package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.xdr.SorobanAuthorizationEntryXdr
import com.soneso.stellar.sdk.xdr.fromXdrBase64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * Verificación PURA de un sobre recibido ([ReceivedSobreVerifier], D11): rol, índice de nonces, tope, vigencia con
 * ledger real o estimado, y que un sobre alterado nunca se acepta. Todo contra el fixture de `gen-auth-fixture.mjs`.
 */
class ReceivedSobreVerifierTest {

    private val fx: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/deferred_auth_fixture.json")) {
            "Falta deferred_auth_fixture.json: node scripts/gen-auth-fixture.mjs"
        }
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun s(key: String) = fx[key]!!.jsonPrimitive.content
    private val network = Network.TESTNET
    private val ids get() = DeferredIds(pool = s("pool"), usdcSac = s("usdc_sac"), admin = s("admin"))
    private val feeBps get() = fx["fee_bps"]!!.jsonPrimitive.int
    private val payer get() = s("payer_public")
    private val merchant get() = s("merchant")

    /** Otra cuenta cualquiera: quien la tenga como dueña es PORTADOR de este sobre. */
    private val otro get() = s("admin")
    private val nonce get() = s("nonce").toLong()
    private val exp get() = fx["signature_expiration_ledger"]!!.jsonPrimitive.int.toLong()
    private val ahoraMs = 1_761_400_000_000L

    private suspend fun verify(
        text: String = s("sobre_qr"),
        owner: String = merchant,
        tope: Long = ReceivedSobreVerifier.TOPE_DEFAULT_STROOPS,
        ledgerActual: Long? = 6_000_000L,
        estimate: Long? = null,
        anchorAgeMs: Long? = null,
        vistos: (String, Long) -> ReceivedSobreRecord? = { _, _ -> null },
        fee: Int = feeBps,
        red: Network = network,
    ): RecibidoCheck = ReceivedSobreVerifier.verify(
        text, owner, tope, ledgerActual, estimate, anchorAgeMs, vistos, ids, fee, red, ahoraMs,
    )

    private fun RecibidoCheck.record(): ReceivedSobreRecord = (this as RecibidoCheck.Aceptado).record

    private fun motivo(c: RecibidoCheck): String? = (c as? RecibidoCheck.Rechazado)?.motivo

    /** Firma OTRO sobre del mismo pagador (mismo nonce por omisión), como lo haría un pagador tramposo. */
    private suspend fun firmarSobre(amount: Long = s("amount").toLong(), nonce: Long = this.nonce): String {
        val kp = KeyPair.fromSecretSeed(hexToBytes(s("payer_seed_hex")))
        val tipBps = fx["tip_bps"]!!.jsonPrimitive.int
        val inv = AuthTreeBuilder.payMerchantInvocation(payer, merchant, amount, tipBps, feeBps, ids)
        val signed = DeferredAuthSigner.sign(DeferredAuthSigner.unsignedEntry(payer, nonce, exp, inv), kp, network)
        return SobreCodec.encode(signed.entry)
    }

    private fun hexToBytes(h: String): ByteArray = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ── 1–2. Aceptación y rol ───────────────────────────────────────────────

    @Test
    fun `1 el comercio destinatario acepta un sobre vigente como COBRO y lo deriva todo del XDR`() = runTest {
        val r = verify().record()
        assertEquals(RolRecibido.COBRO, r.rol)
        assertEquals(RecibidoStatus.EN_TRANSITO, r.status)
        assertEquals(PayMerchantSplit(tip = 500_000L, fee = 125_000L, toMerchant = 24_875_000L), r.split)
        assertEquals(500_000L, r.tipStroops)
        assertEquals(125_000L, r.feeStroops)
        assertEquals(24_875_000L, r.toMerchantStroops)
        assertEquals(25_500_000L, r.totalStroops)
        assertEquals(nonce, r.nonce)
        assertEquals("$payer:$nonce", r.id)
        assertEquals(payer, r.payer)
        assertEquals(merchant, r.merchant)
        assertEquals(merchant, r.owner)
        assertEquals(25_000_000L, r.amountStroops)
        assertEquals(200, r.tipBps)
        assertEquals(50, r.feeBps)
        assertEquals(exp, r.exp)
        assertEquals(s("entry_signed_xdr"), r.authXdr)
        assertEquals(s("sobre_qr"), r.sobreQr)
        assertEquals(6_000_000L, r.receivedAtLedger)
        assertEquals(ahoraMs, r.receivedAtMs)
        assertTrue(r.vigenciaComprobada)
        assertTrue(r.pending)
        assertNull(r.txHash)
        assertEquals(0, r.sendAttempts)
    }

    @Test
    fun `2 una wallet distinta del comercio lo acepta como PORTADOR`() = runTest {
        val r = verify(owner = otro).record()
        assertEquals(RolRecibido.PORTADOR, r.rol)
        assertEquals(otro, r.owner)
        assertEquals(merchant, r.merchant)
        assertTrue(r.vigenciaComprobada)
    }

    @Test
    fun `el texto con espacios y saltos se guarda en su forma canonica`() = runTest {
        val sucio = "  \n" + s("sobre_qr").substring(0, 300) + "\n" + s("sobre_qr").substring(300) + "\t "
        assertEquals(s("sobre_qr"), verify(text = sucio).record().sobreQr)
    }

    // ── 3–4. Índice (owner, payer, nonce) ───────────────────────────────────

    @Test
    fun `3 el mismo sobre ya registrado es idempotente aunque haya vencido`() = runTest {
        val guardado = verify().record()
        var preguntado: Pair<String, Long>? = null
        val vistos: (String, Long) -> ReceivedSobreRecord? = { p, n ->
            preguntado = p to n
            guardado
        }
        assertEquals(RecibidoCheck.YaRegistrado(guardado), verify(vistos = vistos))
        assertEquals(payer to nonce, preguntado)
        // Aunque entre tanto venza o supere el tope, sigue siendo "ya registrado": no se vuelve a juzgar.
        assertEquals(RecibidoCheck.YaRegistrado(guardado), verify(vistos = vistos, ledgerActual = exp + 10))
        assertEquals(RecibidoCheck.YaRegistrado(guardado), verify(vistos = vistos, tope = 1L))
    }

    @Test
    fun `4 el mismo nonce con otro XDR es un nonce reutilizado`() = runTest {
        val otroSobre = firmarSobre(amount = s("amount").toLong() + 1)
        val primero = verify(text = otroSobre).record()
        assertEquals(nonce, primero.nonce)
        // Llega el sobre del fixture (mismo pagador y nonce, otro monto): fraude, para el COBRO y para el PORTADOR.
        val fraude = RecibidoCheck.Rechazado(ReceivedSobreVerifier.MOTIVO_NONCE_REUTILIZADO)
        assertEquals(fraude, verify(vistos = { _, _ -> primero }))
        assertEquals(fraude, verify(owner = otro, vistos = { _, _ -> primero.copy(owner = otro, rol = RolRecibido.PORTADOR) }))
    }

    @Test
    fun `otro nonce del mismo pagador es un sobre nuevo`() = runTest {
        val r = verify(
            text = firmarSobre(nonce = nonce + 1),
            vistos = { p, n ->
                assertEquals(payer, p)
                assertEquals(nonce + 1, n)
                null
            },
        ).record()
        assertEquals("$payer:${nonce + 1}", r.id)
    }

    // ── 5. Tope (solo COBRO) ────────────────────────────────────────────────

    @Test
    fun `5 el tope rechaza al COBRO pero no al PORTADOR`() = runTest {
        // 2,5 USDC contra un tope de 2 USDC.
        assertEquals(ReceivedSobreVerifier.MOTIVO_TOPE, motivo(verify(tope = 20_000_000L)))
        assertEquals(RolRecibido.PORTADOR, verify(owner = otro, tope = 20_000_000L).record().rol)
        // El tope es inclusivo: el monto exacto pasa, un stroop menos no.
        assertEquals(RolRecibido.COBRO, verify(tope = 25_000_000L).record().rol)
        assertEquals(ReceivedSobreVerifier.MOTIVO_TOPE, motivo(verify(tope = 24_999_999L)))
        // Tope 0 = no aceptar cobros sin señal.
        assertEquals(ReceivedSobreVerifier.MOTIVO_TOPE, motivo(verify(tope = 0L)))
        assertEquals(200_000_000L, ReceivedSobreVerifier.TOPE_DEFAULT_STROOPS)
    }

    // ── 6. Vigencia con el ledger real ──────────────────────────────────────

    @Test
    fun `6 un COBRO vencido o por vencer se rechaza con el ledger real`() = runTest {
        assertEquals(ReceivedSobreVerifier.MOTIVO_EXPIRADO, motivo(verify(ledgerActual = exp)))
        assertEquals(ReceivedSobreVerifier.MOTIVO_EXPIRADO, motivo(verify(ledgerActual = exp + 500)))
        assertEquals(ReceivedSobreVerifier.MOTIVO_VENCE_PRONTO, motivo(verify(ledgerActual = exp - 100)))
        // El colchón es de 360 ledgers (30 min): justo en el límite pasa, un ledger antes no.
        assertEquals(RolRecibido.COBRO, verify(ledgerActual = exp - 360).record().rol)
        assertEquals(ReceivedSobreVerifier.MOTIVO_VENCE_PRONTO, motivo(verify(ledgerActual = exp - 359)))
        assertEquals(360L, ReceivedSobreVerifier.MARGEN_LEDGERS)
    }

    @Test
    fun `un PORTADOR solo rechaza lo ya vencido, no exige colchon`() = runTest {
        assertEquals(ReceivedSobreVerifier.MOTIVO_EXPIRADO, motivo(verify(owner = otro, ledgerActual = exp)))
        val r = verify(owner = otro, ledgerActual = exp - 100).record()
        assertEquals(RolRecibido.PORTADOR, r.rol)
        assertTrue(r.vigenciaComprobada)
    }

    // ── 7. Sin referencia de ledger ─────────────────────────────────────────

    @Test
    fun `7 sin ledger ni estimacion un COBRO se rechaza y un PORTADOR solo transporta`() = runTest {
        assertEquals(ReceivedSobreVerifier.MOTIVO_SIN_HORA, motivo(verify(ledgerActual = null, estimate = null)))
        val p = verify(owner = otro, ledgerActual = null, estimate = null).record()
        assertEquals(RolRecibido.PORTADOR, p.rol)
        assertFalse(p.vigenciaComprobada)
        assertNull(p.receivedAtLedger)
    }

    // ── 8. Estimación local ─────────────────────────────────────────────────

    @Test
    fun `8 sin red el margen crece con la edad del ancla`() = runTest {
        val estimate = exp - 1_000L
        val horas24 = 24L * 3_600_000L
        // Ancla recién tomada: 1 000 ledgers de colchón bastan (>= 360).
        assertEquals(RolRecibido.COBRO, verify(ledgerActual = null, estimate = estimate, anchorAgeMs = 0L).record().rol)
        assertEquals(RolRecibido.COBRO, verify(ledgerActual = null, estimate = estimate, anchorAgeMs = null).record().rol)
        // 24 h sin ver la red: 360 + 1 920 = 2 280 > 1 000.
        assertEquals(
            ReceivedSobreVerifier.MOTIVO_VENCE_PRONTO,
            motivo(verify(ledgerActual = null, estimate = estimate, anchorAgeMs = horas24)),
        )
        assertEquals(estimate, verify(ledgerActual = null, estimate = estimate, anchorAgeMs = 0L).record().receivedAtLedger)
        // Vencido según la estimación.
        assertEquals(ReceivedSobreVerifier.MOTIVO_EXPIRADO, motivo(verify(ledgerActual = null, estimate = exp)))
    }

    @Test
    fun `el margen es 360 mas la deriva de la edad del ancla`() {
        assertEquals(360L, ReceivedSobreVerifier.margen(null))
        assertEquals(360L, ReceivedSobreVerifier.margen(0L))
        assertEquals(360L, ReceivedSobreVerifier.margen(-5_000L))
        assertEquals(360L + 80L, ReceivedSobreVerifier.margen(3_600_000L))
        assertEquals(360L + 1_920L, ReceivedSobreVerifier.margen(24L * 3_600_000L))
    }

    @Test
    fun `el ledger real manda sobre la estimacion y no arrastra la deriva del ancla`() = runTest {
        // La estimación diría "vencido", pero el ledger real (exacto) dice que sobra tiempo.
        val r = verify(ledgerActual = 6_000_000L, estimate = exp + 50, anchorAgeMs = 24L * 3_600_000L).record()
        assertEquals(6_000_000L, r.receivedAtLedger)
        // Con el ledger real no se aplica la deriva: exp - ledger = 400 >= 360 aunque el ancla sea de hace 24 h.
        assertEquals(RolRecibido.COBRO, verify(ledgerActual = exp - 400, anchorAgeMs = 24L * 3_600_000L).record().rol)
    }

    // ── 9. Sobres inválidos ─────────────────────────────────────────────────

    @Test
    fun `9 un texto que no es un sobre se rechaza`() = runTest {
        assertEquals(SobreValidator.MOTIVO_NO_SOBRE, motivo(verify(text = "hola")))
        assertEquals(SobreValidator.MOTIVO_NO_SOBRE, motivo(verify(text = "")))
        assertEquals(SobreValidator.MOTIVO_NO_SOBRE, motivo(verify(text = s("entry_signed_xdr")))) // XDR sin prefijo
        assertEquals(SobreValidator.MOTIVO_NO_SOBRE, motivo(verify(text = "raiz:pay?to=$merchant&amount=5")))
    }

    @Test
    fun `9 ningun byte alterado produce un sobre distinto aceptado`() = runTest {
        val bytes = Base64.getUrlDecoder().decode(s("sobre_qr").removePrefix(SobreCodec.PREFIX))
        assertEquals(912, bytes.size)
        var rechazados = 0
        for (i in bytes.indices) {
            val alterado = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val text = SobreCodec.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(alterado)
            when (val c = verify(text = text)) {
                is RecibidoCheck.Rechazado -> rechazados++
                // Un byte de relleno XDR puede no cambiar nada; entonces el registro es IDÉNTICO al original.
                is RecibidoCheck.Aceptado ->
                    assertEquals("byte $i: aceptado pero distinto", s("entry_signed_xdr"), c.record.authXdr)
                is RecibidoCheck.YaRegistrado -> fail("byte $i: no había nada registrado")
            }
        }
        assertTrue("casi todos los bytes están protegidos ($rechazados de ${bytes.size})", rechazados >= bytes.size - 20)
    }

    @Test
    fun `una tarifa o una red distintas rechazan el sobre`() = runTest {
        assertEquals(SobreValidator.MOTIVO_REPARTO, motivo(verify(fee = 100)))
        assertEquals(SobreValidator.MOTIVO_FIRMA, motivo(verify(red = Network.PUBLIC)))
    }

    @Test
    fun `un nonce negativo int64 se acepta, forma el id con el signo y sobrevive al codec`() = runTest {
        val v = fx["variants"]!!.jsonObject["negative_nonce"]!!.jsonObject
        val negativo = v["nonce"]!!.jsonPrimitive.content.toLong()
        assertTrue(negativo < 0)
        val entry = SorobanAuthorizationEntryXdr.fromXdrBase64(v["entry_signed_xdr"]!!.jsonPrimitive.content)
        val r = verify(text = SobreCodec.encode(entry)).record()
        assertEquals(negativo, r.nonce)
        assertEquals("$payer:$negativo", r.id)
        assertEquals(listOf(r), ReceivedSobreCodec.decode(ReceivedSobreCodec.encode(listOf(r))))
    }
}
