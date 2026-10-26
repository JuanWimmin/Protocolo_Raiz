package com.raiz.app.data.deferred

import android.util.Log
import com.raiz.app.data.network.ConnectivityMonitor
import com.raiz.app.data.stellar.DeploymentsLoader
import com.raiz.app.data.stellar.DepositPlan
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.TxJournal
import com.raiz.app.data.stellar.WalletManager
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.xdr.SorobanAuthorizationEntryXdr
import com.soneso.stellar.sdk.xdr.fromXdrBase64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquestador ÚNICO que LIQUIDA sobres de pago diferido: envía a la red la auth firmada del pagador como una
 * transacción cuya fuente es la wallet de esta app, que paga el fee (decisión D1: la mecánica de envío vive una
 * sola vez para los sobres propios y los recibidos).
 *
 * Qué hace: con red, cierra solos los sobres de esta wallet ([settlePending]) — los que ella FIRMÓ (propios, lado
 * pagador) y los que RECIBIÓ de otro teléfono (lado cobrador o portador, ver [receive]): primero CONSULTA (nonce
 * consumido → cobrado; vencido → expirado) y solo después ENVÍA los que sigan pendientes. "Liquidar ahora"
 * ([settleOwn] / [settleReceived] con `manual = true`) hace lo mismo para un sobre concreto, saltándose el backoff y
 * el tope de intentos.
 *
 * Reglas que no se rompen (revisión adversarial):
 * - Un sobre se da por cobrado solo con prueba de la red: un SUCCESS con esta auth ([SobreSubmit.Liquidado]) o,
 *   en un sobre PROPIO, el nonce consumido (D2). En un sobre RECIBIDO el nonce consumido NO basta: exige un hash
 *   cuya auth sea ESTE sobre ([ReceivedSobrePolicy]). `ExistingValue` por sí solo NUNCA significa "cobrado".
 * - La verdad on-chain manda (D3): una transición parte SIEMPRE del registro vivo ([DeferredStore.mutate]),
 *   nunca de una copia capturada antes del envío; RECHAZADO solo por motivos definitivos (`InvalidAction`);
 *   fondos, trustline, comercio, fees, red y demás aplazan con motivo visible y el cierre lo decide la red.
 * - Diario pre-envío (D6): el hash de la tx firmada se anota (con `commit()`) ANTES de enviarla; si el proceso
 *   muere, la próxima pasada la resuelve por hash con la HORA DE LA RED (nunca el reloj del teléfono) antes de
 *   construir otra. Un mutex por sobre (tryLock → [Resultado.Ocupado]) y uno por cuenta fuente (dos envíos de la
 *   misma cuenta en paralelo darían `txBAD_SEQ`).
 * - Tope de fee (D7): la simulación es gratis; si cotiza por encima del tope no se envía ([Resultado.FeeAlta]).
 * - Conectividad (D9): el envío automático respeta [ConnectivityMonitor.isOnline] (incluido el "sin señal" forzado
 *   de debug). Solo cuentan como intento ([DeferredRecord.sendAttempts]) los envíos que LLEGARON a la red.
 * - Solo se firma con la semilla de la propia wallet ([WalletManager.seedKeyPairOrNull]); NUNCA con la clave
 *   demo. Una wallet passkey o sin semilla no envía desde la app: usa la pasarela web ([Liquidador.Pasarela]).
 *
 * [ledgerEstimator] y [deploymentsLoader] alimentan [receive] (vigencia sin red y verificación del árbol); la lógica
 * pura vive en [ReceivedSobreVerifier] y [ReceivedSobrePolicy], que se prueban en JVM.
 *
 * Logs: direcciones, ids y hashes; nunca XDR ni claves.
 */
@Singleton
class SobreSettler @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val deferredStore: DeferredStore,
    private val deferredPaymentManager: DeferredPaymentManager,
    private val connectivity: ConnectivityMonitor,
    private val ledgerEstimator: LedgerEstimator,
    private val deploymentsLoader: DeploymentsLoader,
    private val receivedStore: ReceivedSobreStore,
) {

    /** Quién puede enviar el sobre desde ESTA app. */
    sealed interface Liquidador {
        /** Wallet semilla: la app firma y envía la transacción. */
        data object DesdeLaApp : Liquidador

        /** No se envía desde la app; la UI copia el sobre y abre [pasarelaUrl]. [motivo] va a pantalla. */
        data class Pasarela(val motivo: String) : Liquidador
    }

    /** Resultado de un intento de liquidar un sobre concreto. */
    sealed interface Resultado {
        /** El sobre quedó como indican [propio] / [recibido] (el registro VIVO tras el intento; puede seguir pendiente). */
        data class Actualizado(val propio: DeferredRecord?, val recibido: ReceivedSobreRecord?) : Resultado

        /** No se puede enviar desde esta app; [pasarelaUrl] abre la pasarela web con el sobre (null si no aplica). */
        data class NoEnviable(val motivo: String, val pasarelaUrl: String?) : Resultado

        /** Otro envío del mismo sobre está en curso. */
        data object Ocupado : Resultado

        /** Sin red (o "sin señal" forzado en debug para el envío automático): no se pudo simular ni enviar. */
        data object SinSenal : Resultado

        /** La red cobraría [feeStroops] (más que el tope [maxFeeStroops]): no se envió. */
        data class FeeAlta(val feeStroops: Long, val maxFeeStroops: Long) : Resultado
    }

    /** Qué pasó en una pasada de [settlePending] con los sobres que estaban pendientes. */
    data class Resumen(
        val confirmados: Int,
        val expirados: Int,
        val rechazados: Int,
        /** Intentos que no cerraron el sobre y quedaron aplazados con motivo. */
        val aplazados: Int,
        /** Transacciones que salieron a la red en esta pasada. */
        val enviados: Int,
    )

    companion object {
        const val PASARELA_URL = "https://raizapp.xyz/pasarela.html"

        /** Tope de fee del envío automático de un sobre PROPIO: 100 XLM (es su propio pago). */
        const val AUTO_MAX_FEE_PROPIOS = 1_000_000_000L

        /** Tope del automático de un sobre RECIBIDO: 2 XLM (un desconocido no puede drenar XLM del comercio). */
        const val AUTO_MAX_FEE_RECIBIDOS = 20_000_000L

        /** Tope de "Liquidar ahora" (el usuario confirma): 100 XLM. */
        const val MANUAL_MAX_FEE = 1_000_000_000L

        /** El pagador espera esto UNA vez por pasada si algún sobre suyo está ENTREGADO: el cobrador suele liquidarlo antes. */
        const val PAYER_JITTER_MS = 10_000L

        const val MOTIVO_PASSKEY = "Una wallet passkey no puede enviar la transacción desde la app: usa la pasarela web."
        const val MOTIVO_SIN_WALLET = "En modo demo no se liquida desde la app: usa la pasarela web."
        const val MOTIVO_WALLET_DISTINTA = "La wallet activa no es la dueña de este sobre."
        const val AVISO_FEE =
            "El primer pago de un turista en el barrio puede costar ≈ 30 XLM de red en testnet; los paga la cuenta que envía."

        const val MOTIVO_SOBRE_AUSENTE = "Este sobre ya no está guardado en el teléfono."
        const val MOTIVO_SOBRE_DANADO = "El sobre guardado en el teléfono está dañado: usa la pasarela web con la copia que enviaste."
        const val MOTIVO_NO_COMPROBADO = "El sobre venció, pero no se pudo comprobar si se cobró; se volverá a intentar."
        const val MOTIVO_NO_GUARDADO = "No pudimos guardar el sobre en el teléfono; no lo des por recibido."
        const val MOTIVO_OTRA_WALLET = "Este sobre ya lo recibió otra wallet de este teléfono."
        const val MOTIVO_NO_VERIFICABLE = "No pudimos verificar el sobre; inténtalo de nuevo."

        private const val TAG = "RAIZ"
        private const val ONLINE_DEBOUNCE_MS = 2_000L

        /** Con red, cuánto se espera al ledger real al recibir un sobre; pasado esto se verifica con la estimación. */
        private const val LEDGER_LOOKUP_TIMEOUT_MS = 6_000L

        /** Los ids de sobres recibidos (`<payer>:<nonce>`) no chocan con los propios (nonce decimal), pero se separan igual. */
        private const val RECEIVED_LOCK_PREFIX = "rx:"
        private const val STROOPS_PER_XLM = 10_000_000L

        /** `https://raizapp.xyz/pasarela.html#sobre=<sobre>`: fragmento, no query, para que el sobre no viaje al servidor. */
        fun pasarelaUrlFor(sobreQr: String): String = PASARELA_URL + "#sobre=" + URLEncoder.encode(sobreQr, "UTF-8")

        /** Stroops → XLM con 2 decimales y punto (30 XLM = `300000000` → "30.00"), sin depender del locale. */
        fun formatXlm(stroops: Long): String {
            val whole = stroops / STROOPS_PER_XLM
            val cents = (stroops % STROOPS_PER_XLM) / (STROOPS_PER_XLM / 100)
            return "$whole.${cents.toString().padStart(2, '0')}"
        }

        private val RESUMEN_VACIO = Resumen(confirmados = 0, expirados = 0, rechazados = 0, aplazados = 0, enviados = 0)
    }

    private val started = AtomicBoolean(false)
    private val receiveLock = Mutex()
    private val receiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deployments by lazy { deploymentsLoader.load() }
    private val network: Network
        get() = if (deployments.network == "public") Network.PUBLIC else Network.TESTNET
    private val passLocks = ConcurrentHashMap<String, Mutex>()
    private val idLocks = ConcurrentHashMap<String, Mutex>()
    private val accountLocks = ConcurrentHashMap<String, Mutex>()

    // ── API pública ──────────────────────────────────────────────────────────────

    /**
     * ¿Quién puede enviar desde esta app? Wallet passkey (`C…`) o sin semilla → [Liquidador.Pasarela]; wallet
     * semilla → [Liquidador.DesdeLaApp]. Lectura sin red ni derivar claves: la UI puede llamarla al componer.
     */
    fun liquidador(): Liquidador = when {
        walletManager.isPasskeyWallet() -> Liquidador.Pasarela(MOTIVO_PASSKEY)
        !walletManager.isSeedWallet() -> Liquidador.Pasarela(MOTIVO_SIN_WALLET)
        else -> Liquidador.DesdeLaApp
    }

    /** URL de la pasarela web con el sobre en el fragmento (`#sobre=`). Usa `java.net.URLEncoder` (funciona en JVM). */
    fun pasarelaUrl(sobreQr: String): String = pasarelaUrlFor(sobreQr)

    /**
     * Sin red: marca EXPIRADO lo que la estimación local de ledger da por vencido, en los sobres propios y en los
     * recibidos (no toca los que tienen una tx en vuelo ni, en los recibidos, un nonce ya visto consumido). Un EXPIRADO
     * local —propio o recibido— no es definitivo: con red [settlePending] vuelve a mirar si se cobró (hash del pago) antes
     * de darlo por expirado según la red.
     */
    fun refreshStatuses() {
        deferredPaymentManager.refreshStatuses()
        val estimate = ledgerEstimator.estimate() ?: return
        receivedStore.refreshAll { ReceivedSobrePolicy.refresh(it, estimate) }
    }

    /**
     * Una pasada completa sobre los sobres pendientes de [owner] (por omisión la wallet activa): los PROPIOS (que
     * firmó) y los RECIBIDOS de otro teléfono.
     *  1. CONSULTA: los propios con [DeferredPaymentManager.syncWithNetwork] y los recibidos con la política del
     *     cobrador (nonce consumido sin hash sigue en tránsito; ledger real pasado de `exp` → cobrado o expirado);
     *     cierra los que la red ya resolvió. Incluye los que solo vencieron por estimación local (propios y recibidos:
     *     [DeferredPolicy.needsNetworkCheck], [ReceivedSobrePolicy.needsNetworkCheck]). Lee los pagos del Pool una sola
     *     vez por pagador, desde el ledger donde pudo liquidarse el sobre más antiguo ([SobrePaymentsCache]);
     *  2. si no hay red ([ConnectivityMonitor.isOffline], con el "sin señal" forzado de debug) o la wallet no envía
     *     desde la app ([liquidador]), termina aquí;
     *  3. ENVÍA primero los recibidos que [ReceivedSobrePolicy.shouldAutoSend] autoriza (sin esperar) y después los
     *     propios que [DeferredPolicy.shouldAutoSend] autoriza (ambos reanudan los que tienen una tx en vuelo). Si
     *     algún propio está ENTREGADO espera [PAYER_JITTER_MS] UNA vez (el cobrador suele tenerlo) y vuelve a consultar.
     *
     * Nunca lanza (salvo `CancellationException`). Si ya hay una pasada en curso para [owner] devuelve un resumen vacío.
     */
    suspend fun settlePending(owner: String? = walletManager.currentAccountId()): Resumen =
        withContext(Dispatchers.Default) { settlePendingInternal(owner) }

    /**
     * Liquida UN sobre propio. `manual = true` es "Liquidar ahora": salta el backoff y el tope de intentos (no la
     * simulación ni la red) y admite hasta [MANUAL_MAX_FEE] de fee; exige red real pero no respeta el "sin señal"
     * forzado de debug. `manual = false` es el automático (lo que [settlePending] usa por cada candidato).
     */
    suspend fun settleOwn(id: String, manual: Boolean = false): Resultado =
        withContext(Dispatchers.Default) { settleOwnInternal(id, manual).resultado }

    // ── API: sobres recibidos ────────────────────────────────────────────────────

    /** Sobres recibidos en este teléfono, de todas las wallets; las pantallas filtran con [receivedFor]. */
    val receivedFlow: StateFlow<List<ReceivedSobreRecord>> get() = receivedStore.recordsFlow

    fun receivedFor(owner: String?): List<ReceivedSobreRecord> = receivedStore.recordsFor(owner)

    fun pendingReceivedCount(owner: String?): Int = receivedStore.pendingCount(owner)

    /** Tope por pago sin señal de [owner] en stroops (solo cuenta en el rol COBRO); por omisión 20 USDC. */
    fun topeStroops(owner: String): Long = receivedStore.topeStroops(owner)

    fun setTopeStroops(owner: String, stroops: Long) = receivedStore.setTopeStroops(owner, stroops)

    /**
     * Recibe un sobre de otro teléfono (texto escaneado o pegado). Lo verifica ([ReceivedSobreVerifier]) y, si pasa,
     * lo guarda con `commit()` ANTES de devolver [RecibidoCheck.Aceptado]: la pantalla solo puede decir "recibido"
     * si quedó guardado. Con red y wallet semilla lo liquida enseguida en segundo plano (sin jitter: los recibidos
     * no esperan); si no, queda EN_TRANSITO para la próxima pasada o para la pasarela web ([liquidador]).
     *
     * Recibe la wallet activa ([WalletManager.currentAccountId]); sin wallet propia no se reciben sobres. La vigencia
     * se mide con el ledger real si hay red (hasta [LEDGER_LOOKUP_TIMEOUT_MS]) y, si no, con la estimación del
     * [LedgerEstimator] más un margen que crece con la edad de su ancla. Un COBRO sin ninguna referencia se rechaza:
     * nunca se cobra a ciegas. Nunca lanza (salvo `CancellationException`).
     */
    suspend fun receive(text: String): RecibidoCheck = withContext(Dispatchers.Default) {
        try {
            receiveInternal(text)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "SobreSettler: recibir un sobre falló (${e.javaClass.simpleName})")
            RecibidoCheck.Rechazado(MOTIVO_NO_VERIFICABLE)
        }
    }

    /**
     * Liquida UN sobre recibido. Misma secuencia y garantías que [settleOwn]; la política de veredictos es la del
     * cobrador ([ReceivedSobrePolicy]): "cobrado" solo con un hash cuya auth sea este sobre. El automático admite hasta
     * [AUTO_MAX_FEE_RECIBIDOS] de fee y "Liquidar ahora" (`manual = true`) hasta [MANUAL_MAX_FEE]. Solo envía la
     * wallet dueña del sobre; una passkey o sin semilla recibe [Resultado.NoEnviable] con la URL de la pasarela.
     */
    suspend fun settleReceived(id: String, manual: Boolean = false): Resultado =
        withContext(Dispatchers.Default) { settleReceivedInternal(id, manual).resultado }

    /**
     * Arranca (idempotente) el envío automático al volver la red: cada vez que la conectividad pasa a "con señal"
     * —y al arrancar, si ya la hay— espera [ONLINE_DEBOUNCE_MS] y lanza [settlePending]. La UI lo llama desde
     * `MainActivity` tras desbloquear la app (no desde `RaizApplication`: antes de eso no hay wallet).
     */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            connectivity.offline
                .map { !it }
                .distinctUntilChanged()
                .filter { it }
                .debounce(ONLINE_DEBOUNCE_MS)
                .collect {
                    try {
                        settlePending()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Log.w(TAG, "SobreSettler: la pasada automática falló (${e.javaClass.simpleName})")
                    }
                }
        }
    }

    // ── Pasada ───────────────────────────────────────────────────────────────────

    private suspend fun settlePendingInternal(owner: String?): Resumen {
        if (owner == null) return RESUMEN_VACIO
        val pass = passLocks.computeIfAbsent(owner) { Mutex() }
        if (!pass.tryLock()) return RESUMEN_VACIO
        try {
            // De los propios también se mira el EXPIRADO que fue solo una estimación local: un cobrador pudo liquidarlo
            // justo antes de vencer y la red es la única que lo sabe (si no, `refreshStatuses()` lo enterraba sin remedio).
            val ownBefore = deferredStore.recordsFor(owner)
                .filter { DeferredPolicy.needsNetworkCheck(it) }
                .associate { it.id to it.status }
            // De los recibidos también se mira el EXPIRADO que fue solo una estimación local: la red puede traer un hash.
            val receivedBefore = receivedStore.recordsFor(owner)
                .filter { ReceivedSobrePolicy.needsNetworkCheck(it) }
                .associate { it.id to it.status }
            if (ownBefore.isEmpty() && receivedBefore.isEmpty()) return RESUMEN_VACIO

            // 1. Consultar: la red es la única fuente de verdad.
            val cache = PassCache()
            if (ownBefore.isNotEmpty()) syncQuietly(owner)
            if (receivedBefore.isNotEmpty()) syncReceivedQuietly(owner, cache)

            // 2. Enviar solo con red, wallet semilla y siendo esa la wallet activa.
            var enviados = 0
            var aplazados = 0
            val passStartMs = System.currentTimeMillis()
            val puedeEnviar = connectivity.isOnline() &&
                liquidador() is Liquidador.DesdeLaApp &&
                owner == walletManager.currentAccountId()
            if (puedeEnviar) {
                // Todos los sobres salen de la misma cuenta fuente: con una tx suya aún en vuelo la siguiente
                // reutilizaría su secuencia (txBAD_SEQ), y sin XLM todas fallarían igual. Se sigue en la próxima pasada.
                var detener = false

                // 2a. Recibidos: de inmediato (sin jitter), antes que los propios.
                // Solo si algo toca enviar se pregunta el ledger (el margen hasta `exp` necesita el real).
                var recibidos = receivedCandidates(owner, ledgerHint = null)
                if (recibidos.isNotEmpty()) recibidos = receivedCandidates(owner, cache.ledger())
                for (candidate in recibidos) {
                    if (!connectivity.isOnline()) break // se fue la señal a mitad de la pasada
                    val run = settleReceivedInternal(candidate.id, manual = false)
                    if (run.outcome.salioAlaRed()) enviados++
                    val after = (run.resultado as? Resultado.Actualizado)?.recibido
                    if (after != null && after.pending && after.lastAttemptMs >= passStartMs) aplazados++
                    if (run.outcome.detienePasada()) {
                        detener = true
                        break
                    }
                }

                // 2b. Propios.
                if (!detener) {
                    var candidates = autoCandidates(owner, ledgerHint = null)
                    if (candidates.isNotEmpty()) candidates = autoCandidates(owner, cache.ledger())
                    if (candidates.any { it.status == DeferredStatus.ENTREGADO && it.inFlightHash == null }) {
                        // El cobrador suele tener el sobre: se le da la ventaja y se vuelve a mirar antes de gastar un fee.
                        Log.i(TAG, "SobreSettler: $owner espera ${PAYER_JITTER_MS / 1000} s por si el cobrador ya liquida")
                        delay(PAYER_JITTER_MS)
                        syncQuietly(owner)
                        candidates = autoCandidates(owner, cache.ledger(refresh = true))
                    }
                    for (candidate in candidates) {
                        if (!connectivity.isOnline()) break // se fue la señal a mitad de la pasada
                        val run = settleOwnInternal(candidate.id, manual = false)
                        if (run.outcome.salioAlaRed()) enviados++
                        val after = (run.resultado as? Resultado.Actualizado)?.propio
                        if (after != null && after.pending && after.lastAttemptMs >= passStartMs) aplazados++
                        if (run.outcome.detienePasada()) break
                    }
                }
            }

            // 3. Resumen: qué pasó con los que se miraron al empezar.
            val ownNow = deferredStore.recordsFor(owner).associateBy { it.id }
            val receivedNow = receivedStore.recordsFor(owner).associateBy { it.id }
            var confirmados = 0
            var expirados = 0
            var rechazados = 0
            for ((id, antes) in ownBefore) {
                val ahora = ownNow[id]?.status ?: continue
                if (ahora == antes) continue // un EXPIRADO local que la red confirma como EXPIRADO no es noticia
                when (ahora) {
                    DeferredStatus.CONFIRMADO -> confirmados++
                    DeferredStatus.EXPIRADO -> expirados++
                    DeferredStatus.RECHAZADO -> rechazados++
                    DeferredStatus.FIRMADO, DeferredStatus.ENTREGADO -> Unit
                }
            }
            for ((id, antes) in receivedBefore) {
                val ahora = receivedNow[id]?.status ?: continue
                if (ahora == antes) continue
                when (ahora) {
                    RecibidoStatus.CONFIRMADO -> confirmados++
                    RecibidoStatus.EXPIRADO -> expirados++
                    RecibidoStatus.RECHAZADO -> rechazados++
                    RecibidoStatus.EN_TRANSITO -> Unit
                }
            }
            val resumen = Resumen(confirmados, expirados, rechazados, aplazados, enviados)
            if (resumen != RESUMEN_VACIO) Log.i(TAG, "SobreSettler: pasada de $owner → $resumen")
            return resumen
        } finally {
            pass.unlock()
        }
    }

    /** Pendientes propios que toca enviar ahora, o reanudar si tienen una tx en vuelo (su veredicto se resuelve por hash). */
    private fun autoCandidates(owner: String, ledgerHint: Long?): List<DeferredRecord> {
        val nowMs = System.currentTimeMillis()
        return deferredStore.recordsFor(owner).filter {
            it.pending && (it.inFlightHash != null || DeferredPolicy.shouldAutoSend(it, ledgerHint, nowMs))
        }
    }

    private suspend fun syncQuietly(owner: String) {
        try {
            deferredPaymentManager.syncWithNetwork(owner)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "SobreSettler: no se pudo sincronizar los sobres de $owner (${e.javaClass.simpleName})")
        }
    }

    /** Pendientes recibidos que toca enviar ahora, o reanudar si tienen una tx en vuelo (su veredicto se resuelve por hash). */
    private fun receivedCandidates(owner: String, ledgerHint: Long?): List<ReceivedSobreRecord> {
        val nowMs = System.currentTimeMillis()
        return receivedStore.recordsFor(owner).filter {
            it.pending && (it.inFlightHash != null || ReceivedSobrePolicy.shouldAutoSend(it, ledgerHint, nowMs))
        }
    }

    private suspend fun syncReceivedQuietly(owner: String, cache: PassCache) {
        try {
            syncReceived(owner, cache)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "SobreSettler: no se pudo sincronizar los sobres recibidos de $owner (${e.javaClass.simpleName})")
        }
    }

    /**
     * Consulta a la red por cada sobre recibido de [owner] que lo necesite y no envía nada (D2/D3/D4): nonce consumido
     * → [resolveConsumedNonce]; nonce libre y ledger real ya pasado de `exp` → [resolvePastExpiration]; sin poder
     * consultar, se salta y se reintenta en la próxima pasada. Cada cambio sale de [ReceivedSobrePolicy] y se aplica
     * con [ReceivedSobreStore.mutate] sobre el registro VIVO.
     */
    private suspend fun syncReceived(owner: String, cache: PassCache) {
        // Del más antiguo al más reciente: la primera lectura de pagos (la más ancha) sirve a todos, y uno tan viejo que el
        // RPC ya no retiene sus eventos se queda sin comprobar sin impedir cerrar los demás.
        val porRevisar = receivedStore.recordsFor(owner)
            .filter { ReceivedSobrePolicy.needsNetworkCheck(it) }
            .sortedBy { ReceivedSobrePolicy.eventsSinceLedger(it) }
        for (snapshot in porRevisar) {
            val live = receivedStore.get(snapshot.id) ?: continue
            val consumed = if (live.pending) sorobanClient.nonceConsumed(live.payer, live.nonce) else null
            when (ReceivedSobrePolicy.consulta(live, consumed)) {
                ReceivedSobrePolicy.Consulta.SALTAR -> Unit
                ReceivedSobrePolicy.Consulta.NONCE_CONSUMIDO -> resolveConsumedNonce(live.id, cache)
                // El nonce no está consumido (o el sobre solo venció según la estimación local): ¿la red ya pasó `exp`?
                ReceivedSobrePolicy.Consulta.MIRAR_EXPIRACION ->
                    if (ReceivedSobrePolicy.isPastExpiration(live, cache.ledger())) resolvePastExpiration(live.id, cache)
            }
        }
    }

    /**
     * D2: la red consumió el nonce de este sobre recibido. Con un hash cuya auth es este sobre → CONFIRMADO; sin hash,
     * otra tx del pagador con el mismo nonce y otra auth → RECHAZADO (posible fraude); si no, sigue EN_TRANSITO
     * "buscando el comprobante" y se vuelve a buscar en cada pasada.
     */
    private suspend fun resolveConsumedNonce(id: String, cache: PassCache) {
        val live = receivedStore.get(id) ?: return
        val prueba = cache.prueba(live)
        val otraTx = prueba is ReceivedSobrePolicy.Prueba.Ninguna && cache.nonceEnOtraTx(live) != null
        val updated = receivedStore.mutate(id) { ReceivedSobrePolicy.whenNonceConsumed(it, prueba, otraTx) }
        Log.i(
            TAG,
            "SobreSettler: recibido $id con el nonce consumido → ${updated?.status} " +
                "(${prueba.javaClass.simpleName}${if (otraTx) ", nonce en otra tx" else ""})",
        )
    }

    /**
     * D4: el ledger real ya pasó `exp`. La entrada del nonce es temporal y desaparece al vencer, así que antes de dar
     * el sobre por EXPIRADO se busca su hash: con él → CONFIRMADO; sin él → EXPIRADO según la red (o RECHAZADO si ya se
     * había visto su nonce consumido). Devuelve lo que encontró para que quien llama decida qué hacer si no se pudo
     * comprobar (la política en ese caso no cambia el registro).
     */
    private suspend fun resolvePastExpiration(id: String, cache: PassCache): ReceivedSobrePolicy.Prueba {
        val live = receivedStore.get(id) ?: return ReceivedSobrePolicy.Prueba.NoComprobable
        val prueba = cache.prueba(live)
        val updated = receivedStore.mutate(id) { ReceivedSobrePolicy.whenPastExpiration(it, prueba) }
        Log.i(TAG, "SobreSettler: recibido $id pasó exp=${live.exp} → ${updated?.status} (${prueba.javaClass.simpleName})")
        return prueba
    }

    /**
     * Lo que una pasada trae de la red UNA sola vez: el último ledger y los pagos del Pool de cada pagador, leídos
     * ([SorobanClient.sobrePaymentEvents]) desde el ledger que pide el sobre más antiguo ([SobrePaymentsCache]): un sobre
     * vive 16 h y la ventana de `tourPaymentEvents` solo llega a ~11. Un fallo de lectura se recuerda y no se repite.
     */
    private inner class PassCache {
        private var ledgerValue: Long? = null
        private var ledgerLoaded = false
        private val pagos = SobrePaymentsCache { payer, since -> sorobanClient.sobrePaymentEvents(payer, since) }

        suspend fun ledger(refresh: Boolean = false): Long? {
            if (!ledgerLoaded || refresh) {
                ledgerValue = sorobanClient.latestLedgerSequence()
                ledgerLoaded = true
            }
            return ledgerValue
        }

        /** ¿Hay una tx SUCCESS cuya auth sea este sobre? Traduce [SorobanClient.SobreHashLookup] a la política pura. */
        suspend fun prueba(r: ReceivedSobreRecord): ReceivedSobrePolicy.Prueba {
            val lista = pagos.payments(r.payer, ReceivedSobrePolicy.eventsSinceLedger(r))
                ?: return ReceivedSobrePolicy.Prueba.NoComprobable
            return when (val lookup = sorobanClient.lookupSobreHash(r.payer, r.merchant, r.authXdr, lista)) {
                is SorobanClient.SobreHashLookup.Found -> ReceivedSobrePolicy.Prueba.Hash(lookup.txHash)
                SorobanClient.SobreHashLookup.NotFound -> ReceivedSobrePolicy.Prueba.Ninguna
                SorobanClient.SobreHashLookup.Unavailable -> ReceivedSobrePolicy.Prueba.NoComprobable
            }
        }

        /** Hash de una tx exitosa del pagador que gastó el MISMO nonce con OTRO sobre, o null. */
        suspend fun nonceEnOtraTx(r: ReceivedSobreRecord): String? {
            val lista = pagos.payments(r.payer, ReceivedSobrePolicy.eventsSinceLedger(r)) ?: return null
            return sorobanClient.sobreNonceEnOtraTx(r.payer, r.merchant, r.nonce, r.authXdr, lista)
        }
    }

    private fun SobreSubmit?.salioAlaRed(): Boolean =
        this is SobreSubmit.Liquidado || this is SobreSubmit.FallidaEnRed || this is SobreSubmit.EnVuelo

    /** Con una tx en vuelo, o sin XLM en la cuenta fuente, seguir enviando desde ella en esta pasada no tiene sentido. */
    private fun SobreSubmit?.detienePasada(): Boolean =
        this is SobreSubmit.EnVuelo || this is SobreSubmit.SinFondosFuente

    // ── Un sobre propio ──────────────────────────────────────────────────────────

    /** Resultado interno: lo que ve la UI y, si hubo envío, el veredicto de [SorobanClient.submitSobre]. */
    private class SendRun(val resultado: Resultado, val outcome: SobreSubmit?)

    private suspend fun settleOwnInternal(id: String, manual: Boolean): SendRun {
        val record = deferredStore.get(id)
            ?: return SendRun(Resultado.NoEnviable(MOTIVO_SOBRE_AUSENTE, null), null)
        if (!record.pending) return SendRun(Resultado.Actualizado(record, null), null)

        // D10: dueño y firmante. Propios ⇒ la semilla de la wallet debe ser el pagador del sobre.
        val pasarela = pasarelaUrl(record.sobreQr)
        (liquidador() as? Liquidador.Pasarela)?.let { return SendRun(Resultado.NoEnviable(it.motivo, pasarela), null) }
        val signer = walletManager.seedKeyPairOrNull()
            ?: return SendRun(Resultado.NoEnviable(MOTIVO_SIN_WALLET, pasarela), null)
        if (signer.getAccountId() != record.payer) {
            return SendRun(Resultado.NoEnviable(MOTIVO_WALLET_DISTINTA, pasarela), null)
        }

        // D9: sin red no hay simulación. El automático respeta además el "sin señal" forzado de debug.
        val hayRed = if (manual) connectivity.online.value else connectivity.isOnline()
        if (!hayRed) return SendRun(Resultado.SinSenal, null)

        // D6: un mutex por sobre (tryLock) y uno por cuenta fuente, siempre en este orden.
        val idLock = idLocks.computeIfAbsent(id) { Mutex() }
        if (!idLock.tryLock()) return SendRun(Resultado.Ocupado, null)
        try {
            return accountLocks.computeIfAbsent(signer.getAccountId()) { Mutex() }.withLock {
                runOwn(id, signer, manual)
            }
        } finally {
            idLock.unlock()
        }
    }

    private suspend fun runOwn(id: String, signer: KeyPair, manual: Boolean): SendRun {
        val maxFee = if (manual) MANUAL_MAX_FEE else AUTO_MAX_FEE_PROPIOS
        val outcome = (attempt(OwnTarget(id), signer, maxFee, manual) as? Attempt.Enviado)?.outcome
            ?: return SendRun(Resultado.Actualizado(deferredStore.get(id), null), null)
        return SendRun(applyOwn(id, outcome, manual), outcome)
    }

    /**
     * Política de un sobre PROPIO ante cada veredicto de [SorobanClient.submitSobre] (D2/D3): siempre sobre el
     * registro VIVO. En un sobre propio el nonce consumido basta para darlo por cobrado (con el hash si se
     * encuentra); RECHAZADO solo con `InvalidAction`; todo lo demás aplaza con motivo y decide la red.
     */
    private suspend fun applyOwn(id: String, outcome: SobreSubmit, manual: Boolean): Resultado {
        val live = deferredStore.get(id) ?: return Resultado.Actualizado(null, null)
        val nowMs = System.currentTimeMillis()
        Log.i(TAG, "SobreSettler: sobre $id → ${outcome.javaClass.simpleName}")
        when (outcome) {
            is SobreSubmit.Liquidado ->
                deferredStore.mutate(id) { DeferredPolicy.markConfirmed(it, outcome.txHash) }

            // El diario ya quedó abierto en el registro: la próxima pasada lo resuelve por hash.
            is SobreSubmit.EnVuelo -> Unit

            SobreSubmit.NonceUsado, is SobreSubmit.FallidaEnRed -> {
                val fallida = outcome is SobreSubmit.FallidaEnRed
                val consumed = live.id.toLongOrNull()?.let { sorobanClient.nonceConsumed(live.payer, it) }
                if (consumed == true) {
                    // Lo gastó una tx con ESTE sobre (propio ⇒ basta el nonce). Nunca se usa el hash de la tx fallida propia.
                    val hash = sorobanClient.findSobreHash(
                        live.payer,
                        live.merchantAddress,
                        live.authXdr,
                        sinceLedger = DeferredPolicy.eventsSinceLedger(live),
                    )
                    deferredStore.mutate(id) { DeferredPolicy.markConfirmed(it, hash) }
                } else {
                    val motivo = if (fallida) SobreErrors.MSG_FALLO_RED else SobreErrors.MSG_EXISTING
                    deferredStore.mutate(id) { DeferredPolicy.markSendFailed(it, motivo, nowMs, countAsSend = fallida) }
                }
            }

            SobreSubmit.Expirada -> {
                val ledger = sorobanClient.latestLedgerSequence()
                if (ledger != null && ledger > live.exp) {
                    // D4: antes de dar por expirado, ¿se cobró justo antes de vencer?
                    val lookup = sorobanClient.lookupSobreHash(
                        live.payer,
                        live.merchantAddress,
                        live.authXdr,
                        sinceLedger = DeferredPolicy.eventsSinceLedger(live),
                    )
                    when (lookup) {
                        is SorobanClient.SobreHashLookup.Found ->
                            deferredStore.mutate(id) { DeferredPolicy.markConfirmed(it, lookup.txHash) }
                        SorobanClient.SobreHashLookup.NotFound ->
                            deferredStore.mutate(id) { DeferredPolicy.markExpiredByNetwork(it) }
                        SorobanClient.SobreHashLookup.Unavailable ->
                            deferredStore.mutate(id) {
                                DeferredPolicy.markSendFailed(it, MOTIVO_NO_COMPROBADO, nowMs, countAsSend = false)
                            }
                    }
                } else {
                    deferredStore.mutate(id) {
                        DeferredPolicy.markSendFailed(it, SobreErrors.MSG_INVALID_INPUT, nowMs, countAsSend = false)
                    }
                }
            }

            is SobreSubmit.RechazadaDefinitiva ->
                deferredStore.mutate(id) { DeferredPolicy.markRejected(it, outcome.motivo) }

            is SobreSubmit.RechazadaTransitoria ->
                deferredStore.mutate(id) { DeferredPolicy.markSendFailed(it, outcome.motivo, nowMs, countAsSend = false) }

            is SobreSubmit.SinFondosFuente ->
                deferredStore.mutate(id) { DeferredPolicy.markSendFailed(it, outcome.motivo, nowMs, countAsSend = false) }

            is SobreSubmit.SinRed ->
                deferredStore.mutate(id) { DeferredPolicy.markSendFailed(it, outcome.motivo, nowMs, countAsSend = false) }

            is SobreSubmit.FeeAlta -> {
                val motivo = feeAltaMotivo(outcome.feeStroops, outcome.maxFeeStroops, manual)
                deferredStore.mutate(id) { DeferredPolicy.markFeeTooHigh(it, motivo, nowMs, outcome.feeStroops) }
                return Resultado.FeeAlta(outcome.feeStroops, outcome.maxFeeStroops)
            }
        }
        return Resultado.Actualizado(deferredStore.get(id), null)
    }

    /** Texto del aplazo por fee alto (D7): el automático invita a "Liquidar ahora"; el manual dice que ni así cabe. */
    private fun feeAltaMotivo(feeStroops: Long, maxFeeStroops: Long, manual: Boolean): String {
        val cotizado = formatXlm(feeStroops)
        return if (manual) {
            "La red cobra $cotizado XLM por este envío, más del máximo permitido (${formatXlm(maxFeeStroops)} XLM)."
        } else {
            "La red cobra $cotizado XLM por este envío; usa Liquidar ahora para confirmar."
        }
    }

    // ── Recibir un sobre ─────────────────────────────────────────────────────────

    private suspend fun receiveInternal(text: String): RecibidoCheck {
        if (!walletManager.hasUsableWallet()) return RecibidoCheck.Rechazado(ReceivedSobreVerifier.MOTIVO_SIN_WALLET)
        val owner = walletManager.currentAccountId()
            ?: return RecibidoCheck.Rechazado(ReceivedSobreVerifier.MOTIVO_SIN_WALLET)

        // Referencia de vigencia: el ledger real con red (el "sin señal" forzado de debug cuenta como sin red); si no,
        // la estimación del ancla y su edad, que sale del propio ancla: (L estimado − L0) ledgers de 5 s.
        val ledgerActual = if (connectivity.isOnline()) {
            withTimeoutOrNull(LEDGER_LOOKUP_TIMEOUT_MS) { sorobanClient.latestLedgerSequence() }
        } else {
            null
        }
        val readiness = ledgerEstimator.readiness() as? Readiness.Lista
        val estimate = readiness?.estimate
        val anchorAgeMs = readiness?.let { (it.estimate - it.anchor.l0) * LedgerMath.TAU_MS }

        // Verificar y guardar van juntos bajo un candado: dos escaneos del mismo sobre a la vez no lo registran dos veces.
        val check = receiveLock.withLock {
            val verdict = ReceivedSobreVerifier.verify(
                text = text,
                owner = owner,
                topeStroops = receivedStore.topeStroops(owner),
                ledgerActual = ledgerActual,
                estimate = estimate,
                anchorAgeMs = anchorAgeMs,
                vistos = { payer, nonce -> receivedStore.seen(owner, payer, nonce) },
                ids = DeferredIds.from(deployments),
                feeBps = deployments.protocolFeeBps,
                network = network,
                nowMs = System.currentTimeMillis(),
            )
            if (verdict is RecibidoCheck.Aceptado && !receivedStore.saveDurable(verdict.record)) {
                // El almacén no pisa nunca el sobre de otra wallet con el mismo id: se distingue de un fallo de disco.
                val deOtra = receivedStore.get(verdict.record.id)?.let { it.owner != owner } ?: false
                RecibidoCheck.Rechazado(if (deOtra) MOTIVO_OTRA_WALLET else MOTIVO_NO_GUARDADO)
            } else {
                verdict
            }
        }

        when (check) {
            is RecibidoCheck.Aceptado ->
                Log.i(
                    TAG,
                    "SobreSettler: sobre recibido ${check.record.id} rol=${check.record.rol} owner=$owner " +
                        "exp=${check.record.exp} vigenciaComprobada=${check.record.vigenciaComprobada}",
                )
            is RecibidoCheck.YaRegistrado -> Log.i(TAG, "SobreSettler: el sobre ${check.record.id} ya estaba registrado")
            is RecibidoCheck.Rechazado -> Log.i(TAG, "SobreSettler: sobre rechazado al recibir (${check.motivo})")
        }

        // Con red y wallet semilla se liquida enseguida, en segundo plano y sin esperar (D9: los recibidos no hacen jitter).
        if (check is RecibidoCheck.Aceptado && connectivity.isOnline() && liquidador() is Liquidador.DesdeLaApp) {
            val id = check.record.id
            receiveScope.launch {
                try {
                    settleReceived(id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "SobreSettler: la liquidación automática de $id falló (${e.javaClass.simpleName})")
                }
            }
        }
        return check
    }

    // ── Un sobre recibido ────────────────────────────────────────────────────────

    private suspend fun settleReceivedInternal(id: String, manual: Boolean): SendRun {
        val record = receivedStore.get(id)
            ?: return SendRun(Resultado.NoEnviable(MOTIVO_SOBRE_AUSENTE, null), null)
        if (!record.pending) return SendRun(Resultado.Actualizado(null, record), null)

        // D10: dueño y firmante. Recibidos ⇒ la semilla de la wallet debe ser la dueña del sobre Y la wallet activa.
        val pasarela = pasarelaUrl(record.sobreQr)
        (liquidador() as? Liquidador.Pasarela)?.let { return SendRun(Resultado.NoEnviable(it.motivo, pasarela), null) }
        val signer = walletManager.seedKeyPairOrNull()
            ?: return SendRun(Resultado.NoEnviable(MOTIVO_SIN_WALLET, pasarela), null)
        if (signer.getAccountId() != record.owner || record.owner != walletManager.currentAccountId()) {
            return SendRun(Resultado.NoEnviable(MOTIVO_WALLET_DISTINTA, pasarela), null)
        }

        // D9: sin red no hay simulación. El automático respeta además el "sin señal" forzado de debug.
        val hayRed = if (manual) connectivity.online.value else connectivity.isOnline()
        if (!hayRed) return SendRun(Resultado.SinSenal, null)

        // D6: un mutex por sobre (tryLock) y uno por cuenta fuente, siempre en este orden.
        val idLock = idLocks.computeIfAbsent(RECEIVED_LOCK_PREFIX + id) { Mutex() }
        if (!idLock.tryLock()) return SendRun(Resultado.Ocupado, null)
        try {
            return accountLocks.computeIfAbsent(signer.getAccountId()) { Mutex() }.withLock {
                runReceived(id, signer, manual)
            }
        } finally {
            idLock.unlock()
        }
    }

    private suspend fun runReceived(id: String, signer: KeyPair, manual: Boolean): SendRun {
        // D7: un desconocido no puede drenar XLM del comercio con sobres de 1 stroop: el automático solo admite 2 XLM.
        val maxFee = if (manual) MANUAL_MAX_FEE else AUTO_MAX_FEE_RECIBIDOS
        val outcome = (attempt(ReceivedTarget(id), signer, maxFee, manual) as? Attempt.Enviado)?.outcome
            ?: return SendRun(Resultado.Actualizado(null, receivedStore.get(id)), null)
        return SendRun(applyReceived(id, outcome, manual), outcome)
    }

    /**
     * Política de un sobre RECIBIDO ante cada veredicto de [SorobanClient.submitSobre] (D2/D3/D4): siempre sobre el
     * registro VIVO. A diferencia del sobre propio, el nonce consumido NO basta: "cobrado" exige un hash cuya auth sea
     * este sobre; sin él sigue en tránsito buscando el comprobante, y si lo gastó otro sobre es un fraude. RECHAZADO solo
     * con motivos definitivos (`InvalidAction`, nonce usado en otro pago); lo demás aplaza con motivo y decide la red.
     * Qué hacer con cada veredicto lo decide [ReceivedSobrePolicy.reaccion] (puro y probado); aquí solo se ejecuta.
     */
    private suspend fun applyReceived(id: String, outcome: SobreSubmit, manual: Boolean): Resultado {
        val live = receivedStore.get(id) ?: return Resultado.Actualizado(null, null)
        val nowMs = System.currentTimeMillis()
        Log.i(TAG, "SobreSettler: recibido $id → ${outcome.javaClass.simpleName}")
        when (val reaccion = ReceivedSobrePolicy.reaccion(outcome)) {
            is ReceivedSobrePolicy.Reaccion.Confirmar ->
                receivedStore.mutate(id) { ReceivedSobrePolicy.markConfirmed(it, reaccion.txHash) }

            // El diario ya quedó abierto en el registro: la próxima pasada lo resuelve por hash.
            ReceivedSobrePolicy.Reaccion.Nada -> Unit

            is ReceivedSobrePolicy.Reaccion.RevisarNonce -> {
                // Una tx fallida NO consume el nonce: solo si de verdad está consumido se busca quién lo gastó.
                if (sorobanClient.nonceConsumed(live.payer, live.nonce) == true) {
                    resolveConsumedNonce(id, PassCache())
                } else {
                    val motivo = if (reaccion.fallida) SobreErrors.MSG_FALLO_RED else SobreErrors.MSG_EXISTING
                    receivedStore.mutate(id) {
                        ReceivedSobrePolicy.markSendFailed(it, motivo, nowMs, countAsSend = reaccion.fallida)
                    }
                }
            }

            ReceivedSobrePolicy.Reaccion.RevisarExpiracion -> {
                val cache = PassCache()
                if (ReceivedSobrePolicy.isPastExpiration(live, cache.ledger())) {
                    // D4: antes de dar por expirado, ¿se cobró justo antes de vencer?
                    if (resolvePastExpiration(id, cache) is ReceivedSobrePolicy.Prueba.NoComprobable) {
                        receivedStore.mutate(id) {
                            ReceivedSobrePolicy.markSendFailed(it, MOTIVO_NO_COMPROBADO, nowMs, countAsSend = false)
                        }
                    }
                } else {
                    receivedStore.mutate(id) {
                        ReceivedSobrePolicy.markSendFailed(it, SobreErrors.MSG_INVALID_INPUT, nowMs, countAsSend = false)
                    }
                }
            }

            is ReceivedSobrePolicy.Reaccion.Rechazar ->
                receivedStore.mutate(id) { ReceivedSobrePolicy.markRejected(it, reaccion.motivo) }

            is ReceivedSobrePolicy.Reaccion.Aplazar ->
                receivedStore.mutate(id) {
                    ReceivedSobrePolicy.markSendFailed(it, reaccion.motivo, nowMs, countAsSend = reaccion.cuentaComoEnvio)
                }

            is ReceivedSobrePolicy.Reaccion.FeeAlta -> {
                val motivo = feeAltaMotivo(reaccion.feeStroops, reaccion.maxFeeStroops, manual)
                receivedStore.mutate(id) { ReceivedSobrePolicy.markFeeTooHigh(it, motivo, nowMs, reaccion.feeStroops) }
                return Resultado.FeeAlta(reaccion.feeStroops, reaccion.maxFeeStroops)
            }
        }
        return Resultado.Actualizado(null, receivedStore.get(id))
    }

    // ── Mecánica de envío compartida (propios y recibidos) ────────────────────────
    //
    // Todo lo de abajo trabaja contra un [SobreTarget]: el registro VIVO de un sobre en SU almacén. Un sobre
    // recibido solo necesita otra implementación del target y su propia política de veredictos (como [applyOwn]).

    /** Vista de lo que el motor de envío lee de un sobre vivo. */
    private class LiveSobre(
        val payer: String,
        val authXdr: String,
        val sobreQr: String,
        val pending: Boolean,
        val inFlightHash: String?,
        val inFlightValidUntilSec: Long,
    )

    /** Operaciones del motor sobre el registro vivo de UN sobre; cada una lee–modifica–escribe de forma atómica. */
    private interface SobreTarget {
        val id: String

        /** El registro vivo ahora mismo, o null si ya no existe. */
        fun live(): LiveSobre?

        /** ¿Le toca al envío AUTOMÁTICO intentar este sobre ahora? (tope de intentos, backoff; ver [DeferredPolicy.shouldAutoSend]). */
        fun shouldAutoSend(nowMs: Long): Boolean

        /** Anota el diario con commit. `true` solo si quedó anotado (el sobre debe seguir pendiente). */
        fun markInFlight(txHash: String, validUntilSec: Long): Boolean

        fun clearInFlight(txHash: String)

        fun markSendFailed(motivo: String, nowMs: Long, countAsSend: Boolean)

        fun markConfirmed(txHash: String?)
    }

    /** El sobre propio [id] en [DeferredStore]. */
    private inner class OwnTarget(override val id: String) : SobreTarget {
        override fun live(): LiveSobre? = deferredStore.get(id)?.let { r ->
            LiveSobre(
                payer = r.payer,
                authXdr = r.authXdr,
                sobreQr = r.sobreQr,
                pending = r.pending,
                inFlightHash = r.inFlightHash,
                inFlightValidUntilSec = r.inFlightValidUntilSec,
            )
        }

        // El margen de ledgers hasta `exp` lo aplica quien elige los candidatos (tiene el ledger); aquí no se conoce.
        override fun shouldAutoSend(nowMs: Long): Boolean =
            deferredStore.get(id)?.let { DeferredPolicy.shouldAutoSend(it, ledgerHint = null, nowMs = nowMs) } ?: false

        override fun markInFlight(txHash: String, validUntilSec: Long): Boolean =
            deferredStore.mutate(id) { DeferredPolicy.markInFlight(it, txHash, validUntilSec) }?.inFlightHash == txHash

        override fun clearInFlight(txHash: String) {
            deferredStore.mutate(id) { if (it.inFlightHash == txHash) DeferredPolicy.clearInFlight(it) else it }
        }

        override fun markSendFailed(motivo: String, nowMs: Long, countAsSend: Boolean) {
            deferredStore.mutate(id) { DeferredPolicy.markSendFailed(it, motivo, nowMs, countAsSend) }
        }

        override fun markConfirmed(txHash: String?) {
            deferredStore.mutate(id) { DeferredPolicy.markConfirmed(it, txHash) }
        }
    }

    /** El sobre recibido [id] en [ReceivedSobreStore]. */
    private inner class ReceivedTarget(override val id: String) : SobreTarget {
        override fun live(): LiveSobre? = receivedStore.get(id)?.let { r ->
            LiveSobre(
                payer = r.payer,
                authXdr = r.authXdr,
                sobreQr = r.sobreQr,
                pending = r.pending,
                inFlightHash = r.inFlightHash,
                inFlightValidUntilSec = r.inFlightValidUntilSec,
            )
        }

        // El margen de ledgers hasta `exp` lo aplica quien elige los candidatos (tiene el ledger); aquí no se conoce.
        override fun shouldAutoSend(nowMs: Long): Boolean =
            receivedStore.get(id)?.let { ReceivedSobrePolicy.shouldAutoSend(it, ledgerHint = null, nowMs = nowMs) } ?: false

        override fun markInFlight(txHash: String, validUntilSec: Long): Boolean =
            receivedStore.mutate(id) { ReceivedSobrePolicy.markInFlight(it, txHash, validUntilSec) }?.inFlightHash == txHash

        override fun clearInFlight(txHash: String) {
            receivedStore.mutate(id) { if (it.inFlightHash == txHash) ReceivedSobrePolicy.clearInFlight(it) else it }
        }

        override fun markSendFailed(motivo: String, nowMs: Long, countAsSend: Boolean) {
            receivedStore.mutate(id) { ReceivedSobrePolicy.markSendFailed(it, motivo, nowMs, countAsSend) }
        }

        // D2: en un sobre recibido "cobrado" exige un hash; sin él no se cierra nada.
        override fun markConfirmed(txHash: String?) {
            if (txHash != null) receivedStore.mutate(id) { ReceivedSobrePolicy.markConfirmed(it, txHash) }
        }
    }

    private sealed interface Attempt {
        /** No había nada que enviar ahora (cerrado, esperando un veredicto previo, backoff, tope de intentos). */
        data object Nada : Attempt

        /** Se llamó a [SorobanClient.submitSobre] y este fue su veredicto. */
        data class Enviado(val outcome: SobreSubmit) : Attempt
    }

    /** Qué quedó tras mirar la tx que estaba en vuelo. */
    private enum class Resume {
        /** Veredicto definitivo aplicado al registro (cobrado o fallido): no hay más que hacer ahora. */
        RESUELTO,

        /** Aún puede entrar: NO construir otra tx. */
        ESPERANDO,

        /** Ya no hay tx en vuelo: se puede construir otra. */
        LIBRE,
    }

    /**
     * Un intento de envío de [target]: reanuda primero la tx que haya quedado en vuelo (por hash, con la hora de la
     * red); después aplica el tope de intentos (solo en automático) y llama a [SorobanClient.submitSobre] con un
     * diario que anota el hash en el registro ANTES de enviar. Quien llama ya tiene los candados y la política.
     */
    private suspend fun attempt(target: SobreTarget, signer: KeyPair, maxFee: Long, manual: Boolean): Attempt {
        var live = target.live() ?: return Attempt.Nada
        if (!live.pending) return Attempt.Nada

        if (live.inFlightHash != null) {
            when (resumeInFlight(target, live)) {
                Resume.RESUELTO, Resume.ESPERANDO -> return Attempt.Nada
                Resume.LIBRE -> Unit // ya no hay tx en vuelo: abajo se decide si se construye otra
            }
            live = target.live() ?: return Attempt.Nada
            if (!live.pending) return Attempt.Nada
        }
        // Automático: respeta el tope de intentos y el backoff (también tras reanudar). "Liquidar ahora" no.
        if (!manual && !target.shouldAutoSend(System.currentTimeMillis())) return Attempt.Nada

        val entry = runCatching { SorobanAuthorizationEntryXdr.fromXdrBase64(live.authXdr) }.getOrNull()
            ?: SobreCodec.decode(live.sobreQr)
            ?: return Attempt.Enviado(SobreSubmit.RechazadaDefinitiva(MOTIVO_SOBRE_DANADO))

        Log.i(TAG, "SobreSettler: sobre ${target.id} (payer=${live.payer}) sale desde ${signer.getAccountId()} manual=$manual tope=$maxFee")
        return Attempt.Enviado(sorobanClient.submitSobre(entry, signer, maxFee, journalFor(target)))
    }

    private fun journalFor(target: SobreTarget): TxJournal = object : TxJournal {
        override suspend fun signed(txHash: String, validUntilSec: Long, sequence: Long) {
            // Si no queda anotado (disco, o el sobre se cerró entre tanto) esto lanza y la tx NO se envía.
            check(target.markInFlight(txHash, validUntilSec)) { "no se pudo anotar la tx en el diario del sobre" }
            Log.i(TAG, "SobreSettler: sobre ${target.id} firmó tx $txHash (válida hasta $validUntilSec, seq=$sequence)")
        }

        override suspend fun settled(txHash: String) {
            target.clearInFlight(txHash)
        }
    }

    /**
     * Resuelve por hash la tx que el diario tiene en vuelo (respuesta perdida, proceso muerto tras enviar). Regla
     * anti doble envío: mientras pueda entrar todavía NO se construye otra ([DepositPlan.pendingVerdict] con la HORA
     * DE LA RED; sin ella se espera, nunca se usa el reloj del teléfono).
     */
    private suspend fun resumeInFlight(target: SobreTarget, live: LiveSobre): Resume {
        val hash = live.inFlightHash ?: return Resume.LIBRE
        val lookup = sorobanClient.transactionAuthEntries(hash)
        val nowMs = System.currentTimeMillis()
        return when (lookup.status) {
            DepositPlan.TxStatus.SUCCESS ->
                if (lookup.authEntriesXdr.isEmpty() || lookup.authEntriesXdr.contains(live.authXdr)) {
                    Log.i(TAG, "SobreSettler: sobre ${target.id}: la tx en vuelo $hash entró (cobrado)")
                    target.markConfirmed(hash)
                    Resume.RESUELTO
                } else {
                    // Éxito de una tx que no lleva este sobre: no es prueba de nada. Se libera el diario.
                    Log.w(TAG, "SobreSettler: sobre ${target.id}: la tx $hash tuvo éxito pero su auth no es este sobre")
                    target.clearInFlight(hash)
                    Resume.LIBRE
                }

            DepositPlan.TxStatus.FAILED -> {
                Log.w(TAG, "SobreSettler: sobre ${target.id}: la tx en vuelo $hash entró y falló")
                target.clearInFlight(hash)
                target.markSendFailed(SobreErrors.MSG_FALLO_RED, nowMs, countAsSend = true)
                Resume.RESUELTO
            }

            DepositPlan.TxStatus.NOT_FOUND, DepositPlan.TxStatus.UNKNOWN -> {
                val networkNowSec = sorobanClient.networkTimeSec()
                if (networkNowSec == null) {
                    Resume.ESPERANDO
                } else {
                    when (DepositPlan.pendingVerdict(lookup.status, networkNowSec, live.inFlightValidUntilSec)) {
                        DepositPlan.Verdict.SAFE_TO_RETRY -> {
                            Log.i(TAG, "SobreSettler: sobre ${target.id}: la tx en vuelo $hash no entró y ya no puede entrar")
                            target.clearInFlight(hash)
                            target.markSendFailed(SobreErrors.MSG_VENCIO, nowMs, countAsSend = true)
                            Resume.LIBRE
                        }
                        DepositPlan.Verdict.KEEP_WAITING, DepositPlan.Verdict.APPLIED -> Resume.ESPERANDO
                    }
                }
            }
        }
    }
}
