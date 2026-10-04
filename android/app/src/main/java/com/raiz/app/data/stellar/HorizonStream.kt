package com.raiz.app.data.stellar

import android.util.Log
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.soneso.stellar.sdk.Asset
import com.soneso.stellar.sdk.AssetTypeCreditAlphaNum4
import com.soneso.stellar.sdk.ChangeTrustOperation
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.PathPaymentStrictSendOperation
import com.soneso.stellar.sdk.TransactionBuilder
import com.soneso.stellar.sdk.horizon.HorizonServer
import com.soneso.stellar.sdk.horizon.exceptions.NetworkException
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
 * Cotización de [HorizonStream.quoteStrictSend]: el [quote] (montos en stroops, ver
 * [SwapMath.Quote]) junto con el [path] de assets del SDK (`com.soneso.stellar.sdk.Asset`,
 * distinto de `com.soneso.stellar.sdk.horizon.responses.Asset` que devuelve Horizon) que
 * hay que reenviar tal cual a [HorizonStream.pathPaymentStrictSend] — así la operación usa
 * el MISMO camino que se cotizó, no uno que Horizon pudiera elegir distinto al enviar.
 */
data class StrictSendQuote(
    val quote: SwapMath.Quote,
    val path: List<Asset>,
)

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
 * próximo tick). La escritura ([enableUsdcTrustline]) usa [withRetryOnDns]
 * con backoff 1s/2s.
 */
@Singleton
class HorizonStream @Inject constructor(
    private val deploymentsLoader: DeploymentsLoader,
) {
    private val deployments: Deployments by lazy { deploymentsLoader.load() }

    /**
     * Emisor del USDC para operaciones clásicas (trustline, balance, faucet).
     * Es el USDC de Blend (`usdc_issuer` en deployments.json, el mismo SAC
     * que custodia el fondo desde el Camino A); si no está, cae al admin
     * (compat con el deploy previo).
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
     * Flow del balance USDC (el de Blend, el que usa el pool) en stroops. Delega en
     * [assetBalanceFlow] — se conserva como método propio porque es, con diferencia,
     * el asset que más se consulta en la app (BalanceCard, pagos, etc.).
     */
    fun usdcBalanceFlow(
        accountId: String,
        intervalMs: Long = 5_000L,
    ): Flow<Long> = assetBalanceFlow(accountId, "USDC", usdcIssuer, intervalMs)

    /** One-shot balance USDC de Blend (sin polling). Útil para verificaciones puntuales. */
    suspend fun getUsdcBalance(accountId: String): Long = getAssetBalance(accountId, "USDC", usdcIssuer)

    /**
     * Flow del balance de cualquier asset clásico (`code`/`issuer`) en stroops.
     * Mismo patrón de polling que [usdcBalanceFlow] — pensado para el USDC del
     * anchor de prueba (D3, `RaizConstants.ANCHOR_USDC_CODE`/`ANCHOR_USDC_ISSUER`),
     * distinto del USDC de Blend que usa el pool.
     *
     * Emite `0L` si la cuenta no tiene trustline a ese asset o el balance es nulo.
     * `distinctUntilChanged` evita re-emisiones espurias del mismo valor.
     */
    fun assetBalanceFlow(
        accountId: String,
        code: String,
        issuer: String,
        intervalMs: Long = 5_000L,
    ): Flow<Long> = flow {
        while (coroutineContext.isActive) {
            val stroops = fetchAssetBalance(accountId, code, issuer)
            emit(stroops)
            delay(intervalMs)
        }
    }.distinctUntilChanged()

    /** One-shot balance de cualquier asset clásico (sin polling). */
    suspend fun getAssetBalance(accountId: String, code: String, issuer: String): Long =
        withContext(Dispatchers.IO) {
            fetchAssetBalance(accountId, code, issuer)
        }

    /**
     * Igual que [getAssetBalance] pero distingue "no se pudo leer" (`null`) de "saldo 0"
     * (sin trustline o balance 0). Para lecturas puntuales cuyo resultado decide qué ve el
     * usuario (p. ej. si hay USDC del anchor que convertir): un fallo de red no debe
     * convertirse en un 0 falso. Reintenta errores transitorios ([withRetryOnDns]).
     */
    suspend fun getAssetBalanceOrNull(accountId: String, code: String, issuer: String): Long? =
        withContext(Dispatchers.IO) {
            runCatching {
                withRetryOnDns {
                    val account = horizonServer.accounts().account(accountId)
                    account.balances.firstOrNull { b -> b.assetCode == code && b.assetIssuer == issuer }
                        ?.balance?.toUsdcStroops() ?: 0L
                }
            }.getOrElse { e ->
                Log.w(TAG, "getAssetBalanceOrNull falló ($code): ${e.message}")
                null
            }
        }

    private suspend fun fetchAssetBalance(accountId: String, code: String, issuer: String): Long {
        return runCatching {
            val account = horizonServer.accounts().account(accountId)
            val asset = account.balances.firstOrNull { b ->
                b.assetCode == code && b.assetIssuer == issuer
            }
            asset?.balance?.toUsdcStroops() ?: 0L
        }.getOrElse { e ->
            Log.w(TAG, "horizon poll falló ($code/$issuer): ${e.message}")
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
                val assetIssuer = record["asset_issuer"]?.jsonPrimitive?.contentOrNull
                listOf(
                    PaymentRecord(
                        txHash = txHash,
                        from = from,
                        to = to,
                        amountStroops = amount.toUsdcStroops(),
                        assetCode = if (assetType == "native") "XLM" else (assetCode ?: "?"),
                        createdAt = createdAt,
                        isOutgoing = from == observerAccount,
                        assetIssuer = if (assetType == "native") null else assetIssuer,
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
                    val assetIssuer = ch["asset_issuer"]?.jsonPrimitive?.contentOrNull
                    PaymentRecord(
                        txHash = txHash,
                        from = from,
                        to = to,
                        amountStroops = amount.toUsdcStroops(),
                        assetCode = if (assetType == "native") "XLM" else (assetCode ?: "?"),
                        createdAt = createdAt,
                        isOutgoing = from == observerAccount,
                        assetIssuer = if (assetType == "native") null else assetIssuer,
                    )
                }
            }

            else -> emptyList()
        }
    }

    /**
     * Último pago ENTRANTE de `accountId` en un asset clásico dado (`code`/`issuer`),
     * ordenado por Horizon `desc` (más reciente primero). Pensado como respaldo para
     * hallar el `txHash` de un depósito del anchor de prueba (D3) cuando el propio
     * anchor no lo informa en `stellar_transaction_id`. `null` si no hay coincidencias
     * en el historial reciente (últimos 20 pagos) — no implica que no exista, solo que
     * no está entre los más recientes.
     *
     * Filtra por `assetCode` (como pide el flujo D3); si Horizon informó `asset_issuer`
     * en el record, también debe coincidir con `issuer` — evita confundir dos assets
     * con el mismo código y emisor distinto (p. ej. el USDC del anchor vs. el de Blend).
     */
    suspend fun latestIncomingPayment(accountId: String, code: String, issuer: String): RaizResult<PaymentRecord?> =
        when (val result = paymentHistory(accountId, limit = 20)) {
            is RaizResult.Success -> RaizResult.Success(
                result.data.firstOrNull { record ->
                    !record.isOutgoing &&
                        record.assetCode == code &&
                        (record.assetIssuer == null || record.assetIssuer == issuer)
                },
            )
            is RaizResult.Error -> result
        }

    /**
     * Cotiza la conversión "USDC del anchor de prueba → USDC del fondo (Blend)" (WP3
     * stretch) contra `/paths/strict-send` de Horizon: cuánto `USDC:destIssuer` se
     * recibiría al enviar exactamente `sendStroops` de `USDC:sendIssuer`.
     *
     * Elige el record con MAYOR `destinationAmount` (Horizon puede devolver varios
     * caminos; el pool directo verificado hoy entre los dos USDC de testnet es el único,
     * pero por si en el futuro hay más de uno tomamos el mejor). El `path` de la
     * respuesta va en [StrictSendQuote.path], en el mismo orden, para reenviarlo
     * intacto a [pathPaymentStrictSend].
     *
     * `RaizResult.Error(NOT_FOUND, …)` si Horizon no devuelve ningún camino (sin
     * liquidez ahora mismo). Errores de red → `NETWORK_ERROR` (con reintento DNS
     * vía [withRetryOnDns]).
     */
    suspend fun quoteStrictSend(
        sendCode: String,
        sendIssuer: String,
        sendStroops: Long,
        destCode: String,
        destIssuer: String,
    ): RaizResult<StrictSendQuote> = withContext(Dispatchers.IO) {
        runCatching {
            withRetryOnDns {
                horizonServer.strictSendPaths()
                    .sourceAsset("credit_alphanum4", sendCode, sendIssuer)
                    .sourceAmount(SwapMath.stroopsToAmount(sendStroops))
                    .destinationAssets(listOf(Triple("credit_alphanum4", destCode, destIssuer)))
                    .execute()
                    .records
            }
        }.fold(
            onSuccess = { records ->
                val best = records.maxByOrNull { it.destinationAmount.toUsdcStroops() }
                if (best == null) {
                    RaizResult.Error(
                        RaizErrorCode.NOT_FOUND,
                        "No hay liquidez en testnet para convertir este USDC ahora.",
                    )
                } else {
                    val destStroops = best.destinationAmount.toUsdcStroops()
                    val destMinStroops = SwapMath.applySlippageBps(destStroops, SwapMath.SLIPPAGE_BPS)
                    val path = best.path.map { it.toOperationAsset() }
                    RaizResult.Success(
                        StrictSendQuote(
                            quote = SwapMath.Quote(
                                sendStroops = sendStroops,
                                destStroops = destStroops,
                                destMinStroops = destMinStroops,
                                hops = path.size,
                            ),
                            path = path,
                        ),
                    )
                }
            },
            onFailure = { e ->
                Log.w(TAG, "quoteStrictSend falló ($sendCode→$destCode): ${e.message}")
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "horizon error")
            },
        )
    }

    /**
     * Convierte una `Asset` de RESPUESTA de Horizon (`horizon.responses.Asset`, la que
     * trae `PathResponse.path`) en la `Asset` de OPERACIÓN del SDK
     * (`com.soneso.stellar.sdk.Asset`, la que espera `PathPaymentStrictSendOperation`).
     * Son dos clases distintas con el mismo nombre corto — de ahí el import calificado.
     */
    private fun com.soneso.stellar.sdk.horizon.responses.Asset.toOperationAsset(): Asset =
        if (assetType == "native") {
            Asset.createNativeAsset()
        } else {
            Asset.createNonNativeAsset(
                assetCode ?: error("asset credit sin assetCode en la respuesta de Horizon"),
                assetIssuer ?: error("asset credit sin assetIssuer en la respuesta de Horizon"),
            )
        }

    /**
     * Ejecuta la conversión "USDC del anchor de prueba → USDC del fondo (Blend)" (WP3
     * stretch) con una `PathPaymentStrictSendOperation` NO custodial: la firma el propio
     * [signer] y el destino es su MISMA cuenta (`signer.getAccountId()`) — nunca se mueve
     * a una cuenta ajena, solo cambia de asset. `path` debe ser el mismo que devolvió
     * [quoteStrictSend] para esa cotización (no se vuelve a pedir aquí).
     *
     * Devuelve el hash de la transacción si Horizon la acepta. Errores de Horizon
     * (result_codes de la operación, en el body de la excepción) se traducen a
     * `RaizErrorCode`: `op_underfunded` → `INSUFFICIENT_BALANCE`; `op_too_few_offers` /
     * `op_under_dest_min` (la liquidez se movió entre cotizar y enviar) → `NETWORK_ERROR`;
     * `op_no_trust` (falta la trustline al USDC destino) → `NOT_FOUND`; el resto →
     * `UNKNOWN` con el fragmento `result_codes` acotado a 200 caracteres. No se loguea el XDR.
     *
     * Un path payment NO es idempotente: la transacción se construye y firma UNA vez (solo
     * `loadAccount`, que es lectura, se reintenta) y el sobre se envía una sola vez. Si la
     * conexión se corta sin respuesta de Horizon, se consulta la tx por su hash antes de
     * declarar fallo — puede haber entrado igualmente.
     */
    suspend fun pathPaymentStrictSend(
        signer: KeyPair,
        sendCode: String,
        sendIssuer: String,
        sendStroops: Long,
        destCode: String,
        destIssuer: String,
        destMinStroops: Long,
        path: List<Asset>,
    ): RaizResult<String> = withContext(Dispatchers.IO) {
        val accountId = signer.getAccountId()
        // 1. Construir y firmar UNA sola vez. Reconstruir tras un corte de red (secuencia
        //    nueva) enviaría una segunda conversión.
        val (envelope, txHash) = try {
            val source = withRetryOnDns { horizonServer.loadAccount(accountId) }
            val op = PathPaymentStrictSendOperation(
                sendAsset = Asset.createNonNativeAsset(sendCode, sendIssuer),
                sendAmount = SwapMath.stroopsToAmount(sendStroops),
                destination = accountId,
                destAsset = Asset.createNonNativeAsset(destCode, destIssuer),
                destMin = SwapMath.stroopsToAmount(destMinStroops),
                path = path,
            )
            val tx = TransactionBuilder(source, Network.TESTNET)
                .setBaseFee(100L)
                .addOperation(op)
                .setTimeout(60L)
                .build()
            tx.sign(signer)
            tx.toEnvelopeXdrBase64() to tx.hashHex()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "pathPaymentStrictSend: no se pudo preparar la tx ($sendCode→$destCode): ${e.message}")
            return@withContext RaizResult.Error(
                RaizErrorCode.NETWORK_ERROR,
                "No se pudo preparar la conversión. Revisa la red y reintenta.",
            )
        }

        // 2. Enviar el MISMO sobre una vez.
        try {
            val response = horizonServer.submitTransaction(envelope)
            Log.i(TAG, "pathPaymentStrictSend: $sendCode→$destCode OK para $accountId, hash=${response.hash}")
            RaizResult.Success(response.hash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val httpBody = (e as? NetworkException)?.body
            if (httpBody.isNullOrBlank()) {
                // Sin respuesta HTTP de Horizon (corte de red, timeout): resultado ambiguo.
                if (transactionSucceeded(txHash) == true) {
                    Log.i(TAG, "pathPaymentStrictSend: respuesta perdida pero la tx entró, hash=$txHash")
                    RaizResult.Success(txHash)
                } else {
                    Log.e(TAG, "pathPaymentStrictSend: sin confirmación ($sendCode→$destCode): ${e.message}")
                    RaizResult.Error(
                        RaizErrorCode.NETWORK_ERROR,
                        "No se pudo confirmar la conversión. Revisa tu saldo antes de reintentar.",
                    )
                }
            } else {
                mapPathPaymentError(httpBody, e)
            }
        }
    }

    /**
     * Traduce los `result_codes` de Horizon a [RaizResult.Error]. Viven en el body de la
     * excepción: el mensaje del SDK solo dice "Bad request (code: 400)". Nunca vuelca el XDR,
     * solo el fragmento `result_codes` acotado.
     */
    private fun mapPathPaymentError(body: String, e: Exception): RaizResult.Error {
        val lower = body.lowercase()
        return when {
            "op_underfunded" in lower ->
                RaizResult.Error(RaizErrorCode.INSUFFICIENT_BALANCE, "No tienes suficiente USDC del anchor.")
            "op_too_few_offers" in lower || "op_under_dest_min" in lower ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "La liquidez cambió: vuelve a cotizar.")
            "op_no_trust" in lower ->
                RaizResult.Error(RaizErrorCode.NOT_FOUND, "Falta la trustline al USDC del fondo.")
            else -> {
                val detail = (RESULT_CODES_REGEX.find(body)?.value ?: e.message ?: "horizon error").take(200)
                Log.e(TAG, "pathPaymentStrictSend falló: $detail")
                RaizResult.Error(RaizErrorCode.UNKNOWN, "Horizon rechazó la conversión: $detail")
            }
        }
    }

    /**
     * ¿Entró y tuvo éxito la transacción `hash`? `true`/`false` si Horizon la conoce; `null`
     * si no aparece tras unos intentos (o no hay red).
     */
    private suspend fun transactionSucceeded(hash: String): Boolean? {
        repeat(TX_LOOKUP_ATTEMPTS) { attempt ->
            val successful = runCatching { fetchJson("${RaizConstants.TESTNET_HORIZON_URL}/transactions/$hash") }
                .getOrNull()?.get("successful")?.jsonPrimitive?.contentOrNull
            if (successful != null) return successful == "true"
            if (attempt < TX_LOOKUP_ATTEMPTS - 1) delay(TX_LOOKUP_DELAY_MS)
        }
        return null
    }

    /**
     * Monto realmente recibido por un `path_payment_strict_send` ya aplicado (campo `amount`
     * de la operación en Horizon), en stroops; `null` si no se pudo leer. Un strict-send
     * entrega lo que dé el pool en ese ledger (≥ `dest_min`), que puede diferir de lo cotizado.
     */
    suspend fun strictSendReceivedStroops(txHash: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val json = fetchJson("${RaizConstants.TESTNET_HORIZON_URL}/transactions/$txHash/operations?limit=5")
            json?.get("_embedded")?.jsonObject?.get("records")?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "path_payment_strict_send" }
                ?.get("amount")?.jsonPrimitive?.contentOrNull
                ?.toUsdcStroops()
        }.getOrNull()
    }

    /** GET JSON a Horizon por HTTP directo (mismo patrón que [paymentHistory]). `null` si 404. */
    private fun fetchJson(urlStr: String): JsonObject? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode == 404) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return jsonCodec.parseToJsonElement(body).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Verifica si la cuenta tiene trustline al USDC de Blend (el que usa el pool).
     * Delega en [hasTrustline] — se conserva como método propio por ser, con
     * diferencia, la comprobación más frecuente en la app.
     */
    suspend fun hasUsdcTrustline(accountId: String): Boolean = hasTrustline(accountId, "USDC", usdcIssuer)

    /**
     * Verifica si la cuenta tiene trustline a un asset clásico (`code`/`issuer`)
     * cualquiera. Sin esto, la cuenta no puede recibir ese asset.
     */
    suspend fun hasTrustline(accountId: String, code: String, issuer: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val account = horizonServer.accounts().account(accountId)
                account.balances.any { b -> b.assetCode == code && b.assetIssuer == issuer }
            }.getOrElse { false }
        }

    /**
     * Activa el trustline USDC de Blend para la cuenta del `signer`. Delega en
     * [enableTrustline] con el USDC del pool — se conserva como método propio
     * porque es, con diferencia, el trustline que más se activa en la app.
     */
    suspend fun enableUsdcTrustline(signer: KeyPair): RaizResult<Unit> = enableTrustline(signer, "USDC", usdcIssuer)

    /**
     * Activa el trustline a un asset clásico (`code`/`issuer`) para la cuenta del
     * `signer`. Construye y firma una `ChangeTrustOperation` y la envía a Horizon.
     * Tras esto, la cuenta puede recibir ese asset (y mostrarse balance>0 en lugar
     * del default 0). Pensado tanto para el USDC de Blend como para el del anchor
     * de prueba (D3).
     *
     * Errores comunes:
     *  - InsufficientBalance: la cuenta debe tener al menos ~1 XLM de reserve.
     *    (Cuentas creadas con friendbot vienen con 10000 XLM, no es problema.)
     *  - El sequence number lo lee Horizon en loadAccount; refrescar si falla.
     *
     * Reintenta hasta 3 veces ante errores DNS transitorios (backoff 1s/2s).
     */
    suspend fun enableTrustline(signer: KeyPair, code: String, issuer: String): RaizResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                withRetryOnDns {
                    val accountId = signer.getAccountId()
                    val source = horizonServer.loadAccount(accountId)
                    val asset = AssetTypeCreditAlphaNum4(code, issuer)
                    val op = ChangeTrustOperation(asset, ChangeTrustOperation.MAX_LIMIT)
                    val tx = TransactionBuilder(source, Network.TESTNET)
                        .setBaseFee(100L)
                        .addOperation(op)
                        .setTimeout(60L)
                        .build()
                    tx.sign(signer)
                    // submitTransaction de Horizon espera el XDR envelope base64.
                    horizonServer.submitTransaction(tx.toEnvelopeXdrBase64())
                    Log.i(TAG, "Trustline $code activado para $accountId")
                }
            }.fold(
                onSuccess = { RaizResult.Success(Unit) },
                onFailure = { e ->
                    Log.e(TAG, "enableTrustline($code) falló: ${e.message}")
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

    // Los flujos admin (faucet, registro de comercio, soulbound, vault) viven
    // en el relayer (data/relayer/RelayerClient) desde 0.2.0: el APK ya no
    // lleva autoridad admin. Aquí vivía el faucet clásico (`payment` de USDC
    // firmado por el admin hacia una cuenta G…); hoy es `POST /v1/faucet`.

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
        /** Intentos de consulta por hash tras un envío sin respuesta (≈ 2 cierres de ledger). */
        const val TX_LOOKUP_ATTEMPTS = 4
        const val TX_LOOKUP_DELAY_MS = 2_500L
        /** Fragmento `"result_codes": {...}` del body de error de Horizon (sin el XDR). */
        val RESULT_CODES_REGEX = Regex("\"result_codes\"\\s*:\\s*\\{[^}]*\\}")
    }
}
