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
    // Campos opcionales — null si no están en deployments.json (backward-compat)
    @SerialName("defindex_vault") val defindexVault: String? = null,
    @SerialName("defindex_usdc") val defindexUsdc: String? = null,
    /**
     * Emisor (G...) del USDC para operaciones clásicas en Horizon (trustline,
     * balance, faucet). Si es null, se usa `admin` (deploy previo, USDC propio).
     * Tras el re-deploy a DeFindex apunta al USDC de Blend.
     */
    @SerialName("usdc_issuer") val usdcIssuer: String? = null,
)
