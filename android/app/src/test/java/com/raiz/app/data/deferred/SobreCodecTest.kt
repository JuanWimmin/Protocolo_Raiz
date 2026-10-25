package com.raiz.app.data.deferred

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.xdr.SorobanAuthorizationEntryXdr
import com.soneso.stellar.sdk.xdr.SorobanCredentialsXdr
import com.soneso.stellar.sdk.xdr.fromXdrBase64
import com.soneso.stellar.sdk.xdr.toXdrBase64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * El sobre que viaja (`raiz:sobre:1:` + base64url del XDR firmado): codec byte a byte contra el fixture,
 * densidad del QR y verificación sin red ([SobreValidator], comprobaciones 1–5 y 9 de A3 del documento
 * "Pago diferido por enjambre").
 */
class SobreCodecTest {

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
    private fun entry(): SorobanAuthorizationEntryXdr = SorobanAuthorizationEntryXdr.fromXdrBase64(s("entry_signed_xdr"))

    // ── codec ───────────────────────────────────────────────────────────────

    @Test
    fun `encode es prefijo mas base64url del XDR y mide 1229 chars como el fixture`() {
        val qr = SobreCodec.encode(entry())
        val esperado = SobreCodec.PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(s("entry_signed_xdr")))
        assertEquals(esperado, qr)
        assertEquals(s("sobre_qr"), qr)
        assertEquals(1229, qr.length)
        assertTrue(SobreCodec.isSobre(qr))
    }

    @Test
    fun `decode hace round trip y tolera espacios, saltos y relleno`() {
        val qr = SobreCodec.encode(entry())
        assertEquals(s("entry_signed_xdr"), SobreCodec.decode(qr)!!.toXdrBase64())
        assertEquals(s("entry_signed_xdr"), SobreCodec.decode("  $qr==\n")!!.toXdrBase64())
        val conSalto = qr.substring(0, 400) + "\n" + qr.substring(400)
        assertEquals(s("entry_signed_xdr"), SobreCodec.decode(conSalto)!!.toXdrBase64())
    }

    @Test
    fun `decode devuelve null con basura, sin prefijo, vacio o demasiado largo`() {
        assertNull(SobreCodec.decode("no es un sobre"))
        assertNull(SobreCodec.decode(""))
        assertNull(SobreCodec.decode(SobreCodec.PREFIX))
        assertNull(SobreCodec.decode(SobreCodec.PREFIX + "AAAA"))
        assertNull(SobreCodec.decode(s("entry_signed_xdr"))) // XDR sin prefijo: no es un sobre
        assertNull(SobreCodec.decode(SobreCodec.PREFIX + "A".repeat(SobreCodec.MAX_CHARS)))
        assertNull(SobreCodec.decode("raiz:pay?to=GABC&amount=5"))
    }

    @Test
    fun `el QR del sobre cabe en version 29 con M y 25 con L`() {
        val qr = SobreCodec.encode(entry())
        assertTrue(Encoder.encode(qr, ErrorCorrectionLevel.M).version.versionNumber <= 29)
        assertTrue(Encoder.encode(qr, ErrorCorrectionLevel.L).version.versionNumber <= 25)
    }

    // ── PayArgs ─────────────────────────────────────────────────────────────

    @Test
    fun `PayArgs extrae los argumentos firmados de la raiz`() {
        val args = PayArgs.from(entry().rootInvocation, s("pool"))
        assertNotNull(args)
        assertEquals(s("payer_public"), args!!.payer)
        assertEquals(s("merchant"), args.merchant)
        assertEquals(s("amount").toLong(), args.amount)
        assertEquals(fx["tip_bps"]!!.jsonPrimitive.int, args.tipBps)
        // Otro Pool, o una sub-invocación (transfer) como raíz: no es un pago a este Pool.
        assertNull(PayArgs.from(entry().rootInvocation, s("usdc_sac")))
        assertNull(PayArgs.from(entry().rootInvocation.subInvocations.first(), s("pool")))
    }

    // ── validación ──────────────────────────────────────────────────────────

    @Test
    fun `validate acepta el sobre del fixture y devuelve reparto, nonce y expiracion`() = runTest {
        val check = SobreValidator.validateText(s("sobre_qr"), ids, feeBps, network)
        assertTrue("esperaba Ok, fue $check", check is SobreCheck.Ok)
        val ok = check as SobreCheck.Ok
        assertEquals(s("merchant"), ok.args.merchant)
        assertEquals(s("amount").toLong(), ok.args.amount)
        assertEquals(s("tip").toLong(), ok.split.tip)
        assertEquals(s("fee").toLong(), ok.split.fee)
        assertEquals(s("to_merchant").toLong(), ok.split.toMerchant)
        assertEquals(s("nonce").toLong(), ok.nonce)
        assertEquals(fx["signature_expiration_ledger"]!!.jsonPrimitive.content.toLong(), ok.expirationLedger)
    }

    @Test
    fun `texto que no es un sobre es RECHAZADO`() = runTest {
        assertRechazado(SobreValidator.MOTIVO_NO_SOBRE, SobreValidator.validateText("hola", ids, feeBps, network))
    }

    @Test
    fun `otra tarifa del Pool rompe el reparto y es RECHAZADO`() = runTest {
        assertRechazado(SobreValidator.MOTIVO_REPARTO, SobreValidator.validate(entry(), ids, feeBps = 0, network = network))
        assertRechazado(SobreValidator.MOTIVO_REPARTO, SobreValidator.validate(entry(), ids.copy(admin = s("merchant")), feeBps, network))
    }

    @Test
    fun `sub-invocacion alterada (reparto distinto a los argumentos) es RECHAZADO`() = runTest {
        val e = entry()
        val payer = s("payer_public")
        // Raíz con amount + 1 pero los transfers originales: el reparto ya no coincide con los argumentos.
        val alterada = AuthTreeBuilder.payMerchantInvocation(payer, s("merchant"), s("amount").toLong() + 1, 200, feeBps, ids)
            .copy(subInvocations = e.rootInvocation.subInvocations)
        assertRechazado(SobreValidator.MOTIVO_REPARTO, SobreValidator.validate(e.copy(rootInvocation = alterada), ids, feeBps, network))
    }

    @Test
    fun `arbol coherente pero no firmado, otra red o firma alterada son RECHAZADO por firma`() = runTest {
        val e = entry()
        val payer = s("payer_public")
        // Todo el árbol con amount + 1 (coherente) pero la firma es la del árbol original.
        val coherente = AuthTreeBuilder.payMerchantInvocation(payer, s("merchant"), s("amount").toLong() + 1, 200, feeBps, ids)
        assertRechazado(SobreValidator.MOTIVO_FIRMA, SobreValidator.validate(e.copy(rootInvocation = coherente), ids, feeBps, network))
        assertRechazado(SobreValidator.MOTIVO_FIRMA, SobreValidator.validate(e, ids, feeBps, Network.PUBLIC))
        val creds = (e.credentials as SorobanCredentialsXdr.Address).value
        val sig = hexToBytes(s("signature_hex")).also { it[3] = (it[3].toInt() xor 0x10).toByte() }
        val pub = KeyPair.fromAccountId(payer).getPublicKey()
        val tampered = e.copy(credentials = SorobanCredentialsXdr.Address(creds.copy(signature = DeferredAuthSigner.signatureScVal(pub, sig))))
        assertRechazado(SobreValidator.MOTIVO_FIRMA, SobreValidator.validate(tampered, ids, feeBps, network))
    }

    @Test
    fun `firmante distinto del pagador del arbol es RECHAZADO`() = runTest {
        val e = entry()
        val otro = KeyPair.random().getAccountId()
        val arbolDeOtro = AuthTreeBuilder.payMerchantInvocation(otro, s("merchant"), s("amount").toLong(), 200, feeBps, ids)
        assertRechazado(SobreValidator.MOTIVO_PAGADOR, SobreValidator.validate(e.copy(rootInvocation = arbolDeOtro), ids, feeBps, network))
    }

    @Test
    fun `raiz que no es pay_merchant del Pool es RECHAZADO`() = runTest {
        val e = entry()
        val transferComoRaiz = e.rootInvocation.subInvocations.first()
        assertRechazado(SobreValidator.MOTIVO_NO_POOL, SobreValidator.validate(e.copy(rootInvocation = transferComoRaiz), ids, feeBps, network))
    }

    private fun assertRechazado(motivo: String, check: SobreCheck) {
        assertTrue("esperaba Rechazado($motivo), fue $check", check is SobreCheck.Rechazado)
        assertEquals(motivo, (check as SobreCheck.Rechazado).motivo)
    }

    private fun hexToBytes(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
