package com.raiz.app.ui.become_merchant

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.MerchantCategory
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.RoleResolver
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordenadas centrales del barrio para los pines del mapa.
 *  - Centro Histórico (Cartagena)
 *  - Barrio Norte (Bogotá)
 *  - Costa Vieja (Cartagena)
 * El comerciante hereda estas coordenadas por defecto + un pequeño jitter
 * para que su pin no se superponga exactamente con otros.
 */
private val BARRIO_INFO = linkedMapOf(
    "ce47120000000000000000000000000000000000000000000000000000000001" to
        Triple("Centro Histórico", 10_421_500, -75_547_800),
    "bba17e0000000000000000000000000000000000000000000000000000000002" to
        Triple("Barrio Norte", 4_670_000, -74_055_000),
    "c057a9000000000000000000000000000000000000000000000000000000000a" to
        Triple("Costa Vieja", 10_405_500, -75_535_000),
)

data class BecomeMerchantUiState(
    val name: String = "",
    val category: MerchantCategory = MerchantCategory.CAFE,
    val barrioId: String = BARRIO_INFO.keys.first(),
    val submitting: Boolean = false,
    val success: Boolean = false,
    val error: String? = null,
) {
    val barrioOptions: List<Pair<String, String>>
        get() = BARRIO_INFO.entries.map { it.key to it.value.first }

    val barrioName: String
        get() = BARRIO_INFO[barrioId]?.first ?: "—"

    val canSubmit: Boolean
        get() = name.isNotBlank() && !submitting && !success
}

@HiltViewModel
class BecomeMerchantViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val roleResolver: RoleResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(BecomeMerchantUiState())
    val state: StateFlow<BecomeMerchantUiState> = _state.asStateFlow()

    fun updateName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun selectCategory(c: MerchantCategory) = _state.update { it.copy(category = c) }
    fun selectBarrio(barrioId: String) = _state.update { it.copy(barrioId = barrioId) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val merchantAccount = walletManager.currentAccountId()
            if (merchantAccount == null) {
                _state.update { it.copy(submitting = false, error = "No hay wallet activa.") }
                return@launch
            }
            val admin = walletManager.demoAdminKeyPair()
            if (admin == null) {
                _state.update {
                    it.copy(
                        submitting = false,
                        error = "Admin no configurado en local.properties. En producción esto pasaría por un flujo de aprobación.",
                    )
                }
                return@launch
            }

            val (_, lat, lng) = BARRIO_INFO[s.barrioId]!!
            // Jitter pequeño (~50 metros) para que el pin no quede encima
            // de otros del mismo barrio. ±200 microgrados ≈ ±20 m.
            val jitterLat = (-200..200).random()
            val jitterLng = (-200..200).random()

            Log.i(
                TAG,
                "registerMerchant addr=$merchantAccount name=${s.name} cat=${s.category.symbol} barrio=${s.barrioName}",
            )
            val result = sorobanClient.registerMerchant(
                admin = admin,
                merchantAddress = merchantAccount,
                name = s.name,
                barrioId = s.barrioId,
                latE6 = lat + jitterLat,
                lngE6 = lng + jitterLng,
                categorySymbol = s.category.symbol,
            )

            when (result) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Merchant registrado on-chain")
                    // Invalida el cache del RoleResolver para que la próxima
                    // lectura detecte al usuario como MERCHANT.
                    roleResolver.invalidate()
                    _state.update { it.copy(submitting = false, success = true) }
                }
                is RaizResult.Error -> _state.update {
                    it.copy(submitting = false, error = result.message)
                }
            }
        }
    }

    private companion object { const val TAG = "RAIZ" }
}
