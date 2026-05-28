package com.raiz.app.data.stellar

import com.raiz.app.data.model.Barrio
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.MerchantCategory
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.contract.ContractClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fachada para invocar los 4 contratos Soroban de RAÍZ.
 *
 * Lecturas (read-only) usan `signer = null` y `source = deployments.admin`
 * para que el SDK simule la transacción sin firmar — basta con que la
 * source account exista en testnet (la del admin sí).
 *
 * Los structs (`Barrio`, `Merchant`) se reciben como SCVal Map y se parsean
 * manualmente con `ScvalParse` para mantener type-safety.
 */
@Singleton
class SorobanClient @Inject constructor(
    private val deploymentsLoader: DeploymentsLoader,
) {

    private val deployments: Deployments by lazy { deploymentsLoader.load() }

    private val network: Network by lazy {
        when (deployments.network) {
            "testnet" -> Network.TESTNET
            "public" -> Network.PUBLIC
            else -> Network.TESTNET
        }
    }

    private val rpcUrl: String by lazy {
        when (deployments.network) {
            "testnet" -> RaizConstants.TESTNET_SOROBAN_RPC_URL
            else -> RaizConstants.TESTNET_SOROBAN_RPC_URL
        }
    }

    /**
     * Cliente del Pool. Se cachea entre llamadas porque construirlo dispara
     * `getLatestLedger` + `loadContractSpec` (dos round-trips a RPC) y
     * eso es lento.
     */
    private suspend fun poolClient(): ContractClient =
        cachedPoolClient ?: ContractClient.forContract(
            contractId = deployments.pool,
            rpcUrl = rpcUrl,
            network = network,
        ).also { cachedPoolClient = it }

    private var cachedPoolClient: ContractClient? = null

    // ── Pool: get_pool_balance ────────────────────────────────────────────

    suspend fun getPoolBalance(barrioId: String): RaizResult<Long> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(
                code = RaizErrorCode.PARSE_ERROR,
                message = "barrio_id no es hex de 32 bytes: $barrioId",
            )

        return runCatching {
            val client = poolClient()
            client.invoke<Long>(
                functionName = "get_pool_balance",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { ScvalParse.asLong(it) },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "getPoolBalance: ${e.message}")
            },
        )
    }

    // ── Pool: get_barrio ──────────────────────────────────────────────────

    suspend fun getBarrio(barrioId: String): RaizResult<Barrio> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")

        return runCatching {
            poolClient().invoke<Barrio>(
                functionName = "get_barrio",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    val fields = ScvalParse.asStruct(scval)
                    Barrio(
                        id = ScvalParse.asHex(fields.req("id")),
                        name = ScvalParse.asString(fields.req("name")),
                        poolBalanceStroops = ScvalParse.asLong(fields.req("pool_balance")),
                        totalCollectedStroops = ScvalParse.asLong(fields.req("total_collected")),
                        txCount = ScvalParse.asULongAsLong(fields.req("tx_count")),
                        uniqueTourists = ScvalParse.asUIntAsInt(fields.req("unique_tourists")),
                        treasuryContract = ScvalParse.asAddressString(fields.req("treasury_contract")),
                    )
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                if (e.message?.contains("BarrioNotFound") == true ||
                    e.message?.contains("Error(Contract, #6)") == true
                ) {
                    RaizResult.Error(RaizErrorCode.NOT_FOUND, "barrio no registrado")
                } else {
                    RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "getBarrio: ${e.message}")
                }
            },
        )
    }

    // ── Pool: list_merchants ──────────────────────────────────────────────

    suspend fun listMerchants(barrioId: String): RaizResult<List<Merchant>> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id inválido")

        return runCatching {
            poolClient().invoke<List<Merchant>>(
                functionName = "list_merchants",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    ScvalParse.asVec(scval).map { item ->
                        val f = ScvalParse.asStruct(item)
                        Merchant(
                            address = ScvalParse.asAddressString(f.req("address")),
                            name = ScvalParse.asString(f.req("name")),
                            barrioId = ScvalParse.asHex(f.req("barrio_id")),
                            verified = ScvalParse.asBoolean(f.req("verified")),
                            latE6 = ScvalParse.asInt32(f.req("lat_e6")),
                            lngE6 = ScvalParse.asInt32(f.req("lng_e6")),
                            category = MerchantCategory.fromSymbol(
                                ScvalParse.asSymbol(f.req("category"))
                            ),
                        )
                    }
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "listMerchants: ${e.message}")
            },
        )
    }

    // ── Pool: pay_merchant (ESCRITURA firmada) ───────────────────────────

    /**
     * Paga del turista al comercio con Tip Barrio opcional.
     *
     * Dispara la cadena completa del contrato Pool:
     *   - transfiere `amount - fee` del turista al comercio
     *   - transfiere `tip` del turista al pool del barrio
     *   - llama Rewards.accrue_points cross-contract (puntos para el turista)
     *   - emite evento `payment(tourist, merchant, amount, tip, barrio_id)`
     *
     * Devuelve Unit en éxito porque el contrato no retorna datos. La
     * confirmación visual de éxito viene de:
     *   - el balance USDC del turista bajando (HorizonStream)
     *   - el pool_balance del barrio subiendo (próximo getBarrio)
     */
    suspend fun payMerchant(
        tourist: KeyPair,
        merchantAddress: String,
        amountStroops: Long,
        tipBps: Int,
    ): RaizResult<Unit> {
        return runCatching {
            poolClient().invoke<Unit>(
                functionName = "pay_merchant",
                arguments = mapOf(
                    "tourist" to tourist.getAccountId(),
                    "merchant" to merchantAddress,
                    "amount" to amountStroops,
                    "tip_bps" to tipBps.toUInt(),
                ),
                source = tourist.getAccountId(),
                signer = tourist,
                parseResultXdrFn = { /* void */ },
            )
        }.fold(
            onSuccess = { RaizResult.Success(Unit) },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                val code = when {
                    "InsufficientBalance" in msg ||
                        "Error(Contract, #7)" in msg -> RaizErrorCode.INSUFFICIENT_BALANCE
                    "trustline" in msg.lowercase() ||
                        "Error(Contract, #13)" in msg -> RaizErrorCode.INSUFFICIENT_BALANCE
                    "MerchantNotFound" in msg ||
                        "Error(Contract, #4)" in msg -> RaizErrorCode.NOT_FOUND
                    else -> RaizErrorCode.NETWORK_ERROR
                }
                RaizResult.Error(code, "payMerchant: ${e.message}")
            },
        )
    }

    // ── Diagnóstico ──────────────────────────────────────────────────────

    fun debugDeployments(): Deployments = deployments

    // ── Helpers privados ──────────────────────────────────────────────────

    /** Convierte hex (64 chars) a ByteArray (32 bytes). null si inválido. */
    private fun String.hexToBytes(): ByteArray? {
        val clean = removePrefix("0x")
        if (clean.length != 64 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return null
        }
        return ByteArray(32) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Acceso a campo de struct con error claro si falta. */
    private fun <V> Map<String, V>.req(key: String): V =
        this[key] ?: error("Campo '$key' no presente en el struct SCVal")
}
