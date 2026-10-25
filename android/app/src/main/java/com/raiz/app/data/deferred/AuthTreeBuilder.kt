package com.raiz.app.data.deferred

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.raiz.app.data.model.Deployments
import com.soneso.stellar.sdk.Address
import com.soneso.stellar.sdk.scval.Scv
import com.soneso.stellar.sdk.xdr.InvokeContractArgsXdr
import com.soneso.stellar.sdk.xdr.SCSymbolXdr
import com.soneso.stellar.sdk.xdr.SCValXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizedFunctionXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizedInvocationXdr

/**
 * Direcciones que fija el árbol de autorización de `pay_merchant` (salen de `deployments.json`).
 * Si el Pool cambiara `ProtocolFeeBps` o el admin, la firma dejaría de coincidir y la red rechazaría
 * el sobre con `Error(Auth, InvalidAction)`: fallo seguro, nunca un cobro distinto del firmado.
 */
data class DeferredIds(val pool: String, val usdcSac: String, val admin: String) {
    companion object {
        fun from(deployments: Deployments) = DeferredIds(
            pool = deployments.pool,
            usdcSac = deployments.usdcSac,
            admin = deployments.admin,
        )
    }
}

/** Reparto de `amount` tal como lo hace el contrato: `tip` al pool del barrio, `fee` al admin, el resto al comercio. */
data class PayMerchantSplit(val tip: Long, val fee: Long, val toMerchant: Long)

/**
 * Árbol de autorización de `Pool.pay_merchant(tourist, merchant, amount, tip_bps)` para el pagador
 * (spec `docs/pago_diferido_v0.md` §2.1, espejo de `contracts/pool/src/lib.rs`):
 *
 * ```
 * root:  Pool.pay_merchant(tourist, merchant, amount, tip_bps)     [require_auth(tourist)]
 *   ├─ USDC.transfer(tourist, merchant, amount - fee)
 *   ├─ USDC.transfer(tourist, Pool,     tip)     solo si tip > 0
 *   └─ USDC.transfer(tourist, admin,    fee)     solo si fee > 0
 * ```
 *
 * `Rewards.accrue_points` lo autoriza el propio Pool y no entra en el árbol del turista. Es puro y no
 * toca la red: se construye en modo avión. Sus bytes se comparan en `DeferredAuthSpikeTest` contra el
 * fixture generado con `@stellar/stellar-sdk` (`scripts/gen-auth-fixture.mjs`), que a su vez se
 * contrasta con la simulación real del host (`--verify-tree`).
 */
object AuthTreeBuilder {
    const val BPS_DENOMINATOR = 10_000L
    const val MAX_BPS = 10_000
    const val FN_PAY_MERCHANT = "pay_merchant"
    const val FN_TRANSFER = "transfer"

    /** Misma aritmética entera que el contrato (`i128`, trunca hacia cero). */
    fun split(amount: Long, tipBps: Int, feeBps: Int): PayMerchantSplit {
        require(amount > 0) { "amount debe ser > 0" }
        require(tipBps in 0..MAX_BPS) { "tip_bps fuera de rango: $tipBps" }
        require(feeBps in 0..MAX_BPS) { "fee_bps fuera de rango: $feeBps" }
        val tip = Math.multiplyExact(amount, tipBps.toLong()) / BPS_DENOMINATOR
        val fee = Math.multiplyExact(amount, feeBps.toLong()) / BPS_DENOMINATOR
        return PayMerchantSplit(tip = tip, fee = fee, toMerchant = amount - fee)
    }

    /**
     * Invocación raíz con sus sub-invocaciones. Las ramas se deciden por el valor calculado
     * (`tip > 0`, `fee > 0`), no por los bps: con montos pequeños el contrato trunca a 0 y omite el transfer.
     */
    fun payMerchantInvocation(
        payer: String,
        merchant: String,
        amount: Long,
        tipBps: Int,
        feeBps: Int,
        ids: DeferredIds,
    ): SorobanAuthorizedInvocationXdr {
        val s = split(amount, tipBps, feeBps)
        val subInvocations = buildList {
            add(transfer(ids.usdcSac, from = payer, to = merchant, amount = s.toMerchant))
            if (s.tip > 0) add(transfer(ids.usdcSac, from = payer, to = ids.pool, amount = s.tip))
            if (s.fee > 0) add(transfer(ids.usdcSac, from = payer, to = ids.admin, amount = s.fee))
        }
        return contractFn(
            contractId = ids.pool,
            function = FN_PAY_MERCHANT,
            args = listOf(
                Address(payer).toSCVal(),
                Address(merchant).toSCVal(),
                i128(amount),
                Scv.toUint32(tipBps.toUInt()),
            ),
            subInvocations = subInvocations,
        )
    }

    /** Sub-invocación `transfer(from, to, amount)` del SAC: el token hace `from.require_auth()`. */
    fun transfer(usdcSac: String, from: String, to: String, amount: Long): SorobanAuthorizedInvocationXdr =
        contractFn(
            contractId = usdcSac,
            function = FN_TRANSFER,
            args = listOf(Address(from).toSCVal(), Address(to).toSCVal(), i128(amount)),
        )

    private fun i128(value: Long): SCValXdr = Scv.toInt128(BigInteger.fromLong(value))

    private fun contractFn(
        contractId: String,
        function: String,
        args: List<SCValXdr>,
        subInvocations: List<SorobanAuthorizedInvocationXdr> = emptyList(),
    ): SorobanAuthorizedInvocationXdr =
        SorobanAuthorizedInvocationXdr(
            function = SorobanAuthorizedFunctionXdr.ContractFn(
                InvokeContractArgsXdr(
                    contractAddress = Address(contractId).toSCAddress(),
                    functionName = SCSymbolXdr(function),
                    args = args,
                ),
            ),
            subInvocations = subInvocations,
        )
}
