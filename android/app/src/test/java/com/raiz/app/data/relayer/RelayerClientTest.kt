package com.raiz.app.data.relayer

import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM puros de [RelayerClient] con Ktor MockEngine (sin red real).
 * Cubre el mapeo HTTP → [RaizResult] descrito en
 * `raiz-relayer/docs/SESION_B_APP.md` § 2, la forma exacta de los bodies
 * (tipos string/numérico que espera el zod del relayer) y la semántica de
 * `idempotency-key` por intento (H1).
 */
class RelayerClientTest {

    private val barrioId = "a".repeat(64)
    private val txHash64 = "abcdef0123456789".repeat(4)

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, listOf("application/json"))

    private fun buildClient(engine: MockEngine): RelayerClient {
        val http = HttpClient(engine) {
            // Mismo Json que el HttpClient de producción (DataModule) — una sola definición.
            install(ContentNegotiation) { json(RelayerJson) }
        }
        return RelayerClient(http, baseUrl = BASE_URL, appKey = APP_KEY)
    }

    /** Body JSON tal como sale por el cable (lo que verá el zod del relayer). */
    private fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    private fun okEngine(content: String = """{"ok":true,"txHash":"abc123","ledger":42}""") = MockEngine {
        respond(content = content, status = HttpStatusCode.OK, headers = jsonHeaders())
    }

    private fun errorEngine(status: HttpStatusCode, code: String, message: String, details: String? = null) =
        MockEngine {
            val detailsJson = details?.let { ""","details":$it""" } ?: ""
            respond(
                content = """{"ok":false,"error":{"code":"$code","message":"$message","retryable":false$detailsJson}}""",
                status = status,
                headers = jsonHeaders(),
            )
        }

    // ── Éxitos ──────────────────────────────────────────────────────────

    @Test
    fun registerMerchant200DevuelveSuccessConTxHash() = runTest {
        val client = buildClient(okEngine())

        val result = client.registerMerchant(
            address = "GABCDEF",
            name = "Cafe Don Aurelio",
            barrioId = barrioId,
            latE6 = 1_042_150,
            lngE6 = -7_554_780,
            categorySymbol = "cafe",
        )

        assertTrue(result is RaizResult.Success)
        assertEquals("abc123", (result as RaizResult.Success).data)
    }

    @Test
    fun registerMerchantEnviaClavesExactasYTiposCorrectos() = runTest {
        var body: JsonObject? = null
        val engine = MockEngine { request ->
            body = bodyOf(request)
            respond(content = """{"ok":true,"txHash":"abc123","ledger":1}""", status = HttpStatusCode.OK, headers = jsonHeaders())
        }
        val client = buildClient(engine)

        client.registerMerchant("GABCDEF", "Cafe Don Aurelio", barrioId, 1_042_150, -7_554_780, "cafe")

        val json = requireNotNull(body)
        // Claves EXACTAS del schema zod de /v1/register-merchant: ni una más, ni una menos.
        assertEquals(setOf("address", "name", "barrioId", "latE6", "lngE6", "category"), json.keys)
        assertEquals("GABCDEF", json.getValue("address").jsonPrimitive.content)
        assertEquals("Cafe Don Aurelio", json.getValue("name").jsonPrimitive.content)
        assertEquals(barrioId, json.getValue("barrioId").jsonPrimitive.content)
        // latE6/lngE6 numéricos (no strings): el relayer los valida como int.
        assertFalse("latE6 debe ser numérico", json.getValue("latE6").jsonPrimitive.isString)
        assertFalse("lngE6 debe ser numérico", json.getValue("lngE6").jsonPrimitive.isString)
        assertEquals(1_042_150, json.getValue("latE6").jsonPrimitive.int)
        assertEquals(-7_554_780, json.getValue("lngE6").jsonPrimitive.int)
        assertEquals("cafe", json.getValue("category").jsonPrimitive.content)
    }

    @Test
    fun vaultDepositEnviaAmountStroopsComoStringDecimal() = runTest {
        var body: JsonObject? = null
        val engine = MockEngine { request ->
            body = bodyOf(request)
            respond(content = """{"ok":true,"txHash":"abc123","ledger":1}""", status = HttpStatusCode.OK, headers = jsonHeaders())
        }
        val client = buildClient(engine)

        val result = client.vaultDeposit(barrioId, 20_000_000L)

        assertTrue(result is RaizResult.Success)
        val json = requireNotNull(body)
        assertEquals(setOf("barrioId", "amountStroops"), json.keys)
        val amount = json.getValue("amountStroops")
        // i128 como string decimal (README del relayer § vault): nunca un número JSON.
        assertTrue(amount is JsonPrimitive)
        assertTrue("amountStroops debe viajar como string", (amount as JsonPrimitive).isString)
        assertEquals("20000000", amount.content)
        assertEquals(barrioId, json.getValue("barrioId").jsonPrimitive.content)
    }

    @Test
    fun vaultRedeemEnviaSharesComoStringDecimal() = runTest {
        var body: JsonObject? = null
        val engine = MockEngine { request ->
            body = bodyOf(request)
            respond(content = """{"ok":true,"txHash":"abc123","ledger":1}""", status = HttpStatusCode.OK, headers = jsonHeaders())
        }
        val client = buildClient(engine)

        client.vaultRedeem(barrioId, 1_234_567L)

        val json = requireNotNull(body)
        assertEquals(setOf("barrioId", "shares"), json.keys)
        val shares = json.getValue("shares").jsonPrimitive
        assertTrue("shares debe viajar como string", shares.isString)
        assertEquals("1234567", shares.content)
    }

    // ── Éxitos idempotentes (409) ────────────────────────────────────────

    @Test
    fun mintResident409AlreadyResidentEsSuccessNull() = runTest {
        val client = buildClient(errorEngine(HttpStatusCode.Conflict, "ALREADY_RESIDENT", "Ya es residente"))

        val result = client.mintResident("GABCDEF", barrioId)

        assertTrue(result is RaizResult.Success)
        assertNull((result as RaizResult.Success).data)
    }

    @Test
    fun registerMerchant409MerchantExistsEsSuccessNull() = runTest {
        val client = buildClient(
            errorEngine(HttpStatusCode.Conflict, "MERCHANT_EXISTS", "El comercio ya está registrado"),
        )

        val result = client.registerMerchant("GABCDEF", "Cafe Don Aurelio", barrioId, 1, 2, "cafe")

        // H3: el estado final deseado ya se cumple → éxito sin txHash, no un error.
        assertTrue("MERCHANT_EXISTS debe ser Success(null), fue $result", result is RaizResult.Success)
        assertNull((result as RaizResult.Success).data)
    }

    // ── Mapeo de errores (SESION_B_APP.md § 2) ───────────────────────────

    @Test
    fun faucet429RateLimitedIncluyeRetryAfterSeconds() = runTest {
        val client = buildClient(
            errorEngine(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Cupo agotado", details = """{"retryAfterSeconds":120}"""),
        )

        val result = client.faucet("GABCDEF")

        assertTrue(result is RaizResult.Error)
        val error = result as RaizResult.Error
        assertEquals(RaizErrorCode.RATE_LIMITED, error.code)
        assertTrue("mensaje debería incluir el retryAfterSeconds: ${error.message}", error.message.contains("120"))
    }

    @Test
    fun rateLimitedNoDuplicaElSufijoSiElRelayerYaDiceReintentaEn() = runTest {
        // Mensaje real del relayer (src/app.ts): ya trae la instrucción.
        val relayerMessage = "Cupo agotado (faucet_por_address). Reintenta en 120 s."
        val client = buildClient(
            errorEngine(HttpStatusCode.TooManyRequests, "RATE_LIMITED", relayerMessage, details = """{"retryAfterSeconds":120}"""),
        )

        val error = client.faucet("GABCDEF") as RaizResult.Error

        assertEquals(RaizErrorCode.RATE_LIMITED, error.code)
        assertEquals(relayerMessage, error.message)
        assertEquals(1, Regex("eintenta en").findAll(error.message).count())
    }

    @Test
    fun faucet422NoTrustlineMapeaANotFoundConMensaje() = runTest {
        val client = buildClient(
            errorEngine(HttpStatusCode.UnprocessableEntity, "NO_TRUSTLINE", "Falta la trustline USDC"),
        )

        val error = client.faucet("GABCDEF") as RaizResult.Error

        assertEquals(RaizErrorCode.NOT_FOUND, error.code)
        assertEquals("Falta la trustline USDC", error.message)
    }

    @Test
    fun faucet503FaucetEmptyMapeaAInsufficientBalance() = runTest {
        val client = buildClient(
            errorEngine(HttpStatusCode.ServiceUnavailable, "FAUCET_EMPTY", "Sin USDC en el admin"),
        )

        val error = client.faucet("GABCDEF") as RaizResult.Error

        // H4: sin USDC en el faucet es saldo insuficiente, no fallo de red.
        assertEquals(RaizErrorCode.INSUFFICIENT_BALANCE, error.code)
        assertEquals("Sin USDC en el admin", error.message)
    }

    @Test
    fun trustlineDeauthorizedYUnauthorizedAdminMapeanAUnauthorizedConMensaje() = runTest {
        val casos = listOf(
            HttpStatusCode.UnprocessableEntity to "TRUSTLINE_DEAUTHORIZED",
            HttpStatusCode.BadGateway to "UNAUTHORIZED_ADMIN",
        )
        for ((status, code) in casos) {
            val client = buildClient(errorEngine(status, code, "mensaje de $code"))

            val error = client.faucet("GABCDEF") as RaizResult.Error

            assertEquals(code, RaizErrorCode.UNAUTHORIZED, error.code)
            assertEquals("mensaje de $code", error.message)
        }
    }

    @Test
    fun unauthorizedAppMapeaAUnauthorizedConTextoPropio() = runTest {
        val client = buildClient(
            errorEngine(HttpStatusCode.Unauthorized, "UNAUTHORIZED_APP", "Falta o es incorrecta la cabecera x-raiz-app-key."),
        )

        val error = client.faucet("GABCDEF") as RaizResult.Error

        assertEquals(RaizErrorCode.UNAUTHORIZED, error.code)
        assertEquals(RelayerClient.MSG_APP_UNAUTHORIZED, error.message)
    }

    @Test
    fun rpcUnreachableQueueFullYRestoreRequiredMapeanANetworkError() = runTest {
        for (code in listOf("RPC_UNREACHABLE", "QUEUE_FULL", "RESTORE_REQUIRED")) {
            val client = buildClient(errorEngine(HttpStatusCode.ServiceUnavailable, code, "mensaje de $code"))

            val error = client.vaultDeposit(barrioId, 1L) as RaizResult.Error

            assertEquals(code, RaizErrorCode.NETWORK_ERROR, error.code)
            assertEquals("mensaje de $code", error.message)
            // Estos NO son "transacción en vuelo": el cooldown de Yield no aplica.
            assertFalse(code, RelayerClient.isPendingTransactionError(error))
        }
    }

    @Test
    fun txTimeoutConTxHashEnDetailsIncluyeElHashAbreviado() = runTest {
        val client = buildClient(
            errorEngine(
                HttpStatusCode.ServiceUnavailable,
                "TX_TIMEOUT",
                "La operación superó el tiempo máximo del relayer.",
                details = """{"txHash":"$txHash64"}""",
            ),
        )

        val error = client.vaultDeposit(barrioId, 20_000_000L) as RaizResult.Error

        assertEquals(RaizErrorCode.NETWORK_ERROR, error.code)
        // Hash abreviado (8 primeros … 4 últimos), nunca el hash entero ni el mensaje genérico.
        assertTrue(error.message, error.message.contains("abcdef01…6789"))
        assertFalse(error.message, error.message.contains(txHash64))
        assertTrue(error.message, error.message.startsWith(RelayerClient.MSG_TX_PENDING_PREFIX))
        assertTrue(error.message, error.message.contains("mismo resultado"))
        assertTrue(RelayerClient.isPendingTransactionError(error))
    }

    @Test
    fun txTimeoutSinTxHashConservaElMensajeDelRelayer() = runTest {
        val relayerMessage = "La operación superó el tiempo máximo del relayer."
        val client = buildClient(
            errorEngine(HttpStatusCode.ServiceUnavailable, "TX_TIMEOUT", relayerMessage, details = """{"txHash":null}"""),
        )

        val error = client.vaultDeposit(barrioId, 20_000_000L) as RaizResult.Error

        assertEquals(RaizErrorCode.NETWORK_ERROR, error.code)
        assertEquals(relayerMessage, error.message)
    }

    // ── Transporte: red, timeouts y bodies no JSON ───────────────────────

    @Test
    fun excepcionDeRedMapeaANetworkErrorNoContactado() = runTest {
        val client = buildClient(MockEngine { throw IOException("no hay red") })

        val error = client.faucet("GABCDEF") as RaizResult.Error

        assertEquals(RaizErrorCode.NETWORK_ERROR, error.code)
        assertEquals(RelayerClient.MSG_RELAYER_UNREACHABLE, error.message)
        assertFalse(RelayerClient.isPendingTransactionError(error))
    }

    @Test
    fun timeoutDeKtorMapeaANetworkErrorSigueProcesando() = runTest {
        val client = buildClient(
            MockEngine { throw HttpRequestTimeoutException("$BASE_URL/v1/faucet", 95_000L) },
        )

        val error = client.faucet("GABCDEF") as RaizResult.Error

        assertEquals(RaizErrorCode.NETWORK_ERROR, error.code)
        // No "no se pudo contactar": la tx puede estar firmándose; se reintenta con la MISMA key.
        assertTrue(error.message, error.message.contains("sigue procesando"))
        assertTrue(error.message, error.message.contains("mismo intento"))
        assertTrue(RelayerClient.isPendingTransactionError(error))
    }

    @Test
    fun respuesta502HtmlDelProxyMapeaANetworkError() = runTest {
        val engine = MockEngine {
            respond(
                content = "<html><body><h1>502 Bad Gateway</h1></body></html>",
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, listOf("text/html")),
            )
        }
        val client = buildClient(engine)

        val result = client.faucet("GABCDEF")

        assertTrue("debe ser Error, fue $result", result is RaizResult.Error)
        assertEquals(RaizErrorCode.NETWORK_ERROR, (result as RaizResult.Error).code)
    }

    @Test
    fun respuesta200ConBodyVacioMapeaANetworkError() = runTest {
        val client = buildClient(okEngine(content = ""))

        val result = client.vaultDeposit(barrioId, 1L)

        assertTrue("debe ser Error, fue $result", result is RaizResult.Error)
        assertEquals(RaizErrorCode.NETWORK_ERROR, (result as RaizResult.Error).code)
    }

    // ── Headers e idempotencia ───────────────────────────────────────────

    @Test
    fun enviaHeaderAppKeyEIdempotencyKeyDeMaximo64Chars() = runTest {
        var capturedAppKey: String? = null
        var capturedIdempotencyKey: String? = null
        val engine = MockEngine { request ->
            capturedAppKey = request.headers[APP_KEY_HEADER]
            capturedIdempotencyKey = request.headers[IDEMPOTENCY_HEADER]
            respond(content = """{"ok":true,"txHash":"abc123","ledger":1}""", status = HttpStatusCode.OK, headers = jsonHeaders())
        }
        val client = buildClient(engine)

        client.registerMerchant("GABCDEF", "Cafe Don Aurelio", barrioId, 1, 2, "cafe")

        assertEquals(APP_KEY, capturedAppKey)
        val key = requireNotNull(capturedIdempotencyKey)
        assertTrue("idempotency-key vacía", key.isNotBlank())
        assertTrue("idempotency-key de ${key.length} chars supera el límite de 64", key.length <= 64)
    }

    @Test
    fun mismaIdempotencyKeyLlegaIgualEnDosRequestsYPorDefectoCambia() = runTest {
        val captured = mutableListOf<String?>()
        val engine = MockEngine { request ->
            captured += request.headers[IDEMPOTENCY_HEADER]
            respond(content = """{"ok":true,"txHash":"abc123","ledger":1}""", status = HttpStatusCode.OK, headers = jsonHeaders())
        }
        val client = buildClient(engine)
        val attemptKey = RelayerClient.newIdempotencyKey()

        // Mismo intento de usuario (reintento): la key viaja tal cual las dos veces.
        client.faucet("GABCDEF", idempotencyKey = attemptKey)
        client.faucet("GABCDEF", idempotencyKey = attemptKey)
        // Intentos distintos (sin key explícita): keys distintas.
        client.faucet("GABCDEF")
        client.faucet("GABCDEF")

        assertEquals(4, captured.size)
        assertEquals(attemptKey, captured[0])
        assertEquals(attemptKey, captured[1])
        assertNotNull(captured[2])
        assertNotNull(captured[3])
        assertNotEquals(captured[2], captured[3])
        assertNotEquals(attemptKey, captured[2])
    }

    @Test
    fun idempotencyKeyDeMasDe64CharsNoSeEnviaYDevuelveError() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(content = """{"ok":true,"txHash":"abc123","ledger":1}""", status = HttpStatusCode.OK, headers = jsonHeaders())
        }
        val client = buildClient(engine)

        val result = client.faucet("GABCDEF", idempotencyKey = "x".repeat(65))

        assertTrue(result is RaizResult.Error)
        assertEquals(RaizErrorCode.PARSE_ERROR, (result as RaizResult.Error).code)
        assertEquals("no debe salir ninguna request con key inválida", 0, requests)
    }

    // ── GET /v1/health ───────────────────────────────────────────────────

    @Test
    fun health200ParseaVaultEndpointsYFaucetEnabled() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {"ok":true,"network":"testnet","protocolVersion":28,
                     "admin":"GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P",
                     "contracts":{"pool":"CD775D33SPEO3BTAZIEQTQGN6HERTR5YNEQOZWWKXLDKLJ2B34LCKBE2"},
                     "faucet":{"enabled":true,"amountStroops":"200000000","adminUsdcStroops":"9990000000","remainingToday":50},
                     "limits":{"faucetPerDay":50},"vaultEndpoints":true,"queue":{"pending":0},
                     "version":"0.1.0","uptimeSeconds":123,"campoNuevoQueLaAppNoConoce":1}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val client = buildClient(engine)

        val result = client.health()

        assertTrue("debe ser Success, fue $result", result is RaizResult.Success)
        val health = (result as RaizResult.Success).data
        assertTrue(health.ok)
        assertTrue(health.vaultEndpoints)
        assertTrue(health.faucet.enabled)
        assertEquals("200000000", health.faucet.amountStroops)
        assertEquals(50, health.faucet.remainingToday)
        assertEquals("testnet", health.network)
    }

    @Test
    fun health503ConEnvelopeMapeaANetworkErrorConElMensaje() = runTest {
        val client = buildClient(
            errorEngine(HttpStatusCode.ServiceUnavailable, "RPC_UNREACHABLE", "RPC o Horizon no responden"),
        )

        val result = client.health()

        assertTrue(result is RaizResult.Error)
        val error = result as RaizResult.Error
        assertEquals(RaizErrorCode.NETWORK_ERROR, error.code)
        assertEquals("RPC o Horizon no responden", error.message)
    }

    companion object {
        private const val BASE_URL = "https://relayer.test"
        private const val APP_KEY = "test-key-1234567890"
        private const val APP_KEY_HEADER = "x-raiz-app-key"
        private const val IDEMPOTENCY_HEADER = "idempotency-key"
    }
}
