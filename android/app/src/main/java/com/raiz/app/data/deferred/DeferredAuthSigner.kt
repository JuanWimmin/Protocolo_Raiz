package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.Address
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.crypto.getSha256Crypto
import com.soneso.stellar.sdk.scval.Scv
import com.soneso.stellar.sdk.xdr.HashIDPreimageSorobanAuthorizationXdr
import com.soneso.stellar.sdk.xdr.HashIDPreimageXdr
import com.soneso.stellar.sdk.xdr.HashXdr
import com.soneso.stellar.sdk.xdr.Int64Xdr
import com.soneso.stellar.sdk.xdr.PublicKeyXdr
import com.soneso.stellar.sdk.xdr.SCAddressXdr
import com.soneso.stellar.sdk.xdr.SCMapEntryXdr
import com.soneso.stellar.sdk.xdr.SCValXdr
import com.soneso.stellar.sdk.xdr.SorobanAddressCredentialsXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizationEntryXdr
import com.soneso.stellar.sdk.xdr.SorobanAuthorizedInvocationXdr
import com.soneso.stellar.sdk.xdr.SorobanCredentialsXdr
import com.soneso.stellar.sdk.xdr.Uint32Xdr
import com.soneso.stellar.sdk.xdr.XdrWriter
import java.security.SecureRandom

/** Resultado de firmar: la entrada lista para el sobre, el hash firmado y la firma ed25519 (64 bytes). */
class SignedDeferredAuth(
    val entry: SorobanAuthorizationEntryXdr,
    val payloadHash: ByteArray,
    val signature: ByteArray,
)

/**
 * Firma **sin red** de una [SorobanAuthorizationEntryXdr] con credenciales de dirección (cuenta `G…`),
 * exactamente como la verifica el host de Soroban y como la produce `Auth.authorizeEntry` del SDK:
 *
 * ```
 * payload = sha256( XDR( HashIdPreimage::SorobanAuthorization { network_id, nonce, signature_expiration_ledger, invocation } ) )
 * credentials.address.signature = Vec[ Map { "public_key": Bytes32, "signature": Bytes64 } ]
 * ```
 *
 * Por qué a mano y no `Auth.authorizeInvocation`: ese elige el nonce al azar y no expone el preimage,
 * y el pagador del pago diferido necesita fijar nonce y expiración él mismo (estimados sin red, spec
 * §3.1) y el cobrador necesita verificar sobres ajenos ([verify]). En el SDK 1.6.0 `Util.hash` y la
 * serialización del preimage son `internal`; de ahí [xdrBytes] y `getSha256Crypto()`.
 *
 * Solo credenciales legacy (`SorobanCredentialsXdr.Address`): es lo que conoce este SDK y lo que la
 * red sigue aceptando en Protocol 29 (medido el 25-oct-2026).
 */
object DeferredAuthSigner {
    const val KEY_PUBLIC_KEY = "public_key"
    const val KEY_SIGNATURE = "signature"
    const val SIGNATURE_BYTES = 64

    private val random = SecureRandom()

    /** 64 bits del CSPRNG; puede ser negativo (es un `int64` en XDR). La red lo consume una sola vez. */
    fun newNonce(): Long = random.nextLong()

    /** Entrada con la firma vacía (`SCV_VOID`), igual que la devuelve la simulación en modo record. */
    fun unsignedEntry(
        payer: String,
        nonce: Long,
        expirationLedger: Long,
        invocation: SorobanAuthorizedInvocationXdr,
    ): SorobanAuthorizationEntryXdr =
        SorobanAuthorizationEntryXdr(
            credentials = SorobanCredentialsXdr.Address(
                SorobanAddressCredentialsXdr(
                    address = Address(payer).toSCAddress(),
                    nonce = Int64Xdr(nonce),
                    signatureExpirationLedger = Uint32Xdr(expirationLedger.toUInt()),
                    signature = Scv.toVoid(),
                ),
            ),
            rootInvocation = invocation,
        )

    /** Preimage `ENVELOPE_TYPE_SOROBAN_AUTHORIZATION` (tipo 9). */
    fun preimage(
        networkId: ByteArray,
        nonce: Long,
        expirationLedger: Long,
        invocation: SorobanAuthorizedInvocationXdr,
    ): HashIDPreimageXdr =
        HashIDPreimageXdr.SorobanAuthorization(
            HashIDPreimageSorobanAuthorizationXdr(
                networkId = HashXdr(networkId),
                nonce = Int64Xdr(nonce),
                signatureExpirationLedger = Uint32Xdr(expirationLedger.toUInt()),
                invocation = invocation,
            ),
        )

    fun xdrBytes(preimage: HashIDPreimageXdr): ByteArray = XdrWriter().also { preimage.encode(it) }.toByteArray()

    /** Lo que firma el pagador: `sha256(XDR(preimage))`. */
    suspend fun payloadHash(
        network: Network,
        nonce: Long,
        expirationLedger: Long,
        invocation: SorobanAuthorizedInvocationXdr,
    ): ByteArray = getSha256Crypto().hash(xdrBytes(preimage(network.networkId(), nonce, expirationLedger, invocation)))

    /** `Vec[ Map{ public_key, signature } ]`: el formato que el host espera para una cuenta `G…`. */
    fun signatureScVal(publicKey: ByteArray, signature: ByteArray): SCValXdr =
        Scv.toVec(
            listOf(
                Scv.toMap(
                    linkedMapOf(
                        Scv.toSymbol(KEY_PUBLIC_KEY) to Scv.toBytes(publicKey),
                        Scv.toSymbol(KEY_SIGNATURE) to Scv.toBytes(signature),
                    ),
                ),
            ),
        )

    /**
     * Firma [entry] con [keyPair] para [network]. Nonce y expiración se toman de las credenciales de la
     * propia entrada (es lo que queda firmado). Lanza si la entrada no es de la dirección del keypair.
     */
    suspend fun sign(entry: SorobanAuthorizationEntryXdr, keyPair: KeyPair, network: Network): SignedDeferredAuth {
        val creds = addressCredentials(entry)
            ?: throw IllegalArgumentException("La entrada no lleva credenciales de dirección")
        val publicKey = keyPair.getPublicKey()
        require(ed25519Of(creds.address)?.contentEquals(publicKey) == true) {
            "El keypair no corresponde a la dirección de la entrada"
        }
        val hash = payloadHash(network, creds.nonce.value, creds.signatureExpirationLedger.value.toLong(), entry.rootInvocation)
        val signature = keyPair.sign(hash)
        val signed = entry.copy(
            credentials = SorobanCredentialsXdr.Address(creds.copy(signature = signatureScVal(publicKey, signature))),
        )
        return SignedDeferredAuth(entry = signed, payloadHash = hash, signature = signature)
    }

    /**
     * Verificación offline (cobrador): una sola firma, de la clave de la dirección, válida sobre el
     * preimage de [network]. No comprueba expiración ni nonce: eso lo hace quien conoce el ledger.
     */
    suspend fun verify(entry: SorobanAuthorizationEntryXdr, network: Network): Boolean {
        val creds = addressCredentials(entry) ?: return false
        val expectedKey = ed25519Of(creds.address) ?: return false
        val signatures = (creds.signature as? SCValXdr.Vec)?.value?.value ?: return false
        if (signatures.size != 1) return false
        val fields = (signatures.single() as? SCValXdr.Map)?.value?.value ?: return false
        val publicKey = bytesField(fields, KEY_PUBLIC_KEY) ?: return false
        val signature = bytesField(fields, KEY_SIGNATURE) ?: return false
        if (!publicKey.contentEquals(expectedKey) || signature.size != SIGNATURE_BYTES) return false
        val hash = payloadHash(network, creds.nonce.value, creds.signatureExpirationLedger.value.toLong(), entry.rootInvocation)
        return KeyPair.fromPublicKey(publicKey).verify(hash, signature)
    }

    fun addressCredentials(entry: SorobanAuthorizationEntryXdr): SorobanAddressCredentialsXdr? =
        (entry.credentials as? SorobanCredentialsXdr.Address)?.value

    /** Clave ed25519 (32 bytes) de una dirección de cuenta `G…`; null si es un contrato u otro tipo. */
    fun ed25519Of(address: SCAddressXdr): ByteArray? =
        ((address as? SCAddressXdr.AccountId)?.value?.value as? PublicKeyXdr.Ed25519)?.value?.value

    private fun bytesField(fields: List<SCMapEntryXdr>, key: String): ByteArray? =
        fields.firstOrNull { (it.key as? SCValXdr.Sym)?.value?.value == key }
            ?.let { (it.`val` as? SCValXdr.Bytes)?.value?.value }
}
