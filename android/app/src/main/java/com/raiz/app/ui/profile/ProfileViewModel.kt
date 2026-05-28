package com.raiz.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.Proposal
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.RoleContext
import com.raiz.app.data.model.UserRole
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.stellar.HorizonStream
import com.raiz.app.data.stellar.RoleResolver
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado de ProfileScreen.
 *
 * - `detectedRole`: rol real on-chain del address del wallet. Null mientras se
 *   está resolviendo.
 * - `roleOverride`: solo para el demo del hackathon — permite "ver como" otro
 *   rol con la misma cuenta. Las lecturas (propuestas, ventas) usan datos de
 *   los barrios del seed. Las escrituras (vote, etc.) seguirían fallando si
 *   la cuenta firmante no tiene los permisos, lo cual está OK como feedback.
 * - `effectiveRole`: lo que la UI debe renderizar (override si hay, sino real).
 */
data class ProfileUiState(
    val wallet: WalletState,
    val detectedRole: RoleContext? = null,
    val roleOverride: UserRole? = null,
    val history: List<PaymentRecord> = emptyList(),
    val historyLoading: Boolean = true,
    val historyError: String? = null,
    val residentProposals: List<Proposal> = emptyList(),
    val proposalsLoading: Boolean = false,
    val proposalsError: String? = null,
    /** Estado de un voto en curso (id → "submitting" | "ok" | "error msg"). */
    val voteState: Map<Long, VoteStatus> = emptyMap(),
) {
    val effectiveRole: UserRole get() = roleOverride ?: detectedRole?.role ?: UserRole.TOURIST

    /** Barrio "activo" para la sección de rol: el real si hay, sino fallback Centro. */
    val activeBarrioId: String get() = detectedRole?.barrioId ?: DEMO_BARRIO_ID
    val activeBarrioName: String get() = detectedRole?.barrioName ?: "Centro Histórico"

    private companion object {
        const val DEMO_BARRIO_ID =
            "ce47120000000000000000000000000000000000000000000000000000000001"
    }
}

/** Estado del voto sobre una propuesta. */
sealed interface VoteStatus {
    data object Submitting : VoteStatus
    data object Ok : VoteStatus
    data class Failed(val message: String) : VoteStatus
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val horizonStream: HorizonStream,
    private val sorobanClient: SorobanClient,
    private val roleResolver: RoleResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileUiState(wallet = walletManager.mockWallet()),
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        observeBalance()
        loadHistory()
        resolveRole()
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
            _state.update { it.copy(detectedRole = role) }
            // Si es residente, precargar propuestas del barrio
            if (role.role == UserRole.RESIDENT && role.barrioId != null) {
                loadProposalsFor(role.barrioId)
            }
        }
    }

    /** Override para el demo: "ver como X". */
    fun setRoleOverride(role: UserRole?) {
        _state.update { it.copy(roleOverride = role) }
        // Carga datos según el rol elegido si aún no los tenemos.
        if (role == UserRole.RESIDENT) {
            val barrioId = _state.value.activeBarrioId
            if (_state.value.residentProposals.isEmpty() && !_state.value.proposalsLoading) {
                loadProposalsFor(barrioId)
            }
        }
    }

    /**
     * Vota sobre una propuesta. La firma usa:
     *   - el wallet demo (turista) si el rol real es residente,
     *   - el demoResidentKeyPair si el modo es override "ver como residente".
     *
     * Si la cuenta firmante no tiene ResidentToken, el contrato rechaza con
     * NotAResident (#6) y lo mostramos como error claro.
     */
    fun vote(proposalId: Long, support: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(voteState = it.voteState + (proposalId to VoteStatus.Submitting)) }

            // Override "ver como residente" usa la cuenta demo de residente
            // del seed (la wallet del usuario casi nunca tiene ResidentToken).
            // Si no hay override → firma con la wallet activa del usuario.
            val signer = if (state.value.roleOverride == UserRole.RESIDENT) {
                walletManager.demoResidentKeyPair()
            } else {
                walletManager.currentKeyPair()
            }

            if (signer == null) {
                _state.update {
                    it.copy(
                        voteState = it.voteState + (proposalId to VoteStatus.Failed(
                            "No hay wallet residente configurada. Revisa local.properties.",
                        )),
                    )
                }
                return@launch
            }

            Log.i(TAG, "Votando $proposalId support=$support con ${signer.getAccountId()}")
            when (val r = sorobanClient.vote(signer, proposalId, support)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Voto OK")
                    _state.update {
                        it.copy(voteState = it.voteState + (proposalId to VoteStatus.Ok))
                    }
                    // Refrescar la lista de propuestas para ver el voto reflejado.
                    loadProposalsFor(state.value.activeBarrioId)
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "Voto falló: ${r.code} — ${r.message}")
                    _state.update {
                        it.copy(
                            voteState = it.voteState + (proposalId to VoteStatus.Failed(humanError(r.code.name, r.message))),
                        )
                    }
                }
            }
        }
    }

    private fun humanError(code: String, message: String): String = when (code) {
        "ALREADY_VOTED" -> "Ya votaste en esta propuesta"
        "NOT_A_RESIDENT" -> "Esa cuenta no tiene ResidentToken del barrio"
        "PROPOSAL_CLOSED" -> "La propuesta ya cerró"
        else -> message
    }

    private fun loadProposalsFor(barrioId: String) {
        viewModelScope.launch {
            _state.update { it.copy(proposalsLoading = true, proposalsError = null) }
            when (val r = sorobanClient.listActiveProposals(barrioId)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Propuestas activas: ${r.data.size}")
                    _state.update {
                        it.copy(residentProposals = r.data, proposalsLoading = false)
                    }
                }
                is RaizResult.Error -> _state.update {
                    it.copy(proposalsLoading = false, proposalsError = r.message)
                }
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
