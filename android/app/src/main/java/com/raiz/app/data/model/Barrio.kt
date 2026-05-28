package com.raiz.app.data.model

/**
 * Espejo de BarrioData en el contrato Pool.
 * Ver `docs/raiz_v2_spec_contratos.md` §Contrato 1.
 */
data class Barrio(
    val id: String,                  // hex 64 chars de BytesN<32>
    val name: String,
    val poolBalanceStroops: Long,
    val totalCollectedStroops: Long,
    val txCount: Long,
    val uniqueTourists: Int,
    val treasuryContract: String,    // Address C...
) {
    val poolBalanceUsdc: Double get() = poolBalanceStroops.toUsdc()
    val totalCollectedUsdc: Double get() = totalCollectedStroops.toUsdc()
}
