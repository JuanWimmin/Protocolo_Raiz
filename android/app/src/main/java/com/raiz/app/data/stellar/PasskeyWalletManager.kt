package com.raiz.app.data.stellar

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.raiz.app.BuildConfig
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletAuthMethod
import com.raiz.app.data.model.WalletState
import com.soneso.stellar.sdk.smartaccount.AndroidStorageAdapter
import com.soneso.stellar.sdk.smartaccount.AndroidWebAuthnProvider
import com.soneso.stellar.sdk.smartaccount.core.SubmissionMethod
import com.soneso.stellar.sdk.smartaccount.core.WebAuthnException
import com.soneso.stellar.sdk.smartaccount.core.CredentialException
import com.soneso.stellar.sdk.smartaccount.core.SmartAccountException
import com.soneso.stellar.sdk.smartaccount.oz.OZSmartAccountConfig
import com.soneso.stellar.sdk.smartaccount.oz.OZSmartAccountKit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crea y gestiona smart wallets secp256r1 (passkey WebAuthn) sobre la
 * infraestructura OpenZeppelin de Soneso en testnet.
 *
 * ## Restricción de contexto
 * [createSmartWallet] requiere un Activity como parámetro porque el Credential
 * Manager de Android necesita una Activity para presentar el selector de passkey
 * (CredentialManager.createCredential(activity, ...)). NO se puede usar
 * ApplicationContext aquí — AndroidWebAuthnProvider guarda el contexto y lo
 * pasa directamente al sistema operativo.
 *
 * ## Guard de versión
 * Passkey solo está disponible desde Android 9 (API 28). [isSupported] retorna
 * false en API 26-27 (minSdk RAÍZ) y la UI oculta el botón en esos casos.
 *
 * ## Infraestructura pública Soneso (testnet)
 * - Relayer: https://smart-account-relayer-proxy.soneso.workers.dev
 * - Indexer: https://smart-account-indexer.sdf-ecosystem.workers.dev
 * El relayer patrocina las fees de despliegue del smart account en testnet;
 * por eso NO necesitamos pasar autoFund=true ni nativeTokenContract.
 *
 * ## Pendiente para pago end-to-end
 * El flujo de pago actual (SorobanClient.payMerchant) asume un KeyPair G...
 * que firma la transacción clásicamente. Para pagar desde la smart wallet hay que:
 *   1. Reconectar la sesión: OZWalletOperations.connectWallet()
 *   2. Construir la transacción: OZTransactionOperations.contractCall()
 *   3. Firmar con passkey y submitir: OZTransactionOperations.executeAndSubmit()
 * El saldo USDC del C... se lee via SAC (no Horizon accounts) porque la cuenta
 * on-chain es un contrato, no una cuenta clásica.
 */
@Singleton
class PasskeyWalletManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val store: SecureWalletStore,
) {

    /** true si el dispositivo tiene soporte de passkeys (API >= 28). */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * ¿Está configurado el rpId en BuildConfig?
     * Si está vacío, el flujo passkey se oculta en la UI (no rompe nada).
     */
    val isConfigured: Boolean
        get() = BuildConfig.PASSKEY_RP_ID.isNotBlank()

    /** true si el dispositivo puede mostrar la opción passkey. */
    val isAvailable: Boolean
        get() = isSupported && isConfigured

    /**
     * Crea una nueva smart wallet usando WebAuthn secp256r1.
     *
     * @param activity  Activity activa — OBLIGATORIO para que el Credential Manager
     *                  del SO pueda presentar el diálogo de creación de passkey.
     * @param userName  Nombre visible para el usuario en el selector de passkey.
     *                  Por defecto "RAIZ Turista".
     * @return [RaizResult.Success] con el [WalletState] inicial (balances a cero);
     *         [RaizResult.Error] si el usuario cancela, el dispositivo no soporta
     *         passkeys, o el deploy del contrato falla.
     *
     * EFECTOS COLATERALES: persiste credentialId + contractId en [SecureWalletStore].
     * Si ya había una seed wallet guardada, esta sigue intacta — las dos pueden
     * coexistir aunque solo la seed tiene prioridad en [WalletManager.currentAccountId].
     */
    suspend fun createSmartWallet(
        activity: Activity,
        userName: String = "RAIZ Turista",
    ): RaizResult<WalletState> {
        if (!isSupported) {
            return RaizResult.Error(
                code = RaizErrorCode.UNKNOWN,
                message = "Passkey requiere Android 9 (API 28) o superior. " +
                          "Este dispositivo tiene API ${Build.VERSION.SDK_INT}.",
            )
        }
        if (!isConfigured) {
            return RaizResult.Error(
                code = RaizErrorCode.UNKNOWN,
                message = "passkey.rp.id no está configurado en local.properties.",
            )
        }

        return try {
            val kit = buildKit(activity)
            val result = kit.walletOperations.createWallet(
                userName = userName,
                autoSubmit = true,
                autoFund  = false,      // El relayer Soneso patrocina el XLM inicial;
                                        // autoFund=true requeriría pasar el SAC de XLM.
                nativeTokenContract = "",
                // submissionMethod usa el default del SDK = SubmissionMethod.RELAYER
            )

            store.savePasskeyWallet(
                credentialId = result.credentialId,
                contractId   = result.contractId,
            )
            Log.i(TAG, "Smart wallet creada: ${result.contractId}")

            RaizResult.Success(
                WalletState(
                    publicKey          = result.contractId,  // C... del smart account
                    usdcBalanceStroops = 0L,
                    xlmBalanceStroops  = 0L,
                    points             = 0L,
                    authMethod         = WalletAuthMethod.PASSKEY,
                ),
            )
        } catch (e: WebAuthnException.Cancelled) {
            Log.d(TAG, "Passkey: usuario canceló")
            RaizResult.Error(RaizErrorCode.UNKNOWN, "Operación cancelada por el usuario.")
        } catch (e: WebAuthnException.NotSupported) {
            Log.w(TAG, "Passkey no soportado: ${e.message}")
            RaizResult.Error(RaizErrorCode.UNKNOWN, "Este dispositivo no soporta passkeys.")
        } catch (e: WebAuthnException.RegistrationFailed) {
            Log.e(TAG, "Passkey registro falló: ${e.message}")
            RaizResult.Error(RaizErrorCode.UNKNOWN, "Error al registrar la passkey: ${e.message}")
        } catch (e: CredentialException.DeploymentFailed) {
            Log.e(TAG, "Deploy smart account falló: ${e.message}")
            RaizResult.Error(
                RaizErrorCode.NETWORK_ERROR,
                "No se pudo desplegar el contrato en Soroban: ${e.message}",
            )
        } catch (e: SmartAccountException) {
            Log.e(TAG, "SmartAccountException: ${e.message}")
            RaizResult.Error(RaizErrorCode.UNKNOWN, e.message ?: "Error inesperado del SDK.")
        } catch (e: Exception) {
            Log.e(TAG, "createSmartWallet falló", e)
            RaizResult.Error(RaizErrorCode.UNKNOWN, e.message ?: "Error desconocido.")
        }
    }

    // ── Construcción del kit ──────────────────────────────────────────────

    /**
     * Construye un [OZSmartAccountKit] fresco para cada operación.
     *
     * El kit NO se cachea porque [AndroidWebAuthnProvider] guarda el Activity
     * context con el que fue construido, y la Activity puede cambiar entre
     * llamadas (rotación de pantalla, recreación). Re-crearlo es barato; el
     * [AndroidStorageAdapter] usa un nombre de fichero fijo y es idempotente.
     */
    private fun buildKit(activity: Activity): OZSmartAccountKit {
        val webAuthnProvider = AndroidWebAuthnProvider(
            context = activity,          // Activity — requerido por Credential Manager
            rpId    = BuildConfig.PASSKEY_RP_ID,
            rpName  = BuildConfig.PASSKEY_RP_NAME,
            // timeout y authenticatorAttachment usan los defaults del SDK
            // (60 000 ms y "platform" respectivamente).
        )

        val storage = AndroidStorageAdapter(appContext)

        val config = OZSmartAccountConfig.Builder(
            rpcUrl                   = TESTNET_RPC_URL,
            networkPassphrase        = TESTNET_PASSPHRASE,
            accountWasmHash          = OZ_ACCOUNT_WASM_HASH,
            webauthnVerifierAddress  = OZ_WEBAUTHN_VERIFIER,
        )
            .rpId(BuildConfig.PASSKEY_RP_ID)
            .rpName(BuildConfig.PASSKEY_RP_NAME)
            .relayerUrl(OZ_RELAYER_URL)
            .indexerUrl(OZ_INDEXER_URL)
            .webauthnProvider(webAuthnProvider)
            .storage(storage)
            .build()

        return OZSmartAccountKit.create(config)
    }

    // ── Constantes de infraestructura ─────────────────────────────────────

    private companion object {
        const val TAG = "RAIZ"

        // Stellar testnet
        const val TESTNET_RPC_URL    = "https://soroban-testnet.stellar.org"
        const val TESTNET_PASSPHRASE = "Test SDF Network ; September 2015"

        // Infraestructura pública de Soneso para su Smart Account Kit (OpenZeppelin).
        // Fuente: https://github.com/Soneso/stellar-swift-wallet-sdk (DemoConfig.swift)
        // y confirmado en los tests de la demo de Soneso.
        //
        // accountWasmHash: hash del WASM del contrato de smart account OZ desplegado
        // en testnet. Si Soneso actualiza el contrato, este hash cambia.
        const val OZ_ACCOUNT_WASM_HASH       = "86b49fe03f7df0ad1c2a28bd8361b923ab57096e09f397f92f0c00ae3bd06d28"
        const val OZ_WEBAUTHN_VERIFIER       = "CB26VN37RCVNTHJZDEPK6IRO2MMTS3Z2IEO5JD5BINY2OOJ5KKJG7NKY"
        const val OZ_RELAYER_URL             = "https://smart-account-relayer-proxy.soneso.workers.dev"
        const val OZ_INDEXER_URL             = "https://smart-account-indexer.sdf-ecosystem.workers.dev"
    }
}
