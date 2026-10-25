package com.raiz.app.data.deferred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transiciones de la máquina de estados del sobre (spec §6) y codec del almacén. */
class DeferredPolicyTest {

    private fun record(id: String, status: DeferredStatus, exp: Long, payer: String = "GPAYER") = DeferredRecord(
        id = id,
        sobreQr = "raiz:sobre:1:e30",
        authXdr = "AAAA",
        payer = payer,
        merchantAddress = "GMERCHANT",
        merchantName = "Cafe Don Aurelio",
        merchantVerified = true,
        amountStroops = 25_000_000L,
        tipBps = 200,
        feeBps = 50,
        exp = exp,
        l0 = 6_389_772L,
        t0 = 1_761_393_600L,
        signedAtMs = 1_000L,
        validUntilMs = 2_000L,
        status = status,
    )

    @Test
    fun `refresh marca EXPIRADO solo a los pendientes con exp alcanzada`() {
        val list = listOf(
            record("a", DeferredStatus.FIRMADO, exp = 100),
            record("b", DeferredStatus.ENTREGADO, exp = 100),
            record("c", DeferredStatus.FIRMADO, exp = 101),
            record("d", DeferredStatus.CONFIRMADO, exp = 50),
            record("e", DeferredStatus.RECHAZADO, exp = 50),
        )
        val out = DeferredPolicy.refresh(list, estimate = 100)
        assertEquals(DeferredStatus.EXPIRADO, out[0].status)
        assertEquals(DeferredPolicy.MOTIVO_EXPIRADO, out[0].motivo)
        assertEquals(DeferredStatus.EXPIRADO, out[1].status)
        assertEquals(DeferredStatus.FIRMADO, out[2].status)
        assertEquals(DeferredStatus.CONFIRMADO, out[3].status)
        assertEquals(DeferredStatus.RECHAZADO, out[4].status)
        assertEquals(list, DeferredPolicy.refresh(list, estimate = null))
    }

    @Test
    fun `transiciones entregado, confirmado, expirado por la red y rechazado`() {
        val r = record("a", DeferredStatus.FIRMADO, exp = 100)
        val delivered = DeferredPolicy.markDelivered(r)
        assertEquals(DeferredStatus.ENTREGADO, delivered.status)
        assertEquals(delivered, DeferredPolicy.markDelivered(delivered))

        val confirmed = DeferredPolicy.markConfirmed(delivered, "ab".repeat(32))
        assertEquals(DeferredStatus.CONFIRMADO, confirmed.status)
        assertEquals("ab".repeat(32), confirmed.txHash)
        // Prueba por nonce sin hash todavía: CONFIRMADO con hash nulo; un hash posterior lo completa.
        val sinHash = DeferredPolicy.markConfirmed(delivered, null)
        assertEquals(DeferredStatus.CONFIRMADO, sinHash.status)
        assertNull(sinHash.txHash)
        assertEquals("cd".repeat(32), DeferredPolicy.markConfirmed(sinHash, "cd".repeat(32)).txHash)
        // Un hash confirmado manda sobre un EXPIRADO local.
        assertEquals(DeferredStatus.CONFIRMADO, DeferredPolicy.markConfirmed(r.copy(status = DeferredStatus.EXPIRADO), "cd".repeat(32)).status)
        // Expirado según la red.
        val expiradoRed = DeferredPolicy.markExpiredByNetwork(delivered)
        assertEquals(DeferredStatus.EXPIRADO, expiradoRed.status)
        assertEquals(DeferredPolicy.MOTIVO_EXPIRADO_RED, expiradoRed.motivo)
        assertEquals(confirmed, DeferredPolicy.markExpiredByNetwork(confirmed))
        // RECHAZADO es terminal.
        val rejected = DeferredPolicy.markRejected(delivered, "nonce usado")
        assertEquals(DeferredStatus.RECHAZADO, rejected.status)
        assertEquals(rejected, DeferredPolicy.markConfirmed(rejected, "ef".repeat(32)))
        assertEquals(confirmed, DeferredPolicy.markRejected(confirmed, "tarde"))
    }

    @Test
    fun `el codec hace round trip y lee basura como lista vacia`() {
        val list = listOf(record("1", DeferredStatus.FIRMADO, 10), record("-7", DeferredStatus.CONFIRMADO, 10).copy(txHash = "ff".repeat(32)))
        val encoded = DeferredRecordCodec.encode(list)
        assertEquals(list, DeferredRecordCodec.decode(encoded))
        assertTrue(DeferredRecordCodec.decode(null).isEmpty())
        assertTrue(DeferredRecordCodec.decode("{basura").isEmpty())
        assertTrue(DeferredRecordCodec.decode("[{\"id\":1}]").isEmpty())
    }

    @Test
    fun `derivados del registro`() {
        val r = record("a", DeferredStatus.FIRMADO, exp = 100)
        assertEquals(500_000L, r.tipStroops)
        assertEquals(125_000L, r.feeStroops)
        assertEquals(25_500_000L, r.totalStroops)
        assertTrue(r.pending)
    }
}
