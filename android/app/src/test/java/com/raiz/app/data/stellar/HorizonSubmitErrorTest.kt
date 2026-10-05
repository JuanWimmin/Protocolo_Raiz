package com.raiz.app.data.stellar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM puros del clasificador de errores de `POST /transactions` de Horizon
 * ([DepositPlan.isDefinitiveSubmitRejection]).
 *
 * Regla: solo un body con `result_codes` es un RECHAZO DEFINITIVO (la red evaluó la
 * transacción). Todo lo demás — sin body, `504 Timeout`, `503`, `429` — es AMBIGUO: la
 * transacción puede entrar igualmente y hay que consultarla por hash antes de declarar fallo.
 * Antes, un 504 (que también trae body) se reportaba como "Horizon rechazó la conversión".
 */
class HorizonSubmitErrorTest {

    @Test
    fun `tx_failed con result_codes es un rechazo definitivo`() {
        assertTrue(DepositPlan.isDefinitiveSubmitRejection(TX_FAILED_BODY))
    }

    @Test
    fun `tx_bad_seq en una sola linea es un rechazo definitivo`() {
        val body = """{"type":"https://stellar.org/horizon-errors/transaction_failed","status":400,""" +
            """"extras":{"result_codes":{"transaction":"tx_bad_seq"}}}"""
        assertTrue(DepositPlan.isDefinitiveSubmitRejection(body))
    }

    @Test
    fun `un 504 de Horizon trae body pero es ambiguo`() {
        assertFalse(DepositPlan.isDefinitiveSubmitRejection(TIMEOUT_BODY))
    }

    @Test
    fun `un 503 o un 429 son ambiguos`() {
        val staleHistory = """{"type":"https://stellar.org/horizon-errors/stale_history","title":"Historical DB Is Too Stale","status":503}"""
        val rateLimited = """{"type":"https://stellar.org/horizon-errors/rate_limit_exceeded","title":"Rate Limit Exceeded","status":429}"""
        assertFalse(DepositPlan.isDefinitiveSubmitRejection(staleHistory))
        assertFalse(DepositPlan.isDefinitiveSubmitRejection(rateLimited))
    }

    @Test
    fun `sin body es ambiguo`() {
        assertFalse(DepositPlan.isDefinitiveSubmitRejection(null))
        assertFalse(DepositPlan.isDefinitiveSubmitRejection(""))
        assertFalse(DepositPlan.isDefinitiveSubmitRejection("   "))
    }

    @Test
    fun `una pagina de error que no es JSON es ambigua`() {
        assertFalse(DepositPlan.isDefinitiveSubmitRejection("<html><body>502 Bad Gateway</body></html>"))
    }

    @Test
    fun `resultCodesFragment extrae solo los codigos, nunca el XDR`() {
        val fragment = DepositPlan.resultCodesFragment(TX_FAILED_BODY)

        assertEquals(
            """"result_codes": {
      "transaction": "tx_failed",
      "operations": ["op_under_dest_min"]
    }""",
            fragment,
        )
        assertFalse(fragment!!.contains("envelope_xdr"))
        assertFalse(fragment.contains("AAAAAgAAAAA"))
    }

    @Test
    fun `resultCodesFragment es null si no hay codigos`() {
        assertNull(DepositPlan.resultCodesFragment(TIMEOUT_BODY))
        assertNull(DepositPlan.resultCodesFragment(null))
        assertNull(DepositPlan.resultCodesFragment(""))
    }

    private companion object {
        /** Forma real de un `transaction_failed` (400) de Horizon; los XDR van recortados. */
        val TX_FAILED_BODY = """
{
  "type": "https://stellar.org/horizon-errors/transaction_failed",
  "title": "Transaction Failed",
  "status": 400,
  "detail": "The transaction failed when submitted to the stellar network. The `extras.result_codes` field on this response contains further details.",
  "extras": {
    "envelope_xdr": "AAAAAgAAAAA...",
    "result_codes": {
      "transaction": "tx_failed",
      "operations": ["op_under_dest_min"]
    },
    "result_xdr": "AAAAAAAAAGT/////AAAAAQ..."
  }
}
""".trim()

        /** Forma real de un `timeout` (504) de Horizon: la transacción puede entrar igual. */
        val TIMEOUT_BODY = """
{
  "type": "https://stellar.org/horizon-errors/timeout",
  "title": "Timeout",
  "status": 504,
  "detail": "Your request timed out before completing.  Please try your request again. If you are submitting a transaction make sure you are sending the same exact transaction (with the same sequence number).",
  "extras": {
    "envelope_xdr": "AAAAAgAAAAA...",
    "hash": "23e926f4a24981e4e2f524221b26408273a508722931422b9c62d83be83d3658"
  }
}
""".trim()
    }
}
