package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.xdr.TransactionResultCodeXdr
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transiciones PURAS de un sobre recibido ([ReceivedSobrePolicy]): hash obligatorio para el cobro (D2), la verdad
 * on-chain pisa RECHAZADO y EXPIRADO (D3), qué hacer ante un nonce consumido o una expiración pasada (D2/D4) y el
 * codec del almacén.
 */
class ReceivedSobrePolicyTest {

    private val hash1 = "ab".repeat(32)
    private val hash2 = "cd".repeat(32)
    private val cerrados = listOf(RecibidoStatus.CONFIRMADO, RecibidoStatus.EXPIRADO, RecibidoStatus.RECHAZADO)

    private fun record(status: RecibidoStatus = RecibidoStatus.EN_TRANSITO, exp: Long = 1_000L) = ReceivedSobreRecord(
        id = "GPAYER:7",
        owner = "GMERCHANT",
        sobreQr = "raiz:sobre:1:e30",
        authXdr = "AAAA",
        payer = "GPAYER",
        merchant = "GMERCHANT",
        amountStroops = 25_000_000L,
        tipBps = 200,
        feeBps = 50,
        exp = exp,
        nonce = 7L,
        rol = RolRecibido.COBRO,
        status = status,
        receivedAtMs = 1_000L,
    )

    /** Un registro con todo el "ruido" de envío que un cierre debe limpiar. */
    private fun sucio(status: RecibidoStatus = RecibidoStatus.EN_TRANSITO) = record(status).copy(
        motivo = "viejo",
        lastSendMotivo = "Fondos insuficientes",
        sendAttempts = 2,
        lastAttemptMs = 5L,
        inFlightHash = hash2,
        inFlightValidUntilSec = 77L,
        lastQuotedFeeStroops = 5L,
    )

    // ── fromCheck ───────────────────────────────────────────────────────────

    @Test
    fun `fromCheck copia el sobre verificado a un registro EN_TRANSITO`() = runTest {
        val fx = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/deferred_auth_fixture.json")!!.bufferedReader().readText(),
        ).jsonObject
        fun s(k: String) = fx[k]!!.jsonPrimitive.content
        val ids = DeferredIds(pool = s("pool"), usdcSac = s("usdc_sac"), admin = s("admin"))
        val ok = SobreValidator.validateText(s("sobre_qr"), ids, fx["fee_bps"]!!.jsonPrimitive.int, Network.TESTNET)
        check(ok is SobreCheck.Ok)

        val r = ReceivedSobrePolicy.fromCheck(
            ok = ok, sobreQr = s("sobre_qr"), owner = s("merchant"), rol = RolRecibido.COBRO,
            feeBps = 50, nowMs = 123L, ledgerRef = 6_000_000L, vigenciaComprobada = true,
        )
        assertEquals("${s("payer_public")}:${s("nonce")}", r.id)
        assertEquals(ReceivedSobrePolicy.idOf(s("payer_public"), s("nonce").toLong()), r.id)
        assertEquals(s("entry_signed_xdr"), r.authXdr)
        assertEquals(s("merchant"), r.merchant)
        assertEquals(25_000_000L, r.amountStroops)
        assertEquals(s("signature_expiration_ledger").toLong(), r.exp)
        assertEquals(RecibidoStatus.EN_TRANSITO, r.status)
        assertEquals(123L, r.receivedAtMs)
        assertEquals(6_000_000L, r.receivedAtLedger)
        assertNull(r.txHash)
        assertNull(r.motivo)
    }

    // ── Cierres: el hash es obligatorio (D2) y la verdad on-chain manda (D3) ─

    @Test
    fun `markConfirmed exige un hash y limpia el diario, los motivos y la cotizacion`() {
        for (status in listOf(RecibidoStatus.EN_TRANSITO, RecibidoStatus.EXPIRADO, RecibidoStatus.RECHAZADO)) {
            val c = ReceivedSobrePolicy.markConfirmed(sucio(status), hash1)
            assertEquals(RecibidoStatus.CONFIRMADO, c.status)
            assertEquals(hash1, c.txHash)
            assertNull(c.motivo)
            assertNull(c.lastSendMotivo)
            assertNull(c.inFlightHash)
            assertEquals(0L, c.inFlightValidUntilSec)
            assertEquals(0L, c.lastQuotedFeeStroops)
            assertEquals("lo ya gastado se conserva", 2, c.sendAttempts)
        }
        // Sin hash (en blanco) no hay prueba: el registro no cambia, ni siquiera un EXPIRADO o un RECHAZADO.
        for (status in RecibidoStatus.entries) {
            val r = sucio(status)
            assertSame(r, ReceivedSobrePolicy.markConfirmed(r, ""))
            assertSame(r, ReceivedSobrePolicy.markConfirmed(r, "   "))
        }
    }

    @Test
    fun `markConfirmed es idempotente y conserva el primer hash`() {
        val c = ReceivedSobrePolicy.markConfirmed(record(), hash1)
        assertSame(c, ReceivedSobrePolicy.markConfirmed(c, hash2))
        assertEquals(hash1, ReceivedSobrePolicy.markConfirmed(c, hash2).txHash)
    }

    @Test
    fun `markNonceConsumidoSinHash mantiene EN_TRANSITO y solo toca pendientes`() {
        val r = ReceivedSobrePolicy.markNonceConsumidoSinHash(record())
        assertEquals(RecibidoStatus.EN_TRANSITO, r.status)
        assertTrue(r.pending)
        assertEquals(ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE, r.motivo)
        assertNull(r.txHash)
        for (status in cerrados) {
            val c = record(status).copy(motivo = "otro")
            assertSame(c, ReceivedSobrePolicy.markNonceConsumidoSinHash(c))
        }
    }

    @Test
    fun `lo que no es un cierre ni un diario no cambia un sobre ya cerrado`() {
        for (status in cerrados) {
            val c = sucio(status)
            assertSame(c, ReceivedSobrePolicy.markInFlight(c, hash1, 9L))
            assertSame(c, ReceivedSobrePolicy.clearInFlight(c))
            assertSame(c, ReceivedSobrePolicy.markSendFailed(c, "x", 1L, countAsSend = true))
            assertSame(c, ReceivedSobrePolicy.markFeeTooHigh(c, "x", 1L, 5L))
            assertSame(c, ReceivedSobrePolicy.markRejected(c, "x"))
            assertSame(c, ReceivedSobrePolicy.markExpiredByNetwork(c))
        }
    }

    @Test
    fun `expirar por la red o rechazar cierra tambien el diario`() {
        for (out in listOf(ReceivedSobrePolicy.markExpiredByNetwork(sucio()), ReceivedSobrePolicy.markRejected(sucio(), "alterado"))) {
            assertNull(out.inFlightHash)
            assertEquals(0L, out.inFlightValidUntilSec)
        }
        val e = ReceivedSobrePolicy.markExpiredByNetwork(record())
        assertEquals(RecibidoStatus.EXPIRADO, e.status)
        assertEquals(ReceivedSobrePolicy.MOTIVO_EXPIRADO_RED, e.motivo)
        val x = ReceivedSobrePolicy.markRejected(record(), "firma")
        assertEquals(RecibidoStatus.RECHAZADO, x.status)
        assertEquals("firma", x.motivo)
    }

    // ── Envío ───────────────────────────────────────────────────────────────

    @Test
    fun `markInFlight y clearInFlight llevan el diario de un sobre pendiente`() {
        val r = record()
        val enVuelo = ReceivedSobrePolicy.markInFlight(r, hash1, validUntilSec = 1_761_400_000L)
        assertEquals(hash1, enVuelo.inFlightHash)
        assertEquals(1_761_400_000L, enVuelo.inFlightValidUntilSec)
        assertEquals(RecibidoStatus.EN_TRANSITO, enVuelo.status)
        assertEquals(r, ReceivedSobrePolicy.clearInFlight(enVuelo))
    }

    @Test
    fun `markSendFailed cuenta solo los envios que llegaron a la red y no cambia el estado`() {
        val simulacion = ReceivedSobrePolicy.markSendFailed(record(), "Fondos insuficientes", nowMs = 5_000L, countAsSend = false)
        assertEquals(RecibidoStatus.EN_TRANSITO, simulacion.status)
        assertEquals(0, simulacion.sendAttempts)
        assertEquals("Fondos insuficientes", simulacion.lastSendMotivo)
        assertEquals(5_000L, simulacion.lastAttemptMs)
        val enviado = ReceivedSobrePolicy.markSendFailed(simulacion, "La transacción entró y falló.", 9_000L, countAsSend = true)
        assertEquals(1, enviado.sendAttempts)
        assertEquals(9_000L, enviado.lastAttemptMs)
    }

    @Test
    fun `markFeeTooHigh guarda la cotizacion y la siguiente falla la borra`() {
        val alta = ReceivedSobrePolicy.markFeeTooHigh(record(), "La red cobra 28.65 XLM", 7_000L, 286_500_000L)
        assertEquals(286_500_000L, alta.lastQuotedFeeStroops)
        assertEquals(0, alta.sendAttempts)
        assertEquals(7_000L, alta.lastAttemptMs)
        assertEquals(0L, ReceivedSobrePolicy.markSendFailed(alta, "otra", 8_000L, countAsSend = false).lastQuotedFeeStroops)
    }

    @Test
    fun `shouldAutoSend exige pendiente, sin tx en vuelo, tope, margen de ledgers y backoff`() {
        val now = 1_000_000_000L
        val base = record(exp = 1_000L)
        assertTrue(ReceivedSobrePolicy.shouldAutoSend(base, ledgerHint = null, nowMs = now))
        for (closed in cerrados) assertFalse(ReceivedSobrePolicy.shouldAutoSend(base.copy(status = closed), null, now))
        // Con una tx en vuelo NO se construye otra (se reanuda por hash).
        assertFalse(ReceivedSobrePolicy.shouldAutoSend(base.copy(inFlightHash = hash1), null, now))
        // Tope de envíos que llegaron a la red.
        val max = ReceivedSobrePolicy.MAX_SEND_ATTEMPTS
        assertEquals(3, max)
        assertTrue(ReceivedSobrePolicy.shouldAutoSend(base.copy(sendAttempts = max - 1), null, now))
        assertFalse(ReceivedSobrePolicy.shouldAutoSend(base.copy(sendAttempts = max), null, now))
        // exp − ledger >= 3 cuando se conoce el ledger.
        assertTrue(ReceivedSobrePolicy.shouldAutoSend(base, ledgerHint = 997, nowMs = now))
        assertFalse(ReceivedSobrePolicy.shouldAutoSend(base, ledgerHint = 998, nowMs = now))
        assertFalse(ReceivedSobrePolicy.shouldAutoSend(base, ledgerHint = 1_500, nowMs = now))
        // Backoff de 5 min desde el último intento.
        assertEquals(300_000L, ReceivedSobrePolicy.RETRY_BACKOFF_MS)
        assertFalse(ReceivedSobrePolicy.shouldAutoSend(base.copy(lastAttemptMs = now - 1_000L), null, now))
        assertFalse(ReceivedSobrePolicy.shouldAutoSend(base.copy(lastAttemptMs = now - 299_999L), null, now))
        assertTrue(ReceivedSobrePolicy.shouldAutoSend(base.copy(lastAttemptMs = now - 300_000L), null, now))
    }

    @Test
    fun `un nonce ya visto consumido no se envia otra vez en automatico`() {
        val buscando = ReceivedSobrePolicy.markNonceConsumidoSinHash(record())
        assertFalse(ReceivedSobrePolicy.shouldAutoSend(buscando, null, 1_000_000_000L))
    }

    // ── Vencimiento por estimación local ────────────────────────────────────

    @Test
    fun `refresh vence solo a los pendientes sin tx en vuelo ni nonce consumido`() {
        val lista = listOf(
            record(exp = 100).copy(id = "a"),
            record(exp = 101).copy(id = "b"),
            record(exp = 100).copy(id = "c", inFlightHash = hash1, inFlightValidUntilSec = 9L),
            ReceivedSobrePolicy.markNonceConsumidoSinHash(record(exp = 100)).copy(id = "d"),
            record(RecibidoStatus.CONFIRMADO, exp = 50).copy(id = "e"),
            record(RecibidoStatus.RECHAZADO, exp = 50).copy(id = "f"),
        )
        val out = ReceivedSobrePolicy.refresh(lista, estimate = 100).associateBy { it.id }
        assertEquals(RecibidoStatus.EXPIRADO, out["a"]!!.status)
        assertEquals(ReceivedSobrePolicy.MOTIVO_EXPIRADO_LOCAL, out["a"]!!.motivo)
        assertEquals(RecibidoStatus.EN_TRANSITO, out["b"]!!.status)
        assertEquals(RecibidoStatus.EN_TRANSITO, out["c"]!!.status)
        assertEquals(RecibidoStatus.EN_TRANSITO, out["d"]!!.status)
        assertEquals(RecibidoStatus.CONFIRMADO, out["e"]!!.status)
        assertEquals(RecibidoStatus.RECHAZADO, out["f"]!!.status)
        assertEquals(lista, ReceivedSobrePolicy.refresh(lista, estimate = null))
    }

    @Test
    fun `el EXPIRADO local no es definitivo y la red lo confirma o lo desmiente`() {
        val local = ReceivedSobrePolicy.refresh(listOf(record(exp = 100)), 100).single()
        assertTrue(ReceivedSobrePolicy.isLocalExpiry(local))
        assertTrue(ReceivedSobrePolicy.needsNetworkCheck(local))
        assertTrue(ReceivedSobrePolicy.needsNetworkCheck(record()))
        val deLaRed = ReceivedSobrePolicy.markExpiredByNetwork(record())
        assertFalse(ReceivedSobrePolicy.needsNetworkCheck(deLaRed))
        assertFalse(ReceivedSobrePolicy.needsNetworkCheck(record(RecibidoStatus.CONFIRMADO)))
        assertFalse(ReceivedSobrePolicy.needsNetworkCheck(record(RecibidoStatus.RECHAZADO)))
        // La red lo desmiente con un hash...
        assertEquals(RecibidoStatus.CONFIRMADO, ReceivedSobrePolicy.markConfirmed(local, hash1).status)
        // ...o lo confirma (y ya no se vuelve a consultar).
        val confirmado = ReceivedSobrePolicy.verifyLocalExpiry(local)
        assertEquals(RecibidoStatus.EXPIRADO, confirmado.status)
        assertEquals(ReceivedSobrePolicy.MOTIVO_EXPIRADO_RED, confirmado.motivo)
        assertSame(deLaRed, ReceivedSobrePolicy.verifyLocalExpiry(deLaRed))
    }

    // ── Decisiones ante lo que dice la red (D2/D3/D4) ───────────────────────

    private val conHash = ReceivedSobrePolicy.Prueba.Hash(hash1)
    private val ninguna = ReceivedSobrePolicy.Prueba.Ninguna
    private val noComprobable = ReceivedSobrePolicy.Prueba.NoComprobable

    @Test
    fun `nonce consumido con un hash de este sobre lo confirma`() {
        val c = ReceivedSobrePolicy.whenNonceConsumed(record(), conHash, nonceEnOtraTx = false)
        assertEquals(RecibidoStatus.CONFIRMADO, c.status)
        assertEquals(hash1, c.txHash)
        // El hash manda sobre un RECHAZADO previo (D3).
        assertEquals(
            RecibidoStatus.CONFIRMADO,
            ReceivedSobrePolicy.whenNonceConsumed(record(RecibidoStatus.RECHAZADO), conHash, nonceEnOtraTx = true).status,
        )
    }

    @Test
    fun `nonce consumido SIN hash no es cobrado y sigue en transito buscando el comprobante`() {
        for (prueba in listOf(ninguna, noComprobable)) {
            val r = ReceivedSobrePolicy.whenNonceConsumed(record(), prueba, nonceEnOtraTx = false)
            assertEquals(RecibidoStatus.EN_TRANSITO, r.status)
            assertEquals(ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE, r.motivo)
            assertNull(r.txHash)
        }
    }

    @Test
    fun `nonce gastado por otro sobre del mismo pagador es rechazo por posible fraude`() {
        val r = ReceivedSobrePolicy.whenNonceConsumed(record(), ninguna, nonceEnOtraTx = true)
        assertEquals(RecibidoStatus.RECHAZADO, r.status)
        assertEquals(ReceivedSobrePolicy.MOTIVO_NONCE_OTRO_PAGO, r.motivo)
        // Sin poder comprobar nada no se acusa de fraude.
        assertEquals(
            RecibidoStatus.EN_TRANSITO,
            ReceivedSobrePolicy.whenNonceConsumed(record(), noComprobable, nonceEnOtraTx = true).status,
        )
    }

    @Test
    fun `pasada la expiracion un hash confirma incluso un EXPIRADO o RECHAZADO previo`() {
        for (status in RecibidoStatus.entries) {
            val c = ReceivedSobrePolicy.whenPastExpiration(sucio(status), conHash)
            assertEquals(RecibidoStatus.CONFIRMADO, c.status)
            assertEquals(hash1, c.txHash)
        }
    }

    @Test
    fun `pasada la expiracion sin hash un nonce nunca visto consumido simplemente expiro`() {
        val e = ReceivedSobrePolicy.whenPastExpiration(record(), ninguna)
        assertEquals(RecibidoStatus.EXPIRADO, e.status)
        assertEquals(ReceivedSobrePolicy.MOTIVO_EXPIRADO_RED, e.motivo)
    }

    @Test
    fun `pasada la expiracion sin hash un nonce que ya se vio consumido es posible fraude`() {
        val buscando = ReceivedSobrePolicy.markNonceConsumidoSinHash(record())
        val r = ReceivedSobrePolicy.whenPastExpiration(buscando, ninguna)
        assertEquals(RecibidoStatus.RECHAZADO, r.status)
        assertEquals(ReceivedSobrePolicy.MOTIVO_NONCE_OTRO_PAGO, r.motivo)
    }

    @Test
    fun `pasada la expiracion sin poder comprobar nada el registro no cambia`() {
        val r = record()
        assertSame(r, ReceivedSobrePolicy.whenPastExpiration(r, noComprobable))
        val buscando = ReceivedSobrePolicy.markNonceConsumidoSinHash(r)
        assertSame(buscando, ReceivedSobrePolicy.whenPastExpiration(buscando, noComprobable))
    }

    @Test
    fun `pasada la expiracion un EXPIRADO local sin hash pasa a expirado por la red`() {
        val local = ReceivedSobrePolicy.refresh(listOf(record(exp = 100)), 100).single()
        val r = ReceivedSobrePolicy.whenPastExpiration(local, ninguna)
        assertEquals(RecibidoStatus.EXPIRADO, r.status)
        assertEquals(ReceivedSobrePolicy.MOTIVO_EXPIRADO_RED, r.motivo)
    }

    // ── Pasada de consulta y reacción a cada veredicto (D2/D3) ──────────────

    @Test
    fun `consulta decide por el nonce, la tx en vuelo y el estado`() {
        val saltar = ReceivedSobrePolicy.Consulta.SALTAR
        val nonceConsumido = ReceivedSobrePolicy.Consulta.NONCE_CONSUMIDO
        val mirarExpiracion = ReceivedSobrePolicy.Consulta.MIRAR_EXPIRACION
        fun consulta(r: ReceivedSobreRecord, consumed: Boolean?) = ReceivedSobrePolicy.consulta(r, consumed)
        // Pendiente.
        assertEquals(nonceConsumido, consulta(record(), true))
        assertEquals(mirarExpiracion, consulta(record(), false))
        assertEquals("sin respuesta de la red no se decide nada", saltar, consulta(record(), null))
        // Con una tx en vuelo manda su hash (ni vence ni se rechaza antes), pero un nonce consumido sí se investiga.
        val enVuelo = record().copy(inFlightHash = hash1, inFlightValidUntilSec = 9L)
        assertEquals(saltar, consulta(enVuelo, false))
        assertEquals(nonceConsumido, consulta(enVuelo, true))
        // "Buscando el comprobante" se vuelve a investigar en cada pasada.
        val buscando = ReceivedSobrePolicy.markNonceConsumidoSinHash(record())
        assertEquals(nonceConsumido, consulta(buscando, true))
        assertEquals(mirarExpiracion, consulta(buscando, false))
        // EXPIRADO solo por estimación local: no hay nonce que mirar, decide el ledger real.
        val local = ReceivedSobrePolicy.refresh(listOf(record(exp = 100)), 100).single()
        assertEquals(mirarExpiracion, consulta(local, null))
        // Cerrados de verdad: nada que consultar.
        assertEquals(saltar, consulta(record(RecibidoStatus.CONFIRMADO), true))
        assertEquals(saltar, consulta(record(RecibidoStatus.RECHAZADO), false))
        assertEquals(saltar, consulta(ReceivedSobrePolicy.markExpiredByNetwork(record()), false))
    }

    @Test
    fun `exp es el ultimo ledger valido y sin ledger no se sabe`() {
        val r = record(exp = 1_000L)
        assertFalse(ReceivedSobrePolicy.isPastExpiration(r, 999L))
        assertFalse(ReceivedSobrePolicy.isPastExpiration(r, 1_000L))
        assertTrue(ReceivedSobrePolicy.isPastExpiration(r, 1_001L))
        assertFalse(ReceivedSobrePolicy.isPastExpiration(r, null))
    }

    @Test
    fun `solo un Liquidado confirma y solo InvalidAction rechaza`() {
        fun reaccion(o: SobreSubmit) = ReceivedSobrePolicy.reaccion(o)
        assertEquals(ReceivedSobrePolicy.Reaccion.Confirmar(hash1), reaccion(SobreSubmit.Liquidado(hash1)))
        assertEquals(ReceivedSobrePolicy.Reaccion.Nada, reaccion(SobreSubmit.EnVuelo(hash1, 9L)))
        // El nonce usado y la tx fallida NO confirman ni rechazan: hay que mirar la red.
        assertEquals(ReceivedSobrePolicy.Reaccion.RevisarNonce(fallida = false), reaccion(SobreSubmit.NonceUsado))
        assertEquals(ReceivedSobrePolicy.Reaccion.RevisarNonce(fallida = true), reaccion(SobreSubmit.FallidaEnRed(hash1)))
        assertEquals(ReceivedSobrePolicy.Reaccion.RevisarExpiracion, reaccion(SobreSubmit.Expirada))
        assertEquals(
            ReceivedSobrePolicy.Reaccion.Rechazar(SobreErrors.MSG_AUTH),
            reaccion(SobreSubmit.RechazadaDefinitiva(SobreErrors.MSG_AUTH)),
        )
        assertEquals(
            ReceivedSobrePolicy.Reaccion.FeeAlta(300_000_000L, 20_000_000L),
            reaccion(SobreSubmit.FeeAlta(300_000_000L, 20_000_000L)),
        )
    }

    @Test
    fun `fondos, trustline, comercio, fuente sin XLM y fallos de red aplazan sin contar como envio`() {
        val aplazables = listOf(
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_10),
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_13),
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_5),
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_4),
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_AUTH_OTRO),
            SobreSubmit.SinFondosFuente(SobreErrors.MSG_SIN_XLM),
            SobreSubmit.SinRed(SobreErrors.MSG_SATURADA),
        )
        for (o in aplazables) {
            val r = ReceivedSobrePolicy.reaccion(o)
            assertTrue("$o debe aplazar", r is ReceivedSobrePolicy.Reaccion.Aplazar)
            assertFalse(r.cuentaComoEnvio())
        }
    }

    @Test
    fun `de punta a punta con los clasificadores de la red solo InvalidAction termina en rechazo`() {
        fun rechaza(o: SobreSubmit) = ReceivedSobrePolicy.reaccion(o) is ReceivedSobrePolicy.Reaccion.Rechazar
        val simulaciones = listOf(
            "Error(Contract, #10)", "Error(Contract, #13)", "Error(Contract, #5)", "Error(Contract, #4)",
            "Error(Contract, #7)", "Error(Auth, ExistingValue)", "Error(Auth, InvalidInput)",
            "Error(Auth, MissingValue)", "transaction simulation failed",
        )
        for (texto in simulaciones) assertFalse("«$texto» no debe rechazar", rechaza(SobreErrors.classifySimulation(texto)))
        assertTrue(rechaza(SobreErrors.classifySimulation("HostError: Error(Auth, InvalidAction)")))
        val codigos = listOf(
            TransactionResultCodeXdr.txINSUFFICIENT_BALANCE, TransactionResultCodeXdr.txINSUFFICIENT_FEE,
            TransactionResultCodeXdr.txBAD_SEQ, TransactionResultCodeXdr.txTOO_LATE,
            TransactionResultCodeXdr.txBAD_AUTH, TransactionResultCodeXdr.txNO_ACCOUNT,
            TransactionResultCodeXdr.txSOROBAN_INVALID, null,
        )
        for (codigo in codigos) assertFalse("$codigo no debe rechazar", rechaza(SobreErrors.classifySend(codigo)))
    }

    private fun ReceivedSobrePolicy.Reaccion.cuentaComoEnvio(): Boolean =
        (this as ReceivedSobrePolicy.Reaccion.Aplazar).cuentaComoEnvio

    // ── Codec ───────────────────────────────────────────────────────────────

    @Test
    fun `el codec hace round trip y lee basura como lista vacia`() {
        val lleno = record().copy(
            id = "a:1", sendAttempts = 2, lastSendMotivo = "x", lastAttemptMs = 9L, inFlightHash = hash1,
            inFlightValidUntilSec = 5L, lastQuotedFeeStroops = 9L, receivedAtLedger = 77L,
            vigenciaComprobada = false, motivo = "m", txHash = hash2,
        )
        val list = listOf(lleno, record(RecibidoStatus.CONFIRMADO).copy(id = "b:-2", nonce = -2L))
        assertEquals(list, ReceivedSobreCodec.decode(ReceivedSobreCodec.encode(list)))
        assertTrue(ReceivedSobreCodec.decode(null).isEmpty())
        for (basura in listOf("", "   ", "{basura", "[{\"id\":1}]", "{}", "42", "null", "[1,2,3]")) {
            assertTrue("«$basura» debe leerse vacía", ReceivedSobreCodec.decode(basura).isEmpty())
        }
    }

    @Test
    fun `un registro guardado sin los campos opcionales se lee con valores por defecto`() {
        val minimo = """[{"id":"G:7","owner":"GM","sobreQr":"raiz:sobre:1:e30","authXdr":"AAAA","payer":"GP",""" +
            """"merchant":"GM","amountStroops":25000000,"tipBps":200,"feeBps":50,"exp":1000,"nonce":7,""" +
            """"rol":"COBRO","status":"EN_TRANSITO","receivedAtMs":1000,"campoFuturo":true}]"""
        val r = ReceivedSobreCodec.decode(minimo).single()
        assertEquals(RolRecibido.COBRO, r.rol)
        assertNull(r.receivedAtLedger)
        assertTrue(r.vigenciaComprobada)
        assertEquals(0, r.sendAttempts)
        assertNull(r.inFlightHash)
        assertEquals(0L, r.lastQuotedFeeStroops)
    }

    @Test
    fun `un elemento ilegible no se lleva a los buenos`() {
        val buenos = listOf(record().copy(id = "a:1"), record().copy(id = "b:2"))
        val roto = ReceivedSobreCodec.encode(buenos).dropLast(1) + ",{\"id\":\"c:3\",\"status\":\"RARO\"}]"
        assertEquals(buenos, ReceivedSobreCodec.decode(roto))
    }

    @Test
    fun `los derivados del registro cuadran con el contrato`() {
        val r = record()
        assertEquals(500_000L, r.tipStroops)
        assertEquals(125_000L, r.feeStroops)
        assertEquals(24_875_000L, r.toMerchantStroops)
        assertEquals(25_500_000L, r.totalStroops)
        assertTrue(r.pending)
        assertFalse(record(RecibidoStatus.CONFIRMADO).pending)
        assertEquals("GP:-9", ReceivedSobrePolicy.idOf("GP", -9L))
    }

    @Test
    fun `eventsSinceLedger usa la ventana nominal del sobre y no el ledger de recepcion`() {
        val nominal = 1_000_000L - LedgerMath.WINDOW_12H - ReceivedSobrePolicy.EVENTS_DRIFT_MARGIN_LEDGERS
        assertEquals(985_480L, nominal)
        val r = record(exp = 1_000_000L)
        assertEquals(nominal, ReceivedSobrePolicy.eventsSinceLedger(r))
        // Recibido DESPUÉS de esa fecha (lo normal): manda la ventana nominal, otro pudo liquidarlo antes de que llegara.
        assertEquals(nominal, ReceivedSobrePolicy.eventsSinceLedger(r.copy(receivedAtLedger = 990_000L)))
        // Recibido ANTES (un sobre con más vigencia que los de RAÍZ): se parte de ese ledger.
        assertEquals(500_000L, ReceivedSobrePolicy.eventsSinceLedger(r.copy(receivedAtLedger = 500_000L)))
        // Nunca baja de 1.
        assertEquals(1L, ReceivedSobrePolicy.eventsSinceLedger(record(exp = 100L)))
    }
}
