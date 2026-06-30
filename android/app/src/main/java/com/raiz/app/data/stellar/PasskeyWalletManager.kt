package com.raiz.app.data.stellar

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.raiz.app.BuildConfig
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletAuthMethod
import com.raiz.app.data.model.WalletState
import com.soneso.stellar.sdk.Address
import com.soneso.stellar.sdk.scval.Scv
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
    private val deploymentsLoader: DeploymentsLoader,
) {

    // Lazy porque DeploymentsLoader lee assets — no queremos I/O en el hilo de inyección.
    private val deployments by lazy { deploymentsLoader.load() }

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

    // ── Transacciones firmadas con passkey ────────────────────────────────
    //
    // FLUJO GENERAL DE CADA MÉTODO:
    //   1. Verificar que hay credenciales guardadas.
    //   2. buildKit(activity) — kit fresco con el Activity actual.
    //   3. connectWallet(fresh=false, prompt=true) — restaura sesión del
    //      AndroidStorageAdapter; si la sesión local expiró, muestra WebAuthn
    //      (excepcional). No genera fee on-chain.
    //   4. transactionOperations.contractCall(...) — simula la tx, recoge el
    //      árbol de auth entries del servidor RPC y firma TODAS las entradas
    //      que pertenecen al smart account (C...) con UNA SOLA ceremonia
    //      WebAuthn (passkey). Incluye sub-invocaciones cruzadas (e.g. la
    //      transferencia USDC interna en pay_merchant). Luego envía via relayer.
    //   5. TransactionResult.success/error → RaizResult.
    //
    // AUTH ANIDADA (pay_merchant):
    //   Pool.pay_merchant llama internamente a SAC.transfer(from=tourist=C…, …)
    //   que genera auth entries adicionales para el smart account. La simulación
    //   devuelve el árbol completo; contractCall firma todas las entradas del C…
    //   en la misma ceremonia passkey → UNA firma, múltiples auth entries.
    //
    // LIMITACIÓN — valor de retorno:
    //   contractCall() devuelve TransactionResult(success, hash, ledger, error)
    //   pero NO expone el valor de retorno de la función Soroban (solo hay acceso
    //   a eso via simulateAndExtractResult, que es @InternalStellarSdkApi).
    //   Por eso createProposalWithPasskey devuelve RaizResult<Unit> en lugar de
    //   RaizResult<Long>; la UI debe consultar listActiveProposals() para obtener
    //   el id de la propuesta recién creada.

    /**
     * Paga al comercio desde el smart account del turista, firmado con passkey.
     *
     * Invoca [Pool.pay_merchant(tourist=C…, merchant, amount, tip_bps)] donde
     * `tourist` es la dirección C… del smart account. El contrato Pool requiere
     * que el tourist autorice tanto la llamada top-level como la transferencia
     * USDC interna (SAC.transfer). Ambas auth entries se firman en una sola
     * ceremonia WebAuthn (passkey).
     *
     * @param activity        Activity activa — OBLIGATORIO para el Credential Manager.
     * @param merchantAddress Dirección G… del comercio (registrado en Pool).
     * @param amountStroops   Importe total en stroops (7 decimales). 1 USDC = 10_000_000.
     * @param tipBps          Tip Barrio en basis points. 200 = 2%. Validado en el contrato.
     */
    suspend fun payMerchantWithPasskey(
        activity: Activity,
        merchantAddress: String,
        amountStroops: Long,
        tipBps: Int,
    ): RaizResult<Unit> {
        if (!isAvailable) return passkeyNotAvailableError()

        val credId      = store.storedPasskeyCredentialId() ?: return noWalletError()
        val contractId  = store.storedPasskeyContractId()   ?: return noWalletError()

        return try {
            val kit = buildKit(activity)

            // Restaurar la sesión. fresh=false usa la clave de sesión cacheada;
            // prompt=true la renueva via WebAuthn solo si expiró (poco frecuente).
            kit.walletOperations.connectWallet(
                OZWalletOperations.ConnectWalletOptions(
                    credentialId = credId,
                    contractId   = contractId,
                    fresh        = false,
                    prompt       = true,
                ),
            )

            // Parámetros en el orden exacto que espera Pool.pay_merchant:
            //   tourist: Address, merchant: Address, amount: i128, tip_bps: u32
            val params = listOf(
                Address(contractId).toSCVal(),                    // tourist  (C…)
                Address(merchantAddress).toSCVal(),               // merchant (G…)
                Scv.toInt128(BigInteger.fromLong(amountStroops)), // amount   stroops
                Scv.toUint32(tipBps.toUInt()),                    // tip_bps  basis points
            )

            val result = kit.transactionOperations.contractCall(
                target    = deployments.pool,
                targetFn  = "pay_merchant",
                targetArgs = params,
            )

            if (result.success) {
                Log.i(TAG, "payMerchantWithPasskey OK hash=${result.hash}")
                RaizResult.Success(Unit)
            } else {
                Log.e(TAG, "payMerchantWithPasskey FAIL: ${result.error}")
                val code = when {
                    result.error?.contains("InsufficientBalance") == true ||
                        result.error?.contains("Error(Contract, #7)") == true ->
                        RaizErrorCode.INSUFFICIENT_BALANCE
                    result.error?.contains("MerchantNotFound") == true ||
                        result.error?.contains("Error(Contract, #4)") == true ->
                        RaizErrorCode.NOT_FOUND
                    else -> RaizErrorCode.NETWORK_ERROR
                }
                RaizResult.Error(code, result.error ?: "pay_merchant falló")
            }
        } catch (e: WebAuthnException.Cancelled) {
            RaizResult.Error(RaizErrorCode.UNKNOWN, "Operación cancelada por el usuario.")
        } catch (e: WebAuthnException) {
            Log.e(TAG, "payMerchantWithPasskey WebAuthn: ${e.message}")
            RaizResult.Error(RaizErrorCode.UNAUTHORIZED, "Verificación passkey falló: ${e.message}")
        } catch (e: SmartAccountException) {
            Log.e(TAG, "payMerchantWithPasskey SmartAccount: ${e.message}")
            RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "Error del smart account.")
        } catch (e: Exception) {
            Log.e(TAG, "payMerchantWithPasskey inesperado", e)
            RaizResult.Error(RaizErrorCode.UNKNOWN, e.message ?: "Error desconocido.")
        }
    }

    /**
     * Emite un voto en una propuesta de gobernanza, firmado con passkey.
     *
     * Invoca [Governance.vote(resident=C…, proposal_id, support)]. El smart
     * account debe tener un ResidentToken minted en el barrio de la propuesta;
     * el contrato lo verifica on-chain y rechaza con NotAResident si no.
     *
     * @param activity    Activity activa — OBLIGATORIO.
     * @param proposalId  ID (u64) de la propuesta activa.
     * @param support     true = a favor, false = en contra.
     */
    suspend fun voteWithPasskey(
        activity: Activity,
        proposalId: Long,
        support: Boolean,
    ): RaizResult<Unit> {
        if (!isAvailable) return passkeyNotAvailableError()

        val credId     = store.storedPasskeyCredentialId() ?: return noWalletError()
        val contractId = store.storedPasskeyContractId()   ?: return noWalletError()

        return try {
            val kit = buildKit(activity)
            kit.walletOperations.connectWallet(
                OZWalletOperations.ConnectWalletOptions(
                    credentialId = credId,
                    contractId   = contractId,
                    fresh        = false,
                    prompt       = true,
                ),
            )

            // Governance.vote(resident: Address, proposal_id: u64, support: bool)
            val params = listOf(
                Address(contractId).toSCVal(),           // resident  (C… del smart account)
                Scv.toUint64(proposalId.toULong()),     // proposal_id u64
                Scv.toBoolean(support),                  // support   bool
            )

            val result = kit.transactionOperations.contractCall(
                target     = deployments.governance,
                targetFn   = "vote",
                targetArgs = params,
            )

            if (result.success) {
                Log.i(TAG, "voteWithPasskey OK proposalId=$proposalId support=$support hash=${result.hash}")
                RaizResult.Success(Unit)
            } else {
                Log.e(TAG, "voteWithPasskey FAIL: ${result.error}")
                val code = when {
                    result.error?.contains("AlreadyVoted") == true ||
                        result.error?.contains("Error(Contract, #10)") == true ->
                        RaizErrorCode.ALREADY_VOTED
                    result.error?.contains("NotAResident") == true ||
                        result.error?.contains("Error(Contract, #6)") == true ->
                        RaizErrorCode.NOT_A_RESIDENT
                    result.error?.contains("ProposalClosed") == true ||
                        result.error?.contains("Error(Contract, #11)") == true ->
                        RaizErrorCode.PROPOSAL_CLOSED
                    else -> RaizErrorCode.NETWORK_ERROR
                }
                RaizResult.Error(code, result.error ?: "vote falló")
            }
        } catch (e: WebAuthnException.Cancelled) {
            RaizResult.Error(RaizErrorCode.UNKNOWN, "Operación cancelada por el usuario.")
        } catch (e: WebAuthnException) {
            Log.e(TAG, "voteWithPasskey WebAuthn: ${e.message}")
            RaizResult.Error(RaizErrorCode.UNAUTHORIZED, "Verificación passkey falló: ${e.message}")
        } catch (e: SmartAccountException) {
            Log.e(TAG, "voteWithPasskey SmartAccount: ${e.message}")
            RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "Error del smart account.")
        } catch (e: Exception) {
            Log.e(TAG, "voteWithPasskey inesperado", e)
            RaizResult.Error(RaizErrorCode.UNKNOWN, e.message ?: "Error desconocido.")
        }
    }

    /**
     * Crea una propuesta de gasto del fondo comunitario, firmada con passkey.
     *
     * Invoca [Governance.create_proposal(proposer=C…, barrio_id, description,
     * amount, recipient, duration_days)]. El smart account debe ser residente
     * verificado del barrio (ResidentToken minted); el contrato rechaza con
     * NotAResident si no.
     *
     * ## Limitación
     * [OZTransactionOperations.contractCall] devuelve [TransactionResult] que
     * solo contiene éxito/error y el hash de la tx, pero NO el valor de retorno
     * de la función Soroban (el u64 con el proposal_id asignado on-chain). Por
     * eso este método devuelve [RaizResult<Unit>]. La UI debe llamar a
     * [SorobanClient.listActiveProposals] después de crear para obtener el id.
     *
     * @param activity       Activity activa — OBLIGATORIO.
     * @param barrioId       ID del barrio (hex 64 chars = 32 bytes).
     * @param description    Texto de la propuesta (soroban::String).
     * @param amountStroops  Monto a retirar del fondo, en stroops.
     * @param recipient      Dirección G… o C… del beneficiario.
     * @param durationDays   Duración de la votación [3, 14] días.
     */
    suspend fun createProposalWithPasskey(
        activity: Activity,
        barrioId: String,
        description: String,
        amountStroops: Long,
        recipient: String,
        durationDays: Int,
    ): RaizResult<Unit> {
        if (!isAvailable) return passkeyNotAvailableError()

        val credId     = store.storedPasskeyCredentialId() ?: return noWalletError()
        val contractId = store.storedPasskeyContractId()   ?: return noWalletError()

        val barrioBytes = barrioId.hexToBytes32()
            ?: return RaizResult.Error(RaizErrorCode.PARSE_ERROR, "barrio_id no es hex de 32 bytes: $barrioId")

        return try {
            val kit = buildKit(activity)
            kit.walletOperations.connectWallet(
                OZWalletOperations.ConnectWalletOptions(
                    credentialId = credId,
                    contractId   = contractId,
                    fresh        = false,
                    prompt       = true,
                ),
            )

            // Governance.create_proposal(proposer, barrio_id, description, amount, recipient, duration_days)
            // ORDEN CRÍTICO — debe coincidir con el spec del contrato.
            val params = listOf(
                Address(contractId).toSCVal(),                    // proposer     (C… del smart account)
                Scv.toBytes(barrioBytes),                         // barrio_id    BytesN<32>
                Scv.toString(description),                        // description  soroban::String
                Scv.toInt128(BigInteger.fromLong(amountStroops)), // amount       i128 stroops
                Address(recipient).toSCVal(),                     // recipient    Address
                Scv.toUint32(durationDays.toUInt()),             // duration_days u32
            )

            val result = kit.transactionOperations.contractCall(
                target     = deployments.governance,
                targetFn   = "create_proposal",
                targetArgs = params,
            )

            if (result.success) {
                Log.i(TAG, "createProposalWithPasskey OK hash=${result.hash}")
                RaizResult.Success(Unit)
            } else {
                Log.e(TAG, "createProposalWithPasskey FAIL: ${result.error}")
                val code = when {
                    result.error?.contains("NotAResident") == true ||
                        result.error?.contains("Error(Contract, #6)") == true ->
                        RaizErrorCode.NOT_A_RESIDENT
                    result.error?.contains("InvalidAmount") == true ||
                        result.error?.contains("Error(Contract, #9)") == true ->
                        RaizErrorCode.PARSE_ERROR
                    result.error?.contains("InvalidDuration") == true ||
                        result.error?.contains("Error(Contract, #8)") == true ->
                        RaizErrorCode.PARSE_ERROR
                    else -> RaizErrorCode.NETWORK_ERROR
                }
                RaizResult.Error(code, result.error ?: "create_proposal falló")
            }
        } catch (e: WebAuthnException.Cancelled) {
            RaizResult.Error(RaizErrorCode.UNKNOWN, "Operación cancelada por el usuario.")
        } catch (e: WebAuthnException) {
            Log.e(TAG, "createProposalWithPasskey WebAuthn: ${e.message}")
            RaizResult.Error(RaizErrorCode.UNAUTHORIZED, "Verificación passkey falló: ${e.message}")
        } catch (e: SmartAccountException) {
            Log.e(TAG, "createProposalWithPasskey SmartAccount: ${e.message}")
            RaizResult.Error(RaizErrorCode.NETWORK_ERROR, e.message ?: "Error del smart account.")
        } catch (e: Exception) {
            Log.e(TAG, "createProposalWithPasskey inesperado", e)
            RaizResult.Error(RaizErrorCode.UNKNOWN, e.message ?: "Error desconocido.")
        }
    }

    // ── Helpers de error ──────────────────────────────────────────────────

    private fun passkeyNotAvailableError(): RaizResult.Error =
        RaizResult.Error(
            RaizErrorCode.UNKNOWN,
            when {
                !isSupported  -> "Passkey requiere Android 9 (API 28)+. API actual: ${Build.VERSION.SDK_INT}."
                !isConfigured -> "passkey.rp.id no está configurado en local.properties."
                else          -> "Passkey no disponible."
            },
        )

    private fun noWalletError(): RaizResult.Error =
        RaizResult.Error(
            RaizErrorCode.NOT_FOUND,
            "No hay wallet passkey guardada en este dispositivo. " +
            "Crea una nueva wallet o importa tu frase semilla.",
        )

    // ── Helpers privados ──────────────────────────────────────────────────

    /**
     * Convierte un barrio_id (hex, 64 chars) a 32 bytes para [Scv.toBytes].
     * Devuelve null si la cadena no es hex válido de exactamente 32 bytes.
     * Mismo patrón que SorobanClient.hexToBytes (duplicado intencionalmente
     * para mantener independencia de módulo entre ambas clases).
     */
    private fun String.hexToBytes32(): ByteArray? {
        val clean = removePrefix("0x")
        if (clean.length != 64 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return null
        }
        return ByteArray(32) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
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
