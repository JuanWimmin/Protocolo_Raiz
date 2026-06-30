package com.raiz.app.ui.cobros

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizConstants
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
 * [montoCobro] texto USDC que teclea el comerciante (vacío = cobro abierto).
 * [montoStroops] conversión de [montoCobro] a stroops; 0 = monto abierto.
 * [qrContent] contenido del QR: incluye monto si se especificó, si no solo la dirección.
 */
data class CobrosUiState(
    val accountId: String = "",
    val totalReceivedStroops: Long = 0L,
    val cobroCount: Int = 0,
    val ultimosCobros: List<PaymentRecord> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    // Orden de cobro por monto fijo
    val montoCobro: String = "",    // texto tal como lo escribe el comerciante (USDC)
    val montoStroops: Long = 0L,    // 0 = monto abierto; >0 = orden de cobro fija
) {
    /**
     * Contenido que se codifica en el QR.
     *
     * - Con monto: `raiz:pay?to=<address>&amount=<stroops>` (orden de cobro fija)
     * - Sin monto: solo la Stellar address (cobro abierto — comportamiento anterior)
     */
    val qrContent: String
        get() = if (montoStroops > 0L && accountId.isNotBlank())
            "raiz:pay?to=$accountId&amount=$montoStroops"
        else
            accountId
}

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

    /**
     * Actualiza el monto que quiere cobrar el comerciante.
     *
     * Sanea el texto (solo dígitos + un punto decimal), convierte a stroops y
     * actualiza el estado. El QR en la UI se regenera automáticamente vía
     * [CobrosUiState.qrContent].
     *
     * @param texto Texto tal como lo escribe el usuario en el TextField (USDC).
     */
    fun onMontoCambiado(texto: String) {
        val sanitizado = sanitizarMonto(texto)
        val usdc = sanitizado.toDoubleOrNull() ?: 0.0
        val stroops = (usdc * RaizConstants.USDC_STROOPS_PER_UNIT).toLong()
        _state.update { it.copy(montoCobro = sanitizado, montoStroops = stroops) }
    }

    /**
     * Filtra el texto a solo dígitos y un punto decimal.
     * Garantiza que no haya más de un separador decimal.
     */
    private fun sanitizarMonto(texto: String): String {
        val filtrado = texto.filter { it.isDigit() || it == '.' }
        val partes = filtrado.split('.')
        return when {
            partes.size <= 1 -> filtrado
            else -> "${partes[0]}.${partes.drop(1).joinToString("")}"
        }
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
