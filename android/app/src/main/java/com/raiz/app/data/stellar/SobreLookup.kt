package com.raiz.app.data.stellar

import com.raiz.app.data.model.PaymentRecord

/**
 * Qué pagos del Pool se revisan al buscar la tx que liquidó un sobre (puro, se prueba en JVM). Cada candidato cuesta
 * un `getTransaction`, así que hay un tope; pero si el tope deja pagos SIN revisar, "no lo encontré" ya no es una
 * respuesta firme y quien busca debe tratarlo como "no se pudo comprobar" ([Candidates.truncated]).
 */
object SobreLookup {
    /** Pagos que se revisan como máximo por búsqueda. */
    const val MAX_CANDIDATES = 24

    /** [payments] a revisar (más recientes primero) y si el tope dejó otros fuera. */
    class Candidates(val payments: List<PaymentRecord>, val truncated: Boolean)

    /** Pagos del [payer] a [merchant]: ahí está la tx cuya auth es el sobre. */
    fun hashCandidates(events: List<PaymentRecord>, payer: String, merchant: String): Candidates {
        val all = events
            .filter { it.from == payer && it.to == merchant }
            .sortedByDescending { it.createdAt }
        return Candidates(all.take(MAX_CANDIDATES), truncated = all.size > MAX_CANDIDATES)
    }

    /**
     * Pagos SALIENTES del [payer]: los dirigidos a [merchant] primero y luego el resto, porque el nonce de un sobre
     * reutilizado suele gastarse pagando a OTRO comercio.
     */
    fun nonceCandidates(events: List<PaymentRecord>, payer: String, merchant: String): Candidates {
        val all = events
            .filter { it.from == payer }
            .sortedWith(compareByDescending<PaymentRecord> { it.to == merchant }.thenByDescending { it.createdAt })
        return Candidates(all.take(MAX_CANDIDATES), truncated = all.size > MAX_CANDIDATES)
    }
}
