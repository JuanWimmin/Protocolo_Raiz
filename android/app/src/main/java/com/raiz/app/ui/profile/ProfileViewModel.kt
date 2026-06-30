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
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
        loadHistory()
        resolveRole()
        _state.update {
            it.copy(appLockEnabled = appLock.enabled, appLockAvailable = appLock.canAuthenticate())
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

    private fun observeBalance() {
        viewModelScope.launch {
            horizonStream.usdcBalanceFlow(walletManager.mockWallet().publicKey).collect { stroops ->
                _state.update { it.copy(wallet = it.wallet.copy(usdcBalanceStroops = stroops)) }
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            _state.update { it.copy(historyLoading = true, historyError = null) }
            when (val r = horizonStream.paymentHistory(walletManager.mockWallet().publicKey, limit = 30)) {
                is RaizResult.Success -> _state.update {
                    it.copy(history = r.data, historyLoading = false, historyError = null)
                }
                is RaizResult.Error -> _state.update {
                    it.copy(historyLoading = false, historyError = r.message)
                }
            }
        }
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
    }
}
