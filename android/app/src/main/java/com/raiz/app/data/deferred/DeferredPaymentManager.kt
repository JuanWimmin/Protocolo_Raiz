package com.raiz.app.data.deferred

import android.util.Log
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.DeploymentsLoader
import com.raiz.app.data.stellar.DepositPlan
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.xdr.toXdrBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
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
 * - Antes de dar el sobre por emitido se auto-valida ([SobreValidator]) y se persiste con `commit()`.
 * - Un sobre se da por COBRADO solo con prueba de la red: el nonce consumido bajo la cuenta del pagador
 *   ([syncWithNetwork]) o una tx cuya auth sea exactamente este sobre ([confirmByHash]). Nunca por
 *   `ExistingValue` ni por un hash cualquiera.
 * - Los sobres son de cada wallet ([recordsFor]), no de la app.
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

    /** Wallet activa (G… o C…); los sobres se filtran por ella. */
    fun currentPayer(): String? = walletManager.currentAccountId()

    /** Todos los sobres del dispositivo; las pantallas filtran por [currentPayer]. */
    val recordsFlow: StateFlow<List<DeferredRecord>> get() = store.records

    fun recordsFor(payer: String?): List<DeferredRecord> = store.recordsFor(payer)

    fun pendingCountFor(payer: String?): Int = store.pendingCount(payer)

    /** ¿Se puede firmar sin señal ahora mismo, y si no, por qué (texto para la pantalla)? */
    fun availability(): Availability {
        if (walletManager.isPasskeyWallet()) return Availability.NoDisponible(MOTIVO_PASSKEY)
        if (!walletManager.isSeedWallet()) return Availability.NoDisponible(MOTIVO_SIN_WALLET)
        return when (val r = ledgerEstimator.readiness()) {
            is Readiness.Lista -> Availability.Disponible
            is Readiness.NoLista -> Availability.NoDisponible(r.motivo)
        }
    }

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
                val authXdr = signed.entry.toXdrBase64()
                val sobreQr = SobreCodec.encode(signed.entry)

                // Autocomprobación: lo que vamos a mostrar debe pasar nuestro propio validador y volver a
                // decodificar a los mismos bytes. Si no, es un bug nuestro y no sale del teléfono.
                when (val check = SobreValidator.validate(signed.entry, ids, feeBps, network)) {
                    is SobreCheck.Rechazado ->
                        return@withContext RaizResult.Error(RaizErrorCode.UNKNOWN, "$MOTIVO_AUTOCHECK: ${check.motivo}")
                    is SobreCheck.Ok -> {
                        val a = check.args
                        if (a.merchant != merchant.address || a.amount != amountStroops || a.tipBps != tipBps || a.payer != payer) {
                            return@withContext RaizResult.Error(RaizErrorCode.UNKNOWN, "$MOTIVO_AUTOCHECK: argumentos")
                        }
                    }
                }
                if (SobreCodec.decode(sobreQr)?.toXdrBase64() != authXdr) {
                    return@withContext RaizResult.Error(RaizErrorCode.UNKNOWN, "$MOTIVO_AUTOCHECK: codec")
                }

                val now = System.currentTimeMillis()
                val record = DeferredRecord(
                    id = nonce.toString(),
                    sobreQr = sobreQr,
                    authXdr = authXdr,
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
                Log.i(TAG, "sobre firmado id=${record.id} merchant=${merchant.address} amount=$amountStroops exp=$exp l0=${readiness.anchor.l0} chars=${sobreQr.length}")
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

    /** Sin red: FIRMADO/ENTREGADO con la expiración alcanzada según la estimación local → EXPIRADO. */
    fun refreshStatuses() {
        val estimate = ledgerEstimator.estimate() ?: return
        val current = store.all()
        val next = DeferredPolicy.refresh(current, estimate)
        if (next != current) store.replaceAll(next)
    }

    /**
     * Con red: cierra solos los sobres pendientes de [payer] consultando a la red, que es la única fuente
     * de verdad. Para cada uno: si la red ya **consumió su nonce** (entrada temporal bajo la cuenta del
     * pagador; existe solo si una tx con ESTE sobre se ejecutó) → CONFIRMADO, con el hash del evento de
     * pago si se encuentra; si no, y el ledger real ya pasó la expiración → EXPIRADO. Sin red no toca nada.
     * Devuelve cuántos sobres cambiaron.
     */
    suspend fun syncWithNetwork(payer: String? = currentPayer()): Int {
        val pending = store.recordsFor(payer).filter { it.pending }
        if (pending.isEmpty()) return 0
        var latest: Long? = null
        var events: List<PaymentRecord>? = null
        var changed = 0
        for (r in pending) {
            val nonce = r.id.toLongOrNull() ?: continue
            val consumed = sorobanClient.nonceConsumed(r.payer, nonce) ?: continue
            if (consumed) {
                if (events == null) {
                    events = (sorobanClient.tourPaymentEvents(r.payer) as? RaizResult.Success)?.data ?: emptyList()
                }
                // El hash se toma solo de una tx cuya auth sea ESTE sobre (regla 3): se prueban los últimos
                // pagos a ese comercio y se comprueba la auth de cada candidato.
                val hash = events
                    .filter { it.to == r.merchantAddress }
                    .sortedByDescending { it.createdAt }
                    .take(MAX_HASH_CANDIDATES)
                    .firstOrNull { ev -> sorobanClient.transactionAuthEntries(ev.txHash).authEntriesXdr.contains(r.authXdr) }
                    ?.txHash
                store.update(DeferredPolicy.markConfirmed(r, hash))
                Log.i(TAG, "sobre ${r.id} CONFIRMADO por nonce consumido; hash=${hash ?: "pendiente"}")
                changed++
            } else {
                if (latest == null) latest = sorobanClient.latestLedgerSequence()
                val l = latest ?: continue
                if (l > r.exp) {
                    store.update(DeferredPolicy.markExpiredByNetwork(r))
                    Log.i(TAG, "sobre ${r.id} EXPIRADO: ledger $l > exp ${r.exp}")
                    changed++
                }
            }
        }
        return changed
    }

    /**
     * Cierre manual v0: el usuario pega el hash que le devolvió la pasarela. Solo cuenta si la auth de esa
     * transacción es exactamente este sobre (regla 3 de `docs/Pago diferido por enjambre.md`).
     */
    suspend fun confirmByHash(id: String, hash: String): RaizResult<DeferredRecord> {
        val record = store.get(id) ?: return RaizResult.Error(RaizErrorCode.NOT_FOUND, "Sobre no encontrado")
        val h = hash.trim().lowercase()
        if (!HASH_RE.matches(h)) return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "El hash debe tener 64 caracteres hexadecimales")
        val lookup = sorobanClient.transactionAuthEntries(h)
        val esEsteSobre = lookup.authEntriesXdr.contains(record.authXdr)
        return when (lookup.status) {
            DepositPlan.TxStatus.SUCCESS -> {
                if (!esEsteSobre) return RaizResult.Error(RaizErrorCode.UNKNOWN, MOTIVO_OTRO_SOBRE)
                val next = DeferredPolicy.markConfirmed(record, h)
                store.update(next)
                RaizResult.Success(next)
            }
            DepositPlan.TxStatus.FAILED -> {
                if (!esEsteSobre) return RaizResult.Error(RaizErrorCode.UNKNOWN, MOTIVO_OTRO_SOBRE)
                val next = DeferredPolicy.markRejected(record, "La transacción con este sobre falló en la red")
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
        private const val MAX_HASH_CANDIDATES = 5
        const val MOTIVO_PASSKEY = "Firmar sin señal llega en v1 para wallets passkey."
        const val MOTIVO_SIN_WALLET = "En modo demo no se firma sin señal: crea tu wallet con frase semilla."
        const val MOTIVO_WALLET_DISTINTA = "La wallet activa no coincide con la clave guardada."
        const val MOTIVO_GUARDADO = "No pudimos guardar el sobre en el teléfono; no se mostró el QR."
        const val MOTIVO_AUTOCHECK = "Autocomprobación del sobre fallida"
        const val MOTIVO_OTRO_SOBRE = "Esa transacción no lleva este sobre: no lo damos por cobrado."
    }
}
