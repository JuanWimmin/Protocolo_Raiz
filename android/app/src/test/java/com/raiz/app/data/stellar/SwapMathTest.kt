package com.raiz.app.data.stellar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests JVM puros de [SwapMath] (WP3 stretch — conversión USDC-anchor → USDC-Blend).
 * Sin Android ni SDK Stellar: solo aritmética entera sobre stroops.
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
}
