package com.raiz.app.data.deferred

import com.raiz.app.data.stellar.ScvalParse
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.xdr.SCValXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizationEntryXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizedFunctionXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizedInvocationXdr
import com.soneso.stellar.sdk.xdr.XdrWriter

/** Los argumentos firmados de la raíz `Pool.pay_merchant(tourist, merchant, amount, tip_bps)`. */
data class PayArgs(
    val payer: String,
    val merchant: String,
    val amount: Long,
    val tipBps: Int,
) {
    companion object {
        /**
         * Extrae los argumentos de la invocación raíz, o `null` si no es `pay_merchant` del Pool [poolId]
         * con exactamente los 4 argumentos esperados. Nunca lanza.
         */
        fun from(invocation: SorobanAuthorizedInvocationXdr, poolId: String): PayArgs? = runCatching {
            val fn = (invocation.function as? SorobanAuthorizedFunctionXdr.ContractFn)?.value ?: return null
            val contract = ScvalParse.asAddressString(SCValXdr.Address(fn.contractAddress))
            if (contract != poolId) return null
            if (fn.functionName.value != AuthTreeBuilder.FN_PAY_MERCHANT) return null
            if (fn.args.size != 4) return null
            val amount = ScvalParse.asLong(fn.args[2])
            val tipBps = ScvalParse.asUIntAsInt(fn.args[3])
            if (amount <= 0 || tipBps < 0 || tipBps > AuthTreeBuilder.MAX_BPS) return null
            PayArgs(
                payer = ScvalParse.asAddressString(fn.args[0]),
                merchant = ScvalParse.asAddressString(fn.args[1]),
                amount = amount,
                tipBps = tipBps,
            )
        }.getOrNull()
    }
}

/** Veredicto sobre un sobre: listo para liquidar/cobrar, o el motivo del rechazo. */
sealed interface SobreCheck {
    data class Ok(
        val entry: SorobanAuthorizationEntryXdr,
        val args: PayArgs,
        val split: PayMerchantSplit,
        val nonce: Long,
        val expirationLedger: Long,
    ) : SobreCheck

    data class Rechazado(val motivo: String) : SobreCheck
}

/**
 * Verificación sin red de un sobre (comprobaciones 1–5 y 9 de A3 en `docs/Pago diferido por enjambre.md`):
 * es un sobre RAÍZ, lo firma una cuenta `G…`, la raíz es `pay_merchant` de nuestro Pool, el firmante es el
 * pagador del árbol, el árbol reconstruido con nuestro `fee_bps` es idéntico byte a byte (reparto, fee y
 * admin incluidos) y la firma ed25519 es válida para la red indicada.
 *
 * No comprueba expiración, nonce visto, destinatario ni tope: eso depende de quién verifica (cobrador,
 * pasarela) y de si tiene ledger.
 */
object SobreValidator {
    const val MOTIVO_NO_SOBRE = "No es un sobre RAÍZ"
    const val MOTIVO_SIN_FIRMANTE = "El sobre no lo firma una cuenta"
    const val MOTIVO_NO_POOL = "No es un pago a este Pool"
    const val MOTIVO_PAGADOR = "El pagador del árbol no es quien firma"
    const val MOTIVO_REPARTO = "El reparto no coincide (¿cambió la tarifa del Pool?)"
    const val MOTIVO_FIRMA = "Firma inválida"

    suspend fun validateText(text: String, ids: DeferredIds, feeBps: Int, network: Network): SobreCheck {
        val entry = SobreCodec.decode(text) ?: return SobreCheck.Rechazado(MOTIVO_NO_SOBRE)
        return validate(entry, ids, feeBps, network)
    }

    suspend fun validate(
        entry: SorobanAuthorizationEntryXdr,
        ids: DeferredIds,
        feeBps: Int,
        network: Network,
    ): SobreCheck {
        val creds = DeferredAuthSigner.addressCredentials(entry)
            ?: return SobreCheck.Rechazado(MOTIVO_SIN_FIRMANTE)
        val signerKey = DeferredAuthSigner.ed25519Of(creds.address)
            ?: return SobreCheck.Rechazado(MOTIVO_SIN_FIRMANTE)
        val signer = KeyPair.fromPublicKey(signerKey).getAccountId()
        val args = PayArgs.from(entry.rootInvocation, ids.pool)
            ?: return SobreCheck.Rechazado(MOTIVO_NO_POOL)
        if (args.payer != signer) return SobreCheck.Rechazado(MOTIVO_PAGADOR)
        val expected = runCatching {
            AuthTreeBuilder.payMerchantInvocation(args.payer, args.merchant, args.amount, args.tipBps, feeBps, ids)
        }.getOrElse { return SobreCheck.Rechazado(MOTIVO_REPARTO) }
        if (!expected.bytes().contentEquals(entry.rootInvocation.bytes())) {
            return SobreCheck.Rechazado(MOTIVO_REPARTO)
        }
        if (!DeferredAuthSigner.verify(entry, network)) return SobreCheck.Rechazado(MOTIVO_FIRMA)
        return SobreCheck.Ok(
            entry = entry,
            args = args,
            split = AuthTreeBuilder.split(args.amount, args.tipBps, feeBps),
            nonce = creds.nonce.value,
            expirationLedger = creds.signatureExpirationLedger.value.toLong(),
        )
    }

    private fun SorobanAuthorizedInvocationXdr.bytes(): ByteArray = XdrWriter().also { encode(it) }.toByteArray()
}
