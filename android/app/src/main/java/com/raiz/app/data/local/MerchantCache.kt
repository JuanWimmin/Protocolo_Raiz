package com.raiz.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.MerchantCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Espejo serializable de [Merchant] (el modelo no es `@Serializable` y no queremos tocarlo). */
@Serializable
data class CachedMerchant(
    val address: String,
    val name: String,
    val barrioId: String,
    val verified: Boolean,
    val latE6: Int,
    val lngE6: Int,
    val category: String,
) {
    fun toModel() = Merchant(
        address = address,
        name = name,
        barrioId = barrioId,
        verified = verified,
        latE6 = latE6,
        lngE6 = lngE6,
        category = MerchantCategory.fromSymbol(category),
    )

    companion object {
        fun from(m: Merchant) = CachedMerchant(
            address = m.address,
            name = m.name,
            barrioId = m.barrioId,
            verified = m.verified,
            latE6 = m.latE6,
            lngE6 = m.lngE6,
            category = m.category.symbol,
        )
    }
}

/**
 * Última foto conocida de los comercios del territorio, para consultarlos **sin señal** (pago diferido).
 * Nada de mocks: solo lo que la red devolvió alguna vez. La llena `SorobanClient` cada vez que
 * `list_merchants` / `get_merchant` responden con éxito.
 */
@Singleton
class MerchantCache @Inject constructor(
    @ApplicationContext appContext: Context,
) {
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(CachedMerchant.serializer())

    fun putBarrio(barrioId: String, merchants: List<Merchant>, nowMs: Long = System.currentTimeMillis()) {
        val editor = prefs.edit()
        editor.putString(keyBarrio(barrioId), json.encodeToString(listSerializer, merchants.map(CachedMerchant::from)))
        editor.putLong(keyUpdated(barrioId), nowMs)
        merchants.forEach { editor.putString(keyMerchant(it.address), json.encodeToString(CachedMerchant.serializer(), CachedMerchant.from(it))) }
        editor.apply()
    }

    fun putMerchant(merchant: Merchant) {
        prefs.edit()
            .putString(keyMerchant(merchant.address), json.encodeToString(CachedMerchant.serializer(), CachedMerchant.from(merchant)))
            .apply()
    }

    /** Comercio por dirección, si alguna vez se consultó con red. */
    fun merchant(address: String): Merchant? =
        prefs.getString(keyMerchant(address), null)
            ?.let { runCatching { json.decodeFromString(CachedMerchant.serializer(), it) }.getOrNull() }
            ?.toModel()

    /** Lista de un barrio, o null si nunca se cargó. */
    fun barrio(barrioId: String): List<Merchant>? =
        prefs.getString(keyBarrio(barrioId), null)
            ?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            ?.map(CachedMerchant::toModel)

    fun updatedAt(barrioId: String): Long? = prefs.getLong(keyUpdated(barrioId), -1L).takeIf { it > 0 }

    private fun keyBarrio(barrioId: String) = "barrio_$barrioId"
    private fun keyUpdated(barrioId: String) = "updated_$barrioId"
    private fun keyMerchant(address: String) = "m_$address"

    private companion object {
        const val PREFS = "raiz_merchants"
    }
}
