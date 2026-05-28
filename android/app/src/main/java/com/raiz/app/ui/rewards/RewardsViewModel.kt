package com.raiz.app.ui.rewards

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.Reward
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de un canje individual (id → estado). */
sealed interface RedeemStatus {
    data object Submitting : RedeemStatus
    data class Ok(val redemptionId: Long) : RedeemStatus
    data class Failed(val code: RaizErrorCode, val message: String) : RedeemStatus
}

data class RewardsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Rewards agrupados por barrio para listar por sección. */
    val rewardsByBarrio: List<Pair<String, List<Reward>>> = emptyList(),
    /** Puntos del turista on-chain. -1 si aún no se cargó. */
    val points: Long = -1L,
    /** Estado de redeems en curso o resueltos. */
    val redeemState: Map<Long, RedeemStatus> = emptyMap(),
)

@HiltViewModel
class RewardsViewModel @Inject constructor(
    private val sorobanClient: SorobanClient,
    private val walletManager: WalletManager,
) : ViewModel() {

    private val _state = MutableStateFlow(RewardsUiState())
    val state: StateFlow<RewardsUiState> = _state.asStateFlow()

    init {
        loadAll()
    }

    /** Carga rewards de los 3 barrios + puntos del turista en paralelo. */
    fun loadAll() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val accountId = walletManager.mockWallet().publicKey

            // Carga paralela de rewards por barrio.
            val barrios = BARRIOS.entries.toList()
            val deferred = barrios.map { (id, name) ->
                async {
                    name to sorobanClient.listRewards(id)
                }
            }
            val results = deferred.awaitAll()
            val combined = mutableListOf<Pair<String, List<Reward>>>()
            results.forEach { (name, r) ->
                when (r) {
                    is RaizResult.Success -> if (r.data.isNotEmpty()) combined += name to r.data
                    is RaizResult.Error -> Log.w(TAG, "listRewards($name): ${r.message}")
                }
            }

            // Puntos del turista
            val points = when (val r = sorobanClient.getPoints(accountId)) {
                is RaizResult.Success -> r.data
                is RaizResult.Error -> {
                    Log.w(TAG, "getPoints: ${r.message}")
                    0L
                }
            }
            Log.i(TAG, "Rewards cargados: ${combined.sumOf { it.second.size }} en ${combined.size} barrios · puntos=$points")

            _state.update {
                it.copy(
                    loading = false,
                    rewardsByBarrio = combined,
                    points = points,
                )
            }
        }
    }

    /** Canje firmado de un reward. Refresca tras OK. */
    fun redeem(reward: Reward) {
        viewModelScope.launch {
            _state.update { it.copy(redeemState = it.redeemState + (reward.id to RedeemStatus.Submitting)) }

            val keyPair = walletManager.demoKeyPair()
            if (keyPair == null) {
                _state.update {
                    it.copy(
                        redeemState = it.redeemState + (reward.id to RedeemStatus.Failed(
                            RaizErrorCode.UNAUTHORIZED,
                            "Wallet demo no configurada.",
                        )),
                    )
                }
                return@launch
            }

            Log.i(TAG, "Canjeando reward ${reward.id} (${reward.name})")
            when (val r = sorobanClient.redeem(keyPair, reward.id)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Canje OK · redemption_id=${r.data}")
                    _state.update {
                        it.copy(redeemState = it.redeemState + (reward.id to RedeemStatus.Ok(r.data)))
                    }
                    // Refrescar puntos + stock
                    loadAll()
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "Canje falló: ${r.code} — ${r.message}")
                    _state.update {
                        it.copy(redeemState = it.redeemState + (reward.id to RedeemStatus.Failed(
                            r.code,
                            humanError(r.code),
                        )))
                    }
                }
            }
        }
    }

    /** Limpia el feedback de un canje tras mostrarlo. */
    fun dismissRedeem(rewardId: Long) {
        _state.update { it.copy(redeemState = it.redeemState - rewardId) }
    }

    private fun humanError(code: RaizErrorCode): String = when (code) {
        RaizErrorCode.INSUFFICIENT_POINTS -> "Te faltan puntos para este premio"
        RaizErrorCode.OUT_OF_STOCK -> "Este premio se agotó"
        RaizErrorCode.NOT_FOUND -> "El premio ya no está disponible"
        RaizErrorCode.UNAUTHORIZED -> "Wallet no configurada"
        else -> "No se pudo canjear. Inténtalo de nuevo."
    }

    private companion object {
        const val TAG = "RAIZ"
        val BARRIOS: LinkedHashMap<String, String> = linkedMapOf(
            "ce47120000000000000000000000000000000000000000000000000000000001" to "Centro Histórico",
            "bba17e0000000000000000000000000000000000000000000000000000000002" to "Barrio Norte",
            "c057a9000000000000000000000000000000000000000000000000000000000a" to "Costa Vieja",
        )
    }
}
