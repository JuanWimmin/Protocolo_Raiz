package com.raiz.app.data.model

/**
 * Categorías de comercio — los mismos `Symbol` que usa el contrato Pool.
 * El `symbol` se usa para serializar a/de SCVal; el `label` es texto user-facing.
 */
enum class MerchantCategory(val symbol: String, val label: String) {
    CAFE("cafe", "Café"),
    RESTAURANTE("restaurante", "Restaurante"),
    ARTESANIA("artesania", "Artesanía"),
    TIENDA("tienda", "Tienda"),
    CULTURA("cultura", "Cultura"),
    HOSPEDAJE("hospedaje", "Hospedaje"),
    OTRO("otro", "Otro");

    companion object {
        fun fromSymbol(s: String) = entries.firstOrNull { it.symbol == s } ?: OTRO
    }
}

/**
 * Espejo de MerchantData en el contrato Pool.
 * lat/lng vienen como i32 × 1_000_000 desde el contrato.
 */
data class Merchant(
    val address: String,             // G... cuenta del comercio
    val name: String,
    val barrioId: String,            // hex 64 chars
    val verified: Boolean,
    val latE6: Int,
    val lngE6: Int,
    val category: MerchantCategory,
) {
    val lat: Double get() = latE6 / 1_000_000.0
    val lng: Double get() = lngE6 / 1_000_000.0
}
