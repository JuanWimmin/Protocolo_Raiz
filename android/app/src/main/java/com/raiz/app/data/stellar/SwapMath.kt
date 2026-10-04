package com.raiz.app.data.stellar

import com.raiz.app.data.model.RaizConstants
import java.util.Locale

/**
 * Matemática pura de la conversión "USDC del anchor de prueba → USDC del fondo (Blend)"
 * (WP3 stretch, ver `wp3_swap_contract.md`): sin Android ni SDK Stellar, para poder
 * testearla en JVM puro (`test/`, no `androidTest/`).
 *
 * Contexto: en testnet el anchor de prueba del SDF (`GBBD47IF…`) emite un USDC DISTINTO
 * al USDC de Blend (`GATALTGT…`) que usa el Pool y los comercios. La conversión se hace
 * con una `PathPaymentStrictSendOperation` contra el pool de liquidez clásico entre ambos
 * (id `23283282…`, fee 30 bps) — [HorizonStream.quoteStrictSend] /
 * [HorizonStream.pathPaymentStrictSend]. En mainnet ambos serían el USDC de Circle y esta
 * conversión no existiría.
 *
 * Montos SIEMPRE en stroops (`Long`) salvo en la frontera con Horizon, que espera un
 * string decimal de 7 decimales — nunca `Double` para dinero.
 */
object SwapMath {

    /** Tolerancia de slippage para `dest_min`: 100 bps = 1 %. */
    const val SLIPPAGE_BPS = 100

    /**
     * Resultado de cotizar una conversión: cuánto se envía (`sendStroops`), cuánto
     * devolvió Horizon como mejor cotización (`destStroops`), el mínimo aceptable tras
     * aplicar slippage (`destMinStroops`, es el `dest_min` de la operación) y cuántos
     * saltos tiene el camino elegido (`hops`; 0 = pool directo, como el verificado hoy).
     */
    data class Quote(
        val sendStroops: Long,
        val destStroops: Long,
        val destMinStroops: Long,
        val hops: Int,
    )

    /**
     * stroops (`Long`, ≥ 0) -> string decimal Horizon de 7 decimales, sin notación
     * científica ni redondeo (`44422608` -> `"4.4422608"`, `50000000` -> `"5.0000000"`).
     * Enteros puros — nunca pasa por `Double`.
     */
    fun stroopsToAmount(stroops: Long): String {
        require(stroops >= 0) { "stroops no puede ser negativo: $stroops" }
        val whole = stroops / RaizConstants.USDC_STROOPS_PER_UNIT
        val frac = stroops % RaizConstants.USDC_STROOPS_PER_UNIT
        return String.format(Locale.US, "%d.%07d", whole, frac)
    }

    /**
     * String decimal Horizon (`"5"`, `"5.1"`, `"4.4422608"`) -> stroops (`Long`).
     * Más de 7 decimales se TRUNCAN (no redondean): `"0.00000001"` -> `0`.
     * `IllegalArgumentException` si `amount` no es un decimal no-negativo válido.
     */
    fun amountToStroops(amount: String): Long {
        val trimmed = amount.trim()
        require(trimmed.isNotEmpty()) { "amount vacío" }
        val parts = trimmed.split(".")
        require(parts.size in 1..2) { "amount inválido: $amount" }
        val wholeStr = parts[0]
        require(wholeStr.isNotEmpty() && wholeStr.all { it.isDigit() }) { "amount inválido: $amount" }
        val fracStr = parts.getOrNull(1) ?: ""
        require(parts.size == 1 || fracStr.isNotEmpty()) { "amount inválido: $amount" }
        require(fracStr.all { it.isDigit() }) { "amount inválido: $amount" }
        val whole = wholeStr.toLong()
        val fracTruncated = fracStr.take(RaizConstants.USDC_DECIMALS).padEnd(RaizConstants.USDC_DECIMALS, '0')
        return whole * RaizConstants.USDC_STROOPS_PER_UNIT + fracTruncated.toLong()
    }

    /**
     * Aplica slippage a la baja: `destStroops * (10_000 - bps) / 10_000`, entero, con
     * redondeo hacia abajo — es el `dest_min` que se pasa a
     * `PathPaymentStrictSendOperation` para tolerar que el pool se mueva entre la
     * cotización y el envío.
     */
    fun applySlippageBps(destStroops: Long, bps: Int): Long {
        require(bps in 0..RaizConstants.BPS_DENOMINATOR) { "bps fuera de rango: $bps" }
        return destStroops * (RaizConstants.BPS_DENOMINATOR - bps) / RaizConstants.BPS_DENOMINATOR
    }
}
