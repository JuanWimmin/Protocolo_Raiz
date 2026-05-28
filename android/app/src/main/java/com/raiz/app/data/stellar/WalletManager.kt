package com.raiz.app.data.stellar

import com.raiz.app.BuildConfig
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletAuthMethod
import com.raiz.app.data.model.WalletState
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.sep.sep05.Mnemonic
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custodia de claves de la wallet del turista.
 *
 * Dos métodos:
 *   - PASSKEY (preferido): usa androidx.credentials + WebAuthn / FIDO2.
 *     La clave privada nunca sale del TEE. Pendiente de implementar.
 *   - SEED_PHRASE (fallback): BIP-39 de 12 palabras, derivación SEP-05
 *     (m/44'/148'/0'). El SDK de Soneso hace toda la criptografía.
 *
 * La seed phrase nunca debe loggearse, persistirse en SharedPreferences sin
 * cifrar, ni hacerle backup en la nube (ver xml/backup_rules.xml).
 *
 * Balances (USDC/XLM/points) se llenan en 0 cuando se crea la wallet — los
 * pulea el WalletViewModel suscribiéndose a Horizon SSE + Rewards.get_points.
 */
@Singleton
class WalletManager @Inject constructor(
    // Cuando metamos persistencia segura, inyectar aquí un EncryptedSeedStore
    // basado en androidx.security.crypto.EncryptedSharedPreferences.
) {

    /** Genera una nueva seed phrase de 12 palabras (SEP-05 / BIP-39). */
    suspend fun generateSeedPhrase(): RaizResult<List<String>> = runCatching {
        Mnemonic.generate12WordsMnemonic().split(" ")
    }.fold(
        onSuccess = { RaizResult.Success(it) },
        onFailure = { RaizResult.Error(RaizErrorCode.UNKNOWN, it.message ?: "generate failed") },
    )

    /**
     * Crea una wallet desde una seed phrase existente.
     * Deriva la cuenta en path m/44'/148'/0' y devuelve un WalletState con
     * balances en cero (los actualiza después WalletViewModel vía streams).
     */
    suspend fun createWithSeedPhrase(words: List<String>): RaizResult<WalletState> {
        val phrase = words.joinToString(" ").trim()
        if (phrase.isEmpty()) {
            return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "seed phrase vacía")
        }
        return runCatching {
            val mnemonic = Mnemonic.from(phrase)
            try {
                val keypair = mnemonic.getKeyPair(index = 0)
                WalletState(
                    publicKey = keypair.getAccountId(),
                    usdcBalanceStroops = 0L,
                    xlmBalanceStroops = 0L,
                    points = 0L,
                    authMethod = WalletAuthMethod.SEED_PHRASE,
                )
            } finally {
                // Zero-out de la seed interna en memoria — importante.
                mnemonic.close()
            }
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = {
                RaizResult.Error(RaizErrorCode.PARSE_ERROR, "seed inválida: ${it.message}")
            },
        )
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun createWithPasskey(): RaizResult<WalletState> {
        // TODO: integrar androidx.credentials.CredentialManager
        //       + smart contract account (passkey-bound).
        return RaizResult.Error(
            code = RaizErrorCode.UNKNOWN,
            message = "WalletManager.createWithPasskey: TODO — pendiente Credentials API",
        )
    }

    /**
     * Solo para tests/UI: devuelve un WalletState mock que apunta a la cuenta
     * `raiz-tourist` del seed (ver `scripts/seed_testnet.sh`). Esa cuenta sí
     * tiene saldo USDC real on-chain, así que con HorizonStream conectado se
     * ve el balance real en vivo.
     *
     * NOTA: `publicKey` se hardcodea en lugar de derivarlo del demoKeyPair()
     * porque ese es suspend (criptografía); sería incómodo en un getter sync.
     * Si rotamos el secret del demo, actualizar también `DEMO_PUBLIC` abajo.
     */
    fun mockWallet(): WalletState = WalletState(
        publicKey = DEMO_PUBLIC,
        usdcBalanceStroops = 0L,                 // se actualiza vía HorizonStream
        xlmBalanceStroops = 100_000_000_000L,    // friendbot inicial
        points = 320,
        authMethod = WalletAuthMethod.SEED_PHRASE,
    )

    /**
     * KeyPair firmable de la cuenta demo. Vuelve null si
     * BuildConfig.DEMO_TOURIST_SECRET está vacío (típico cuando alguien clona
     * el repo y no tiene su `local.properties` configurado). En ese caso,
     * la app no puede firmar pagos pero sí leer.
     *
     * WARNING: Esta key sale de `local.properties` que NO está en git, pero
     * SÍ queda en el APK debug. No es para producción.
     */
    suspend fun demoKeyPair(): KeyPair? {
        cachedDemoKp?.let { return it }
        val secret = BuildConfig.DEMO_TOURIST_SECRET
        if (secret.isBlank()) return null
        return runCatching { KeyPair.fromSecretSeed(secret) }
            .onSuccess { cachedDemoKp = it }
            .getOrNull()
    }

    /**
     * KeyPair de un residente del Centro Histórico, también desde
     * local.properties. Solo se usa cuando el modo demo override es
     * "ver como residente" y el usuario quiere disparar un voto real.
     * Vuelve null si el secret está vacío.
     */
    suspend fun demoResidentKeyPair(): KeyPair? {
        cachedResidentKp?.let { return it }
        val secret = BuildConfig.DEMO_RESIDENT_SECRET
        if (secret.isBlank()) return null
        return runCatching { KeyPair.fromSecretSeed(secret) }
            .onSuccess { cachedResidentKp = it }
            .getOrNull()
    }

    private var cachedDemoKp: KeyPair? = null
    private var cachedResidentKp: KeyPair? = null

    private companion object {
        // G... de `stellar keys address raiz-tourist`. Si rotamos el secret
        // en local.properties, regenerar este string con el comando.
        const val DEMO_PUBLIC = "GDLGYDO4XY6YC6TNSPZELYEP73QOL4SUOVPUMJPHYC7WTTRQNORQIZM7"
    }
}
