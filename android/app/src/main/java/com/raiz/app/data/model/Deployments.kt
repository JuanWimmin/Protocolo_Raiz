package com.raiz.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Espejo de `deployments.json` (raíz del repo, copiado a assets/ en el build).
 * Generado por scripts/deploy_testnet.sh tras desplegar los 4 contratos.
 */
@Serializable
data class Deployments(
    val network: String,
    val admin: String,
    @SerialName("admin_identity") val adminIdentity: String? = null,
    @SerialName("usdc_sac") val usdcSac: String,
    val pool: String,
    val governance: String,
    val treasury: String,
    val rewards: String,
    @SerialName("protocol_fee_bps") val protocolFeeBps: Int,
    @SerialName("deployed_at") val deployedAt: String,
    // Campos opcionales — null si no están en deployments.json (backward-compat).
    // `ignoreUnknownKeys = true` en DeploymentsLoader hace que claves no
    // mapeadas aquí (p. ej. las viejas `defindex_vault`/`defindex_usdc` que
    // puedan seguir presentes en el asset hasta el re-deploy F1) se ignoren
    // sin romper el parseo.
    /**
     * Contrato `yield_adapter` propio de RAÍZ (F1: Blend v2 directo). Null
     * hasta el re-deploy F1 — mientras tanto [com.raiz.app.data.stellar.BlendClient]
     * degrada con gracia (APY del adapter no disponible, resto de la
     * pantalla Yield sigue funcionando con los datos del pool Blend).
     */
    @SerialName("yield_adapter") val yieldAdapter: String? = null,
    /**
     * Pool USDC de Blend v2 (TestnetV2) contra el que lee [com.raiz.app.data.stellar.BlendClient]
     * directamente (TVL/utilización del pool completo). Si es null, se usa
     * el fallback fijo `RaizConstants.BLEND_POOL_TESTNET` — Blend ya vive en
     * testnet independientemente del deploy de RAÍZ.
     */
    @SerialName("blend_pool") val blendPool: String? = null,
    /**
     * Emisor (G...) del USDC para operaciones clásicas en Horizon (trustline,
     * balance, faucet). Si es null, se usa `admin` (deploy previo, USDC propio).
     * Es el USDC de Blend (el mismo que ya custodia el fondo tras el re-deploy
     * a Camino A).
     */
    @SerialName("usdc_issuer") val usdcIssuer: String? = null,
)
