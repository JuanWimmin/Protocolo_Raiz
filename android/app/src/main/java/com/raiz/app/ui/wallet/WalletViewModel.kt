package com.raiz.app.ui.wallet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface WalletUiState {
    data object Loading : WalletUiState
    data class Ready(
        val wallet: WalletState,
        val poolBalanceLabel: String,    // texto del aporte/saldo del barrio actual, o "—"
    ) : WalletUiState
    data class Error(val message: String) : WalletUiState
}

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    // TODO: HorizonStream para balance USDC en vivo (siguiente paso).
) : ViewModel() {

    private val _state = MutableStateFlow<WalletUiState>(
        WalletUiState.Ready(
            wallet = walletManager.mockWallet(),
            poolBalanceLabel = "Conectando…",
        ),
    )
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    init {
        // Smoke test del round-trip a testnet: llama get_pool_balance del
        // barrio Centro Histórico (id que dejó el seed). Si responde, el
        // cableado Stellar SDK → Soroban RPC → contrato funciona end-to-end.
        loadCentroPoolBalance()
    }

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
                        } else {
                            current
                        }
                    }
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "getPoolBalance falló: ${result.code} — ${result.message}")
                    _state.update { current ->
                        if (current is WalletUiState.Ready) {
                            current.copy(poolBalanceLabel = "Sin red")
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
        // barrio Centro Histórico — del seed (scripts/seed_testnet.sh).
        const val BARRIO_CENTRO_ID = "ce47120000000000000000000000000000000000000000000000000000000001"
    }
}
