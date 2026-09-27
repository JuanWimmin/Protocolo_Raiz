package com.raiz.app.data.anchor

import android.util.Log
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.soneso.stellar.sdk.KeyPair
import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.sep.sep01.StellarToml
import com.soneso.stellar.sdk.sep.sep10.WebAuth
import com.soneso.stellar.sdk.sep.sep10.exceptions.ChallengeRequestException
import com.soneso.stellar.sdk.sep.sep10.exceptions.ChallengeValidationException
import com.soneso.stellar.sdk.sep.sep10.exceptions.TokenSubmissionException
import com.soneso.stellar.sdk.sep.sep24.Sep24DepositRequest
import com.soneso.stellar.sdk.sep.sep24.Sep24Service
import com.soneso.stellar.sdk.sep.sep24.Sep24Transaction
import com.soneso.stellar.sdk.sep.sep24.Sep24TransactionRequest
import com.soneso.stellar.sdk.sep.sep24.Sep24TransactionStatus
import com.soneso.stellar.sdk.sep.sep24.exceptions.Sep24AuthenticationRequiredException
import com.soneso.stellar.sdk.sep.sep24.exceptions.Sep24InvalidRequestException
import com.soneso.stellar.sdk.sep.sep24.exceptions.Sep24ServerErrorException
import com.soneso.stellar.sdk.sep.sep24.exceptions.Sep24TransactionNotFoundException
import io.ktor.client.HttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Cliente del anchor de prueba del SDF (`testanchor.stellar.org`, D3 del SOW Instaward):
 * SEP-1 (descubrimiento), SEP-10 (auth con firma de wallet) y SEP-24 (depósito interactivo).
 *
 * El USDC de este anchor es DISTINTO al USDC de Blend que usa el fondo del barrio
 * (`data/stellar/HorizonStream`, `usdcIssuer`): este cliente solo mueve el USDC del
 * `stellar.toml` del anchor. La UI lo rotula siempre "USDC · anchor de prueba".
 *
 * Wallets passkey (`C...`) no pueden autenticar con SEP-10 clásico (firma de cuenta
 * `G...`, no de contrato) — [authenticate] espera un [KeyPair] con clave privada
 * (la wallet semilla). La pantalla de depósito debe filtrar ese caso antes de llamar
 * a este cliente (`DepositPhase.PASSKEY_UNSUPPORTED`).
 *
 * Mismo patrón que [com.raiz.app.data.relayer.RelayerClient]: constructor primario
 * parametrizable (tests JVM con `MockEngine` sin tocar `BuildConfig`) + constructor
 * `@Inject` secundario con el `HttpClient` `@Named("anchor")` de `di/DataModule.kt` y
 * el dominio fijo del proyecto. Nunca lanza — todo suspend devuelve [RaizResult].
 *
 * @param http HttpClient inyectado con `@Named("anchor")`.
 * @param homeDomain Dominio del anchor (sin esquema). Parámetro del constructor
 *   primario, igual que `baseUrl` en `RelayerClient`, para poder apuntar a un
 *   dominio de prueba con `MockEngine` en tests JVM.
 */
@Singleton
class AnchorClient(
    private val http: HttpClient,
    private val homeDomain: String,
) {

    @Inject
    constructor(@Named("anchor") http: HttpClient) : this(
        http = http,
        homeDomain = RaizConstants.ANCHOR_HOME_DOMAIN,
    )

    private var cachedInfo: AnchorInfo? = null
    private val sessions = mutableMapOf<String, AnchorSession>()

    /**
     * Resuelve [AnchorInfo] desde `stellar.toml` (SEP-1: `WEB_AUTH_ENDPOINT`,
     * `TRANSFER_SERVER_SEP0024`, `SIGNING_KEY`, issuer USDC en `[[CURRENCIES]]`) y
     * `/info` de SEP-24 (mín/máx de depósito USDC). Cachea en memoria; `force = true`
     * fuerza un refetch (el anchor de prueba no suele reconfigurarse, pero por si acaso).
     *
     * Si el `stellar.toml` no trae issuer para `USDC`, cae a
     * [RaizConstants.ANCHOR_USDC_ISSUER] con un aviso — nunca al revés: el toml manda.
     * Endpoints SEP-10/24 sin `https://` se rechazan con `PARSE_ERROR` (el JWT viajaría en claro).
     */
    suspend fun loadInfo(force: Boolean = false): RaizResult<AnchorInfo> = withContext(Dispatchers.IO) {
        cachedInfo?.let { if (!force) return@withContext RaizResult.Success(it) }
        try {
            val toml = StellarToml.fromDomain(homeDomain, httpClient = http)
            val webAuthEndpoint = toml.generalInformation.webAuthEndpoint
            val signingKey = toml.generalInformation.signingKey
            val sep24Url = toml.generalInformation.transferServerSep24
            if (webAuthEndpoint == null || signingKey == null || sep24Url == null) {
                Log.w(
                    TAG,
                    "AnchorClient.loadInfo: stellar.toml de $homeDomain incompleto " +
                        "(webAuth=$webAuthEndpoint sep24=$sep24Url signingKey=$signingKey)",
                )
                return@withContext RaizResult.Error(
                    RaizErrorCode.PARSE_ERROR,
                    "El anchor de prueba no publica los endpoints SEP-10/24 esperados",
                )
            }
            // SEP-1 exige https en WEB_AUTH_ENDPOINT / TRANSFER_SERVER_SEP0024: por ahí viajan el
            // challenge firmado y el `Authorization: Bearer <jwt>` de cada request SEP-24. El
            // `cleartextTrafficPermitted="false"` del network_security_config NO protege aquí:
            // el engine CIO de Ktor no consulta la NetworkSecurityPolicy de Android.
            if (!isHttps(webAuthEndpoint) || !isHttps(sep24Url)) {
                Log.w(
                    TAG,
                    "AnchorClient.loadInfo: stellar.toml de $homeDomain con endpoints sin TLS " +
                        "(webAuth=$webAuthEndpoint sep24=$sep24Url); se rechaza",
                )
                return@withContext RaizResult.Error(RaizErrorCode.PARSE_ERROR, MSG_ANCHOR_NO_TLS)
            }

            val tomlUsdcIssuer = toml.currencies
                ?.firstOrNull { it.code == RaizConstants.ANCHOR_USDC_CODE }
                ?.issuer
            val usdcIssuer = when {
                tomlUsdcIssuer == null -> {
                    Log.w(TAG, "AnchorClient.loadInfo: stellar.toml sin issuer USDC; se usa el fijo del proyecto")
                    RaizConstants.ANCHOR_USDC_ISSUER
                }
                tomlUsdcIssuer != RaizConstants.ANCHOR_USDC_ISSUER -> {
                    Log.w(
                        TAG,
                        "AnchorClient.loadInfo: issuer USDC del toml ($tomlUsdcIssuer) difiere de la " +
                            "constante (${RaizConstants.ANCHOR_USDC_ISSUER}); se usa el del toml",
                    )
                    tomlUsdcIssuer
                }
                else -> tomlUsdcIssuer
            }

            val sep24 = Sep24Service(sep24Url, httpClient = http)
            val infoResponse = sep24.info(lang = "es")
            val usdcAsset = infoResponse.depositAssets?.get(RaizConstants.ANCHOR_USDC_CODE)

            val info = AnchorInfo(
                homeDomain = homeDomain,
                webAuthEndpoint = webAuthEndpoint,
                sep24Url = sep24Url,
                signingKey = signingKey,
                usdcIssuer = usdcIssuer,
                depositMinUsdc = usdcAsset?.minAmount?.toDoubleOrNull(),
                depositMaxUsdc = usdcAsset?.maxAmount?.toDoubleOrNull(),
            )
            cachedInfo = info
            RaizResult.Success(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AnchorClient.loadInfo: ${e.javaClass.simpleName}: ${e.message}")
            mapException(e)
        }
    }

    /**
     * `true` si hay una [AnchorSession] cacheada para `account` y su JWT sigue
     * sirviendo ([AnchorSession.isUsable]). No hace red.
     */
    fun cachedSession(account: String): AnchorSession? {
        val session = sessions[account] ?: return null
        return if (session.isUsable()) session else null
    }

    /** Borra la sesión cacheada de `account`, o todas si `account` es `null`. */
    fun clearSession(account: String? = null) {
        if (account == null) sessions.clear() else sessions.remove(account)
    }

    /**
     * Autenticación SEP-10 completa (challenge → validación de las 13 comprobaciones
     * del SDK → firma con `keyPair` → envío → JWT). Reutiliza la sesión cacheada si
     * sigue [AnchorSession.isUsable] en vez de repetir el challenge.
     *
     * El JWT vive SOLO en memoria (en [sessions]) — nunca se persiste a disco.
     */
    suspend fun authenticate(keyPair: KeyPair): RaizResult<AnchorSession> = withContext(Dispatchers.IO) {
        val account = keyPair.getAccountId()
        cachedSession(account)?.let { return@withContext RaizResult.Success(it) }

        val info = when (val result = loadInfo()) {
            is RaizResult.Success -> result.data
            is RaizResult.Error -> return@withContext result
        }
        try {
            val webAuth = WebAuth(
                authEndpoint = info.webAuthEndpoint,
                network = Network.TESTNET,
                serverSigningKey = info.signingKey,
                serverHomeDomain = homeDomain,
                httpClient = http,
            )
            val token = webAuth.jwtToken(
                clientAccountId = account,
                signers = listOf(keyPair),
                homeDomain = homeDomain,
            )
            val session = AnchorSession(account = account, jwt = token.token, expiresAtEpochSec = token.exp)
            sessions[account] = session
            Log.i(TAG, "AnchorClient.authenticate: sesión SEP-10 obtenida (exp=${token.exp})")
            RaizResult.Success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AnchorClient.authenticate: ${e.javaClass.simpleName}: ${e.message}")
            mapException(e)
        }
    }

    /**
     * `POST /sep24/transactions/deposit/interactive`. Devuelve la URL a abrir en un
     * Custom Tab (`ui/deposit/CustomTabs.kt`) y el `id` para consultar el estado
     * ([depositStatus] / [pollDeposit]).
     *
     * `amountUsdc` es opcional (el usuario también puede fijar el monto dentro del
     * flujo interactivo del anchor); cuando viene, viaja como string decimal.
     */
    suspend fun startDeposit(session: AnchorSession, amountUsdc: String?): RaizResult<AnchorDepositStart> =
        withContext(Dispatchers.IO) {
            val info = when (val result = loadInfo()) {
                is RaizResult.Success -> result.data
                is RaizResult.Error -> return@withContext result
            }
            try {
                val sep24 = Sep24Service(info.sep24Url, httpClient = http)
                val response = sep24.deposit(
                    Sep24DepositRequest(
                        assetCode = RaizConstants.ANCHOR_USDC_CODE,
                        jwt = session.jwt,
                        assetIssuer = info.usdcIssuer,
                        amount = amountUsdc,
                        account = session.account,
                        lang = "es",
                        walletName = "RAÍZ",
                    ),
                )
                if (response.type != TYPE_INTERACTIVE_CUSTOMER_INFO_NEEDED) {
                    Log.w(TAG, "AnchorClient.startDeposit: type inesperado '${response.type}'")
                }
                RaizResult.Success(
                    AnchorDepositStart(id = response.id, interactiveUrl = response.url, type = response.type),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AnchorClient.startDeposit: ${e.javaClass.simpleName}: ${e.message}")
                mapException(e)
            }
        }

    /** `GET /sep24/transaction?id=…`. Un solo chequeo de estado (sin polling). */
    suspend fun depositStatus(session: AnchorSession, id: String): RaizResult<AnchorDepositStatus> =
        withContext(Dispatchers.IO) {
            val info = when (val result = loadInfo()) {
                is RaizResult.Success -> result.data
                is RaizResult.Error -> return@withContext result
            }
            try {
                val sep24 = Sep24Service(info.sep24Url, httpClient = http)
                val response = sep24.transaction(Sep24TransactionRequest(jwt = session.jwt, id = id, lang = "es"))
                RaizResult.Success(response.transaction.toAnchorDepositStatus())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AnchorClient.depositStatus($id): ${e.javaClass.simpleName}: ${e.message}")
                mapException(e)
            }
        }

    /**
     * Sondea [depositStatus] hasta un estado terminal ([AnchorDepositStatus.isTerminal])
     * o hasta agotar `timeoutMs`. Emite cada CAMBIO de estado (y siempre el primero);
     * NO emite en cada tick si el estado no cambió.
     *
     * Backoff: arranca en `initialDelayMs` (3 s por defecto) y crece ×1.5 hasta
     * `maxDelayMs` (10 s por defecto) — parámetros inyectables para que los tests JVM
     * no esperen segundos reales.
     *
     * Errores transitorios de red (`NETWORK_ERROR`, `NOT_FOUND` puntual, etc.) NO
     * cortan el flow: se registran y se reintenta en el siguiente tick. Un `401/403`
     * (`RaizErrorCode.UNAUTHORIZED`, sesión SEP-10 caducada a mitad de polling) SÍ
     * corta con [AnchorSessionExpired] — el llamador debe re-[authenticate] y volver
     * a llamar a [pollDeposit]. Al vencer `timeoutMs` sin terminal, lanza
     * [AnchorPollTimeout] garantizando haber emitido el último estado conocido antes.
     *
     * El "reloj" del timeout es la suma de los `delay()` ya consumidos, NO
     * `System.currentTimeMillis()`: con `kotlinx-coroutines-test` (`runTest`), `delay()`
     * avanza un reloj VIRTUAL (instantáneo en tiempo real) mientras que
     * `System.currentTimeMillis()` sigue el reloj real — mezclar los dos volvería el
     * timeout dependiente de cuánto tarde el JVM de verdad en iterar el `while`, en vez
     * de determinista. Sumar `delayMs` mantiene ambos en la misma base de tiempo tanto
     * en producción (tiempo real) como en tests (tiempo virtual).
     */
    fun pollDeposit(
        session: AnchorSession,
        id: String,
        timeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS,
        initialDelayMs: Long = DEFAULT_POLL_INITIAL_DELAY_MS,
        maxDelayMs: Long = DEFAULT_POLL_MAX_DELAY_MS,
    ): Flow<AnchorDepositStatus> = flow {
        var elapsedMs = 0L
        var delayMs = initialDelayMs
        var lastEmittedState: AnchorDepositState? = null

        while (true) {
            when (val result = depositStatus(session, id)) {
                is RaizResult.Success -> {
                    val status = result.data
                    val isNewState = status.state != lastEmittedState
                    if (isNewState) {
                        lastEmittedState = status.state
                        emit(status)
                    }
                    if (status.isTerminal) return@flow
                    if (elapsedMs >= timeoutMs) {
                        // Garantiza que el último estado consultado salió por el flow antes de cortar.
                        if (!isNewState) emit(status)
                        throw AnchorPollTimeout(id, status.state)
                    }
                }
                is RaizResult.Error -> {
                    if (result.code == RaizErrorCode.UNAUTHORIZED) {
                        throw AnchorSessionExpired(result.message)
                    }
                    Log.w(TAG, "AnchorClient.pollDeposit($id): error transitorio (${result.code}): ${result.message}")
                    if (elapsedMs >= timeoutMs) {
                        throw AnchorPollTimeout(id, lastEmittedState)
                    }
                }
            }
            delay(delayMs)
            elapsedMs += delayMs
            delayMs = (delayMs * 3 / 2).coerceAtMost(maxDelayMs)
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    @Suppress("DEPRECATION") // amountFee: el SDK lo marca deprecated en favor de feeDetails, pero sigue siendo el valor simple que necesitamos.
    private fun Sep24Transaction.toAnchorDepositStatus(): AnchorDepositStatus = AnchorDepositStatus(
        id = id,
        rawStatus = status,
        state = toDepositState(),
        stellarTxHash = stellarTransactionId,
        amountInUsdc = amountIn,
        amountOutUsdc = amountOut,
        amountFeeUsdc = amountFee,
        message = message,
        moreInfoUrl = moreInfoUrl,
        completedAt = completedAt,
    )

    /**
     * Mapeo de `Sep24TransactionStatus` (SDK) a [AnchorDepositState] (propio):
     * colapsa las variantes equivalentes de cara a la UI (ver KDoc de [AnchorDepositState]).
     */
    private fun Sep24Transaction.toDepositState(): AnchorDepositState = when (getStatusEnum()) {
        Sep24TransactionStatus.INCOMPLETE -> AnchorDepositState.INCOMPLETE
        Sep24TransactionStatus.PENDING_USER_TRANSFER_START,
        Sep24TransactionStatus.PENDING_USER_TRANSFER_COMPLETE,
        Sep24TransactionStatus.PENDING_USER,
        -> AnchorDepositState.PENDING_USER
        Sep24TransactionStatus.PENDING_ANCHOR,
        Sep24TransactionStatus.ON_HOLD,
        -> AnchorDepositState.PENDING_ANCHOR
        Sep24TransactionStatus.PENDING_STELLAR -> AnchorDepositState.PENDING_STELLAR
        Sep24TransactionStatus.PENDING_TRUST -> AnchorDepositState.PENDING_TRUST
        Sep24TransactionStatus.PENDING_EXTERNAL -> AnchorDepositState.PENDING_EXTERNAL
        Sep24TransactionStatus.COMPLETED -> AnchorDepositState.COMPLETED
        Sep24TransactionStatus.REFUNDED -> AnchorDepositState.REFUNDED
        Sep24TransactionStatus.EXPIRED -> AnchorDepositState.EXPIRED
        Sep24TransactionStatus.NO_MARKET,
        Sep24TransactionStatus.TOO_SMALL,
        Sep24TransactionStatus.TOO_LARGE,
        Sep24TransactionStatus.ERROR,
        -> AnchorDepositState.ERROR
        null -> AnchorDepositState.UNKNOWN
    }

    /**
     * Traduce las excepciones documentadas del SDK (SEP-1/10/24) y de transporte a
     * [RaizResult.Error]. Única función de mapeo de errores de esta clase.
     *
     * `IllegalStateException` se añade a la familia `NETWORK_ERROR`: es lo que lanza
     * `StellarToml.fromDomain` cuando el `stellar.toml` no responde 200 — no está en
     * el catálogo de excepciones de SEP-10/24 propiamente, pero es la misma familia
     * ("no se pudo hablar con el anchor") de cara al usuario.
     */
    private fun mapException(e: Exception): RaizResult.Error = when (e) {
        is Sep24AuthenticationRequiredException,
        is TokenSubmissionException,
        -> RaizResult.Error(RaizErrorCode.UNAUTHORIZED, MSG_ANCHOR_REJECTED_AUTH)

        is ChallengeValidationException ->
            RaizResult.Error(RaizErrorCode.UNAUTHORIZED, MSG_INVALID_CHALLENGE)

        is Sep24InvalidRequestException ->
            RaizResult.Error(RaizErrorCode.PARSE_ERROR, e.message ?: MSG_ANCHOR_INVALID_REQUEST)

        is Sep24TransactionNotFoundException ->
            RaizResult.Error(RaizErrorCode.NOT_FOUND, MSG_DEPOSIT_NOT_FOUND)

        // El SDK solo tipifica el 403 con body {"type":"authentication_required"}; un 401 (o un
        // 403 con otro body) llega como Sep24ServerErrorException con su statusCode. Ambos
        // significan "el JWT no sirve" → UNAUTHORIZED, para que pollDeposit corte con
        // AnchorSessionExpired y el VM re-autentique, en vez de reintentar 5 min como si fuera red.
        is Sep24ServerErrorException ->
            if (e.statusCode == 401 || e.statusCode == 403) {
                RaizResult.Error(RaizErrorCode.UNAUTHORIZED, MSG_ANCHOR_REJECTED_AUTH)
            } else {
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, MSG_ANCHOR_UNREACHABLE)
            }

        is ChallengeRequestException,
        is IllegalStateException,
        is IOException, // cubre HttpRequestTimeoutException, ConnectTimeoutException, SocketTimeoutException y UnknownHostException (todas son IOException en Ktor/JVM).
        -> RaizResult.Error(RaizErrorCode.NETWORK_ERROR, MSG_ANCHOR_UNREACHABLE)

        else -> RaizResult.Error(RaizErrorCode.UNKNOWN, e.message ?: "Error desconocido del anchor de prueba")
    }

    private fun isHttps(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

    companion object {
        private const val TAG = "RAIZ"
        private const val TYPE_INTERACTIVE_CUSTOMER_INFO_NEEDED = "interactive_customer_info_needed"

        const val DEFAULT_POLL_TIMEOUT_MS = 5 * 60_000L
        const val DEFAULT_POLL_INITIAL_DELAY_MS = 3_000L
        const val DEFAULT_POLL_MAX_DELAY_MS = 10_000L

        const val MSG_ANCHOR_REJECTED_AUTH = "El anchor rechazó la autenticación"
        const val MSG_INVALID_CHALLENGE = "El challenge del anchor no es válido: no confíes en este servidor"
        const val MSG_ANCHOR_INVALID_REQUEST = "El anchor rechazó la solicitud"
        const val MSG_DEPOSIT_NOT_FOUND = "El anchor no encontró ese depósito"
        const val MSG_ANCHOR_UNREACHABLE = "No se pudo hablar con el anchor de prueba. Revisa la red y reintenta."
        const val MSG_ANCHOR_NO_TLS = "El anchor publica endpoints sin TLS; no se usará"
    }
}
