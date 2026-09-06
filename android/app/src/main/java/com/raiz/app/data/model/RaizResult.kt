package com.raiz.app.data.model

/**
 * Wrapper de resultado para no propagar excepciones a la UI.
 * El SorobanClient y los repositorios devuelven RaizResult<T> en lugar de lanzar.
 */
sealed class RaizResult<out T> {
    data class Success<T>(val data: T) : RaizResult<T>()
    data class Error(val code: RaizErrorCode, val message: String) : RaizResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): RaizResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    fun getOrNull(): T? = (this as? Success)?.data
}

enum class RaizErrorCode {
    /** Saldo insuficiente. Desde el relayer: `503 FAUCET_EMPTY` (el admin se quedó sin USDC para el faucet). */
    INSUFFICIENT_BALANCE,
    INSUFFICIENT_POINTS,
    OUT_OF_STOCK,
    NOT_A_RESIDENT,
    ALREADY_VOTED,
    PROPOSAL_CLOSED,
    QUORUM_NOT_REACHED,
    /** Desde el relayer: `401 UNAUTHORIZED_APP`, `422 TRUSTLINE_DEAUTHORIZED`, `502 UNAUTHORIZED_ADMIN`. */
    UNAUTHORIZED,
    /**
     * Fallo de red o servicio no disponible. Desde el relayer: `RPC_UNREACHABLE`,
     * `QUEUE_FULL`, `RESTORE_REQUIRED`, `TX_TIMEOUT` y los timeouts de Ktor. Ojo:
     * en los dos últimos la transacción PUEDE estar en vuelo — el mensaje lo dice
     * y el reintento debe reutilizar la misma `idempotency-key`
     * (`RelayerClient.isPendingTransactionError`).
     */
    NETWORK_ERROR,
    SIMULATION_FAILED,
    PARSE_ERROR,
    NOT_FOUND,
    UNKNOWN,

    /**
     * El relayer admin respondió 429 (`RATE_LIMITED`): cupo por IP, por address
     * (faucet) o cupo diario del endpoint agotado. El mensaje incluye el
     * `retryAfterSeconds` del relayer cuando viene en `details`. Ver
     * `data/relayer/RelayerClient.kt` y `raiz-relayer/README.md` § Límites y cupos.
     */
    RATE_LIMITED,
}
