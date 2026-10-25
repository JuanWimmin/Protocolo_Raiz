package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.Address
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.xdr.SCAddressXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizationEntryXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizedInvocationXdr
import com.soneso.stellar.sdk.xdr.XdrWriter
import com.soneso.stellar.sdk.xdr.fromXdrBase64
import com.soneso.stellar.sdk.xdr.toXdrBase64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * El **sobre** del pago diferido (spec `docs/pago_diferido_v0.md` §5): JSON compacto → base64url → QR.
 *
 * Los campos fuera de [auth] son redundantes (todo está dentro del XDR) y existen para que el cobrador
 * muestre el detalle y verifique ANTES de decodificar el XDR; la pasarela reconstruye la invocación desde
 * el XDR, nunca desde el JSON. Si JSON y XDR difieren → RECHAZADO ([DeferredEnvelopeValidator]).
 *
 * El orden de las propiedades es el del JSON canónico del fixture (`scripts/gen-auth-fixture.mjs`):
 * `v, net, payer, merchant, amount, tip_bps, fee_bps, nonce, exp, l0, t0, auth`. Ninguna propiedad con
 * backing field adicional: kotlinx.serialization la serializaría y rompería la igualdad byte a byte.
 */
@Serializable
data class DeferredEnvelope(
    val v: Int,
    val net: String,
    val payer: String,
    val merchant: String,
    /** Monto base en stroops, en decimal (i128 en el contrato). */
    val amount: String,
    @SerialName("tip_bps") val tipBps: Int,
    @SerialName("fee_bps") val feeBps: Int,
    /** Nonce int64 en decimal (puede ser negativo). */
    val nonce: String,
    /** `signature_expiration_ledger`. */
    val exp: Long,
    /** Último ledger conocido por el pagador al firmar. */
    val l0: Long,
    /** Instante de cierre (epoch s) de [l0]: dato informativo, el reloj monotónico no sale del teléfono. */
    val t0: Long,
    /** `SorobanAuthorizationEntry` firmada, XDR en base64 (con padding, como `toXdrBase64()`). */
    val auth: String,
) {
    val amountStroops: Long? get() = amount.toLongOrNull()
    val nonceLong: Long? get() = nonce.toLongOrNull()

    /** JSON compacto canónico (sin espacios, orden de declaración). */
    fun toJson(): String = CANONICAL.encodeToString(serializer(), this)

    /** Lo que va en el QR y en "Copiar sobre": base64url SIN padding del JSON canónico en UTF-8. */
    fun toBase64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toJson().toByteArray(Charsets.UTF_8))

    /** Decodifica el XDR de [auth]; lanza si está corrupto (usar dentro de `runCatching`). */
    fun entry(): SorobanAuthorizationEntryXdr = SorobanAuthorizationEntryXdr.fromXdrBase64(auth)

    companion object {
        const val VERSION = 1

        private val CANONICAL = Json
        private val LENIENT = Json { ignoreUnknownKeys = true }

        fun of(
            entry: SorobanAuthorizationEntryXdr,
            payer: String,
            merchant: String,
            amountStroops: Long,
            tipBps: Int,
            feeBps: Int,
            nonce: Long,
            exp: Long,
            l0: Long,
            t0: Long,
            network: Network,
        ): DeferredEnvelope = DeferredEnvelope(
            v = VERSION,
            net = network.networkPassphrase,
            payer = payer,
            merchant = merchant,
            amount = amountStroops.toString(),
            tipBps = tipBps,
            feeBps = feeBps,
            nonce = nonce.toString(),
            exp = exp,
            l0 = l0,
            t0 = t0,
            auth = entry.toXdrBase64(),
        )

        /**
         * Acepta el base64url (con o sin padding, con espacios o saltos alrededor) o el JSON crudo.
         * Nunca lanza: devuelve `Result.failure` con basura.
         */
        fun parse(raw: String): Result<DeferredEnvelope> = runCatching {
            val text = raw.trim()
            val json = if (text.startsWith("{")) {
                text
            } else {
                val compact = text.filterNot { it.isWhitespace() }.trimEnd('=')
                String(Base64.getUrlDecoder().decode(compact), Charsets.UTF_8)
            }
            LENIENT.decodeFromString(serializer(), json)
        }
    }
}

/** Resultado de validar un sobre: o la entrada lista para enviar y su reparto, o el motivo del rechazo. */
sealed interface EnvelopeCheck {
    data class Ok(val entry: SorobanAuthorizationEntryXdr, val split: PayMerchantSplit) : EnvelopeCheck
    data class Rechazado(val motivo: String) : EnvelopeCheck
}

/**
 * Validación JSON ↔ XDR del sobre (spec §5 y §8.7). Reconstruye la invocación con [AuthTreeBuilder] a partir
 * de los campos del JSON y la compara BYTE A BYTE con `rootInvocation` del XDR; después verifica la firma.
 * No comprueba expiración ni nonce consumido: eso lo hace quien conoce el ledger (estimador o red).
 */
object DeferredEnvelopeValidator {
    const val MOTIVO_VERSION = "versión del sobre no soportada"
    const val MOTIVO_RED = "sobre de otra red"
    const val MOTIVO_MONTO = "monto ilegible"
    const val MOTIVO_NONCE = "nonce ilegible"
    const val MOTIVO_AUTH = "autorización ilegible"
    const val MOTIVO_CREDENCIALES = "la autorización no es de una cuenta"
    const val MOTIVO_PAGADOR = "el pagador no coincide con la firma"
    const val MOTIVO_NONCE_EXP = "nonce o expiración no coinciden"
    const val MOTIVO_ARBOL = "monto o destinatario no coinciden"
    const val MOTIVO_FIRMA = "firma inválida"

    suspend fun validate(env: DeferredEnvelope, ids: DeferredIds, network: Network): EnvelopeCheck {
        if (env.v != DeferredEnvelope.VERSION) return EnvelopeCheck.Rechazado(MOTIVO_VERSION)
        if (env.net != network.networkPassphrase) return EnvelopeCheck.Rechazado(MOTIVO_RED)
        val amount = env.amountStroops ?: return EnvelopeCheck.Rechazado(MOTIVO_MONTO)
        val nonce = env.nonceLong ?: return EnvelopeCheck.Rechazado(MOTIVO_NONCE)
        val entry = runCatching { env.entry() }.getOrElse { return EnvelopeCheck.Rechazado(MOTIVO_AUTH) }
        val creds = DeferredAuthSigner.addressCredentials(entry)
            ?: return EnvelopeCheck.Rechazado(MOTIVO_CREDENCIALES)
        val payerAddress = runCatching { Address(env.payer).toSCAddress() }
            .getOrElse { return EnvelopeCheck.Rechazado(MOTIVO_PAGADOR) }
        if (!payerAddress.bytes().contentEquals(creds.address.bytes())) {
            return EnvelopeCheck.Rechazado(MOTIVO_PAGADOR)
        }
        if (creds.nonce.value != nonce || creds.signatureExpirationLedger.value.toLong() != env.exp) {
            return EnvelopeCheck.Rechazado(MOTIVO_NONCE_EXP)
        }
        val expected = runCatching {
            AuthTreeBuilder.payMerchantInvocation(env.payer, env.merchant, amount, env.tipBps, env.feeBps, ids)
        }.getOrElse { return EnvelopeCheck.Rechazado(MOTIVO_ARBOL) }
        if (!expected.bytes().contentEquals(entry.rootInvocation.bytes())) {
            return EnvelopeCheck.Rechazado(MOTIVO_ARBOL)
        }
        if (!DeferredAuthSigner.verify(entry, network)) return EnvelopeCheck.Rechazado(MOTIVO_FIRMA)
        return EnvelopeCheck.Ok(entry, AuthTreeBuilder.split(amount, env.tipBps, env.feeBps))
    }

    private fun SCAddressXdr.bytes(): ByteArray = XdrWriter().also { encode(it) }.toByteArray()
    private fun SorobanAuthorizedInvocationXdr.bytes(): ByteArray = XdrWriter().also { encode(it) }.toByteArray()
}
