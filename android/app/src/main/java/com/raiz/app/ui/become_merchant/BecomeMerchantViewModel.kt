package com.raiz.app.ui.become_merchant

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.BuildConfig
import com.raiz.app.data.model.MerchantCategory
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.RoleResolver
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject

/** Pasos del onboarding de comerciante: datos primero, ubicación después. */
enum class OnboardingStep { INFO, LOCATION }

/**
 * Coordenadas centrales de cada barrio (lat/lng × 1_000_000 como Int).
 * El comerciante hereda estas coordenadas si no elige una ubicación propia,
 * o como punto inicial del mapa picker.
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
    // ── Paso activo del onboarding ─────────────────────────────────────
    val step: OnboardingStep = OnboardingStep.INFO,
    // ── Ubicación elegida por el comerciante (null = aún no eligió) ────
    val pickedLatE6: Int? = null,
    val pickedLngE6: Int? = null,
    // ── Búsqueda por dirección ─────────────────────────────────────────
    val addressQuery: String = "",
    val geocodingLoading: Boolean = false,
    val pickedAddress: String? = null,
) {
    val barrioOptions: List<Pair<String, String>>
        get() = BARRIO_INFO.entries.map { it.key to it.value.first }

    val barrioName: String
        get() = BARRIO_INFO[barrioId]?.first ?: "—"

    /** El nombre tiene al menos 2 caracteres — habilita el botón "Siguiente". */
    val canGoNext: Boolean
        get() = name.trim().length >= 2

    /** Habilita el botón final "Registrarme" en el paso de ubicación. */
    val canSubmit: Boolean
        get() = name.trim().length >= 2 && !submitting && !success

    /** Latitud del centro del barrio en grados para inicializar el mapa. */
    val barrioCenterLat: Double
        get() = (BARRIO_INFO[barrioId]?.second ?: 10_421_500) / 1_000_000.0

    /** Longitud del centro del barrio en grados para inicializar el mapa. */
    val barrioCenterLng: Double
        get() = (BARRIO_INFO[barrioId]?.third ?: -75_547_800) / 1_000_000.0

    /**
     * Latitud final a registrar on-chain (× 1e6).
     * Si el usuario no eligió en el mapa, usa el centro del barrio.
     */
    val finalLatE6: Int
        get() = pickedLatE6 ?: (BARRIO_INFO[barrioId]?.second ?: 10_421_500)

    /**
     * Longitud final a registrar on-chain (× 1e6).
     * Si el usuario no eligió en el mapa, usa el centro del barrio.
     */
    val finalLngE6: Int
        get() = pickedLngE6 ?: (BARRIO_INFO[barrioId]?.third ?: -75_547_800)
}

@HiltViewModel
class BecomeMerchantViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val roleResolver: RoleResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(BecomeMerchantUiState())
    val state: StateFlow<BecomeMerchantUiState> = _state.asStateFlow()

    // ── Paso 1: datos del negocio ─────────────────────────────────────

    fun updateName(value: String) {
        // Saneo básico: quita caracteres de control y limita a MAX_NAME.
        val clean = value.replace(CONTROL_CHARS, "").take(MAX_NAME)
        _state.update { it.copy(name = clean, error = null) }
    }

    fun selectCategory(c: MerchantCategory) = _state.update { it.copy(category = c) }

    fun selectBarrio(barrioId: String) = _state.update {
        // Al cambiar el barrio se resetea la ubicación elegida para que el mapa
        // vuelva a centrar en el nuevo barrio la próxima vez que se abra el paso 2.
        it.copy(
            barrioId = barrioId,
            pickedLatE6 = null,
            pickedLngE6 = null,
            pickedAddress = null,
        )
    }

    /** Avanza al paso 2 (ubicación). Solo ejecuta si el nombre es válido. */
    fun goToLocationStep() {
        if (_state.value.canGoNext) {
            _state.update { it.copy(step = OnboardingStep.LOCATION, error = null) }
        }
    }

    /** Retrocede al paso 1 (datos). Se llama desde el botón Atrás en paso 2. */
    fun backToInfoStep() = _state.update { it.copy(step = OnboardingStep.INFO, error = null) }

    // ── Paso 2: ubicación ─────────────────────────────────────────────

    /**
     * Guarda las coordenadas elegidas tocando el mapa.
     * Recibe latE6/lngE6 directamente del OnMapClickListener de Mapbox.
     */
    fun onMapTapped(latE6: Int, lngE6: Int) {
        _state.update {
            it.copy(
                pickedLatE6 = latE6,
                pickedLngE6 = lngE6,
                pickedAddress = null, // el tap borra la dirección buscada
                error = null,
            )
        }
    }

    /** Actualiza el texto del campo de búsqueda de dirección. */
    fun onAddressQueryChanged(q: String) {
        _state.update { it.copy(addressQuery = q, error = null) }
    }

    /**
     * Geocodifica el texto de [BecomeMerchantUiState.addressQuery] usando la
     * API pública de Mapbox Geocoding v5.
     *
     * URL: https://api.mapbox.com/geocoding/v5/mapbox.places/{query}.json
     *      ?access_token=<token>&country=co&limit=1
     *
     * El resultado actualiza [pickedLatE6] / [pickedLngE6] / [pickedAddress]
     * en el estado, y el LaunchedEffect de la UI anima la cámara al punto.
     */
    fun searchAddress() {
        val query = _state.value.addressQuery.trim()
        if (query.isEmpty()) return
        _state.update { it.copy(geocodingLoading = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = BuildConfig.MAPBOX_TOKEN
                val encoded = URLEncoder.encode(query, "UTF-8")
                val urlStr = "https://api.mapbox.com/geocoding/v5/mapbox.places/$encoded.json" +
                    "?access_token=$token&country=co&limit=1"

                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    readTimeout = 8_000
                    connectTimeout = 8_000
                    requestMethod = "GET"
                }
                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                geocodingLoading = false,
                                error = "No se pudo geocodificar (HTTP $responseCode). Toca el mapa.",
                            )
                        }
                    }
                    return@launch
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val features = JSONObject(body).getJSONArray("features")
                if (features.length() == 0) {
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                geocodingLoading = false,
                                error = "No se encontró esa dirección. Toca el mapa para fijar la ubicación.",
                            )
                        }
                    }
                    return@launch
                }

                val feat = features.getJSONObject(0)
                // La API devuelve center = [lng, lat]
                val center = feat.getJSONArray("center")
                val lng = center.getDouble(0)
                val lat = center.getDouble(1)
                val placeName = feat.optString("place_name", query)

                val latE6 = (lat * 1_000_000).toInt()
                val lngE6 = (lng * 1_000_000).toInt()
                Log.i(TAG, "Geocodificación OK: $placeName lat=$latE6 lng=$lngE6")

                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            geocodingLoading = false,
                            pickedLatE6 = latE6,
                            pickedLngE6 = lngE6,
                            pickedAddress = placeName,
                            error = null,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error geocodificando: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            geocodingLoading = false,
                            error = "Error de red al buscar la dirección.",
                        )
                    }
                }
            }
        }
    }

    // ── Envío ─────────────────────────────────────────────────────────

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
                        error = "Admin no configurado en local.properties. En producción pasaría por revisión del admin del barrio.",
                    )
                }
                return@launch
            }

            // Coordenadas finales: las que el comerciante eligió (mapa o geocodificación)
            // o el centro del barrio si no eligió ninguna.
            val latE6 = s.finalLatE6
            val lngE6 = s.finalLngE6

            Log.i(
                TAG,
                "registerMerchant addr=$merchantAccount name=${s.name} " +
                    "cat=${s.category.symbol} barrio=${s.barrioName} " +
                    "lat=$latE6 lng=$lngE6",
            )

            val result = sorobanClient.registerMerchant(
                admin = admin,
                merchantAddress = merchantAccount,
                name = s.name,
                barrioId = s.barrioId,
                latE6 = latE6,
                lngE6 = lngE6,
                categorySymbol = s.category.symbol,
            )

            when (result) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Merchant registrado on-chain con lat=$latE6 lng=$lngE6")
                    // Invalida el cache para que RoleResolver detecte al usuario como MERCHANT.
                    roleResolver.invalidate()
                    _state.update { it.copy(submitting = false, success = true) }
                }
                is RaizResult.Error -> _state.update {
                    it.copy(submitting = false, error = result.message)
                }
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
        const val MAX_NAME = 40
        val CONTROL_CHARS = Regex("""\p{Cntrl}""")
    }
}
