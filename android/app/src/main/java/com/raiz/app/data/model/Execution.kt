package com.raiz.app.data.model

/**
 * Espejo del struct Execution del contrato Treasury.
 *
 * Cada Execution registra una ejecución exitosa de una propuesta aprobada:
 * el dinero del pool del barrio se transfiere al `recipient` y queda este
 * registro auditable on-chain. La app lo lee vía Treasury.get_execution_log
 * para mostrar en el Dashboard de transparencia.
 *
 * - `txHash` es el sha256 determinístico que el contrato calcula a partir
 *   de (proposal_id, barrio_id, executed_at). NO es el txHash de Stellar
 *   (ese solo lo conoce el cliente off-chain). Sirve como referencia
 *   auditable única.
 */
data class Execution(
    val proposalId: Long,
    val barrioId: String,
    val amountStroops: Long,
    val recipient: String,
    val executedAt: Long,    // unix timestamp
    val txHash: String,      // hex de 64 chars
) {
    val amountUsdc: Double get() = amountStroops.toUsdc()
}
