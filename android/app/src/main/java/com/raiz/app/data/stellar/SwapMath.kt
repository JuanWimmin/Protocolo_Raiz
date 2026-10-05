package com.raiz.app.data.stellar

import com.raiz.app.data.model.RaizConstants
import java.math.BigInteger
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
     * Guarda de precio de la conversión AUTOMÁTICA: solo se convierte sin que el usuario lo
     * pida si la cotización entrega al menos el 97 % de lo enviado (9 700 bps). El precio lo
     * pone un pool de liquidez de testnet que RAÍZ no controla: el 27-sep cotizó al 88,9 % y
     * el 3-oct llegó a vaciarse (≈ 3 %); los días normales ronda el 100,5–101 %.
     */
    const val AUTO_CONVERT_MIN_RATIO_BPS = 9_700

    /**
     * Suelo duro de la conversión MANUAL: por debajo del 50 % de lo enviado ni siquiera se
     * ofrece "Convertir de todos modos" — un pool vaciado no debe poder aceptarse con un tap.
     */
    const val MANUAL_CONVERT_MIN_RATIO_BPS = 5_000

    /** Dónde cae una cotización respecto a las dos guardas de precio. */
    enum class QuoteGuard {
        /** ≥ 97 %: se puede convertir sola. */
        OK,

        /** Entre el 50 % y el 97 %: solo a mano, con aviso. */
        BELOW_AUTO,

        /** < 50 %: no se ofrece convertir, solo volver a cotizar. */
        BELOW_HARD_FLOOR,
    }

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

    /**
     * ¿Lo cotizado (`destStroops`) es al menos `minRatioBps` de lo enviado (`sendStroops`)?
     * Compara por multiplicación cruzada (`dest × 10 000 ≥ send × minRatioBps`), sin dividir:
     * no hay redondeo que pueda dejar pasar un stroop de menos. `BigInteger` para que ningún
     * monto desborde. Sin nada que enviar (`send ≤ 0`) o con un recibido negativo no pasa.
     */
    fun meetsRatio(sendStroops: Long, destStroops: Long, minRatioBps: Int): Boolean {
        require(minRatioBps >= 0) { "minRatioBps no puede ser negativo: $minRatioBps" }
        if (sendStroops <= 0L || destStroops < 0L) return false
        val left = BigInteger.valueOf(destStroops).multiply(BPS)
        val right = BigInteger.valueOf(sendStroops).multiply(BigInteger.valueOf(minRatioBps.toLong()))
        return left >= right
    }

    /** Guarda de la conversión automática: recibir ≥ 97 % de lo enviado (o el umbral dado). */
    fun meetsAutoConvertGuard(
        sendStroops: Long,
        destStroops: Long,
        minRatioBps: Int = AUTO_CONVERT_MIN_RATIO_BPS,
    ): Boolean = meetsRatio(sendStroops, destStroops, minRatioBps)

    /** Clasifica una cotización frente a la guarda automática (97 %) y el suelo duro (50 %). */
    fun quoteGuard(sendStroops: Long, destStroops: Long): QuoteGuard = when {
        meetsRatio(sendStroops, destStroops, AUTO_CONVERT_MIN_RATIO_BPS) -> QuoteGuard.OK
        meetsRatio(sendStroops, destStroops, MANUAL_CONVERT_MIN_RATIO_BPS) -> QuoteGuard.BELOW_AUTO
        else -> QuoteGuard.BELOW_HARD_FLOOR
    }

    /**
     * Relación recibido/enviado en basis points, truncada (`4,4422608 / 5` → `8884`). Solo para
     * MOSTRAR el aviso ("recibirías el 88 %"); las decisiones usan [meetsRatio], que no divide.
     * `0` si no hay nada que enviar; satura en `Int.MAX_VALUE`.
     */
    fun ratioBps(sendStroops: Long, destStroops: Long): Int {
        if (sendStroops <= 0L || destStroops <= 0L) return 0
        val ratio = BigInteger.valueOf(destStroops).multiply(BPS).divide(BigInteger.valueOf(sendStroops))
        return if (ratio > INT_MAX) Int.MAX_VALUE else ratio.toInt()
    }

    /**
     * `dest_min` de la conversión AUTOMÁTICA: el mayor entre la cotización − 1 %
     * ([applySlippageBps]) y el 97 % de lo enviado redondeado HACIA ARRIBA, de modo que el 97 %
     * sea un suelo duro y no "97 % menos la tolerancia" (que dejaría pasar hasta el 96,03 %).
     * Si la cotización pasa la guarda, este mínimo nunca supera lo cotizado. La conversión
     * manual sigue usando solo la cotización − 1 %.
     */
    fun autoDestMin(sendStroops: Long, destStroops: Long): Long {
        require(sendStroops >= 0L && destStroops >= 0L) { "montos negativos: $sendStroops / $destStroops" }
        return maxOf(applySlippageBps(destStroops, SLIPPAGE_BPS), ceilRatio(sendStroops, AUTO_CONVERT_MIN_RATIO_BPS))
    }

    /**
     * `dest_min` de la conversión MANUAL: la cotización − 1 % ([applySlippageBps]), pero nunca
     * por debajo del suelo duro (50 % de lo enviado, redondeado HACIA ARRIBA). Sin él, una
     * cotización justo en el 50 % — la peor que la app deja aceptar con un tap — podría
     * ejecutarse al 49,5 %.
     */
    fun manualDestMin(sendStroops: Long, destStroops: Long): Long {
        require(sendStroops >= 0L && destStroops >= 0L) { "montos negativos: $sendStroops / $destStroops" }
        return maxOf(applySlippageBps(destStroops, SLIPPAGE_BPS), ceilRatio(sendStroops, MANUAL_CONVERT_MIN_RATIO_BPS))
    }

    /** `ceil(sendStroops × bps / 10 000)` en enteros y sin desborde. */
    private fun ceilRatio(sendStroops: Long, bps: Int): Long =
        BigInteger.valueOf(sendStroops)
            .multiply(BigInteger.valueOf(bps.toLong()))
            .add(BPS.subtract(BigInteger.ONE))
            .divide(BPS)
            .toLong()

    private val BPS: BigInteger = BigInteger.valueOf(RaizConstants.BPS_DENOMINATOR.toLong())
    private val INT_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())
}
