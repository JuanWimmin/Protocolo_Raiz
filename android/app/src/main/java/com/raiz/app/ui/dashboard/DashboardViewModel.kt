package com.raiz.app.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.Barrio
import com.raiz.app.data.model.Execution
import com.raiz.app.data.model.Proposal
import com.raiz.app.data.model.ProposalStatus
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de una acción on-chain por propuesta (tally/execute). */
sealed interface ProposalActionState {
    data object Submitting : ProposalActionState
    data class Failed(val message: String) : ProposalActionState
    data object Ok : ProposalActionState
}

data class DashboardUiState(
    val barriosMeta: List<Pair<String, String>> = BARRIOS,
    val selectedBarrioId: String = BARRIOS.first().first,
    val loading: Boolean = true,
    val error: String? = null,
    val barrio: Barrio? = null,
    val executions: List<Execution> = emptyList(),
    val merchantCount: Int = 0,
    val proposals: List<Proposal> = emptyList(),
    val proposalAction: Map<Long, ProposalActionState> = emptyMap(),
) {
    val selectedBarrioName: String
        get() = barriosMeta.firstOrNull { it.first == selectedBarrioId }?.second
            ?: selectedBarrioId.take(8)

    val totalExecutedStroops: Long get() = executions.sumOf { it.amountStroops }

    val usedPct: Int
        get() {
            val total = barrio?.totalCollectedStroops ?: 0L
            return if (total <= 0) 0 else ((totalExecutedStroops * 100) / total).toInt().coerceIn(0, 100)
        }

    companion object {
        val BARRIOS: List<Pair<String, String>> = listOf(
            "ce47120000000000000000000000000000000000000000000000000000000001" to "Centro Histórico",
            "bba17e0000000000000000000000000000000000000000000000000000000002" to "Barrio Norte",
            "c057a9000000000000000000000000000000000000000000000000000000000a" to "Costa Vieja",
        )
    }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val sorobanClient: SorobanClient,
    private val walletManager: WalletManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        loadFor(_state.value.selectedBarrioId)
    }

    fun selectBarrio(barrioId: String) {
        if (barrioId == _state.value.selectedBarrioId) return
        _state.update { it.copy(selectedBarrioId = barrioId) }
        loadFor(barrioId)
    }

    fun refresh() = loadFor(_state.value.selectedBarrioId)

    /** Cierra la votación llamando tally on-chain. */
    fun closeVoting(proposalId: Long) {
        viewModelScope.launch {
            _state.update {
                it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Submitting))
            }
            val signer = walletManager.currentKeyPair()
            if (signer == null) {
                _state.update {
                    it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Failed(
                        "Necesitas una wallet conectada para cerrar la votación.",
                    )))
                }
                return@launch
            }
            when (val r = sorobanClient.tally(signer, proposalId)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Tally propuesta $proposalId → ${r.data}")
                    _state.update {
                        it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Ok))
                    }
                    loadFor(_state.value.selectedBarrioId)
                }
                is RaizResult.Error -> _state.update {
                    it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Failed(r.message)))
                }
            }
        }
    }

    /** Ejecuta una propuesta Passed via Treasury — trustless. */
    fun executeProposal(proposalId: Long) {
        viewModelScope.launch {
            _state.update {
                it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Submitting))
            }
            val signer = walletManager.currentKeyPair()
            if (signer == null) {
                _state.update {
                    it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Failed(
                        "Necesitas una wallet conectada para ejecutar.",
                    )))
                }
                return@launch
            }
            when (val r = sorobanClient.executeProposal(signer, proposalId)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Propuesta $proposalId ejecutada on-chain")
                    _state.update {
                        it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Ok))
                    }
                    loadFor(_state.value.selectedBarrioId)
                }
                is RaizResult.Error -> {
                    val human = when (r.code) {
                        RaizErrorCode.QUORUM_NOT_REACHED -> "La propuesta no alcanzó quórum o aún no cerró"
                        else -> r.message
                    }
                    _state.update {
                        it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Failed(human)))
                    }
                }
            }
        }
    }

    private fun loadFor(barrioId: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            val barrioResult = sorobanClient.getBarrio(barrioId)
            val executionsResult = sorobanClient.getExecutionLog(barrioId)
            val merchantsResult = sorobanClient.listMerchants(barrioId)
            val proposalsResult = sorobanClient.listActiveProposals(barrioId)

            val barrio = (barrioResult as? RaizResult.Success)?.data
            val executions = (executionsResult as? RaizResult.Success)?.data.orEmpty()
            val merchants = (merchantsResult as? RaizResult.Success)?.data.orEmpty()
            val proposals = (proposalsResult as? RaizResult.Success)?.data.orEmpty()

            val firstError = listOfNotNull(
                (barrioResult as? RaizResult.Error)?.message,
                (executionsResult as? RaizResult.Error)?.message,
            ).firstOrNull()

            Log.i(
                TAG,
                "Dashboard $barrioId: pool=${barrio?.poolBalanceUsdc} executions=${executions.size} proposals=${proposals.size}",
            )

            _state.update {
                it.copy(
                    loading = false,
                    error = if (barrio == null) firstError else null,
                    barrio = barrio,
                    executions = executions,
                    merchantCount = merchants.size,
                    proposals = proposals,
                    // Limpia acciones resueltas para la nueva carga.
                    proposalAction = it.proposalAction.filterValues { v ->
                        v is ProposalActionState.Submitting
                    },
                )
            }
        }
    }

    private companion object { const val TAG = "RAIZ" }
}
