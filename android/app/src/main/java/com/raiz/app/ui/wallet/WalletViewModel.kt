package com.raiz.app.ui.wallet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.PassportData
import com.raiz.app.data.model.PassportLevel
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.data.relayer.RelayerClient
import com.raiz.app.data.stellar.DeploymentsLoader
import com.raiz.app.data.stellar.HorizonStream
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import com.soneso.stellar.sdk.KeyPair
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado del onboarding on-chain del wallet activo. Una wallet recién creada
 * tiene que pasar por estos 3 pasos antes de poder operar como turista o
 * comerciante. El banner de WalletScreen muestra uno solo a la vez según el
 * step actual; al completarse todos se oculta.
 */
enum class AccountSetupStep {
    FUND_XLM,           // La cuenta no existe en Stellar todavía (falta XLM).
    ACTIVATE_TRUSTLINE, // Cuenta existe pero no tiene trustline USDC.
    REQUEST_USDC,       // Trustline OK pero balance USDC = 0 (demo faucet).
    DONE,               // Lista para usar.
}

sealed interface WalletUiState {
    data object Loading : WalletUiState
    data class Ready(
        val wallet: WalletState,
        val poolBalanceLabel: String,
        val passport: PassportData? = null,
        val setupStep: AccountSetupStep = AccountSetupStep.DONE,
        val setupInProgress: Boolean = false,
        val setupError: String? = null,
        /** false si falta `raiz.relayer.url` / `raiz.relayer.key` en local.properties. */
        val relayerConfigured: Boolean = true,
        /**
         * `idempotency-key` del intento de faucet en curso (H1): se conserva en
         * los reintentos (misma key → el relayer devuelve el mismo `txHash`, no
         * paga dos veces) y se descarta al llegar el éxito.
         */
        val faucetAttemptKey: String? = null,
        /** Address (body del faucet) con la que se generó [faucetAttemptKey]; si cambia, key nueva. */
        val faucetAttemptFingerprint: String? = null,
    ) : WalletUiState
    data class Error(val message: String) : WalletUiState
}

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val horizonStream: HorizonStream,
    private val deploymentsLoader: DeploymentsLoader,
    private val relayerClient: RelayerClient,
) : ViewModel() {

    private val deployments by lazy { deploymentsLoader.load() }

    private val _state = MutableStateFlow<WalletUiState>(
        WalletUiState.Ready(
            // Arranca con `points = 0` hasta que getPoints responda; las
            // demás propiedades del wallet vienen del mock por ahora.
            wallet = walletManager.mockWallet().copy(points = 0L),
            poolBalanceLabel = "Conectando…",
            relayerConfigured = relayerClient.isConfigured(),
        ),
    )
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    init {
        observeUsdcBalance()
        loadCentroPoolBalance()
        loadPassport()
        refreshSetupStep()
        // Auto-refresco periódico — un solo loop para no duplicar coroutines.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(AUTO_REFRESH_INTERVAL_MS)
                refresh()
            }
        }
    }

    /**
     * Detecta el siguiente paso de onboarding del wallet activo:
     *   FUND_XLM → ACTIVATE_TRUSTLINE → REQUEST_USDC → DONE
     * Re-llamar tras cada acción (fondear/activar/pedir) para avanzar el banner.
     */
    private fun refreshSetupStep() {
        viewModelScope.launch {
            val accountId = walletManager.currentAccountId() ?: return@launch

            // Las wallets passkey son SMART ACCOUNTS (C...): el relayer de Soneso ya
            // las despliega y fondea con XLM al crearlas. No necesitan friendbot ni
            // trustline clásica (su USDC vive vía SAC del contrato, no en la cuenta).
            // El único paso pendiente es tener saldo USDC > 0; lo verificamos via SAC.
            if (walletManager.isPasskeyWallet()) {
                Log.i(TAG, "Wallet passkey (smart account): verificando saldo USDC via SAC…")
                val step = when (val r = sorobanClient.usdcBalanceOfContract(accountId)) {
                    is RaizResult.Success -> {
                        Log.i(TAG, "Balance SAC del smart account $accountId: ${r.data} stroops")
                        if (r.data > 0L) AccountSetupStep.DONE else AccountSetupStep.REQUEST_USDC
                    }
                    is RaizResult.Error -> {
                        // Si la lectura SAC falla (red, contrato no inicializado), asumimos
                        // sin saldo para que el banner aparezca y el usuario pueda pedir USDC.
                        Log.w(TAG, "usdcBalanceOfContract para passkey falló: ${r.message} → REQUEST_USDC")
                        AccountSetupStep.REQUEST_USDC
                    }
                }
                _state.update { current ->
                    if (current is WalletUiState.Ready)
                        current.copy(setupStep = step, setupError = null)
                    else current
                }
                return@launch
            }

            // Rama clásica (G...): Horizon para existencia, trustline y saldo.
            val step = when {
                !horizonStream.accountExists(accountId) -> AccountSetupStep.FUND_XLM
                !horizonStream.hasUsdcTrustline(accountId) -> AccountSetupStep.ACTIVATE_TRUSTLINE
                horizonStream.getUsdcBalance(accountId) == 0L -> AccountSetupStep.REQUEST_USDC
                else -> AccountSetupStep.DONE
            }
            Log.i(TAG, "Setup step para $accountId: $step")
            _state.update { current ->
                if (current is WalletUiState.Ready) current.copy(setupStep = step, setupError = null)
                else current
            }
        }
    }

    /** Step 1: friendbot. */
    fun fundWithFriendbot() {
        runSetupAction { signer, accountId ->
            // friendbot no requiere firma; el signer lo ignoramos aquí.
            horizonStream.fundWithFriendbot(accountId)
        }
    }

    /** Step 2: activar trustline USDC. */
    fun activateUsdcTrustline() {
        runSetupAction { signer, _ ->
            if (signer == null) return@runSetupAction RaizResult.Error(
                com.raiz.app.data.model.RaizErrorCode.UNKNOWN,
                "No hay wallet activa para firmar.",
            )
            horizonStream.enableUsdcTrustline(signer)
        }
    }

    /**
     * Step 3: pedir USDC de prueba (faucet demo, ahora vía `raiz-relayer`).
     *
     * El relayer decide server-side el método de entrega según el prefijo de
     * la dirección: `payment` clásico para G… o `sac_transfer` para C… (smart
     * account passkey, solo si ya está desplegada). La app ya no firma nada
     * como admin — solo pide el faucet con la API key estática.
     */
    fun requestUsdcFaucet() {
        viewModelScope.launch {
            val accountId = walletManager.currentAccountId() ?: return@launch
            if (!relayerClient.isConfigured()) {
                _state.update { current ->
                    if (current is WalletUiState.Ready) current.copy(
                        setupInProgress = false,
                        setupError = "Relayer no configurado (raiz.relayer.url / raiz.relayer.key en local.properties)",
                    ) else current
                }
                return@launch
            }
            // H1: idempotency-key por INTENTO. Volver a pulsar "Pedir USDC de prueba"
            // tras un error (timeout, red) reutiliza la key → el relayer devuelve el
            // mismo resultado (10 min de caché) en vez de pagar un segundo faucet.
            val ready = _state.value as? WalletUiState.Ready
            val attemptKey = ready?.let { r -> r.faucetAttemptKey?.takeIf { r.faucetAttemptFingerprint == accountId } }
                ?: RelayerClient.newIdempotencyKey()
            _state.update { current ->
                if (current is WalletUiState.Ready) {
                    current.copy(faucetAttemptKey = attemptKey, faucetAttemptFingerprint = accountId)
                } else current
            }
            beginSetupAction()

            Log.i(TAG, "Faucet vía relayer → $accountId attempt=${attemptKey.take(8)}")
            val result = relayerClient.faucet(accountId, idempotencyKey = attemptKey)
            if (result is RaizResult.Success) {
                Log.i(
                    TAG,
                    "Faucet OK: tx=${result.data.txHash} amount=${result.data.amountStroops} " +
                        "method=${result.data.method}",
                )
                // Éxito definitivo: la key no se reutiliza más (el próximo faucet es otro intento).
                _state.update { current ->
                    if (current is WalletUiState.Ready) {
                        current.copy(faucetAttemptKey = null, faucetAttemptFingerprint = null)
                    } else current
                }
            }
            // En error se conserva faucetAttemptKey: el reintento es el mismo intento.
            finishSetupAction(result.map { Unit })
        }
    }

    /**
     * Plantilla para las acciones de onboarding que necesitan signer + accountId
     * y comparten el mismo ciclo de estado (in_progress → ok/err → re-check step).
     */
    private fun runSetupAction(
        action: suspend (signer: KeyPair?, accountId: String) -> RaizResult<Unit>,
    ) {
        viewModelScope.launch {
            val accountId = walletManager.currentAccountId() ?: return@launch
            val signer = walletManager.currentKeyPair()
            beginSetupAction()
            val result = action(signer, accountId)
            finishSetupAction(result)
        }
    }

    private fun beginSetupAction() {
        _state.update { current ->
            if (current is WalletUiState.Ready)
                current.copy(setupInProgress = true, setupError = null)
            else current
        }
    }

    private fun finishSetupAction(result: RaizResult<Unit>) {
        when (result) {
            is RaizResult.Success -> {
                _state.update { current ->
                    if (current is WalletUiState.Ready)
                        current.copy(setupInProgress = false, setupError = null)
                    else current
                }
                // Pequeña espera para que Horizon/Soroban procese antes del re-check.
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1500L)
                    // Bug 3: para passkey, el saldo vive en el SAC — no hay SSE,
                    // por lo que observeUsdcBalance() no se auto-actualiza. Releemos
                    // explícitamente para que el BalanceCard refleje el nuevo saldo
                    // sin requerir reiniciar la app.
                    if (walletManager.isPasskeyWallet()) refreshPasskeyBalance()
                    refreshSetupStep()
                }
            }
            is RaizResult.Error -> _state.update { current ->
                if (current is WalletUiState.Ready)
                    current.copy(setupInProgress = false, setupError = result.message)
                else current
            }
        }
    }

    /**
     * Bug 3 — Re-lectura del saldo USDC del smart account (C...) vía SAC.
     *
     * Solo tiene efecto en wallets passkey. Los smart accounts no tienen
     * trustline clásica ni aparecen en Horizon accounts API, así que Horizon
     * SSE nunca los actualiza. Tras el faucet leemos el saldo directamente del
     * Stellar Asset Contract y lo propagamos al WalletState.
     */
    private suspend fun refreshPasskeyBalance() {
        val accountId = walletManager.currentAccountId() ?: return
        when (val r = sorobanClient.usdcBalanceOfContract(accountId)) {
            is RaizResult.Success -> {
                Log.i(TAG, "Saldo SAC post-faucet: ${r.data} stroops (${r.data.formatUsdc()})")
                _state.update { current ->
                    if (current is WalletUiState.Ready)
                        current.copy(wallet = current.wallet.copy(usdcBalanceStroops = r.data))
                    else current
                }
            }
            is RaizResult.Error ->
                Log.w(TAG, "refreshPasskeyBalance: ${r.message}")
        }
    }

    /**
     * Carga el balance USDC y lo refleja en el WalletState.
     *
     * Rama passkey (C...): una lectura directa al SAC (Stellar Asset Contract)
     * porque Horizon no conoce el balance de contratos Soroban. Los smart accounts
     * no tienen trustline clásica — su saldo vive en el storage del SAC.
     *
     * Rama clásica (G...): Horizon SSE via [HorizonStream.usdcBalanceFlow] para
     * actualizaciones en tiempo real.
     */
    private fun observeUsdcBalance() {
        viewModelScope.launch {
            val accountId = walletManager.mockWallet().publicKey

            if (walletManager.isPasskeyWallet()) {
                // Los smart accounts C... no aparecen en la Horizon accounts API;
                // consultamos SAC.balance(id=C...) una sola vez al iniciar.
                Log.i(TAG, "Balance SAC para smart account $accountId")
                when (val r = sorobanClient.usdcBalanceOfContract(accountId)) {
                    is RaizResult.Success -> {
                        Log.i(TAG, "Balance SAC: ${r.data} stroops (${r.data.formatUsdc()})")
                        _state.update { current ->
                            if (current is WalletUiState.Ready)
                                current.copy(wallet = current.wallet.copy(usdcBalanceStroops = r.data))
                            else current
                        }
                    }
                    is RaizResult.Error ->
                        Log.w(TAG, "usdcBalanceOfContract falló: ${r.message}")
                }
                return@launch
            }

            // Rama clásica: SSE de Horizon para actualizaciones en tiempo real.
            Log.i(TAG, "Suscribiéndome al balance USDC de $accountId")
            horizonStream.usdcBalanceFlow(accountId).collect { stroops ->
                Log.i(TAG, "Balance USDC actualizado: $stroops stroops (${stroops.formatUsdc()})")
                _state.update { current ->
                    if (current is WalletUiState.Ready) {
                        current.copy(wallet = current.wallet.copy(usdcBalanceStroops = stroops))
                    } else current
                }
            }
        }
    }

    /** Smoke test del Soroban RPC: lee saldo del pool Centro Histórico. */
    private fun loadCentroPoolBalance() {
        viewModelScope.launch {
            Log.i(TAG, "Llamando get_pool_balance para Centro Histórico…")
            val result = sorobanClient.getPoolBalance(BARRIO_CENTRO_ID)
            when (result) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Pool balance Centro: ${result.data} stroops (${result.data.formatUsdc()})")
                    _state.update { current ->
                        if (current is WalletUiState.Ready) {
                            current.copy(poolBalanceLabel = "Centro: ${result.data.formatUsdc()}")
                        } else current
                    }
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "getPoolBalance falló: ${result.code} — ${result.message}")
                    _state.update { current ->
                        if (current is WalletUiState.Ready) {
                            current.copy(poolBalanceLabel = "Sin red")
                        } else current
                    }
                }
            }
        }
    }

    /**
     * Carga datos del RAÍZ Passport:
     * 1. Trae los puntos del turista vía Rewards.get_points (REAL).
     * 2. Trae los merchants de los 3 barrios → mapa addr→barrio.
     * 3. Trae el historial de pagos del turista.
     * 4. Calcula aporte al pool, transacciones locales, barrios visitados.
     *
     * Actualiza tanto `wallet.points` como `passport` con la misma fuente.
     */
    private fun loadPassport() {
        viewModelScope.launch {
            val accountId = walletManager.mockWallet().publicKey

            // 1. Puntos reales del contrato Rewards. Esta es la ÚNICA fuente
            //    de "puntos" en toda la app — la UI lee siempre desde aquí.
            val points = when (val r = sorobanClient.getPoints(accountId)) {
                is RaizResult.Success -> r.data
                is RaizResult.Error -> {
                    Log.w(TAG, "getPoints en passport: ${r.message}")
                    0L
                }
            }
            // Propaga al WalletState inmediatamente para que el StatBox
            // "Puntos" se actualice incluso si las otras lecturas se demoran.
            _state.update { current ->
                if (current is WalletUiState.Ready) {
                    current.copy(wallet = current.wallet.copy(points = points))
                } else current
            }

            // 2. Mapa merchant → barrio.
            val merchantToBarrio: Map<String, String> = buildMap {
                BARRIOS.forEach { (id, _) ->
                    when (val r = sorobanClient.listMerchants(id)) {
                        is RaizResult.Success -> r.data.forEach { put(it.address, id) }
                        is RaizResult.Error -> Log.w(TAG, "listMerchants($id): ${r.message}")
                    }
                }
            }
            // Diagnóstico: dirección del turista y merchants registrados por barrio.
            Log.i(TAG, "loadPassport: touristAddress=${accountId}")
            Log.i(TAG, "loadPassport: merchantToBarrio keys=${merchantToBarrio.keys}")

            // 3. Pagos a comercios del turista — vía EVENTOS Soroban del Pool.
            //    Horizon NO ve pay_merchant (mueve USDC DENTRO del contrato vía el SAC),
            //    así que los pagos a comercios salen de los eventos `payment` del Pool.
            val merchantPayments = when (val r = sorobanClient.tourPaymentEvents(accountId)) {
                is RaizResult.Success -> r.data
                is RaizResult.Error -> {
                    Log.w(TAG, "tourPaymentEvents en passport: ${r.message}")
                    emptyList()
                }
            }
            // Diagnóstico: merchants que aparecen en los pagos del turista.
            Log.i(TAG, "loadPassport: merchantPayments=${merchantPayments.size} -> ${merchantPayments.map { it.to }}")

            // 4. Stats agregadas.
            //
            // Aporte al barrio: derivado de los puntos (proxy exacto del tip acumulado).
            //   1 punto = 0.01 USDC de tip = 100_000 stroops (POINTS_PER_STROOP_DIVISOR).
            // Coherente porque los puntos se acreditan en el mismo Pool que emite el tip.
            val aportadoStroops = points * com.raiz.app.data.model.RaizConstants.POINTS_PER_STROOP_DIVISOR

            // Transacciones locales y barrios visitados: de los eventos de pago a comercios
            // (cada evento ya viene filtrado por este turista). Esto desbloquea los sellos.
            val txsLocales: Set<String> = merchantPayments
                .filter { merchantToBarrio.containsKey(it.to) }
                .map { it.txHash }
                .toSet()

            val barriosVisitados: Set<String> = merchantPayments
                .mapNotNull { merchantToBarrio[it.to] }
                .toSet()
            // Diagnóstico: IDs de barrios que resultan del cruce merchant→barrio.
            Log.i(TAG, "loadPassport: barriosVisitados=${barriosVisitados}")

            val nivel = PassportLevel.fromPoints(points)
            val passport = PassportData(
                nombre = "Viajer@ ${accountId.takeLast(4)}",
                ubicacion = "Colombia 2026",
                nivel = nivel,
                points = points,
                ptsParaSiguienteNivel = nivel.ptsToNext(points),
                aportadoAlBarrioStroops = aportadoStroops,
                transaccionesLocales = txsLocales.size,
                barriosVisitados = barriosVisitados,
            )
            Log.i(TAG, "Passport: pts=$points · aportado=${aportadoStroops.formatUsdc()} tx=${txsLocales.size} barrios=${barriosVisitados.size}")
            _state.update { current ->
                if (current is WalletUiState.Ready) current.copy(passport = passport) else current
            }
        }
    }

    /**
     * Recarga todos los datos: passport (puntos + stats), pool del barrio y saldo USDC.
     * Llamado desde ON_RESUME y desde el loop de auto-refresco periódico.
     */
    fun refresh() {
        loadPassport()
        loadCentroPoolBalance()
        // El saldo SAC de los smart accounts passkey no tiene SSE — se refresca manualmente.
        if (walletManager.isPasskeyWallet()) {
            viewModelScope.launch { refreshPasskeyBalance() }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
        /** Intervalo del loop de auto-refresco periódico (20 s). */
        const val AUTO_REFRESH_INTERVAL_MS = 20_000L
        const val BARRIO_CENTRO_ID = "ce47120000000000000000000000000000000000000000000000000000000001"
        val BARRIOS: LinkedHashMap<String, String> = linkedMapOf(
            BARRIO_CENTRO_ID to "Centro Histórico",
            "bba17e0000000000000000000000000000000000000000000000000000000002" to "Barrio Norte",
            "c057a9000000000000000000000000000000000000000000000000000000000a" to "Costa Vieja",
        )
    }
}
