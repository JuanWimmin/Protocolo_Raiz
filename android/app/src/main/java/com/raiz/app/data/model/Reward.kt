package com.raiz.app.data.model

/**
 * Espejo del struct Reward del contrato Rewards.
 *
 * Ver `docs/raiz_v2_spec_contratos.md` §Contrato 4. Cada barrio puede tener
 * varios rewards (artesanías) que el turista canjea con sus puntos
 * (1 punto = 0.01 USDC de tip generado).
 */
data class Reward(
    val id: Long,
    val barrioId: String,
    val name: String,             // "Mochila wayuu artesanal"
    val artisan: String,          // G... del artesano que entrega
    val pointsCost: Long,
    val stock: Int,
    val imageRef: String,         // URL para MVP, ipfs:// en v2
) {
    val outOfStock: Boolean get() = stock <= 0

    /** ¿El turista puede canjearlo con sus puntos actuales? */
    fun canAffordWith(points: Long): Boolean = !outOfStock && points >= pointsCost

    /** Puntos que faltan para alcanzar este reward (≥ 0). */
    fun shortfallFrom(points: Long): Long = (pointsCost - points).coerceAtLeast(0L)

    /** Fracción 0..1 para barra de progreso "tus puntos / costo". */
    fun progressFrom(points: Long): Float =
        if (pointsCost <= 0) 1f else (points.toFloat() / pointsCost).coerceIn(0f, 1f)
}

/**
 * Espejo de Redemption del contrato Rewards. Se crea cuando el turista
 * canjea un reward — y el artesano debe marcar `claimed=true` cuando entregue
 * el premio físico.
 */
data class Redemption(
    val id: Long,
    val tourist: String,
    val rewardId: Long,
    val redeemedAt: Long,
    val claimed: Boolean,
)
