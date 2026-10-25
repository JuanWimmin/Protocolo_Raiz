package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.FriendBot
import com.soneso.stellar.sdk.InvokeHostFunctionOperation
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.Transaction
import com.soneso.stellar.sdk.TransactionBuilder
import com.soneso.stellar.sdk.rpc.SorobanServer
import com.soneso.stellar.sdk.rpc.requests.SimulateTransactionRequest
import com.soneso.stellar.sdk.rpc.responses.GetTransactionResponse
import com.soneso.stellar.sdk.rpc.responses.GetTransactionStatus
import com.soneso.stellar.sdk.rpc.responses.SendTransactionStatus
import com.soneso.stellar.sdk.rpc.responses.SimulateTransactionResponse
import com.soneso.stellar.sdk.sep.sep05.Mnemonic
import com.soneso.stellar.sdk.xdr.HostFunctionXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizationEntryXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizedFunctionXdr
import com.soneso.stellar.sdk.xdr.SorobanCredentialsXdr
import com.soneso.stellar.sdk.xdr.fromXdrBase64
import com.soneso.stellar.sdk.xdr.toXdrBase64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Prueba de integración del spike B1 (pago diferido) contra **testnet**. APAGADA por defecto; se
 * activa solo con variables de entorno (nunca hay secretos en el repo):
 *
 * ```
 * RAIZ_DEFERRED_IT=1
 * RAIZ_DEFERRED_PAYER_MNEMONIC="12 o 24 palabras"      (o RAIZ_DEFERRED_PAYER_SECRET=S…)
 * gradlew.bat :app:testDebugUnitTest --tests "*DeferredAuthTestnetIT" --rerun -i      (desde android/)
 * ```
 * `--rerun` porque Gradle no rastrea las variables de entorno del JVM de test.
 *
 * Qué demuestra (spec `docs/pago_diferido_v0.md` §1, §2, §6):
 * - (b) el pagador construye y firma la entrada **sin tocar la red** entre construir y firmar;
 * - (c) OTRA cuenta (pasarela desechable fondeada por friendbot) reconstruye la invocación desde el
 *   XDR, adjunta la entrada, simula en modo enforce, paga el fee y envía → hash real, fuente = pasarela;
 * - (d) el mismo sobre reenviado falla (nonce consumido);
 * - (e) una entrada con expiración pasada falla;
 * - (f) tope de expiración que acepta la red, medido por simulación (sin gastar).
 *
 * Gasta: una tx de 0,5 USDC + 2 % del pagador y el fee en XLM de la pasarela.
 */
class DeferredAuthTestnetIT {

    private class Ctx(
        val server: SorobanServer,
        val payer: KeyPair,
        val gateway: KeyPair,
        val ids: DeferredIds,
        val feeBps: Int,
        val merchant: String,
    ) {
        /** Entrada firmada por el pagador con nonce nuevo; ninguna llamada de red. */
        suspend fun signFresh(exp: Long): SorobanAuthorizationEntryXdr {
            val invocation = AuthTreeBuilder.payMerchantInvocation(payer.getAccountId(), merchant, AMOUNT, TIP_BPS, feeBps, ids)
            val unsigned = DeferredAuthSigner.unsignedEntry(payer.getAccountId(), DeferredAuthSigner.newNonce(), exp, invocation)
            return DeferredAuthSigner.sign(unsigned, payer, Network.TESTNET).entry
        }

        /**
         * Lo que hace la pasarela: reconstruye la invocación DESDE EL XDR del sobre (nunca desde el JSON),
         * la mete en una tx cuya fuente es ella misma y simula en modo enforce (la auth ya viene firmada).
         */
        suspend fun simulateFromGateway(entry: SorobanAuthorizationEntryXdr): Pair<Transaction, SimulateTransactionResponse> {
            val args = (entry.rootInvocation.function as SorobanAuthorizedFunctionXdr.ContractFn).value
            val op = InvokeHostFunctionOperation(hostFunction = HostFunctionXdr.InvokeContract(args), auth = listOf(entry))
            val source = server.getAccount(gateway.getAccountId())
            val tx = TransactionBuilder(source, Network.TESTNET)
                .addOperation(op)
                .setBaseFee(BASE_FEE)
                .setTimeout(TX_TIMEOUT_SEC)
                .build()
            val sim = server.simulateTransaction(tx, null, SimulateTransactionRequest.AuthMode.ENFORCE)
            return tx to sim
        }

        suspend fun awaitTx(hash: String): GetTransactionResponse {
            repeat(POLL_ATTEMPTS) {
                val r = server.getTransaction(hash)
                if (r.status != GetTransactionStatus.NOT_FOUND) return r
                delay(POLL_INTERVAL_MS)
            }
            throw AssertionError("Sin respuesta final para $hash tras ${POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000} s")
        }
    }

    companion object {
        private const val RPC = "https://soroban-testnet.stellar.org"
        private const val AMOUNT = 5_000_000L // 0,5 USDC
        private const val TIP_BPS = 200
        private const val WINDOW_LEDGERS = 100L
        private const val BASE_FEE = 500_000L
        private const val TX_TIMEOUT_SEC = 120L
        private const val POLL_ATTEMPTS = 30
        private const val POLL_INTERVAL_MS = 3_000L
        private const val SEND_ATTEMPTS = 3

        /** `StateArchivalSettings.max_entry_ttl` de testnet (leído el 25-oct-2026). El nonce vive hasta E. */
        private const val MAX_ENTRY_TTL = 3_110_400L

        private val enabled = System.getenv("RAIZ_DEFERRED_IT") == "1"

        private val ctxLazy = lazy { runBlocking { buildCtx() } }
        private fun ctx(): Ctx = ctxLazy.value

        private suspend fun buildCtx(): Ctx {
            val fxText = requireNotNull(DeferredAuthTestnetIT::class.java.classLoader.getResourceAsStream("deferred_auth_fixture.json")) {
                "Falta deferred_auth_fixture.json"
            }.bufferedReader().readText()
            val fx = Json.parseToJsonElement(fxText).jsonObject
            val ids = DeferredIds(
                pool = fx["pool"]!!.jsonPrimitive.content,
                usdcSac = fx["usdc_sac"]!!.jsonPrimitive.content,
                admin = fx["admin"]!!.jsonPrimitive.content,
            )
            val payer = payerFromEnv()
            val gateway = KeyPair.random()
            val server = SorobanServer(RPC)
            assertTrue("friendbot no fondeó la pasarela", FriendBot.fundTestnetAccount(gateway.getAccountId()))
            retry(times = 10, delayMs = 2_000) { server.getAccount(gateway.getAccountId()) }
            println("IT pagador=${payer.getAccountId()} pasarela=${gateway.getAccountId()} fee_bps=${fx["fee_bps"]!!.jsonPrimitive.int}")
            return Ctx(server, payer, gateway, ids, fx["fee_bps"]!!.jsonPrimitive.int, fx["merchant"]!!.jsonPrimitive.content)
        }

        private suspend fun payerFromEnv(): KeyPair {
            System.getenv("RAIZ_DEFERRED_PAYER_MNEMONIC")?.takeIf { it.isNotBlank() }?.let { words ->
                return Mnemonic.from(words.trim()).use { it.getKeyPair(0) }
            }
            System.getenv("RAIZ_DEFERRED_PAYER_SECRET")?.takeIf { it.isNotBlank() }?.let { secret ->
                return KeyPair.fromSecretSeed(secret.trim())
            }
            throw AssertionError("Define RAIZ_DEFERRED_PAYER_MNEMONIC o RAIZ_DEFERRED_PAYER_SECRET")
        }

        private suspend fun <T> retry(times: Int, delayMs: Long, block: suspend () -> T): T {
            var last: Throwable? = null
            repeat(times) {
                try {
                    return block()
                } catch (t: Throwable) {
                    last = t
                    delay(delayMs)
                }
            }
            throw last ?: IllegalStateException("retry sin intentos")
        }
    }

    @Test
    fun `b c d firma sin red, otra cuenta envia y el replay falla`() = runBlocking {
        assumeTrue("RAIZ_DEFERRED_IT != 1: prueba de integración apagada", enabled)
        val c = ctx()

        // (b) Último dato de red que el pagador conoce ANTES de quedarse sin señal.
        val latest = c.server.getLatestLedger().sequence
        val exp = latest + WINDOW_LEDGERS
        val nonce = DeferredAuthSigner.newNonce()
        // ── desde aquí el pagador no toca la red ──
        val invocation = AuthTreeBuilder.payMerchantInvocation(c.payer.getAccountId(), c.merchant, AMOUNT, TIP_BPS, c.feeBps, c.ids)
        val signed = DeferredAuthSigner.sign(
            DeferredAuthSigner.unsignedEntry(c.payer.getAccountId(), nonce, exp, invocation),
            c.payer,
            Network.TESTNET,
        )
        val sobre = signed.entry.toXdrBase64()
        println("sobre firmado (${sobre.length} chars base64, exp=$exp, nonce=$nonce)")
        // ── la pasarela recibe el sobre ──
        val entry = SorobanAuthorizationEntryXdr.fromXdrBase64(sobre)
        assertTrue("la pasarela verifica la firma offline", DeferredAuthSigner.verify(entry, Network.TESTNET))

        // (c) Otra cuenta simula (enforce), paga el fee y envía.
        val (tx, sim) = c.simulateFromGateway(entry)
        assertNull("la simulación enforce debe pasar; error=${sim.error}", sim.error)
        println("simulación OK: minResourceFee=${sim.minResourceFee} latestLedger=${sim.latestLedger}")
        val prepared = c.server.prepareTransaction(tx, sim)
        prepared.sign(c.gateway)
        val hash = prepared.hashHex()
        var status: SendTransactionStatus? = null
        var errorXdr: String? = null
        for (attempt in 1..SEND_ATTEMPTS) {
            val sent = c.server.sendTransaction(prepared)
            status = sent.status
            errorXdr = sent.errorResultXdr
            println("sendTransaction intento $attempt: ${sent.status} hash=$hash error=${sent.errorResultXdr}")
            // Solo TRY_AGAIN_LATER justifica reenviar el MISMO sobre (patrón de SorobanClient.sacTransfer).
            if (sent.status != SendTransactionStatus.TRY_AGAIN_LATER) break
            delay(2_000)
        }
        assertTrue(
            "sendTransaction devolvió $status (errorResultXdr=$errorXdr)",
            status == SendTransactionStatus.PENDING || status == SendTransactionStatus.DUPLICATE,
        )
        val result = c.awaitTx(hash)
        assertEquals("tx fallida: resultXdr=${result.resultXdr}", GetTransactionStatus.SUCCESS, result.status)
        println("CONFIRMADA ledger=${result.ledger}: https://stellar.expert/explorer/testnet/tx/$hash")

        // Lo que quedó on-chain: fuente = pasarela, auth = pagador.
        val envelope = Transaction.fromEnvelopeXdr(prepared.toEnvelopeXdrBase64(), Network.TESTNET)
        assertEquals(c.gateway.getAccountId(), envelope.sourceAccount)
        val op = envelope.operations.single() as InvokeHostFunctionOperation
        val creds = (op.auth.single().credentials as SorobanCredentialsXdr.Address).value
        assertTrue(DeferredAuthSigner.ed25519Of(creds.address)!!.contentEquals(c.payer.getPublicKey()))
        assertEquals(nonce, creds.nonce.value)

        // (d) Replay: el mismo sobre en una tx nueva de la pasarela → la red lo rechaza (nonce consumido).
        val (_, replay) = c.simulateFromGateway(entry)
        println("replay del mismo sobre: ${replay.error}")
        assertNotNull("el replay debe fallar en simulación", replay.error)
    }

    @Test
    fun `e f expiracion pasada falla y tope de expiracion medido`() = runBlocking {
        assumeTrue("RAIZ_DEFERRED_IT != 1: prueba de integración apagada", enabled)
        val c = ctx()

        // (e) Expirada: E = L − 1.
        val latest = c.server.getLatestLedger().sequence
        val (_, expired) = c.simulateFromGateway(c.signFresh(exp = latest - 1))
        println("expirada (E=${latest - 1}, L=$latest): ${expired.error}")
        assertNotNull("una entrada expirada debe fallar", expired.error)

        // (f) Tope: el nonce se guarda como entrada temporal viva hasta E, así que E − L está acotado por
        // max_entry_ttl. Se juzga por Δ_efectivo = E − latestLedger de la propia simulación (la red avanza
        // un ledger cada 5 s entre leer y simular).
        var maxOk = -1L
        var minFail = Long.MAX_VALUE
        for (delta in listOf(MAX_ENTRY_TTL - 2, MAX_ENTRY_TTL - 1, MAX_ENTRY_TTL, MAX_ENTRY_TTL + 1)) {
            val l = c.server.getLatestLedger().sequence
            val e = l + delta
            val (_, sim) = c.simulateFromGateway(c.signFresh(exp = e))
            val effective = e - (sim.latestLedger ?: l)
            val ok = sim.error == null
            println("Δ_pedido=$delta Δ_efectivo=$effective → ${if (ok) "ACEPTADA" else "RECHAZADA: ${sim.error?.take(200)}"}")
            if (ok) maxOk = maxOf(maxOk, effective) else minFail = minOf(minFail, effective)
        }
        println("TOPE MEDIDO: mayor Δ aceptado=$maxOk · menor Δ rechazado=$minFail · max_entry_ttl=$MAX_ENTRY_TTL")
        assertTrue("alguna expiración lejana debe aceptarse", maxOk > 0)
        if (minFail != Long.MAX_VALUE) assertTrue("el tope debe ser monótono", minFail > maxOk)
        else fail("ninguna expiración fue rechazada: subir el Δ máximo de la prueba")
    }
}
