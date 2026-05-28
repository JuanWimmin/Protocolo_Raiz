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
)
