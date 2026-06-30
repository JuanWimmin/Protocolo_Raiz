package com.raiz.app.data.stellar

import android.util.Log
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.soneso.stellar.sdk.AssetTypeCreditAlphaNum4
import com.soneso.stellar.sdk.ChangeTrustOperation
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.PaymentOperation
import com.soneso.stellar.sdk.TransactionBuilder
import com.soneso.stellar.sdk.horizon.HorizonServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stream del balance USDC de una cuenta Stellar y operaciones Horizon auxiliares.
 *
 * El SDK Soneso NO expone SSE (Horizon `stream`) en `PaymentsRequestBuilder`,
 * así que usamos polling cada `intervalMs` con `accounts().account(id)`.
 * 5s es suficiente para el demo; cuando integremos pagos reales podemos bajar
 * a 2s o cambiar a SSE manual con OkHttp si la libertad de Horizon lo soporta.
 *
 * Devuelve `0L` ante cualquier error de red (no rompe el flow — el VM puede
 * mostrar "sin red" si necesita).
 *
 * ── NOTA DNS (TAREA 2) ──────────────────────────────────────────────────────
 * En Android se pueden ver errores transitorios del tipo:
 *   "Unable to resolve host 'horizon-testnet.stellar.org': No address associated
 *    with hostname"
 * causados por breves pérdidas de conectividad (cambio WiFi→datos, VPN, etc.)
 * El polling [usdcBalanceFlow] los absorbe (devuelve 0L y reintenta en el
 * próximo tick). Las operaciones de escritura ([enableUsdcTrustline],
 * [sendUsdcFromAdmin]) usan [withRetryOnDns] con backoff 1s/2s.
 */
@Singleton
class HorizonStream @Inject constructor(
    private val deploymentsLoader: DeploymentsLoader,
) {
    private val deployments: Deployments by lazy { deploymentsLoader.load() }

    /**
     * Emisor del USDC para operaciones clásicas (trustline, balance, faucet).
     * Tras el re-deploy a DeFindex será el USDC de Blend (`usdc_issuer` en
     * deployments.json); si no está, cae al admin (compat con el deploy previo).
     */
    private val usdcIssuer: String by lazy { deployments.usdcIssuer ?: deployments.admin }

    private val horizonServer: HorizonServer by lazy {
        val url = when (deployments.network) {
            "testnet" -> RaizConstants.TESTNET_HORIZON_URL
            else -> RaizConstants.TESTNET_HORIZON_URL
        }
        HorizonServer(
            url,
            HorizonServer.createDefaultHttpClient(),
            HorizonServer.createSubmitHttpClient(),
        )
    }

    /**
     * Flow del balance USDC en stroops. Polling cada `intervalMs`.
     * Emite `0L` si la cuenta no tiene trustline USDC o el balance es nulo.
     * `distinctUntilChanged` evita re-emisiones espurias del mismo valor.
     */
    fun usdcBalanceFlow(
        accountId: String,
        intervalMs: Long = 5_000L,
    ): Flow<Long> = flow {
        while (coroutineContext.isActive) {
            val stroops = fetchUsdcBalance(accountId)
            emit(stroops)
            delay(intervalMs)
        }
    }.distinctUntilChanged()

    /** One-shot balance USDC (sin polling). Útil para verificaciones puntuales. */
    suspend fun getUsdcBalance(accountId: String): Long = withContext(Dispatchers.IO) {
        fetchUsdcBalance(accountId)
    }

    private suspend fun fetchUsdcBalance(accountId: String): Long {
        return runCatching {
            val account = horizonServer.accounts().account(accountId)
            val usdc = account.balances.firstOrNull { b ->
                b.assetCode == "USDC" && b.assetIssuer == usdcIssuer
            }
            usdc?.balance?.toUsdcStroops() ?: 0L
        }.getOrElse { e ->
            Log.w(TAG, "horizon poll falló: ${e.message}")
            0L
        }
    }

    /**
     * Historial de pagos de una cuenta. Implementado con HTTP directo a
     * Horizon en lugar del SDK Soneso porque el deserializador del SDK
     * crashea con operaciones `invoke_host_function` que reportan
     * `AssetContractBalanceChange` sin el campo `to`. Esos sub-objetos no
     * son pagos clásicos sino emisiones del contrato — los filtramos.
     *
     * Devuelve solo operaciones tipo `payment` (transfers clásicos).
     * Los pagos hechos vía contrato Soroban (pay_merchant → usdc.transfer)
     * SÍ aparecen aquí porque cada transfer genera una operación payment.
     */
    suspend fun paymentHistory(
        accountId: String,
        limit: Int = 20,
    ): RaizResult<List<PaymentRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = when (deployments.network) {
                "testnet" -> RaizConstants.TESTNET_HORIZON_URL
                else -> RaizConstants.TESTNET_HORIZON_URL
            }
            val urlStr = "$baseUrl/accounts/$accountId/payments?order=desc&limit=$limit"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            // Cuenta nueva sin fondear → Horizon responde 404 (la cuenta aún no
            // existe on-chain). HttpURLConnection lanzaría FileNotFoundException con
            // la URL como mensaje (sin "404"), así que lo atajamos por responseCode:
            // no es un error, simplemente no hay historial todavía → lista vacía.
            if (conn.responseCode == 404) {
                conn.disconnect()
                return@runCatching emptyList<PaymentRecord>()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = jsonCodec.parseToJsonElement(body).jsonObject
            val records = json["_embedded"]?.jsonObject
                ?.get("records")?.jsonArray
                ?: JsonArray(emptyList())

            // Cada operation puede expandir a 0..N PaymentRecord:
            //   - `payment` clásico → 1 record.
            //   - `invoke_host_function` → 1 record por cada transfer
            //     dentro de `asset_balance_changes` (ignora burn/mint).
            records.flatMap { el ->
                runCatching { recordsFromOp(el.jsonObject, accountId) }.getOrElse { emptyList() }
            }
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                val msg = e.message ?: ""
                // Cuenta nueva sin fondear → Horizon responde 404 (Resource Missing).
                // NO es un error: simplemente aún no hay historial. Devolvemos lista
                // vacía para que la UI muestre el estado "sin transacciones" en vez
                // de "No pudimos cargar el historial".
                if (e is java.io.FileNotFoundException ||
                    msg.contains("404") ||
                    msg.contains("not found", ignoreCase = true) ||
                    msg.contains("Resource Missing", ignoreCase = true)
                ) {
                    Log.i(TAG, "paymentHistory: cuenta sin actividad todavía → historial vacío")
                    RaizResult.Success(emptyList())
                } else {
                    Log.w(TAG, "paymentHistory falló: ${e.message}")
                    RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "horizon error")
                }
            },
        )
    }

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    /**
     * Convierte una operation JSON de Horizon en 0..N PaymentRecord según su tipo.
     */
    private fun recordsFromOp(record: JsonObject, observerAccount: String): List<PaymentRecord> {
        val type = record["type"]?.jsonPrimitive?.contentOrNull
        val txHash = record["transaction_hash"]?.jsonPrimitive?.contentOrNull ?: ""
        val createdAt = record["created_at"]?.jsonPrimitive?.contentOrNull ?: ""

        return when (type) {
            "payment" -> {
                val from = record["from"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val to = record["to"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val amount = record["amount"]?.jsonPrimitive?.contentOrNull ?: "0"
                val assetType = record["asset_type"]?.jsonPrimitive?.contentOrNull
                val assetCode = record["asset_code"]?.jsonPrimitive?.contentOrNull
                listOf(
                    PaymentRecord(
                        txHash = txHash,
                        from = from,
                        to = to,
                        amountStroops = amount.toUsdcStroops(),
                        assetCode = if (assetType == "native") "XLM" else (assetCode ?: "?"),
                        createdAt = createdAt,
                        isOutgoing = from == observerAccount,
                    ),
                )
            }

            "invoke_host_function" -> {
                // Pagos hechos vía contrato Soroban (pay_merchant, etc.) reportan
                // los movimientos USDC dentro de `asset_balance_changes`. Solo
                // listamos los de tipo `transfer` — ignoramos burns (fees del
                // protocolo) y mints (issuer ↔ classic).
                val changes = record["asset_balance_changes"]?.jsonArray ?: return emptyList()
                changes.mapNotNull { c ->
                    val ch = c.jsonObject
                    if (ch["type"]?.jsonPrimitive?.contentOrNull != "transfer") return@mapNotNull null
                    val from = ch["from"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val to = ch["to"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val amount = ch["amount"]?.jsonPrimitive?.contentOrNull ?: "0"
                    val assetCode = ch["asset_code"]?.jsonPrimitive?.contentOrNull
                    val assetType = ch["asset_type"]?.jsonPrimitive?.contentOrNull
                    PaymentRecord(
                        txHash = txHash,
                        from = from,
                        to = to,
                        amountStroops = amount.toUsdcStroops(),
                        assetCode = if (assetType == "native") "XLM" else (assetCode ?: "?"),
                        createdAt = createdAt,
                        isOutgoing = from == observerAccount,
                    )
                }
            }

            else -> emptyList()
        }
    }

    /**
     * Verifica si la cuenta tiene trustline al USDC del admin del protocolo.
     * Sin esto, no puede recibir USDC y los pagos a su address fallarán.
     */
    suspend fun hasUsdcTrustline(accountId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val account = horizonServer.accounts().account(accountId)
            account.balances.any { b ->
                b.assetCode == "USDC" && b.assetIssuer == usdcIssuer
            }
        }.getOrElse { false }
    }

    /**
     * Activa el trustline USDC para la cuenta del `signer`. Construye y firma
     * una `ChangeTrustOperation` y la envía a Horizon. Tras esto, la cuenta
     * puede recibir USDC (y mostrarse balance>0 en lugar del default 0).
     *
     * Errores comunes:
     *  - InsufficientBalance: la cuenta debe tener al menos ~1 XLM de reserve.
     *    (Cuentas creadas con friendbot vienen con 10000 XLM, no es problema.)
     *  - El sequence number lo lee Horizon en loadAccount; refrescar si falla.
     *
     * Reintenta hasta 3 veces ante errores DNS transitorios (backoff 1s/2s).
     */
    suspend fun enableUsdcTrustline(signer: KeyPair): RaizResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withRetryOnDns {
                val accountId = signer.getAccountId()
                val source = horizonServer.loadAccount(accountId)
                val asset = AssetTypeCreditAlphaNum4("USDC", usdcIssuer)
                val op = ChangeTrustOperation(asset, ChangeTrustOperation.MAX_LIMIT)
                val tx = TransactionBuilder(source, Network.TESTNET)
                    .setBaseFee(100L)
                    .addOperation(op)
                    .setTimeout(60L)
                    .build()
                tx.sign(signer)
                // submitTransaction de Horizon espera el XDR envelope base64.
                horizonServer.submitTransaction(tx.toEnvelopeXdrBase64())
                Log.i(TAG, "Trustline USDC activado para $accountId")
            }
        }.fold(
            onSuccess = { RaizResult.Success(Unit) },
            onFailure = { e ->
                Log.e(TAG, "enableUsdcTrustline falló: ${e.message}")
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "horizon error")
            },
        )
    }

    /**
     * ¿Existe la cuenta on-chain? Una wallet recién creada (sin XLM) NO existe
     * en Stellar hasta que reciba el mínimo de XLM. 404 = no existe; cualquier
     * otro error de red lo tratamos también como "no existe" para activar el
     * banner de friendbot (preferimos un falso positivo a bloquear al usuario).
     */
    suspend fun accountExists(accountId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            horizonServer.accounts().account(accountId)
            true
        }.getOrElse { e ->
            Log.i(TAG, "accountExists($accountId) → false (${e.message})")
            false
        }
    }

    /**
     * Fondea una cuenta nueva con friendbot (10000 XLM testnet). Idempotente
     * solo en el sentido de que si la cuenta ya existe, friendbot responde 400
     * y devolvemos error — pero no rompe nada.
     */
    suspend fun fundWithFriendbot(accountId: String): RaizResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://friendbot.stellar.org/?addr=$accountId"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code !in 200..299) {
                // Si la cuenta YA existe, friendbot responde 400 con "already funded"
                // / "op_already_exists". NO es un fallo: la cuenta ya está fondeada,
                // lo tratamos como éxito para no mostrar un error confuso.
                val low = body.lowercase()
                if ("already funded" in low || "already exists" in low || "op_already_exists" in low) {
                    Log.i(TAG, "Friendbot: $accountId ya estaba fondeada (tratado como OK)")
                    return@runCatching
                }
                throw RuntimeException("friendbot HTTP $code: ${body.take(200)}")
            }
            Log.i(TAG, "Friendbot fondeó $accountId")
        }.fold(
            onSuccess = { RaizResult.Success(Unit) },
            onFailure = { e ->
                Log.e(TAG, "fundWithFriendbot falló: ${e.message}")
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "friendbot error")
            },
        )
    }

    /**
     * Envía USDC del admin del protocolo a `destination` como Payment classic.
     * Usado SOLO como faucet demo: cuando un usuario nuevo necesita USDC para
     * probar el flow de pago. En producción no existe — los anchors (SEP-24)
     * harían el on-ramp desde fiat.
     *
     * Reintenta hasta 3 veces ante errores DNS transitorios (backoff 1s/2s).
     */
    suspend fun sendUsdcFromAdmin(
        adminSigner: KeyPair,
        destination: String,
        amountStroops: Long,
    ): RaizResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withRetryOnDns {
                val source = horizonServer.loadAccount(adminSigner.getAccountId())
                val asset = AssetTypeCreditAlphaNum4("USDC", usdcIssuer)
                val amount = amountStroops.toUsdcDecimal()
                val op = PaymentOperation(destination, asset, amount)
                val tx = TransactionBuilder(source, Network.TESTNET)
                    .setBaseFee(100L)
                    .addOperation(op)
                    .setTimeout(60L)
                    .build()
                tx.sign(adminSigner)
                horizonServer.submitTransaction(tx.toEnvelopeXdrBase64())
                Log.i(TAG, "Admin envió $amount USDC a $destination")
            }
        }.fold(
            onSuccess = { RaizResult.Success(Unit) },
            onFailure = { e ->
                Log.e(TAG, "sendUsdcFromAdmin falló: ${e.message}")
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "horizon error")
            },
        )
    }

    /**
     * Reintento con backoff exponencial para errores DNS/conexión transitorios
     * en llamadas a Horizon.
     *
     * Errores que se reintentan:
     *   - UnknownHostException ("Unable to resolve host '...'")
     *   - ConnectException ("Failed to connect to ...")
     *   - SocketTimeoutException
     *   - Mensajes con "connection reset", "eof", "connect timed out"
     *
     * Errores que se propagan inmediatamente (no reintentar):
     *   - Errores de protocolo Horizon (HTTP 4xx/5xx ya parseados)
     *   - CancellationException (scope cancelado)
     *
     * Backoff: 1s tras el intento 1, 2s tras el intento 2. Total: 3 intentos.
     */
    private suspend fun <T> withRetryOnDns(
        maxAttempts: Int = 3,
        block: suspend () -> T,
    ): T {
        var lastEx: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message.orEmpty().lowercase()
                val cause = e.cause
                val isTransient = "unable to resolve" in msg ||
                    "no address associated" in msg ||
                    "failed to connect" in msg ||
                    "connection reset" in msg ||
                    "eof" in msg ||
                    "connect timed out" in msg ||
                    e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    e is java.net.SocketTimeoutException ||
                    cause is java.net.UnknownHostException ||
                    cause is java.net.ConnectException
                if (!isTransient || attempt == maxAttempts - 1) throw e
                lastEx = e
                Log.w(TAG, "Horizon transitorio (intento ${attempt + 1}/$maxAttempts): ${e.message}")
                delay(1_000L shl attempt) // intento 0→1s, intento 1→2s
            }
        }
        throw lastEx ?: error("withRetryOnDns: sin excepción tras $maxAttempts intentos")
    }

    /** Long stroops → "10.0000000" (7 decimales fijos) que Stellar exige. */
    private fun Long.toUsdcDecimal(): String {
        val unit = this / RaizConstants.USDC_STROOPS_PER_UNIT
        val frac = (this % RaizConstants.USDC_STROOPS_PER_UNIT)
            .toString()
            .padStart(RaizConstants.USDC_DECIMALS, '0')
        return "$unit.$frac"
    }

    /**
     * Convierte un balance USDC en string decimal ("5.0000000") a Long stroops.
     * Stellar usa 7 decimales fijos; toleramos menos decimales pero no más.
     */
    private fun String.toUsdcStroops(): Long {
        val parts = split(".")
        val intPart = parts[0].toLong() * RaizConstants.USDC_STROOPS_PER_UNIT
        val fracPart = parts.getOrNull(1)
            ?.padEnd(RaizConstants.USDC_DECIMALS, '0')
            ?.take(RaizConstants.USDC_DECIMALS)
            ?.toLong()
            ?: 0L
        return intPart + fracPart
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
