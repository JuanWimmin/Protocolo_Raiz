package com.raiz.app.data.model

/**
 * Datos del "RAÍZ Passport" del turista — agregado de su actividad on-chain
 * que se renderiza en la WalletScreen.
 *
 * Derivado del paymentHistory + listMerchants + Rewards.get_points:
 *  - aportadoAlBarrioStroops: suma de tips enviados al contrato Pool
 *    (transacciones donde `to == deployments.pool`).
 *  - transaccionesLocales: número de tx unique donde el turista pagó a algún
 *    merchant registrado.
 *  - barriosVisitados: set de barrioIds con al menos un pago a merchant
 *    del barrio.
 *  - points: SALDO DE PUNTOS del contrato Rewards. Es la ÚNICA medida que
 *    llamamos "puntos" en toda la app — el saldo USDC se muestra como USDC
 *    en el BalanceCard, no aquí.
 */
data class PassportData(
    val nombre: String,
    val ubicacion: String,
    val nivel: PassportLevel,
    val points: Long,                         // del contrato Rewards
    val ptsParaSiguienteNivel: Long,
    val aportadoAlBarrioStroops: Long,
    val transaccionesLocales: Int,
    val barriosVisitados: Set<String>,
)

/**
 * Niveles del passport — basados en PUNTOS del contrato Rewards.
 *
 * Como 1 punto = 0.01 USDC de tip, los thresholds están calibrados para
 * tener sentido económico: subir de nivel implica haber generado un cierto
 * volumen de tips (y por tanto haber aportado al fondo del barrio).
 *
 *   BROTE   ≥   0 pts → inicio
 *   SEMILLA ≥ 100 pts → ~1 USDC en tips (≈ 1 pago razonable con tip)
 *   RAIZ    ≥ 500 pts → ~5 USDC en tips
 *   BOSQUE  ≥ 2000 pts → ~20 USDC en tips (turista frecuente)
 */
enum class PassportLevel(val label: String, val thresholdPoints: Long) {
    BROTE("Brote", 0L),
    SEMILLA("Semilla", 100L),
    RAIZ("Raíz", 500L),
    BOSQUE("Bosque", 2_000L);

    /** Pts que faltan para subir al siguiente nivel. 0 si ya estás en el top. */
    fun ptsToNext(currentPoints: Long): Long {
        val next = next() ?: return 0L
        return (next.thresholdPoints - currentPoints).coerceAtLeast(0L)
    }

    fun next(): PassportLevel? {
        val ordered = entries
        val idx = ordered.indexOf(this)
        return ordered.getOrNull(idx + 1)
    }

    companion object {
        /** Devuelve el nivel correspondiente a una cantidad de puntos. */
        fun fromPoints(points: Long): PassportLevel {
            var current = BROTE
            for (lvl in entries) {
                if (points >= lvl.thresholdPoints) current = lvl
            }
            return current
        }
    }
}
