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

    /** ¿Hay una wallet guardada en este dispositivo? */
    fun hasStoredWallet(): Boolean = prefs.contains(KEY_SEED) && prefs.contains(KEY_ACCOUNT_ID)

    /** Public key (G...) de la wallet guardada, o null si no hay. */
    fun storedAccountId(): String? = prefs.getString(KEY_ACCOUNT_ID, null)

    /** Las 12 palabras BIP-39 de la wallet guardada, o null si no hay. */
    fun storedSeedPhrase(): String? = prefs.getString(KEY_SEED, null)

    /** Guarda la wallet (sobrescribe la anterior si existía). */
    fun save(seedPhrase: String, accountId: String) {
        prefs.edit()
            .putString(KEY_SEED, seedPhrase.trim())
            .putString(KEY_ACCOUNT_ID, accountId)
            .apply()
        Log.i(TAG, "Wallet guardada: $accountId")
    }

    /** Borra la wallet — usado al hacer logout. */
    fun clear() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Wallet borrada del dispositivo")
    }

    private companion object {
        const val FILE_NAME = "raiz_wallet"
        const val KEY_SEED = "seed_phrase"
        const val KEY_ACCOUNT_ID = "account_id"
        const val TAG = "RAIZ"
    }
}
