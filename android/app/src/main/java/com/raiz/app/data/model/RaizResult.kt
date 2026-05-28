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
    INSUFFICIENT_BALANCE,
    INSUFFICIENT_POINTS,
    OUT_OF_STOCK,
    NOT_A_RESIDENT,
    ALREADY_VOTED,
    PROPOSAL_CLOSED,
    QUORUM_NOT_REACHED,
    UNAUTHORIZED,
    NETWORK_ERROR,
    SIMULATION_FAILED,
    PARSE_ERROR,
    NOT_FOUND,
    UNKNOWN,
}
