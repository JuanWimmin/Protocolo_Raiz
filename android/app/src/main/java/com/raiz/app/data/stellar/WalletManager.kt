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
 * Orden de resolución de "qué wallet usar":
 *   1. La guardada en SecureWalletStore (crear/importar desde WelcomeScreen).
 *   2. La demo del local.properties (BuildConfig.DEMO_TOURIST_SECRET).
 *   3. Placeholder (no firmable, solo lectura).
 *
 * Métodos públicos clave:
 *   - hasUsableWallet() — sync — ¿la app puede ofrecer la home directo?
 *   - currentAccountId() — sync — public key del wallet activo (null si no hay).
 *   - currentKeyPair() — suspend — KeyPair firmable (null si no hay).
 *   - currentWallet() — sync — WalletState placeholder con publicKey real.
 *
 * Los métodos suspend solo se llaman para firmar; los sync para inicializar
 * UI y observar balances vía Horizon.
 */
@Singleton
class WalletManager @Inject constructor(
    private val store: SecureWalletStore,
) {

    /** ¿Hay una wallet usable (guardada o demo)? */
    fun hasUsableWallet(): Boolean =
        store.hasStoredWallet() || BuildConfig.DEMO_TOURIST_SECRET.isNotBlank()

    /**
     * Public key activo en este momento (sync).
     * Prioridad: guardada > demo > null.
     */
    fun currentAccountId(): String? {
        store.storedAccountId()?.let { return it }
        if (BuildConfig.DEMO_TOURIST_SECRET.isNotBlank()) return DEMO_PUBLIC
        return null
    }

    /**
     * Estado base del wallet activo. Los balances/puntos los llena el
     * ViewModel via Horizon + Rewards; aquí solo nos importa el publicKey.
     */
    fun currentWallet(): WalletState = WalletState(
        publicKey = currentAccountId() ?: PLACEHOLDER_ACCOUNT,
        usdcBalanceStroops = 0L,
        xlmBalanceStroops = 100_000_000_000L,
        points = 0L,
        authMethod = if (store.hasStoredWallet()) WalletAuthMethod.SEED_PHRASE
                     else WalletAuthMethod.SEED_PHRASE,
    )

    /**
     * KeyPair firmable. Suspend porque deriva criptografía.
     * Prioridad: guardada > demo > null.
     */
    suspend fun currentKeyPair(): KeyPair? {
        // 1. Wallet guardada
        store.storedSeedPhrase()?.let { phrase ->
            return runCatching {
                Mnemonic.from(phrase).use { it.getKeyPair(0) }
            }.getOrNull()
        }
        // 2. Demo de BuildConfig
        return demoKeyPair()
    }

    /**
     * Compat: lo usaban WalletViewModel/ProfileViewModel/RewardsViewModel
     * para obtener el publicKey rápidamente. Ahora delega a currentWallet().
     */
    fun mockWallet(): WalletState = currentWallet()

    // ── Crear / importar / borrar ─────────────────────────────────────────

    /** Genera una nueva seed phrase de 12 palabras BIP-39. */
    suspend fun generateSeedPhrase(): RaizResult<List<String>> = runCatching {
        Mnemonic.generate12WordsMnemonic().split(" ")
    }.fold(
        onSuccess = { RaizResult.Success(it) },
        onFailure = { RaizResult.Error(RaizErrorCode.UNKNOWN, it.message ?: "generate failed") },
    )

    /**
     * Crea una wallet desde una seed phrase y la persiste en el dispositivo.
     * La phrase puede venir de generateSeedPhrase() (crear) o del usuario
     * tipeando 12 palabras (importar).
     */
    suspend fun saveWallet(words: List<String>): RaizResult<WalletState> {
        val phrase = words.joinToString(" ").trim()
        if (phrase.isEmpty()) {
            return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "seed phrase vacía")
        }
        return runCatching {
            val accountId = Mnemonic.from(phrase).use { it.getKeyPair(0) }.getAccountId()
            store.save(phrase, accountId)
            WalletState(
                publicKey = accountId,
                usdcBalanceStroops = 0L,
                xlmBalanceStroops = 0L,
                points = 0L,
                authMethod = WalletAuthMethod.SEED_PHRASE,
            )
        }.fold(
            onSuccess = { RaizResult.Success(it) },
            onFailure = {
                RaizResult.Error(RaizErrorCode.PARSE_ERROR, "seed inválida: ${it.message}")
            },
        )
    }

    /** Borra la wallet guardada — efecto: la app vuelve a Welcome. */
    fun logout() {
        store.clear()
        cachedDemoKp = null
    }

    // ── Passkey: pendiente ────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    suspend fun createWithPasskey(): RaizResult<WalletState> {
        return RaizResult.Error(
            code = RaizErrorCode.UNKNOWN,
            message = "Passkey wallet: pendiente Credentials API",
        )
    }

    // ── Demo keypair (raiz-tourist del seed) ──────────────────────────────

    suspend fun demoKeyPair(): KeyPair? {
        cachedDemoKp?.let { return it }
        val secret = BuildConfig.DEMO_TOURIST_SECRET
        if (secret.isBlank()) return null
        return runCatching { KeyPair.fromSecretSeed(secret) }
            .onSuccess { cachedDemoKp = it }
            .getOrNull()
    }

    /** KeyPair de residente del Centro Histórico (para voto demo). */
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
        /** G... de raiz-tourist del seed; coincide con DEMO_TOURIST_SECRET. */
        const val DEMO_PUBLIC = "GDLGYDO4XY6YC6TNSPZELYEP73QOL4SUOVPUMJPHYC7WTTRQNORQIZM7"
        /** Placeholder si no hay wallet alguna — fuerza al Welcome. */
        const val PLACEHOLDER_ACCOUNT = "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
    }
}
