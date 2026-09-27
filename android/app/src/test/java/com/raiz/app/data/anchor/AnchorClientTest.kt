package com.raiz.app.data.anchor

import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.soneso.stellar.sdk.KeyPair
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM puros de [AnchorClient] con Ktor `MockEngine` (sin red real), mismo
 * estilo que `RelayerClientTest`. Cubre SEP-1 (`stellar.toml` + `/info`), SEP-24
 * (deposit interactivo, consulta y polling de estado) y el mapeo de errores del
 * SDK Soneso a [RaizResult] descrito en `wp3_contract.md` § Agente DATA.
 *
 * [TESTANCHOR_TOML] es el `stellar.toml` REAL de `testanchor.stellar.org` tal cual
 * (descargado el 2026-09-27, 1708 bytes): trae `ACCOUNTS`, `[DOCUMENTATION]`, la
 * currency `native` sin `issuer` y los campos extra de USDC/SRT, para que el test
 * ejercite el parser del SDK y el filtro `code == "USDC"` con el documento de verdad.
 *
 * La autenticación SEP-10 completa ([AnchorClient.authenticate] en el camino feliz)
 * NO se testea aquí: `WebAuth.validateChallenge` exige un challenge firmado por la
 * `SIGNING_KEY` del anchor con las 13 comprobaciones de seguridad del SDK, lo que
 * requeriría construir a mano una transacción challenge válida — fuera de alcance
 * de un test de mapeo HTTP. Sí se cubre el mapeo de errores de transporte
 * ([com.soneso.stellar.sdk.sep.sep10.exceptions.ChallengeRequestException]) contra
 * el propio `GET` del challenge, tal como permite `wp3_contract.md` § Agente DATA
 * punto 6 para este caso.
 */
class AnchorClientTest {

    private val domain = "testanchor.stellar.org"

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, listOf("application/json"))
    private fun tomlHeaders() = headersOf(HttpHeaders.ContentType, listOf("text/plain"))

    private fun buildClient(engine: MockEngine): AnchorClient {
        // expectSuccess = false: igual que el HttpClient de producción (@Named("anchor") en
        // di/DataModule.kt) — el SDK Soneso decide sus excepciones tipadas a partir del status
        // HTTP y el body; con el validador de Ktor activo (default) un 4xx/5xx lanzaría ANTES
        // de que el SDK tenga oportunidad de inspeccionar la respuesta.
        val http = HttpClient(engine) { expectSuccess = false }
        return AnchorClient(http, homeDomain = domain)
    }

    /** Respuesta `/transaction` de SEP-24 con el status pedido (y opcionalmente el hash on-chain). */
    private fun txJson(status: String, stellarTxHash: String? = null): String {
        val hashField = stellarTxHash?.let { ""","stellar_transaction_id":"$it"""" } ?: ""
        return """{"transaction":{"id":"dep1","kind":"deposit","status":"$status"$hashField}}"""
    }

    private fun engine(handler: (path: String) -> Pair<String, HttpStatusCode>?): MockEngine = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("stellar.toml") -> respond(TESTANCHOR_TOML, HttpStatusCode.OK, tomlHeaders())
            path.endsWith("/info") -> respond(INFO_JSON, HttpStatusCode.OK, jsonHeaders())
            else -> {
                val (body, status) = handler(path) ?: ("{}" to HttpStatusCode.NotFound)
                respond(body, status, jsonHeaders())
            }
        }
    }

    // ── SEP-1: stellar.toml + /info ──────────────────────────────────────

    @Test
    fun loadInfoParseaTomlYSep24InfoCorrectamente() = runTest {
        val client = buildClient(engine { null })

        val result = client.loadInfo()

        assertTrue("debe ser Success, fue $result", result is RaizResult.Success)
        val info = (result as RaizResult.Success).data
        assertEquals(domain, info.homeDomain)
        assertEquals("https://testanchor.stellar.org/auth", info.webAuthEndpoint)
        assertEquals("https://testanchor.stellar.org/sep24", info.sep24Url)
        assertEquals("GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR", info.signingKey)
        assertEquals(RaizConstants.ANCHOR_USDC_ISSUER, info.usdcIssuer)
        assertEquals(1.0, info.depositMinUsdc)
        assertEquals(10.0, info.depositMaxUsdc)
    }

    @Test
    fun loadInfoCacheaEnMemoriaYNoRepiteRequests() = runTest {
        var requests = 0
        val mockEngine = MockEngine { request ->
            requests++
            val path = request.url.encodedPath
            when {
                path.endsWith("stellar.toml") -> respond(TESTANCHOR_TOML, HttpStatusCode.OK, tomlHeaders())
                else -> respond(INFO_JSON, HttpStatusCode.OK, jsonHeaders())
            }
        }
        val client = buildClient(mockEngine)

        client.loadInfo()
        client.loadInfo()

        assertEquals("toml + info, una sola vez", 2, requests)
    }

    @Test
    fun loadInfoSinCurrencyUsdcEnElTomlCaeAlIssuerConstante() = runTest {
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("stellar.toml") -> respond(TESTANCHOR_TOML_SIN_USDC, HttpStatusCode.OK, tomlHeaders())
                else -> respond(INFO_JSON, HttpStatusCode.OK, jsonHeaders())
            }
        }
        val client = buildClient(mockEngine)

        val result = client.loadInfo()

        assertTrue(result is RaizResult.Success)
        assertEquals(RaizConstants.ANCHOR_USDC_ISSUER, (result as RaizResult.Success).data.usdcIssuer)
    }

    @Test
    fun loadInfoRechazaEndpointsSinTls() = runTest {
        // SEP-1 exige https en WEB_AUTH_ENDPOINT / TRANSFER_SERVER_SEP0024; por ahí viajarían el
        // challenge firmado y el `Authorization: Bearer <jwt>`. El engine CIO no aplica la
        // NetworkSecurityPolicy de Android, así que AnchorClient lo comprueba a mano.
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("stellar.toml") -> respond(TESTANCHOR_TOML_SIN_TLS, HttpStatusCode.OK, tomlHeaders())
                else -> respond(INFO_JSON, HttpStatusCode.OK, jsonHeaders())
            }
        }
        val client = buildClient(mockEngine)

        val result = client.loadInfo()

        assertTrue("debe ser Error, fue $result", result is RaizResult.Error)
        assertEquals(RaizErrorCode.PARSE_ERROR, (result as RaizResult.Error).code)
        assertEquals(AnchorClient.MSG_ANCHOR_NO_TLS, result.message)
    }

    // ── SEP-24: deposit interactivo ──────────────────────────────────────

    @Test
    fun startDepositEnviaCamposExactosYHeaderAuthorizationBearer() = runTest {
        var capturedAuth: String? = null
        var capturedBody: String? = null
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("stellar.toml") -> respond(TESTANCHOR_TOML, HttpStatusCode.OK, tomlHeaders())
                path.endsWith("/info") -> respond(INFO_JSON, HttpStatusCode.OK, jsonHeaders())
                path.endsWith("/transactions/deposit/interactive") -> {
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    capturedBody = String(request.body.toByteArray())
                    respond(
                        """{"type":"interactive_customer_info_needed",""" +
                            """"url":"https://anchor-ref-ui-testanchor.stellar.org?transaction_id=dep1&token=tok",""" +
                            """"id":"dep1"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession(account = "GABCDEFTESTACCOUNT", jwt = "jwt-value", expiresAtEpochSec = null)

        val result = client.startDeposit(session, amountUsdc = "5")

        assertTrue("debe ser Success, fue $result", result is RaizResult.Success)
        val start = (result as RaizResult.Success).data
        assertEquals("dep1", start.id)
        assertEquals("interactive_customer_info_needed", start.type)
        assertTrue(start.interactiveUrl.contains("transaction_id=dep1"))

        assertEquals("Bearer jwt-value", capturedAuth)
        val body = requireNotNull(capturedBody)
        // El Content-Disposition del multipart de esta versión de Ktor NO entrecomilla el name.
        assertTrue("falta asset_code en el multipart:\n$body", body.contains("name=asset_code"))
        assertTrue(body.contains(RaizConstants.ANCHOR_USDC_CODE))
        assertTrue("falta asset_issuer en el multipart:\n$body", body.contains("name=asset_issuer"))
        assertTrue(body.contains(RaizConstants.ANCHOR_USDC_ISSUER))
        assertTrue("falta account en el multipart:\n$body", body.contains("name=account"))
        assertTrue(body.contains("GABCDEFTESTACCOUNT"))
        assertTrue("falta amount en el multipart:\n$body", body.contains("name=amount"))
        assertTrue("falta lang en el multipart:\n$body", body.contains("name=lang"))
        assertTrue(body.contains("es"))
    }

    // ── SEP-24: consulta de estado (sin polling) ─────────────────────────

    @Test
    fun depositStatusMapeaIncompleteYCompletedConHash() = runTest {
        var call = 0
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) return@engine null
            call++
            val body = if (call == 1) txJson("incomplete") else txJson("completed", stellarTxHash = "hash123")
            body to HttpStatusCode.OK
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        val incomplete = client.depositStatus(session, "dep1")
        assertTrue(incomplete is RaizResult.Success)
        val incompleteStatus = (incomplete as RaizResult.Success).data
        assertEquals(AnchorDepositState.INCOMPLETE, incompleteStatus.state)
        assertEquals("incomplete", incompleteStatus.rawStatus)
        assertNull(incompleteStatus.stellarTxHash)
        assertTrue(!incompleteStatus.isTerminal)

        val completed = client.depositStatus(session, "dep1")
        assertTrue(completed is RaizResult.Success)
        val completedStatus = (completed as RaizResult.Success).data
        assertEquals(AnchorDepositState.COMPLETED, completedStatus.state)
        assertEquals("hash123", completedStatus.stellarTxHash)
        assertTrue(completedStatus.isTerminal)
    }

    @Test
    fun depositStatus403AuthenticationRequiredMapeaAUnauthorized() = runTest {
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) null
            else """{"type":"authentication_required"}""" to HttpStatusCode.Forbidden
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        val result = client.depositStatus(session, "dep1")

        assertTrue(result is RaizResult.Error)
        assertEquals(RaizErrorCode.UNAUTHORIZED, (result as RaizResult.Error).code)
    }

    @Test
    fun depositStatus401SinTypeAuthenticationRequiredMapeaAUnauthorized() = runTest {
        // El SDK solo tipifica el 403 + {"type":"authentication_required"}; un 401 llega como
        // Sep24ServerErrorException(statusCode = 401) y debe seguir siendo UNAUTHORIZED.
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) null
            else """{"error":"jwt expired"}""" to HttpStatusCode.Unauthorized
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        val result = client.depositStatus(session, "dep1")

        assertTrue(result is RaizResult.Error)
        assertEquals(RaizErrorCode.UNAUTHORIZED, (result as RaizResult.Error).code)
    }

    @Test
    fun depositStatus404MapeaANotFound() = runTest {
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) null
            else """{"error":"transaction not found"}""" to HttpStatusCode.NotFound
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        val result = client.depositStatus(session, "dep1")

        assertTrue(result is RaizResult.Error)
        assertEquals(RaizErrorCode.NOT_FOUND, (result as RaizResult.Error).code)
    }

    @Test
    fun depositStatus500MapeaANetworkError() = runTest {
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) null
            else """{"error":"boom"}""" to HttpStatusCode.InternalServerError
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        val result = client.depositStatus(session, "dep1")

        assertTrue(result is RaizResult.Error)
        assertEquals(RaizErrorCode.NETWORK_ERROR, (result as RaizResult.Error).code)
    }

    @Test
    fun depositStatus400InvalidRequestMapeaAParseError() = runTest {
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) null
            else """{"error":"missing id"}""" to HttpStatusCode.BadRequest
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        val result = client.depositStatus(session, "dep1")

        assertTrue(result is RaizResult.Error)
        assertEquals(RaizErrorCode.PARSE_ERROR, (result as RaizResult.Error).code)
    }

    // ── SEP-24: polling ───────────────────────────────────────────────────

    @Test
    fun pollDepositEmiteCadaCambioDeEstadoYTerminaEnCompleted() = runTest {
        var call = 0
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) return@engine null
            call++
            val body = when (call) {
                1 -> txJson("incomplete")
                2 -> txJson("incomplete") // mismo estado: NO debe re-emitirse
                3 -> txJson("pending_anchor")
                else -> txJson("completed", stellarTxHash = "abc123")
            }
            body to HttpStatusCode.OK
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        val statuses = client.pollDeposit(session, "dep1", initialDelayMs = 1L, maxDelayMs = 1L).toList()

        // 4 llamadas HTTP, pero solo 3 emisiones (incomplete repetido no se re-emite).
        assertEquals(3, statuses.size)
        assertEquals(AnchorDepositState.INCOMPLETE, statuses[0].state)
        assertEquals(AnchorDepositState.PENDING_ANCHOR, statuses[1].state)
        assertEquals(AnchorDepositState.COMPLETED, statuses[2].state)
        assertEquals("abc123", statuses[2].stellarTxHash)
    }

    @Test
    fun pollDepositConAuthenticationRequiredCortaConAnchorSessionExpired() = runTest {
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) null
            else """{"type":"authentication_required"}""" to HttpStatusCode.Forbidden
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        var thrown: Throwable? = null
        try {
            client.pollDeposit(session, "dep1", initialDelayMs = 1L, maxDelayMs = 1L).toList()
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("debía cortar con AnchorSessionExpired, fue $thrown", thrown is AnchorSessionExpired)
    }

    @Test
    fun pollDepositCon401CortaConAnchorSessionExpired() = runTest {
        // Sin este corte, un JWT rechazado con 401 se sondearía 5 min como "error transitorio".
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) null
            else """{"error":"jwt expired"}""" to HttpStatusCode.Unauthorized
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        var thrown: Throwable? = null
        try {
            client.pollDeposit(session, "dep1", initialDelayMs = 1L, maxDelayMs = 1L).toList()
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("debía cortar con AnchorSessionExpired, fue $thrown", thrown is AnchorSessionExpired)
    }

    @Test
    fun pollDepositAgotaTimeoutYLanzaAnchorPollTimeoutTrasEmitirElUltimoEstado() = runTest {
        // Siempre "incomplete": nunca llega a terminal, así que debe cortar por timeout.
        // timeoutMs = 0L hace el corte determinista en la PRIMERA vuelta (el reloj del
        // timeout es tiempo acumulado de `delay()`, que arranca en 0 — ver KDoc de
        // AnchorClient.pollDeposit): garantiza el caso límite "incluso con timeout ya
        // agotado, el primer estado sale por el flow antes de lanzar la excepción".
        val mockEngine = engine { path ->
            if (!path.endsWith("/transaction")) null else txJson("incomplete") to HttpStatusCode.OK
        }
        val client = buildClient(mockEngine)
        val session = AnchorSession("GABCDEF", "jwt", null)

        val emitted = mutableListOf<AnchorDepositState>()
        var thrown: Throwable? = null
        try {
            client.pollDeposit(session, "dep1", timeoutMs = 0L, initialDelayMs = 1L, maxDelayMs = 1L)
                .collect { emitted += it.state }
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("debía cortar con AnchorPollTimeout, fue $thrown", thrown is AnchorPollTimeout)
        assertEquals(listOf(AnchorDepositState.INCOMPLETE), emitted)
    }

    // ── SEP-10: solo el mapeo de errores de transporte (ver KDoc de la clase) ──

    @Test
    fun authenticate500EnElChallengeMapeaANetworkError() = runTest {
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("stellar.toml") -> respond(TESTANCHOR_TOML, HttpStatusCode.OK, tomlHeaders())
                // authenticate() llama a loadInfo() primero (necesita sep24Url del toml, aunque
                // este test solo ejercite el challenge SEP-10): el /info de SEP-24 debe responder
                // OK para llegar a ejercitar el GET del challenge en /auth.
                path.endsWith("/info") -> respond(INFO_JSON, HttpStatusCode.OK, jsonHeaders())
                path.endsWith("/auth") -> respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, jsonHeaders())
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val client = buildClient(mockEngine)
        val keyPair = KeyPair.random()

        val result = client.authenticate(keyPair)

        assertTrue("debe ser Error, fue $result", result is RaizResult.Error)
        assertEquals(RaizErrorCode.NETWORK_ERROR, (result as RaizResult.Error).code)
    }

    // ── cachedSession / clearSession (sin red) ───────────────────────────

    @Test
    fun cachedSessionDevuelveNullSinSesionYTrasClearSession() {
        val client = buildClient(MockEngine { respond("{}", HttpStatusCode.NotFound) })

        assertNull(client.cachedSession("GABCDEF"))

        client.clearSession()
        assertNull(client.cachedSession("GABCDEF"))
    }

    companion object {
        /** `https://testanchor.stellar.org/.well-known/stellar.toml` tal cual, descargado el 2026-09-27. */
        private val TESTANCHOR_TOML = """
            ACCOUNTS = ["GCSGSR6KQQ5BP2FXVPWRL6SWPUSFWLVONLIBJZUKTVQB5FYJFVL6XOXE"]
            VERSION = "0.1.0"
            SIGNING_KEY = "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
            NETWORK_PASSPHRASE = "Test SDF Network ; September 2015"

            WEB_AUTH_ENDPOINT = "https://testanchor.stellar.org/auth"
            KYC_SERVER = "https://testanchor.stellar.org/sep12"
            TRANSFER_SERVER = "https://testanchor.stellar.org/sep6"
            TRANSFER_SERVER_SEP0024 = "https://testanchor.stellar.org/sep24"
            DIRECT_PAYMENT_SERVER = "https://testanchor.stellar.org/sep31"
            ANCHOR_QUOTE_SERVER = "https://testanchor.stellar.org/sep38"
            WEB_AUTH_FOR_CONTRACTS_ENDPOINT = "https://testanchor.stellar.org/sep45/auth"
            WEB_AUTH_CONTRACT_ID = "CD3LA6RKF5D2FN2R2L57MWXLBRSEWWENE74YBEFZSSGNJRJGICFGQXMX"

            [[CURRENCIES]]
            code = "SRT"
            issuer = "GCDNJUBQSX7AJWLJACMJ7I4BC3Z47BQUTMHEICZLE6MU4KQBRYG5JY6B"
            status = "test"
            is_asset_anchored = false
            anchor_asset_type = "crypto"
            desc = "Stellar Reference Token (SRT) is an asset issued on testnet and is used as an anchored asset for this reference server for demonstration and testing purposes."

            [[CURRENCIES]]
            code = "USDC"
            issuer = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            status = "test"
            is_asset_anchored = false
            anchor_asset_type = "crypto"
            desc = "Circle USDC Token"

            [[CURRENCIES]]
            code = "native"
            status = "test"
            is_asset_anchored = false
            anchor_asset_type = "crypto"
            desc = "XLM, the native asset of the Stellar network."

            [DOCUMENTATION]
            ORG_NAME = "Stellar Development Foundation"
            ORG_URL = "https://stellar.org"
            ORG_DESCRIPTION = "The Stellar Development Foundation (SDF) is a non-profit organization whose mission is to create equitable access to the global financial system."
            ORG_GITHUB = "stellar"
        """.trimIndent()

        /** Endpoints SEP-10/24 sin TLS: AnchorClient.loadInfo debe rechazarlo. */
        private val TESTANCHOR_TOML_SIN_TLS = """
            NETWORK_PASSPHRASE="Test SDF Network ; September 2015"
            WEB_AUTH_ENDPOINT="http://testanchor.stellar.org/auth"
            TRANSFER_SERVER_SEP0024="http://testanchor.stellar.org/sep24"
            SIGNING_KEY="GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
        """.trimIndent()

        private val TESTANCHOR_TOML_SIN_USDC = """
            NETWORK_PASSPHRASE="Test SDF Network ; September 2015"
            WEB_AUTH_ENDPOINT="https://testanchor.stellar.org/auth"
            TRANSFER_SERVER_SEP0024="https://testanchor.stellar.org/sep24"
            SIGNING_KEY="GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
        """.trimIndent()

        private val INFO_JSON = """
            {"deposit":{"USDC":{"enabled":true,"min_amount":"1","max_amount":"10"}},
             "withdraw":{},"fee":{"enabled":false},
             "features":{"account_creation":false,"claimable_balances":false}}
        """.trimIndent()
    }
}
