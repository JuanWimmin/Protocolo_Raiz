package com.raiz.app.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.local.ExecutionHashStore
import com.raiz.app.data.model.Barrio
import com.raiz.app.data.model.Execution
import com.raiz.app.data.model.Proposal
import com.raiz.app.data.model.ProposalStatus
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.DeploymentsLoader
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

    /**
     * Acción confirmada. `txHash` es el hash real de la transacción cuando la
     * acción fue `execute_proposal` (camino feliz de D2: lo devuelve el
     * `sendTransaction`); null para `tally`.
     */
    data class Ok(val txHash: String? = null) : ProposalActionState
}

data class DashboardUiState(
    val barriosMeta: List<Pair<String, String>> = BARRIOS,
    val selectedBarrioId: String = BARRIOS.first().first,
    val loading: Boolean = true,
    val error: String? = null,
    val barrio: Barrio? = null,
    /** Ejecuciones de `get_execution_log`, ya correlacionadas con su hash real cuando existe. */
    val executions: List<Execution> = emptyList(),
    /**
     * false si la lectura de eventos `execution` del RPC falló (red/RPC), en
     * cuyo caso una ejecución sin hash puede ser reciente y la UI no debe
     * llamarla "histórica" con seguridad.
     */
    val executionEventsOk: Boolean = true,
    val merchantCount: Int = 0,
    val proposals: List<Proposal> = emptyList(),
    val proposalAction: Map<Long, ProposalActionState> = emptyMap(),
    /** Camino A: shares del fondo del barrio en la fuente de yield (F1: Blend v2 vía yield_adapter; ≈ USDC a precio 1.0). */
    val vaultSharesStroops: Long = 0L,
    // Direcciones C… de los contratos desplegados — vacías hasta que DeploymentsLoader cargue.
    val contractPool: String = "",
    val contractGovernance: String = "",
    val contractTreasury: String = "",
    val contractRewards: String = "",
) {
    val selectedBarrioName: String
        get() = barriosMeta.firstOrNull { it.first == selectedBarrioId }?.second
            ?: selectedBarrioId.take(8)

    val totalExecutedStroops: Long get() = executions.sumOf { it.amountStroops }

    val verifiedExecutions: Int get() = executions.count { it.verified }

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
    private val deploymentsLoader: DeploymentsLoader,
    private val executionHashStore: ExecutionHashStore,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        // Carga las direcciones de contratos desde deployments.json (en assets/).
        // Si el archivo no existe o falla, los campos quedan vacíos y la sección
        // "Contratos verificados" del Dashboard no se muestra.
        runCatching { deploymentsLoader.load() }.getOrNull()?.let { deps ->
            _state.update {
                it.copy(
                    contractPool       = deps.pool,
                    contractGovernance = deps.governance,
                    contractTreasury   = deps.treasury,
                    contractRewards    = deps.rewards,
                )
            }
        }
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
                        it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Ok()))
                    }
                    loadFor(_state.value.selectedBarrioId)
                }
                is RaizResult.Error -> _state.update {
                    it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Failed(r.message)))
                }
            }
        }
    }

    /**
     * Ejecuta una propuesta Passed via Treasury — trustless.
     *
     * Camino feliz de D2: el hash real de la transacción lo devuelve el propio
     * `sendTransaction`; se muestra al instante en la card de la propuesta y se
     * persiste en [ExecutionHashStore] para que la fila de "Ejecuciones" siga
     * enlazando a Stellar Expert cuando el evento salga de la ventana del RPC.
     */
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
                    val txHash = r.data
                    Log.i(TAG, "Propuesta $proposalId ejecutada on-chain → tx $txHash")
                    executionHashStore.save(proposalId, txHash)
                    _state.update {
                        it.copy(proposalAction = it.proposalAction + (proposalId to ProposalActionState.Ok(txHash)))
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
            val vaultSharesResult = sorobanClient.getVaultShares(barrioId)

            val barrio = (barrioResult as? RaizResult.Success)?.data
            val rawExecutions = (executionsResult as? RaizResult.Success)?.data.orEmpty()
            val merchants = (merchantsResult as? RaizResult.Success)?.data.orEmpty()
            val proposals = (proposalsResult as? RaizResult.Success)?.data.orEmpty()
            val vaultShares = (vaultSharesResult as? RaizResult.Success)?.data ?: 0L

            // Correlación D2: hash real por proposal_id. Solo consultamos eventos si
            // hay ejecuciones que enlazar (ahorra ~13 páginas de getEvents en barrios
            // sin ejecuciones). Fuente 1: evento `execution` del RPC (manda);
            // fuente 2: hash capturado por esta app al ejecutar (caché local).
            var eventsOk = true
            val hashByProposal: Map<Long, String> = if (rawExecutions.isEmpty()) {
                emptyMap()
            } else {
                val fromEvents = when (val ev = sorobanClient.executionEvents(barrioId)) {
                    is RaizResult.Success -> ev.data.associate { it.proposalId to it.txHash }
                    is RaizResult.Error -> {
                        Log.w(TAG, "Dashboard $barrioId: eventos execution no disponibles: ${ev.message}")
                        eventsOk = false
                        emptyMap()
                    }
                }
                executionHashStore.all() + fromEvents
            }
            val executions = rawExecutions.map { exec ->
                exec.copy(realTxHash = hashByProposal[exec.proposalId])
            }

            val firstError = listOfNotNull(
                (barrioResult as? RaizResult.Error)?.message,
                (executionsResult as? RaizResult.Error)?.message,
            ).firstOrNull()

            Log.i(
                TAG,
                "Dashboard $barrioId: pool=${barrio?.poolBalanceUsdc} executions=${executions.size} " +
                    "(verificadas=${executions.count { it.verified }}) proposals=${proposals.size}",
            )

            _state.update {
                it.copy(
                    loading = false,
                    error = if (barrio == null) firstError else null,
                    barrio = barrio,
                    executions = executions,
                    executionEventsOk = eventsOk,
                    merchantCount = merchants.size,
                    proposals = proposals,
                    vaultSharesStroops = vaultShares,
                    // Limpia acciones resueltas para la nueva carga. Se conserva el
                    // Ok de una ejecución (lleva el hash real) para que la card lo
                    // siga mostrando mientras la propuesta aparezca en la lista.
                    proposalAction = it.proposalAction.filterValues { v ->
                        v is ProposalActionState.Submitting ||
                            (v is ProposalActionState.Ok && v.txHash != null)
                    },
                )
            }
        }
    }

    private companion object { const val TAG = "RAIZ" }
}
