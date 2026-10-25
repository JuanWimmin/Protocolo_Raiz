package com.raiz.app.data.deferred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estimador §3.1 con los números de la spec: τ = 5 s, τ_min = 4,5 s, W(12 h) = 9 600; cierres reales de
 * 4,5 / 5 / 6 s a 1 h y 12 h; y la disposición del estimador (ancla, reinicio, antigüedad, salto de reloj).
 */
class LedgerMathTest {

    private val l0 = 5_384_000L
    private val h = 3_600_000L

    private fun realLedger(dtMs: Long, tauRealMs: Long) = l0 + dtMs / tauRealMs

    @Test
    fun `ventana para 12 h con tau_min 4,5 s es 9600`() {
        assertEquals(9_600L, LedgerMath.window(8_640L))
        assertEquals(9_600L, LedgerMath.WINDOW_12H)
        assertEquals(8_640L, LedgerMath.window(8_640L, 5_000L, 5_000L))
    }

    @Test
    fun `estimador exacto con cierre real de 5 s a 1 h y a 12 h`() {
        for (dt in listOf(h, 12 * h)) {
            val est = LedgerMath.estimate(l0, 0L, dt)
            assertEquals(realLedger(dt, 5_000L), est)
            assertEquals(9_600L, LedgerMath.expiration(l0, 0L, dt) - realLedger(dt, 5_000L))
        }
    }

    @Test
    fun `cierre real de 4,5 s subestima 80 y 960 ledgers y el sobre sigue vigente`() {
        val e1 = LedgerMath.estimate(l0, 0L, h)
        assertEquals(80L, realLedger(h, 4_500L) - e1)
        val e12 = LedgerMath.estimate(l0, 0L, 12 * h)
        assertEquals(960L, realLedger(12 * h, 4_500L) - e12)
        // Ventana efectiva: E − real = 9 600 − 960 = 8 640 ledgers ≈ 10,8 h a 4,5 s: sigue vigente.
        val exp = LedgerMath.expiration(l0, 0L, 12 * h)
        assertEquals(8_640L, exp - realLedger(12 * h, 4_500L))
        assertTrue(exp > realLedger(12 * h, 4_500L))
    }

    @Test
    fun `cierre real de 6 s sobreestima 120 y 1440 ledgers sin acortar la ventana`() {
        assertEquals(120L, LedgerMath.estimate(l0, 0L, h) - realLedger(h, 6_000L))
        assertEquals(1_440L, LedgerMath.estimate(l0, 0L, 12 * h) - realLedger(12 * h, 6_000L))
        assertEquals(11_040L, LedgerMath.expiration(l0, 0L, 12 * h) - realLedger(12 * h, 6_000L))
    }

    @Test
    fun `valido hasta en hora de pared es ahora mas 12 h usando tau_min`() {
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
        assertEquals(l0 + 720L + 9_600L, est.expiration())
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
        assertFalse(DeferredPolicy.canSign(est.readiness(), l0 + 9_600L))
        est.record(l0, null)
        val r = est.readiness()
        assertTrue(DeferredPolicy.canSign(r, l0 + 1))
        assertFalse(DeferredPolicy.canSign(r, l0))
    }
}
