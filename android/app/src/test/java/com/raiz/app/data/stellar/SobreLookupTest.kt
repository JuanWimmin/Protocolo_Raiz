package com.raiz.app.data.stellar

import com.raiz.app.data.model.PaymentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué pagos del Pool se revisan al buscar la tx que liquidó un sobre ([SobreLookup]). */
class SobreLookupTest {

    private val payer = "GPAYER"
    private val merchant = "GMERCHANT"

    private fun pago(hash: String, from: String = payer, to: String = merchant, createdAt: String) = PaymentRecord(
        txHash = hash,
        from = from,
        to = to,
        amountStroops = 25_500_000L,
        assetCode = "USDC",
        createdAt = createdAt,
        isOutgoing = from == payer,
    )

    private fun dia(n: Int) = "2026-10-%02dT12:00:00Z".format(n)

    private fun minuto(n: Int) = "2026-10-01T00:%02d:00Z".format(n)

    @Test
    fun `hashCandidates toma solo los pagos del pagador a ese comercio, los mas recientes primero`() {
        val events = listOf(
            pago("viejo", createdAt = dia(1)),
            pago("otro-comercio", to = "GOTRO", createdAt = dia(5)),
            pago("otro-pagador", from = "GOTROPAGADOR", createdAt = dia(6)),
            pago("nuevo", createdAt = dia(9)),
            pago("medio", createdAt = dia(4)),
        )
        val pick = SobreLookup.hashCandidates(events, payer, merchant)
        assertEquals(listOf("nuevo", "medio", "viejo"), pick.payments.map { it.txHash })
        assertFalse(pick.truncated)
    }

    @Test
    fun `hashCandidates avisa cuando el tope deja pagos sin revisar`() {
        val exacto = List(SobreLookup.MAX_CANDIDATES) { pago("h$it", createdAt = minuto(it)) }
        assertFalse(SobreLookup.hashCandidates(exacto, payer, merchant).truncated)
        assertEquals(SobreLookup.MAX_CANDIDATES, SobreLookup.hashCandidates(exacto, payer, merchant).payments.size)

        val demasiados = exacto + pago("hextra", createdAt = dia(2))
        val pick = SobreLookup.hashCandidates(demasiados, payer, merchant)
        assertTrue(pick.truncated)
        assertEquals(SobreLookup.MAX_CANDIDATES, pick.payments.size)
        // Se quedan los MÁS recientes: el pago extra (el más nuevo) entra y el más viejo queda fuera.
        assertEquals("hextra", pick.payments.first().txHash)
        assertFalse(pick.payments.any { it.txHash == "h0" })
    }

    @Test
    fun `nonceCandidates pone primero los pagos a ese comercio y luego el resto del pagador`() {
        val events = listOf(
            pago("a-otro-nuevo", to = "GOTRO", createdAt = dia(9)),
            pago("al-comercio-viejo", createdAt = dia(1)),
            pago("al-comercio-nuevo", createdAt = dia(5)),
            pago("a-otro-viejo", to = "GOTRO2", createdAt = dia(2)),
            pago("de-otro-pagador", from = "GOTROPAGADOR", createdAt = dia(8)),
        )
        val pick = SobreLookup.nonceCandidates(events, payer, merchant)
        assertEquals(
            listOf("al-comercio-nuevo", "al-comercio-viejo", "a-otro-nuevo", "a-otro-viejo"),
            pick.payments.map { it.txHash },
        )
        assertFalse(pick.truncated)
    }

    @Test
    fun `nonceCandidates tambien respeta el tope`() {
        val muchos = List(SobreLookup.MAX_CANDIDATES + 3) { pago("h$it", to = "GOTRO$it", createdAt = minuto(it)) }
        val pick = SobreLookup.nonceCandidates(muchos, payer, merchant)
        assertTrue(pick.truncated)
        assertEquals(SobreLookup.MAX_CANDIDATES, pick.payments.size)
    }
}
