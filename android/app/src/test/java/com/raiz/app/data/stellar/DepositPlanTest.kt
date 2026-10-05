package com.raiz.app.data.stellar

import com.raiz.app.data.stellar.DepositPlan.Leg
import com.raiz.app.data.stellar.DepositPlan.SetupStep
import com.raiz.app.data.stellar.DepositPlan.Trigger
import com.raiz.app.data.stellar.DepositPlan.TxStatus
import com.raiz.app.data.stellar.DepositPlan.Verdict
import com.raiz.app.data.stellar.SwapMath.QuoteGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM puros de [DepositPlan]: las decisiones del depósito SEP-24 de punta a punta
 * (qué tramo falta, cuándo se convierte sola, qué hacer con una transacción en vuelo, a
 * dónde se puede enviar). Sin Android, red ni SDK Stellar.
 */
class DepositPlanTest {

    // ── nextLeg: tramo pendiente según los saldos de la cuenta operativa ────

    @Test
    fun `nextLeg semilla sin saldo del anchor no tiene nada pendiente`() {
        assertEquals(Leg.NONE, DepositPlan.nextLeg(needsForward = false, anchorStroops = 0L, fundStroops = 0L))
    }

    @Test
    fun `nextLeg semilla con saldo del anchor convierte`() {
        assertEquals(Leg.CONVERT, DepositPlan.nextLeg(needsForward = false, anchorStroops = 45_000_000L, fundStroops = 0L))
    }

    @Test
    fun `nextLeg semilla nunca reenvia aunque tenga USDC del fondo`() {
        // En semilla el USDC del fondo ya está en la wallet del usuario: no es "pendiente".
        assertEquals(Leg.NONE, DepositPlan.nextLeg(needsForward = false, anchorStroops = 0L, fundStroops = 245_229_258L))
        assertEquals(
            Leg.CONVERT,
            DepositPlan.nextLeg(needsForward = false, anchorStroops = 45_000_000L, fundStroops = 245_229_258L),
        )
    }

    @Test
    fun `nextLeg passkey sin saldos no tiene nada pendiente`() {
        assertEquals(Leg.NONE, DepositPlan.nextLeg(needsForward = true, anchorStroops = 0L, fundStroops = 0L))
    }

    @Test
    fun `nextLeg passkey con USDC del anchor convierte`() {
        assertEquals(Leg.CONVERT, DepositPlan.nextLeg(needsForward = true, anchorStroops = 45_000_000L, fundStroops = 0L))
    }

    @Test
    fun `nextLeg passkey con USDC del fondo lo envia a la wallet`() {
        assertEquals(Leg.FORWARD, DepositPlan.nextLeg(needsForward = true, anchorStroops = 0L, fundStroops = 45_229_258L))
    }

    @Test
    fun `nextLeg passkey con los dos saldos envia primero`() {
        // El envío no tiene precio en juego: no debe quedar rehén de la guarda de la conversión.
        assertEquals(
            Leg.FORWARD,
            DepositPlan.nextLeg(needsForward = true, anchorStroops = 45_000_000L, fundStroops = 45_229_258L),
        )
    }

    @Test
    fun `nextLeg ignora saldos negativos o de un stroop con sentido`() {
        assertEquals(Leg.NONE, DepositPlan.nextLeg(needsForward = true, anchorStroops = -1L, fundStroops = -1L))
        assertEquals(Leg.FORWARD, DepositPlan.nextLeg(needsForward = true, anchorStroops = 0L, fundStroops = 1L))
        assertEquals(Leg.CONVERT, DepositPlan.nextLeg(needsForward = false, anchorStroops = 1L, fundStroops = 0L))
    }

    // ── shouldConvert: disparador × tipo de wallet × guarda de precio ───────

    @Test
    fun `shouldConvert tras un deposito convierte sola solo si pasa la guarda`() {
        for (needsForward in listOf(false, true)) {
            assertTrue(DepositPlan.shouldConvert(Trigger.AFTER_DEPOSIT, needsForward, QuoteGuard.OK))
            assertFalse(DepositPlan.shouldConvert(Trigger.AFTER_DEPOSIT, needsForward, QuoteGuard.BELOW_AUTO))
            assertFalse(DepositPlan.shouldConvert(Trigger.AFTER_DEPOSIT, needsForward, QuoteGuard.BELOW_HARD_FLOOR))
        }
    }

    @Test
    fun `shouldConvert al abrir la pantalla en semilla solo ofrece`() {
        assertFalse(DepositPlan.shouldConvert(Trigger.ON_OPEN, needsForward = false, guard = QuoteGuard.OK))
        assertFalse(DepositPlan.shouldConvert(Trigger.ON_OPEN, needsForward = false, guard = QuoteGuard.BELOW_AUTO))
        assertFalse(DepositPlan.shouldConvert(Trigger.ON_OPEN, needsForward = false, guard = QuoteGuard.BELOW_HARD_FLOOR))
    }

    @Test
    fun `shouldConvert al abrir la pantalla en passkey termina sola si pasa la guarda`() {
        assertTrue(DepositPlan.shouldConvert(Trigger.ON_OPEN, needsForward = true, guard = QuoteGuard.OK))
        assertFalse(DepositPlan.shouldConvert(Trigger.ON_OPEN, needsForward = true, guard = QuoteGuard.BELOW_AUTO))
        assertFalse(DepositPlan.shouldConvert(Trigger.ON_OPEN, needsForward = true, guard = QuoteGuard.BELOW_HARD_FLOOR))
    }

    @Test
    fun `shouldConvert a mano acepta todo menos el suelo duro`() {
        for (needsForward in listOf(false, true)) {
            assertTrue(DepositPlan.shouldConvert(Trigger.MANUAL, needsForward, QuoteGuard.OK))
            assertTrue(DepositPlan.shouldConvert(Trigger.MANUAL, needsForward, QuoteGuard.BELOW_AUTO))
            // Por debajo del 50 % ni siquiera un tap del usuario convierte.
            assertFalse(DepositPlan.shouldConvert(Trigger.MANUAL, needsForward, QuoteGuard.BELOW_HARD_FLOOR))
        }
    }

    // ── setupSteps: qué le falta a la cuenta de depósito ────────────────────

    @Test
    fun `setupSteps cuenta inexistente necesita friendbot y las dos trustlines en orden`() {
        assertEquals(
            listOf(SetupStep.FRIENDBOT, SetupStep.TRUST_ANCHOR, SetupStep.TRUST_FUND),
            DepositPlan.setupSteps(null),
        )
    }

    @Test
    fun `setupSteps cuenta recien fondeada necesita las dos trustlines`() {
        assertEquals(
            listOf(SetupStep.TRUST_ANCHOR, SetupStep.TRUST_FUND),
            DepositPlan.setupSteps(snapshot(anchor = null, fund = null)),
        )
    }

    @Test
    fun `setupSteps solo devuelve la trustline que falta`() {
        assertEquals(listOf(SetupStep.TRUST_FUND), DepositPlan.setupSteps(snapshot(anchor = 0L, fund = null)))
        assertEquals(listOf(SetupStep.TRUST_ANCHOR), DepositPlan.setupSteps(snapshot(anchor = null, fund = 0L)))
    }

    @Test
    fun `setupSteps cuenta completa no necesita nada`() {
        assertEquals(emptyList<SetupStep>(), DepositPlan.setupSteps(snapshot(anchor = 0L, fund = 0L)))
        assertEquals(emptyList<SetupStep>(), DepositPlan.setupSteps(snapshot(anchor = 45_000_000L, fund = 7L)))
    }

    // ── pendingVerdict: qué hacer con la transacción en vuelo ───────────────

    @Test
    fun `pendingVerdict SUCCESS es aplicada sin importar el reloj`() {
        assertEquals(Verdict.APPLIED, DepositPlan.pendingVerdict(TxStatus.SUCCESS, nowSec = 0L, validUntilSec = 1_000L))
        assertEquals(Verdict.APPLIED, DepositPlan.pendingVerdict(TxStatus.SUCCESS, nowSec = 9_999L, validUntilSec = 1_000L))
    }

    @Test
    fun `pendingVerdict FAILED permite reintentar (entro y fallo, es definitivo)`() {
        assertEquals(Verdict.SAFE_TO_RETRY, DepositPlan.pendingVerdict(TxStatus.FAILED, nowSec = 0L, validUntilSec = 1_000L))
    }

    @Test
    fun `pendingVerdict NOT_FOUND espera mientras la tx pueda entrar todavia`() {
        val validUntil = 1_000L
        // Antes de vencer, justo al vencer y dentro del margen (60 s): NO se construye otra.
        assertEquals(Verdict.KEEP_WAITING, DepositPlan.pendingVerdict(TxStatus.NOT_FOUND, 940L, validUntil))
        assertEquals(Verdict.KEEP_WAITING, DepositPlan.pendingVerdict(TxStatus.NOT_FOUND, 1_000L, validUntil))
        assertEquals(Verdict.KEEP_WAITING, DepositPlan.pendingVerdict(TxStatus.NOT_FOUND, 1_060L, validUntil))
        // Pasado vigencia + margen ya no puede entrar.
        assertEquals(Verdict.SAFE_TO_RETRY, DepositPlan.pendingVerdict(TxStatus.NOT_FOUND, 1_061L, validUntil))
    }

    @Test
    fun `pendingVerdict UNKNOWN se trata igual que NOT_FOUND`() {
        val validUntil = 1_000L
        assertEquals(Verdict.KEEP_WAITING, DepositPlan.pendingVerdict(TxStatus.UNKNOWN, 1_060L, validUntil))
        assertEquals(Verdict.SAFE_TO_RETRY, DepositPlan.pendingVerdict(TxStatus.UNKNOWN, 1_061L, validUntil))
    }

    @Test
    fun `pendingVerdict respeta un margen distinto`() {
        assertEquals(
            Verdict.KEEP_WAITING,
            DepositPlan.pendingVerdict(TxStatus.NOT_FOUND, nowSec = 1_015L, validUntilSec = 1_000L, marginSec = 15L),
        )
        assertEquals(
            Verdict.SAFE_TO_RETRY,
            DepositPlan.pendingVerdict(TxStatus.NOT_FOUND, nowSec = 1_016L, validUntilSec = 1_000L, marginSec = 15L),
        )
    }

    // ── isSnapshotFresh: ¿la foto ya refleja la tx propia? ──────────────────

    @Test
    fun `isSnapshotFresh exige la secuencia de la tx o una posterior`() {
        assertFalse(DepositPlan.isSnapshotFresh(snapshotSequence = 99L, minSequence = 100L))
        assertTrue(DepositPlan.isSnapshotFresh(snapshotSequence = 100L, minSequence = 100L))
        assertTrue(DepositPlan.isSnapshotFresh(snapshotSequence = 101L, minSequence = 100L))
        assertTrue(DepositPlan.isSnapshotFresh(snapshotSequence = 5L, minSequence = 0L))
    }

    // ── Destino del envío final ─────────────────────────────────────────────

    @Test
    fun `isContractAddress acepta solo un C de 56 caracteres base32`() {
        assertTrue(DepositPlan.isContractAddress(CONTRACT))
        assertFalse(DepositPlan.isContractAddress(null))
        assertFalse(DepositPlan.isContractAddress(""))
        assertFalse(DepositPlan.isContractAddress(ACCOUNT)) // G…: una cuenta clásica no es un smart account
        assertFalse(DepositPlan.isContractAddress(CONTRACT.dropLast(1))) // 55 caracteres
        assertFalse(DepositPlan.isContractAddress(CONTRACT + "A")) // 57 caracteres
        assertFalse(DepositPlan.isContractAddress(CONTRACT.lowercase()))
        assertFalse(DepositPlan.isContractAddress(CONTRACT.dropLast(1) + "1")) // '1' no es base32
        assertFalse(DepositPlan.isContractAddress(CONTRACT.dropLast(1) + "8")) // '8' no es base32
    }

    @Test
    fun `isValidForwardDestination exige el smart account guardado en el dispositivo`() {
        assertTrue(DepositPlan.isValidForwardDestination(CONTRACT, storedOwner = CONTRACT))
        // Otro contrato válido que no es el de la wallet activa.
        assertFalse(DepositPlan.isValidForwardDestination(OTHER_CONTRACT, storedOwner = CONTRACT))
        // Sin wallet passkey guardada no hay destino válido.
        assertFalse(DepositPlan.isValidForwardDestination(CONTRACT, storedOwner = null))
        // Una G… nunca es destino del envío final, aunque coincida con lo guardado.
        assertFalse(DepositPlan.isValidForwardDestination(ACCOUNT, storedOwner = ACCOUNT))
        assertFalse(DepositPlan.isValidForwardDestination("", storedOwner = ""))
        assertFalse(DepositPlan.isValidForwardDestination(null, storedOwner = null))
    }

    // ── Marca "depósito iniciado" y XLM mínimo ──────────────────────────────

    @Test
    fun `isStartedMarkerFresh dura 15 minutos`() {
        val startedAt = 10_000L
        assertFalse(DepositPlan.isStartedMarkerFresh(null, nowSec = startedAt))
        assertTrue(DepositPlan.isStartedMarkerFresh(startedAt, nowSec = startedAt))
        assertTrue(DepositPlan.isStartedMarkerFresh(startedAt, nowSec = startedAt + 900L))
        assertFalse(DepositPlan.isStartedMarkerFresh(startedAt, nowSec = startedAt + 901L))
        // Reloj atrasado respecto a la marca: no se da por vigente.
        assertFalse(DepositPlan.isStartedMarkerFresh(startedAt, nowSec = startedAt - 1L))
    }

    @Test
    fun `hasEnoughXlm pide al menos 5 XLM`() {
        assertTrue(DepositPlan.hasEnoughXlm(50_000_000L))
        assertTrue(DepositPlan.hasEnoughXlm(99_999_117_785L))
        assertFalse(DepositPlan.hasEnoughXlm(49_999_999L))
        assertFalse(DepositPlan.hasEnoughXlm(0L))
    }

    // ── PendingDepositTx: diario de la tx en vuelo ──────────────────────────

    @Test
    fun `PendingDepositTx ida y vuelta de una conversion y de un envio`() {
        val convert = PendingDepositTx(Leg.CONVERT, HASH, amountStroops = 45_000_000L, validUntilSec = 1_791_141_307L, sequence = 21_525_371_065_204_742L)
        val forward = PendingDepositTx(Leg.FORWARD, HASH.uppercase(), amountStroops = 45_229_258L, validUntilSec = 1_791_141_337L, sequence = 0L)
        assertEquals(convert, PendingDepositTx.decode(convert.encode()))
        assertEquals(forward, PendingDepositTx.decode(forward.encode()))
        assertEquals("CONVERT|$HASH|45000000|1791141307|21525371065204742", convert.encode())
    }

    @Test
    fun `PendingDepositTx decode devuelve null con texto vacio o corrupto y nunca lanza`() {
        assertNull(PendingDepositTx.decode(null))
        assertNull(PendingDepositTx.decode(""))
        assertNull(PendingDepositTx.decode("   "))
        assertNull(PendingDepositTx.decode("basura"))
        assertNull(PendingDepositTx.decode("CONVERT|$HASH|45000000|1791141307")) // falta la secuencia
        assertNull(PendingDepositTx.decode("CONVERT|$HASH|45000000|1791141307|1|extra"))
        assertNull(PendingDepositTx.decode("NONE|$HASH|45000000|1791141307|1")) // NONE no es un tramo que se envíe
        assertNull(PendingDepositTx.decode("SWAP|$HASH|45000000|1791141307|1")) // tipo desconocido
        assertNull(PendingDepositTx.decode("CONVERT|${HASH.dropLast(1)}|45000000|1791141307|1")) // hash de 63
        assertNull(PendingDepositTx.decode("CONVERT|${HASH.dropLast(1)}z|45000000|1791141307|1")) // hash no hex
        assertNull(PendingDepositTx.decode("CONVERT|$HASH|-1|1791141307|1")) // monto negativo
        assertNull(PendingDepositTx.decode("CONVERT|$HASH|4.5|1791141307|1")) // monto no entero
        assertNull(PendingDepositTx.decode("CONVERT|$HASH|45000000|0|1")) // sin vigencia
        assertNull(PendingDepositTx.decode("CONVERT|$HASH|45000000|1791141307|-5")) // secuencia negativa
    }

    private fun snapshot(anchor: Long?, fund: Long?) = ClassicAccountSnapshot(
        sequence = 1L,
        xlmStroops = 99_999_117_785L,
        anchorUsdcStroops = anchor,
        fundUsdcStroops = fund,
    )

    private companion object {
        /** Contratos reales de testnet (Pool y Governance de RAÍZ): solo como strkeys `C…` válidas. */
        const val CONTRACT = "CD775D33SPEO3BTAZIEQTQGN6HERTR5YNEQOZWWKXLDKLJ2B34LCKBE2"
        const val OTHER_CONTRACT = "CBBYI45J3VWQ53QATRWTARCFWNIG7EEZTFCS5OXJWS7KRCPOHQXHAL32"

        /** Cuenta clásica pública (issuer del USDC del anchor de prueba). */
        const val ACCOUNT = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"

        /** Hash real de una conversión documentada en la evidencia de D3. */
        const val HASH = "23e926f4a24981e4e2f524221b26408273a508722931422b9c62d83be83d3658"
    }
}
