package com.raiz.app.data.stellar

import com.raiz.app.BuildConfig
import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.VaultPosition
import com.raiz.app.data.model.VaultStats
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.contract.ContractClient
import com.soneso.stellar.sdk.xdr.SCValXdr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cliente para el vault USDC de DeFindex en testnet.
 *
 * Sigue el mismo patrón que [SorobanClient]: usa `ContractClient.forContract`
 * (que carga el spec del WASM on-chain) e invoca cada función con el método
 * `invoke<T>(functionName, arguments, source, signer, parseResultXdrFn)`.
 *
 * Conversiones de argumentos que el SDK hace automáticamente (confirmado por
 * inspección de bytecode de ContractSpec.nativeToXdrSCVal):
 *  - `Long`        → i128  (vía handleI128Type → BigInteger.fromLong)
 *  - `List<Long>`  → Vec<i128> (vía handleVecType → handleI128Type por elemento)
 *  - `String` G.../C... → Address (vía handleAddressType → new Address(string))
 *  - `Boolean`     → bool
 * No es necesario construir SCVal explícitos para estos tipos al usar `invoke()`.
 *
 * Dirección del vault testnet:
 *   `CBMVK2JK6NTOT2O4HNQAIQFJY232BHKGLIMXDVQVHIIZKDACXDFZDWHN`
 */
@Singleton
class DefindexClient @Inject constructor(
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
     * Cliente del vault. Cacheado porque `forContract` dispara dos round-trips
     * a RPC (getLatestLedger + loadContractSpec) y es costoso hacerlo en cada call.
     */
    private var cachedVaultClient: ContractClient? = null

    /** Codec JSON reutilizable para la respuesta REST del APY. */
    private val jsonCodec = Json { ignoreUnknownKeys = true }

    private suspend fun vaultClient(): ContractClient {
        val vaultId = deployments.defindexVault
            ?: error("vault DeFindex no configurado en deployments")
        return cachedVaultClient ?: ContractClient.forContract(
            contractId = vaultId,
            rpcUrl = rpcUrl,
            network = network,
        ).also { cachedVaultClient = it }
    }

    // ── Lecturas (signer = null, source = admin) ─────────────────────────

    /**
     * Devuelve el estado global del vault: TVL, oferta total de shares y
     * precio por share. Realiza 3 llamadas RPC secuenciales.
     */
    suspend fun getVaultStats(): RaizResult<VaultStats> {
        if (deployments.defindexVault == null) {
            return RaizResult.Error(RaizErrorCode.NOT_FOUND, "vault DeFindex no configurado")
        }
        return runCatching {
            val client = vaultClient()

            // 1. Oferta total de shares (i128)
            val totalSupply = client.invoke<Long>(
                functionName = "total_supply",
                arguments = emptyMap(),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { ScvalParse.asLong(it) },
            )

            // 2. TVL = suma de total_amount de cada CurrentAssetInvestmentAllocation
            val tvl = client.invoke<Long>(
                functionName = "fetch_total_managed_funds",
                arguments = emptyMap(),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { scval ->
                    ScvalParse.asVec(scval).sumOf { item ->
                        val f = ScvalParse.asStruct(item)
                        ScvalParse.asLong(f.req("total_amount"))
                    }
                },
            )

            // 3. Precio por share = TVL / total_supply. Calculado off-chain a
            //    propósito: get_asset_amounts_per_shares lo resuelve el host como
            //    WRITE (footprint de restore) y exigiría firma; total_supply y
            //    fetch_total_managed_funds sí son read-only. 1 share = 10^7 (7 dec).
            val pricePerShare = if (totalSupply > 0L) {
                (tvl.toBigInteger() *
                    RaizConstants.USDC_STROOPS_PER_UNIT.toBigInteger() /
                    totalSupply.toBigInteger()).toLong()
            } else {
                RaizConstants.USDC_STROOPS_PER_UNIT
            }

            VaultStats(
                tvlStroops = tvl,
                totalSupplyShares = totalSupply,
                pricePerShareStroops = pricePerShare,
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(mapError(e.message), "defindex.getVaultStats: ${e.message}")
            },
        )
    }

    /**
     * Devuelve la posición del `holder` en el vault: shares que posee y su
     * valor actual en USDC (stroops). Si shares == 0, no realiza la segunda
     * llamada RPC.
     */
    suspend fun getPosition(holder: String): RaizResult<VaultPosition> {
        if (deployments.defindexVault == null) {
            return RaizResult.Error(RaizErrorCode.NOT_FOUND, "vault DeFindex no configurado")
        }
        return runCatching {
            val client = vaultClient()

            // balance(id: Address) -> i128
            val shares = client.invoke<Long>(
                functionName = "balance",
                arguments = mapOf("id" to holder),
                source = deployments.admin,
                signer = null,
                parseResultXdrFn = { ScvalParse.asLong(it) },
            )

            // Valor actual = shares * TVL / total_supply. Todo read-only (evita
            // get_asset_amounts_per_shares, que el host trataría como write).
            val currentValue = if (shares > 0L) {
                val totalSupply = client.invoke<Long>(
                    functionName = "total_supply",
                    arguments = emptyMap(),
                    source = deployments.admin,
                    signer = null,
                    parseResultXdrFn = { ScvalParse.asLong(it) },
                )
                val tvl = client.invoke<Long>(
                    functionName = "fetch_total_managed_funds",
                    arguments = emptyMap(),
                    source = deployments.admin,
                    signer = null,
                    parseResultXdrFn = { scval ->
                        ScvalParse.asVec(scval).sumOf { item ->
                            ScvalParse.asLong(ScvalParse.asStruct(item).req("total_amount"))
                        }
                    },
                )
                if (totalSupply > 0L) {
                    (shares.toBigInteger() * tvl.toBigInteger() /
                        totalSupply.toBigInteger()).toLong()
                } else {
                    shares
                }
            } else {
                0L
            }

            VaultPosition(shares = shares, currentValueStroops = currentValue)
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(mapError(e.message), "defindex.getPosition: ${e.message}")
            },
        )
    }

    // ── Escrituras (firmadas con signer) ─────────────────────────────────

    /**
     * Deposita `amountStroops` USDC en el vault firmando con `signer`.
     *
     * El vault hace `from.require_auth()` e internamente hace `transfer` del
     * USDC del firmante al vault — no requiere approve/allowance previo cuando
     * el firmante ES `from` y firma la invocación (igual que `pay_merchant`).
     *
     * Devuelve las **shares minteadas** (segundo elemento de la tupla de retorno).
     * La tupla completa es `(Vec<i128>, i128, Option<...>)`.
     */
    suspend fun deposit(signer: KeyPair, amountStroops: Long): RaizResult<Long> {
        if (deployments.defindexVault == null) {
            return RaizResult.Error(RaizErrorCode.NOT_FOUND, "vault DeFindex no configurado")
        }
        return runCatching {
            vaultClient().invoke<Long>(
                functionName = "deposit",
                arguments = mapOf(
                    // Vec<i128>: List<Long> se convierte automáticamente por el SDK
                    "amounts_desired" to listOf(amountStroops),
                    "amounts_min" to listOf(0L),
                    "from" to signer.getAccountId(),
                    "invest" to true,
                ),
                source = signer.getAccountId(),
                signer = signer,
                parseResultXdrFn = { scval ->
                    // retorna (Vec<i128>, i128, Option<...>) codificado como SCV_VEC
                    // el elemento [1] es las shares minteadas (i128)
                    ScvalParse.asLong(ScvalParse.asVec(scval)[1])
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(mapError(e.message), "defindex.deposit: ${e.message}")
            },
        )
    }

    /**
     * Retira `shares` del vault firmando con `signer`.
     *
     * Devuelve la **suma de los montos retirados** en stroops USDC.
     * El vault USDC tiene 1 solo asset, así que el Vec<i128> de retorno
     * tiene un único elemento; sumamos por robustez ante futuros multi-asset.
     */
    suspend fun withdraw(signer: KeyPair, shares: Long): RaizResult<Long> {
        if (deployments.defindexVault == null) {
            return RaizResult.Error(RaizErrorCode.NOT_FOUND, "vault DeFindex no configurado")
        }
        return runCatching {
            vaultClient().invoke<Long>(
                functionName = "withdraw",
                arguments = mapOf(
                    "withdraw_shares" to shares,
                    "min_amounts_out" to listOf(0L),
                    "from" to signer.getAccountId(),
                ),
                source = signer.getAccountId(),
                signer = signer,
                parseResultXdrFn = { scval ->
                    // retorna Vec<i128> — suma de montos retirados por asset
                    ScvalParse.asVec(scval).sumOf { ScvalParse.asLong(it) }
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(mapError(e.message), "defindex.withdraw: ${e.message}")
            },
        )
    }

    // ── APY en vivo (REST, best-effort) ──────────────────────────────────

    /**
     * APY del vault vía la REST de DeFindex. **Best-effort y opcional**:
     * requiere `defindex.api.key` en local.properties (BuildConfig.DEFINDEX_API_KEY).
     *
     * Devuelve el APY en basis points (1500 = 15%) o `null` si no hay key,
     * el endpoint falla, o el formato no es el esperado. La pantalla Yield
     * funciona igual sin esto: muestra precio por share, TVL y rendimiento,
     * todo leído on-chain.
     */
    suspend fun getApyBps(): Int? = withContext(Dispatchers.IO) {
        val vaultId = deployments.defindexVault ?: return@withContext null
        val key = BuildConfig.DEFINDEX_API_KEY
        if (key.isBlank()) return@withContext null
        runCatching {
            val net = if (deployments.network == "public") "mainnet" else "testnet"
            val url = "https://api.defindex.io/vault/$vaultId/apy?network=$net"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return@runCatching null
            val obj = jsonCodec.parseToJsonElement(body).jsonObject
            val raw = (obj["apy"] ?: obj["strategyApy"])?.jsonPrimitive?.doubleOrNull
                ?: return@runCatching null
            // La API devuelve fracción (0.15 = 15%). Si llegara como porcentaje
            // (>1.5), lo interpretamos como tal. Convertimos a basis points.
            if (kotlin.math.abs(raw) <= 1.5) (raw * 10_000).toInt() else (raw * 100).toInt()
        }.getOrNull()
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    /** Mapea el mensaje de error del contrato a un [RaizErrorCode] semántico. */
    private fun mapError(msg: String?): RaizErrorCode = when {
        msg == null -> RaizErrorCode.NETWORK_ERROR
        // DeFindex usa error code #111 para balance insuficiente
        "InsufficientBalance" in msg ||
            "#111" in msg ||
            "balance" in msg.lowercase() -> RaizErrorCode.INSUFFICIENT_BALANCE
        // Error #130 = Unauthorized (signer != from, o from no firmó)
        "#130" in msg ||
            "Unauthorized" in msg -> RaizErrorCode.UNAUTHORIZED
        else -> RaizErrorCode.NETWORK_ERROR
    }

    /** Acceso a campo de struct con mensaje de error claro si falta. */
    private fun <V> Map<String, V>.req(key: String): V =
        this[key] ?: error("Campo '$key' no presente en el struct SCVal")
}
