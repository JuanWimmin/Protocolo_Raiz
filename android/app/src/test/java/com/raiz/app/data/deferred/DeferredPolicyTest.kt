package com.raiz.app.data.deferred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // RECHAZADO ya no es terminal frente a la prueba on-chain (D3): un hash con esta auth manda.
        val rejected = DeferredPolicy.markRejected(delivered, "nonce usado")
        assertEquals(DeferredStatus.RECHAZADO, rejected.status)
        val reconfirmed = DeferredPolicy.markConfirmed(rejected, "ef".repeat(32))
        assertEquals(DeferredStatus.CONFIRMADO, reconfirmed.status)
        assertEquals("ef".repeat(32), reconfirmed.txHash)
        assertNull(reconfirmed.motivo)
        // Pero un CONFIRMADO no se vuelve RECHAZADO ni EXPIRADO.
        assertEquals(confirmed, DeferredPolicy.markRejected(confirmed, "tarde"))
        assertEquals(confirmed, DeferredPolicy.markExpiredByNetwork(confirmed))
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

    // ── Campos del envío automático (SobreSettler) ───────────────────────────

    @Test
    fun `un registro guardado SIN los campos nuevos se decodifica con valores por defecto`() {
        // Tal como lo dejó la app 0.4.x en `records_v2`: con y sin las claves nulas.
        val conNulos = """
            [{"id":"-7","sobreQr":"raiz:sobre:1:e30","authXdr":"AAAA","payer":"GPAYER",
            "merchantAddress":"GMERCHANT","merchantName":"Cafe Don Aurelio","merchantVerified":true,
            "amountStroops":25000000,"tipBps":200,"feeBps":50,"exp":6401292,"l0":6389772,"t0":1761393600,
            "signedAtMs":1000,"validUntilMs":2000,"status":"ENTREGADO","txHash":null,"motivo":null}]
        """.trimIndent().replace("\n", "")
        val sinNulos = conNulos.replace(",\"txHash\":null,\"motivo\":null", "")
        assertFalse(sinNulos.contains("txHash"))
        for (raw in listOf(conNulos, sinNulos)) {
            val list = DeferredRecordCodec.decode(raw)
            assertEquals(1, list.size)
            val r = list.single()
            assertEquals("-7", r.id)
            assertEquals(DeferredStatus.ENTREGADO, r.status)
            assertEquals(0, r.sendAttempts)
            assertNull(r.lastSendMotivo)
            assertEquals(0L, r.lastAttemptMs)
            assertNull(r.inFlightHash)
            assertEquals(0L, r.inFlightValidUntilSec)
            assertEquals(0L, r.lastQuotedFeeStroops)
        }
    }

    @Test
    fun `el codec conserva los campos del envio automatico`() {
        val r = record("1", DeferredStatus.ENTREGADO, exp = 10).copy(
            sendAttempts = 2,
            lastSendMotivo = "Fondos insuficientes",
            lastAttemptMs = 1_700_000_000_000L,
            inFlightHash = "ab".repeat(32),
            inFlightValidUntilSec = 1_761_400_000L,
            lastQuotedFeeStroops = 300_000_000L,
        )
        assertEquals(listOf(r), DeferredRecordCodec.decode(DeferredRecordCodec.encode(listOf(r))))
    }

    @Test
    fun `shouldAutoSend exige pendiente, sin tx en vuelo, tope, margen de ledgers y backoff`() {
        val now = 1_000_000_000L
        val base = record("a", DeferredStatus.ENTREGADO, exp = 1_000)
        assertTrue(DeferredPolicy.shouldAutoSend(base, ledgerHint = null, nowMs = now))
        assertTrue(DeferredPolicy.shouldAutoSend(base.copy(status = DeferredStatus.FIRMADO), ledgerHint = 900, nowMs = now))

        // No pendientes.
        for (closed in listOf(DeferredStatus.CONFIRMADO, DeferredStatus.EXPIRADO, DeferredStatus.RECHAZADO)) {
            assertFalse(DeferredPolicy.shouldAutoSend(base.copy(status = closed), null, now))
        }
        // Con una tx en vuelo NO se construye otra (se reanuda por hash).
        assertFalse(DeferredPolicy.shouldAutoSend(base.copy(inFlightHash = "ab".repeat(32)), null, now))
        // Tope de envíos que llegaron a la red.
        assertTrue(DeferredPolicy.shouldAutoSend(base.copy(sendAttempts = DeferredPolicy.MAX_SEND_ATTEMPTS - 1), null, now))
        assertFalse(DeferredPolicy.shouldAutoSend(base.copy(sendAttempts = DeferredPolicy.MAX_SEND_ATTEMPTS), null, now))
        // exp − ledger ≥ 3 cuando se conoce el ledger.
        assertTrue(DeferredPolicy.shouldAutoSend(base, ledgerHint = 997, nowMs = now))
        assertFalse(DeferredPolicy.shouldAutoSend(base, ledgerHint = 998, nowMs = now))
        assertFalse(DeferredPolicy.shouldAutoSend(base, ledgerHint = 1_000, nowMs = now))
        assertFalse(DeferredPolicy.shouldAutoSend(base, ledgerHint = 1_500, nowMs = now))
        // Backoff de 5 min desde el último intento.
        val reciente = base.copy(lastAttemptMs = now - 1_000L)
        assertFalse(DeferredPolicy.shouldAutoSend(reciente, null, now))
        assertFalse(DeferredPolicy.shouldAutoSend(base.copy(lastAttemptMs = now - DeferredPolicy.RETRY_BACKOFF_MS + 1), null, now))
        assertTrue(DeferredPolicy.shouldAutoSend(base.copy(lastAttemptMs = now - DeferredPolicy.RETRY_BACKOFF_MS), null, now))
        assertEquals(5 * 60_000L, DeferredPolicy.RETRY_BACKOFF_MS)
        assertEquals(3, DeferredPolicy.MAX_SEND_ATTEMPTS)
    }

    @Test
    fun `markSendFailed cuenta solo los envios que llegaron a la red y no cambia el estado`() {
        val r = record("a", DeferredStatus.ENTREGADO, exp = 100)
        val simulacion = DeferredPolicy.markSendFailed(r, "Fondos insuficientes", nowMs = 5_000L, countAsSend = false)
        assertEquals(DeferredStatus.ENTREGADO, simulacion.status)
        assertEquals(0, simulacion.sendAttempts)
        assertEquals("Fondos insuficientes", simulacion.lastSendMotivo)
        assertEquals(5_000L, simulacion.lastAttemptMs)
        // El backoff se aplica aunque no cuente como envío.
        assertFalse(DeferredPolicy.shouldAutoSend(simulacion, null, nowMs = 5_001L))

        val enviado = DeferredPolicy.markSendFailed(simulacion, "La transacción entró y falló.", nowMs = 9_000L, countAsSend = true)
        assertEquals(1, enviado.sendAttempts)
        assertEquals(9_000L, enviado.lastAttemptMs)

        // Un sobre ya cerrado no cambia: ni CONFIRMADO, ni EXPIRADO, ni RECHAZADO.
        for (closed in listOf(DeferredStatus.CONFIRMADO, DeferredStatus.EXPIRADO, DeferredStatus.RECHAZADO)) {
            val c = r.copy(status = closed)
            assertEquals(c, DeferredPolicy.markSendFailed(c, "x", 1L, countAsSend = true))
        }
    }

    @Test
    fun `markFeeTooHigh guarda la cotizacion y la siguiente falla la borra`() {
        val r = record("a", DeferredStatus.ENTREGADO, exp = 100)
        val alta = DeferredPolicy.markFeeTooHigh(r, "La red cobra 28.65 XLM", nowMs = 7_000L, quotedFeeStroops = 286_500_000L)
        assertEquals(286_500_000L, alta.lastQuotedFeeStroops)
        assertEquals(0, alta.sendAttempts)
        assertEquals(7_000L, alta.lastAttemptMs)
        assertEquals(DeferredStatus.ENTREGADO, alta.status)
        assertEquals(0L, DeferredPolicy.markSendFailed(alta, "otra cosa", 8_000L, countAsSend = false).lastQuotedFeeStroops)
        val cerrado = r.copy(status = DeferredStatus.CONFIRMADO)
        assertEquals(cerrado, DeferredPolicy.markFeeTooHigh(cerrado, "x", 1L, 5L))
    }

    @Test
    fun `markInFlight y clearInFlight solo actuan sobre pendientes`() {
        val r = record("a", DeferredStatus.ENTREGADO, exp = 100)
        val hash = "cd".repeat(32)
        val enVuelo = DeferredPolicy.markInFlight(r, hash, validUntilSec = 1_761_400_000L)
        assertEquals(hash, enVuelo.inFlightHash)
        assertEquals(1_761_400_000L, enVuelo.inFlightValidUntilSec)
        assertEquals(DeferredStatus.ENTREGADO, enVuelo.status)
        val libre = DeferredPolicy.clearInFlight(enVuelo)
        assertNull(libre.inFlightHash)
        assertEquals(0L, libre.inFlightValidUntilSec)
        assertEquals(r, libre)
        for (closed in listOf(DeferredStatus.CONFIRMADO, DeferredStatus.EXPIRADO, DeferredStatus.RECHAZADO)) {
            val c = r.copy(status = closed)
            assertEquals(c, DeferredPolicy.markInFlight(c, hash, 1L))
            val cConDiario = c.copy(inFlightHash = hash, inFlightValidUntilSec = 9L)
            assertEquals(cConDiario, DeferredPolicy.clearInFlight(cConDiario))
        }
    }

    @Test
    fun `markConfirmed pisa EXPIRADO y RECHAZADO y limpia el diario y los motivos`() {
        val hash = "ef".repeat(32)
        val sucio = record("a", DeferredStatus.ENTREGADO, exp = 100).copy(
            motivo = "viejo",
            lastSendMotivo = "Fondos insuficientes",
            sendAttempts = 2,
            inFlightHash = "ab".repeat(32),
            inFlightValidUntilSec = 77L,
            lastQuotedFeeStroops = 5L,
        )
        for (status in listOf(DeferredStatus.ENTREGADO, DeferredStatus.EXPIRADO, DeferredStatus.RECHAZADO)) {
            val c = DeferredPolicy.markConfirmed(sucio.copy(status = status), hash)
            assertEquals(DeferredStatus.CONFIRMADO, c.status)
            assertEquals(hash, c.txHash)
            assertNull(c.motivo)
            assertNull(c.lastSendMotivo)
            assertNull(c.inFlightHash)
            assertEquals(0L, c.inFlightValidUntilSec)
            assertEquals(0L, c.lastQuotedFeeStroops)
            // Se conserva lo que ya se había gastado.
            assertEquals(2, c.sendAttempts)
        }
        // Sin hash (prueba por nonce) conserva el que ya hubiera.
        val conHash = DeferredPolicy.markConfirmed(sucio.copy(txHash = hash), null)
        assertEquals(hash, conHash.txHash)
    }

    @Test
    fun `cerrar un sobre por la red o rechazarlo cierra tambien el diario`() {
        val r = record("a", DeferredStatus.ENTREGADO, exp = 100).copy(inFlightHash = "ab".repeat(32), inFlightValidUntilSec = 9L)
        for (out in listOf(DeferredPolicy.markExpiredByNetwork(r), DeferredPolicy.markRejected(r, "alterado"))) {
            assertNull(out.inFlightHash)
            assertEquals(0L, out.inFlightValidUntilSec)
        }
    }

    @Test
    fun `refresh no vence por estimacion un sobre con una tx en vuelo`() {
        val enVuelo = record("a", DeferredStatus.ENTREGADO, exp = 100).copy(inFlightHash = "ab".repeat(32), inFlightValidUntilSec = 9L)
        val normal = record("b", DeferredStatus.ENTREGADO, exp = 100)
        val out = DeferredPolicy.refresh(listOf(enVuelo, normal), estimate = 200)
        assertEquals(DeferredStatus.ENTREGADO, out[0].status)
        assertEquals(DeferredStatus.EXPIRADO, out[1].status)
    }

    // ── Un EXPIRADO por estimación local NO es definitivo ─────────────────────────────────────────
    // (hallazgo de la revisión: `refreshStatuses()` enterraba un sobre que un cobrador liquidó justo antes de vencer)

    private val hashA = "ab".repeat(32)

    /** Un sobre que `refresh` dio por vencido con la estimación local: EXPIRADO, pero solo "según la estimación". */
    private fun vencidoLocal(exp: Long = 100): DeferredRecord =
        DeferredPolicy.refresh(listOf(record("a", DeferredStatus.ENTREGADO, exp = exp)), estimate = exp).single()

    @Test
    fun `un EXPIRADO por estimacion local sigue necesitando a la red`() {
        val local = vencidoLocal()
        assertEquals(DeferredStatus.EXPIRADO, local.status)
        assertEquals(DeferredPolicy.MOTIVO_EXPIRADO, local.motivo)
        assertFalse(local.pending)
        assertTrue(DeferredPolicy.isLocalExpiry(local))
        assertTrue(DeferredPolicy.needsNetworkCheck(local))
        // Los pendientes, por supuesto, también.
        assertTrue(DeferredPolicy.needsNetworkCheck(record("p", DeferredStatus.FIRMADO, exp = 100)))
        assertTrue(DeferredPolicy.needsNetworkCheck(record("p", DeferredStatus.ENTREGADO, exp = 100)))
        // Lo que la red ya cerró no se vuelve a consultar.
        val pendiente = record("p", DeferredStatus.ENTREGADO, exp = 100)
        assertFalse(DeferredPolicy.isLocalExpiry(pendiente))
        assertFalse(DeferredPolicy.needsNetworkCheck(DeferredPolicy.markExpiredByNetwork(pendiente)))
        assertFalse(DeferredPolicy.needsNetworkCheck(DeferredPolicy.markConfirmed(pendiente, hashA)))
        assertFalse(DeferredPolicy.needsNetworkCheck(DeferredPolicy.markRejected(pendiente, "alterado")))
    }

    @Test
    fun `el motivo del EXPIRADO local sobrevive al codec del almacen`() {
        val local = vencidoLocal()
        val back = DeferredRecordCodec.decode(DeferredRecordCodec.encode(listOf(local))).single()
        assertEquals(local, back)
        assertTrue(DeferredPolicy.needsNetworkCheck(back))
    }

    @Test
    fun `la consulta de un EXPIRADO local nunca se salta y no mira el nonce`() {
        val local = vencidoLocal()
        for (consumed in listOf<Boolean?>(null, true, false)) {
            assertEquals(DeferredPolicy.Consulta.MIRAR_EXPIRACION, DeferredPolicy.consulta(local, consumed))
        }
    }

    @Test
    fun `la consulta de un pendiente depende de lo que diga el nonce y lo cerrado se salta`() {
        val p = record("p", DeferredStatus.ENTREGADO, exp = 100)
        assertEquals(DeferredPolicy.Consulta.SALTAR, DeferredPolicy.consulta(p, null))
        assertEquals(DeferredPolicy.Consulta.NONCE_CONSUMIDO, DeferredPolicy.consulta(p, true))
        assertEquals(DeferredPolicy.Consulta.MIRAR_EXPIRACION, DeferredPolicy.consulta(p, false))
        val cerrados = listOf(
            DeferredPolicy.markExpiredByNetwork(p),
            DeferredPolicy.markConfirmed(p, hashA),
            DeferredPolicy.markRejected(p, "alterado"),
        )
        for (cerrado in cerrados) {
            for (consumed in listOf<Boolean?>(null, true, false)) {
                assertEquals(DeferredPolicy.Consulta.SALTAR, DeferredPolicy.consulta(cerrado, consumed))
            }
        }
    }

    @Test
    fun `pasada la expiracion un hash confirma el EXPIRADO local de un sobre cobrado justo antes de vencer`() {
        val out = DeferredPolicy.whenPastExpiration(vencidoLocal(), ReceivedSobrePolicy.Prueba.Hash(hashA))
        assertEquals(DeferredStatus.CONFIRMADO, out.status)
        assertEquals(hashA, out.txHash)
        assertNull(out.motivo)
        assertFalse(DeferredPolicy.needsNetworkCheck(out))
    }

    @Test
    fun `pasada la expiracion sin hash el EXPIRADO local pasa a EXPIRADO por la red y deja de consultarse`() {
        val out = DeferredPolicy.whenPastExpiration(vencidoLocal(), ReceivedSobrePolicy.Prueba.Ninguna)
        assertEquals(DeferredStatus.EXPIRADO, out.status)
        assertEquals(DeferredPolicy.MOTIVO_EXPIRADO_RED, out.motivo)
        assertFalse(DeferredPolicy.needsNetworkCheck(out))
    }

    @Test
    fun `pasada la expiracion sin poder comprobar nada no cambia y se vuelve a consultar`() {
        val local = vencidoLocal()
        val sinCambio = DeferredPolicy.whenPastExpiration(local, ReceivedSobrePolicy.Prueba.NoComprobable)
        assertEquals(local, sinCambio)
        assertTrue(DeferredPolicy.needsNetworkCheck(sinCambio))
        val pendiente = record("p", DeferredStatus.ENTREGADO, exp = 100)
        assertEquals(pendiente, DeferredPolicy.whenPastExpiration(pendiente, ReceivedSobrePolicy.Prueba.NoComprobable))
    }

    @Test
    fun `pasada la expiracion un sobre pendiente se cierra segun la red`() {
        val p = record("p", DeferredStatus.ENTREGADO, exp = 100)
        assertEquals(DeferredStatus.CONFIRMADO, DeferredPolicy.whenPastExpiration(p, ReceivedSobrePolicy.Prueba.Hash(hashA)).status)
        val expirado = DeferredPolicy.whenPastExpiration(p, ReceivedSobrePolicy.Prueba.Ninguna)
        assertEquals(DeferredStatus.EXPIRADO, expirado.status)
        assertEquals(DeferredPolicy.MOTIVO_EXPIRADO_RED, expirado.motivo)
    }

    @Test
    fun `la consulta de expiracion no toca lo ya cerrado salvo que traiga un hash`() {
        val p = record("p", DeferredStatus.ENTREGADO, exp = 100)
        val confirmado = DeferredPolicy.markConfirmed(p, hashA)
        assertEquals(confirmado, DeferredPolicy.whenPastExpiration(confirmado, ReceivedSobrePolicy.Prueba.Ninguna))
        val rechazado = DeferredPolicy.markRejected(p, "alterado")
        assertEquals(rechazado, DeferredPolicy.whenPastExpiration(rechazado, ReceivedSobrePolicy.Prueba.Ninguna))
        // La verdad on-chain manda (D3): un hash con esta auth pisa hasta un RECHAZADO.
        val pisado = DeferredPolicy.whenPastExpiration(rechazado, ReceivedSobrePolicy.Prueba.Hash(hashA))
        assertEquals(DeferredStatus.CONFIRMADO, pisado.status)
    }

    @Test
    fun `verifyLocalExpiry solo cambia el motivo del EXPIRADO local`() {
        val red = DeferredPolicy.verifyLocalExpiry(vencidoLocal())
        assertEquals(DeferredStatus.EXPIRADO, red.status)
        assertEquals(DeferredPolicy.MOTIVO_EXPIRADO_RED, red.motivo)
        assertEquals(red, DeferredPolicy.verifyLocalExpiry(red))
        val pendiente = record("p", DeferredStatus.ENTREGADO, exp = 100)
        assertEquals(pendiente, DeferredPolicy.verifyLocalExpiry(pendiente))
    }

    @Test
    fun `eventsSinceLedger parte del ancla con que se firmo menos un margen y nunca baja de 1`() {
        val r = record("a", DeferredStatus.ENTREGADO, exp = 100) // l0 = 6_389_772
        assertEquals(6_389_772L - DeferredPolicy.EVENTS_MARGIN_LEDGERS, DeferredPolicy.eventsSinceLedger(r))
        assertEquals(1L, DeferredPolicy.eventsSinceLedger(r.copy(l0 = 5L)))
        assertEquals(1L, DeferredPolicy.eventsSinceLedger(r.copy(l0 = 0L)))
    }
}
