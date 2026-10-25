package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.Auth
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.xdr.SorobanAuthorizationEntryXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizedInvocationXdr
import com.soneso.stellar.sdk.xdr.SorobanCredentialsXdr
import com.soneso.stellar.sdk.xdr.XdrWriter
import com.soneso.stellar.sdk.xdr.fromXdrBase64
import com.soneso.stellar.sdk.xdr.toXdrBase64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Spike B1 del pago diferido (HackMeridian 2026): el pagador Kotlin reproduce **byte a byte** la
 * `SorobanAuthorizationEntry` de `pay_merchant` generada con `@stellar/stellar-sdk` 17
 * (`scripts/gen-auth-fixture.mjs` → `src/test/resources/deferred_auth_fixture.json`).
 *
 * Si algo difiere, el mensaje dice en qué byte. Se corrige Kotlin, nunca el fixture a mano
 * (se regenera con el script, que además contrasta el árbol con la simulación real del host).
 * Cubre los tests 0–5 y 7 de la spec §8 del lado del pagador.
 */
class DeferredAuthSpikeTest {

    private val fx: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/deferred_auth_fixture.json")) {
            "Falta deferred_auth_fixture.json: ejecuta node scripts/gen-auth-fixture.mjs"
        }
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun str(key: String): String = fx[key]!!.jsonPrimitive.content
    private fun int(key: String): Int = fx[key]!!.jsonPrimitive.int
    private fun variant(name: String): JsonObject = fx["variants"]!!.jsonObject[name]!!.jsonObject

    private val network = Network.TESTNET
    private val ids get() = DeferredIds(pool = str("pool"), usdcSac = str("usdc_sac"), admin = str("admin"))
    private val nonce get() = str("nonce").toLong()
    private val exp get() = int("signature_expiration_ledger").toLong()

    private fun invocation(
        amount: Long = str("amount").toLong(),
        tipBps: Int = int("tip_bps"),
        feeBps: Int = int("fee_bps"),
        merchant: String = str("merchant"),
    ): SorobanAuthorizedInvocationXdr =
        AuthTreeBuilder.payMerchantInvocation(str("payer_public"), merchant, amount, tipBps, feeBps, ids)

    private suspend fun payer(): KeyPair = KeyPair.fromSecretSeed(hexToBytes(str("payer_seed_hex")))

    // ── 0. Árbol §2.1 ───────────────────────────────────────────────────────

    @Test
    fun `0 split reparte igual que el contrato`() {
        val s = AuthTreeBuilder.split(str("amount").toLong(), int("tip_bps"), int("fee_bps"))
        assertEquals(str("tip").toLong(), s.tip)
        assertEquals(str("fee").toLong(), s.fee)
        assertEquals(str("to_merchant").toLong(), s.toMerchant)
    }

    @Test
    fun `0 arbol de pay_merchant byte a byte con el fixture`() {
        val inv = invocation()
        assertEquals(3, inv.subInvocations.size)
        assertSameBytes("invocation", b64(str("invocation_xdr")), inv.bytes())
    }

    @Test
    fun `0 el fixture apunta a los mismos contratos que la app`() {
        // El working dir de los tests unitarios es android/app: leemos el asset directamente.
        val asset = File("src/main/assets/deployments.json")
        if (!asset.exists()) return
        val d = Json.parseToJsonElement(asset.readText()).jsonObject
        assertEquals(d["pool"]!!.jsonPrimitive.content, str("pool"))
        assertEquals(d["usdc_sac"]!!.jsonPrimitive.content, str("usdc_sac"))
        assertEquals(d["admin"]!!.jsonPrimitive.content, str("admin"))
        assertEquals(d["protocol_fee_bps"]!!.jsonPrimitive.int, int("fee_bps"))
    }

    // ── 1–3. Entrada, preimage, firma ───────────────────────────────────────

    @Test
    fun `1 entrada sin firmar byte a byte`() = runTest {
        val kp = payer()
        assertEquals(str("payer_public"), kp.getAccountId())
        val unsigned = DeferredAuthSigner.unsignedEntry(kp.getAccountId(), nonce, exp, invocation())
        assertEquals(str("entry_unsigned_xdr"), unsigned.toXdrBase64())
    }

    @Test
    fun `2 preimage y hash coinciden`() = runTest {
        assertEquals(str("network_id_hex"), hex(network.networkId()))
        val preimage = DeferredAuthSigner.preimage(network.networkId(), nonce, exp, invocation())
        assertSameBytes("preimage", b64(str("preimage_xdr")), DeferredAuthSigner.xdrBytes(preimage))
        assertEquals(str("payload_hash_hex"), hex(DeferredAuthSigner.payloadHash(network, nonce, exp, invocation())))
    }

    @Test
    fun `3 firma y entrada firmada byte a byte`() = runTest {
        val kp = payer()
        val unsigned = DeferredAuthSigner.unsignedEntry(kp.getAccountId(), nonce, exp, invocation())
        val signed = DeferredAuthSigner.sign(unsigned, kp, network)
        assertEquals(str("payload_hash_hex"), hex(signed.payloadHash))
        assertArrayEquals(hexToBytes(str("signature_hex")), signed.signature)
        assertEquals(
            str("signature_scval_xdr"),
            DeferredAuthSigner.signatureScVal(kp.getPublicKey(), signed.signature).toXdrBase64(),
        )
        assertEquals(str("entry_signed_xdr"), signed.entry.toXdrBase64())
    }

    @Test
    fun `4 coincide con Auth authorizeEntry del SDK`() = runTest {
        val kp = payer()
        val unsigned = DeferredAuthSigner.unsignedEntry(kp.getAccountId(), nonce, exp, invocation())
        val viaSdk = Auth.authorizeEntry(unsigned, kp, exp, network)
        assertEquals(str("entry_signed_xdr"), viaSdk.toXdrBase64())
    }

    @Test
    fun `5 round trip del XDR firmado`() {
        val entry = SorobanAuthorizationEntryXdr.fromXdrBase64(str("entry_signed_xdr"))
        assertEquals(str("entry_signed_xdr"), entry.toXdrBase64())
        assertEquals(3, entry.rootInvocation.subInvocations.size)
        val creds = (entry.credentials as SorobanCredentialsXdr.Address).value
        assertEquals(nonce, creds.nonce.value)
        assertEquals(exp, creds.signatureExpirationLedger.value.toLong())
    }

    // ── 6. Verificación offline (spec §8.4 y §8.5) ──────────────────────────

    @Test
    fun `6 verify acepta la firma del fixture y rechaza otra red`() = runTest {
        val entry = SorobanAuthorizationEntryXdr.fromXdrBase64(str("entry_signed_xdr"))
        assertTrue(DeferredAuthSigner.verify(entry, network))
        assertFalse(DeferredAuthSigner.verify(entry, Network.PUBLIC))
    }

    @Test
    fun `6 el hash cambia si cambia nonce, expiracion o invocacion`() = runTest {
        val base = DeferredAuthSigner.payloadHash(network, nonce, exp, invocation())
        assertFalse(base.contentEquals(DeferredAuthSigner.payloadHash(network, nonce + 1, exp, invocation())))
        assertFalse(base.contentEquals(DeferredAuthSigner.payloadHash(network, nonce, exp + 1, invocation())))
        assertFalse(base.contentEquals(DeferredAuthSigner.payloadHash(network, nonce, exp, invocation(amount = str("amount").toLong() + 1))))
        assertFalse(base.contentEquals(DeferredAuthSigner.payloadHash(network, nonce, exp, invocation(merchant = str("admin")))))
        // Estable para los mismos datos.
        assertTrue(base.contentEquals(DeferredAuthSigner.payloadHash(network, nonce, exp, invocation())))
    }

    @Test
    fun `6 una firma alterada o de otra clave no verifica`() = runTest {
        val kp = payer()
        val signed = DeferredAuthSigner.sign(DeferredAuthSigner.unsignedEntry(kp.getAccountId(), nonce, exp, invocation()), kp, network)
        val creds = (signed.entry.credentials as SorobanCredentialsXdr.Address).value
        val tampered = signed.signature.copyOf().also { it[10] = (it[10].toInt() xor 0x01).toByte() }
        val badSig = signed.entry.copy(
            credentials = SorobanCredentialsXdr.Address(creds.copy(signature = DeferredAuthSigner.signatureScVal(kp.getPublicKey(), tampered))),
        )
        assertFalse(DeferredAuthSigner.verify(badSig, network))

        val other = KeyPair.random()
        val otherKey = signed.entry.copy(
            credentials = SorobanCredentialsXdr.Address(creds.copy(signature = DeferredAuthSigner.signatureScVal(other.getPublicKey(), signed.signature))),
        )
        assertFalse(DeferredAuthSigner.verify(otherKey, network))
        assertFalse(DeferredAuthSigner.verify(DeferredAuthSigner.unsignedEntry(kp.getAccountId(), nonce, exp, invocation()), network))
    }

    // ── 7. Variantes del árbol (spec §8.2, §8.3) y nonce negativo ───────────

    @Test
    fun `7 tip_bps 0 deja dos sub-invocaciones`() = checkVariant("tip0", expectedSubs = 2)

    @Test
    fun `7 fee_bps 0 deja dos sub-invocaciones`() = checkVariant("fee0", expectedSubs = 2)

    @Test
    fun `7 montos que no dividen exacto truncan como el contrato`() = checkVariant("trunc", expectedSubs = 3)

    @Test
    fun `7 monto minusculo trunca tip y fee a 0 y deja una sola sub-invocacion`() = checkVariant("tiny", expectedSubs = 1)

    @Test
    fun `7 nonce negativo firma igual que el SDK JS`() = runTest {
        val v = variant("negative_nonce")
        val negNonce = v["nonce"]!!.jsonPrimitive.content.toLong()
        assertTrue(negNonce < 0)
        val kp = payer()
        val preimage = DeferredAuthSigner.preimage(network.networkId(), negNonce, exp, invocation())
        assertSameBytes("preimage nonce negativo", b64(v["preimage_xdr"]!!.jsonPrimitive.content), DeferredAuthSigner.xdrBytes(preimage))
        val signed = DeferredAuthSigner.sign(DeferredAuthSigner.unsignedEntry(kp.getAccountId(), negNonce, exp, invocation()), kp, network)
        assertEquals(v["payload_hash_hex"]!!.jsonPrimitive.content, hex(signed.payloadHash))
        assertArrayEquals(hexToBytes(v["signature_hex"]!!.jsonPrimitive.content), signed.signature)
        assertEquals(v["entry_signed_xdr"]!!.jsonPrimitive.content, signed.entry.toXdrBase64())
        assertTrue(DeferredAuthSigner.verify(signed.entry, network))
    }

    private fun checkVariant(name: String, expectedSubs: Int) {
        val v = variant(name)
        val amount = v["amount"]!!.jsonPrimitive.content.toLong()
        val tipBps = v["tip_bps"]!!.jsonPrimitive.int
        val feeBps = v["fee_bps"]!!.jsonPrimitive.int
        val s = AuthTreeBuilder.split(amount, tipBps, feeBps)
        assertEquals(v["tip"]!!.jsonPrimitive.content.toLong(), s.tip)
        assertEquals(v["fee"]!!.jsonPrimitive.content.toLong(), s.fee)
        assertEquals(v["to_merchant"]!!.jsonPrimitive.content.toLong(), s.toMerchant)
        val inv = invocation(amount = amount, tipBps = tipBps, feeBps = feeBps)
        assertEquals(expectedSubs, v["sub_invocations"]!!.jsonPrimitive.int)
        assertEquals(expectedSubs, inv.subInvocations.size)
        assertSameBytes("variante $name", b64(v["invocation_xdr"]!!.jsonPrimitive.content), inv.bytes())
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun SorobanAuthorizedInvocationXdr.bytes(): ByteArray = XdrWriter().also { encode(it) }.toByteArray()

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertSameBytes(what: String, expected: ByteArray, actual: ByteArray) {
        if (expected.contentEquals(actual)) return
        val n = minOf(expected.size, actual.size)
        val i = (0 until n).firstOrNull { expected[it] != actual[it] } ?: n
        fail(
            "$what: difiere en el byte $i (esperado ${expected.size} bytes, obtenido ${actual.size}). " +
                "esperado[$i..]=${hex(expected.copyOfRange(i, minOf(i + 16, expected.size)))} " +
                "obtenido[$i..]=${hex(actual.copyOfRange(i, minOf(i + 16, actual.size)))}",
        )
    }
}
