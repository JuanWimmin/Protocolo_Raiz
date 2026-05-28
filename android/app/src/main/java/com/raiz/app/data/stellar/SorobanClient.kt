package com.raiz.app.data.stellar

import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.contract.ContractClient
import com.soneso.stellar.sdk.scval.Scv
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fachada para invocar los 4 contratos Soroban de RAÍZ.
 *
 * Por ahora expone solo `getPoolBalance` como smoke test del setup completo
 * (Deployments → SDK → testnet → parseo SCVal). Las demás llamadas se irán
 * añadiendo a medida que cada pantalla las necesite:
 *
 *   - Pool: listMerchants, getBarrio, payMerchant
 *   - Governance: listActiveProposals, vote, createProposal, mintResident
 *   - Treasury: getExecutionLog, executeProposal
 *   - Rewards: listRewards, getPoints, redeem, claimRedemption
 *
 * Convenciones:
 *   - Lecturas (read-only) usan `signer = null` — `ContractClient.invoke`
 *     simula vía RPC sin enviar tx.
 *   - Montos USDC siempre en Long stroops.
 *   - `barrioId` siempre como String hex de 64 chars (BytesN<32>).
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
     * Lee el saldo del pool de un barrio. Read-only: simula contra Soroban RPC
     * sin firmar nada.
     *
     * @param barrioId hex de 64 chars (BytesN<32>).
     */
    suspend fun getPoolBalance(barrioId: String): RaizResult<Long> {
        val bytes = barrioId.hexToBytes()
            ?: return RaizResult.Error(
                code = RaizErrorCode.PARSE_ERROR,
                message = "barrio_id no es hex de 32 bytes: $barrioId",
            )

        return runCatching {
            val client = ContractClient.forContract(
                contractId = deployments.pool,
                rpcUrl = rpcUrl,
                network = network,
            )
            client.invoke<Long>(
                functionName = "get_pool_balance",
                arguments = mapOf("barrio_id" to bytes),
                source = deployments.admin,            // cualquier G... válido sirve para simulate
                signer = null,                         // read-only
                parseResultXdrFn = { scval ->
                    // Scv.fromInt128 retorna BigInteger (ionspin); a Long para stroops.
                    Scv.fromInt128(scval).longValue(exactRequired = false)
                },
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = { e ->
                RaizResult.Error(
                    code = RaizErrorCode.NETWORK_ERROR,
                    message = "getPoolBalance falló: ${e.message}",
                )
            },
        )
    }

    /** Diagnóstico: retorna los IDs de contratos cargados desde deployments.json. */
    fun debugDeployments(): Deployments = deployments

    // ── Helpers privados ──────────────────────────────────────────────────

    /** Convierte hex (64 chars) a ByteArray (32 bytes). null si no es válido. */
    private fun String.hexToBytes(): ByteArray? {
        val clean = removePrefix("0x")
        if (clean.length != 64 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return null
        }
        return ByteArray(32) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
