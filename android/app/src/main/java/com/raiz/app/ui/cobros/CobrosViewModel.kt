package com.raiz.app.ui.cobros

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.HorizonStream
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado de CobrosScreen.
 *
 * [accountId] es la Stellar address del comerciante — se usa para generar el QR.
 * [ultimosCobros] son los últimos pagos ENTRANTES (isOutgoing = false).
 * [totalReceivedStroops] suma de todos los cobros cargados.
 */
data class CobrosUiState(
    val accountId: String = "",
    val totalReceivedStroops: Long = 0L,
    val cobroCount: Int = 0,
    val ultimosCobros: List<PaymentRecord> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * ViewModel de CobrosScreen — pantalla exclusiva del rol MERCHANT.
 *
 * Lee el historial de pagos vía [HorizonStream.paymentHistory] y filtra
 * solo los entrantes. Los cobros salientes (compras del comerciante) no se
 * muestran aquí.
 */
@HiltViewModel
class CobrosViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val horizonStream: HorizonStream,
) : ViewModel() {

    private val _state = MutableStateFlow(
        CobrosUiState(accountId = walletManager.currentAccountId() ?: ""),
    )
    val state: StateFlow<CobrosUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val accountId = walletManager.currentAccountId() ?: return
        _state.update { it.copy(accountId = accountId, loading = true, error = null) }
        viewModelScope.launch {
            when (val r = horizonStream.paymentHistory(accountId, limit = 50)) {
                is RaizResult.Success -> {
                    val incoming = r.data.filter { !it.isOutgoing }
                    val total = incoming.sumOf { it.amountStroops }
                    Log.i(TAG, "Cobros cargados: ${incoming.size}, total=$total stroops")
                    _state.update {
                        it.copy(
                            loading = false,
                            ultimosCobros = incoming,
                            cobroCount = incoming.size,
                            totalReceivedStroops = total,
                        )
                    }
                }
                is RaizResult.Error -> _state.update {
                    it.copy(loading = false, error = r.message)
                }
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
