package com.raiz.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.stellar.HorizonStream
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val wallet: WalletState,
    val history: List<PaymentRecord> = emptyList(),
    val historyLoading: Boolean = true,
    val historyError: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val horizonStream: HorizonStream,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileUiState(wallet = walletManager.mockWallet()),
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        observeBalance()
        loadHistory()
    }

    /** Polling del balance USDC vía Horizon — igual que WalletViewModel. */
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
                is RaizResult.Success -> {
                    Log.i(TAG, "Historial cargado: ${r.data.size} pagos")
                    _state.update {
                        it.copy(history = r.data, historyLoading = false, historyError = null)
                    }
                }
                is RaizResult.Error -> {
                    Log.w(TAG, "Historial falló: ${r.message}")
                    _state.update {
                        it.copy(historyLoading = false, historyError = r.message)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
