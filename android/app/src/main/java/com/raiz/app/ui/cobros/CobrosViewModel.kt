package com.raiz.app.ui.cobros

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.deferred.ReceivedSobrePolicy
import com.raiz.app.data.deferred.ReceivedSobreRecord
import com.raiz.app.data.deferred.ReceivedSobreVerifier
import com.raiz.app.data.deferred.RecibidoCheck
import com.raiz.app.data.deferred.RecibidoStatus
import com.raiz.app.data.deferred.RolRecibido
import com.raiz.app.data.deferred.SobreCodec
import com.raiz.app.data.deferred.SobreSettler
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.data.network.ConnectivityMonitor
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * Veredicto de recibir un sobre, tal como lo muestra la pantalla (espejo de [RecibidoCheck]).
 */
sealed interface VeredictoUi {
    /** Pasó todas las comprobaciones y quedó guardado (EN_TRANSITO). */
    data class Aceptado(val record: ReceivedSobreRecord) : VeredictoUi

    /** Ya estaba guardado: no se duplica. [record] es el registro vigente. */
    data class YaRegistrado(val record: ReceivedSobreRecord) : VeredictoUi

    /** No se acepta: [motivo] va a pantalla. El sobre NO se guardó. */
    data class Rechazado(val motivo: String) : VeredictoUi
}

/**
 * Estado de CobrosScreen.
 *
 * [accountId] es la Stellar address del comerciante — se usa para generar el QR.
 * [ultimosCobros] son los últimos pagos ENTRANTES (isOutgoing = false).
 * [totalReceivedStroops] suma de todos los cobros cargados.
 * [montoCobro] texto USDC que teclea el comerciante (vacío = cobro abierto).
 * [montoStroops] conversión de [montoCobro] a stroops; 0 = monto abierto.
 * [qrContent] contenido del QR: incluye monto si se especificó, si no solo la dirección.
 *
 * [loading] y [error] son SOLO de la sección de ventas: sin señal las ventas no cargan, pero el
 * cobro con sobres sí funciona, así que la pantalla se pinta siempre.
 *
 * Cobro sin señal (sobres recibidos de otro teléfono):
 * - [recibidos]: los de ESTA wallet, EN_TRANSITO primero y luego por fecha de recepción (más nuevo primero).
 * - [veredicto]: resultado de escanear/pegar un sobre; se muestra en un diálogo hasta descartarlo.
 * - [verificando]: se está verificando un sobre (con red espera al ledger real, hasta ~6 s).
 * - [sinSenal]: no hay red (o se forzó "sin señal" en debug).
 * - [liquidando]: ids con un "Liquidar ahora" en curso.
 * - [topeStroops]: monto máximo que se acepta de un sobre sin verificarlo en la red.
 * - [liquidadorMotivo]: null = esta wallet envía desde la app (semilla); si no, por qué NO puede (usa la pasarela).
 * - [aviso]: evento de un disparo (Toast). La pantalla lo consume con [CobrosViewModel.consumirAviso].
 * - [pendientePasarela]: (sobre, url) de un solo disparo: la pantalla copia el sobre y abre la pasarela.
 * - [detalleId]: sobre cuyo detalle está abierto.
 */
data class CobrosUiState(
    val accountId: String = "",
    val totalReceivedStroops: Long = 0L,
    val cobroCount: Int = 0,
    val ultimosCobros: List<PaymentRecord> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    // Orden de cobro por monto fijo
    val montoCobro: String = "",    // texto tal como lo escribe el comerciante (USDC)
    val montoStroops: Long = 0L,    // 0 = monto abierto; >0 = orden de cobro fija
    // Cobro sin señal
    val recibidos: List<ReceivedSobreRecord> = emptyList(),
    val veredicto: VeredictoUi? = null,
    val verificando: Boolean = false,
    val sinSenal: Boolean = false,
    val liquidando: Set<String> = emptySet(),
    val topeStroops: Long = ReceivedSobreVerifier.TOPE_DEFAULT_STROOPS,
    val liquidadorMotivo: String? = null,
    val aviso: String? = null,
    val pendientePasarela: Pair<String, String>? = null,
    val detalleId: String? = null,
) {
    /**
     * Contenido que se codifica en el QR.
     *
     * - Con monto: `raiz:pay?to=<address>&amount=<stroops>` (orden de cobro fija)
     * - Sin monto: solo la Stellar address (cobro abierto — comportamiento anterior)
     */
    val qrContent: String
        get() = if (montoStroops > 0L && accountId.isNotBlank())
            "raiz:pay?to=$accountId&amount=$montoStroops"
        else
            accountId

    /** Sobres recibidos que siguen EN_TRANSITO (promesas firmadas aún sin comprobante). */
    val enTransito: Int
        get() = recibidos.count { it.pending }
}

/**
 * ViewModel de CobrosScreen — pantalla exclusiva del rol MERCHANT.
 *
 * Lee las VENTAS RECIBIDAS vía [SorobanClient.tourPaymentEvents] (eventos
 * `payment` del Pool donde este comerciante es el destinatario) y filtra los
 * entrantes. Horizon NO sirve aquí: los pagos a comercio son llamadas Soroban
 * (no pagos clásicos), y un comerciante passkey es un contrato C… que Horizon
 * tampoco indexa — por eso antes salía siempre 0.
 *
 * Además es el COBRADOR del pago diferido: recibe sobres de otro teléfono (escaneados o pegados), los
 * verifica sin red y los guarda vía [SobreSettler.receive]; el [SobreSettler] los liquida solo al haber
 * señal y aquí solo se observan ([SobreSettler.receivedFlow]) y se ofrece "Liquidar ahora".
 */
@HiltViewModel
class CobrosViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val settler: SobreSettler,
    private val connectivity: ConnectivityMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(estadoInicial())
    val state: StateFlow<CobrosUiState> = _state.asStateFlow()

    /** Carga de ventas en curso: una recarga cancela la anterior para que no se pisen. */
    private var ventasJob: Job? = null

    init {
        observarRecibidos()
        observarConectividad()
        load()
    }

    private fun estadoInicial(): CobrosUiState {
        val accountId = walletManager.currentAccountId().orEmpty()
        val tieneWallet = accountId.isNotBlank()
        return CobrosUiState(
            accountId = accountId,
            recibidos = if (tieneWallet) ordenar(settler.receivedFor(accountId)) else emptyList(),
            sinSenal = connectivity.isOffline(),
            topeStroops = if (tieneWallet) {
                settler.topeStroops(accountId)
            } else {
                ReceivedSobreVerifier.TOPE_DEFAULT_STROOPS
            },
            liquidadorMotivo = motivoLiquidador(),
        )
    }

    /**
     * Refresca la pantalla completa: vence por estimación local lo que ya no vale, deja que el [SobreSettler] consulte y
     * envíe lo pendiente (si hay red) y recarga las ventas. Sin red no deja la pantalla inutilizable: la
     * sección de ventas muestra su error en línea y el resto sigue funcionando.
     */
    fun load() {
        val accountId = walletManager.currentAccountId()
        if (accountId == null) {
            _state.update { it.copy(loading = false, error = MENSAJE_SIN_WALLET) }
            return
        }
        _state.update {
            it.copy(
                accountId = accountId,
                topeStroops = settler.topeStroops(accountId),
                liquidadorMotivo = motivoLiquidador(),
            )
        }
        settler.refreshStatuses()
        viewModelScope.launch { runCatching { settler.settlePending(accountId) } }
        cargarVentas(accountId)
    }

    /**
     * Al volver a la pantalla (p. ej. de la pasarela web o del escáner) la red pudo haber cerrado sobres:
     * sin red solo vence por estimación local; con red el [SobreSettler] consulta y envía lo pendiente.
     */
    fun alReanudar() {
        val accountId = walletManager.currentAccountId() ?: return
        settler.refreshStatuses()
        viewModelScope.launch { runCatching { settler.settlePending(accountId) } }
    }

    private fun cargarVentas(accountId: String) {
        ventasJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        ventasJob = viewModelScope.launch {
            // Sin señal no hay nada que esperar: se dice en línea y el cobro sin señal sigue disponible.
            if (connectivity.isOffline()) {
                _state.update { it.copy(loading = false, error = MENSAJE_VENTAS_SIN_SENAL) }
                return@launch
            }
            val resultado = sorobanClient.tourPaymentEvents(accountId)
            // Si otra carga reemplazó a esta mientras esperaba la red, su resultado ya no vale.
            ensureActive()
            when (val r = resultado) {
                is RaizResult.Success -> {
                    // Ventas recibidas = eventos del Pool donde este comerciante es
                    // el destinatario (isOutgoing = false). Los salientes (compras
                    // propias del comerciante) se excluyen.
                    val incoming = r.data.filter { !it.isOutgoing }
                    val total = incoming.sumOf { it.amountStroops }
                    Log.i(TAG, "Ventas recibidas: ${incoming.size}, total=$total stroops")
                    _state.update {
                        it.copy(
                            loading = false,
                            ultimosCobros = incoming,
                            cobroCount = incoming.size,
                            totalReceivedStroops = total,
                        )
                    }
                }
                is RaizResult.Error -> _state.update {
                    it.copy(loading = false, error = r.message)
                }
            }
        }
    }

    // ── Observadores ─────────────────────────────────────────────────────────

    /** Los sobres recibidos de ESTA wallet (el almacén guarda los de todas las wallets del teléfono). */
    private fun observarRecibidos() {
        viewModelScope.launch {
            settler.receivedFlow.collect { registros ->
                val owner = walletManager.currentAccountId()
                val propios = if (owner == null) emptyList() else registros.filter { it.owner == owner }
                _state.update { it.copy(recibidos = ordenar(propios)) }
            }
        }
    }

    /**
     * "Sin señal" por conectividad (patrón de PayViewModel). En el flanco a "con señal" lanza una pasada
     * del [SobreSettler] (consulta y envía lo pendiente) y, si las ventas habían fallado, las recarga.
     */
    @OptIn(FlowPreview::class)
    private fun observarConectividad() {
        viewModelScope.launch {
            // Se parte del estado con que nació la pantalla: si nació sin señal y la señal vuelve, es un flanco.
            var estabaSinSenal = _state.value.sinSenal
            connectivity.offline.debounce(DEBOUNCE_MS).collect { offline ->
                val veniaSinSenal = estabaSinSenal
                estabaSinSenal = offline
                _state.update { it.copy(sinSenal = offline) }
                if (!offline && veniaSinSenal) {
                    viewModelScope.launch { alVolverLaSenal() }
                }
            }
        }
    }

    private suspend fun alVolverLaSenal() {
        val accountId = walletManager.currentAccountId() ?: return
        if (_state.value.error != null) cargarVentas(accountId)
        runCatching { settler.settlePending(accountId) }
    }

    // ── Orden de cobro por monto fijo ────────────────────────────────────────

    /**
     * Actualiza el monto que quiere cobrar el comerciante.
     *
     * Sanea el texto (solo dígitos + un punto decimal), convierte a stroops y
     * actualiza el estado. El QR en la UI se regenera automáticamente vía
     * [CobrosUiState.qrContent].
     *
     * @param texto Texto tal como lo escribe el usuario en el TextField (USDC).
     */
    fun onMontoCambiado(texto: String) {
        val sanitizado = sanitizarMonto(texto)
        val usdc = sanitizado.toDoubleOrNull() ?: 0.0
        val stroops = (usdc * RaizConstants.USDC_STROOPS_PER_UNIT).toLong()
        _state.update { it.copy(montoCobro = sanitizado, montoStroops = stroops) }
    }

    /**
     * Filtra el texto a solo dígitos y un punto decimal.
     * Garantiza que no haya más de un separador decimal. La coma decimal de los teclados en
     * español vale como punto: sin esto "2,5" se leía como "25".
     */
    private fun sanitizarMonto(texto: String): String {
        val filtrado = texto.replace(',', '.').filter { it.isDigit() || it == '.' }
        val partes = filtrado.split('.')
        return when {
            partes.size <= 1 -> filtrado
            else -> "${partes[0]}.${partes.drop(1).joinToString("")}"
        }
    }

    // ── Cobro sin señal: recibir un sobre ────────────────────────────────────

    /**
     * Un sobre llegó por el escáner o el portapapeles. Si no es un sobre RAÍZ solo se avisa; si lo es,
     * [SobreSettler.receive] lo verifica sin red (firma, árbol, nonce, tope y vigencia), lo guarda y,
     * con red y wallet semilla, empieza a liquidarlo en segundo plano. El resultado queda en
     * [CobrosUiState.veredicto]. Si el sobre se aceptó, ya está guardado en el teléfono.
     */
    fun onSobreTexto(raw: String) {
        val texto = raw.trim()
        if (texto.isEmpty()) return
        if (!SobreCodec.isSobre(texto)) {
            avisar("No es un sobre RAÍZ")
            return
        }
        if (_state.value.verificando) return
        _state.update { it.copy(verificando = true) }
        viewModelScope.launch {
            val veredicto = settler.receive(texto).aUi()
            _state.update { it.copy(verificando = false, veredicto = veredicto) }
        }
    }

    /** "Pegar sobre": [texto] es lo que había en el portapapeles (null o en blanco = vacío). */
    fun onPegarSobre(texto: String?) {
        if (texto.isNullOrBlank()) avisar("El portapapeles está vacío") else onSobreTexto(texto)
    }

    fun descartarVeredicto() {
        _state.update { it.copy(veredicto = null) }
    }

    /** Abre (id) o cierra (null) el detalle de un sobre recibido. */
    fun abrirDetalle(id: String?) {
        _state.update { it.copy(detalleId = id) }
    }

    // ── Cobro sin señal: liquidar ────────────────────────────────────────────

    /**
     * "Liquidar ahora" (envío manual): salta el backoff y admite un fee de red mayor que el del envío
     * automático. Exige red real. El resultado se traduce a un [CobrosUiState.aviso]; el registro se
     * actualiza solo por [SobreSettler.receivedFlow].
     */
    fun liquidarAhora(id: String) {
        if (id in _state.value.liquidando) return
        _state.update { it.copy(liquidando = it.liquidando + id) }
        viewModelScope.launch {
            try {
                aplicarResultado(id, settler.settleReceived(id, manual = true))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Cobros: liquidar un sobre falló (${e.javaClass.simpleName})")
                avisar("No se pudo liquidar el sobre. Inténtalo de nuevo.")
            } finally {
                _state.update { it.copy(liquidando = it.liquidando - id) }
            }
        }
    }

    private fun aplicarResultado(id: String, resultado: SobreSettler.Resultado) {
        when (resultado) {
            is SobreSettler.Resultado.Actualizado -> avisar(avisoDeRegistro(resultado.recibido))
            is SobreSettler.Resultado.NoEnviable -> {
                val sobre = buscar(id)?.sobreQr
                _state.update {
                    it.copy(
                        aviso = resultado.motivo,
                        pendientePasarela = sobre?.let { s -> s to (resultado.pasarelaUrl ?: settler.pasarelaUrl(s)) },
                    )
                }
            }
            SobreSettler.Resultado.Ocupado -> avisar("Ya se está enviando")
            SobreSettler.Resultado.SinSenal -> avisar("Sin señal: se enviará solo al volver la red")
            is SobreSettler.Resultado.FeeAlta -> avisar(
                "La red cobra ${SobreSettler.formatXlm(resultado.feeStroops)} XLM por este envío, " +
                    "más del máximo permitido (${SobreSettler.formatXlm(resultado.maxFeeStroops)} XLM).",
            )
        }
    }

    /** Qué le dice el Toast al usuario según cómo quedó el registro VIVO tras el intento. */
    private fun avisoDeRegistro(r: ReceivedSobreRecord?): String {
        if (r == null) return SobreSettler.MOTIVO_SOBRE_AUSENTE
        val ultimoEnvio = r.lastSendMotivo?.takeIf { it.isNotBlank() }
        return when {
            // Con comprobante, y sin afirmar un envío que quizá no ocurrió en este toque (el registro pudo cerrarse antes).
            !r.txHash.isNullOrBlank() ->
                if (r.rol == RolRecibido.COBRO) "Cobrado: la red liquidó el pago" else "Liquidado en la red"
            r.status == RecibidoStatus.RECHAZADO -> "Sobre rechazado: ${r.motivo ?: "la red lo rechazó"}"
            r.status == RecibidoStatus.EXPIRADO -> r.motivo ?: "El sobre expiró sin liquidarse"
            r.motivo == ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE ->
                "La red ya consumió el nonce: buscando el comprobante"
            r.inFlightHash != null -> "Enviado: esperando que la red lo confirme"
            ultimoEnvio != null -> ultimoEnvio
            else -> "Sigue en tránsito"
        }
    }

    // ── Pasarela web y avisos ────────────────────────────────────────────────

    /**
     * Una wallet passkey (o sin semilla) no envía desde la app: la pantalla copia el sobre y abre la pasarela
     * web ([CobrosUiState.pendientePasarela], de un solo disparo).
     */
    fun abrirPasarela(id: String) {
        val sobre = buscar(id)?.sobreQr
        if (sobre == null) {
            avisar(SobreSettler.MOTIVO_SOBRE_AUSENTE)
            return
        }
        _state.update { it.copy(pendientePasarela = sobre to settler.pasarelaUrl(sobre)) }
    }

    fun consumirAviso() {
        _state.update { it.copy(aviso = null) }
    }

    fun consumirPasarela() {
        _state.update { it.copy(pendientePasarela = null) }
    }

    // ── Tope por pago sin señal ──────────────────────────────────────────────

    /**
     * Guarda el tope que escribió el comerciante (USDC). Solo cuenta en los sobres donde él es el comercio
     * destinatario. 0 = no aceptar cobros sin señal.
     */
    fun onTopeTexto(usdcTexto: String) {
        val accountId = walletManager.currentAccountId() ?: return
        val stroops = textoAStroops(sanitizarMonto(usdcTexto))
        if (stroops == null) {
            avisar("Escribe un monto válido en USDC")
            return
        }
        settler.setTopeStroops(accountId, stroops)
        val vigente = settler.topeStroops(accountId)
        _state.update { it.copy(topeStroops = vigente, aviso = "Tope guardado: ${vigente.formatUsdc()}") }
    }

    /** Texto USDC ya saneado → stroops exactos (sin pasar por Double); null si no es un número o no cabe en un Long. */
    private fun textoAStroops(texto: String): Long? = runCatching {
        BigDecimal(texto)
            .movePointRight(RaizConstants.USDC_DECIMALS)
            .setScale(0, RoundingMode.DOWN)
            .longValueExact()
    }.getOrNull()

    // ── Auxiliares ───────────────────────────────────────────────────────────

    private fun avisar(texto: String) {
        _state.update { it.copy(aviso = texto) }
    }

    /** El registro vigente de [id]: primero el de la pantalla; si no, el del almacén. */
    private fun buscar(id: String): ReceivedSobreRecord? =
        _state.value.recibidos.firstOrNull { it.id == id }
            ?: settler.receivedFor(walletManager.currentAccountId()).firstOrNull { it.id == id }

    /** null = esta wallet envía desde la app; si no, el motivo por el que usa la pasarela web. */
    private fun motivoLiquidador(): String? =
        (settler.liquidador() as? SobreSettler.Liquidador.Pasarela)?.motivo

    /** EN_TRANSITO primero y luego por fecha de recepción, el más nuevo arriba. */
    private fun ordenar(lista: List<ReceivedSobreRecord>): List<ReceivedSobreRecord> =
        lista.sortedWith(
            compareByDescending<ReceivedSobreRecord> { it.pending }.thenByDescending { it.receivedAtMs },
        )

    private fun RecibidoCheck.aUi(): VeredictoUi = when (this) {
        is RecibidoCheck.Aceptado -> VeredictoUi.Aceptado(record)
        is RecibidoCheck.YaRegistrado -> VeredictoUi.YaRegistrado(record)
        is RecibidoCheck.Rechazado -> VeredictoUi.Rechazado(motivo)
    }

    private companion object {
        const val TAG = "RAIZ"
        const val DEBOUNCE_MS = 300L
        const val MENSAJE_SIN_WALLET = "No hay una wallet activa."
        const val MENSAJE_VENTAS_SIN_SENAL = "Se cargan cuando vuelva la red; mientras tanto puedes cobrar con sobres."
    }
}
