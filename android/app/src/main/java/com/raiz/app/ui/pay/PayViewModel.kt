package com.raiz.app.ui.pay

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.MerchantCategory
import com.raiz.app.data.model.PaymentPreview
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.security.AppLock
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
    data object Loading : PayUiState
    data class Editing(
        val preview: PaymentPreview,
        val tipEnabled: Boolean,
        val submitting: Boolean,
        /** Si true, confirmar el pago exige biometría/PIN (lock activado). */
        val requireBiometric: Boolean = false,
    ) : PayUiState
    data class Success(
        val merchantName: String,
        val totalStroops: Long,
        val pointsEarned: Long,
        /** Stroops del Tip Barrio; 0 si el tip estaba desactivado al confirmar. */
        val tipStroops: Long = 0L,
    ) : PayUiState
    data class Error(val code: RaizErrorCode, val message: String) : PayUiState
}

@HiltViewModel
class PayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val appLock: AppLock,
) : ViewModel() {

    // address del merchant, vía nav arg `merchant_address`. Si está vacío
    // usamos el merchant demo (Cafe Don Aurelio) para compat con flow viejo.
    private val merchantAddress: String =
        savedStateHandle.get<String>("merchant_address").orEmpty().ifBlank { DEMO_MERCHANT_ADDR }

    private val defaultAmountStroops = 50_000_000L  // 5 USDC

    private val _state = MutableStateFlow<PayUiState>(PayUiState.Loading)
    val state: StateFlow<PayUiState> = _state.asStateFlow()

    init {
        loadMerchant()
    }

    private fun loadMerchant() {
        viewModelScope.launch {
            // Fast-path para el demo merchant (sin round-trip): valores conocidos.
            if (merchantAddress == DEMO_MERCHANT_ADDR) {
                _state.value = stateFor(demoMerchant)
                return@launch
            }
            Log.i(TAG, "Cargando merchant: $merchantAddress")
            when (val r = sorobanClient.getMerchant(merchantAddress)) {
                is RaizResult.Success -> _state.value = stateFor(r.data)
                is RaizResult.Error -> _state.value = PayUiState.Error(r.code, r.message)
            }
        }
    }

    private fun stateFor(m: Merchant): PayUiState.Editing = PayUiState.Editing(
        preview = PaymentPreview(m, defaultAmountStroops, RaizConstants.DEFAULT_TIP_BPS),
        tipEnabled = true,
        submitting = false,
        requireBiometric = appLock.isActive(),
    )

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
            val keyPair = walletManager.currentKeyPair()
            if (keyPair == null) {
                _state.value = PayUiState.Error(
                    RaizErrorCode.UNAUTHORIZED,
                    "No hay wallet configurada para firmar pagos.",
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
                        tipStroops = editing.preview.tipStroops,
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
        const val DEMO_MERCHANT_ADDR = "GA7CDZDQJVKUQGMY5ZCTYOCDOXIWEDRPE5YAGHGJZOB4QM6WMUVE4TSN"
        val demoMerchant = Merchant(
            address = DEMO_MERCHANT_ADDR,
            name = "Cafe Don Aurelio",
            barrioId = "ce47120000000000000000000000000000000000000000000000000000000001",
            verified = true,
            latE6 = 10_422_100,
            lngE6 = -75_547_800,
            category = MerchantCategory.CAFE,
        )
    }
}
