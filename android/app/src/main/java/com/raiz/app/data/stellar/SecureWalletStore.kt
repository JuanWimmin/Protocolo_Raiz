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
 * NOTA seguridad: `xml/backup_rules.xml` excluye `raiz_wallet` del backup
 * en la nube — para que el secret no salga del dispositivo aunque el
 * usuario active Google Backup.
 */
@Singleton
class SecureWalletStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences by lazy { buildPrefs(context) }

    private fun buildPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
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

    // ── General ───────────────────────────────────────────────────────────

    /** Borra TODA la wallet (seed + passkey) — usado al hacer logout. */
    fun clear() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Wallet borrada del dispositivo")
    }

    private companion object {
        const val FILE_NAME = "raiz_wallet"
        // Seed phrase (BIP-39)
        const val KEY_SEED = "seed_phrase"
        const val KEY_ACCOUNT_ID = "account_id"
        // Passkey / smart account
        const val KEY_PASSKEY_CREDENTIAL_ID = "passkey_credential_id"
        const val KEY_PASSKEY_CONTRACT_ID = "passkey_contract_id"
        const val TAG = "RAIZ"
    }
}
