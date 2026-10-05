package com.raiz.app.ui.deposit

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.anchor.AnchorClient
import com.raiz.app.data.anchor.AnchorDepositState
import com.raiz.app.data.anchor.AnchorDepositStatus
import com.raiz.app.data.anchor.AnchorPollTimeout
import com.raiz.app.data.anchor.AnchorSession
import com.raiz.app.data.anchor.AnchorSessionExpired
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.relayer.RelayerClient
import com.raiz.app.data.stellar.ClassicAccountSnapshot
import com.raiz.app.data.stellar.DepositAccountManager
import com.raiz.app.data.stellar.DepositPlan
import com.raiz.app.data.stellar.DepositRoute
import com.raiz.app.data.stellar.DeploymentsLoader
import com.raiz.app.data.stellar.HorizonStream
import com.raiz.app.data.stellar.PendingDepositTx
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.SwapMath
import com.raiz.app.data.stellar.TxJournal
import com.raiz.app.data.stellar.WalletManager
import com.soneso.stellar.sdk.Asset
import com.soneso.stellar.sdk.KeyPair
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Fases del depósito SEP-24. La Screen dibuja una card distinta por fase;
 * ver `ui/deposit/DepositScreen.kt`.
 */
enum class DepositPhase {
    LOADING_INFO,        // Cargando toml + /info del anchor.
    READY,                // Listo para pedir el monto y depositar.
    ANCHOR_UNAVAILABLE,  // El anchor no responde (toml / `/info`): reintentar o USDC demo del relayer.
    NEEDS_XLM,            // La cuenta G... (wallet semilla) aún no existe on-chain.
    PREPARING,            // Cuenta/trustlines → SEP-10 → SEP-24 interactive (stepLabel indica cuál).
    AWAITING_USER,        // El anchor espera que el usuario complete el flujo en su web.
    POLLING,               // El anchor está procesando (pending_anchor/stellar/external/trust).
    COMPLETED,             // Depósito acreditado por el anchor; después corre el cierre (swap / forward).
    FAILED,                 // Error irrecuperable (o el anchor devolvió refunded/expired/error).
    TIMED_OUT,             // Se venció el timeout de polling sin llegar a un estado terminal.
}

/**
 * Estado de la conversión "USDC del anchor de prueba → USDC del fondo (Blend)"
 * (stretch WP3, 2026-09-27): un `PathPaymentStrictSendOperation` NO custodial
 * — firmado por la cuenta operativa, destino esa misma cuenta — contra el pool
 * de liquidez clásico de testnet entre ambos USDC. Se convierte SIEMPRE el
 * saldo completo del anchor. Ver `data/stellar/SwapMath.kt` y
 * `HorizonStream.quoteStrictSend`/`pathPaymentStrictSend`.
 */
sealed interface SwapState {
    data object Idle : SwapState
    data object Quoting : SwapState

    /**
     * Cotización a la vista. [guard] dice si pasa la guarda de precio: con `OK` puede
     * convertirse sola; con `BELOW_AUTO` solo a mano y con aviso; con `BELOW_HARD_FLOOR` no se
     * ofrece convertir.
     */
    data class Quoted(
        val sendStroops: Long,
        val destStroops: Long,
        val destMinStroops: Long,
        val guard: SwapMath.QuoteGuard = SwapMath.QuoteGuard.OK,
    ) : SwapState

    data object Submitting : SwapState

    /** Conversión enviada cuyo veredicto aún no se conoce (respuesta perdida). No se construye otra. */
    data class Confirming(val txHash: String) : SwapState

    /**
     * `destIsExact` = `destStroops` es lo realmente recibido (leído de Horizon), no la cotización.
     * `destStroops == 0` con `destIsExact = false` = monto desconocido (resultado recuperado por hash).
     */
    data class Done(val sendStroops: Long, val destStroops: Long, val txHash: String, val destIsExact: Boolean) : SwapState
    data class Failed(val message: String) : SwapState
}

/**
 * Último tramo del depósito de una wallet PASSKEY: la cuenta de depósito envía el USDC del
 * fondo al smart account `C…` con un `transfer` del SAC (`SorobanClient.sacTransfer`). En
 * wallets de semilla se queda siempre en [Idle]: el USDC del fondo ya está en su cuenta.
 */
sealed interface ForwardState {
    data object Idle : ForwardState
    data class Sending(val amountStroops: Long) : ForwardState

    /** Enviado, esperando confirmación. `stalled` = el veredicto se demora: no repetir, se comprueba solo. */
    data class Confirming(val txHash: String, val amountStroops: Long, val stalled: Boolean) : ForwardState
    data class Done(val amountStroops: Long, val txHash: String) : ForwardState

    /** El dinero sigue en la cuenta de depósito. */
    data class Failed(val message: String) : ForwardState
}

/** Contingencia cuando el anchor no responde: USDC demo (Blend) del relayer, directo a la wallet. */
sealed interface DemoFaucetState {
    data object Idle : DemoFaucetState
    data class Requesting(val stepLabel: String?) : DemoFaucetState
    data class Done(val amountStroops: Long, val txHash: String) : DemoFaucetState
    data class Failed(val message: String) : DemoFaucetState
}

data class DepositUiState(
    val phase: DepositPhase = DepositPhase.LOADING_INFO,
    /**
     * Cuenta OPERATIVA del depósito (G…): la que se autentica con el anchor, recibe su USDC y
     * firma la conversión. En semilla es la propia wallet; en passkey, la cuenta de depósito
     * (vacía hasta que se crea con el primer depósito).
     */
    val account: String = "",
    /** A dónde termina el dinero: en semilla == [account]; en passkey, el smart account `C…`. */
    val destination: String = "",
    val isPasskey: Boolean = false,
    val anchorDomain: String = RaizConstants.ANCHOR_HOME_DOMAIN,
    val minUsdc: Double? = null,
    val maxUsdc: Double? = null,
    val amountInput: String = "5",
    val amountError: String? = null,
    /** null = desconocido todavía (aún no se consultó Horizon). */
    val hasAnchorTrustline: Boolean? = null,
    /** Saldo del USDC del anchor en la cuenta operativa. NUNCA se suma al saldo principal. */
    val anchorUsdcBalanceStroops: Long = 0L,
    /** Passkey: USDC del fondo que sigue en la cuenta de depósito, pendiente de enviar al `C…`. */
    val fundPendingStroops: Long = 0L,
    /** "Habilitando USDC del anchor en tu cuenta…", "Autenticando con el anchor (SEP-10)…", "Abriendo el depósito (SEP-24)…" */
    val stepLabel: String? = null,
    val depositId: String? = null,
    val interactiveUrl: String? = null,
    /** Último estado reportado por el anchor. */
    val status: AnchorDepositStatus? = null,
    val elapsedSec: Int = 0,
    /** stellar_transaction_id del anchor (o respaldo Horizon /payments). */
    val txHash: String? = null,
    val error: String? = null,
    /** Conversión "anchor-USDC → USDC del fondo" (stretch WP3). Ver [SwapState]. */
    val swap: SwapState = SwapState.Idle,
    /** Paso en curso de la conversión. Separado de [stepLabel], que es del depósito. */
    val swapStepLabel: String? = null,
    /** Envío final a la wallet passkey. Ver [ForwardState]. */
    val forward: ForwardState = ForwardState.Idle,
    /** `true` mientras corre el cierre (conversión / envío) — para avisar de que se puede salir. */
    val settling: Boolean = false,
    /** Contingencia "USDC demo (relayer)" de [DepositPhase.ANCHOR_UNAVAILABLE]. */
    val demoFaucet: DemoFaucetState = DemoFaucetState.Idle,
)

/**
 * ViewModel de la pantalla "Depositar" (SEP-24 con el testanchor del SDF): el on-ramp de
 * TODAS las wallets, que termina en USDC del fondo (Blend), el que sirve para pagar.
 *
 * ## Cuenta operativa ([DepositRoute])
 *
 * Todo el flujo trabaja contra "una cuenta G… y su KeyPair", resueltos por
 * [DepositAccountManager.route]:
 *  - wallet de semilla → la propia wallet;
 *  - wallet passkey (smart account `C…`) → su CUENTA DE DEPÓSITO, una cuenta clásica de
 *    tránsito creada en el teléfono. SEP-10 clásico no autentica un `C…` y un path payment no
 *    admite un `C…` como destino; la autenticación directa del smart account (SEP-45) está en
 *    el roadmap de RAÍZ. Esta clase NUNCA llama a `WalletManager.currentKeyPair()`.
 *
 * ## Flujo feliz
 *
 *   1. `loadInfo()` — toml + `/info` del anchor (min/max, issuer real). Si falla →
 *      [DepositPhase.ANCHOR_UNAVAILABLE] (reintentar, o USDC demo del relayer como contingencia).
 *   2. Semilla: si la cuenta no existe on-chain → [DepositPhase.NEEDS_XLM].
 *   3. `startDeposit()`: (passkey: crear la cuenta de depósito, friendbot y sus dos trustlines)
 *      → trustline al USDC del anchor → SEP-10 → SEP-24 interactive → Custom Tab → polling.
 *   4. El anchor marca `completed` → [DepositPhase.COMPLETED] y arranca el CIERRE.
 *
 * ## Cierre ([settle])
 *
 * La verdad son los saldos on-chain de la cuenta operativa. En bucle: resolver por hash la
 * transacción propia que pudiera estar en vuelo → leer la foto de la cuenta → ejecutar el
 * tramo que falta ([DepositPlan.nextLeg]):
 *  - `CONVERT`: USDC del anchor → USDC del fondo. Sola únicamente si la cotización pasa la
 *    guarda de precio ([SwapMath.quoteGuard], [DepositPlan.shouldConvert]); si no, queda la
 *    card con la cotización, el aviso y los botones.
 *  - `FORWARD` (solo passkey): `transfer` del SAC de la cuenta de depósito al `C…`.
 * Cada tramo mueve TODO lo que hay hacia un destino fijo, así que repetirlo es inocuo; la
 * parte que envía y confirma corre en `NonCancellable` para que salir de la pantalla no la
 * corte a medias. Lo que quede pendiente se retoma al volver a abrir Depositar (ON_OPEN).
 *
 * El `deposit_id` en curso se guarda en [SavedStateHandle] para sobrevivir a la vuelta desde
 * el Custom Tab (el proceso puede morir en background); la transacción en vuelo y la cuenta de
 * depósito viven cifradas en `SecureWalletStore`.
 */
@HiltViewModel
class DepositViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val horizonStream: HorizonStream,
    private val anchorClient: AnchorClient,
    private val savedStateHandle: SavedStateHandle,
    private val deploymentsLoader: DeploymentsLoader,
    private val depositAccounts: DepositAccountManager,
    private val sorobanClient: SorobanClient,
    private val relayerClient: RelayerClient,
) : ViewModel() {

    private val _state = MutableStateFlow(DepositUiState())
    val state: StateFlow<DepositUiState> = _state.asStateFlow()

    /**
     * USDC del fondo (Blend) — el mismo issuer que usa `HorizonStream.usdcBalanceFlow`
     * para el saldo principal de la wallet. Necesario aquí para cotizar/enviar la
     * conversión (destino del path payment).
     */
    private val deployments: Deployments by lazy { deploymentsLoader.load() }
    private val blendUsdcIssuer: String by lazy { deployments.usdcIssuer ?: deployments.admin }

    /**
     * Issuer del USDC del anchor leído de su `stellar.toml`. Mientras el anchor no haya
     * respondido se usa el fijo del proyecto ([anchorIssuer]): cotizar, convertir y leer saldos
     * NO deben depender de que el anchor esté vivo — puede haber un depósito a medio camino.
     */
    private var knownAnchorIssuer: String? = null

    /**
     * Camino del pool de liquidez devuelto por la última cotización (`quoteStrictSend`).
     * En el pool verificado hoy es un único salto (`path = []`), pero se guarda tal cual
     * lo devuelva Horizon para no asumirlo. Se reutiliza al enviar la conversión — pedir
     * una segunda cotización ahí introduciría una carrera con la que ya se le mostró al
     * usuario en el botón "Convertir".
     */
    private var swapPath: List<Asset> = emptyList()

    /** Evento one-shot: la Screen abre esta URL en una Custom Tab al recibirla. */
    private val _openUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openUrl: SharedFlow<String> = _openUrl.asSharedFlow()

    private var pollJob: Job? = null
    private var tickerJob: Job? = null
    /**
     * Coroutine de reanudación (SEP-10 + arranque del polling) lanzada por
     * [resumePolling]. Se rastrea aparte de [pollJob] porque durante la ventana
     * de `obtainSession` (challenge + firma + POST, varios segundos tras muerte
     * de proceso) `pollJob` sigue inactivo: sin este Job, [reset] no podría
     * cancelarla y el polling "resucitaría" tras "Cancelar"/"Volver".
     */
    private var resumeJob: Job? = null

    /** Cierre en curso (conversión / envío a la wallet). Uno solo a la vez: comparten cuenta y secuencia. */
    private var settleJob: Job? = null

    init {
        val owner = walletManager.currentAccountId().orEmpty()
        val isPasskey = walletManager.isPasskeyWallet()
        _state.update {
            it.copy(
                account = if (isPasskey) depositAccounts.depositAccountId().orEmpty() else owner,
                destination = owner,
                isPasskey = isPasskey,
            )
        }
        loadInfo()
    }

    private fun loadInfo() {
        viewModelScope.launch {
            _state.update { it.copy(phase = DepositPhase.LOADING_INFO, error = null) }
            when (val r = anchorClient.loadInfo()) {
                is RaizResult.Success -> {
                    val info = r.data
                    knownAnchorIssuer = info.usdcIssuer
                    _state.update {
                        it.copy(
                            anchorDomain = info.homeDomain,
                            minUsdc = info.depositMinUsdc,
                            maxUsdc = info.depositMaxUsdc,
                            // El monto inicial ("5") también se contrasta con los límites
                            // reales de /info: si el anchor los cambiara (min 6, max 4…) el
                            // CTA no quedaría habilitado con un monto fuera de rango.
                            amountError = validateAmount(it.amountInput, info.depositMinUsdc, info.depositMaxUsdc),
                        )
                    }
                    checkAccount()
                }
                is RaizResult.Error -> {
                    Log.w(TAG, "Depósito: el anchor de prueba no responde (${r.code})")
                    _state.update { it.copy(phase = DepositPhase.ANCHOR_UNAVAILABLE, error = r.message) }
                    // Lo que ya esté en camino no depende del anchor: se termina (u ofrece) igual.
                    settle(DepositPlan.Trigger.ON_OPEN)
                }
            }
        }
    }

    /** "Reintentar" de [DepositPhase.ANCHOR_UNAVAILABLE]: vuelve a cargar el toml y `/info`. */
    fun retryAnchor() {
        if (state.value.phase != DepositPhase.ANCHOR_UNAVAILABLE) return
        loadInfo()
    }

    private fun checkAccount() {
        viewModelScope.launch {
            if (!state.value.isPasskey) {
                val accountId = state.value.account
                if (accountId.isBlank()) {
                    _state.update { it.copy(phase = DepositPhase.FAILED, error = "No hay wallet activa.") }
                    return@launch
                }
                if (!horizonStream.accountExists(accountId)) {
                    _state.update { it.copy(phase = DepositPhase.NEEDS_XLM) }
                    return@launch
                }
            }
            // Passkey: que la cuenta de depósito no exista todavía no es un error ni le pide
            // nada al usuario — se crea (y se fondea) al iniciar el primer depósito.
            _state.update { it.copy(phase = DepositPhase.READY) }

            // Restauración: había un depósito en curso cuando el proceso murió
            // (el usuario estaba en el Custom Tab) → retoma el polling.
            val savedId = savedStateHandle.get<String>(KEY_DEPOSIT_ID)
            if (savedId != null) {
                _state.update { it.copy(phase = DepositPhase.AWAITING_USER, depositId = savedId) }
                resumePolling()
            } else {
                // ¿Quedó algo a medio camino (USDC del anchor sin convertir, USDC del fondo sin
                // enviar, una tx propia en vuelo)? Semilla: se ofrece; passkey: se termina solo.
                settle(DepositPlan.Trigger.ON_OPEN)
            }
        }
    }

    /** Paso previo a [DepositPhase.NEEDS_XLM]: friendbot (solo wallets de semilla). */
    fun fundWithFriendbot() {
        viewModelScope.launch {
            val accountId = state.value.account
            _state.update { it.copy(phase = DepositPhase.PREPARING, stepLabel = "Fondeando con friendbot…", error = null) }
            when (val r = horizonStream.fundWithFriendbot(accountId)) {
                is RaizResult.Success -> checkAccount()
                is RaizResult.Error -> _state.update {
                    it.copy(phase = DepositPhase.NEEDS_XLM, error = r.message)
                }
            }
        }
    }

    /**
     * Valida 1..max con máximo 2 decimales; RAÍZ nunca opera con floats en el contrato, solo en UI.
     *
     * La coma se acepta como separador decimal: con `KeyboardType.Decimal` los teclados
     * es-CO/es-ES (Gboard, Samsung) emiten ',' y Compose no la traduce. Se normaliza a '.'
     * en vez de descartarla — descartarla convertía "1,0" en "10" en silencio.
     */
    fun onAmountChange(input: String) {
        val sanitized = input.replace(',', '.').filter { it.isDigit() || it == '.' }
        _state.update { it.copy(amountInput = sanitized, amountError = validateAmount(sanitized)) }
    }

    /**
     * `min`/`max` por defecto salen del state; `loadInfo` los pasa explícitos porque
     * dentro de `_state.update` el state aún no tiene los límites recién leídos.
     */
    private fun validateAmount(
        input: String,
        min: Double? = state.value.minUsdc,
        max: Double? = state.value.maxUsdc,
    ): String? {
        if (input.isBlank()) return "Ingresa un monto"
        if (input.count { it == '.' } > 1) return "Monto inválido"
        val value = input.toDoubleOrNull() ?: return "Monto inválido"
        val minValue = min ?: 1.0
        val maxValue = max ?: 10.0
        if (value < minValue) return "Mínimo ${formatLimit(minValue)} USDC"
        if (value > maxValue) return "Máximo ${formatLimit(maxValue)} USDC"
        val decimals = input.substringAfter('.', "")
        if (decimals.length > 2) return "Máximo 2 decimales"
        return null
    }

    /** Monto tal como viaja al anchor: sin punto decimal colgando ("5." → "5"). */
    private fun amountForAnchor(input: String): String = input.trimEnd('.')

    /** SEP-24 exige https en la URL interactiva y en `more_info_url`; nada más se abre. */
    private fun isHttpsUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

    private fun formatLimit(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /** Issuer del USDC del anchor: el de su toml si ya respondió; si no, el fijo del proyecto. */
    private fun anchorIssuer(): String = knownAnchorIssuer ?: RaizConstants.ANCHOR_USDC_ISSUER

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    // ── Depósito: preparación → SEP-10 → SEP-24 → polling ─────────────────────

    /**
     * Dispara el flujo completo: cuenta operativa lista (passkey: crearla, friendbot y sus
     * trustlines; semilla: trustline al USDC del anchor) → SEP-10 → SEP-24 interactive → abre
     * Custom Tabs → arranca el polling.
     */
    fun startDeposit() {
        val current = state.value
        if (current.phase != DepositPhase.READY) return
        // Un cierre en curso (o una tx propia en vuelo) y un depósito nuevo no se solapan:
        // comparten cuenta y secuencia.
        if (isSettlingOrInFlight()) return
        // Revalida aquí en vez de confiar solo en amountError: el monto inicial o un
        // cambio de límites del anchor podrían no haber pasado por onAmountChange.
        val amountError = validateAmount(current.amountInput)
        if (amountError != null) {
            _state.update { it.copy(amountError = amountError) }
            return
        }

        viewModelScope.launch {
            // Al salir de READY se descarta cualquier cotización previa: COMPLETED no debe
            // heredar un "Convertir N" viejo mientras llega la cotización nueva.
            _state.update {
                it.copy(
                    phase = DepositPhase.PREPARING,
                    stepLabel = null,
                    error = null,
                    swap = SwapState.Idle,
                    swapStepLabel = null,
                    forward = ForwardState.Idle,
                )
            }

            val info = anchorClient.loadInfo().getOrNull()
            if (info == null) {
                _state.update {
                    it.copy(phase = DepositPhase.ANCHOR_UNAVAILABLE, stepLabel = null, error = MSG_ANCHOR_UNREACHABLE)
                }
                return@launch
            }
            knownAnchorIssuer = info.usdcIssuer

            // Cuenta operativa. En passkey, la primera vez genera y guarda la cuenta de depósito.
            if (current.isPasskey) _state.update { it.copy(stepLabel = STEP_PREPARING_DEPOSIT_ACCOUNT) }
            val route = when (val r = depositAccounts.route(createIfMissing = true)) {
                is RaizResult.Success -> r.data
                is RaizResult.Error -> {
                    failDeposit(r.message)
                    return@launch
                }
            }
            if (route == null) {
                failDeposit(DepositAccountManager.MSG_NO_SIGNER)
                return@launch
            }
            _state.update { it.copy(account = route.account, destination = route.destination) }
            Log.i(TAG, "Depósito: cuenta operativa ${route.account} → destino final ${route.destination}")

            val prepared = if (route.needsForward) {
                prepareDepositAccount(route, info.usdcIssuer)
            } else {
                prepareSeedAccount(route, info.usdcIssuer)
            }
            if (!prepared) return@launch
            _state.update { it.copy(hasAnchorTrustline = true) }

            // (b) Autenticación SEP-10 (reutiliza sesión cacheada si sirve).
            _state.update { it.copy(stepLabel = "Autenticando con el anchor (SEP-10)…") }
            val session = obtainSession(route.signer) ?: return@launch

            // (c) Depósito interactivo SEP-24.
            _state.update { it.copy(stepLabel = "Abriendo el depósito (SEP-24)…") }
            when (val r = anchorClient.startDeposit(session, amountForAnchor(current.amountInput))) {
                is RaizResult.Success -> {
                    if (!isHttpsUrl(r.data.interactiveUrl)) {
                        // Cualquier otro esquema se despacharía como intent implícito a la app
                        // que lo reclame (market:, tel:, file:// → crash). No se abre ni se
                        // guarda el depósito: el usuario puede "Reintentar".
                        Log.w(TAG, "startDeposit: el anchor devolvió una URL interactiva sin https; se rechaza")
                        _state.update { it.copy(phase = DepositPhase.FAILED, stepLabel = null, error = MSG_UNSAFE_URL) }
                        return@launch
                    }
                    savedStateHandle[KEY_DEPOSIT_ID] = r.data.id
                    // Passkey: deja constancia local de que hay un depósito en camino (fila de
                    // Inicio y aviso al cerrar sesión), aunque el proceso muera en la web del anchor.
                    depositAccounts.markDepositStarted()
                    _state.update {
                        it.copy(
                            phase = DepositPhase.AWAITING_USER,
                            depositId = r.data.id,
                            interactiveUrl = r.data.interactiveUrl,
                            stepLabel = null,
                        )
                    }
                    _openUrl.tryEmit(r.data.interactiveUrl)
                    beginPolling(session, r.data.id)
                }
                is RaizResult.Error -> failDeposit(r.message)
            }
        }
    }

    private fun failDeposit(message: String) {
        _state.update { it.copy(phase = DepositPhase.FAILED, stepLabel = null, error = message) }
    }

    /** Wallet de semilla: como siempre — la cuenta debe existir y se le crea la trustline al USDC del anchor. */
    private suspend fun prepareSeedAccount(route: DepositRoute, anchorUsdcIssuer: String): Boolean {
        if (!horizonStream.accountExists(route.account)) {
            _state.update { it.copy(phase = DepositPhase.NEEDS_XLM, stepLabel = null) }
            return false
        }
        // (a) Trustline al USDC del anchor.
        _state.update { it.copy(stepLabel = "Habilitando USDC del anchor en tu cuenta…") }
        val alreadyTrusts = horizonStream.hasTrustline(route.account, RaizConstants.ANCHOR_USDC_CODE, anchorUsdcIssuer)
        if (!alreadyTrusts) {
            when (val r = horizonStream.enableTrustline(route.signer, RaizConstants.ANCHOR_USDC_CODE, anchorUsdcIssuer)) {
                is RaizResult.Error -> {
                    failDeposit(r.message)
                    return false
                }
                is RaizResult.Success -> Unit
            }
        }
        return true
    }

    /**
     * Wallet passkey: deja la cuenta de depósito lista para recibir del anchor y convertir.
     * Idempotente — [DepositPlan.setupSteps] se calcula de la foto on-chain y solo devuelve lo
     * que falta (friendbot la primera vez; después, nada). El usuario no pulsa nada: es una
     * cuenta de tránsito, no "su" cuenta.
     */
    private suspend fun prepareDepositAccount(route: DepositRoute, anchorUsdcIssuer: String): Boolean {
        var lastError = MSG_DEPOSIT_ACCOUNT_SETUP_FAILED
        // Varias pasadas: cada una recalcula de la foto lo que falta. Dos transacciones seguidas
        // de la misma cuenta pueden chocar un instante con una réplica de Horizon atrasada
        // (secuencia vieja); repetir es seguro — friendbot y ChangeTrust son idempotentes.
        repeat(DEPOSIT_ACCOUNT_SETUP_PASSES) { pass ->
            if (pass > 0) delay(DEPOSIT_ACCOUNT_SETUP_RETRY_MS)
            _state.update { it.copy(stepLabel = STEP_PREPARING_DEPOSIT_ACCOUNT) }
            val snapshot = when (val r = horizonStream.accountSnapshot(route.account, anchorUsdcIssuer)) {
                is RaizResult.Success -> r.data
                is RaizResult.Error -> {
                    lastError = MSG_DEPOSIT_ACCOUNT_UNREADABLE
                    return@repeat
                }
            }
            if (snapshot != null && !DepositPlan.hasEnoughXlm(snapshot.xlmStroops)) {
                Log.w(
                    TAG,
                    "Depósito: la cuenta de depósito ${route.account} se quedó sin XLM (${snapshot.xlmStroops} stroops)",
                )
                failDeposit(MSG_DEPOSIT_ACCOUNT_NO_XLM)
                return false
            }
            val steps = DepositPlan.setupSteps(snapshot)
            if (steps.isEmpty()) return true
            Log.i(TAG, "Depósito: preparando la cuenta de depósito ${route.account}: $steps")
            val failure = runSetupSteps(route, steps, anchorUsdcIssuer)
            if (failure == null) return true
            lastError = failure
        }
        failDeposit(lastError)
        return false
    }

    /** Ejecuta los pasos en orden. `null` = todos bien; si no, el mensaje del paso que falló. */
    private suspend fun runSetupSteps(
        route: DepositRoute,
        steps: List<DepositPlan.SetupStep>,
        anchorUsdcIssuer: String,
    ): String? {
        for (step in steps) {
            when (step) {
                DepositPlan.SetupStep.FRIENDBOT -> {
                    _state.update { it.copy(stepLabel = STEP_CREATING_DEPOSIT_ACCOUNT) }
                    if (horizonStream.fundWithFriendbot(route.account) is RaizResult.Error) return MSG_FRIENDBOT_FAILED
                    if (!awaitAccountOnChain(route.account, anchorUsdcIssuer)) return MSG_FRIENDBOT_FAILED
                }
                DepositPlan.SetupStep.TRUST_ANCHOR -> {
                    _state.update { it.copy(stepLabel = "Habilitando USDC del anchor en tu cuenta de depósito…") }
                    val r = horizonStream.enableTrustline(route.signer, RaizConstants.ANCHOR_USDC_CODE, anchorUsdcIssuer)
                    if (r is RaizResult.Error) return MSG_DEPOSIT_ACCOUNT_SETUP_FAILED
                }
                DepositPlan.SetupStep.TRUST_FUND -> {
                    _state.update { it.copy(stepLabel = STEP_TRUST_FUND_DEPOSIT_ACCOUNT) }
                    val r = horizonStream.enableUsdcTrustline(route.signer)
                    if (r is RaizResult.Error) return MSG_DEPOSIT_ACCOUNT_SETUP_FAILED
                }
            }
        }
        return null
    }

    /** Tras friendbot, espera a que Horizon ya vea la cuenta (la siguiente tx necesita su secuencia). */
    private suspend fun awaitAccountOnChain(account: String, anchorUsdcIssuer: String): Boolean {
        repeat(ACCOUNT_VISIBLE_ATTEMPTS) { attempt ->
            val r = horizonStream.accountSnapshot(account, anchorUsdcIssuer)
            if (r is RaizResult.Success && r.data != null) return true
            if (attempt < ACCOUNT_VISIBLE_ATTEMPTS - 1) delay(ACCOUNT_VISIBLE_DELAY_MS)
        }
        return false
    }

    /** Sesión SEP-10 usable: cache en memoria si sirve, si no autentica de nuevo. */
    private suspend fun obtainSession(kp: KeyPair): AnchorSession? {
        val accountId = kp.getAccountId()
        anchorClient.cachedSession(accountId)?.let { cached -> if (cached.isUsable()) return cached }
        return when (val r = anchorClient.authenticate(kp)) {
            is RaizResult.Success -> r.data
            is RaizResult.Error -> {
                _state.update { it.copy(phase = DepositPhase.FAILED, error = r.message) }
                null
            }
        }
    }

    /**
     * Ruta de un depósito YA iniciado (reanudar el polling, re-autenticar, reintentar la
     * trustline). Nunca crea una cuenta de depósito: si no hay, no hay nada que retomar.
     */
    private suspend fun existingRoute(): DepositRoute? =
        (depositAccounts.route(createIfMissing = false) as? RaizResult.Success)?.data

    /** Re-abre la URL interactiva guardada (o la del último `moreInfoUrl` del anchor). */
    fun reopenInteractive() {
        val url = state.value.interactiveUrl ?: state.value.status?.moreInfoUrl ?: return
        if (!isHttpsUrl(url)) {
            Log.w(TAG, "reopenInteractive: URL sin https; no se abre")
            return
        }
        _openUrl.tryEmit(url)
    }

    /**
     * Retoma el polling: desde [DepositPhase.TIMED_OUT] ("Seguir esperando"),
     * desde `onResume()` si no hay un poll activo, o al restaurar un depósito
     * en curso tras recrearse el VM.
     */
    fun resumePolling() {
        val id = state.value.depositId ?: savedStateHandle.get<String>(KEY_DEPOSIT_ID) ?: return
        // Dos taps rápidos en "Seguir esperando" (o ON_RESUME durante la restauración)
        // no deben disparar dos SEP-10: el guard incluye la reanudación en curso.
        if (isPollingOrResuming()) return
        resumeJob = viewModelScope.launch {
            val route = existingRoute()
            if (route == null) {
                _state.update {
                    it.copy(phase = DepositPhase.FAILED, error = DepositAccountManager.MSG_NO_SIGNER)
                }
                return@launch
            }
            if (state.value.account != route.account) _state.update { it.copy(account = route.account) }
            val session = obtainSession(route.signer) ?: return@launch
            beginPolling(session, id)
        }
    }

    /** Llamado por la Screen en ON_RESUME (p. ej. al volver del Custom Tab). */
    fun onResume() {
        val current = state.value
        if (current.phase in RESUMABLE_PHASES) {
            if (!isPollingOrResuming()) resumePolling() else pollNow()
            return
        }
        // Passkey: al volver a la pantalla se termina lo que haya quedado en camino (el pago
        // del anchor pudo llegar mientras la app estaba en segundo plano).
        if (current.isPasskey && current.phase in SETTLE_ON_RESUME_PHASES && settleJob?.isActive != true) {
            settle(DepositPlan.Trigger.ON_OPEN)
        }
    }

    private fun isPollingOrResuming(): Boolean =
        pollJob?.isActive == true || resumeJob?.isActive == true

    /**
     * Al volver de la web del anchor con el sondeo vivo, lo reinicia para que consulte el estado
     * YA (la primera consulta de `pollDeposit` no espera) en vez de aguardar el siguiente turno
     * del backoff, que puede tardar hasta 10 s. El contador de espera no se reinicia.
     */
    private fun pollNow() {
        if (resumeJob?.isActive == true || pollJob?.isActive != true) return
        val current = state.value
        if (current.phase == DepositPhase.TIMED_OUT) return
        val id = current.depositId ?: return
        val session = anchorClient.cachedSession(current.account)?.takeIf { it.isUsable() } ?: return
        beginPolling(session, id, keepTicker = true)
    }

    /**
     * `true` mientras el depósito `id` siga siendo el que la pantalla muestra en
     * curso. Tras [reset] (READY, `depositId = null`) devuelve `false` y un
     * `beginPolling` que llegue tarde (la autenticación de [resumePolling]
     * terminó después de "Cancelar") no debe arrancar nada.
     */
    private fun isTracking(id: String): Boolean {
        val current = state.value
        return current.phase in RESUMABLE_PHASES && current.depositId == id
    }

    /** Cancela el depósito en curso y vuelve a [DepositPhase.READY]. */
    fun reset() {
        resumeJob?.cancel()
        pollJob?.cancel()
        stopTicker()
        savedStateHandle.remove<String>(KEY_DEPOSIT_ID)
        // Se canceló sin que el anchor llegara a recibir el formulario (último estado conocido:
        // ninguno o `incomplete`): no hay nada en camino, así que se quita la marca local y
        // Inicio no enseña "Tienes un depósito en camino". Si el anchor ya lo estaba procesando,
        // la marca se queda — el pago puede llegar igual — y caduca sola.
        val lastAnchorState = state.value.status?.state
        if (lastAnchorState == null || lastAnchorState == AnchorDepositState.INCOMPLETE) {
            depositAccounts.clearDepositStarted()
        }
        // Un cierre en marcha no se pisa: sus estados (conversión / envío) siguen siendo los reales.
        val settling = settleJob?.isActive == true
        _state.update {
            it.copy(
                phase = DepositPhase.READY,
                stepLabel = null,
                depositId = null,
                interactiveUrl = null,
                status = null,
                elapsedSec = 0,
                txHash = null,
                error = null,
                swap = if (settling) it.swap else SwapState.Idle,
                swapStepLabel = if (settling) it.swapStepLabel else null,
                forward = if (settling) it.forward else ForwardState.Idle,
            )
        }
        // De vuelta en READY puede haber algo pendiente (p. ej. un depósito anterior): sin
        // volver a mirar, la card quedaría sin botón.
        settle(DepositPlan.Trigger.ON_OPEN)
    }

    /**
     * @param reauthAttempts cuántas veces ya se re-autenticó por [AnchorSessionExpired]
     *   en esta cadena de polling. El contrato pide "re-autenticar UNA vez y seguir":
     *   al superar [MAX_REAUTH_ATTEMPTS] se pasa a [DepositPhase.FAILED] en vez de
     *   entrar en un bucle sin fin de challenge → token → 403 → challenge…
     */
    private fun beginPolling(
        session: AnchorSession,
        id: String,
        reauthAttempts: Int = 0,
        keepTicker: Boolean = false,
    ) {
        if (!isTracking(id)) {
            Log.w(TAG, "beginPolling: el depósito $id ya no está en curso (phase=${state.value.phase}); no se arranca")
            return
        }
        pollJob?.cancel()
        if (!keepTicker || tickerJob?.isActive != true) startTicker()
        // Reintenta enableTrustline como mucho una vez por sesión de polling
        // (si el anchor reporta pending_trust — el usuario no tuvo trustline
        // a tiempo de la primera llamada, o la creó tarde).
        var trustlineRetried = false

        pollJob = viewModelScope.launch {
            try {
                anchorClient.pollDeposit(session, id).collect { status ->
                    _state.update { it.copy(status = status) }
                    when (status.state) {
                        AnchorDepositState.INCOMPLETE, AnchorDepositState.PENDING_USER ->
                            _state.update { it.copy(phase = DepositPhase.AWAITING_USER) }

                        AnchorDepositState.PENDING_ANCHOR,
                        AnchorDepositState.PENDING_STELLAR,
                        AnchorDepositState.PENDING_EXTERNAL,
                        AnchorDepositState.UNKNOWN ->
                            _state.update { it.copy(phase = DepositPhase.POLLING) }

                        AnchorDepositState.PENDING_TRUST -> {
                            _state.update {
                                it.copy(
                                    phase = DepositPhase.POLLING,
                                    stepLabel = "Falta la trustline: la app la crea ahora",
                                )
                            }
                            if (!trustlineRetried) {
                                trustlineRetried = true
                                val route = existingRoute()
                                if (route != null) {
                                    horizonStream.enableTrustline(route.signer, RaizConstants.ANCHOR_USDC_CODE, anchorIssuer())
                                }
                            }
                        }

                        AnchorDepositState.COMPLETED -> {
                            stopTicker()
                            savedStateHandle.remove<String>(KEY_DEPOSIT_ID)
                            val hash = status.stellarTxHash ?: fallbackTxHash()
                            Log.i(
                                TAG,
                                "Depósito: el anchor pagó ${status.amountOutUsdc ?: "?"} USDC a " +
                                    "${state.value.account}, hash=${hash ?: "desconocido"}",
                            )
                            // Passkey: desde ahora hay dinero en la cuenta de depósito (constancia local).
                            depositAccounts.onAnchorPaid()
                            _state.update {
                                it.copy(phase = DepositPhase.COMPLETED, txHash = hash, stepLabel = null)
                            }
                            // Cierre automático: conversión (si pasa la guarda) y, en passkey, envío a la wallet.
                            settle(DepositPlan.Trigger.AFTER_DEPOSIT, expectFunds = true)
                        }

                        AnchorDepositState.REFUNDED, AnchorDepositState.EXPIRED, AnchorDepositState.ERROR -> {
                            stopTicker()
                            savedStateHandle.remove<String>(KEY_DEPOSIT_ID)
                            // Estado terminal sin pago: ya no hay nada en camino.
                            depositAccounts.clearDepositStarted()
                            _state.update {
                                it.copy(
                                    phase = DepositPhase.FAILED,
                                    error = status.message ?: "El anchor no completó el depósito (${status.rawStatus}).",
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AnchorPollTimeout) {
                Log.w(TAG, "pollDeposit: timeout esperando al anchor (id=$id)")
                stopTicker()
                _state.update { it.copy(phase = DepositPhase.TIMED_OUT) }
            } catch (e: AnchorSessionExpired) {
                if (reauthAttempts >= MAX_REAUTH_ATTEMPTS) {
                    // El anchor rechazó también el JWT recién emitido (reloj desajustado,
                    // rotación de secreto, sub ≠ tx…): reintentar no lo arregla.
                    Log.w(TAG, "pollDeposit: el anchor rechazó la sesión re-autenticada (id=$id); se abandona")
                    stopTicker()
                    savedStateHandle.remove<String>(KEY_DEPOSIT_ID)
                    _state.update { it.copy(phase = DepositPhase.FAILED, stepLabel = null, error = MSG_SESSION_REJECTED_TWICE) }
                    return@launch
                }
                Log.w(TAG, "pollDeposit: sesión SEP-10 caducada, re-autenticando (intento ${reauthAttempts + 1})…")
                anchorClient.clearSession(session.account)
                val route = existingRoute()
                if (route == null) {
                    stopTicker()
                    _state.update { it.copy(phase = DepositPhase.FAILED, error = "Sesión del anchor caducada.") }
                    return@launch
                }
                when (val r = anchorClient.authenticate(route.signer)) {
                    is RaizResult.Success -> beginPolling(r.data, id, reauthAttempts + 1)
                    is RaizResult.Error -> {
                        stopTicker()
                        _state.update { it.copy(phase = DepositPhase.FAILED, error = r.message) }
                    }
                }
            }
        }
    }

    /** Respaldo si el anchor no rellenó `stellar_transaction_id`: último pago entrante de ese asset. */
    private suspend fun fallbackTxHash(): String? {
        val result = horizonStream.latestIncomingPayment(state.value.account, RaizConstants.ANCHOR_USDC_CODE, anchorIssuer())
        return (result as? RaizResult.Success)?.data?.txHash
    }

    // ── Cierre: conversión al USDC del fondo y envío a la wallet ───────────────

    /** Resultado de un tramo que mueve dinero. */
    private sealed interface LegResult {
        /** Aplicado. [sequence] = secuencia de la tx: la foto siguiente debe reflejarla. */
        data class Done(val sequence: Long) : LegResult

        /** Enviado sin veredicto: el diario lo conserva y la vuelta siguiente lo resuelve por hash. */
        data object InFlight : LegResult

        /** No se movió nada (o falló de forma definitiva): la cadena se detiene y la card dice por qué. */
        data object Stop : LegResult
    }

    /** Qué pasó con la transacción en vuelo del diario. */
    private sealed interface PendingOutcome {
        data object None : PendingOutcome
        data object Cleared : PendingOutcome
        data class Waiting(val tx: PendingDepositTx) : PendingOutcome
        data class Applied(val sequence: Long) : PendingOutcome
    }

    private fun isSettlingOrInFlight(): Boolean {
        val current = state.value
        return settleJob?.isActive == true ||
            current.swap is SwapState.Submitting || current.swap is SwapState.Confirming ||
            current.forward is ForwardState.Sending || current.forward is ForwardState.Confirming
    }

    /**
     * Arranca el cierre si no hay otro en marcha.
     *
     * @param expectFunds el anchor acaba de marcar `completed`: si Horizon aún no refleja el
     *   pago, se relee unas veces antes de concluir que no hay nada que cerrar.
     */
    private fun settle(trigger: DepositPlan.Trigger, expectFunds: Boolean = false) {
        if (settleJob?.isActive == true) return
        settleJob = viewModelScope.launch {
            _state.update { it.copy(settling = true) }
            try {
                // Un solo cierre a la vez en TODO el proceso (ver DepositAccountManager.settlementLock).
                depositAccounts.settlementLock.withLock { runSettlement(trigger, expectFunds) }
            } finally {
                _state.update { it.copy(settling = false) }
            }
        }
    }

    /**
     * @param afterSequence secuencia de una transacción propia que ya entró justo antes de
     *   llamar (la conversión manual): la primera foto debe reflejarla, igual que las tx que
     *   aplique este mismo bucle.
     */
    private suspend fun runSettlement(
        trigger: DepositPlan.Trigger,
        expectFunds: Boolean,
        afterSequence: Long = 0L,
    ) {
        val route = when (val r = depositAccounts.route(createIfMissing = false)) {
            // Passkey sin cuenta de depósito todavía: no puede haber nada en camino.
            is RaizResult.Success -> r.data ?: return
            is RaizResult.Error -> {
                Log.w(TAG, "Depósito: no se pudo resolver la cuenta operativa (${r.code})")
                return
            }
        }
        if (state.value.account != route.account) _state.update { it.copy(account = route.account) }

        var legs = 0
        var pendingPolls = 0
        var staleReads = 0
        var emptyReads = 0
        // Secuencia mínima que debe traer la foto para fiarse de ella: la de la última tx propia
        // que ya entró. Horizon (varias réplicas) puede servir un instante una foto anterior.
        var minSequence = afterSequence
        var stillExpecting = expectFunds

        // Tx en vuelo que esta pantalla dejó marcada como "confirmando" en la vuelta anterior.
        var waitingFor: PendingDepositTx? = null

        while (legs < MAX_SETTLE_LEGS) {
            // 1. ¿Una tx propia en vuelo? Se resuelve por hash ANTES de mirar saldos o construir otra.
            when (val pending = resolvePendingTx(route)) {
                is PendingOutcome.Waiting -> {
                    waitingFor = pending.tx
                    pendingPolls++
                    // Sin veredicto tras el tope: la card conserva "aún no se confirma" y ofrece
                    // "Comprobar ahora" (el diario sigue impidiendo construir otra).
                    if (pendingPolls > MAX_PENDING_POLLS) return
                    delay(PENDING_POLL_MS)
                    continue
                }
                is PendingOutcome.Applied -> {
                    minSequence = maxOf(minSequence, pending.sequence)
                    stillExpecting = false
                    pendingPolls = 0
                    waitingFor = null
                }
                PendingOutcome.Cleared -> {
                    pendingPolls = 0
                    waitingFor = null
                }
                PendingOutcome.None -> {
                    // El diario ya no tiene la tx que aquí se estaba esperando: la resolvió otra
                    // corrutina (p. ej. la cola de una pantalla anterior). Se consulta una vez por
                    // su hash para publicar el resultado real en vez de dejar "aún no se confirma".
                    val orphan = waitingFor
                    waitingFor = null
                    if (orphan != null) {
                        pendingPolls = 0
                        if (pendingStatus(orphan) == DepositPlan.TxStatus.SUCCESS) {
                            publishApplied(orphan)
                            minSequence = maxOf(minSequence, orphan.sequence)
                            stillExpecting = false
                        } else {
                            clearConfirming(orphan)
                        }
                    }
                }
            }

            // 2. Foto de la cuenta operativa: la fuente de verdad de lo que falta.
            val snapshot = when (val r = horizonStream.accountSnapshot(route.account, anchorIssuer())) {
                is RaizResult.Success -> r.data
                is RaizResult.Error -> {
                    publishUnreadable(route)
                    return
                }
            }
            if (snapshot == null) {
                // La cuenta aún no existe on-chain (cuenta de depósito sin fondear): nada en camino.
                publishBalances(route, null, trustEmpty = true)
                return
            }
            if (!DepositPlan.isSnapshotFresh(snapshot.sequence, minSequence)) {
                // Horizon todavía no refleja la tx propia recién confirmada: decidir con esta
                // foto repetiría un tramo ya hecho. Se relee; si no se pone al día, se deja
                // para la próxima vez (nunca se muestra un fallo tras un éxito).
                staleReads++
                if (staleReads > MAX_STALE_READS) return
                delay(STALE_READ_DELAY_MS)
                continue
            }
            // Mientras se espera el pago recién anunciado por el anchor, una foto vacía no prueba
            // que no haya nada en camino (Horizon puede ir unos segundos por detrás).
            publishBalances(route, snapshot, trustEmpty = !stillExpecting)

            // 3. Tramo siguiente.
            val anchor = snapshot.anchorUsdcStroops ?: 0L
            val fund = snapshot.fundUsdcStroops ?: 0L
            when (DepositPlan.nextLeg(route.needsForward, anchor, fund)) {
                DepositPlan.Leg.NONE -> {
                    if (stillExpecting && emptyReads < MAX_EMPTY_READS) {
                        emptyReads++
                        delay(EMPTY_READ_DELAY_MS)
                        continue
                    }
                    onNothingPending()
                    return
                }

                DepositPlan.Leg.FORWARD -> {
                    stillExpecting = false
                    when (val result = forward(route, fund)) {
                        is LegResult.Done -> {
                            minSequence = maxOf(minSequence, result.sequence)
                            // Todo llegó a la wallet y no queda nada por convertir: se quita ya la
                            // constancia de "depósito en camino" (si la pantalla se cierra antes
                            // de la foto siguiente, Inicio la mostraría un instante de más).
                            if (anchor == 0L) depositAccounts.setInTransit(false)
                        }
                        LegResult.InFlight -> Unit
                        LegResult.Stop -> return
                    }
                    legs++
                }

                DepositPlan.Leg.CONVERT -> {
                    stillExpecting = false
                    val quoted = quote(anchor) ?: return
                    if (!DepositPlan.shouldConvert(trigger, route.needsForward, quoted.guard)) {
                        // Queda la card con la cotización (y el aviso, si no pasó la guarda): espera un tap.
                        Log.i(TAG, "Depósito: conversión no automática (trigger=$trigger, guarda=${quoted.guard})")
                        return
                    }
                    when (val result = convert(route, quoted, auto = true)) {
                        is LegResult.Done -> minSequence = maxOf(minSequence, result.sequence)
                        LegResult.InFlight -> Unit
                        LegResult.Stop -> return
                    }
                    legs++
                }
            }
        }
    }

    /**
     * Resuelve por hash la transacción propia que el diario tenga en vuelo (respuesta perdida,
     * proceso muerto tras enviar, o salida de la pantalla a mitad). Regla anti doble envío:
     * mientras pueda entrar todavía NO se construye otra ([DepositPlan.pendingVerdict]).
     */
    private suspend fun resolvePendingTx(route: DepositRoute): PendingOutcome {
        val pending = depositAccounts.pendingTx(route.account) ?: return PendingOutcome.None
        // "Ahora" = hora de la RED (cierre del último ledger), leída ANTES que el estado: un "no
        // existe" obtenido después de que la red pasara el `maxTime` de la tx (más el margen) es
        // definitivo. Si el RPC no responde se usa el reloj del teléfono.
        val now = sorobanClient.networkTimeSec() ?: nowSec()
        val status = pendingStatus(pending)
        return when (DepositPlan.pendingVerdict(status, now, pending.validUntilSec)) {
            DepositPlan.Verdict.APPLIED -> {
                Log.i(TAG, "Depósito: la tx en vuelo (${pending.kind}) entró, hash=${pending.hash}")
                publishApplied(pending)
                depositAccounts.clearPendingTx(route.account, pending.hash)
                PendingOutcome.Applied(pending.sequence)
            }
            DepositPlan.Verdict.SAFE_TO_RETRY -> {
                Log.i(TAG, "Depósito: la tx en vuelo (${pending.kind}) no entró ($status), hash=${pending.hash}")
                depositAccounts.clearPendingTx(route.account, pending.hash)
                clearConfirming(pending)
                PendingOutcome.Cleared
            }
            DepositPlan.Verdict.KEEP_WAITING -> {
                publishConfirming(pending)
                PendingOutcome.Waiting(pending)
            }
        }
    }

    /** Estado de una transacción propia según quien la recibió: Horizon (conversión) o el RPC (envío). */
    private suspend fun pendingStatus(pending: PendingDepositTx): DepositPlan.TxStatus = when (pending.kind) {
        DepositPlan.Leg.CONVERT -> horizonStream.transactionStatus(pending.hash)
        DepositPlan.Leg.FORWARD -> sorobanClient.transactionStatus(pending.hash)
        DepositPlan.Leg.NONE -> DepositPlan.TxStatus.UNKNOWN
    }

    private suspend fun publishApplied(pending: PendingDepositTx) {
        when (pending.kind) {
            DepositPlan.Leg.CONVERT -> {
                val alreadyShown = (state.value.swap as? SwapState.Done)?.txHash == pending.hash
                if (!alreadyShown) {
                    val received = horizonStream.strictSendReceivedStroops(pending.hash)
                    _state.update {
                        it.copy(
                            swap = SwapState.Done(
                                sendStroops = pending.amountStroops,
                                destStroops = received ?: 0L,
                                txHash = pending.hash,
                                destIsExact = received != null,
                            ),
                            swapStepLabel = null,
                        )
                    }
                }
            }
            DepositPlan.Leg.FORWARD -> _state.update {
                it.copy(forward = ForwardState.Done(pending.amountStroops, pending.hash))
            }
            DepositPlan.Leg.NONE -> Unit
        }
    }

    private fun publishConfirming(pending: PendingDepositTx) {
        _state.update {
            when (pending.kind) {
                DepositPlan.Leg.CONVERT -> it.copy(swap = SwapState.Confirming(pending.hash), swapStepLabel = null)
                DepositPlan.Leg.FORWARD ->
                    it.copy(forward = ForwardState.Confirming(pending.hash, pending.amountStroops, stalled = true))
                DepositPlan.Leg.NONE -> it
            }
        }
    }

    private fun clearConfirming(pending: PendingDepositTx) {
        _state.update {
            when {
                pending.kind == DepositPlan.Leg.CONVERT && it.swap is SwapState.Confirming ->
                    it.copy(swap = SwapState.Idle, swapStepLabel = null)
                pending.kind == DepositPlan.Leg.FORWARD && it.forward is ForwardState.Confirming ->
                    it.copy(forward = ForwardState.Idle)
                else -> it
            }
        }
    }

    /**
     * @param trustEmpty `false` cuando una foto sin saldo todavía no prueba que no haya nada en
     *   camino (se está esperando el pago que el anchor acaba de anunciar).
     */
    private fun publishBalances(route: DepositRoute, snapshot: ClassicAccountSnapshot?, trustEmpty: Boolean) {
        val anchor = snapshot?.anchorUsdcStroops ?: 0L
        val fund = if (route.needsForward) snapshot?.fundUsdcStroops ?: 0L else 0L
        _state.update {
            it.copy(
                anchorUsdcBalanceStroops = anchor,
                hasAnchorTrustline = snapshot?.anchorUsdcStroops != null,
                fundPendingStroops = fund,
            )
        }
        // Passkey: constancia local de si queda dinero en la cuenta de depósito (fila de Inicio,
        // aviso al cerrar sesión). No aplica a semilla: ahí el saldo ya es de la propia wallet.
        if (route.needsForward) {
            val inTransit = anchor > 0L || fund > 0L
            if (inTransit || trustEmpty) depositAccounts.setInTransit(inTransit)
        }
    }

    /**
     * No se pudo leer la cuenta: se conservan los saldos previos (un 0 falso ocultaría la card
     * justo tras el depósito) y la card ofrece "Reintentar". No pisa un resultado ya logrado.
     */
    private fun publishUnreadable(route: DepositRoute) {
        _state.update {
            val swapBusy = it.swap is SwapState.Submitting || it.swap is SwapState.Confirming
            val forwardSettled = it.forward is ForwardState.Done || it.forward is ForwardState.Confirming
            when {
                swapBusy || forwardSettled -> it
                // Conversión ya hecha: en passkey lo que falta es el envío a la wallet, y su card
                // es la que ofrece "Reintentar" (sin esto no quedaría ningún botón).
                it.swap is SwapState.Done ->
                    if (route.needsForward) it.copy(forward = ForwardState.Failed(MSG_DEPOSIT_ACCOUNT_UNREADABLE)) else it
                else -> it.copy(swap = SwapState.Failed(MSG_BALANCE_UNREADABLE), swapStepLabel = null)
            }
        }
    }

    /**
     * La foto dice que no queda nada por convertir ni por enviar. Sin saldo del anchor no hay
     * nada que cotizar; un resultado recién logrado se conserva para que el usuario siga viendo
     * el monto y el hash.
     */
    private fun onNothingPending() {
        _state.update {
            it.copy(
                // Aquí el diario está vacío y la cuenta no tiene nada pendiente: un "confirmando"
                // que quedara de antes ya no describe nada real y no se conserva.
                swap = when (it.swap) {
                    is SwapState.Done, is SwapState.Submitting -> it.swap
                    else -> SwapState.Idle
                },
                forward = when (it.forward) {
                    is ForwardState.Done -> it.forward
                    else -> ForwardState.Idle
                },
            )
        }
    }

    /**
     * Cotiza la conversión del saldo COMPLETO de USDC-anchor al USDC del fondo (Blend) vía el
     * pool de liquidez clásico de testnet y la clasifica frente a la guarda de precio. Guarda el
     * `path` devuelto para reutilizarlo tal cual al enviar — pedir una segunda cotización ahí
     * podría no coincidir con lo que el usuario vio en el botón.
     */
    private suspend fun quote(sendStroops: Long): SwapState.Quoted? {
        _state.update { it.copy(swap = SwapState.Quoting, swapStepLabel = null) }
        return when (
            val r = horizonStream.quoteStrictSend(
                sendCode = RaizConstants.ANCHOR_USDC_CODE,
                sendIssuer = anchorIssuer(),
                sendStroops = sendStroops,
                destCode = "USDC",
                destIssuer = blendUsdcIssuer,
            )
        ) {
            is RaizResult.Success -> {
                swapPath = r.data.path
                val quote = r.data.quote
                val quoted = SwapState.Quoted(
                    sendStroops = quote.sendStroops,
                    destStroops = quote.destStroops,
                    destMinStroops = quote.destMinStroops,
                    guard = SwapMath.quoteGuard(quote.sendStroops, quote.destStroops),
                )
                _state.update { it.copy(swap = quoted) }
                quoted
            }
            is RaizResult.Error -> {
                _state.update { it.copy(swap = SwapState.Failed(r.message)) }
                null
            }
        }
    }

    /**
     * Reintento tras [SwapState.Failed] / "Volver a cotizar" / "Terminar depósito": vuelve a
     * mirar la cuenta ANTES de cotizar (si el fallo fue ambiguo y la tx sí entró, el saldo real
     * ya es 0 y cotizar con el viejo llevaría a un bucle de `op_underfunded`) y resuelve antes
     * cualquier transacción propia en vuelo.
     */
    fun retryQuote() = settle(DepositPlan.Trigger.ON_OPEN)

    /** "Reintentar" del envío a la wallet passkey. */
    fun retryForward() = settle(DepositPlan.Trigger.ON_OPEN)

    /**
     * Tap en "Convertir N" / "Convertir de todos modos": envía la conversión YA cotizada (la que
     * el usuario está viendo; no se vuelve a cotizar) y, si entra, sigue con el resto del cierre
     * (en passkey, el envío a la wallet). Por debajo del suelo duro (50 %) no hace nada: la card
     * ni siquiera ofrece el botón.
     */
    fun convertAnchorUsdc() {
        val quoted = state.value.swap as? SwapState.Quoted ?: return
        if (settleJob?.isActive == true) return
        if (!DepositPlan.shouldConvert(DepositPlan.Trigger.MANUAL, state.value.isPasskey, quoted.guard)) return
        // Transición síncrona Quoted → Submitting: un segundo tap ya no encuentra Quoted.
        _state.update { it.copy(swap = SwapState.Submitting, swapStepLabel = null, settling = true) }
        settleJob = viewModelScope.launch {
            try {
                depositAccounts.settlementLock.withLock {
                    val route = existingRoute()
                    if (route == null) {
                        _state.update {
                            it.copy(swap = SwapState.Failed(DepositAccountManager.MSG_NO_SIGNER), swapStepLabel = null)
                        }
                        return@withLock
                    }
                    if (depositAccounts.pendingTx(route.account) != null) {
                        // Otra corrutina dejó una tx propia en vuelo mientras esta cotización
                        // estaba en pantalla: no se construye otra; el cierre normal la resuelve
                        // primero y vuelve a cotizar si hace falta.
                        runSettlement(DepositPlan.Trigger.ON_OPEN, expectFunds = false)
                        return@withLock
                    }
                    // Lo que venga después: el envío a la wallet (passkey); otra conversión solo si
                    // pasa la guarda. En semilla basta para refrescar el saldo de la card negra.
                    when (val result = convert(route, quoted, auto = false)) {
                        is LegResult.Done -> runSettlement(
                            DepositPlan.Trigger.AFTER_DEPOSIT,
                            expectFunds = false,
                            afterSequence = result.sequence,
                        )
                        LegResult.InFlight -> runSettlement(DepositPlan.Trigger.AFTER_DEPOSIT, expectFunds = false)
                        LegResult.Stop -> Unit
                    }
                }
            } finally {
                _state.update { it.copy(settling = false) }
            }
        }
    }

    /**
     * Envía la conversión cotizada: si falta la trustline al USDC del fondo, la crea primero;
     * luego firma y envía el `PathPaymentStrictSendOperation` (destino = la propia cuenta
     * operativa — NO custodial). Tras acreditarse lee de Horizon el monto REALMENTE recibido
     * (un strict-send entrega lo que dé el pool en ese ledger, ≥ `dest_min`, no lo cotizado).
     *
     * @param auto conversión automática: el `dest_min` es además un suelo duro del 97 % de lo
     *   enviado ([SwapMath.autoDestMin]); la manual usa el 1 % de tolerancia sobre la cotización
     *   con el suelo duro del 50 % ([SwapMath.manualDestMin]).
     */
    private suspend fun convert(route: DepositRoute, quoted: SwapState.Quoted, auto: Boolean): LegResult {
        _state.update { it.copy(swap = SwapState.Submitting, swapStepLabel = null) }

        if (!horizonStream.hasUsdcTrustline(route.account)) {
            _state.update {
                it.copy(
                    swapStepLabel = if (route.needsForward) {
                        STEP_TRUST_FUND_DEPOSIT_ACCOUNT
                    } else {
                        "Habilitando el USDC del fondo en tu cuenta…"
                    },
                )
            }
            when (val t = horizonStream.enableUsdcTrustline(route.signer)) {
                is RaizResult.Error -> {
                    _state.update { it.copy(swap = SwapState.Failed(t.message), swapStepLabel = null) }
                    return LegResult.Stop
                }
                is RaizResult.Success -> Unit
            }
        }

        _state.update { it.copy(swapStepLabel = "Enviando la conversión…") }
        val destMin = if (auto) {
            SwapMath.autoDestMin(quoted.sendStroops, quoted.destStroops)
        } else {
            SwapMath.manualDestMin(quoted.sendStroops, quoted.destStroops)
        }
        // Vigencia medida con la hora de la RED; solo si el RPC no responde, con la del teléfono.
        val maxTimeSec = sorobanClient.networkTimeSec()?.plus(CONVERT_VALIDITY_SEC)
        var signedSequence = 0L
        val journal = journalFor(route.account, DepositPlan.Leg.CONVERT, quoted.sendStroops) { _, sequence ->
            signedSequence = sequence
        }
        // Tramo que mueve dinero: envío + confirmación + diario terminan aunque se salga de la pantalla.
        val result = withContext(NonCancellable) {
            horizonStream.pathPaymentStrictSend(
                signer = route.signer,
                sendCode = RaizConstants.ANCHOR_USDC_CODE,
                sendIssuer = anchorIssuer(),
                sendStroops = quoted.sendStroops,
                destCode = "USDC",
                destIssuer = blendUsdcIssuer,
                destMinStroops = destMin,
                path = swapPath,
                journal = journal,
                maxTimeSec = maxTimeSec,
            )
        }
        return when (result) {
            is RaizResult.Success -> {
                Log.i(
                    TAG,
                    "Depósito: conversión ${if (auto) "automática" else "manual"} OK en ${route.account}, hash=${result.data}",
                )
                val received = horizonStream.strictSendReceivedStroops(result.data)
                _state.update {
                    it.copy(
                        swap = SwapState.Done(
                            sendStroops = quoted.sendStroops,
                            destStroops = received ?: quoted.destStroops,
                            txHash = result.data,
                            destIsExact = received != null,
                        ),
                        swapStepLabel = null,
                    )
                }
                LegResult.Done(signedSequence)
            }
            is RaizResult.Error -> {
                val inFlight = depositAccounts.pendingTx(route.account)?.takeIf { it.kind == DepositPlan.Leg.CONVERT }
                if (inFlight != null) {
                    // Enviada sin veredicto: no se declara fallo ni se ofrece reintentar todavía.
                    _state.update { it.copy(swap = SwapState.Confirming(inFlight.hash), swapStepLabel = null) }
                    LegResult.InFlight
                } else {
                    _state.update { it.copy(swap = SwapState.Failed(result.message), swapStepLabel = null) }
                    LegResult.Stop
                }
            }
        }
    }

    /**
     * Último tramo (solo passkey): `transfer` del SAC del USDC del fondo desde la cuenta de
     * depósito hacia el smart account `C…`. El smart account solo recibe — no hay huella.
     * Envía TODO el USDC del fondo que haya en la cuenta de depósito.
     *
     * Antes de enviar valida el destino (el `C…` guardado en el dispositivo, nunca otro) y que
     * exista en la red: el SAC acepta transferir a un `C…` sin desplegar y sería irrecuperable.
     */
    private suspend fun forward(route: DepositRoute, amountStroops: Long): LegResult {
        if (!DepositPlan.isValidForwardDestination(route.destination, depositAccounts.passkeyOwner())) {
            Log.w(TAG, "Depósito: el destino del envío no es la wallet passkey activa; no se envía")
            _state.update { it.copy(forward = ForwardState.Failed(MSG_FORWARD_FAILED)) }
            return LegResult.Stop
        }
        _state.update { it.copy(forward = ForwardState.Sending(amountStroops)) }

        when (val exists = sorobanClient.contractExists(route.destination)) {
            is RaizResult.Error -> {
                _state.update { it.copy(forward = ForwardState.Failed(MSG_FORWARD_FAILED)) }
                return LegResult.Stop
            }
            is RaizResult.Success -> if (!exists.data) {
                _state.update { it.copy(forward = ForwardState.Failed(MSG_WALLET_NOT_ON_CHAIN)) }
                return LegResult.Stop
            }
        }

        var attempt = 0
        while (true) {
            _state.update { it.copy(forward = ForwardState.Sending(amountStroops)) }
            var signedSequence = 0L
            val journal = journalFor(route.account, DepositPlan.Leg.FORWARD, amountStroops) { hash, sequence ->
                signedSequence = sequence
                _state.update { it.copy(forward = ForwardState.Confirming(hash, amountStroops, stalled = false)) }
            }
            // Tramo que mueve dinero: envío + confirmación + diario terminan aunque se salga de la pantalla.
            val result = withContext(NonCancellable) {
                sorobanClient.sacTransfer(route.signer, route.destination, amountStroops, journal)
            }
            when (result) {
                is RaizResult.Success -> {
                    Log.i(
                        TAG,
                        "Depósito: envío a la wallet OK ${route.account} → ${route.destination}, " +
                            "$amountStroops stroops, hash=${result.data}",
                    )
                    _state.update { it.copy(forward = ForwardState.Done(amountStroops, result.data)) }
                    return LegResult.Done(signedSequence)
                }
                is RaizResult.Error -> {
                    val inFlight = depositAccounts.pendingTx(route.account)?.takeIf { it.kind == DepositPlan.Leg.FORWARD }
                    if (inFlight != null) {
                        _state.update {
                            it.copy(forward = ForwardState.Confirming(inFlight.hash, amountStroops, stalled = true))
                        }
                        return LegResult.InFlight
                    }
                    // No salió nada a la red. Si falló la simulación puede ser que el RPC aún no
                    // vea el saldo recién convertido (Horizon y RPC ingieren por separado):
                    // reintentar es seguro.
                    attempt++
                    if (result.code == RaizErrorCode.SIMULATION_FAILED && attempt < FORWARD_PREPARE_ATTEMPTS) {
                        delay(FORWARD_RETRY_DELAY_MS)
                        continue
                    }
                    _state.update { it.copy(forward = ForwardState.Failed(MSG_FORWARD_FAILED)) }
                    return LegResult.Stop
                }
            }
        }
    }

    /**
     * Diario de una transacción propia: guarda (síncrono, cifrado) tipo, hash, monto, vigencia y
     * secuencia ANTES de enviarla, y lo borra cuando hay veredicto definitivo. Si no se puede
     * guardar, lanza y la transacción no se envía.
     */
    private fun journalFor(
        account: String,
        kind: DepositPlan.Leg,
        amountStroops: Long,
        onSigned: (hash: String, sequence: Long) -> Unit,
    ): TxJournal = object : TxJournal {
        override suspend fun signed(txHash: String, validUntilSec: Long, sequence: Long) {
            val saved = depositAccounts.savePendingTx(
                account,
                PendingDepositTx(kind, txHash, amountStroops, validUntilSec, sequence),
            )
            check(saved) { "no se pudo guardar el diario de la transacción" }
            onSigned(txHash, sequence)
        }

        override suspend fun settled(txHash: String) {
            depositAccounts.clearPendingTx(account, txHash)
        }
    }

    // ── Contingencia: USDC demo del relayer cuando el anchor no responde ───────

    /**
     * "Usar USDC demo (relayer)" de [DepositPhase.ANCHOR_UNAVAILABLE]: pide al relayer USDC del
     * fondo (Blend) directo a la WALLET del usuario (el `C…` en passkey, no la cuenta de
     * depósito). Misma idempotencia que `WalletViewModel.requestUsdcFaucet`: una
     * `idempotency-key` por intento, que se reutiliza en los reintentos (el relayer devuelve el
     * mismo resultado en vez de pagar dos veces) y se descarta al llegar el éxito. Aquí vive en
     * [SavedStateHandle] para sobrevivir también a la muerte del proceso.
     */
    fun requestDemoUsdc() {
        if (state.value.demoFaucet is DemoFaucetState.Requesting) return
        val target = walletManager.currentAccountId()
        if (target.isNullOrBlank()) {
            _state.update { it.copy(demoFaucet = DemoFaucetState.Failed("No hay wallet activa.")) }
            return
        }
        if (!relayerClient.isConfigured()) {
            _state.update { it.copy(demoFaucet = DemoFaucetState.Failed(MSG_RELAYER_NOT_CONFIGURED)) }
            return
        }
        _state.update { it.copy(demoFaucet = DemoFaucetState.Requesting(null)) }
        viewModelScope.launch {
            if (!state.value.isPasskey) {
                // G…: el relayer exige que la cuenta exista y tenga trustline al USDC del fondo.
                val route = existingRoute()
                if (route == null) {
                    _state.update { it.copy(demoFaucet = DemoFaucetState.Failed(DepositAccountManager.MSG_NO_SIGNER)) }
                    return@launch
                }
                if (!horizonStream.accountExists(target)) {
                    _state.update { it.copy(demoFaucet = DemoFaucetState.Requesting("Fondeando con friendbot…")) }
                    val funded = horizonStream.fundWithFriendbot(target)
                    if (funded is RaizResult.Error) {
                        _state.update { it.copy(demoFaucet = DemoFaucetState.Failed(funded.message)) }
                        return@launch
                    }
                }
                if (!horizonStream.hasUsdcTrustline(target)) {
                    _state.update {
                        it.copy(demoFaucet = DemoFaucetState.Requesting("Habilitando el USDC del fondo en tu cuenta…"))
                    }
                    val trusted = horizonStream.enableUsdcTrustline(route.signer)
                    if (trusted is RaizResult.Error) {
                        _state.update { it.copy(demoFaucet = DemoFaucetState.Failed(trusted.message)) }
                        return@launch
                    }
                }
            }

            _state.update { it.copy(demoFaucet = DemoFaucetState.Requesting("Pidiendo USDC demo al relayer…")) }
            // Idempotency-key por INTENTO: se reutiliza mientras el destino sea el mismo y no haya éxito.
            val attemptKey = savedStateHandle.get<String>(KEY_FAUCET_KEY)
                ?.takeIf { savedStateHandle.get<String>(KEY_FAUCET_TARGET) == target }
                ?: RelayerClient.newIdempotencyKey()
            savedStateHandle[KEY_FAUCET_KEY] = attemptKey
            savedStateHandle[KEY_FAUCET_TARGET] = target

            Log.i(TAG, "Faucet de contingencia vía relayer → $target attempt=${attemptKey.take(8)}")
            when (val result = relayerClient.faucet(target, idempotencyKey = attemptKey)) {
                is RaizResult.Success -> {
                    Log.i(
                        TAG,
                        "Faucet de contingencia OK: tx=${result.data.txHash} " +
                            "amount=${result.data.amountStroops} method=${result.data.method}",
                    )
                    // Éxito definitivo: la key no se reutiliza más.
                    savedStateHandle.remove<String>(KEY_FAUCET_KEY)
                    savedStateHandle.remove<String>(KEY_FAUCET_TARGET)
                    _state.update {
                        it.copy(demoFaucet = DemoFaucetState.Done(result.data.amountStroops, result.data.txHash))
                    }
                }
                // En error se conserva la key: el reintento es el mismo intento.
                is RaizResult.Error -> _state.update { it.copy(demoFaucet = DemoFaucetState.Failed(result.message)) }
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        _state.update { it.copy(elapsedSec = 0) }
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                _state.update { it.copy(elapsedSec = it.elapsedSec + 1) }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private companion object {
        const val TAG = "RAIZ"
        const val KEY_DEPOSIT_ID = "deposit_id"
        const val KEY_FAUCET_KEY = "demo_faucet_key"
        const val KEY_FAUCET_TARGET = "demo_faucet_target"
        /** Re-autenticaciones por [AnchorSessionExpired] permitidas por cadena de polling (contrato WP3: "una vez"). */
        const val MAX_REAUTH_ATTEMPTS = 1

        /** Vigencia (`maxTime`) de la tx de conversión, contada desde la hora de la red. */
        const val CONVERT_VALIDITY_SEC = 60L

        /** Tramos (conversión / envío) por cierre: lo normal son 1 (semilla) o 2 (passkey). */
        const val MAX_SETTLE_LEGS = 4
        /** Sondeos de una tx propia en vuelo: 5 s × 30 cubre la vigencia de la tx (60 s) + margen (60 s). */
        const val MAX_PENDING_POLLS = 30
        const val PENDING_POLL_MS = 5_000L
        /** Relecturas mientras Horizon se pone al día con una tx propia ya confirmada. */
        const val MAX_STALE_READS = 8
        const val STALE_READ_DELAY_MS = 2_000L
        /** Relecturas tras el `completed` del anchor mientras Horizon aún no refleja su pago (≈ 20 s). */
        const val MAX_EMPTY_READS = 8
        const val EMPTY_READ_DELAY_MS = 2_500L
        /** Intentos de preparar el envío a la wallet cuando falla la simulación (nada se envió). */
        const val FORWARD_PREPARE_ATTEMPTS = 4
        const val FORWARD_RETRY_DELAY_MS = 2_500L
        /** Pasadas para dejar lista la cuenta de depósito (friendbot + trustlines). */
        const val DEPOSIT_ACCOUNT_SETUP_PASSES = 3
        const val DEPOSIT_ACCOUNT_SETUP_RETRY_MS = 3_000L
        /** Tras friendbot, lecturas hasta que Horizon ve la cuenta de depósito. */
        const val ACCOUNT_VISIBLE_ATTEMPTS = 4
        const val ACCOUNT_VISIBLE_DELAY_MS = 2_000L

        /** Comprobando la cuenta de depósito (ya existe casi siempre); "Creando…" solo la primera vez. */
        const val STEP_PREPARING_DEPOSIT_ACCOUNT = "Preparando tu cuenta de depósito…"
        const val STEP_CREATING_DEPOSIT_ACCOUNT = "Creando tu cuenta de depósito…"
        const val STEP_TRUST_FUND_DEPOSIT_ACCOUNT = "Habilitando USDC del fondo en tu cuenta de depósito…"

        const val MSG_SESSION_REJECTED_TWICE = "El anchor rechazó la sesión dos veces seguidas. Reintenta el depósito."
        const val MSG_UNSAFE_URL = "El anchor devolvió una URL no segura."
        const val MSG_BALANCE_UNREADABLE = "No se pudo leer tu saldo del anchor. Revisa la red y reintenta."
        const val MSG_ANCHOR_UNREACHABLE = "No se pudo hablar con el anchor de prueba."
        const val MSG_FRIENDBOT_FAILED =
            "No pudimos crear tu cuenta de depósito (friendbot no respondió). Reintenta en unos segundos."
        const val MSG_DEPOSIT_ACCOUNT_UNREADABLE =
            "No se pudo leer tu cuenta de depósito. Revisa la red y reintenta."
        const val MSG_DEPOSIT_ACCOUNT_SETUP_FAILED =
            "No pudimos preparar tu cuenta de depósito. Reintenta en unos segundos."
        const val MSG_DEPOSIT_ACCOUNT_NO_XLM =
            "Tu cuenta de depósito se quedó sin XLM de testnet para operar."
        const val MSG_FORWARD_FAILED = "No se pudo enviar a tu wallet. Tu USDC sigue en tu cuenta de depósito."
        const val MSG_WALLET_NOT_ON_CHAIN =
            "Tu wallet passkey todavía no aparece en la red. Tu USDC sigue en tu cuenta de depósito; " +
                "reintenta en unos minutos."
        const val MSG_RELAYER_NOT_CONFIGURED = "Relayer no configurado (raiz.relayer.url en local.properties)"

        val RESUMABLE_PHASES = setOf(DepositPhase.AWAITING_USER, DepositPhase.POLLING, DepositPhase.TIMED_OUT)
        /** Fases en las que, al volver a la pantalla, una wallet passkey termina lo que quedó en camino. */
        val SETTLE_ON_RESUME_PHASES =
            setOf(DepositPhase.READY, DepositPhase.COMPLETED, DepositPhase.ANCHOR_UNAVAILABLE)
    }
}
