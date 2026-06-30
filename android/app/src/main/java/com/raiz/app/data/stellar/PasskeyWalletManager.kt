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
import com.soneso.stellar.sdk.smartaccount.core.CredentialException
import com.soneso.stellar.sdk.smartaccount.core.IndexerException
import com.soneso.stellar.sdk.smartaccount.core.SmartAccountException
import com.soneso.stellar.sdk.smartaccount.core.SubmissionMethod
import com.soneso.stellar.sdk.smartaccount.core.WebAuthnException
import com.soneso.stellar.sdk.smartaccount.oz.ConnectWalletResult
import com.soneso.stellar.sdk.smartaccount.oz.OZSmartAccountConfig
import com.soneso.stellar.sdk.smartaccount.oz.OZSmartAccountKit
import com.soneso.stellar.sdk.smartaccount.oz.OZWalletOperations
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

    /**
     * Inicia sesión con una passkey existente (reconectar una smart wallet ya creada).
     *
     * ## Flujo de dos caminos
     *
     * ### Caso 1 — credencial guardada en [SecureWalletStore]
     * Si [SecureWalletStore.storedPasskeyCredentialId] tiene valor, se llama a
     * `OZWalletOperations.connectWallet` con esa credencial y el `contractId` C...
     * guardado. El OZKit intenta restaurar la sesión cacheada en el `AndroidStorageAdapter`
     * sin pedir nueva aserción WebAuthn (`fresh = false`). Si la sesión expiró,
     * `prompt = true` levanta el diálogo de passkey automáticamente y el usuario
     * verifica su identidad con biometría / PIN del dispositivo.
     *
     * ### Caso 2 — sin credencial guardada (dispositivo nuevo o tras logout)
     * `ConnectWalletOptions()` sin argumentos dispara el flujo **discoverable**:
     * el SDK pasa una `allowList` vacía al proveedor WebAuthn, lo que hace que el
     * SO muestre todas las passkeys disponibles del `rpId` (RAÍZ) para que el
     * usuario elija la suya desde Google Password Manager o el gestor del dispositivo.
     * Al autenticarse, el indexer de Soneso resuelve el `contractId` C... asociado
     * al `credentialId` devuelto y se persiste para sesiones futuras.
     *
     * ### Caso especial — Ambiguous
     * Si el indexer encontró más de un smart account para el mismo `credentialId`
     * (situación anómala en RAÍZ, pero posible si el usuario desplegó dos wallets),
     * se elige el primer candidato y se reconecta con él sin pedir nueva aserción.
     *
     * ## Limitación del flujo discoverable
     * El caso 2 requiere que el proveedor WebAuthn del SO (API 28+) soporte
     * credenciales "residentes" (passkeys sincronizadas). Si el dispositivo tiene
     * passkeys pero NO las sincroniza con Google Password Manager (p.ej. dispositivos
     * Vivo con gestor propio), el flujo discoverable puede no presentar la passkey
     * correcta. En ese caso el usuario debe usar el mismo dispositivo donde creó la
     * wallet (caso 1) o importar desde frase semilla.
     *
     * @param activity Activity activa — OBLIGATORIO para el Credential Manager del SO.
     * @return [RaizResult.Success] con el [WalletState] de la wallet reconectada
     *         (balances a cero; el ViewModel los actualiza vía Horizon/SAC);
     *         [RaizResult.Error] si el usuario cancela, la passkey no existe, o hay
     *         error de red.
     */
    suspend fun signInWithPasskey(activity: Activity): RaizResult<WalletState> {
        if (!isSupported) {
            return RaizResult.Error(
                code    = RaizErrorCode.UNKNOWN,
                message = "Passkey requiere Android 9 (API 28) o superior. " +
                          "Este dispositivo tiene API ${Build.VERSION.SDK_INT}.",
            )
        }
        if (!isConfigured) {
            return RaizResult.Error(
                code    = RaizErrorCode.UNKNOWN,
                message = "passkey.rp.id no está configurado en local.properties.",
            )
        }

        val storedCredentialId = store.storedPasskeyCredentialId()
        val storedContractId   = store.storedPasskeyContractId()
        val hasStoredCreds     = storedCredentialId != null && storedContractId != null

        return try {
            val kit = buildKit(activity)

            // Construir las opciones según el estado del dispositivo.
            val options = if (hasStoredCreds) {
                // Caso 1 — credencial conocida.
                // fresh=false: el OZKit intenta restaurar la sesión del AndroidStorageAdapter
                // sin pedir nueva aserción (experiencia fluida para el usuario).
                // prompt=true: si la sesión expiró, pide autenticación WebAuthn automáticamente.
                OZWalletOperations.ConnectWalletOptions(
                    credentialId = storedCredentialId!!,
                    contractId   = storedContractId!!,
                    fresh        = false,
                    prompt       = true,
                )
            } else {
                // Caso 2 — discoverable: sin credentialId ni contractId, el SDK
                // pasa una allowList vacía al Credential Manager, lo que hace que
                // el SO muestre TODAS las passkeys del rpId disponibles en el dispositivo
                // (Google Password Manager, Samsung Pass, etc.). El usuario elige la suya
                // y el indexer de Soneso resuelve el contractId C... asociado.
                OZWalletOperations.ConnectWalletOptions()
            }

            resolveConnectResult(
                kit           = kit,
                options       = options,
                hasStoredCreds = hasStoredCreds,
            )
        } catch (e: WebAuthnException.Cancelled) {
            Log.d(TAG, "Passkey signIn: usuario canceló")
            RaizResult.Error(RaizErrorCode.UNKNOWN, "Operación cancelada por el usuario.")
        } catch (e: WebAuthnException.NotSupported) {
            Log.w(TAG, "Passkey signIn: no soportada en este dispositivo: ${e.message}")
            RaizResult.Error(RaizErrorCode.UNKNOWN, "Este dispositivo no soporta passkeys.")
        } catch (e: WebAuthnException.AuthenticationFailed) {
            Log.e(TAG, "Passkey signIn: aserción WebAuthn fallida: ${e.message}")
            RaizResult.Error(RaizErrorCode.UNAUTHORIZED, "La verificación de la passkey falló.")
        } catch (e: CredentialException.NotFound) {
            // El indexer de Soneso no encontró un smart account para este credentialId.
            // Ocurre si la passkey pertenece a otro entorno (mainnet vs testnet) o si
            // el contrato fue borrado.
            Log.e(TAG, "Passkey signIn: smart account no encontrado: ${e.message}")
            RaizResult.Error(
                code    = RaizErrorCode.NOT_FOUND,
                message = "No se encontró una wallet RAÍZ para esta passkey. " +
                          "Asegúrate de usar el mismo dispositivo donde la creaste " +
                          "o importa tu wallet con la frase semilla.",
            )
        } catch (e: IndexerException) {
            // Cubre IndexerException.RequestFailed e IndexerException.Timeout.
            Log.e(TAG, "Passkey signIn: error del indexer Soneso: ${e.message}")
            RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "Error de red al recuperar la wallet.")
        } catch (e: SmartAccountException) {
            Log.e(TAG, "Passkey signIn: SmartAccountException: ${e.message}")
            RaizResult.Error(RaizErrorCode.UNKNOWN, e.message ?: "Error inesperado del SDK.")
        } catch (e: Exception) {
            Log.e(TAG, "signInWithPasskey falló de forma inesperada", e)
            RaizResult.Error(RaizErrorCode.UNKNOWN, e.message ?: "Error desconocido.")
        }
    }

    /**
     * Llama a [OZWalletOperations.connectWallet] con las [options] dadas y despacha
     * el resultado sellado [ConnectWalletResult].
     *
     * El caso [ConnectWalletResult.Ambiguous] (>1 contratos para el mismo credentialId)
     * se resuelve automáticamente eligiendo el primer candidato y reconectando sin
     * una nueva aserción WebAuthn (el usuario ya autenticó en la primera llamada).
     *
     * @param kit            Kit OZ listo con el Activity actual.
     * @param options        Opciones con credentialId/contractId (o defaults para discoverable).
     * @param hasStoredCreds true si el dispositivo tenía una credencial guardada previamente.
     *                       Controla si se persiste el resultado en [SecureWalletStore].
     */
    private suspend fun resolveConnectResult(
        kit: OZSmartAccountKit,
        options: OZWalletOperations.ConnectWalletOptions,
        hasStoredCreds: Boolean,
    ): RaizResult<WalletState> {
        return when (val result = kit.walletOperations.connectWallet(options)) {

            is ConnectWalletResult.Connected -> {
                // Si vino de discoverable (caso 2) o resolvimos una ambigüedad, persistir.
                if (!hasStoredCreds) {
                    store.savePasskeyWallet(
                        credentialId = result.credentialId,
                        contractId   = result.contractId,
                    )
                    Log.i(TAG, "Passkey discoverable: smart wallet guardada: ${result.contractId}")
                }
                Log.i(
                    TAG,
                    "Passkey reconectada: ${result.contractId} " +
                    "(sesión restaurada sin nueva aserción: ${result.restoredFromSession})",
                )
                successState(result.contractId)
            }

            is ConnectWalletResult.Ambiguous -> {
                // El indexer Soneso encontró más de un smart account para el credentialId.
                // En RAÍZ un usuario debería tener exactamente uno; tomamos el primero.
                // No es necesaria una nueva aserción WebAuthn porque el usuario ya autenticó
                // en la primera llamada; solo re-conectamos apuntando al contrato elegido.
                val primaryContract = result.candidates.firstOrNull()
                    ?: return RaizResult.Error(
                        code    = RaizErrorCode.NOT_FOUND,
                        message = "Se detectaron múltiples wallets passkey pero la lista de " +
                                  "candidatos llegó vacía. Contacta con soporte RAÍZ.",
                    )

                Log.w(
                    TAG,
                    "Passkey ambigua: ${result.candidates.size} contratos para credencial " +
                    "${result.credentialId}. Reconectando con el primero: $primaryContract",
                )

                // Reconexión dirigida: fresh=false, prompt=false (ya autenticado).
                val retry = kit.walletOperations.connectWallet(
                    OZWalletOperations.ConnectWalletOptions(
                        credentialId = result.credentialId,
                        contractId   = primaryContract,
                        fresh        = false,
                        prompt       = false,
                    ),
                )

                if (retry is ConnectWalletResult.Connected) {
                    // Persistir siempre al resolver una ambigüedad, para que la próxima
                    // vez vaya por el caso 1 (reconexión directa, más rápida).
                    store.savePasskeyWallet(
                        credentialId = retry.credentialId,
                        contractId   = retry.contractId,
                    )
                    Log.i(TAG, "Passkey ambigua resuelta: ${retry.contractId}")
                    successState(retry.contractId)
                } else {
                    // Otro Ambiguous tras el reintento — situación anómala del indexer.
                    Log.e(TAG, "Reintento post-Ambiguous devolvió otro Ambiguous — indexer inconsistente")
                    RaizResult.Error(
                        code    = RaizErrorCode.UNKNOWN,
                        message = "No se pudo seleccionar un smart account único para esta passkey.",
                    )
                }
            }

            else -> {
                // Otras variantes del sealed ConnectWalletResult (p.ej. cancelado / no
                // encontrado en versiones futuras del SDK). No deberían darse en el flujo
                // normal; las tratamos como error legible para no romper exhaustividad.
                Log.w(TAG, "connectWallet devolvió un resultado no manejado: $result")
                RaizResult.Error(
                    code    = RaizErrorCode.UNKNOWN,
                    message = "No se pudo iniciar sesión con la passkey. Intenta de nuevo o usa tu frase semilla.",
                )
            }
        }
    }

    /**
     * Construye un [WalletState] de retorno con el `contractId` C... del smart account.
     * Los balances (USDC, XLM) y puntos se rellenan a cero — el ViewModel los actualiza
     * en segundo plano vía Horizon SSE y el contrato Rewards.
     */
    private fun successState(contractId: String): RaizResult<WalletState> =
        RaizResult.Success(
            WalletState(
                publicKey          = contractId,   // C... del smart account (no un G...)
                usdcBalanceStroops = 0L,
                xlmBalanceStroops  = 0L,
                points             = 0L,
                authMethod         = WalletAuthMethod.PASSKEY,
            ),
        )

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
