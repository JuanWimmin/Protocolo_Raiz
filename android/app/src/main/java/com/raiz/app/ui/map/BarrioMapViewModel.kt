package com.raiz.app.ui.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.SorobanClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BarrioMapUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Todos los merchants de los 3 barrios, planos. */
    val merchants: List<Merchant> = emptyList(),
    /** Centro inicial del mapa (Cartagena). */
    val initialLat: Double = 10.420,
    val initialLng: Double = -75.546,
    val initialZoom: Double = 13.0,
)

@HiltViewModel
class BarrioMapViewModel @Inject constructor(
    private val sorobanClient: SorobanClient,
) : ViewModel() {

    private val _state = MutableStateFlow(BarrioMapUiState())
    val state: StateFlow<BarrioMapUiState> = _state.asStateFlow()

    init {
        loadMerchants()
    }

    private fun loadMerchants() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // Carga paralela de los 3 barrios.
            val deferred = BARRIOS.keys.map { id ->
                async { sorobanClient.listMerchants(id) }
            }
            val results = deferred.awaitAll()
            val all = mutableListOf<Merchant>()
            results.forEach { r ->
                when (r) {
                    is RaizResult.Success -> all += r.data
                    is RaizResult.Error -> Log.w(TAG, "listMerchants: ${r.message}")
                }
            }
            Log.i(TAG, "Mapa cargado: ${all.size} comercios en ${BARRIOS.size} barrios")
            _state.update {
                it.copy(loading = false, merchants = all)
            }
        }
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
