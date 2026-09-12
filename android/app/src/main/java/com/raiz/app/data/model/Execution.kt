package com.raiz.app.data.model

/**
 * Espejo del struct Execution del contrato Treasury.
 *
 * Cada Execution registra una ejecución exitosa de una propuesta aprobada:
 * el dinero del pool del barrio se transfiere al `recipient` y queda este
 * registro auditable on-chain. La app lo lee vía Treasury.get_execution_log
 * para mostrar en el Dashboard de transparencia.
 *
 * - `txHash` es el **ID de auditoría**: sha256 determinístico que el contrato
 *   calcula a partir de (proposal_id, barrio_id, executed_at). NO es el hash
 *   de la transacción de Stellar (el contrato no puede conocerlo) y NO es
 *   buscable en Stellar Expert. Nunca mostrarlo como si fuera un tx hash.
 * - `realTxHash` es el hash REAL de la transacción que ejecutó la propuesta.
 *   Viene de fuera del contrato: del evento `execution` que el Treasury emite
 *   (RPC `getEvents`, campo `txHash`) o del `sendTransaction` cuando fue esta
 *   misma app la que la disparó. Es `null` cuando la ejecución es más vieja
 *   que la ventana de retención del RPC y nadie la capturó → "histórica".
 */
data class Execution(
    val proposalId: Long,
    val barrioId: String,
    val amountStroops: Long,
    val recipient: String,
    val executedAt: Long,    // unix timestamp
    val txHash: String,      // ID de auditoría on-chain (hex de 64 chars), NO tx hash
    val realTxHash: String? = null, // hash real de la tx de Stellar (evento o sendTransaction)
) {
    val amountUsdc: Double get() = amountStroops.toUsdc()

    /** true si conocemos el hash real → la UI puede enlazar a Stellar Expert. */
    val verified: Boolean get() = !realTxHash.isNullOrBlank()
}

/**
 * Evento `execution` del Treasury tal como lo devuelve el RPC (`getEvents`):
 *   topic = (Symbol("execution"), BytesN<32> barrio_id)
 *   value = (proposal_id u64, amount i128, recipient Address)
 * más el `txHash` de la transacción que lo emitió — la pieza que el contrato
 * no puede guardar y que necesita el dashboard (D2 del SOW).
 */
data class ExecutionEvent(
    val proposalId: Long,
    val barrioId: String,
    val amountStroops: Long,
    val recipient: String,
    val txHash: String,
    val ledger: Long,
    val ledgerClosedAt: String,
)
