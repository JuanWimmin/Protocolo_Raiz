package com.raiz.app.data.model

/**
 * Lo que se muestra al confirmar un pago en PayScreen.
 *
 * - `baseAmountStroops`: lo que el turista quiere pagar (sin tip).
 * - `tipBps`: 0 si tip desactivado, 200 (2%) si activado.
 *
 * Los derived (tipStroops, totalStroops, pointsToEarn) se calculan acá para
 * que la UI no haga aritmética y todos los lugares usen la misma fórmula.
 */
data class PaymentPreview(
    val merchant: Merchant,
    val baseAmountStroops: Long,
    val tipBps: Int,
) {
    val tipStroops: Long get() = baseAmountStroops * tipBps / RaizConstants.BPS_DENOMINATOR
    val totalStroops: Long get() = baseAmountStroops + tipStroops
    val pointsToEarn: Long get() = tipStroops / RaizConstants.POINTS_PER_STROOP_DIVISOR

    val baseAmountUsdc: Double get() = baseAmountStroops.toUsdc()
    val tipUsdc: Double get() = tipStroops.toUsdc()
    val totalUsdc: Double get() = totalStroops.toUsdc()
}
