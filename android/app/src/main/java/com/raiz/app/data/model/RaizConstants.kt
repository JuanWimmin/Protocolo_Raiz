package com.raiz.app.data.model

/**
 * Constantes del dominio RAÍZ. Espejo de las constantes hardcodeadas en los
 * contratos Soroban (ver `docs/raiz_v2_spec_contratos.md`).
 *
 * Si cambian aquí, también cambian en el contrato — y viceversa. El
 * spec-auditor del workspace de contratos verifica esa coherencia.
 */
object RaizConstants {
    const val USDC_DECIMALS = 7
    const val USDC_STROOPS_PER_UNIT = 10_000_000L          // 1 USDC = 10^7 stroops

    const val POINTS_PER_STROOP_DIVISOR = 100_000L         // 1 punto por 0.01 USDC
    const val DEFAULT_TIP_BPS = 200                        // 2% por defecto
    const val PROTOCOL_FEE_BPS = 50                        // 0.5% fee del protocolo
    const val QUORUM_PCT = 30                              // 30% para quórum
    const val BPS_DENOMINATOR = 10_000

    // Red Stellar
    const val TESTNET_NETWORK_PASSPHRASE = "Test SDF Network ; September 2015"
    const val PUBLIC_NETWORK_PASSPHRASE = "Public Global Stellar Network ; September 2015"

    const val TESTNET_HORIZON_URL = "https://horizon-testnet.stellar.org"
    const val TESTNET_SOROBAN_RPC_URL = "https://soroban-testnet.stellar.org"

    /**
     * Pool USDC de Blend v2 "TestnetV2" (reserva USDC en índice 3). Fuente:
     * `blend-capital/blend-utils` `testnet.contracts.json`. Fallback SOLO si
     * `deployments.json` no trae la clave `blend_pool` — nunca la única
     * fuente; [com.raiz.app.data.stellar.BlendClient] prioriza siempre el deployment.
     */
    const val BLEND_POOL_TESTNET = "CCEBVDYM32YNYCVNRXQKDFFPISJJCV557CDZEIRBEE4NCV4KHPQ44HGF"

    /**
     * Anchor de prueba del SDF (D3 del SOW: SEP-1/10/24, ver `data/anchor/AnchorClient.kt`).
     * El USDC de este anchor es DISTINTO al USDC de Blend que usa el fondo del barrio — la UI
     * lo rotula siempre "USDC · anchor de prueba" y nunca se suma al balance del pool.
     */
    const val ANCHOR_HOME_DOMAIN = "testanchor.stellar.org"
    const val ANCHOR_USDC_CODE = "USDC"

    /**
     * Issuer del USDC del testanchor (`stellar.toml` `[[CURRENCIES]]`, verificado 2026-09-27).
     * Fallback SOLO si el `stellar.toml` no trae el issuer o falla el fetch; [AnchorInfo]
     * prioriza siempre el issuer publicado por el propio anchor.
     */
    const val ANCHOR_USDC_ISSUER = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
}

/** stroops (Long) -> USDC (Double). 10^7 stroops = 1 USDC. */
fun Long.toUsdc(): Double = this / RaizConstants.USDC_STROOPS_PER_UNIT.toDouble()

/** USDC (Double) -> stroops (Long). */
fun Double.toStroops(): Long = (this * RaizConstants.USDC_STROOPS_PER_UNIT).toLong()

/** Puntos ganados por un tip dado (en stroops). 1 punto por cada 0.01 USDC. */
fun pointsForTip(tipStroops: Long): Long = tipStroops / RaizConstants.POINTS_PER_STROOP_DIVISOR

/** Formatea stroops como string USDC legible: 42_840_000 -> "4.284 USDC". */
fun Long.formatUsdc(): String {
    // Locale.US fijo: con el locale del dispositivo (es-CO usa coma) "5,000" quedaba "5, USDC".
    val trimmed = String.format(java.util.Locale.US, "%.3f", this.toUsdc()).trimEnd('0').trimEnd('.')
    return "$trimmed USDC"
}
