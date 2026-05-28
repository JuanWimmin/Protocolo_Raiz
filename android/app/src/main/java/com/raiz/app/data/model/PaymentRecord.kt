package com.raiz.app.data.model

/**
 * Pago Horizon (operation type=`payment`) ya parseado a tipos Kotlin.
 *
 * Refleja una operación de transferencia clásica de Stellar — útil para el
 * historial del turista (qué pagó y a quién). Los movimientos internos del
 * contrato Soroban (tip del pool, fee del protocolo) aparecen como pagos
 * separados porque cada `usdc.transfer` los emite como operación clásica.
 */
data class PaymentRecord(
    val txHash: String,
    val from: String,             // G... origen
    val to: String,               // G... destino
    val amountStroops: Long,
    val assetCode: String,        // "USDC", "XLM", etc.
    val createdAt: String,        // ISO 8601 ("2026-05-28T15:00:00Z")
    val isOutgoing: Boolean,      // true si el account observado es el `from`
) {
    val amountUsdc: Double get() = amountStroops.toUsdc()
}
