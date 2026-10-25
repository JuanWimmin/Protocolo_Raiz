package com.raiz.app.data.deferred

import android.util.Log
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.DeploymentsLoader
import com.raiz.app.data.stellar.DepositPlan
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import com.soneso.stellar.sdk.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquestador del lado PAGADOR del pago diferido (spec §1, §6, §7.1): decide si se puede firmar sin señal,
 * firma el sobre con la wallet semilla y lo guarda ANTES de mostrar el QR; después mantiene los estados.
 *
 * Reglas duras:
 * - Solo wallet semilla ([WalletManager.seedKeyPairOrNull]); nunca la clave demo ni passkey (v1).
 * - Nonce y expiración los fija el pagador: nonce del CSPRNG, expiración = L̂ + W con el [LedgerEstimator]
 *   (reloj monotónico). Sin ancla del estimador no se firma.
 * - Antes de dar el sobre por emitido se auto-valida (JSON ↔ XDR y firma) y se persiste con `commit()`.
 */
@Singleton
class DeferredPaymentManager @Inject constructor(
    private val walletManager: WalletManager,
    private val deploymentsLoader: DeploymentsLoader,
    private val ledgerEstimator: LedgerEstimator,
    private val store: DeferredStore,
    private val sorobanClient: SorobanClient,
) {
    private val deployments by lazy { deploymentsLoader.load() }
    private val network: Network
        get() = if (deployments.network == "public") Network.PUBLIC else Network.TESTNET

    sealed interface Availability {
        data object Disponible : Availability
        data class NoDisponible(val motivo: String) : Availability
    }

    /** ¿Se puede firmar sin señal ahora mismo, y si no, por qué (texto para la pantalla)? */
    fun availability(): Availability {
        if (walletManager.isPasskeyWallet()) return Availability.NoDisponible(MOTIVO_PASSKEY)
        if (!walletManager.isSeedWallet()) return Availability.NoDisponible(MOTIVO_SIN_WALLET)
        return when (val r = ledgerEstimator.readiness()) {
            is Readiness.Lista -> Availability.Disponible
            is Readiness.NoLista -> Availability.NoDisponible(r.motivo)
        }
    }

    val records get() = store.records

    /**
     * Firma el sobre para [merchant] por [amountStroops] (+ tip). Todo en `Dispatchers.Default`
     * (derivar la semilla y firmar cuestan cientos de ms). Devuelve el registro ya guardado.
     */
    suspend fun sign(merchant: Merchant, amountStroops: Long, tipBps: Int): RaizResult<DeferredRecord> =
        withContext(Dispatchers.Default) {
            try {
                when (val a = availability()) {
                    is Availability.NoDisponible -> return@withContext RaizResult.Error(RaizErrorCode.UNKNOWN, a.motivo)
                    Availability.Disponible -> Unit
                }
                val keyPair = walletManager.seedKeyPairOrNull()
                    ?: return@withContext RaizResult.Error(RaizErrorCode.UNAUTHORIZED, MOTIVO_SIN_WALLET)
                val payer = keyPair.getAccountId()
                val active = walletManager.currentAccountId()
                if (active != null && active != payer) {
                    return@withContext RaizResult.Error(RaizErrorCode.UNAUTHORIZED, MOTIVO_WALLET_DISTINTA)
                }
                val readiness = ledgerEstimator.readiness() as? Readiness.Lista
                    ?: return@withContext RaizResult.Error(RaizErrorCode.UNKNOWN, LedgerEstimator.MOTIVO_SIN_ANCLA)
                val exp = readiness.estimate + LedgerMath.WINDOW_12H
                if (!DeferredPolicy.canSign(readiness, exp)) {
                    return@withContext RaizResult.Error(RaizErrorCode.UNKNOWN, LedgerEstimator.MOTIVO_SIN_ANCLA)
                }

                val ids = DeferredIds.from(deployments)
                val feeBps = deployments.protocolFeeBps
                val nonce = DeferredAuthSigner.newNonce()
                val invocation = AuthTreeBuilder.payMerchantInvocation(
                    payer = payer,
                    merchant = merchant.address,
                    amount = amountStroops,
                    tipBps = tipBps,
                    feeBps = feeBps,
                    ids = ids,
                )
                val signed = DeferredAuthSigner.sign(
                    DeferredAuthSigner.unsignedEntry(payer, nonce, exp, invocation),
                    keyPair,
                    network,
                )
                val envelope = DeferredEnvelope.of(
                    entry = signed.entry,
                    payer = payer,
                    merchant = merchant.address,
                    amountStroops = amountStroops,
                    tipBps = tipBps,
                    feeBps = feeBps,
                    nonce = nonce,
                    exp = exp,
                    l0 = readiness.anchor.l0,
                    t0 = readiness.anchor.closeTimeSec,
                    network = network,
                )
                // Autocomprobación: si el sobre que vamos a mostrar no pasa nuestro propio validador, es un
                // bug nuestro y no debe salir del teléfono.
                when (val check = DeferredEnvelopeValidator.validate(envelope, ids, network)) {
                    is EnvelopeCheck.Rechazado ->
                        return@withContext RaizResult.Error(RaizErrorCode.UNKNOWN, "Autocomprobación del sobre fallida: ${check.motivo}")
                    is EnvelopeCheck.Ok -> Unit
                }
                val now = System.currentTimeMillis()
                val record = DeferredRecord(
                    id = nonce.toString(),
                    envelopeB64url = envelope.toBase64Url(),
                    payer = payer,
                    merchantAddress = merchant.address,
                    merchantName = merchant.name,
                    merchantVerified = merchant.verified,
                    amountStroops = amountStroops,
                    tipBps = tipBps,
                    feeBps = feeBps,
                    exp = exp,
                    l0 = readiness.anchor.l0,
                    t0 = readiness.anchor.closeTimeSec,
                    signedAtMs = now,
                    validUntilMs = ledgerEstimator.validUntilWallMs(),
                    status = DeferredStatus.FIRMADO,
                )
                if (!store.saveDurable(record)) {
                    return@withContext RaizResult.Error(RaizErrorCode.UNKNOWN, MOTIVO_GUARDADO)
                }
                Log.i(TAG, "sobre firmado id=${record.id} merchant=${merchant.address} amount=$amountStroops exp=$exp l0=${readiness.anchor.l0}")
                RaizResult.Success(record)
            } catch (e: Exception) {
                Log.w(TAG, "sign falló: ${e.javaClass.simpleName}: ${e.message}")
                RaizResult.Error(RaizErrorCode.UNKNOWN, "No pudimos firmar el sobre: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    /** El QR se mostró: la promesa ya puede estar viajando. */
    fun markDelivered(id: String) {
        store.get(id)?.let { store.update(DeferredPolicy.markDelivered(it)) }
    }

    /** FIRMADO/ENTREGADO con la expiración alcanzada (según la estimación) → EXPIRADO. */
    fun refreshStatuses() {
        val estimate = ledgerEstimator.estimate() ?: return
        val current = store.all()
        val next = DeferredPolicy.refresh(current, estimate)
        if (next != current) store.replaceAll(next)
    }

    /** Cierre manual v0: el usuario pega el hash que le devolvió la pasarela. */
    suspend fun confirmByHash(id: String, hash: String): RaizResult<DeferredRecord> {
        val record = store.get(id) ?: return RaizResult.Error(RaizErrorCode.NOT_FOUND, "Sobre no encontrado")
        val h = hash.trim().lowercase()
        if (!HASH_RE.matches(h)) return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "El hash debe tener 64 caracteres hexadecimales")
        return when (sorobanClient.transactionStatus(h)) {
            DepositPlan.TxStatus.SUCCESS -> {
                val next = DeferredPolicy.markConfirmed(record, h)
                store.update(next)
                RaizResult.Success(next)
            }
            DepositPlan.TxStatus.FAILED -> {
                val next = DeferredPolicy.markRejected(record, "La transacción falló en la red")
                store.update(next)
                RaizResult.Success(next)
            }
            DepositPlan.TxStatus.NOT_FOUND ->
                RaizResult.Error(RaizErrorCode.NOT_FOUND, "La red no encuentra esa transacción (o aún no está confirmada)")
            DepositPlan.TxStatus.UNKNOWN ->
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "No se pudo consultar la red; inténtalo con señal")
        }
    }

    companion object {
        private const val TAG = "RAIZ"
        private val HASH_RE = Regex("[0-9a-f]{64}")
        const val MOTIVO_PASSKEY = "Firmar sin señal llega en v1 para wallets passkey."
        const val MOTIVO_SIN_WALLET = "En modo demo no se firma sin señal: crea tu wallet con frase semilla."
        const val MOTIVO_WALLET_DISTINTA = "La wallet activa no coincide con la clave guardada."
        const val MOTIVO_GUARDADO = "No pudimos guardar el sobre en el teléfono; no se mostró el QR."
    }
}
