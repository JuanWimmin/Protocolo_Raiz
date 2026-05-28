package com.raiz.app.data.stellar

import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletAuthMethod
import com.raiz.app.data.model.WalletState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custodia de claves de la wallet del turista.
 *
 * Dos métodos:
 *   - PASSKEY (preferido): usa androidx.credentials + WebAuthn / FIDO2.
 *     La clave privada nunca sale del TEE. Pendiente de implementar.
 *   - SEED_PHRASE (fallback): BIP-39 de 12/24 palabras. La derivación a
 *     Stellar keypair la hace el SDK de Soneso.
 *
 * La seed phrase nunca debe loggearse, persistirse en SharedPreferences sin
 * cifrar, ni hacerle backup en la nube (ver xml/backup_rules.xml).
 *
 * Por ahora ambos métodos están en stub: confirman cableado de DI y firma de
 * API, pero no generan keys reales todavía. La implementación real cabe en
 * un siguiente paso pequeño.
 */
@Singleton
class WalletManager @Inject constructor(
    // En la implementación real: inyectar un KeyStoreManager para guardar
    // seeds cifradas y un PasskeyClient para androidx.credentials.
) {

    @Suppress("UNUSED_PARAMETER")
    suspend fun createWithPasskey(): RaizResult<WalletState> {
        // TODO: integrar androidx.credentials.CredentialManager
        //       + KeyPair.random() del SDK Stellar
        //       + smart contract account (passkey-bound).
        return RaizResult.Error(
            code = RaizErrorCode.UNKNOWN,
            message = "WalletManager.createWithPasskey: TODO — pendiente Credentials API",
        )
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun createWithSeedPhrase(words: List<String>): RaizResult<WalletState> {
        // TODO: usar Wallet.fromMnemonic(words.joinToString(" ")) del SDK
        //       (com.soneso.stellar.sdk.Wallet) y derivar el keypair.
        return RaizResult.Error(
            code = RaizErrorCode.UNKNOWN,
            message = "WalletManager.createWithSeedPhrase: TODO — pendiente derivación BIP-39",
        )
    }

    /** Genera una nueva seed phrase de 12 palabras. Solo para flujo de creación. */
    suspend fun generateSeedPhrase(): RaizResult<List<String>> {
        // TODO: usar Wallet.generate12Words() del SDK
        return RaizResult.Error(
            code = RaizErrorCode.UNKNOWN,
            message = "WalletManager.generateSeedPhrase: TODO",
        )
    }

    /** Solo para tests/UI: devuelve un WalletState mock para validar cableado. */
    fun mockWallet(): WalletState = WalletState(
        publicKey = "GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P",
        usdcBalanceStroops = 50_000_000L,        // 5 USDC
        xlmBalanceStroops = 100_000_000_000L,    // 10000 XLM (testnet friendbot)
        points = 320,
        authMethod = WalletAuthMethod.SEED_PHRASE,
    )
}
