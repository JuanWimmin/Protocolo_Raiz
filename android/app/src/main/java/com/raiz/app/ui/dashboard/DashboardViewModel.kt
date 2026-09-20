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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Estado de una acción on-chain por propuesta (tally/execute). */
sealed interface ProposalActionState {
    data object Submitting : ProposalActionState
    data class Failed(val message: String) : ProposalActionState

    /**
     * Acción confirmada. `txHash` es el hash real de la transacción cuando la
     * acción fue `execute_proposal` (camino feliz de D2); null para `tally`.
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
     * true si `get_execution_log` FALLÓ (red, o entrada archivada que el RPC quiere
     * auto-restaurar y el SDK rechaza sin firmante). La UI no debe pintar entonces
     * "0 ejecuciones" como si fuera un dato.
     */
    val executionsLoadFailed: Boolean = false,
    /** true mientras se buscan en el RPC los eventos de las ejecuciones recientes sin hash. */
    val executionEventsLoading: Boolean = false,
    /** false si esa búsqueda falló o expiró: una fila sin hash puede ser reciente, no "histórica". */
    val executionEventsOk: Boolean = true,
    val merchantCount: Int = 0,
    val proposals: List<Proposal> = emptyList(),
    /**
     * Propuestas que ESTA sesión ejecutó. `list_active_proposals` deja de devolverlas
     * en cuanto pasan a Executed, así que se conservan aquí para que la card siga
     * mostrando el hash real y el enlace (camino feliz de D2).
     */
    val recentlyExecuted: List<Proposal> = emptyList(),
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

    /** Propuestas activas + las que esta sesión ejecutó en el barrio seleccionado. */
    val visibleProposals: List<Proposal>
        get() {
            val activeIds = proposals.map { it.id }.toSet()
            return proposals + recentlyExecuted.filter {
                it.id !in activeIds && it.barrioId.equals(selectedBarrioId, ignoreCase = true)
            }
        }

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

    // Solo se tocan en Main (viewModelScope = Dispatchers.Main.immediate).
    private var loadGen = 0
    private var loadJob: Job? = null

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
        // Limpia TODO lo que depende del barrio: mientras carga el nuevo no deben
        // verse fondos, ejecuciones ni hashes del anterior bajo el chip del nuevo.
        _state.update {
            it.copy(
                selectedBarrioId = barrioId,
                loading = true,
                error = null,
                barrio = null,
                executions = emptyList(),
                executionsLoadFailed = false,
                executionEventsLoading = false,
                executionEventsOk = true,
                proposals = emptyList(),
                merchantCount = 0,
                vaultSharesStroops = 0L,
                proposalAction = it.proposalAction.keepLive(),
            )
        }
        loadFor(barrioId)
    }

    fun refresh() {
        _state.update { it.copy(proposalAction = it.proposalAction.keepLive()) }
        loadFor(_state.value.selectedBarrioId)
    }

    /** Conserva acciones en curso y ejecuciones confirmadas (llevan el hash real). */
    private fun Map<Long, ProposalActionState>.keepLive(): Map<Long, ProposalActionState> =
        filterValues { v ->
            v is ProposalActionState.Submitting || (v is ProposalActionState.Ok && v.txHash != null)
        }

    private fun setAction(proposalId: Long, action: ProposalActionState) {
        _state.update { it.copy(proposalAction = it.proposalAction + (proposalId to action)) }
    }

    private fun noSignerMessage(verb: String): String =
        if (walletManager.isPasskeyWallet()) {
            "La firma con passkey aún no está disponible para esta acción. " +
                "Importa una wallet de frase semilla (con XLM) para $verb."
        } else {
            "Necesitas una wallet conectada para $verb."
        }

    /** Cierra la votación llamando tally on-chain. */
    fun closeVoting(proposalId: Long) {
        viewModelScope.launch {
            setAction(proposalId, ProposalActionState.Submitting)
            val signer = walletManager.currentKeyPair()
            if (signer == null) {
                setAction(proposalId, ProposalActionState.Failed(noSignerMessage("cerrar la votación")))
                return@launch
            }
            when (val r = sorobanClient.tally(signer, proposalId)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Tally propuesta $proposalId → ${r.data}")
                    setAction(proposalId, ProposalActionState.Ok())
                    loadFor(_state.value.selectedBarrioId)
                }
                is RaizResult.Error -> setAction(proposalId, ProposalActionState.Failed(r.message))
            }
        }
    }

    /**
     * Ejecuta una propuesta via Treasury — trustless. Vale para propuestas Passed y
     * para las Active ya cerradas: el Treasury hace el `tally` dentro de la misma tx.
     *
     * Camino feliz de D2: el hash real se conoce al firmar/enviar; se muestra en la
     * card (que se conserva en `recentlyExecuted` aunque la propuesta deje de ser
     * Active) y se persiste en [ExecutionHashStore] para que la fila de "Ejecuciones"
     * siga enlazando a Stellar Expert cuando el evento salga de la ventana del RPC.
     */
    fun executeProposal(proposalId: Long) {
        viewModelScope.launch {
            setAction(proposalId, ProposalActionState.Submitting)
            val signer = walletManager.currentKeyPair()
            if (signer == null) {
                setAction(proposalId, ProposalActionState.Failed(noSignerMessage("ejecutar")))
                return@launch
            }
            val proposal = _state.value.proposals.firstOrNull { it.id == proposalId }
            when (val r = sorobanClient.executeProposal(signer, proposalId)) {
                is RaizResult.Success -> {
                    val txHash = r.data
                    Log.i(TAG, "Propuesta $proposalId ejecutada on-chain → tx $txHash")
                    executionHashStore.save(_state.value.contractTreasury, proposalId, txHash)
                    _state.update {
                        it.copy(
                            proposalAction = it.proposalAction + (proposalId to ProposalActionState.Ok(txHash)),
                            recentlyExecuted = if (proposal == null) it.recentlyExecuted else {
                                it.recentlyExecuted.filter { p -> p.id != proposalId } +
                                    proposal.copy(status = ProposalStatus.EXECUTED)
                            },
                        )
                    }
                    loadFor(_state.value.selectedBarrioId)
                }
                is RaizResult.Error -> {
                    val human = when (r.code) {
                        RaizErrorCode.QUORUM_NOT_REACHED ->
                            "No se pudo ejecutar: la propuesta no alcanzó quórum/mayoría, o ya fue ejecutada."
                        else -> r.message
                    }
                    setAction(proposalId, ProposalActionState.Failed(human))
                    // La tx pudo confirmarse aunque el sondeo fallara: recargar reconcilia
                    // (la propuesta desaparece de activas y su evento aparece en Ejecuciones).
                    loadFor(_state.value.selectedBarrioId)
                }
            }
        }
    }

    private fun loadFor(barrioId: String) {
        // Token de generación: si el usuario cambia de barrio o refresca mientras
        // esta carga está en vuelo, su resultado se descarta entero. No depende de la
        // cancelación (varias lecturas de SorobanClient se tragan CancellationException).
        val gen = ++loadGen
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            val barrioResult = sorobanClient.getBarrio(barrioId)
            val executionsResult = sorobanClient.getExecutionLog(barrioId)
            val merchantsResult = sorobanClient.listMerchants(barrioId)
            val proposalsResult = sorobanClient.listActiveProposals(barrioId)
            val vaultSharesResult = sorobanClient.getVaultShares(barrioId)
            if (gen != loadGen) return@launch

            val barrio = (barrioResult as? RaizResult.Success)?.data
            val rawExecutions = (executionsResult as? RaizResult.Success)?.data.orEmpty()
            val merchants = (merchantsResult as? RaizResult.Success)?.data.orEmpty()
            val proposals = (proposalsResult as? RaizResult.Success)?.data.orEmpty()
            val vaultShares = (vaultSharesResult as? RaizResult.Success)?.data ?: 0L

            val executionsError = (executionsResult as? RaizResult.Error)?.message
            if (executionsError != null) Log.w(TAG, "Dashboard $barrioId: get_execution_log falló: $executionsError")
            val firstError = listOfNotNull(
                (barrioResult as? RaizResult.Error)?.message,
                executionsError,
            ).firstOrNull()

            // ── Fase 1: publicar ya lo leído, con los hashes que hay en caché ──
            val treasuryId = _state.value.contractTreasury
            val cached = executionHashStore.all(treasuryId)
            // Respaldo versionado para ejecuciones cuyo evento ya caducó en el RPC.
            val archive = executionHashStore.archive(treasuryId)
            val nowSec = System.currentTimeMillis() / 1000L
            // Solo pueden tener evento en el RPC las ejecuciones sin hash conocido y
            // dentro de la retención (~7 días; 8 de margen por desfase de reloj).
            val pending = rawExecutions.filter {
                it.proposalId !in cached && it.proposalId !in archive &&
                    it.executedAt >= nowSec - EVENT_RETENTION_SEC
            }
            Log.i(
                TAG,
                "Dashboard $barrioId: pool=${barrio?.poolBalanceUsdc} executions=${rawExecutions.size} " +
                    "(en caché=${rawExecutions.count { it.proposalId in cached }}, por buscar=${pending.size}) " +
                    "proposals=${proposals.size}",
            )
            _state.update {
                if (gen != loadGen) it else it.copy(
                    loading = false,
                    error = if (barrio == null) firstError else null,
                    barrio = barrio,
                    executions = rawExecutions.map { e ->
                        val local = cached[e.proposalId]
                        e.copy(
                            realTxHash = local ?: archive[e.proposalId],
                            realTxHashFromArchive = local == null && archive[e.proposalId] != null,
                        )
                    },
                    executionsLoadFailed = executionsError != null,
                    executionEventsLoading = pending.isNotEmpty(),
                    executionEventsOk = true,
                    merchantCount = merchants.size,
                    proposals = proposals,
                    vaultSharesStroops = vaultShares,
                )
            }
            if (pending.isEmpty()) return@launch

            // ── Fase 2: correlación D2 por proposal_id con los eventos del RPC ──
            val ev = withTimeoutOrNull(EVENTS_TIMEOUT_MS) {
                sorobanClient.executionEvents(barrioId, sinceEpochSec = pending.minOf { it.executedAt })
            }
            if (gen != loadGen) return@launch
            val fromEvents = (ev as? RaizResult.Success)?.data
                ?.associate { it.proposalId to it.txHash }
                .orEmpty()
            if (ev !is RaizResult.Success) {
                Log.w(TAG, "Dashboard $barrioId: eventos execution no disponibles: ${(ev as? RaizResult.Error)?.message ?: "timeout"}")
            }
            // Guardar también lo que viene del RPC: pasada la ventana seguirá enlazando.
            fromEvents.forEach { (id, hash) -> executionHashStore.save(treasuryId, id, hash) }
            _state.update {
                if (gen != loadGen) it else it.copy(
                    executions = it.executions.map { e ->
                        val live = fromEvents[e.proposalId]
                        if (live == null) e else e.copy(realTxHash = live, realTxHashFromArchive = false)
                    },
                    executionEventsLoading = false,
                    executionEventsOk = ev is RaizResult.Success,
                )
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"

        /** Retención de eventos del RPC (~7 días) con un día de margen. */
        const val EVENT_RETENTION_SEC = 8L * 24 * 3600

        /** Tope del barrido de eventos: el resto del dashboard ya está publicado. */
        const val EVENTS_TIMEOUT_MS = 90_000L
    }
}
