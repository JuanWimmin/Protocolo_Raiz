package com.raiz.app.ui.deferred

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.deferred.ReceivedSobreRecord
import com.raiz.app.data.deferred.RecibidoCheck
import com.raiz.app.data.deferred.SobreCodec
import com.raiz.app.data.deferred.SobreInbox
import com.raiz.app.data.deferred.SobreSettler
import com.raiz.app.data.network.ConnectivityMonitor
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de "Recibir sobre": pegar (o escanear) → verificando → resultado. */
sealed interface RecibirSobreUiState {

    /**
     * Sin sobre en el buzón: campo para pegar el texto del sobre. [aviso] es el error inline del último intento.
     * [liquidador] decide qué se le promete a quien pega: una wallet semilla liquida sola al tener señal; una passkey o
     * en modo demo solo guarda el sobre y lo liquida la pasarela web.
     */
    data class Pegar(
        val liquidador: SobreSettler.Liquidador,
        val texto: String = "",
        val sinSenal: Boolean = false,
        val aviso: String? = null,
    ) : RecibirSobreUiState

    data object Verificando : RecibirSobreUiState

    /**
     * Veredicto de [SobreSettler.receive] y lo necesario para dibujarlo.
     * [aviso] es un mensaje de un solo uso (Toast) tras "Liquidar ahora"; [pendientePasarela] es un efecto de un solo
     * uso: (texto del sobre que se copia al portapapeles, URL de la pasarela web), el mismo orden que usa Cobros.
     */
    data class Resultado(
        val check: RecibidoCheck,
        val liquidador: SobreSettler.Liquidador,
        val sinSenal: Boolean,
        val liquidando: Boolean = false,
        val aviso: String? = null,
        val pendientePasarela: Pair<String, String>? = null,
    ) : RecibirSobreUiState {
        /** El registro del sobre (nuevo o ya guardado), o null si se rechazó. */
        val record: ReceivedSobreRecord?
            get() = when (val c = check) {
                is RecibidoCheck.Aceptado -> c.record
                is RecibidoCheck.YaRegistrado -> c.record
                is RecibidoCheck.Rechazado -> null
            }
    }
}

/**
 * Recibir un sobre de otro teléfono (escaneado en Inicio o pegado aquí), para cualquier wallet: el comercio destinatario
 * (COBRO) o un tercero que lo lleva (PORTADOR). El texto llega por [SobreInbox]; la verificación, el guardado y la
 * liquidación en segundo plano son del [SobreSettler].
 */
@HiltViewModel
class RecibirSobreViewModel @Inject constructor(
    private val settler: SobreSettler,
    private val inbox: SobreInbox,
    private val connectivity: ConnectivityMonitor,
    private val walletManager: WalletManager,
) : ViewModel() {

    private val _state = MutableStateFlow<RecibirSobreUiState>(pegar())
    val state: StateFlow<RecibirSobreUiState> = _state.asStateFlow()

    init {
        observarConectividad()
        observarRegistro()
        val texto = inbox.take()
        if (texto != null) verificar(texto)
    }

    /** Mantiene al día "sin señal": cambia el texto honesto y deshabilita "Liquidar ahora". */
    private fun observarConectividad() {
        viewModelScope.launch {
            connectivity.offline.distinctUntilChanged().collect { offline ->
                _state.update { s ->
                    when (s) {
                        is RecibirSobreUiState.Pegar -> s.copy(sinSenal = offline)
                        is RecibirSobreUiState.Resultado -> s.copy(sinSenal = offline)
                        RecibirSobreUiState.Verificando -> s
                    }
                }
            }
        }
    }

    /** El registro mostrado se refresca solo con el vivo del almacén (p. ej. pasa a CONFIRMADO al liquidarse). */
    private fun observarRegistro() {
        viewModelScope.launch {
            settler.receivedFlow.collect { lista ->
                _state.update { s ->
                    if (s !is RecibirSobreUiState.Resultado) return@update s
                    val mostrado = s.record ?: return@update s
                    val vivo = lista.firstOrNull { it.id == mostrado.id } ?: return@update s
                    if (vivo == mostrado) s else s.copy(check = s.check.conRegistro(vivo))
                }
            }
        }
    }

    fun onTexto(t: String) {
        _state.update { s ->
            if (s is RecibirSobreUiState.Pegar) s.copy(texto = t, aviso = null) else s
        }
    }

    /** Vuelve a "pegar" (tras un rechazo, para probar con otro sobre). */
    fun reiniciar() {
        _state.value = pegar()
    }

    /** Estado "pegar" con lo que hoy vale para esta wallet (quién liquida) y para la red (sin señal). */
    private fun pegar(texto: String = "", aviso: String? = null): RecibirSobreUiState.Pegar =
        RecibirSobreUiState.Pegar(
            liquidador = settler.liquidador(),
            texto = texto,
            sinSenal = connectivity.isOffline(),
            aviso = aviso,
        )

    /** Verifica sin red, guarda y (con red y wallet semilla) deja que el teléfono lo liquide en segundo plano. */
    fun verificar(texto: String) {
        if (_state.value is RecibirSobreUiState.Verificando) return
        val limpio = extraerSobre(texto)
        if (limpio == null || !SobreCodec.isSobre(limpio)) {
            _state.value = pegar(texto = texto, aviso = AVISO_NO_ES_SOBRE)
            return
        }
        _state.value = RecibirSobreUiState.Verificando
        viewModelScope.launch {
            val check = settler.receive(limpio)
            _state.value = RecibirSobreUiState.Resultado(
                check = conRegistroVivo(check),
                liquidador = settler.liquidador(),
                sinSenal = connectivity.isOffline(),
            )
        }
    }

    /** "Liquidar ahora": envío manual (tope de fee de 100 XLM, sin backoff). Exige red. */
    fun liquidarAhora() {
        val s = _state.value as? RecibirSobreUiState.Resultado ?: return
        val record = s.record ?: return
        if (s.liquidando) return
        _state.update { cur ->
            if (cur is RecibirSobreUiState.Resultado) cur.copy(liquidando = true, aviso = null) else cur
        }
        viewModelScope.launch {
            val tras = try {
                DeferredLabels.trasLiquidar(settler.settleReceived(record.id, manual = true), record.sobreQr)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Recibir sobre: liquidar falló (${e.javaClass.simpleName})")
                TrasLiquidar(MENSAJE_FALLO_LIQUIDAR, null)
            }
            _state.update { cur ->
                if (cur is RecibirSobreUiState.Resultado) {
                    cur.copy(liquidando = false, aviso = tras.aviso, pendientePasarela = tras.pasarela)
                } else {
                    cur
                }
            }
        }
    }

    /** Wallet passkey/demo: copia el sobre y abre la pasarela web (el efecto lo ejecuta la pantalla). */
    fun abrirPasarela() {
        val record = (_state.value as? RecibirSobreUiState.Resultado)?.record ?: return
        _state.update { cur ->
            if (cur is RecibirSobreUiState.Resultado) {
                cur.copy(pendientePasarela = record.sobreQr to settler.pasarelaUrl(record.sobreQr))
            } else {
                cur
            }
        }
    }

    fun consumirAviso() {
        _state.update { cur ->
            if (cur is RecibirSobreUiState.Resultado && cur.aviso != null) cur.copy(aviso = null) else cur
        }
    }

    fun consumirPasarela() {
        _state.update { cur ->
            if (cur is RecibirSobreUiState.Resultado && cur.pendientePasarela != null) {
                cur.copy(pendientePasarela = null)
            } else {
                cur
            }
        }
    }

    /**
     * Tras [SobreSettler.receive] la liquidación en segundo plano pudo adelantarse al veredicto: se muestra el registro
     * VIVO de esta wallet, no la copia con la que nació.
     */
    private fun conRegistroVivo(check: RecibidoCheck): RecibidoCheck {
        val owner = walletManager.currentAccountId()
        val record = when (check) {
            is RecibidoCheck.Aceptado -> check.record
            is RecibidoCheck.YaRegistrado -> check.record
            is RecibidoCheck.Rechazado -> return check
        }
        val vivo = settler.receivedFor(owner).firstOrNull { it.id == record.id } ?: return check
        return check.conRegistro(vivo)
    }

    private fun RecibidoCheck.conRegistro(r: ReceivedSobreRecord): RecibidoCheck = when (this) {
        is RecibidoCheck.Aceptado -> RecibidoCheck.Aceptado(r)
        is RecibidoCheck.YaRegistrado -> RecibidoCheck.YaRegistrado(r)
        is RecibidoCheck.Rechazado -> this
    }

    /**
     * El sobre empieza en `raiz:sobre:1:`: lo pegado desde un chat puede traer texto delante ("Mira: raiz:sobre:1:…").
     * La validez la decide [SobreSettler.receive] (firma, árbol, vigencia); esto solo localiza dónde empieza.
     */
    private fun extraerSobre(texto: String): String? {
        val inicio = texto.indexOf(SobreCodec.PREFIX)
        return if (inicio < 0) null else texto.substring(inicio).trim()
    }

    private companion object {
        const val TAG = "RAIZ"
        const val AVISO_NO_ES_SOBRE = "No es un sobre RAÍZ"
        const val MENSAJE_FALLO_LIQUIDAR = "No se pudo liquidar el sobre. Inténtalo de nuevo."
    }
}
