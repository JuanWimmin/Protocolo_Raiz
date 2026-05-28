package com.raiz.app.ui.pay

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.MerchantCategory
import com.raiz.app.data.model.PaymentPreview
import com.raiz.app.data.model.RaizConstants
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

sealed interface PayUiState {
    data class Editing(
        val preview: PaymentPreview,
        val tipEnabled: Boolean,
        val submitting: Boolean,
    ) : PayUiState

    data class Success(val merchantName: String, val totalStroops: Long, val pointsEarned: Long) : PayUiState
    data class Error(val code: RaizErrorCode, val message: String) : PayUiState
}

@HiltViewModel
class PayViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
) : ViewModel() {

    // Para el demo arrancamos con el primer merchant del Centro Histórico
    // (Cafe Don Aurelio del seed). Cuando integremos QR, este state se
    // inicializa con el merchant escaneado.
    private val demoMerchant = Merchant(
        address = "GA7CDZDQJVKUQGMY5ZCTYOCDOXIWEDRPE5YAGHGJZOB4QM6WMUVE4TSN",
        name = "Cafe Don Aurelio",
        barrioId = "ce47120000000000000000000000000000000000000000000000000000000001",
        verified = true,
        latE6 = 10_422_100,
        lngE6 = -75_547_800,
        category = MerchantCategory.CAFE,
    )

    // 5 USDC default — usuario puede cambiar en una próxima versión con un input.
    private val defaultAmountStroops = 50_000_000L

    private val _state = MutableStateFlow<PayUiState>(
        PayUiState.Editing(
            preview = PaymentPreview(demoMerchant, defaultAmountStroops, RaizConstants.DEFAULT_TIP_BPS),
            tipEnabled = true,
            submitting = false,
        ),
    )
    val state: StateFlow<PayUiState> = _state.asStateFlow()

    fun toggleTip() {
        _state.update { current ->
            if (current is PayUiState.Editing) {
                val newTipBps = if (current.tipEnabled) 0 else RaizConstants.DEFAULT_TIP_BPS
                current.copy(
                    preview = current.preview.copy(tipBps = newTipBps),
                    tipEnabled = !current.tipEnabled,
                )
            } else {
                current
            }
        }
    }

    fun confirm() {
        val editing = _state.value as? PayUiState.Editing ?: return
        if (editing.submitting) return

        _state.update { editing.copy(submitting = true) }

        viewModelScope.launch {
            val keyPair = walletManager.demoKeyPair()
            if (keyPair == null) {
                _state.value = PayUiState.Error(
                    RaizErrorCode.UNAUTHORIZED,
                    "Wallet demo no configurada. Revisa local.properties.",
                )
                return@launch
            }

            Log.i(TAG, "Pagando ${editing.preview.baseAmountUsdc} USDC tip=${editing.preview.tipBps}bps a ${editing.preview.merchant.name}")
            val result = sorobanClient.payMerchant(
                tourist = keyPair,
                merchantAddress = editing.preview.merchant.address,
                amountStroops = editing.preview.baseAmountStroops,
                tipBps = editing.preview.tipBps,
            )
            when (result) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Pago OK")
                    _state.value = PayUiState.Success(
                        merchantName = editing.preview.merchant.name,
                        totalStroops = editing.preview.totalStroops,
                        pointsEarned = editing.preview.pointsToEarn,
                    )
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "Pago falló: ${result.code} — ${result.message}")
                    _state.value = PayUiState.Error(result.code, result.message)
                }
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
