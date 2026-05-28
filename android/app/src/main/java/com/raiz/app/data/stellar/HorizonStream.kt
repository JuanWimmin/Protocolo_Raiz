package com.raiz.app.data.stellar

import android.util.Log
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.soneso.stellar.sdk.horizon.HorizonServer
import com.soneso.stellar.sdk.horizon.requests.RequestBuilder
import com.soneso.stellar.sdk.horizon.responses.operations.PaymentOperationResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
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
     * Historial de pagos de una cuenta. Lista los últimos `limit` pagos
     * (tipo `payment` clásico de Stellar). Soroban host fns como
     * `pay_merchant` ejecutan `usdc.transfer` internamente, lo cual genera
     * operaciones tipo `payment` que aparecen aquí.
     *
     * Filtra solo `PaymentOperationResponse` — ignora path_payments, manage_data,
     * etc. para mantener la lista limpia.
     */
    suspend fun paymentHistory(
        accountId: String,
        limit: Int = 20,
    ): RaizResult<List<PaymentRecord>> {
        return runCatching {
            val page = horizonServer.payments()
                .forAccount(accountId)
                .order(RequestBuilder.Order.DESC)
                .limit(limit)
                .execute()
            page.records.filterIsInstance<PaymentOperationResponse>().map { op ->
                PaymentRecord(
                    txHash = op.transactionHash,
                    from = op.from,
                    to = op.to,
                    amountStroops = op.amount.toUsdcStroops(),
                    assetCode = if (op.assetType == "native") "XLM" else (op.assetCode ?: "?"),
                    createdAt = op.createdAt,
                    isOutgoing = op.from == accountId,
                )
            }
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                Log.w(TAG, "paymentHistory falló: ${e.message}")
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
