package com.raiz.app.ui.deferred

import com.raiz.app.data.deferred.ReceivedSobrePolicy
import com.raiz.app.data.deferred.ReceivedSobreRecord
import com.raiz.app.data.deferred.RecibidoStatus
import com.raiz.app.data.deferred.RolRecibido
import com.raiz.app.data.deferred.SobreSettler
import com.raiz.app.ui.theme.RaizGray
import com.raiz.app.ui.theme.RaizGreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Honestidad de los textos de un sobre RECIBIDO (pantallas "Recibir sobre", "Mis sobres" y Cobros):
 *  - una wallet que no envía desde la app (passkey o demo) NUNCA recibe la promesa de que "se liquida sola";
 *  - "Cobrado"/"Liquidado" solo con el hash de la transacción;
 *  - un vencimiento que solo estimó este teléfono NO se presenta como definitivo (con red se vuelve a comprobar).
 */
class DeferredLabelsTest {

    private val hash = "ab".repeat(32)
    private val deLaApp = SobreSettler.Liquidador.DesdeLaApp
    private val pasarela = SobreSettler.Liquidador.Pasarela(SobreSettler.MOTIVO_PASSKEY)

    private fun record(
        status: RecibidoStatus = RecibidoStatus.EN_TRANSITO,
        rol: RolRecibido = RolRecibido.COBRO,
        motivo: String? = null,
        txHash: String? = null,
    ) = ReceivedSobreRecord(
        id = "GPAYER:7",
        owner = "GMERCHANT",
        sobreQr = "raiz:sobre:1:e30",
        authXdr = "AAAA",
        payer = "GPAYER",
        merchant = "GMERCHANT",
        amountStroops = 25_000_000L,
        tipBps = 200,
        feeBps = 50,
        exp = 1_000L,
        nonce = 7L,
        rol = rol,
        status = status,
        receivedAtMs = 1_000L,
        txHash = txHash,
        motivo = motivo,
    )

    private val expiradoLocal =
        record(status = RecibidoStatus.EXPIRADO, motivo = ReceivedSobrePolicy.MOTIVO_EXPIRADO_LOCAL)
    private val expiradoRed =
        record(status = RecibidoStatus.EXPIRADO, motivo = ReceivedSobrePolicy.MOTIVO_EXPIRADO_RED)

    // ── Quién liquida: nunca se promete un envío automático a una wallet que no envía desde la app ──

    @Test
    fun `passkey o demo sin senal no promete envio automatico y remite a la pasarela`() {
        val texto = DeferredLabels.recibidoExplicacion(record(), pasarela, sinSenal = true, liquidando = false)
        assertTrue(texto, texto.contains("no envía desde la app"))
        assertTrue(texto, texto.contains("pasarela web"))
        assertFalse(texto, texto.contains("sola"))
    }

    @Test
    fun `passkey o demo con senal tambien remite a la pasarela`() {
        val texto = DeferredLabels.recibidoExplicacion(record(), pasarela, sinSenal = false, liquidando = false)
        assertTrue(texto, texto.contains("pasarela web"))
        assertFalse(texto, texto.contains("sola"))
    }

    @Test
    fun `wallet semilla sin senal si se liquida sola al volver la senal`() {
        val texto = DeferredLabels.recibidoExplicacion(record(), deLaApp, sinSenal = true, liquidando = false)
        assertTrue(texto, texto.contains("Se liquidará sola"))
    }

    // ── Cobrado / Liquidado solo con comprobante ────────────────────────────────────────────────

    @Test
    fun `confirmado sin comprobante no se presenta como cobrado ni liquidado`() {
        val sinHash = record(status = RecibidoStatus.CONFIRMADO)
        assertEquals("Confirmado · sin comprobante", DeferredLabels.recibidoLabel(sinHash.status, sinHash.txHash))
        val texto = DeferredLabels.recibidoExplicacion(sinHash, deLaApp, sinSenal = false, liquidando = false)
        assertFalse(texto, texto.startsWith("Cobrado"))
        assertFalse(texto, texto.startsWith("Liquidado"))
    }

    @Test
    fun `con comprobante el cobro dice cobrado y quien solo lo llevaba dice liquidado`() {
        val cobro = record(status = RecibidoStatus.CONFIRMADO, rol = RolRecibido.COBRO, txHash = hash)
        val portador = record(status = RecibidoStatus.CONFIRMADO, rol = RolRecibido.PORTADOR, txHash = hash)
        assertTrue(DeferredLabels.recibidoExplicacion(cobro, deLaApp, false, false).startsWith("Cobrado"))
        assertTrue(DeferredLabels.recibidoExplicacion(portador, deLaApp, false, false).startsWith("Liquidado"))
    }

    @Test
    fun `el rotulo del estado exige comprobante para decir cobrado y no pinta de exito un nonce sin hash`() {
        assertEquals("Cobrado", estadoRecibidoLabel(record(status = RecibidoStatus.CONFIRMADO, txHash = hash)).first)
        assertEquals(
            "Liquidado",
            estadoRecibidoLabel(record(status = RecibidoStatus.CONFIRMADO, rol = RolRecibido.PORTADOR, txHash = hash)).first,
        )
        val sinHash = estadoRecibidoLabel(record(status = RecibidoStatus.CONFIRMADO))
        assertEquals("Confirmado · sin comprobante", sinHash.first)
        assertEquals(RaizGray.copy(alpha = 0.25f), sinHash.second)
        assertEquals(
            RaizGreen.copy(alpha = 0.18f),
            estadoRecibidoLabel(record(status = RecibidoStatus.CONFIRMADO, txHash = hash)).second,
        )
    }

    // ── Vencimiento: el estimado por este teléfono no es definitivo ────────────────────────────

    @Test
    fun `un vencimiento solo estimado se rotula estimado y no se da por perdido`() {
        assertEquals("Expirado (estimado)", DeferredLabels.expiradoLabel(expiradoLocal))
        assertEquals("Expirado (estimado)", estadoRecibidoLabel(expiradoLocal).first)
        val texto = DeferredLabels.recibidoExplicacion(expiradoLocal, deLaApp, sinSenal = true, liquidando = false)
        assertTrue(texto, texto.contains("estimación de este teléfono"))
        assertTrue(texto, texto.contains("no lo des por perdido"))
        assertFalse(texto, texto.contains("firme uno nuevo"))
        assertFalse(texto, texto.contains("no hubo cobro"))
    }

    @Test
    fun `el comercio no pide otro sobre hasta comprobarlo y quien solo lo lleva no tiene a quien pedirlo`() {
        val comercio = DeferredLabels.expiradoExplicacion(expiradoLocal)
        assertTrue(comercio, comercio.contains("ni pidas otro sobre al pagador"))
        val portador = DeferredLabels.expiradoExplicacion(expiradoLocal.copy(rol = RolRecibido.PORTADOR))
        assertFalse(portador, portador.contains("pidas otro sobre"))
        assertTrue(portador, portador.contains("no lo des por perdido"))
    }

    @Test
    fun `un vencimiento confirmado por la red es definitivo`() {
        assertEquals("Expirado", DeferredLabels.expiradoLabel(expiradoRed))
        assertEquals("Expirado", estadoRecibidoLabel(expiradoRed).first)
        val texto = DeferredLabels.recibidoExplicacion(expiradoRed, deLaApp, sinSenal = false, liquidando = false)
        assertTrue(texto, texto.contains("no hubo cobro"))
        assertTrue(texto, texto.contains("firme uno nuevo"))
    }

    @Test
    fun `el aviso tras liquidar tambien distingue el vencimiento estimado del confirmado`() {
        val local = DeferredLabels.trasLiquidar(SobreSettler.Resultado.Actualizado(null, expiradoLocal), "raiz:sobre:1:e30").aviso
        assertNotNull(local)
        assertTrue(local, local!!.contains("estimación de este teléfono"))
        val red = DeferredLabels.trasLiquidar(SobreSettler.Resultado.Actualizado(null, expiradoRed), "raiz:sobre:1:e30").aviso
        assertEquals("El sobre expiró sin liquidarse.", red)
    }
}
