package com.raiz.app.data.stellar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM puros de [SwapMath] (WP3 stretch — conversión USDC-anchor → USDC-Blend).
 * Sin Android ni SDK Stellar: solo aritmética entera sobre stroops. Incluye la guarda de
 * precio de la conversión automática (97 %), el suelo duro de la manual (50 %) y el `dest_min`
 * de la automática.
 */
class SwapMathTest {

    // ── stroopsToAmount ─────────────────────────────────────────────────────

    @Test
    fun `stroopsToAmount 0 stroops`() {
        assertEquals("0.0000000", SwapMath.stroopsToAmount(0L))
    }

    @Test
    fun `stroopsToAmount 1 stroop`() {
        assertEquals("0.0000001", SwapMath.stroopsToAmount(1L))
    }

    @Test
    fun `stroopsToAmount 44422608 stroops (cotizacion real del pool)`() {
        assertEquals("4.4422608", SwapMath.stroopsToAmount(44_422_608L))
    }

    @Test
    fun `stroopsToAmount 50000000 stroops (5 USDC exactos)`() {
        assertEquals("5.0000000", SwapMath.stroopsToAmount(50_000_000L))
    }

    @Test
    fun `stroopsToAmount 12345678901 stroops (mas de un digito entero)`() {
        assertEquals("1234.5678901", SwapMath.stroopsToAmount(12_345_678_901L))
    }

    @Test
    fun `stroopsToAmount rechaza negativos`() {
        assertThrows(IllegalArgumentException::class.java) {
            SwapMath.stroopsToAmount(-1L)
        }
    }

    // ── amountToStroops ─────────────────────────────────────────────────────

    @Test
    fun `amountToStroops solo parte entera`() {
        assertEquals(50_000_000L, SwapMath.amountToStroops("5"))
    }

    @Test
    fun `amountToStroops un decimal`() {
        assertEquals(51_000_000L, SwapMath.amountToStroops("5.1"))
    }

    @Test
    fun `amountToStroops 7 decimales exactos`() {
        assertEquals(44_422_608L, SwapMath.amountToStroops("4.4422608"))
    }

    @Test
    fun `amountToStroops trunca mas de 7 decimales`() {
        // 8 decimales -> se descarta el octavo (trunca, no redondea) -> 0
        assertEquals(0L, SwapMath.amountToStroops("0.00000001"))
    }

    @Test
    fun `amountToStroops trunca mas de 7 decimales con parte entera no nula`() {
        // El octavo decimal (9) se descarta -> igual que "4.4422608"
        assertEquals(44_422_608L, SwapMath.amountToStroops("4.44226089"))
    }

    @Test
    fun `amountToStroops rechaza texto invalido`() {
        assertThrows(IllegalArgumentException::class.java) {
            SwapMath.amountToStroops("abc")
        }
    }

    @Test
    fun `amountToStroops rechaza vacio`() {
        assertThrows(IllegalArgumentException::class.java) {
            SwapMath.amountToStroops("")
        }
    }

    @Test
    fun `amountToStroops rechaza mas de un punto decimal`() {
        assertThrows(IllegalArgumentException::class.java) {
            SwapMath.amountToStroops("1.2.3")
        }
    }

    // ── applySlippageBps ────────────────────────────────────────────────────

    @Test
    fun `applySlippageBps 100 bps sobre la cotizacion real del pool`() {
        assertEquals(43_978_381L, SwapMath.applySlippageBps(44_422_608L, 100))
    }

    @Test
    fun `applySlippageBps sobre 0`() {
        assertEquals(0L, SwapMath.applySlippageBps(0L, 100))
    }

    @Test
    fun `applySlippageBps con 0 bps es identidad`() {
        assertEquals(44_422_608L, SwapMath.applySlippageBps(44_422_608L, 0))
    }

    @Test
    fun `applySlippageBps rechaza bps fuera de rango`() {
        assertThrows(IllegalArgumentException::class.java) {
            SwapMath.applySlippageBps(44_422_608L, 10_001)
        }
    }

    // ── ida y vuelta stroops <-> amount ─────────────────────────────────────

    @Test
    fun `ida y vuelta stroops-amount-stroops preserva el valor`() {
        val valores = listOf(0L, 1L, 7L, 44_422_608L, 50_000_000L, 12_345_678_901L, 1L)
        for (stroops in valores) {
            val amount = SwapMath.stroopsToAmount(stroops)
            assertEquals(stroops, SwapMath.amountToStroops(amount))
        }
    }

    // ── guarda de precio de la conversión automática (97 %) ─────────────────

    @Test
    fun `meetsAutoConvertGuard pasa justo en el 97 por ciento`() {
        assertTrue(SwapMath.meetsAutoConvertGuard(sendStroops = 10_000_000L, destStroops = 9_700_000L))
    }

    @Test
    fun `meetsAutoConvertGuard no pasa con un stroop menos del 97 por ciento`() {
        assertFalse(SwapMath.meetsAutoConvertGuard(sendStroops = 10_000_000L, destStroops = 9_699_999L))
    }

    @Test
    fun `meetsAutoConvertGuard con las cotizaciones reales del pool`() {
        // 27-sep: 5 → 4,4422608 (88,8 %) — NO debe convertirse sola.
        assertFalse(SwapMath.meetsAutoConvertGuard(50_000_000L, 44_422_608L))
        // 3-oct: 5 → 5,0464711 · 4-oct: 4,5 → 4,5429362 y 4,5 → 4,5229258 (≥ 100 %).
        assertTrue(SwapMath.meetsAutoConvertGuard(50_000_000L, 50_464_711L))
        assertTrue(SwapMath.meetsAutoConvertGuard(45_000_000L, 45_429_362L))
        assertTrue(SwapMath.meetsAutoConvertGuard(45_000_000L, 45_229_258L))
        // Pool vaciado del 3-oct: 5 → ≈ 0,15 (3 %).
        assertFalse(SwapMath.meetsAutoConvertGuard(50_000_000L, 1_500_000L))
    }

    @Test
    fun `meetsAutoConvertGuard sin nada que enviar no pasa`() {
        assertFalse(SwapMath.meetsAutoConvertGuard(sendStroops = 0L, destStroops = 0L))
        assertFalse(SwapMath.meetsAutoConvertGuard(sendStroops = 0L, destStroops = 10_000_000L))
        assertFalse(SwapMath.meetsAutoConvertGuard(sendStroops = -1L, destStroops = 10_000_000L))
        assertFalse(SwapMath.meetsAutoConvertGuard(sendStroops = 10_000_000L, destStroops = -1L))
    }

    @Test
    fun `meetsAutoConvertGuard pasa si se recibe mas de lo enviado`() {
        assertTrue(SwapMath.meetsAutoConvertGuard(sendStroops = 10_000_000L, destStroops = 10_000_001L))
    }

    @Test
    fun `meetsAutoConvertGuard no desborda con montos enormes`() {
        // dest × 10 000 no cabe en un Long: la comparación debe seguir siendo exacta.
        assertTrue(SwapMath.meetsAutoConvertGuard(Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(SwapMath.meetsAutoConvertGuard(Long.MAX_VALUE, Long.MAX_VALUE - 1L))
        assertFalse(SwapMath.meetsAutoConvertGuard(Long.MAX_VALUE, Long.MAX_VALUE / 2L))
    }

    @Test
    fun `meetsAutoConvertGuard respeta un umbral distinto`() {
        assertFalse(SwapMath.meetsAutoConvertGuard(10_000_000L, 9_900_000L, minRatioBps = 9_950))
        assertTrue(SwapMath.meetsAutoConvertGuard(10_000_000L, 9_950_000L, minRatioBps = 9_950))
    }

    // ── quoteGuard (97 % automático · 50 % suelo duro manual) ───────────────

    @Test
    fun `quoteGuard OK desde el 97 por ciento`() {
        assertEquals(SwapMath.QuoteGuard.OK, SwapMath.quoteGuard(10_000_000L, 9_700_000L))
        assertEquals(SwapMath.QuoteGuard.OK, SwapMath.quoteGuard(45_000_000L, 45_229_258L))
    }

    @Test
    fun `quoteGuard BELOW_AUTO entre el 50 y el 97 por ciento`() {
        assertEquals(SwapMath.QuoteGuard.BELOW_AUTO, SwapMath.quoteGuard(10_000_000L, 9_699_999L))
        assertEquals(SwapMath.QuoteGuard.BELOW_AUTO, SwapMath.quoteGuard(50_000_000L, 44_422_608L))
        assertEquals(SwapMath.QuoteGuard.BELOW_AUTO, SwapMath.quoteGuard(10_000_000L, 5_000_000L))
    }

    @Test
    fun `quoteGuard BELOW_HARD_FLOOR por debajo del 50 por ciento`() {
        assertEquals(SwapMath.QuoteGuard.BELOW_HARD_FLOOR, SwapMath.quoteGuard(10_000_000L, 4_999_999L))
        assertEquals(SwapMath.QuoteGuard.BELOW_HARD_FLOOR, SwapMath.quoteGuard(50_000_000L, 1_500_000L))
        assertEquals(SwapMath.QuoteGuard.BELOW_HARD_FLOOR, SwapMath.quoteGuard(10_000_000L, 0L))
        assertEquals(SwapMath.QuoteGuard.BELOW_HARD_FLOOR, SwapMath.quoteGuard(0L, 10_000_000L))
    }

    // ── ratioBps (solo para mostrar el aviso) ───────────────────────────────

    @Test
    fun `ratioBps trunca`() {
        // 44 422 608 / 50 000 000 = 0,88845216 → 8884 bps (no 8885).
        assertEquals(8_884, SwapMath.ratioBps(50_000_000L, 44_422_608L))
        assertEquals(10_050, SwapMath.ratioBps(45_000_000L, 45_229_258L))
        assertEquals(10_000, SwapMath.ratioBps(10_000_000L, 10_000_000L))
        assertEquals(300, SwapMath.ratioBps(50_000_000L, 1_500_000L))
    }

    @Test
    fun `ratioBps sin nada que enviar o recibir es 0 y satura sin desbordar`() {
        assertEquals(0, SwapMath.ratioBps(0L, 10_000_000L))
        assertEquals(0, SwapMath.ratioBps(10_000_000L, 0L))
        assertEquals(Int.MAX_VALUE, SwapMath.ratioBps(1L, Long.MAX_VALUE))
    }

    // ── autoDestMin (dest_min de la conversión automática) ──────────────────

    @Test
    fun `autoDestMin usa la cotizacion menos 1 por ciento cuando supera el 97 por ciento de lo enviado`() {
        // Conversión real del 4-oct: 4,5 → 4,5229258; dest_min 4,4776965 (README D3).
        assertEquals(44_776_965L, SwapMath.autoDestMin(45_000_000L, 45_229_258L))
    }

    @Test
    fun `autoDestMin nunca baja del 97 por ciento de lo enviado`() {
        // Cotización justo en la guarda: cotización − 1 % sería 96,03 %; el suelo duro manda.
        assertEquals(9_700_000L, SwapMath.autoDestMin(10_000_000L, 9_700_000L))
        assertEquals(9_700_000L, SwapMath.autoDestMin(10_000_000L, 9_750_000L))
    }

    @Test
    fun `autoDestMin redondea el 97 por ciento hacia arriba`() {
        // 1 000 001 × 0,97 = 970 000,97 → 970 001 (hacia abajo dejaría pasar un stroop de menos).
        assertEquals(970_001L, SwapMath.autoDestMin(1_000_001L, 970_001L))
    }

    @Test
    fun `autoDestMin nunca supera lo cotizado si la cotizacion pasa la guarda`() {
        val casos = listOf(
            10_000_000L to 9_700_000L,
            10_000_000L to 9_700_001L,
            1_000_001L to 970_001L,
            45_000_000L to 45_229_258L,
            50_000_000L to 50_464_711L,
            7L to 7L,
            1L to 1L,
        )
        for ((send, dest) in casos) {
            assertTrue("la cotización $send → $dest debe pasar la guarda", SwapMath.meetsAutoConvertGuard(send, dest))
            val destMin = SwapMath.autoDestMin(send, dest)
            assertTrue("dest_min $destMin > cotización $dest", destMin <= dest)
            assertTrue("dest_min $destMin < 97 % de $send", SwapMath.meetsAutoConvertGuard(send, destMin))
        }
    }

    @Test
    fun `autoDestMin de cero es cero y rechaza negativos`() {
        assertEquals(0L, SwapMath.autoDestMin(0L, 0L))
        assertThrows(IllegalArgumentException::class.java) {
            SwapMath.autoDestMin(-1L, 10L)
        }
    }

    // ── manualDestMin (dest_min de la conversión manual) ────────────────────

    @Test
    fun `manualDestMin usa la cotizacion menos 1 por ciento en una cotizacion normal`() {
        // 4,5 → 4,5229258: manda el 1 % (44 776 965); el 50 % de lo enviado queda muy por debajo.
        assertEquals(44_776_965L, SwapMath.manualDestMin(45_000_000L, 45_229_258L))
        // La misma tolerancia que ya usaba la conversión manual (cotización real del 27-sep, 88,8 %).
        assertEquals(
            SwapMath.applySlippageBps(44_422_608L, SwapMath.SLIPPAGE_BPS),
            SwapMath.manualDestMin(50_000_000L, 44_422_608L),
        )
    }

    @Test
    fun `manualDestMin nunca baja del 50 por ciento de lo enviado`() {
        // Cotización justo en el 50 %: sin el suelo, el 1 % la dejaría ejecutarse al 49,5 %.
        assertEquals(5_000_000L, SwapMath.manualDestMin(10_000_000L, 5_000_000L))
        assertEquals(5_000_000L, SwapMath.manualDestMin(10_000_000L, 5_040_000L))
        // 50 % de un monto impar, redondeado hacia arriba.
        assertEquals(500_001L, SwapMath.manualDestMin(1_000_001L, 500_001L))
    }

    @Test
    fun `manualDestMin nunca supera lo cotizado si la cotizacion no esta bajo el suelo duro`() {
        val casos = listOf(
            10_000_000L to 5_000_000L,
            1_000_001L to 500_001L,
            45_000_000L to 40_000_000L,
            1L to 1L,
        )
        for ((send, dest) in casos) {
            assertTrue(
                "la cotización $send → $dest no debe estar bajo el suelo duro",
                SwapMath.quoteGuard(send, dest) != SwapMath.QuoteGuard.BELOW_HARD_FLOOR,
            )
            val destMin = SwapMath.manualDestMin(send, dest)
            assertTrue("dest_min $destMin > cotización $dest", destMin <= dest)
            assertTrue(
                "dest_min $destMin < 50 % de $send",
                SwapMath.meetsRatio(send, destMin, SwapMath.MANUAL_CONVERT_MIN_RATIO_BPS),
            )
        }
    }

    @Test
    fun `manualDestMin de cero es cero y rechaza negativos`() {
        assertEquals(0L, SwapMath.manualDestMin(0L, 0L))
        assertThrows(IllegalArgumentException::class.java) {
            SwapMath.manualDestMin(10L, -1L)
        }
    }
}
