package com.raiz.app.data.stellar

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistencia segura de la wallet del usuario.
 *
 * Usa `EncryptedSharedPreferences` que cifra valores con AES-256-GCM. La
 * clave maestra vive en el Android Keystore (en TEE/StrongBox si el
 * dispositivo lo soporta) y nunca sale del sistema operativo.
 *
 * Lo que guardamos:
 *   - `seed_phrase`: las 12 palabras BIP-39 separadas por espacio. Es lo
 *     sensible; solo se descifra cuando se va a firmar una transacción.
 *   - `account_id`: el `G...` derivado. Lo guardamos también desencriptado
 *     a nivel API (igual está cifrado en disco) para acceso rápido sync sin
 *     tener que derivar el KeyPair en cada lectura.
 *
 * NOTA seguridad: `xml/backup_rules.xml` y `xml/data_extraction_rules.xml`
 * excluyen `raiz_wallet.xml` y `raiz_deposit.xml` (el nombre del archivo CON
 * extensión, que es como Android los busca en `shared_prefs/`) del backup en
 * la nube y de la migración de teléfono a teléfono — para que el secret no
 * salga del dispositivo. Además el manifest lleva `allowBackup="false"`.
 *
 * Aparte de la wallet, este store guarda (en un archivo distinto,
 * `raiz_deposit`) las "cuentas de depósito" de las wallets passkey: ver la
 * sección "Cuenta de depósito".
 */
@Singleton
class SecureWalletStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences by lazy { buildPrefs(context, FILE_NAME) }

    /**
     * Segundo archivo cifrado (mismo esquema y misma clave maestra que [prefs]) para las
     * "cuentas de depósito" de las wallets passkey. Va APARTE a propósito: [clear] (logout)
     * no lo toca, porque la semilla de una cuenta de depósito no la tiene nadie más y puede
     * haber dinero en tránsito en ella. Ver la sección "Cuenta de depósito" más abajo.
     */
    private val depositPrefs: SharedPreferences by lazy { buildPrefs(context, DEPOSIT_FILE_NAME) }

    private fun buildPrefs(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── Wallet BIP-39 (seed phrase) ───────────────────────────────────────

    /** ¿Hay una wallet seed guardada en este dispositivo? */
    fun hasStoredWallet(): Boolean = prefs.contains(KEY_SEED) && prefs.contains(KEY_ACCOUNT_ID)

    /** Public key (G...) de la wallet seed guardada, o null si no hay. */
    fun storedAccountId(): String? = prefs.getString(KEY_ACCOUNT_ID, null)

    /** Las 12 palabras BIP-39 de la wallet guardada, o null si no hay. */
    fun storedSeedPhrase(): String? = prefs.getString(KEY_SEED, null)

    /** Guarda la wallet seed (sobrescribe la anterior si existía). */
    fun save(seedPhrase: String, accountId: String) {
        prefs.edit()
            .putString(KEY_SEED, seedPhrase.trim())
            .putString(KEY_ACCOUNT_ID, accountId)
            .apply()
        Log.i(TAG, "Wallet seed guardada: $accountId")
    }

    // ── Wallet Passkey / Smart Account ────────────────────────────────────

    /**
     * ¿Hay una smart wallet (passkey / secp256r1) guardada en este dispositivo?
     *
     * Se guarda tras completar [PasskeyWalletManager.createSmartWallet].
     * Los datos persisten mientras el usuario no haga logout explícito.
     */
    fun hasStoredPasskeyWallet(): Boolean =
        prefs.contains(KEY_PASSKEY_CREDENTIAL_ID) && prefs.contains(KEY_PASSKEY_CONTRACT_ID)

    /**
     * credentialId de la passkey registrada (base64url del CBOR ID).
     * Necesario para volver a conectar la smart wallet en sesiones futuras
     * via `OZWalletOperations.connectWallet(...)`.
     */
    fun storedPasskeyCredentialId(): String? = prefs.getString(KEY_PASSKEY_CREDENTIAL_ID, null)

    /**
     * Dirección C... del smart account desplegado en Soroban.
     * Es el equivalente al "publicKey" G... para las wallets de seed phrase.
     */
    fun storedPasskeyContractId(): String? = prefs.getString(KEY_PASSKEY_CONTRACT_ID, null)

    /**
     * Guarda la referencia de la smart wallet passkey.
     * Los valores NO son secretos: el material privado vive en el FIDO2
     * provider del SO (Android Keystore / TEE), no aquí.
     */
    fun savePasskeyWallet(credentialId: String, contractId: String) {
        prefs.edit()
            .putString(KEY_PASSKEY_CREDENTIAL_ID, credentialId)
            .putString(KEY_PASSKEY_CONTRACT_ID, contractId)
            .apply()
        Log.i(TAG, "Smart wallet guardada: $contractId")
    }

    /** Borra solo la referencia passkey (no toca la seed wallet si existe). */
    fun clearPasskeyWallet() {
        prefs.edit()
            .remove(KEY_PASSKEY_CREDENTIAL_ID)
            .remove(KEY_PASSKEY_CONTRACT_ID)
            .apply()
        Log.i(TAG, "Smart wallet passkey borrada")
    }

    // ── Rol preferido del usuario ─────────────────────────────────────────

    /**
     * Persiste el nombre del rol preferido (valor de [UserRole.name]:
     * "TOURIST", "MERCHANT" o "RESIDENT"). No es información sensible,
     * pero se limpia al hacer logout via [clear] para no contaminar la
     * sesión de otro usuario en el mismo dispositivo.
     */
    fun savePreferredRole(role: String) {
        prefs.edit().putString(KEY_PREFERRED_ROLE, role).apply()
    }

    /**
     * Nombre del rol guardado, o null si el usuario no lo ha elegido
     * todavía o si se llamó [clear] (logout).
     */
    fun preferredRole(): String? = prefs.getString(KEY_PREFERRED_ROLE, null)

    // ── Barrio del residente pendiente de verificación ────────────────────

    /**
     * Guarda el hex (64 chars) del barrio que el usuario eligió al entrar como
     * "residente" en el onboarding, ANTES de que el admin le mintee el
     * ResidentToken. Se lee luego en ProposalsScreen para saber a qué barrio
     * mintear al pulsar "Verificar como residente". No es sensible; se limpia
     * con [clear] al hacer logout.
     */
    fun savePendingResidentBarrio(hex: String) {
        prefs.edit().putString(KEY_PENDING_RESIDENT_BARRIO, hex).apply()
    }

    /** Hex del barrio elegido por el residente pendiente, o null si no eligió. */
    fun pendingResidentBarrio(): String? = prefs.getString(KEY_PENDING_RESIDENT_BARRIO, null)

    // ── Cuenta de depósito (wallets passkey) ──────────────────────────────
    //
    // Una wallet passkey es un smart account C… y no puede firmar SEP-10 clásico ni un
    // path payment. Para depositar con el anchor la app crea UNA cuenta clásica de tránsito
    // por smart account ("cuenta de depósito"): recibe del anchor, convierte y reenvía al C….
    // Su frase BIP-39 se guarda aquí, cifrada igual que la seed de una wallet semilla, en el
    // archivo `raiz_deposit` — que [clear] NO borra. No existe ningún método que borre o
    // sobrescriba una cuenta ya guardada: si se perdiera con dinero en tránsito, ese dinero
    // sería irrecuperable. (Lo único que se descarta es una cuenta que NO llegó a escribirse en
    // disco y que por tanto nadie llegó a usar: ver [saveDepositAccount].)
    //
    // Nada de esto se loguea salvo direcciones públicas (C… y G…): jamás la frase.

    /** Resultado de leer la cuenta de depósito de un smart account. */
    sealed interface DepositAccountRecord {
        /** Aún no se creó ninguna para ese smart account. */
        data object None : DepositAccountRecord

        data class Found(val seedPhrase: String, val accountId: String) : DepositAccountRecord

        /**
         * El archivo cifrado no se pudo leer (Keystore, archivo dañado). NO equivale a [None]:
         * crear una cuenta nueva encima podría dejar huérfana la anterior.
         */
        data object Unreadable : DepositAccountRecord
    }

    /** Cuenta de depósito asociada al smart account `owner` (C…). */
    fun depositAccountRecord(owner: String): DepositAccountRecord = try {
        val seed = depositPrefs.getString(KEY_DEPOSIT_SEED + owner, null)
        val account = depositPrefs.getString(KEY_DEPOSIT_ACCOUNT + owner, null)
        when {
            seed != null && account != null -> DepositAccountRecord.Found(seed, account)
            seed == null && account == null && !depositPrefs.contains(KEY_DEPOSIT_SEED + owner) ->
                DepositAccountRecord.None
            else -> DepositAccountRecord.Unreadable
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo leer la cuenta de depósito de $owner (${e.javaClass.simpleName})")
        DepositAccountRecord.Unreadable
    }

    /** G… de la cuenta de depósito de `owner`, o `null` si no hay (o no se pudo leer). */
    fun depositAccountFor(owner: String): String? =
        (depositAccountRecord(owner) as? DepositAccountRecord.Found)?.accountId

    /**
     * Guarda la cuenta de depósito de `owner`. Escritura SÍNCRONA (`commit`, no `apply`) y
     * verificada con una relectura: la cuenta va a recibir dinero justo después y de esta
     * frase no hay otra copia. Nunca sobrescribe: si ya existe una, solo devuelve `true`
     * cuando es exactamente la misma.
     *
     * @return `true` si la cuenta quedó guardada y se pudo releer.
     */
    fun saveDepositAccount(owner: String, seedPhrase: String, accountId: String): Boolean = try {
        val phrase = seedPhrase.trim()
        when (val existing = depositAccountRecord(owner)) {
            is DepositAccountRecord.Found ->
                existing.seedPhrase == phrase && existing.accountId == accountId && isDepositStoreDurable()
            DepositAccountRecord.Unreadable -> false
            DepositAccountRecord.None -> {
                val seedKey = KEY_DEPOSIT_SEED + owner
                val accountKey = KEY_DEPOSIT_ACCOUNT + owner
                val written = depositPrefs.edit()
                    .putString(seedKey, phrase)
                    .putString(accountKey, accountId)
                    .commit()
                val readBack = depositAccountRecord(owner)
                val ok = written && readBack is DepositAccountRecord.Found &&
                    readBack.seedPhrase == phrase && readBack.accountId == accountId
                if (ok) {
                    Log.i(TAG, "Cuenta de depósito guardada: $accountId (wallet $owner)")
                } else {
                    // Un `commit()` fallido (disco lleno, error de E/S) deja las claves en el mapa
                    // EN MEMORIA de SharedPreferences, y la relectura lee memoria, no disco. Se
                    // descartan: esta cuenta no se le entregó a nadie ni recibió nada, y un
                    // reintento no debe encontrarla "guardada" cuando en disco no existe.
                    depositPrefs.edit().remove(seedKey).remove(accountKey).commit()
                    Log.e(TAG, "No se pudo guardar la cuenta de depósito de $owner")
                }
                ok
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo guardar la cuenta de depósito de $owner (${e.javaClass.simpleName})")
        false
    }

    /**
     * ¿Lo que este store tiene en memoria está también en DISCO? Un `commit()` sin cambios
     * devuelve `true` solo si no queda ninguna escritura pendiente (y si queda, la reintenta).
     * Se comprueba antes de usar una cuenta de depósito para RECIBIR dinero: una frase que solo
     * viviera en la memoria del proceso se perdería al morir este.
     */
    fun isDepositStoreDurable(): Boolean = try {
        depositPrefs.edit().commit()
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo confirmar en disco el archivo de cuentas de depósito (${e.javaClass.simpleName})")
        false
    }

    /**
     * Transacción en vuelo de la cuenta operativa `account` (texto de
     * `PendingDepositTx.encode()`), o `null`. No es un secreto: tipo, hash, monto y vigencia.
     */
    fun pendingDepositTx(account: String): String? = try {
        depositPrefs.getString(KEY_DEPOSIT_PENDING_TX + account, null)
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo leer la tx en vuelo de $account (${e.javaClass.simpleName})")
        null
    }

    /** Guarda la transacción en vuelo ANTES de enviarla. Síncrono; `false` si no se pudo escribir. */
    fun savePendingDepositTx(account: String, encoded: String): Boolean = try {
        val key = KEY_DEPOSIT_PENDING_TX + account
        val written = depositPrefs.edit().putString(key, encoded).commit()
        // Si no llegó a disco tampoco debe quedar en memoria: la transacción NO se va a enviar
        // y nadie debe verla "en vuelo".
        if (!written) depositPrefs.edit().remove(key).commit()
        written
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo guardar la tx en vuelo de $account (${e.javaClass.simpleName})")
        false
    }

    /** Borra la transacción en vuelo de `account` (ya tiene veredicto). */
    fun clearPendingDepositTx(account: String) {
        try {
            depositPrefs.edit().remove(KEY_DEPOSIT_PENDING_TX + account).commit()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo borrar la tx en vuelo de $account (${e.javaClass.simpleName})")
        }
    }

    /** Epoch (s) en que `owner` abrió su último depósito con el anchor, o `null`. */
    fun depositStartedAt(owner: String): Long? = try {
        depositPrefs.getLong(KEY_DEPOSIT_STARTED_AT + owner, 0L).takeIf { it > 0L }
    } catch (e: Exception) {
        null
    }

    fun markDepositStarted(owner: String, epochSec: Long) {
        try {
            depositPrefs.edit().putLong(KEY_DEPOSIT_STARTED_AT + owner, epochSec).apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo marcar el depósito iniciado (${e.javaClass.simpleName})")
        }
    }

    fun clearDepositStarted(owner: String) {
        try {
            depositPrefs.edit().remove(KEY_DEPOSIT_STARTED_AT + owner).apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo limpiar la marca de depósito iniciado (${e.javaClass.simpleName})")
        }
    }

    /** Última comprobación on-chain: ¿había USDC en la cuenta de depósito de `owner`? */
    fun depositInTransit(owner: String): Boolean = try {
        depositPrefs.getBoolean(KEY_DEPOSIT_IN_TRANSIT + owner, false)
    } catch (e: Exception) {
        false
    }

    fun setDepositInTransit(owner: String, inTransit: Boolean) {
        try {
            if (depositInTransit(owner) != inTransit) {
                depositPrefs.edit().putBoolean(KEY_DEPOSIT_IN_TRANSIT + owner, inTransit).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo guardar el estado del depósito en camino (${e.javaClass.simpleName})")
        }
    }

    // ── General ───────────────────────────────────────────────────────────

    /**
     * Borra TODA la wallet (seed + passkey) — usado al hacer logout.
     * La llamada a `.clear()` elimina todas las claves del store, incluida
     * [KEY_PREFERRED_ROLE], de modo que el rol preferido también se descarta.
     *
     * NO toca el archivo `raiz_deposit` (cuentas de depósito de las wallets passkey): cada
     * una sigue ligada a su smart account C… y se retoma al volver a entrar con la misma
     * passkey. Ver la sección "Cuenta de depósito".
     */
    fun clear() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Wallet borrada del dispositivo")
    }

    private companion object {
        const val FILE_NAME = "raiz_wallet"
        /** Cuentas de depósito de las wallets passkey. Excluido del backup igual que [FILE_NAME]. */
        const val DEPOSIT_FILE_NAME = "raiz_deposit"
        // Cuenta de depósito: cada clave lleva como sufijo el C… dueño (o la G… operativa).
        const val KEY_DEPOSIT_SEED = "deposit_seed:"
        const val KEY_DEPOSIT_ACCOUNT = "deposit_account:"
        const val KEY_DEPOSIT_PENDING_TX = "deposit_pending_tx:"
        const val KEY_DEPOSIT_STARTED_AT = "deposit_started_at:"
        const val KEY_DEPOSIT_IN_TRANSIT = "deposit_in_transit:"
        // Seed phrase (BIP-39)
        const val KEY_SEED = "seed_phrase"
        const val KEY_ACCOUNT_ID = "account_id"
        // Passkey / smart account
        const val KEY_PASSKEY_CREDENTIAL_ID = "passkey_credential_id"
        const val KEY_PASSKEY_CONTRACT_ID = "passkey_contract_id"
        // Rol preferido (no sensible — se limpia con .clear() en logout)
        const val KEY_PREFERRED_ROLE = "preferred_role"
        // Barrio elegido por el residente pendiente de verificación on-chain
        const val KEY_PENDING_RESIDENT_BARRIO = "pending_resident_barrio"
        const val TAG = "RAIZ"
    }
}
