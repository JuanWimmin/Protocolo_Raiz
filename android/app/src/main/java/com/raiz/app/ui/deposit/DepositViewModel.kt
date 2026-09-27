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
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.HorizonStream
import com.raiz.app.data.stellar.WalletManager
import com.soneso.stellar.sdk.KeyPair
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import javax.inject.Inject

/**
 * Fases del depósito SEP-24. La Screen dibuja una card distinta por fase;
 * ver `ui/deposit/DepositScreen.kt`.
 */
enum class DepositPhase {
    LOADING_INFO,        // Cargando toml + /info del anchor.
    READY,                // Listo para pedir el monto y depositar.
    PASSKEY_UNSUPPORTED, // Wallet C... (smart account): SEP-10 clásico no la autentica.
    NEEDS_XLM,            // La cuenta G... aún no existe on-chain.
    PREPARING,            // Trustline → SEP-10 → SEP-24 interactive (stepLabel indica cuál).
    AWAITING_USER,        // El anchor espera que el usuario complete el flujo en su web.
    POLLING,               // El anchor está procesando (pending_anchor/stellar/external/trust).
    COMPLETED,             // Depósito acreditado.
    FAILED,                 // Error irrecuperable (o el anchor devolvió refunded/expired/error).
    TIMED_OUT,             // Se venció el timeout de polling sin llegar a un estado terminal.
}

data class DepositUiState(
    val phase: DepositPhase = DepositPhase.LOADING_INFO,
    val account: String = "",
    val isPasskey: Boolean = false,
    val anchorDomain: String = RaizConstants.ANCHOR_HOME_DOMAIN,
    val minUsdc: Double? = null,
    val maxUsdc: Double? = null,
    val amountInput: String = "5",
    val amountError: String? = null,
    /** null = desconocido todavía (aún no se consultó Horizon). */
    val hasAnchorTrustline: Boolean? = null,
    val anchorUsdcBalanceStroops: Long = 0L,
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
)

/**
 * ViewModel de la pantalla "Depositar" (SEP-24 con el testanchor del SDF).
 *
 * Flujo feliz:
 *   1. `loadInfo()` — toml + `/info` del anchor (min/max, issuer real).
 *   2. Si la wallet activa es passkey (C…) → [DepositPhase.PASSKEY_UNSUPPORTED],
 *      SEP-10 clásico no autentica smart accounts (ver contrato WP3).
 *   3. Si la cuenta G… no existe on-chain → [DepositPhase.NEEDS_XLM].
 *   4. `startDeposit()`: trustline (si falta) → SEP-10 → SEP-24 interactive →
 *      abre Custom Tabs (evento [openUrl]) → arranca el polling inmediatamente
 *      (el usuario puede volver de la pestaña en cualquier momento; el estado
 *      ya viene avanzando).
 *   5. El polling (`AnchorClient.pollDeposit`) emite cada cambio de estado; al
 *      llegar a `COMPLETED` refresca el saldo del asset del anchor y guarda el
 *      hash. `REFUNDED`/`EXPIRED`/`ERROR` → [DepositPhase.FAILED]. Timeout de
 *      polling → [DepositPhase.TIMED_OUT] (el usuario puede "Seguir esperando").
 *
 * El `deposit_id` en curso se guarda en [SavedStateHandle] para sobrevivir a
 * la vuelta desde el Custom Tab (proceso puede morir en background) — al
 * recrearse el VM, si hay un id guardado, se retoma el polling.
 */
@HiltViewModel
class DepositViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val horizonStream: HorizonStream,
    private val anchorClient: AnchorClient,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(DepositUiState())
    val state: StateFlow<DepositUiState> = _state.asStateFlow()

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

    init {
        val accountId = walletManager.currentAccountId().orEmpty()
        val isPasskey = walletManager.isPasskeyWallet()
        _state.update { it.copy(account = accountId, isPasskey = isPasskey) }

        if (isPasskey) {
            // SEP-10 clásico firma un challenge con una cuenta Ed25519 (G…); un
            // smart account (C…) necesitaría SEP-45 (WebAuthn), que el anchor de
            // prueba todavía no soporta. No tiene sentido cargar info del anchor.
            _state.update { it.copy(phase = DepositPhase.PASSKEY_UNSUPPORTED) }
        } else {
            loadInfo()
        }
    }

    private fun loadInfo() {
        viewModelScope.launch {
            _state.update { it.copy(phase = DepositPhase.LOADING_INFO, error = null) }
            when (val r = anchorClient.loadInfo()) {
                is RaizResult.Success -> {
                    val info = r.data
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
                is RaizResult.Error -> _state.update {
                    it.copy(phase = DepositPhase.FAILED, error = r.message)
                }
            }
        }
    }

    private fun checkAccount() {
        viewModelScope.launch {
            val accountId = state.value.account
            if (accountId.isBlank()) {
                _state.update { it.copy(phase = DepositPhase.FAILED, error = "No hay wallet activa.") }
                return@launch
            }
            if (!horizonStream.accountExists(accountId)) {
                _state.update { it.copy(phase = DepositPhase.NEEDS_XLM) }
                return@launch
            }
            refreshAnchorBalance()
            _state.update { it.copy(phase = DepositPhase.READY) }

            // Restauración: había un depósito en curso cuando el proceso murió
            // (el usuario estaba en el Custom Tab) → retoma el polling.
            val savedId = savedStateHandle.get<String>(KEY_DEPOSIT_ID)
            if (savedId != null) {
                _state.update { it.copy(phase = DepositPhase.AWAITING_USER, depositId = savedId) }
                resumePolling()
            }
        }
    }

    /** Paso previo a [DepositPhase.NEEDS_XLM]: friendbot. */
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

    /**
     * Dispara el flujo completo: trustline (si falta) → SEP-10 → SEP-24
     * interactive → abre Custom Tabs → arranca el polling.
     */
    fun startDeposit() {
        val current = state.value
        if (current.phase != DepositPhase.READY) return
        // Revalida aquí en vez de confiar solo en amountError: el monto inicial o un
        // cambio de límites del anchor podrían no haber pasado por onAmountChange.
        val amountError = validateAmount(current.amountInput)
        if (amountError != null) {
            _state.update { it.copy(amountError = amountError) }
            return
        }

        viewModelScope.launch {
            val accountId = current.account
            val kp = walletManager.currentKeyPair()
            if (kp == null) {
                _state.update { it.copy(phase = DepositPhase.FAILED, error = "No se pudo firmar: no hay wallet activa.") }
                return@launch
            }

            _state.update { it.copy(phase = DepositPhase.PREPARING, stepLabel = null, error = null) }

            if (!horizonStream.accountExists(accountId)) {
                _state.update { it.copy(phase = DepositPhase.NEEDS_XLM) }
                return@launch
            }

            val info = anchorClient.loadInfo().getOrNull()
            if (info == null) {
                _state.update { it.copy(phase = DepositPhase.FAILED, error = "No se pudo hablar con el anchor de prueba.") }
                return@launch
            }

            // (a) Trustline al USDC del anchor.
            _state.update { it.copy(stepLabel = "Habilitando USDC del anchor en tu cuenta…") }
            val alreadyTrusts = horizonStream.hasTrustline(accountId, RaizConstants.ANCHOR_USDC_CODE, info.usdcIssuer)
            if (!alreadyTrusts) {
                when (val r = horizonStream.enableTrustline(kp, RaizConstants.ANCHOR_USDC_CODE, info.usdcIssuer)) {
                    is RaizResult.Error -> {
                        _state.update { it.copy(phase = DepositPhase.FAILED, error = r.message) }
                        return@launch
                    }
                    is RaizResult.Success -> Unit
                }
            }
            _state.update { it.copy(hasAnchorTrustline = true) }

            // (b) Autenticación SEP-10 (reutiliza sesión cacheada si sirve).
            _state.update { it.copy(stepLabel = "Autenticando con el anchor (SEP-10)…") }
            val session = obtainSession(kp) ?: return@launch

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
                is RaizResult.Error -> _state.update {
                    it.copy(phase = DepositPhase.FAILED, error = r.message)
                }
            }
        }
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
            val kp = walletManager.currentKeyPair()
            if (kp == null) {
                _state.update { it.copy(phase = DepositPhase.FAILED, error = "No se pudo firmar: no hay wallet activa.") }
                return@launch
            }
            val session = obtainSession(kp) ?: return@launch
            beginPolling(session, id)
        }
    }

    /** Llamado por la Screen en ON_RESUME (p. ej. al volver del Custom Tab). */
    fun onResume() {
        val phase = state.value.phase
        if (!isPollingOrResuming() && phase in RESUMABLE_PHASES) {
            resumePolling()
        }
    }

    private fun isPollingOrResuming(): Boolean =
        pollJob?.isActive == true || resumeJob?.isActive == true

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
            )
        }
    }

    /**
     * @param reauthAttempts cuántas veces ya se re-autenticó por [AnchorSessionExpired]
     *   en esta cadena de polling. El contrato pide "re-autenticar UNA vez y seguir":
     *   al superar [MAX_REAUTH_ATTEMPTS] se pasa a [DepositPhase.FAILED] en vez de
     *   entrar en un bucle sin fin de challenge → token → 403 → challenge…
     */
    private fun beginPolling(session: AnchorSession, id: String, reauthAttempts: Int = 0) {
        if (!isTracking(id)) {
            Log.w(TAG, "beginPolling: el depósito $id ya no está en curso (phase=${state.value.phase}); no se arranca")
            return
        }
        pollJob?.cancel()
        startTicker()
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
                                val kp = walletManager.currentKeyPair()
                                val info = anchorClient.loadInfo().getOrNull()
                                if (kp != null && info != null) {
                                    horizonStream.enableTrustline(kp, RaizConstants.ANCHOR_USDC_CODE, info.usdcIssuer)
                                }
                            }
                        }

                        AnchorDepositState.COMPLETED -> {
                            stopTicker()
                            savedStateHandle.remove<String>(KEY_DEPOSIT_ID)
                            val hash = status.stellarTxHash ?: fallbackTxHash()
                            refreshAnchorBalance()
                            _state.update {
                                it.copy(phase = DepositPhase.COMPLETED, txHash = hash, stepLabel = null)
                            }
                        }

                        AnchorDepositState.REFUNDED, AnchorDepositState.EXPIRED, AnchorDepositState.ERROR -> {
                            stopTicker()
                            savedStateHandle.remove<String>(KEY_DEPOSIT_ID)
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
                val kp = walletManager.currentKeyPair()
                if (kp == null) {
                    stopTicker()
                    _state.update { it.copy(phase = DepositPhase.FAILED, error = "Sesión del anchor caducada.") }
                    return@launch
                }
                when (val r = anchorClient.authenticate(kp)) {
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
        val info = anchorClient.loadInfo().getOrNull() ?: return null
        val result = horizonStream.latestIncomingPayment(state.value.account, RaizConstants.ANCHOR_USDC_CODE, info.usdcIssuer)
        return (result as? RaizResult.Success)?.data?.txHash
    }

    private fun refreshAnchorBalance() {
        viewModelScope.launch {
            val info = anchorClient.loadInfo().getOrNull() ?: return@launch
            val stroops = horizonStream.getAssetBalance(state.value.account, RaizConstants.ANCHOR_USDC_CODE, info.usdcIssuer)
            _state.update { it.copy(anchorUsdcBalanceStroops = stroops) }
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
        /** Re-autenticaciones por [AnchorSessionExpired] permitidas por cadena de polling (contrato WP3: "una vez"). */
        const val MAX_REAUTH_ATTEMPTS = 1
        const val MSG_SESSION_REJECTED_TWICE = "El anchor rechazó la sesión dos veces seguidas. Reintenta el depósito."
        const val MSG_UNSAFE_URL = "El anchor devolvió una URL no segura."
        val RESUMABLE_PHASES = setOf(DepositPhase.AWAITING_USER, DepositPhase.POLLING, DepositPhase.TIMED_OUT)
    }
}
