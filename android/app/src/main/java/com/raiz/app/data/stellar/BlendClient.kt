package com.raiz.app.data.stellar

import com.raiz.app.data.model.BlendReserveStats
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.contract.ContractClient
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cliente de lecturas puras contra Blend v2 en testnet (F1: sustituye a
 * `DefindexClient` — no hay depósitos/retiros directos aquí, ese "Camino B"
 * murió; el único camino de escritura es vía `Pool.deposit_idle_to_vault` /
 * `Pool.redeem_from_vault`, ver [SorobanClient]).
 *
 * Dos fuentes de datos, ambas read-only (`signer = null`, `simulateTransaction`
 * vía `ContractClient.invoke`):
 *
 *  1. **El pool de Blend directamente** (`get_reserve(usdc)`) → [getReserveData].
 *     TVL y utilización de TODO el pool Blend (no solo la posición de RAÍZ) —
 *     contexto de riesgo/liquidez. Blend ya vive en testnet de forma
 *     independiente al deploy de RAÍZ, así que esto funciona incluso ANTES
 *     del re-deploy F1 (no depende de `deployments.yieldAdapter`).
 *  2. **El contrato `yield_adapter` propio de RAÍZ** (`apy_hint()`) →
 *     [getAdapterApyBps]. Este SÍ depende del re-deploy F1: si
 *     `deployments.yieldAdapter` es null (deploy pre-F1 todavía activo),
 *     devuelve `null` con gracia — mismo contrato "best-effort, nullable"
 *     que tenía el APY vía REST de DeFindex, sin API key ni llamada de red
 *     externa esta vez (todo on-chain).
 *
 * La posición POR BARRIO (shares, valor) sigue viniendo de `Pool` vía
 * [SorobanClient.getVaultShares] / [SorobanClient.getVaultValue] — el Pool
 * delega internamente en el yield_adapter, así que NO se duplica esa lectura
 * aquí.
 *
 * Fuente del APY mostrado en la pantalla Yield: `yield_adapter.apy_hint()`
 * (el adapter lo deriva de la curva de interés/utilización de Blend), NO por
 * muestreo de `b_rate` entre dos lecturas con timestamp — se documenta en la
 * UI como estimado y variable (el badge cae a "Rinde on-chain" si es null).
 */
@Singleton
class BlendClient @Inject constructor(
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

    /** Address del pool USDC de Blend v2. Prioriza deployments.json; fallback = TestnetV2 fijo. */
    private val blendPoolId: String by lazy {
        deployments.blendPool ?: RaizConstants.BLEND_POOL_TESTNET
    }

    private var cachedBlendPoolClient: ContractClient? = null
    private var cachedAdapterClient: ContractClient? = null

    /** Cliente del pool Blend. Cacheado (misma razón que en [SorobanClient]/DefindexClient: `forContract` cuesta 2 round-trips). */
    private suspend fun blendPoolClient(): ContractClient =
        cachedBlendPoolClient ?: ContractClient.forContract(
            contractId = blendPoolId,
            rpcUrl = rpcUrl,
            network = network,
        ).also { cachedBlendPoolClient = it }

    /** Cliente del yield_adapter propio de RAÍZ. Null si aún no está en deployments.json (pre-F1). */
    private suspend fun adapterClient(): ContractClient? {
        val adapterId = deployments.yieldAdapter ?: return null
        return cachedAdapterClient ?: ContractClient.forContract(
            contractId = adapterId,
            rpcUrl = rpcUrl,
            network = network,
        ).also { cachedAdapterClient = it }
    }

    // ── Blend pool: get_reserve(usdc) — TVL/utilización del pool completo ──

    /**
     * TVL y utilización del pool USDC de Blend (`get_reserve(usdc)`, dato
     * acumulado al ledger actual — incluye accrual). No depende de RAÍZ: lee
     * directamente el contrato de Blend en testnet.
     *
     * `Reserve { asset, config, data, scalar }` — solo se necesita el struct
     * anidado `data` (`ReserveData`): `b_rate`/`d_rate` (i128, escala 1e12
     * en Blend v2) y `b_supply`/`d_supply` (i128, unidades de bToken/dToken).
     *
     * `tvlStroops = b_supply × b_rate / 1e12` (BigInteger: el producto de dos
     * i128 de magnitud realista desborda `Long`). `utilizationBps` = pasivos
     * (`d_supply × d_rate / 1e12`) sobre activos, en basis points.
     */
    suspend fun getReserveData(): RaizResult<BlendReserveStats> {
        return runCatching {
            val client = blendPoolClient()
            client.invoke<BlendReserveStats>(
                functionName = "get_reserve",
                arguments = mapOf("asset" to deployments.usdcSac),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    val reserve = ScvalParse.asStruct(scval)
                    val data = ScvalParse.asStruct(reserve.req("data"))

                    val bRate = ScvalParse.asLong(data.req("b_rate"))
                    val dRate = ScvalParse.asLong(data.req("d_rate"))
                    val bSupply = ScvalParse.asLong(data.req("b_supply"))
                    val dSupply = ScvalParse.asLong(data.req("d_supply"))

                    val totalAssets = (bSupply.toBigInteger() * bRate.toBigInteger()) / SCALAR_12
                    val totalLiabilities = (dSupply.toBigInteger() * dRate.toBigInteger()) / SCALAR_12

                    val utilizationBps = if (totalAssets > BigInteger.ZERO) {
                        (totalLiabilities * BPS.toBigInteger() / totalAssets).toInt()
                    } else {
                        0
                    }

                    BlendReserveStats(
                        bRate = bRate,
                        dRate = dRate,
                        bSupplyStroops = bSupply,
                        dSupplyStroops = dSupply,
                        tvlStroops = totalAssets.toLong(),
                        utilizationBps = utilizationBps,
                    )
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "blend.getReserveData: ${e.message}")
            },
        )
    }

    // ── yield_adapter: apy_hint() — APY estimado, best-effort ──────────────

    /**
     * APY estimado en basis points, leído de `yield_adapter.apy_hint()`
     * (u32). Fuente: la curva de interés/utilización de Blend calculada
     * DENTRO del adapter — no es un muestreo de `b_rate` entre dos lecturas.
     *
     * `null` si `deployments.yieldAdapter` todavía no existe (deploy pre-F1
     * activo) o si la lectura falla — la pantalla Yield funciona igual sin
     * esto (badge cae a "Rinde on-chain").
     */
    suspend fun getAdapterApyBps(): Int? {
        val client = adapterClient() ?: return null
        return runCatching {
            client.invoke<Int>(
                functionName = "apy_hint",
                arguments = emptyMap(),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { ScvalParse.asUIntAsInt(it) },
            )
        }.getOrNull()
    }

    /** Acceso a campo de struct con mensaje de error claro si falta. */
    private fun <V> Map<String, V>.req(key: String): V =
        this[key] ?: error("Campo '$key' no presente en el struct SCVal de Blend")

    private companion object {
        val SCALAR_12: BigInteger = BigInteger.valueOf(1_000_000_000_000L)
        val BPS: Long = RaizConstants.BPS_DENOMINATOR.toLong()
    }
}
