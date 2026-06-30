package com.raiz.app.data.stellar

import com.raiz.app.BuildConfig
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.UserRole
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

    /**
     * ¿Hay una wallet REAL guardada (seed BIP-39 o passkey)?
     *
     * El modo demo NO cuenta a propósito: un usuario nuevo debe ver el
     * onboarding completo (Welcome → crear/importar → elegir rol) y entrar
     * con las pantallas de su rol. El demo es OPT-IN vía el botón "Probar
     * modo demo" del Welcome (que entra como turista en sesión, sin persistir).
     *
     * Antes esto incluía `DEMO_TOURIST_SECRET.isNotBlank()`, lo que hacía que
     * la app SIEMPRE arrancara en la home demo saltándose el onboarding.
     */
    fun hasUsableWallet(): Boolean =
        store.hasStoredWallet() ||
        store.hasStoredPasskeyWallet()

    /**
     * Public key / address activo en este momento (sync).
     * Prioridad: seed guardada > passkey guardada (C...) > demo > null.
     *
     * NOTA: Para wallets passkey este método retorna una dirección C...
     * (contrato Soroban), no un G... clásico. Los callers que asuman G...
     * deben verificar [WalletAuthMethod] antes de usarlo.
     */
    fun currentAccountId(): String? {
        store.storedAccountId()?.let { return it }
        store.storedPasskeyContractId()?.let { return it }
        if (BuildConfig.DEMO_TOURIST_SECRET.isNotBlank()) return DEMO_PUBLIC
        return null
    }

    /** true si la wallet activa es un smart account (passkey). */
    fun isPasskeyWallet(): Boolean =
        !store.hasStoredWallet() && store.hasStoredPasskeyWallet()

    /**
     * Estado base del wallet activo. Los balances/puntos los llena el
     * ViewModel via Horizon + Rewards; aquí solo nos importa el publicKey.
     */
    fun currentWallet(): WalletState = WalletState(
        publicKey = currentAccountId() ?: PLACEHOLDER_ACCOUNT,
        usdcBalanceStroops = 0L,
        xlmBalanceStroops = 100_000_000_000L,
        points = 0L,
        authMethod = when {
            store.hasStoredPasskeyWallet() && !store.hasStoredWallet() -> WalletAuthMethod.PASSKEY
            else -> WalletAuthMethod.SEED_PHRASE
        },
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

    /** Borra TODA la wallet guardada (seed + passkey) — efecto: la app vuelve a Welcome. */
    fun logout() {
        store.clear()
        cachedDemoKp = null
    }

    // ── Rol preferido del usuario ─────────────────────────────────────────

    /**
     * Persiste el rol elegido por el usuario (TOURIST / MERCHANT / RESIDENT).
     * La UI llama esto después de que el usuario pulsa "Soy turista", "Soy
     * comerciante" o "Soy residente" en el onboarding o en el perfil.
     * Delega en [SecureWalletStore.savePreferredRole].
     */
    fun setPreferredRole(role: UserRole) {
        store.savePreferredRole(role.name)
    }

    /**
     * Rol guardado, o null si el usuario no lo ha elegido todavía o se
     * hizo logout. Parsing null-safe: si el nombre guardado no coincide
     * con ningún enum (ej. migración de valores viejos), retorna null en
     * lugar de lanzar [IllegalArgumentException].
     */
    fun preferredRole(): UserRole? {
        val name = store.preferredRole() ?: return null
        return runCatching { UserRole.valueOf(name) }.getOrNull()
    }

    // ── Barrio del residente pendiente de verificación on-chain ────────────

    /**
     * Persiste el barrio (hex) que el usuario eligió al entrar como residente
     * en el onboarding. Se lee luego en ProposalsScreen para mintear el
     * ResidentToken al barrio correcto cuando pulsa "Verificar como residente".
     * Delega en [SecureWalletStore.savePendingResidentBarrio].
     */
    fun savePendingResidentBarrio(hex: String) = store.savePendingResidentBarrio(hex)

    /** Barrio (hex) elegido por el residente pendiente, o null si no eligió. */
    fun pendingResidentBarrio(): String? = store.pendingResidentBarrio()

    /**
     * true si no hay ninguna wallet real guardada en el dispositivo (ni
     * seed BIP-39 ni passkey) Y existe un DEMO_TOURIST_SECRET configurado
     * en local.properties. Indica que el usuario entró por el atajo "demo"
     * en el Welcome sin registrarse.
     *
     * La UI puede usar este flag para mostrar un banner persistente
     * "Modo demo — crea tu wallet para guardar tus puntos".
     */
    val isDemoMode: Boolean
        get() = !store.hasStoredWallet() &&
                !store.hasStoredPasskeyWallet() &&
                BuildConfig.DEMO_TOURIST_SECRET.isNotBlank()

    // ── Passkey ───────────────────────────────────────────────────────────
    //
    // La creación de la smart wallet passkey vive en PasskeyWalletManager
    // (com.raiz.app.data.stellar.PasskeyWalletManager). Requiere un Activity
    // como contexto porque el Credential Manager de Android necesita una
    // Activity para mostrar el selector de passkey.
    //
    // WalletManager conserva el método stub por compatibilidad con el
    // contrato documentado; las pantallas de UI usan PasskeyWalletManager
    // directamente.
    //
    // TODO para pago end-to-end via passkey:
    //   1. Conectar la smart wallet existente: OZWalletOperations.connectWallet()
    //   2. Firmar transacciones: OZTransactionOperations.executeAndSubmit() /
    //      contractCall() — en lugar del KeyPair.sign() actual de SorobanClient.
    //   3. El saldo USDC del C... se lee via SAC (Stellar Asset Contract) en lugar
    //      de la Horizon accounts API que usa HorizonStream (que asume G...).

    @Suppress("UNUSED_PARAMETER")
    suspend fun createWithPasskey(): RaizResult<WalletState> {
        // Esta función se mantiene por interfaz; la UI debe usar PasskeyWalletManager.
        return RaizResult.Error(
            code = RaizErrorCode.UNKNOWN,
            message = "Usa PasskeyWalletManager.createSmartWallet(activity) desde la UI.",
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

    /**
     * KeyPair del admin del protocolo. Usado SOLO en modo demo para que la
     * app pueda firmar register_merchant cuando un usuario solicita ser
     * comerciante. En producción esto no existiría — el registro pasaría
     * por una pantalla del admin del barrio o KYC SEP-12.
     */
    suspend fun demoAdminKeyPair(): KeyPair? {
        cachedAdminKp?.let { return it }
        val secret = BuildConfig.DEMO_ADMIN_SECRET
        if (secret.isBlank()) return null
        return runCatching { KeyPair.fromSecretSeed(secret) }
            .onSuccess { cachedAdminKp = it }
            .getOrNull()
    }

    private var cachedDemoKp: KeyPair? = null
    private var cachedResidentKp: KeyPair? = null
    private var cachedAdminKp: KeyPair? = null

    private companion object {
        /** G... de raiz-tourist del seed; coincide con DEMO_TOURIST_SECRET. */
        const val DEMO_PUBLIC = "GDLGYDO4XY6YC6TNSPZELYEP73QOL4SUOVPUMJPHYC7WTTRQNORQIZM7"
        /** Placeholder si no hay wallet alguna — fuerza al Welcome. */
        const val PLACEHOLDER_ACCOUNT = "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
    }
}
