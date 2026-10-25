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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sobre §5: serialización canónica byte a byte contra el fixture de `scripts/gen-auth-fixture.mjs`,
 * parse tolerante y validación JSON ↔ XDR (spec §8.7).
 */
class DeferredEnvelopeTest {

    private val fx: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/deferred_auth_fixture.json")) {
            "Falta deferred_auth_fixture.json: node scripts/gen-auth-fixture.mjs"
        }
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }
    private val env: JsonObject get() = fx["envelope"]!!.jsonObject
    private fun s(key: String) = fx[key]!!.jsonPrimitive.content
    private val network = Network.TESTNET
    private val ids get() = DeferredIds(pool = s("pool"), usdcSac = s("usdc_sac"), admin = s("admin"))

    private fun fixtureEnvelope(): DeferredEnvelope = DeferredEnvelope(
        v = env["v"]!!.jsonPrimitive.int,
        net = env["net"]!!.jsonPrimitive.content,
        payer = env["payer"]!!.jsonPrimitive.content,
        merchant = env["merchant"]!!.jsonPrimitive.content,
        amount = env["amount"]!!.jsonPrimitive.content,
        tipBps = env["tip_bps"]!!.jsonPrimitive.int,
        feeBps = env["fee_bps"]!!.jsonPrimitive.int,
        nonce = env["nonce"]!!.jsonPrimitive.content,
        exp = env["exp"]!!.jsonPrimitive.content.toLong(),
        l0 = env["l0"]!!.jsonPrimitive.content.toLong(),
        t0 = env["t0"]!!.jsonPrimitive.content.toLong(),
        auth = env["auth"]!!.jsonPrimitive.content,
    )

    // ── serialización ───────────────────────────────────────────────────────

    @Test
    fun `el sobre del fixture serializa byte a byte al base64url de 2043 chars`() {
        val e = fixtureEnvelope()
        assertEquals(Json.encodeToString(JsonObject.serializer(), env), e.toJson())
        assertEquals(s("envelope_b64url"), e.toBase64Url())
        assertEquals(2043, e.toBase64Url().length)
    }

    @Test
    fun `of construye el mismo sobre que el fixture`() {
        val entry = SorobanAuthorizationEntryXdr.fromXdrBase64(s("entry_signed_xdr"))
        val built = DeferredEnvelope.of(
            entry = entry,
            payer = s("payer_public"),
            merchant = s("merchant"),
            amountStroops = s("amount").toLong(),
            tipBps = fx["tip_bps"]!!.jsonPrimitive.int,
            feeBps = fx["fee_bps"]!!.jsonPrimitive.int,
            nonce = s("nonce").toLong(),
            exp = fx["signature_expiration_ledger"]!!.jsonPrimitive.content.toLong(),
            l0 = env["l0"]!!.jsonPrimitive.content.toLong(),
            t0 = env["t0"]!!.jsonPrimitive.content.toLong(),
            network = network,
        )
        assertEquals(fixtureEnvelope(), built)
        assertEquals(s("envelope_b64url"), built.toBase64Url())
    }

    @Test
    fun `parse del base64url devuelve los campos y un XDR con 3 sub-invocaciones`() {
        val parsed = DeferredEnvelope.parse(s("envelope_b64url")).getOrThrow()
        assertEquals(fixtureEnvelope(), parsed)
        assertEquals(3, parsed.entry().rootInvocation.subInvocations.size)
        assertEquals(s("amount").toLong(), parsed.amountStroops)
        assertEquals(s("nonce").toLong(), parsed.nonceLong)
    }

    @Test
    fun `parse acepta padding, espacios y JSON crudo, y nunca lanza con basura`() {
        val b64 = s("envelope_b64url")
        assertEquals(fixtureEnvelope(), DeferredEnvelope.parse("  $b64==\n").getOrThrow())
        assertEquals(fixtureEnvelope(), DeferredEnvelope.parse(fixtureEnvelope().toJson()).getOrThrow())
        assertTrue(DeferredEnvelope.parse("no es un sobre").isFailure)
        assertTrue(DeferredEnvelope.parse("").isFailure)
        assertTrue(DeferredEnvelope.parse("eyJ4IjoxfQ").isFailure) // {"x":1}
    }

    // ── validación JSON ↔ XDR ───────────────────────────────────────────────

    @Test
    fun `validate acepta el sobre del fixture`() = runTest {
        val check = DeferredEnvelopeValidator.validate(fixtureEnvelope(), ids, network)
        assertTrue("esperaba Ok, fue $check", check is EnvelopeCheck.Ok)
        val ok = check as EnvelopeCheck.Ok
        assertEquals(s("tip").toLong(), ok.split.tip)
        assertEquals(s("fee").toLong(), ok.split.fee)
        assertEquals(s("to_merchant").toLong(), ok.split.toMerchant)
    }

    @Test
    fun `monto distinto entre JSON y XDR es RECHAZADO`() = runTest {
        val e = fixtureEnvelope().copy(amount = (s("amount").toLong() + 1).toString())
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_ARBOL, DeferredEnvelopeValidator.validate(e, ids, network))
    }

    @Test
    fun `destinatario distinto entre JSON y XDR es RECHAZADO`() = runTest {
        val e = fixtureEnvelope().copy(merchant = s("admin"))
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_ARBOL, DeferredEnvelopeValidator.validate(e, ids, network))
    }

    @Test
    fun `tip_bps o fee_bps distintos son RECHAZADO`() = runTest {
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_ARBOL, DeferredEnvelopeValidator.validate(fixtureEnvelope().copy(tipBps = 0), ids, network))
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_ARBOL, DeferredEnvelopeValidator.validate(fixtureEnvelope().copy(feeBps = 0), ids, network))
    }

    @Test
    fun `nonce o exp del JSON distintos de las credenciales son RECHAZADO`() = runTest {
        val e = fixtureEnvelope()
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_NONCE_EXP, DeferredEnvelopeValidator.validate(e.copy(nonce = (e.nonce.toLong() + 1).toString()), ids, network))
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_NONCE_EXP, DeferredEnvelopeValidator.validate(e.copy(exp = e.exp + 1), ids, network))
    }

    @Test
    fun `otra red, otra version o pagador distinto son RECHAZADO`() = runTest {
        val e = fixtureEnvelope()
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_RED, DeferredEnvelopeValidator.validate(e, ids, Network.PUBLIC))
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_VERSION, DeferredEnvelopeValidator.validate(e.copy(v = 2), ids, network))
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_PAGADOR, DeferredEnvelopeValidator.validate(e.copy(payer = s("admin")), ids, network))
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_AUTH, DeferredEnvelopeValidator.validate(e.copy(auth = "AAAA"), ids, network))
    }

    @Test
    fun `firma alterada en el XDR es RECHAZADO con motivo firma invalida`() = runTest {
        val e = fixtureEnvelope()
        val entry = e.entry()
        val creds = (entry.credentials as SorobanCredentialsXdr.Address).value
        val other = KeyPair.random()
        val tamperedSig = hexToBytes(s("signature_hex")).also { it[5] = (it[5].toInt() xor 0x40).toByte() }
        val tampered = entry.copy(
            credentials = SorobanCredentialsXdr.Address(
                creds.copy(signature = DeferredAuthSigner.signatureScVal(hexToBytes(payerKeyHex()), tamperedSig)),
            ),
        )
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_FIRMA, DeferredEnvelopeValidator.validate(e.copy(auth = tampered.toXdrBase64()), ids, network))
        val otherKey = entry.copy(
            credentials = SorobanCredentialsXdr.Address(
                creds.copy(signature = DeferredAuthSigner.signatureScVal(other.getPublicKey(), hexToBytes(s("signature_hex")))),
            ),
        )
        assertRechazado(DeferredEnvelopeValidator.MOTIVO_FIRMA, DeferredEnvelopeValidator.validate(e.copy(auth = otherKey.toXdrBase64()), ids, network))
    }

    // ── densidad del QR ─────────────────────────────────────────────────────

    @Test
    fun `el QR del sobre del fixture cabe en version 38 con M y 33 con L`() {
        val b64 = s("envelope_b64url")
        val m = Encoder.encode(b64, ErrorCorrectionLevel.M).version.versionNumber
        val l = Encoder.encode(b64, ErrorCorrectionLevel.L).version.versionNumber
        assertTrue("v$m con M", m <= 38)
        assertTrue("v$l con L", l <= 33)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun payerKeyHex(): String =
        hex(KeyPair.fromAccountId(s("payer_public")).getPublicKey())

    private fun assertRechazado(motivo: String, check: EnvelopeCheck) {
        assertTrue("esperaba Rechazado($motivo), fue $check", check is EnvelopeCheck.Rechazado)
        assertEquals(motivo, (check as EnvelopeCheck.Rechazado).motivo)
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun hexToBytes(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
