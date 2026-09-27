package com.raiz.app.data.anchor

/**
 * Modelos solo-cliente de D3 (SEP-10 + SEP-24 contra el anchor de prueba del SDF,
 * `testanchor.stellar.org`). NO son structs de contrato — ver `docs/RaizModels.kt`
 * para esos. Fuente de verdad de estos: `data/anchor/AnchorClient.kt`.
 *
 * El USDC de este anchor es DISTINTO al USDC de Blend que usa el fondo del barrio:
 * la UI lo rotula siempre "USDC · anchor de prueba" y nunca se suma al balance del pool.
 */

/**
 * Configuración del anchor, resuelta a partir de su `stellar.toml` (SEP-1) y su
 * `/info` de SEP-24. Se cachea en memoria en [AnchorClient] — no cambia entre
 * sesiones salvo que el propio anchor reconfigure sus endpoints.
 */
data class AnchorInfo(
    val homeDomain: String,
    val webAuthEndpoint: String,
    val sep24Url: String,
    val signingKey: String,
    val usdcIssuer: String,
    val depositMinUsdc: Double?,
    val depositMaxUsdc: Double?,
)

/**
 * Sesión SEP-10 autenticada: JWT + cuenta a la que pertenece. Vive SOLO en
 * memoria — [AnchorClient] la cachea por cuenta mientras [isUsable], nunca se
 * persiste a disco ni a `SharedPreferences`.
 */
data class AnchorSession(
    val account: String,
    val jwt: String,
    val expiresAtEpochSec: Long?,
) {
    /**
     * `true` si el JWT sigue sirviendo para una request nueva. Sin `exp` (el
     * anchor no lo publicó) se asume utilizable. Con `exp`, se exige un
     * colchón de 60 s antes de la expiración real para no arrancar un
     * depósito con un token que caduque a mitad de flujo.
     */
    fun isUsable(nowSec: Long = System.currentTimeMillis() / 1000): Boolean {
        val exp = expiresAtEpochSec ?: return true
        return exp - 60 > nowSec
    }
}

/** Respuesta de `POST /sep24/transactions/deposit/interactive` (SEP-24). */
data class AnchorDepositStart(
    val id: String,
    val interactiveUrl: String,
    val type: String,
)

/**
 * Estado propio del depósito, mapeado desde `Sep24TransactionStatus` del SDK
 * (ver el mapeo exacto en `AnchorClient.toDepositState`). Colapsa varios
 * estados SEP-24 equivalentes de cara a la UI (p. ej. las tres variantes de
 * "el anchor está procesando" → `PENDING_ANCHOR`).
 */
enum class AnchorDepositState {
    INCOMPLETE,
    PENDING_USER,
    PENDING_ANCHOR,
    PENDING_STELLAR,
    PENDING_TRUST,
    PENDING_EXTERNAL,
    COMPLETED,
    REFUNDED,
    EXPIRED,
    ERROR,
    UNKNOWN,
}

/** Último estado conocido de una transacción de depósito SEP-24. */
data class AnchorDepositStatus(
    val id: String,
    val rawStatus: String,
    val state: AnchorDepositState,
    val stellarTxHash: String?,
    val amountInUsdc: String?,
    val amountOutUsdc: String?,
    val amountFeeUsdc: String?,
    val message: String?,
    val moreInfoUrl: String?,
    val completedAt: String?,
) {
    val isTerminal: Boolean
        get() = state in TERMINAL_STATES

    private companion object {
        val TERMINAL_STATES = setOf(
            AnchorDepositState.COMPLETED,
            AnchorDepositState.REFUNDED,
            AnchorDepositState.EXPIRED,
            AnchorDepositState.ERROR,
        )
    }
}

/**
 * [AnchorClient.pollDeposit] agotó `timeoutMs` sin llegar a un estado
 * terminal. Dato, no bug de programación: el ViewModel la traduce a un botón
 * "Seguir esperando" (`DepositPhase.TIMED_OUT`), no a un mensaje de error genérico.
 */
class AnchorPollTimeout(
    val depositId: String,
    val lastKnownState: AnchorDepositState?,
) : Exception("Se agotó el tiempo de espera del depósito $depositId (último estado: $lastKnownState)")

/**
 * [AnchorClient.pollDeposit] recibió un 401/403 del anchor durante el
 * polling: la sesión SEP-10 cacheada dejó de servir. El ViewModel debe
 * re-autenticar (nueva firma con la wallet) y reanudar el polling.
 */
class AnchorSessionExpired(message: String) : Exception(message)
