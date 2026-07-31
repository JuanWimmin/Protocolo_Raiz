package com.raiz.app.data.model

/**
 * Modelos de yield sobre el fondo ocioso — F1: Blend v2 directo vía el
 * contrato propio `yield_adapter` (ver `docs/raiz_v2_spec_contratos.md`,
 * "Contrato 5: yield_adapter"). Sustituyen a los antiguos `VaultStats` /
 * `VaultPosition` del vault DeFindex (eliminados junto con `DefindexClient`).
 */

/**
 * Posición de yield de UN barrio vía el YieldAdapter. Espejo del modelo
 * `YieldPosition` de `docs/RaizModels.kt`.
 *
 * `shares` son bTokens de Blend, expresados en la misma escala de 7
 * decimales que USDC a efectos de comparación "depositado vs. valor
 * actual" (igual convención que tenían las shares del vault DeFindex).
 */
data class YieldPosition(
    /** hex de 64 chars. */
    val barrioId: String,
    /** bTokens del barrio — contabilidad que vive en el yield_adapter. */
    val shares: Long,
    /** Valor actual en stroops de USDC: shares × bRate / 1e12. */
    val valueUsdc: Long,
    /** APY estimado en basis points (informativo, variable — ver [BlendClient.getAdapterApyBps]). */
    val apyBps: Int,
)

/**
 * Estado agregado de la reserva USDC del pool Blend v2 (TestnetV2), leído
 * vía `get_reserve(usdc)` DIRECTAMENTE contra el pool de Blend (no contra
 * un contrato propio de RAÍZ — Blend ya vive en testnet de forma
 * independiente al deploy F1).
 *
 * TVL/utilización de TODO el pool Blend, no solo la posición de RAÍZ: da
 * contexto de riesgo/liquidez en la UI ("¿qué tan lleno/prestado está el
 * pool donde el fondo comunitario invierte?").
 */
data class BlendReserveStats(
    /** bToken -> subyacente. Escala 1e12 en Blend v2 (en v1 era 1e9 — no mezclar). */
    val bRate: Long,
    /** dToken -> subyacente. Escala 1e12. */
    val dRate: Long,
    /** Supply total de bTokens del pool Blend (todos los depositantes, no solo RAÍZ). */
    val bSupplyStroops: Long,
    /** Supply total de dTokens (deuda) del pool Blend. */
    val dSupplyStroops: Long,
    /** Activos totales del pool en USDC stroops: b_supply × b_rate / 1e12. */
    val tvlStroops: Long,
    /** Utilización = pasivos/activos, en basis points. */
    val utilizationBps: Int,
)
