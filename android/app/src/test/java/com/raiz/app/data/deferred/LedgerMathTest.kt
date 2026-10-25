package com.raiz.app.data.deferred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estimador §3.1 con la ventana del documento "Pago diferido por enjambre" (A2, paso 8):
 * W = T_des/τ_min + Δ_max·(1/τ_min − 1/τ) = 9 600 + 1 920 = 11 520 ledgers para 12 h, τ = 5 s, τ_min = 4,5 s y
 * hasta 24 h sin señal antes de firmar; cierres reales de 4,5 / 5 / 6 s a 1 h y 12 h; y la disposición del
 * estimador (ancla, reinicio, antigüedad, salto de reloj).
 */
class LedgerMathTest {

    private val l0 = 5_384_000L
    private val h = 3_600_000L

    private fun realLedger(dtMs: Long, tauRealMs: Long) = l0 + dtMs / tauRealMs

    @Test
    fun `ventana para 12 h es 11520 ledgers (9600 de ventana + 1920 de deriva en 24 h)`() {
        assertEquals(11_520L, LedgerMath.WINDOW_12H)
        assertEquals(9_600L, LedgerMath.window(12 * h, deltaMaxMs = 0L))
        assertEquals(8_640L, LedgerMath.window(12 * h, tauMs = 5_000L, tauMinMs = 5_000L, deltaMaxMs = 0L))
        assertEquals(1_920L, LedgerMath.window(0L))
    }

    @Test
    fun `peor caso del documento - red a 4,5 s tras 24 h sin senal deja exactamente 12 h de ventana`() {
        // e_max = 24 h · (1/4,5 − 1/5) = 1 920 ledgers; T_ef = (W − e_max) · 4,5 s = 43 200 s.
        val dt = 24 * h
        val eMax = realLedger(dt, 4_500L) - LedgerMath.estimate(l0, 0L, dt)
        assertEquals(1_920L, eMax)
        assertEquals(43_200_000L, (LedgerMath.WINDOW_12H - eMax) * 4_500L)
    }

    @Test
    fun `estimador exacto con cierre real de 5 s a 1 h y a 12 h`() {
        for (dt in listOf(h, 12 * h)) {
            val est = LedgerMath.estimate(l0, 0L, dt)
            assertEquals(realLedger(dt, 5_000L), est)
            assertEquals(11_520L, LedgerMath.expiration(l0, 0L, dt) - realLedger(dt, 5_000L))
        }
    }

    @Test
    fun `cierre real de 4,5 s subestima 80 y 960 ledgers y el sobre sigue vigente`() {
        assertEquals(80L, realLedger(h, 4_500L) - LedgerMath.estimate(l0, 0L, h))
        assertEquals(960L, realLedger(12 * h, 4_500L) - LedgerMath.estimate(l0, 0L, 12 * h))
        // Ventana efectiva: E − real = 11 520 − 960 = 10 560 ledgers ≈ 13,2 h a 4,5 s: sigue vigente.
        val exp = LedgerMath.expiration(l0, 0L, 12 * h)
        assertEquals(10_560L, exp - realLedger(12 * h, 4_500L))
        assertTrue(exp > realLedger(12 * h, 4_500L))
    }

    @Test
    fun `cierre real de 6 s sobreestima 120 y 1440 ledgers sin acortar la ventana`() {
        assertEquals(120L, LedgerMath.estimate(l0, 0L, h) - realLedger(h, 6_000L))
        assertEquals(1_440L, LedgerMath.estimate(l0, 0L, 12 * h) - realLedger(12 * h, 6_000L))
        assertEquals(12_960L, LedgerMath.expiration(l0, 0L, 12 * h) - realLedger(12 * h, 6_000L))
    }

    @Test
    fun `valido hasta en hora de pared es ahora mas 12 h (la ventana garantizada)`() {
        assertEquals(1_000L + 43_200_000L, LedgerMath.validUntilWallMs(1_000L))
    }

    @Test
    fun `estimate trunca hacia abajo tambien con deltas negativos`() {
        assertEquals(l0, LedgerMath.estimate(l0, 0L, 4_999L))
        assertEquals(l0 - 1, LedgerMath.estimate(l0, 0L, -1L))
        assertTrue(LedgerMath.isExpiredLocally(exp = 100L, estimate = 100L))
        assertFalse(LedgerMath.isExpiredLocally(exp = 101L, estimate = 100L))
    }

    // ── LedgerEstimator con reloj y almacén falsos ──────────────────────────

    private class FakeStorage : AnchorStorage {
        var anchor: LedgerAnchor? = null
        override fun loadAnchor() = anchor
        override fun saveAnchor(anchor: LedgerAnchor) { this.anchor = anchor }
    }

    private class Clocks(var mono: Long = 100_000L, var wall: Long = 1_761_400_000_000L, var boot: String = "7")

    private fun estimator(storage: FakeStorage, c: Clocks) =
        LedgerEstimator(storage, { c.mono }, { c.boot }, { c.wall })

    @Test
    fun `sin ancla, con otro boot_count o ancla de mas de 24 h el estimador no esta listo`() {
        val st = FakeStorage()
        val c = Clocks()
        val est = estimator(st, c)
        assertEquals(Readiness.NoLista(LedgerEstimator.MOTIVO_SIN_ANCLA), est.readiness())
        assertNull(est.estimate())

        est.record(l0, 1_761_400_000L)
        assertTrue(est.readiness() is Readiness.Lista)

        c.boot = "8"
        assertEquals(Readiness.NoLista(LedgerEstimator.MOTIVO_REINICIO), est.readiness())
        assertNull(est.anchor())

        c.boot = "7"
        c.mono += LedgerMath.MAX_ANCHOR_AGE_MS + 1
        c.wall += LedgerMath.MAX_ANCHOR_AGE_MS + 1
        assertEquals(Readiness.NoLista(LedgerEstimator.MOTIVO_ANTIGUA), est.readiness())
    }

    @Test
    fun `un salto del reloj de pared invalida el ancla pero no la estimacion monotonica`() {
        val st = FakeStorage()
        val c = Clocks()
        val est = estimator(st, c)
        est.record(l0, 1_761_400_000L)
        c.mono += h
        c.wall += h + LedgerMath.MAX_CLOCK_DRIFT_MS + 1
        assertEquals(Readiness.NoLista(LedgerEstimator.MOTIVO_RELOJ), est.readiness())
        c.wall -= LedgerMath.MAX_CLOCK_DRIFT_MS + 1
        val ready = est.readiness() as Readiness.Lista
        assertEquals(l0 + 720L, ready.estimate)
        assertEquals(l0 + 720L + 11_520L, est.expiration())
    }

    @Test
    fun `record conserva el maximo si un ledger llega atrasado y tras reinicio reemplaza el ancla`() {
        val st = FakeStorage()
        val c = Clocks()
        val est = estimator(st, c)
        est.record(l0, 1_761_400_000L)
        c.mono += 10_000L
        est.record(l0 - 5, null)
        assertEquals(l0, st.anchor!!.l0)
        assertEquals(100_000L, st.anchor!!.t0Ms)

        c.boot = "8"
        est.record(l0 - 5, null)
        assertEquals(l0 - 5, st.anchor!!.l0)
        assertEquals("8", st.anchor!!.bootId)
        assertEquals(c.wall / 1000L, st.anchor!!.closeTimeSec)
    }

    @Test
    fun `canSign exige estimacion y expiracion por delante`() {
        val st = FakeStorage()
        val c = Clocks()
        val est = estimator(st, c)
        assertFalse(DeferredPolicy.canSign(est.readiness(), l0 + 11_520L))
        est.record(l0, null)
        val r = est.readiness()
        assertTrue(DeferredPolicy.canSign(r, l0 + 1))
        assertFalse(DeferredPolicy.canSign(r, l0))
    }
}
