package com.raiz.app.data.stellar

import com.raiz.app.data.model.Deployments
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
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
 *   - Lecturas (read-only) devuelven RaizResult<T> con simulate, sin signer.
 *   - Escrituras requieren un Keypair del WalletManager y firman + envían.
 *   - Montos USDC siempre en Long stroops.
 *   - barrioId siempre como String hex de 64 chars (BytesN<32>).
 */
@Singleton
class SorobanClient @Inject constructor(
    private val deploymentsLoader: DeploymentsLoader,
) {

    private val deployments: Deployments by lazy { deploymentsLoader.load() }

    /**
     * Lee el saldo del pool de un barrio. Read-only — simula contra Soroban
     * RPC sin firmar nada.
     *
     * Implementación pendiente: depende de exponer el SDK de Soneso vía Hilt
     * (o instanciar ContractClient aquí). Para el primer smoke test devolvemos
     * un Error placeholder; la implementación real va en el siguiente paso
     * cuando confirmemos las firmas del SDK con el primer build.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getPoolBalance(barrioId: String): RaizResult<Long> {
        // TODO: implementar con ContractClient.forContract(deployments.pool, ...)
        //       y Scv.fromInt128(...).toLong() para parsear el resultado.
        return RaizResult.Error(
            code = RaizErrorCode.UNKNOWN,
            message = "SorobanClient.getPoolBalance: pendiente de cablear contra el SDK",
        )
    }

    /** Diagnóstico: retorna los IDs de contratos cargados desde deployments.json. */
    fun debugDeployments(): Deployments = deployments
}
