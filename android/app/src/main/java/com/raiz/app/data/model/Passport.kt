package com.raiz.app.data.model

/**
 * Datos del "RAÍZ Passport" del turista — agregado de su actividad on-chain
 * que se renderiza en la WalletScreen.
 *
 * Derivado del paymentHistory + listMerchants de los barrios conocidos:
 *  - aportadoAlBarrioStroops: suma de tips enviados al contrato Pool
 *    (transacciones donde `to == deployments.pool`).
 *  - transaccionesLocales: número de tx unique donde el turista pagó a algún
 *    merchant registrado.
 *  - barriosVisitados: set de barrioIds con al menos un pago a merchant
 *    del barrio.
 */
data class PassportData(
    val nombre: String,                       // "Viajer@ responsable" o nombre del usuario
    val ubicacion: String,                    // "Colombia 2026" placeholder
    val nivel: PassportLevel,
    val saldoStroops: Long,                   // se muestra como "pts" en el header
    val ptsParaSiguienteNivel: Long,          // cuánto le falta al siguiente nivel
    val aportadoAlBarrioStroops: Long,
    val transaccionesLocales: Int,
    val barriosVisitados: Set<String>,        // barrioIds donde sí pagó
)

/**
 * Niveles del passport, en orden ascendente.
 * Cada nivel tiene un umbral mínimo en stroops (saldo USDC requerido).
 * Los thresholds son arbitrarios; ajustamos cuando definamos la gamificación.
 */
enum class PassportLevel(val label: String, val thresholdStroops: Long) {
    BROTE("Brote", 0L),
    SEMILLA("Semilla", 10_000_000_000L),       // ≥ 1000 USDC
    RAIZ("Raíz", 50_000_000_000L),             // ≥ 5000 USDC
    BOSQUE("Bosque", 200_000_000_000L);        // ≥ 20000 USDC

    /** Pts (stroops) que faltan para subir al siguiente nivel. 0 si ya estás en el top. */
    fun ptsToNext(currentStroops: Long): Long {
        val next = next() ?: return 0L
        return (next.thresholdStroops - currentStroops).coerceAtLeast(0L)
    }

    fun next(): PassportLevel? {
        val ordered = entries
        val idx = ordered.indexOf(this)
        return ordered.getOrNull(idx + 1)
    }

    companion object {
        /** Devuelve el nivel correspondiente a un saldo dado. */
        fun fromStroops(stroops: Long): PassportLevel {
            var current = BROTE
            for (lvl in entries) {
                if (stroops >= lvl.thresholdStroops) current = lvl
            }
            return current
        }
    }
}
