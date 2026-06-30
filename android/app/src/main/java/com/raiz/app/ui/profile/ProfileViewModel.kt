package com.raiz.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.RoleContext
import com.raiz.app.data.model.UserRole
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.security.AppLock
import com.raiz.app.data.stellar.HorizonStream
import com.raiz.app.data.stellar.RoleResolver
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado limpio de ProfileScreen (post-refactor).
 *
 * Las secciones de votación (residentes) y cobros (comerciantes) se movieron
 * a sus propias pantallas: ProposalsScreen y CobrosScreen respectivamente.
 * ProfileScreen ahora gestiona exclusivamente: historial de pagos, QR propio
 * y configuración (seguridad + demo role switch).
 *
 * - [detectedRole]  : rol real on-chain del address del wallet. Null mientras resuelve.
 * - [roleOverride]  : solo demo — "ver como" otro rol. Actualiza el chip de rol del header
 *                     Y (vía callback en ProfileScreen) la nav en RaizApp.
 * - [effectiveRole] : lo que la UI renderiza (override > real > TOURIST por defecto).
 * - [isDemoMode]    : proviene de WalletManager.isDemoMode. Solo en modo demo aparece
 *                     el DemoRoleSwitch en la tab Configuración.
 */
data class ProfileUiState(
    val wallet: WalletState,
    val detectedRole: RoleContext? = null,
    val roleOverride: UserRole? = null,
    val history: List<PaymentRecord> = emptyList(),
    val historyLoading: Boolean = true,
    val historyError: String? = null,
    val appLockEnabled: Boolean = false,
    val appLockAvailable: Boolean = false,
    val isDemoMode: Boolean = false,
) {
    val effectiveRole: UserRole get() = roleOverride ?: detectedRole?.role ?: UserRole.TOURIST

    /** Barrio del rol activo — fallback al barrio demo del seed. */
    val activeBarrioId: String get() = detectedRole?.barrioId ?: DEMO_BARRIO_ID
    val activeBarrioName: String get() = detectedRole?.barrioName ?: "Centro Histórico"

    private companion object {
        const val DEMO_BARRIO_ID =
            "ce47120000000000000000000000000000000000000000000000000000000001"
    }
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val horizonStream: HorizonStream,
    private val sorobanClient: SorobanClient,   // Bug 1 & 2: saldo SAC + puntos
    private val roleResolver: RoleResolver,
    private val appLock: AppLock,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileUiState(
            wallet = walletManager.mockWallet(),
            isDemoMode = walletManager.isDemoMode,
        ),
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        observeBalance()
        loadPoints()           // Bug 2: cargar puntos del contrato Rewards
        loadHistory()
        resolveRole()
        _state.update {
            it.copy(appLockEnabled = appLock.enabled, appLockAvailable = appLock.canAuthenticate())
        }
        // Auto-refresco periódico — un solo loop para no duplicar coroutines.
        viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                refresh()
            }
        }
    }

    /** Activa/desactiva el bloqueo biométrico de la app. */
    fun setAppLockEnabled(enabled: Boolean) {
        appLock.enabled = enabled
        _state.update { it.copy(appLockEnabled = enabled) }
    }

    /**
     * Override de rol para el demo (Configuración → "Ver como…").
     *
     * null = volver al rol real.
     * El caller (ProfileScreen) también propagará el cambio al nivel de RaizApp
     * para actualizar la nav inferior.
     */
    fun setRoleOverride(role: UserRole?) {
        _state.update { it.copy(roleOverride = role) }
    }

    /**
     * Suscribe al balance USDC:
     *   - Passkey (C...): lectura puntual inicial — las actualizaciones se delegan
     *     al loop de auto-refresco periódico (un solo loop activo por ViewModel).
     *   - Clásica (G...): flow SSE de Horizon con actualizaciones en tiempo real.
     */
    private fun observeBalance() {
        viewModelScope.launch {
            val accountId = walletManager.currentAccountId()
                ?: walletManager.mockWallet().publicKey

            if (walletManager.isPasskeyWallet()) {
                // Solo lectura inicial — refresh() llama refreshBalanceOnce()
                // para no tener dos loops infinitos activos.
                refreshBalanceOnce()
                return@launch
            }

            // Wallet clásica G...: SSE de Horizon.
            horizonStream.usdcBalanceFlow(accountId).collect { stroops ->
                _state.update { it.copy(wallet = it.wallet.copy(usdcBalanceStroops = stroops)) }
            }
        }
    }

    /**
     * Una sola lectura del saldo USDC para wallets passkey (smart accounts C...).
     * Para wallets clásicas no hace nada — el SSE de Horizon gestiona el saldo.
     * Se invoca en la carga inicial y en cada ciclo de refresh().
     */
    private suspend fun refreshBalanceOnce() {
        if (!walletManager.isPasskeyWallet()) return
        val accountId = walletManager.currentAccountId() ?: walletManager.mockWallet().publicKey
        when (val r = sorobanClient.usdcBalanceOfContract(accountId)) {
            is RaizResult.Success -> {
                Log.i(TAG, "Saldo SAC (refresco): ${r.data} stroops")
                _state.update { it.copy(wallet = it.wallet.copy(usdcBalanceStroops = r.data)) }
            }
            is RaizResult.Error -> Log.w(TAG, "refreshBalanceOnce: ${r.message}")
        }
    }

    /**
     * Bug 2 — Puntos en perfil.
     *
     * ProfileViewModel nunca llamaba getPoints, por lo que wallet.points
     * permanecía en 0 aunque WalletScreen y RewardsScreen los mostraran bien.
     *
     * Hasta 3 reintentos con delay porque el RPC de Soroban a veces tarda en
     * propagar ledgers recientes. Al actualizar wallet.points, BalancesRow
     * en ProfileScreen lo renderiza automáticamente (ya tenía el tile de puntos).
     */
    private fun loadPoints() {
        viewModelScope.launch {
            val accountId = walletManager.currentAccountId()
                ?: walletManager.mockWallet().publicKey
            repeat(MAX_POINTS_RETRIES) { intento ->
                when (val r = sorobanClient.getPoints(accountId)) {
                    is RaizResult.Success -> {
                        Log.i(TAG, "Puntos en perfil: ${r.data} pts")
                        _state.update { it.copy(wallet = it.wallet.copy(points = r.data)) }
                        return@launch   // éxito — no seguir reintentando
                    }
                    is RaizResult.Error -> {
                        Log.w(TAG, "getPoints intento ${intento + 1}/$MAX_POINTS_RETRIES: ${r.message}")
                        if (intento < MAX_POINTS_RETRIES - 1) delay(RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    /**
     * Historial fusionado de DOS fuentes:
     *  1. Eventos Soroban `payment` del Pool ([SorobanClient.tourPaymentEvents]) —
     *     incluye SALIENTES (accountId==tourist) e ENTRANTES (accountId==merchant).
     *     Horizon NO ve estos movimientos (son invocaciones de contrato, no ops clásicas).
     *  2. Pagos clásicos de Horizon ([HorizonStream.paymentHistory]) — incluye TANTO
     *     entrantes COMO salientes. Para wallets seed (G...) incluye el faucet ENTRANTE.
     *     Para passkey (C...) Horizon devuelve vacío (no es cuenta clásica).
     *     Los registros ya traen [PaymentRecord.isOutgoing] correcto; el merge NO filtra
     *     por dirección — se muestra todo el historial.
     *
     * NOTA passkey (stretch): el faucet de una wallet C... es un SAC transfer — no
     * aparece ni en Horizon (no es cuenta clásica) ni en los eventos del Pool.
     * Para mostrarlo habría que leer getEvents(contractId=usdcSac, topic=transfer, to=accountId).
     * Pendiente de implementar cuando WalletManager soporte signing pleno via smart-account.
     *
     * Se mezclan y ordenan por fecha (createdAt ISO 8601, orden lexicográfico = cronológico).
     */
    fun loadHistory() {
        viewModelScope.launch {
            _state.update { it.copy(historyLoading = true, historyError = null) }
            val accountId = walletManager.currentAccountId() ?: walletManager.mockWallet().publicKey

            // Pool events: SALIENTES cuando accountId==tourist, ENTRANTES cuando accountId==merchant.
            val poolEvents = (sorobanClient.tourPaymentEvents(accountId) as? RaizResult.Success)?.data
                ?: emptyList()

            // Horizon: pagos clásicos en ambas direcciones (incluye faucet ENTRANTE de seed).
            val classicResult = horizonStream.paymentHistory(accountId, limit = 30)
            val classic = (classicResult as? RaizResult.Success)?.data ?: emptyList()

            // Merge sin filtrar por isOutgoing — se quiere ver todo el historial (entrada + salida).
            val merged = (poolEvents + classic).sortedByDescending { it.createdAt }
            // Solo mostramos error si NO hay nada que enseñar y Horizon falló.
            val error = if (merged.isEmpty() && classicResult is RaizResult.Error) classicResult.message else null

            _state.update {
                it.copy(history = merged, historyLoading = false, historyError = error)
            }
        }
    }

    /**
     * Recarga historial, puntos y saldo (one-shot para passkey).
     * Llamado desde ON_RESUME y desde el loop de auto-refresco periódico.
     */
    fun refresh() {
        loadHistory()
        loadPoints()
        viewModelScope.launch { refreshBalanceOnce() }
    }

    private fun resolveRole() {
        viewModelScope.launch {
            val role = roleResolver.resolve(walletManager.mockWallet().publicKey)
            Log.i(TAG, "Rol detectado en perfil: ${role.role}")
            _state.update { it.copy(detectedRole = role) }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
        /** Intervalo del loop de auto-refresco periódico (20 s). Mismo valor que WalletViewModel. */
        const val AUTO_REFRESH_INTERVAL_MS = 20_000L
        /** Pausa entre reintentos de getPoints. */
        const val RETRY_DELAY_MS = 2_000L
        /** Número máximo de intentos para cargar puntos. */
        const val MAX_POINTS_RETRIES = 3
    }
}
