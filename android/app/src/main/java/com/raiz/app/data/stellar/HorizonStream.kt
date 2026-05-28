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
import com.soneso.stellar.sdk.TransactionBuilder
import com.soneso.stellar.sdk.horizon.HorizonServer
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
 * Stream del balance USDC de una cuenta Stellar.
 *
 * El SDK Soneso NO expone SSE (Horizon `stream`) en `PaymentsRequestBuilder`,
 * así que usamos polling cada `intervalMs` con `accounts().account(id)`.
 * 5s es suficiente para el demo; cuando integremos pagos reales podemos bajar
 * a 2s o cambiar a SSE manual con OkHttp si la libertad de Horizon lo soporta.
 *
 * Devuelve `0L` ante cualquier error de red (no rompe el flow — el VM puede
 * mostrar "sin red" si necesita).
 */
@Singleton
class HorizonStream @Inject constructor(
    private val deploymentsLoader: DeploymentsLoader,
) {
    private val deployments: Deployments by lazy { deploymentsLoader.load() }

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

    private suspend fun fetchUsdcBalance(accountId: String): Long {
        return runCatching {
            val account = horizonServer.accounts().account(accountId)
            val usdc = account.balances.firstOrNull { b ->
                b.assetCode == "USDC" && b.assetIssuer == deployments.admin
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
                Log.w(TAG, "paymentHistory falló: ${e.message}")
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "horizon error")
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
                b.assetCode == "USDC" && b.assetIssuer == deployments.admin
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
     */
    suspend fun enableUsdcTrustline(signer: KeyPair): RaizResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val accountId = signer.getAccountId()
            val source = horizonServer.loadAccount(accountId)
            val asset = AssetTypeCreditAlphaNum4("USDC", deployments.admin)
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
        }.fold(
            onSuccess = { RaizResult.Success(Unit) },
            onFailure = { e ->
                Log.e(TAG, "enableUsdcTrustline falló: ${e.message}")
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "horizon error")
            },
        )
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
