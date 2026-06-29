package com.raiz.app.data.model

/**
 * Modelos espejo del vault de DeFindex (protocolo de yield sobre Soroban).
 *
 * Las unidades monetarias son siempre stroops (Long). Las shares también se
 * expresan como Long con 7 decimales (misma escala que USDC).
 */

/** Estado global del vault: TVL, oferta de shares y precio por share. */
data class VaultStats(
    /** Suma de `total_amount` de todas las estrategias activas (stroops USDC). */
    val tvlStroops: Long,
    /** Shares totales en circulación — `total_supply()`. */
    val totalSupplyShares: Long,
    /**
     * Valor en USDC (stroops) de 1 share completa (10_000_000 unidades).
     * Se obtiene vía `get_asset_amounts_per_shares(10_000_000)[0]`.
     */
    val pricePerShareStroops: Long,
)

/** Posición de un holder individual en el vault. */
data class VaultPosition(
    /** Shares que posee el holder — `balance(id)`. */
    val shares: Long,
    /**
     * Valor actual en USDC (stroops) de las shares.
     * 0 si `shares == 0`. Calculado vía `get_asset_amounts_per_shares(shares)[0]`.
     */
    val currentValueStroops: Long,
)
