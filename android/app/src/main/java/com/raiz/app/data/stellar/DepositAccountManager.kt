package com.raiz.app.data.stellar

import android.util.Log
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.sep.sep05.Mnemonic
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Quién firma y recibe un depósito SEP-24, y a dónde va el dinero al final.
 *
 * - Wallet de semilla (o demo): [signer] es la propia wallet y [destination] == [account].
 * - Wallet passkey: [signer]/[account] son la CUENTA DE DEPÓSITO (cuenta clásica de tránsito
 *   creada en el teléfono) y [destination] es el smart account `C…` del usuario.
 *
 * El `KeyPair` solo vive en memoria mientras dura la operación: no se guarda en ningún
 * `UiState`, `SavedStateHandle` ni log.
 */
class DepositRoute(
    val signer: KeyPair,
    val account: String,
    val destination: String,
) {
    /** `true` en passkey: tras convertir hay que enviar el USDC del fondo al smart account. */
    val needsForward: Boolean get() = destination != account
}

/**
 * Resuelve la "cuenta operativa" del depósito SEP-24 (D3) para la wallet activa y lleva su
 * estado local (transacción en vuelo, marca de depósito iniciado).
 *
 * ## Por qué existe la cuenta de depósito
 *
 * Una wallet passkey es un smart account `C…`: no puede firmar el challenge SEP-10 clásico
 * ni un `PathPaymentStrictSend`, y una operación clásica tampoco admite un `C…` como
 * destino. Hasta que RAÍZ implemente SEP-45 (autenticación directa del smart account), el
 * depósito de una wallet passkey pasa por una cuenta clásica Ed25519 generada en el teléfono
 * y ligada a ese `C…`: recibe del anchor, convierte al USDC del fondo y lo reenvía al `C…`
 * con un `transfer` del SAC. El smart account solo recibe: no hace falta la huella.
 *
 * ## Reglas de seguridad
 *
 * - La frase de la cuenta de depósito se guarda cifrada en [SecureWalletStore] (archivo
 *   `raiz_deposit`), NUNCA se loguea ni sale del teléfono, sobrevive al cierre de sesión y
 *   no existe código que la borre o la sobrescriba.
 * - La rama passkey JAMÁS usa `WalletManager.currentKeyPair()`: sin semilla guardada esa
 *   función cae al `demoKeyPair()` (builds debug con `raiz.tourist.secret`), y el depósito se
 *   firmaría con la cuenta demo.
 * - El destino del envío final sale solo del `C…` guardado en el dispositivo
 *   (`SecureWalletStore.storedPasskeyContractId`).
 */
@Singleton
class DepositAccountManager @Inject constructor(
    private val store: SecureWalletStore,
    private val walletManager: WalletManager,
) {
    /** Evita que dos llamadas simultáneas generen dos cuentas de depósito distintas. */
    private val createMutex = Mutex()

    /**
     * Candado ÚNICO (de proceso) del cierre de un depósito: resolver la transacción en vuelo,
     * convertir y enviar a la wallet comparten cuenta y número de secuencia, así que solo una
     * corrutina a la vez puede hacerlo. El `settleJob` de `DepositViewModel` solo protege dentro
     * de UNA pantalla; pueden coexistir dos (la cola `NonCancellable` de una pantalla recién
     * cerrada, o la que sigue viva detrás del bloqueo biométrico) y sin este candado enviarían
     * dos transacciones con la misma secuencia.
     */
    val settlementLock = Mutex()

    /** `C…` del smart account activo si la wallet activa es passkey; `null` en semilla/demo. */
    fun passkeyOwner(): String? {
        if (!walletManager.isPasskeyWallet()) return null
        return store.storedPasskeyContractId()?.takeIf { DepositPlan.isContractAddress(it) }
    }

    /** G… de la cuenta de depósito ya creada para la wallet passkey activa, sin derivar ni crear. */
    fun depositAccountId(): String? = passkeyOwner()?.let { store.depositAccountFor(it) }

    /**
     * Ruta del depósito para la wallet activa.
     *
     * @param createIfMissing en passkey, si aún no hay cuenta de depósito: `true` la genera y la
     *   guarda (solo al iniciar un depósito); `false` devuelve `Success(null)` — no hay nada en
     *   camino que terminar.
     */
    suspend fun route(createIfMissing: Boolean): RaizResult<DepositRoute?> {
        if (!walletManager.isPasskeyWallet()) {
            val keyPair = walletManager.currentKeyPair()
                ?: return RaizResult.Error(RaizErrorCode.UNAUTHORIZED, MSG_NO_SIGNER)
            val account = keyPair.getAccountId()
            return RaizResult.Success(DepositRoute(signer = keyPair, account = account, destination = account))
        }

        val owner = passkeyOwner()
            ?: return RaizResult.Error(RaizErrorCode.NOT_FOUND, "No hay wallet passkey activa.")

        return createMutex.withLock {
            when (val record = store.depositAccountRecord(owner)) {
                SecureWalletStore.DepositAccountRecord.Unreadable ->
                    RaizResult.Error(RaizErrorCode.UNKNOWN, MSG_UNREADABLE)

                is SecureWalletStore.DepositAccountRecord.Found -> {
                    val keyPair = deriveKeyPair(record.seedPhrase)
                    when {
                        keyPair == null -> RaizResult.Error(RaizErrorCode.UNKNOWN, MSG_UNREADABLE)
                        keyPair.getAccountId() != record.accountId -> {
                            Log.e(TAG, "La cuenta de depósito guardada para $owner no coincide con su clave")
                            RaizResult.Error(RaizErrorCode.UNKNOWN, MSG_UNREADABLE)
                        }
                        // Antes de RECIBIR dinero nuevo la frase tiene que estar en disco, no solo
                        // en la memoria del proceso. Para terminar algo que ya está en camino no se
                        // exige: ese dinero ya está en la cuenta y lo urgente es sacarlo de ahí.
                        createIfMissing && !store.isDepositStoreDurable() -> {
                            Log.e(TAG, "La cuenta de depósito de $owner no está confirmada en disco; no se inicia otro depósito")
                            RaizResult.Error(RaizErrorCode.UNKNOWN, MSG_CREATE_FAILED)
                        }
                        else -> RaizResult.Success(
                            DepositRoute(signer = keyPair, account = record.accountId, destination = owner),
                        )
                    }
                }

                SecureWalletStore.DepositAccountRecord.None ->
                    if (createIfMissing) createDepositAccount(owner) else RaizResult.Success(null)
            }
        }
    }

    private suspend fun createDepositAccount(owner: String): RaizResult<DepositRoute?> {
        val phrase = try {
            Mnemonic.generate12WordsMnemonic()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo generar la cuenta de depósito (${e.javaClass.simpleName})")
            return RaizResult.Error(RaizErrorCode.UNKNOWN, MSG_CREATE_FAILED)
        }
        val keyPair = deriveKeyPair(phrase)
            ?: return RaizResult.Error(RaizErrorCode.UNKNOWN, MSG_CREATE_FAILED)
        val accountId = keyPair.getAccountId()
        // Guardar (síncrono y releído) ANTES de que la cuenta pueda recibir nada.
        if (!store.saveDepositAccount(owner, phrase, accountId)) {
            return RaizResult.Error(RaizErrorCode.UNKNOWN, MSG_CREATE_FAILED)
        }
        Log.i(TAG, "Cuenta de depósito creada: $accountId → wallet passkey $owner")
        return RaizResult.Success(DepositRoute(signer = keyPair, account = accountId, destination = owner))
    }

    /** Mismo camino que `WalletManager` para una wallet semilla: BIP-39 → cuenta 0. Nunca loguea la frase. */
    private suspend fun deriveKeyPair(phrase: String): KeyPair? = try {
        Mnemonic.from(phrase).use { it.getKeyPair(0) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo derivar la cuenta de depósito (${e.javaClass.simpleName})")
        null
    }

    // ── Transacción en vuelo (diario) ─────────────────────────────────────

    /** Transacción propia en vuelo de la cuenta operativa `account`, o `null`. */
    fun pendingTx(account: String): PendingDepositTx? = PendingDepositTx.decode(store.pendingDepositTx(account))

    /** Guarda la transacción en vuelo ANTES de enviarla. `false` si no se pudo escribir. */
    fun savePendingTx(account: String, tx: PendingDepositTx): Boolean =
        store.savePendingDepositTx(account, tx.encode())

    /** Borra la transacción en vuelo solo si sigue siendo `txHash` (no pisa una posterior). */
    fun clearPendingTx(account: String, txHash: String) {
        val current = pendingTx(account)
        if (current == null || current.hash.equals(txHash, ignoreCase = true)) {
            store.clearPendingDepositTx(account)
        }
    }

    // ── "Depósito en camino" (solo passkey; sin red) ───────────────────────

    /** Marca que la wallet passkey activa acaba de abrir un depósito con el anchor. */
    fun markDepositStarted() {
        val owner = passkeyOwner() ?: return
        store.markDepositStarted(owner, nowSec())
    }

    /** El depósito abierto terminó sin pago (el anchor lo dio por fallido/expirado): ya no hay nada en camino. */
    fun clearDepositStarted() {
        val owner = passkeyOwner() ?: return
        store.clearDepositStarted(owner)
    }

    /**
     * El anchor acaba de pagar a la cuenta de depósito: desde ahora hay dinero en tránsito
     * (aunque Horizon tarde unos segundos en reflejarlo) y la marca de "depósito iniciado" ya
     * cumplió su papel. No hace nada en wallets de semilla.
     */
    fun onAnchorPaid() {
        val owner = passkeyOwner() ?: return
        store.setDepositInTransit(owner, true)
        store.clearDepositStarted(owner)
    }

    /** Resultado de la última comprobación on-chain: ¿hay USDC en la cuenta de depósito? */
    fun setInTransit(inTransit: Boolean) {
        val owner = passkeyOwner() ?: return
        store.setDepositInTransit(owner, inTransit)
    }

    /**
     * ¿La wallet passkey activa tiene un depósito a medio camino? Decide solo con datos
     * locales (sirve sin red, p. ej. antes de cerrar sesión): USDC visto en la cuenta de
     * depósito en la última comprobación, una transacción propia en vuelo, o un depósito
     * abierto hace menos de [DepositPlan.STARTED_GRACE_SEC].
     */
    fun hasPendingDeposit(): Boolean {
        val owner = passkeyOwner() ?: return false
        val account = store.depositAccountFor(owner) ?: return false
        return store.depositInTransit(owner) ||
            pendingTx(account) != null ||
            DepositPlan.isStartedMarkerFresh(store.depositStartedAt(owner), nowSec())
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    companion object {
        private const val TAG = "RAIZ"
        const val MSG_NO_SIGNER = "No se pudo firmar: no hay wallet activa."
        private const val MSG_UNREADABLE =
            "No se pudo leer tu cuenta de depósito en este teléfono. Reintenta más tarde."
        private const val MSG_CREATE_FAILED =
            "No se pudo guardar tu cuenta de depósito en este teléfono (¿almacenamiento lleno?). " +
                "Libera espacio y reintenta."
    }
}
