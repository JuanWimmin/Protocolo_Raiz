package com.raiz.app.data.relayer

import android.util.Log
import com.raiz.app.BuildConfig
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Cliente HTTP de `raiz-relayer` (D1 del SOW Instaward).
 *
 * Reemplaza a los métodos de [com.raiz.app.data.stellar.SorobanClient] que
 * firmaban con el KeyPair del admin (0.1.0): la clave admin del protocolo ya
 * NO vive en el APK, vive en la variable de entorno del servidor. La app llama a un
 * endpoint JSON con una API key estática y recibe el `txHash`.
 *
 * Contrato completo: `raiz-relayer/README.md` (Endpoints, tabla HTTP↔code) y
 * `raiz-relayer/docs/SESION_B_APP.md` (mapeo a [RaizResult]/[RaizErrorCode]).
 *
 * ## Idempotencia (`idempotency-key`)
 *
 * Todos los POST aceptan un `idempotencyKey` (≤ 64 chars) que se envía tal
 * cual en el header `idempotency-key`. La key es **por intento de usuario**,
 * no por request HTTP: el ViewModel la genera al iniciar un intento
 * ([newIdempotencyKey]), la conserva mientras la acción no llegue a éxito
 * definitivo y la reutiliza en cada "Reintentar" con el mismo body. El relayer
 * cachea la respuesta 10 min por (ruta, key, hash del body) — también un
 * `TX_TIMEOUT` con `details.txHash` —, así que reintentar con la misma key
 * devuelve el mismo resultado en vez de firmar una segunda transacción. Misma
 * key con body distinto → `422 IDEMPOTENCY_MISMATCH`: si el usuario cambia el
 * formulario, toca key nueva (ver `attemptKey` en los ViewModels).
 *
 * @param http HttpClient inyectado con `@Named("relayer")` (CIO + JSON,
 *   timeout 95 s — ver `di/DataModule.kt`).
 * @param baseUrl URL base del relayer, sin `/` final. Parámetro del constructor
 *   primario (en vez de leerlo directo de [BuildConfig] en el cuerpo de la
 *   clase) para poder inyectar una URL de prueba con `MockEngine` en tests JVM
 *   sin tocar `BuildConfig`.
 * @param appKey API key estática para el header `x-raiz-app-key`. Mismo
 *   motivo que `baseUrl`: parametrizable para tests.
 *
 * Hilt usa el constructor secundario `@Inject` (solo el HttpClient): Dagger no
 * entiende los valores por defecto de Kotlin y pediría un binding para
 * `String` si el `@Inject` estuviera en el primario.
 */
@Singleton
class RelayerClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val appKey: String,
) {

    @Inject
    constructor(@Named("relayer") http: HttpClient) : this(
        http = http,
        baseUrl = BuildConfig.RELAYER_URL.trimEnd('/'),
        appKey = BuildConfig.RELAYER_APP_KEY,
    )

    /** Resultado exitoso de `POST /v1/faucet`. */
    data class FaucetOutcome(
        val txHash: String,
        val amountStroops: Long,
        /** `"payment"` (destino G…) o `"sac_transfer"` (destino C…). */
        val method: String,
    )

    private var cachedHealth: RelayerHealth? = null
    private var cachedHealthAtMs: Long = 0L

    /** true si hay URL y API key configuradas (local.properties). Sin esto no tiene sentido llamar al relayer. */
    fun isConfigured(): Boolean = baseUrl.isNotBlank() && appKey.isNotBlank()

    /**
     * `GET /v1/health` (sin autenticación). Cachea 30 s en memoria — pensado
     * como feature-flag para mostrar/ocultar los flujos admin (faucet,
     * volverse comercio, verificar residente, yield). Un 503 no se cachea
     * (el relayer tampoco cachea ese caso): el siguiente `health()` reintenta.
     *
     * Timeout propio y corto (10 s request / 5 s connect): el relayer corta su
     * consulta a RPC/Horizon a los 8 s (`SESION_B_APP.md` § 3), así que no tiene
     * sentido heredar los 95 s del cliente — pensados para los POST que firman —
     * y bloquear una pantalla de solo lectura mientras tanto (H2/H6).
     */
    suspend fun health(): RaizResult<RelayerHealth> {
        val now = System.currentTimeMillis()
        cachedHealth?.let { cached ->
            if (now - cachedHealthAtMs < HEALTH_CACHE_MS) return RaizResult.Success(cached)
        }
        return try {
            val response = http.get("$baseUrl/v1/health") {
                timeout {
                    requestTimeoutMillis = HEALTH_REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = HEALTH_CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = HEALTH_REQUEST_TIMEOUT_MS
                }
            }
            if (response.status == HttpStatusCode.OK) {
                val health = response.decodeOrNull(RelayerHealth.serializer())
                if (health == null) {
                    RaizResult.Error(RaizErrorCode.NETWORK_ERROR, invalidBodyMessage(response.status))
                } else {
                    cachedHealth = health
                    cachedHealthAtMs = now
                    RaizResult.Success(health)
                }
            } else {
                val envelope = response.decodeOrNull(RelayerEnvelope.serializer())
                val message = envelope?.error?.message
                    ?: "El relayer no está disponible (HTTP ${response.status.value})"
                Log.w(TAG, "RelayerClient.health: HTTP ${response.status.value} code=${envelope?.error?.code}")
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "RelayerClient.health: excepción ${e.message}")
            RaizResult.Error(RaizErrorCode.NETWORK_ERROR, MSG_RELAYER_UNREACHABLE)
        }
    }

    /**
     * `POST /v1/register-merchant` → `Pool.register_merchant(MerchantData)`, `verified=true` server-side.
     *
     * `409 MERCHANT_EXISTS` se trata como **éxito idempotente** (H3): el
     * comercio ya está registrado y el relayer no lo sobrescribe, así que el
     * estado final deseado ya se cumple. En ese caso devuelve `Success(null)`
     * (sin `txHash` porque no se envió transacción nueva); el ViewModel decide
     * el texto ("Este comercio ya estaba registrado").
     *
     * @param idempotencyKey key del intento de usuario; ver KDoc de la clase.
     */
    suspend fun registerMerchant(
        address: String,
        name: String,
        barrioId: String,
        latE6: Int,
        lngE6: Int,
        categorySymbol: String,
        idempotencyKey: String = newIdempotencyKey(),
    ): RaizResult<String?> =
        postEnvelope(
            path = "register-merchant",
            requestBody = RegisterMerchantRequest(address, name, barrioId, latE6, lngE6, categorySymbol),
            idempotencyKey = idempotencyKey,
            idempotentCodes = setOf("MERCHANT_EXISTS"),
        ).toOptionalTxHash()

    /**
     * `POST /v1/mint-resident` → `Governance.mint_resident(barrio_admin, resident, barrio_id)`.
     *
     * `409 ALREADY_RESIDENT` se trata como éxito idempotente — el estado final
     * deseado (el address ya puede votar/proponer) ya se cumple, igual que
     * hacía `SorobanClient.mintResident` con el error `#5` de Governance.
     * En ese caso devuelve `Success(null)` (no hay `txHash` porque no se
     * envió transacción nueva).
     *
     * @param idempotencyKey key del intento de usuario; ver KDoc de la clase.
     */
    suspend fun mintResident(
        address: String,
        barrioId: String,
        idempotencyKey: String = newIdempotencyKey(),
    ): RaizResult<String?> =
        postEnvelope(
            path = "mint-resident",
            requestBody = MintResidentRequest(address, barrioId),
            idempotencyKey = idempotencyKey,
            idempotentCodes = setOf("ALREADY_RESIDENT"),
        ).toOptionalTxHash()

    /**
     * `POST /v1/faucet` → 20 USDC de Blend a `address`. G… recibe un
     * `payment` clásico (visible en Horizon); C… recibe un `sac_transfer`
     * (solo si la smart account ya está desplegada, si no `404 ACCOUNT_NOT_FOUND`).
     *
     * @param idempotencyKey key del intento de usuario; ver KDoc de la clase.
     */
    suspend fun faucet(
        address: String,
        idempotencyKey: String = newIdempotencyKey(),
    ): RaizResult<FaucetOutcome> =
        when (val result = postEnvelope("faucet", FaucetRequest(address), idempotencyKey)) {
            is RaizResult.Success -> {
                val envelope = result.data
                val txHash = envelope.txHash
                val amount = envelope.amountStroops?.toLongOrNull()
                val method = envelope.method
                if (txHash != null && amount != null && method != null) {
                    RaizResult.Success(FaucetOutcome(txHash, amount, method))
                } else {
                    RaizResult.Error(RaizErrorCode.PARSE_ERROR, "El relayer respondió sin datos completos de faucet")
                }
            }
            is RaizResult.Error -> result
        }

    /**
     * `POST /v1/vault/deposit` → `Pool.deposit_idle_to_vault(admin, barrio_id, amount)`.
     * `amountStroops` en stroops (i128), viaja como string decimal.
     *
     * @param idempotencyKey key del intento de usuario; ver KDoc de la clase.
     */
    suspend fun vaultDeposit(
        barrioId: String,
        amountStroops: Long,
        idempotencyKey: String = newIdempotencyKey(),
    ): RaizResult<String> =
        postEnvelope("vault/deposit", VaultDepositRequest(barrioId, amountStroops.toString()), idempotencyKey)
            .toTxHash()

    /**
     * `POST /v1/vault/redeem` → `Pool.redeem_from_vault(admin, barrio_id, shares)`.
     *
     * @param idempotencyKey key del intento de usuario; ver KDoc de la clase.
     */
    suspend fun vaultRedeem(
        barrioId: String,
        shares: Long,
        idempotencyKey: String = newIdempotencyKey(),
    ): RaizResult<String> =
        postEnvelope("vault/redeem", VaultRedeemRequest(barrioId, shares.toString()), idempotencyKey)
            .toTxHash()

    // ── Internals ─────────────────────────────────────────────────────────

    private fun RaizResult<RelayerEnvelope>.toTxHash(): RaizResult<String> = when (this) {
        is RaizResult.Success -> data.txHash?.let { RaizResult.Success(it) }
            ?: RaizResult.Error(RaizErrorCode.PARSE_ERROR, "El relayer respondió sin txHash")
        is RaizResult.Error -> this
    }

    /**
     * Como [toTxHash] pero admite el éxito idempotente absorbido por
     * `idempotentCodes` (envelope con `ok=false`): ahí no hay tx nueva y el
     * resultado es `Success(null)`. Un `ok=true` sin `txHash` sigue siendo
     * `PARSE_ERROR`.
     */
    private fun RaizResult<RelayerEnvelope>.toOptionalTxHash(): RaizResult<String?> = when (this) {
        is RaizResult.Success -> if (data.ok) toTxHash() else RaizResult.Success(null)
        is RaizResult.Error -> this
    }

    /**
     * POST genérico con headers `x-raiz-app-key` e `idempotency-key`.
     *
     * La `idempotencyKey` la decide el llamador y es POR INTENTO DE USUARIO:
     * el ViewModel la genera una vez, la guarda en su estado y la reutiliza en
     * los reintentos de ese mismo intento (mismo body). Nunca se genera aquí
     * una key nueva por request — eso volvería a firmar la transacción en cada
     * reintento (H1). La respuesta se decodifica a mano con [RelayerJson] para
     * que un body no JSON (502 `text/html` del proxy, 200 vacío) sea un
     * `NETWORK_ERROR` y no una excepción.
     *
     * @param idempotentCodes códigos de error del relayer que este endpoint
     *   trata como éxito (estado final ya cumplido): el envelope se devuelve
     *   como `Success` con `ok=false` y sin `txHash`.
     */
    private suspend inline fun <reified TBody> postEnvelope(
        path: String,
        requestBody: TBody,
        idempotencyKey: String,
        idempotentCodes: Set<String> = emptySet(),
    ): RaizResult<RelayerEnvelope> {
        if (idempotencyKey.isBlank() || idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            // Bug de programación del llamador; se devuelve error en vez de lanzar para no tumbar el ViewModel.
            Log.e(TAG, "RelayerClient.$path: idempotency-key inválida (len=${idempotencyKey.length})")
            return RaizResult.Error(
                RaizErrorCode.PARSE_ERROR,
                "idempotency-key inválida (vacía o de más de $MAX_IDEMPOTENCY_KEY_LENGTH caracteres)",
            )
        }
        return try {
            val response = http.post("$baseUrl/v1/$path") {
                contentType(ContentType.Application.Json)
                header(HEADER_APP_KEY, appKey)
                header(HEADER_IDEMPOTENCY_KEY, idempotencyKey)
                setBody(requestBody)
            }
            val envelope = response.decodeOrNull(RelayerEnvelope.serializer())
            when {
                envelope == null -> {
                    Log.w(TAG, "RelayerClient.$path: HTTP ${response.status.value} sin envelope JSON")
                    RaizResult.Error(RaizErrorCode.NETWORK_ERROR, invalidBodyMessage(response.status))
                }
                response.status.isSuccess() && envelope.ok -> RaizResult.Success(envelope)
                envelope.error?.code in idempotentCodes -> {
                    Log.i(TAG, "RelayerClient.$path: ${envelope.error?.code} tratado como éxito idempotente")
                    RaizResult.Success(envelope)
                }
                else -> {
                    Log.w(TAG, "RelayerClient.$path: HTTP ${response.status.value} code=${envelope.error?.code}")
                    mapRelayerError(response.status, envelope)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "RelayerClient.$path: excepción ${e.javaClass.simpleName}: ${e.message}")
            mapTransportException(e)
        }
    }

    /**
     * Decodifica el body con [RelayerJson]; `null` si está vacío o no es el JSON
     * esperado (p. ej. la página HTML de error de un proxy).
     */
    private suspend fun <T> HttpResponse.decodeOrNull(deserializer: DeserializationStrategy<T>): T? {
        val text = bodyAsText()
        if (text.isBlank()) return null
        return runCatching { RelayerJson.decodeFromString(deserializer, text) }
            .onFailure { Log.w(TAG, "RelayerClient: body no decodificable (HTTP ${status.value}): ${it.message}") }
            .getOrNull()
    }

    private fun invalidBodyMessage(status: HttpStatusCode): String =
        "El relayer respondió sin JSON válido (HTTP ${status.value}); reintenta en unos segundos"

    /**
     * Excepciones de transporte de Ktor → [RaizErrorCode.NETWORK_ERROR].
     *
     * Los timeouts (request/socket/connect) NO significan "no se pudo contactar":
     * el relayer pudo recibir la petición y seguir firmando (cola + propagación
     * RPC, hasta 90 s). El mensaje pide reintentar con el MISMO intento — misma
     * `idempotency-key` — para recibir el resultado cacheado en vez de firmar otra
     * vez. `io.ktor.client.network.sockets.SocketTimeoutException` es, en JVM,
     * un typealias de [java.net.SocketTimeoutException]; se captura esta última.
     */
    private fun mapTransportException(e: Exception): RaizResult.Error = when (e) {
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        -> RaizResult.Error(RaizErrorCode.NETWORK_ERROR, MSG_RELAYER_STILL_PROCESSING)
        else -> RaizResult.Error(RaizErrorCode.NETWORK_ERROR, MSG_RELAYER_UNREACHABLE)
    }

    /**
     * Traduce `error.code` del relayer a [RaizResult] según la tabla de
     * `raiz-relayer/docs/SESION_B_APP.md` § 2. Única función de mapeo — todo
     * error HTTP del relayer que el endpoint no absorba como idempotente pasa
     * por aquí.
     */
    private fun mapRelayerError(status: HttpStatusCode, envelope: RelayerEnvelope): RaizResult<RelayerEnvelope> {
        val body = envelope.error
        val message = body?.message ?: "Error del relayer (HTTP ${status.value})"
        return when (body?.code) {
            "UNAUTHORIZED_APP" -> RaizResult.Error(RaizErrorCode.UNAUTHORIZED, MSG_APP_UNAUTHORIZED)
            // Relayer mal configurado (no es admin) / trustline desautorizada: no reintentar.
            "TRUSTLINE_DEAUTHORIZED", "UNAUTHORIZED_ADMIN" -> RaizResult.Error(RaizErrorCode.UNAUTHORIZED, message)
            "VALIDATION_ERROR", "PAYLOAD_TOO_LARGE" -> RaizResult.Error(RaizErrorCode.PARSE_ERROR, message)
            "RATE_LIMITED" -> RaizResult.Error(RaizErrorCode.RATE_LIMITED, withRetryAfter(message, body?.details))
            "ACCOUNT_NOT_FOUND", "BARRIO_NOT_FOUND", "BARRIO_ADMIN_NOT_SET", "NOT_FOUND", "NO_TRUSTLINE" ->
                RaizResult.Error(RaizErrorCode.NOT_FOUND, message)
            "CONTRACT_ERROR", "TX_FAILED" -> RaizResult.Error(RaizErrorCode.SIMULATION_FAILED, message)
            // Sin USDC en el admin: es un saldo insuficiente (del faucet), no un fallo de red.
            "FAUCET_EMPTY" -> RaizResult.Error(RaizErrorCode.INSUFFICIENT_BALANCE, message)
            // La tx puede aplicarse después: mensaje con el hash y la instrucción de reintentar con la misma key.
            "TX_TIMEOUT" -> RaizResult.Error(RaizErrorCode.NETWORK_ERROR, txTimeoutMessage(message, body?.details))
            "RPC_UNREACHABLE", "QUEUE_FULL", "RESTORE_REQUIRED" ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, message)
            // MERCHANT_EXISTS / ALREADY_RESIDENT los absorbe su endpoint (idempotentCodes); si llegan aquí
            // es desde otro endpoint y no hay tratamiento especial. IDEMPOTENCY_MISMATCH = bug de reutilización de key.
            "MERCHANT_EXISTS", "ALREADY_RESIDENT", "IDEMPOTENCY_MISMATCH", "INTERNAL" ->
                RaizResult.Error(RaizErrorCode.UNKNOWN, message)
            else -> RaizResult.Error(RaizErrorCode.UNKNOWN, message)
        }
    }

    /**
     * Añade "(reintenta en Ns)" con `details.retryAfterSeconds` — salvo que el
     * relayer ya lo diga en el propio mensaje ("Cupo agotado (…). Reintenta en
     * 120 s."), para no duplicar la instrucción.
     */
    private fun withRetryAfter(message: String, details: JsonObject?): String {
        val seconds = (details?.get("retryAfterSeconds") as? JsonPrimitive)?.longOrNull ?: return message
        if (message.contains("reintenta en", ignoreCase = true)) return message
        return "$message (reintenta en ${seconds}s)"
    }

    /**
     * Mensaje de `TX_TIMEOUT`: si `details.txHash` viene informado, la tx ya se
     * envió a la red y puede aplicarse; se muestra el hash abreviado y se pide
     * reintentar con la MISMA key (el relayer cachea también este caso). Sin
     * hash se devuelve el mensaje del relayer tal cual.
     */
    private fun txTimeoutMessage(relayerMessage: String, details: JsonObject?): String {
        val hash = (details?.get("txHash") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return relayerMessage
        return "$MSG_TX_PENDING_PREFIX (hash ${abbreviateHash(hash)}). " +
            "Espera un minuto y reintenta: el relayer devolverá el mismo resultado."
    }

    private fun abbreviateHash(hash: String): String =
        if (hash.length <= 12) hash else hash.take(8) + "…" + hash.takeLast(4)

    companion object {
        private const val TAG = "RAIZ"
        private const val HEALTH_CACHE_MS = 30_000L
        private const val HEALTH_REQUEST_TIMEOUT_MS = 10_000L
        private const val HEALTH_CONNECT_TIMEOUT_MS = 5_000L
        private const val HEADER_APP_KEY = "x-raiz-app-key"
        private const val HEADER_IDEMPOTENCY_KEY = "idempotency-key"

        /** Límite del relayer para `idempotency-key` (README § Idempotencia). */
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 64

        /** Mensaje de `401 UNAUTHORIZED_APP` (API key vacía/incorrecta). */
        const val MSG_APP_UNAUTHORIZED = "La app no está autorizada en el relayer"

        /** Fallo de transporte que NO es timeout (DNS, conexión rechazada, sin red…). */
        const val MSG_RELAYER_UNREACHABLE = "No se pudo contactar con el relayer"

        /** Timeout de Ktor: la operación puede seguir en curso en el relayer. */
        const val MSG_RELAYER_STILL_PROCESSING =
            "El relayer sigue procesando la operación; reintenta en unos segundos (mismo intento)"

        /** Prefijo del mensaje de `TX_TIMEOUT` con `details.txHash`. */
        const val MSG_TX_PENDING_PREFIX = "La transacción se envió y sigue pendiente"

        /** UUID v4 (36 chars, dentro del límite de 64): una key nueva por intento de usuario. */
        fun newIdempotencyKey(): String = UUID.randomUUID().toString()

        /**
         * true si [result] es un `NETWORK_ERROR` de la familia "la transacción
         * puede estar en vuelo" (timeout de Ktor o `TX_TIMEOUT` con hash): el
         * llamador debe reintentar con la MISMA key y, en flujos sin guard
         * on-chain (Yield), esperar antes de habilitar el botón.
         */
        fun isPendingTransactionError(result: RaizResult<*>): Boolean =
            result is RaizResult.Error &&
                result.code == RaizErrorCode.NETWORK_ERROR &&
                (result.message == MSG_RELAYER_STILL_PROCESSING || result.message.startsWith(MSG_TX_PENDING_PREFIX))
    }
}
