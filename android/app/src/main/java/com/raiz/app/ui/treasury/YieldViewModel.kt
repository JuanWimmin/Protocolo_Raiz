package com.raiz.app.ui.treasury

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.VaultPosition
import com.raiz.app.data.model.VaultStats
import com.raiz.app.data.model.toStroops
import com.raiz.app.data.stellar.DefindexClient
import com.raiz.app.data.stellar.DeploymentsLoader
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de una acción on-chain (deposit/withdraw) en el vault. */
sealed interface TreasuryAction {
    data object Idle : TreasuryAction
    data object Submitting : TreasuryAction
    data class Ok(val message: String) : TreasuryAction
    data class Failed(val message: String) : TreasuryAction
}

data class YieldUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val stats: VaultStats? = null,
    val position: VaultPosition? = null,
    /** APY en basis points (1500 = 15%). null si no hay key/endpoint. */
    val apyBps: Int? = null,
    val amountInput: String = "",
    val action: TreasuryAction = TreasuryAction.Idle,
) {
    /**
     * Rendimiento generado (stroops): valor actual − principal.
     *
     * Aprovecha que el vault arranca a precio-por-share 1.0, así que las
     * shares minteadas equivalen al USDC depositado; conforme el precio sube,
     * `currentValue − shares` es el yield acumulado de nuestra posición.
     */
    val yieldStroops: Long
        get() = ((position?.currentValueStroops ?: 0L) - (position?.shares ?: 0L))
            .coerceAtLeast(0L)
}

/**
 * ViewModel de la pantalla "Tesorería que rinde".
 *
 * Lee el estado del vault USDC de DeFindex (APY, TVL, precio por share) y la
 * posición de la tesorería RAÍZ (cuenta admin) en él. Permite depositar y
 * rescatar firmando con la clave de la tesorería.
 *
 * NOTA (Camino B): aquí la tesorería = cuenta admin del protocolo, con su
 * propio capital de reserva en el USDC del vault (distinto al USDC de los
 * pagos). En el Camino A (on-chain) el propio contrato Pool depositará el
 * fondo de cada barrio automáticamente.
 */
@HiltViewModel
class YieldViewModel @Inject constructor(
    private val defindexClient: DefindexClient,
    private val walletManager: WalletManager,
    private val deploymentsLoader: DeploymentsLoader,
) : ViewModel() {

    /** Holder/firmante = cuenta de la tesorería (admin del protocolo). */
    private val treasuryAddress: String by lazy { deploymentsLoader.load().admin }

    private val _state = MutableStateFlow(YieldUiState())
    val state: StateFlow<YieldUiState> = _state.asStateFlow()

    init { refresh() }

    fun onAmountChange(input: String) {
        // Solo dígitos y un punto decimal.
        val clean = input.filter { it.isDigit() || it == '.' }
        _state.update { it.copy(amountInput = clean) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            val statsResult = defindexClient.getVaultStats()
            val positionResult = defindexClient.getPosition(treasuryAddress)
            val apy = defindexClient.getApyBps() // best-effort, puede ser null

            val stats = (statsResult as? RaizResult.Success)?.data
            val position = (positionResult as? RaizResult.Success)?.data
            val firstError = (statsResult as? RaizResult.Error)?.message
                ?: (positionResult as? RaizResult.Error)?.message

            Log.i(
                TAG,
                "Yield: tvl=${stats?.tvlStroops} pps=${stats?.pricePerShareStroops} " +
                    "shares=${position?.shares} value=${position?.currentValueStroops} apyBps=$apy",
            )

            _state.update {
                it.copy(
                    loading = false,
                    error = if (stats == null) firstError else null,
                    stats = stats,
                    position = position,
                    apyBps = apy,
                )
            }
        }
    }

    /** Deposita el monto del input (USDC) en el vault, firmando como tesorería. */
    fun deposit() {
        val amountStroops = _state.value.amountInput.toDoubleOrNull()?.toStroops() ?: 0L
        if (amountStroops <= 0L) {
            _state.update { it.copy(action = TreasuryAction.Failed("Ingresa un monto válido en USDC.")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(action = TreasuryAction.Submitting) }
            val signer = walletManager.demoAdminKeyPair()
            if (signer == null) {
                _state.update {
                    it.copy(action = TreasuryAction.Failed(
                        "Tesorería no configurada (falta raiz.admin.secret).",
                    ))
                }
                return@launch
            }
            when (val r = defindexClient.deposit(signer, amountStroops)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Depósito OK: ${r.data} shares minteadas")
                    _state.update {
                        it.copy(action = TreasuryAction.Ok("Depósito confirmado on-chain"), amountInput = "")
                    }
                    refresh()
                }
                is RaizResult.Error -> _state.update {
                    it.copy(action = TreasuryAction.Failed(humanError(r.message)))
                }
            }
        }
    }

    /** Rescata toda la posición de shares de la tesorería. */
    fun withdrawAll() {
        val shares = _state.value.position?.shares ?: 0L
        if (shares <= 0L) {
            _state.update { it.copy(action = TreasuryAction.Failed("No hay posición que rescatar.")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(action = TreasuryAction.Submitting) }
            val signer = walletManager.demoAdminKeyPair()
            if (signer == null) {
                _state.update {
                    it.copy(action = TreasuryAction.Failed(
                        "Tesorería no configurada (falta raiz.admin.secret).",
                    ))
                }
                return@launch
            }
            when (val r = defindexClient.withdraw(signer, shares)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Rescate OK: ${r.data} stroops recuperados")
                    _state.update {
                        it.copy(action = TreasuryAction.Ok("Rescate confirmado on-chain"))
                    }
                    refresh()
                }
                is RaizResult.Error -> _state.update {
                    it.copy(action = TreasuryAction.Failed(humanError(r.message)))
                }
            }
        }
    }

    fun clearAction() {
        _state.update { it.copy(action = TreasuryAction.Idle) }
    }

    private fun humanError(raw: String): String = when {
        "InsufficientBalance" in raw || "balance" in raw.lowercase() ->
            "Saldo insuficiente de USDC en la tesorería."
        "vault DeFindex no configurado" in raw ->
            "El vault de DeFindex no está configurado."
        else -> raw
    }

    private companion object { const val TAG = "RAIZ" }
}
