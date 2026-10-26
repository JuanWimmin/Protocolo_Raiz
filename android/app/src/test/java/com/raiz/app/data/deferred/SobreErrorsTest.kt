package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.xdr.TransactionResultCodeXdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Clasificadores PUROS de los errores de la red al liquidar un sobre ([SobreErrors]) y utilidades puras del
 * [SobreSettler]. Los textos son los reales de testnet: el host ENCADENA frames, así que una misma simulación
 * fallida suele traer varios `Error(...)` a la vez.
 */
class SobreErrorsTest {

    /** Lo que devuelve el RPC cuando el contrato falla: la causa arriba y el Auth del frame envolvente abajo. */
    private fun multiFrame(causa: String, envolvente: String) =
        "HostError: $causa\n\nEvent log (newest first):\n" +
            "   0: [Diagnostic Event] contract:CAB123, topics:[error, $causa], data:[\"escalating error to VM trap\"]\n" +
            "   1: [Diagnostic Event] topics:[fn_call, Bytes(abcd), pay_merchant], data:[GPAYER, GMERCH, 5000000, 200]\n" +
            "   2: [Diagnostic Event] topics:[error, $envolvente], data:[\"failed account authentication\"]"

    private fun sim(error: String) = SobreErrors.classifySimulation(error)

    // ── classifySimulation ───────────────────────────────────────────────────

    @Test
    fun `un codigo de contrato gana a un Auth generico de otro frame`() {
        val texto = "HostError: Error(Contract, #10) … Error(Auth, InvalidAction)"
        assertEquals(SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_10), sim(texto))
        assertEquals(
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_10),
            sim(multiFrame("Error(Contract, #10)", "Error(Auth, InvalidAction)")),
        )
    }

    @Test
    fun `ExistingValue es nonce usado y nunca cobrado`() {
        assertEquals(SobreSubmit.NonceUsado, sim("HostError: Error(Auth, ExistingValue)"))
        // Gana incluso a un código de contrato en otro frame: la regla explícita va primero.
        assertEquals(SobreSubmit.NonceUsado, sim(multiFrame("Error(Auth, ExistingValue)", "Error(Contract, #10)")))
        assertEquals(SobreSubmit.NonceUsado, sim("Error(Contract, #10) ... Error(Auth, ExistingValue)"))
    }

    @Test
    fun `InvalidInput es expirada`() {
        assertEquals(SobreSubmit.Expirada, sim("HostError: Error(Auth, InvalidInput)"))
        assertEquals(SobreSubmit.Expirada, sim(multiFrame("Error(Auth, InvalidInput)", "Error(Auth, InvalidAction)")))
    }

    @Test
    fun `solo InvalidAction es un rechazo definitivo`() {
        assertEquals(
            SobreSubmit.RechazadaDefinitiva(SobreErrors.MSG_AUTH),
            sim("HostError: Error(Auth, InvalidAction)"),
        )
        // Tolera espacios distintos.
        assertEquals(SobreSubmit.RechazadaDefinitiva(SobreErrors.MSG_AUTH), sim("Error(Auth,InvalidAction)"))
        // Ningún otro Auth es definitivo.
        assertTrue(sim("HostError: Error(Auth, ArithDomain)") is SobreSubmit.RechazadaTransitoria)
        assertEquals(
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_AUTH_OTRO),
            sim("HostError: Error(Auth, MissingValue)"),
        )
    }

    @Test
    fun `fondos, trustline y comercio aplazan con su motivo`() {
        assertEquals(SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_13), sim("HostError: Error(Contract, #13)"))
        assertEquals(SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_5), sim("HostError: Error(Contract, #5)"))
        assertEquals(SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_4), sim("HostError: Error(Contract, #4)"))
        // Prioridad entre códigos: #10 > #13 > #5 > #4.
        assertEquals(SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_10), sim("Error(Contract, #13) Error(Contract, #10)"))
        assertEquals(SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_13), sim("Error(Contract, #5) Error(Contract, #13)"))
        assertEquals(SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_5), sim("Error(Contract, #4) Error(Contract, #5)"))
        // Un Auth envolvente no los degrada a "alterado".
        assertEquals(
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_13),
            sim(multiFrame("Error(Contract, #13)", "Error(Auth, InvalidAction)")),
        )
    }

    @Test
    fun `el 7 del Pool es monto invalido, NO fondos`() {
        val r = sim("HostError: Error(Contract, #7)")
        assertTrue(r is SobreSubmit.RechazadaTransitoria)
        r as SobreSubmit.RechazadaTransitoria
        assertNotEquals(SobreErrors.MSG_10, r.motivo)
        assertEquals("Sobre inválido para el Pool (#7)", r.motivo)
        assertEquals(
            SobreSubmit.RechazadaTransitoria("Sobre inválido para el Pool (#6)"),
            sim("HostError: Error(Contract, #6)"),
        )
        assertEquals(
            SobreSubmit.RechazadaTransitoria("Sobre inválido para el Pool (#8)"),
            sim("HostError: Error(Contract, #8)"),
        )
        // Un número más largo no se confunde con uno de la tabla por tener el mismo prefijo.
        assertTrue(sim("HostError: Error(Contract, #100)") is SobreSubmit.SinRed)
        assertTrue(sim("HostError: Error(Contract, #130)") is SobreSubmit.SinRed)
        assertTrue(sim("HostError: Error(Contract, #40)") is SobreSubmit.SinRed)
    }

    @Test
    fun `lo demas es sin red con el fragmento del host, nunca definitivo`() {
        assertEquals(
            SobreSubmit.SinRed("${SobreErrors.MSG_SIM_FALLIDA}: Error(WasmVm, InvalidAction)"),
            sim("HostError: Error(WasmVm, InvalidAction)\n\nEvent log..."),
        )
        assertEquals(SobreSubmit.SinRed(SobreErrors.MSG_SIM_FALLIDA), sim(""))
        assertEquals(SobreSubmit.SinRed(SobreErrors.MSG_SIM_FALLIDA), sim("transaction simulation failed"))
        // Un código de contrato no listado tampoco rechaza: se aplaza como cualquier fallo sin veredicto.
        assertTrue(sim("HostError: Error(Contract, #3)") is SobreSubmit.SinRed)
    }

    @Test
    fun `el fragmento del host es el primer Error entre parentesis`() {
        assertEquals("Error(Contract, #10)", SobreErrors.hostErrorFragment("x Error(Contract, #10) y Error(Auth, X)"))
        assertNull(SobreErrors.hostErrorFragment("sin nada"))
        assertNull(SobreErrors.hostErrorFragment(null))
    }

    // ── classifySend ─────────────────────────────────────────────────────────

    @Test
    fun `classifySend manda sin fondos de la fuente a la pasarela`() {
        assertEquals(
            SobreSubmit.SinFondosFuente(SobreErrors.MSG_SIN_XLM),
            SobreErrors.classifySend(TransactionResultCodeXdr.txINSUFFICIENT_BALANCE),
        )
        assertEquals(
            SobreSubmit.SinFondosFuente(SobreErrors.MSG_SIN_XLM),
            SobreErrors.classifySend(TransactionResultCodeXdr.txINSUFFICIENT_FEE),
        )
    }

    @Test
    fun `classifySend aplaza txBAD_AUTH y txNO_ACCOUNT`() {
        assertEquals(
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_BAD_AUTH),
            SobreErrors.classifySend(TransactionResultCodeXdr.txBAD_AUTH),
        )
        assertEquals(
            SobreSubmit.RechazadaTransitoria(SobreErrors.MSG_NO_ACCOUNT),
            SobreErrors.classifySend(TransactionResultCodeXdr.txNO_ACCOUNT),
        )
    }

    @Test
    fun `classifySend pide reconstruir con secuencia o vigencia obsoletas`() {
        assertEquals(SobreSubmit.SinRed(SobreErrors.MSG_RECONSTRUIR), SobreErrors.classifySend(TransactionResultCodeXdr.txBAD_SEQ))
        assertEquals(SobreSubmit.SinRed(SobreErrors.MSG_RECONSTRUIR), SobreErrors.classifySend(TransactionResultCodeXdr.txTOO_LATE))
    }

    @Test
    fun `classifySend con un codigo desconocido o ausente es sin red con el nombre`() {
        val soroban = SobreErrors.classifySend(TransactionResultCodeXdr.txSOROBAN_INVALID)
        assertTrue(soroban is SobreSubmit.SinRed)
        assertTrue((soroban as SobreSubmit.SinRed).motivo.contains("txSOROBAN_INVALID"))
        val nulo = SobreErrors.classifySend(null)
        assertTrue(nulo is SobreSubmit.SinRed)
        assertTrue((nulo as SobreSubmit.SinRed).motivo.contains("sin detalle"))
    }

    // ── Utilidades puras del SobreSettler ────────────────────────────────────

    @Test
    fun `la URL de la pasarela lleva el sobre en el fragmento y se puede recuperar`() {
        val sobre = SOBRE_QR
        val url = SobreSettler.pasarelaUrlFor(sobre)
        assertTrue(url.startsWith("https://raizapp.xyz/pasarela.html#sobre="))
        assertFalse("el sobre no debe ir en la query", url.contains("?"))
        assertFalse("los dos puntos van codificados", url.substringAfter("#sobre=").contains(":"))
        assertEquals(sobre, URLDecoder.decode(url.substringAfter("#sobre="), "UTF-8"))
    }

    @Test
    fun `los XLM se formatean con dos decimales y punto`() {
        assertEquals("30.00", SobreSettler.formatXlm(300_000_000L))
        assertEquals("28.65", SobreSettler.formatXlm(286_500_000L))
        assertEquals("0.05", SobreSettler.formatXlm(500_000L))
        assertEquals("0.00", SobreSettler.formatXlm(0L))
        assertEquals("0.00", SobreSettler.formatXlm(99_999L))
        assertEquals("12.34", SobreSettler.formatXlm(123_456_789L))
        assertEquals("100.00", SobreSettler.formatXlm(SobreSettler.MANUAL_MAX_FEE))
    }

    @Test
    fun `los topes de fee son los decididos`() {
        assertEquals(100.0, SobreSettler.AUTO_MAX_FEE_PROPIOS / 10_000_000.0, 0.0)
        assertEquals(2.0, SobreSettler.AUTO_MAX_FEE_RECIBIDOS / 10_000_000.0, 0.0)
        assertEquals(100.0, SobreSettler.MANUAL_MAX_FEE / 10_000_000.0, 0.0)
        assertEquals(10_000L, SobreSettler.PAYER_JITTER_MS)
    }

    private companion object {
        /** Un sobre con el aspecto real (prefijo + base64url con `-` y `_`) para probar el escape de la URL. */
        const val SOBRE_QR = "raiz:sobre:1:AAAAAQAAAAAAAAAA-_8x0123456789abcdefABCDEF"
    }
}
