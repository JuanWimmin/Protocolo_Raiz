package com.raiz.app.ui.governance

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.Proposal
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.PasskeyWalletManager
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

/** Estado del voto en curso sobre una propuesta. */
sealed interface VoteStatus {
    data object Submitting : VoteStatus
    data object Ok : VoteStatus
    data class Failed(val message: String) : VoteStatus
}

/**
 * Estado de ProposalsScreen.
 *
 * [isRegisteredOnChain] null mientras se verifica, false si la cuenta no tiene
 * ResidentToken, true si está registrada. En modo demo se asume true para que
 * el juez pueda ver contenido completo sin un residente real.
 *
 * [residentCount] se usa junto con [Proposal.reachedQuorum] para mostrar
 * el estado de quórum de cada propuesta.
 */
data class ProposalsUiState(
    val proposals: List<Proposal> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val residentCount: Int = 0,
    /** null = verificando; false = no registrado; true = registrado on-chain. */
    val isRegisteredOnChain: Boolean? = null,
    val barrioId: String = "",
    val barrioName: String = "Mi barrio",
    val voteState: Map<Long, VoteStatus> = emptyMap(),
    val isDemoMode: Boolean = false,
) {
    companion object {
        const val DEMO_BARRIO_ID =
            "ce47120000000000000000000000000000000000000000000000000000000001"
        const val DEMO_BARRIO_NAME = "Centro Histórico"
    }
}

/**
 * ViewModel de ProposalsScreen — pantalla exclusiva del rol RESIDENT.
 *
 * Flujo de carga:
 *   1. Si isDemoMode → carga el barrio demo + propuestas sin verificar registro
 *      (usa demoResidentKeyPair para votar).
 *   2. Si no → getResident(address) para obtener el barrioId real del residente.
 *      Si falla → isRegisteredOnChain = false (la UI muestra aviso pendiente).
 *      Si ok → carga propuestas + getResidentCount del barrio en paralelo.
 *
 * Para crear propuestas se usa CreateProposalViewModel (pantalla separada).
 */
@HiltViewModel
class ProposalsViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val roleResolver: RoleResolver,
    private val passkeyWalletManager: PasskeyWalletManager,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProposalsUiState(isDemoMode = walletManager.isDemoMode),
    )
    val state: StateFlow<ProposalsUiState> = _state.asStateFlow()

    init {
        resolveAndLoad()
    }

    private fun resolveAndLoad() {
        viewModelScope.launch {
            val address = walletManager.currentAccountId() ?: return@launch

            // En demo el turista demo no tiene ResidentToken, pero el juez
            // necesita ver las pantallas completas. Usamos el barrio demo y
            // demoResidentKeyPair() para firmar votos.
            if (walletManager.isDemoMode) {
                _state.update {
                    it.copy(
                        isRegisteredOnChain = true,
                        barrioId = ProposalsUiState.DEMO_BARRIO_ID,
                        barrioName = ProposalsUiState.DEMO_BARRIO_NAME,
                    )
                }
                loadProposalsAndCount(ProposalsUiState.DEMO_BARRIO_ID)
                return@launch
            }

            // Verificación on-chain del token de residencia.
            when (val r = sorobanClient.getResident(address)) {
                is RaizResult.Success -> {
                    val barrioId = r.data.barrioId
                    // El RoleResolver tiene el nombre del barrio en cache (o lo infiere).
                    val ctx = roleResolver.resolve(address)
                    val barrioName = ctx.barrioName ?: barrioId.take(8) + "…"
                    _state.update {
                        it.copy(
                            isRegisteredOnChain = true,
                            barrioId = barrioId,
                            barrioName = barrioName,
                        )
                    }
                    loadProposalsAndCount(barrioId)
                }
                is RaizResult.Error -> {
                    Log.i(TAG, "getResident falló — cuenta no registrada como residente.")
                    _state.update { it.copy(isRegisteredOnChain = false, loading = false) }
                }
            }
        }
    }

    /** Carga propuestas activas y conteo de residentes en paralelo. */
    private fun loadProposalsAndCount(barrioId: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            // Propuestas
            launch {
                when (val r = sorobanClient.listActiveProposals(barrioId)) {
                    is RaizResult.Success -> {
                        Log.i(TAG, "Propuestas activas: ${r.data.size}")
                        _state.update { it.copy(proposals = r.data, loading = false) }
                    }
                    is RaizResult.Error -> _state.update {
                        it.copy(loading = false, error = r.message)
                    }
                }
            }

            // Conteo de residentes (para calcular quórum).
            launch {
                when (val r = sorobanClient.getResidentCount(barrioId)) {
                    is RaizResult.Success -> {
                        Log.i(TAG, "Residentes en barrio: ${r.data}")
                        _state.update { it.copy(residentCount = r.data) }
                    }
                    is RaizResult.Error ->
                        Log.w(TAG, "getResidentCount falló: ${r.message}")
                }
            }
        }
    }

    /** Pull-to-refresh manual. */
    fun refresh() {
        val barrioId = _state.value.barrioId
            .ifEmpty { ProposalsUiState.DEMO_BARRIO_ID }
        loadProposalsAndCount(barrioId)
    }

    /**
     * Vota sobre una propuesta.
     *
     * Rama passkey: si la wallet activa es un smart account (C...), firma
     * el voto vía WebAuthn usando [PasskeyWalletManager.voteWithPasskey].
     * [activity] es obligatorio en esta rama (Credential Manager lo necesita).
     *
     * Rama demo: firma con demoResidentKeyPair (la cuenta turista demo no tiene
     * ResidentToken — el contrato rechazaría con NOT_A_RESIDENT).
     *
     * Rama clásica: firma con el KeyPair real del residente.
     */
    fun vote(proposalId: Long, support: Boolean, activity: Activity? = null) {
        viewModelScope.launch {
            _state.update {
                it.copy(voteState = it.voteState + (proposalId to VoteStatus.Submitting))
            }

            // Rama passkey: el smart account C... firma el voto vía WebAuthn.
            if (walletManager.isPasskeyWallet() && activity != null) {
                Log.i(TAG, "Voto passkey: proposalId=$proposalId support=$support")
                when (val r = passkeyWalletManager.voteWithPasskey(activity, proposalId, support)) {
                    is RaizResult.Success -> {
                        Log.i(TAG, "Voto passkey registrado on-chain")
                        _state.update {
                            it.copy(voteState = it.voteState + (proposalId to VoteStatus.Ok))
                        }
                        refresh()
                    }
                    is RaizResult.Error -> {
                        Log.e(TAG, "Voto passkey falló: ${r.code} — ${r.message}")
                        _state.update {
                            it.copy(
                                voteState = it.voteState + (proposalId to VoteStatus.Failed(
                                    humanError(r.code.name, r.message),
                                )),
                            )
                        }
                    }
                }
                return@launch
            }

            // Rama clásica / demo: firma con KeyPair ed25519.
            val signer = if (walletManager.isDemoMode) {
                walletManager.demoResidentKeyPair()
            } else {
                walletManager.currentKeyPair()
            }

            if (signer == null) {
                _state.update {
                    it.copy(
                        voteState = it.voteState + (proposalId to VoteStatus.Failed(
                            "No hay keypair de residente disponible. Revisa local.properties.",
                        )),
                    )
                }
                return@launch
            }

            Log.i(TAG, "Votando proposalId=$proposalId support=$support con ${signer.getAccountId()}")
            when (val r = sorobanClient.vote(signer, proposalId, support)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Voto registrado on-chain")
                    _state.update {
                        it.copy(voteState = it.voteState + (proposalId to VoteStatus.Ok))
                    }
                    // Refresca la lista para reflejar el nuevo conteo.
                    refresh()
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "Voto falló: ${r.code} — ${r.message}")
                    _state.update {
                        it.copy(
                            voteState = it.voteState + (proposalId to VoteStatus.Failed(
                                humanError(r.code.name, r.message),
                            )),
                        )
                    }
                }
            }
        }
    }

    private fun humanError(code: String, message: String): String = when (code) {
        "ALREADY_VOTED"   -> "Ya votaste en esta propuesta"
        "NOT_A_RESIDENT"  -> "Esa cuenta no tiene ResidentToken del barrio"
        "PROPOSAL_CLOSED" -> "La propuesta ya cerró"
        else              -> message
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
