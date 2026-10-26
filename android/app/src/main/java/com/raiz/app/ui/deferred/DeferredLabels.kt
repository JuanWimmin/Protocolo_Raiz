package com.raiz.app.ui.deferred

import com.raiz.app.data.deferred.DeferredStatus
import com.raiz.app.data.deferred.ReceivedSobrePolicy
import com.raiz.app.data.deferred.ReceivedSobreRecord
import com.raiz.app.data.deferred.RecibidoStatus
import com.raiz.app.data.deferred.RolRecibido
import com.raiz.app.data.deferred.SobreSettler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Qué hace la pantalla tras un intento de liquidar un sobre recibido: un aviso (Toast) y/o abrir la pasarela web.
 * [pasarela] es (texto del sobre que se copia al portapapeles, URL de la pasarela), el mismo orden que usa Cobros;
 * null = no hay nada que abrir.
 */
data class TrasLiquidar(
    val aviso: String?,
    val pasarela: Pair<String, String>?,
)

/** Textos derivados del sobre, compartidos por la pantalla del sobre y el historial. */
object DeferredLabels {
    private val hourFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** "Válido ~12 h (hasta ~HH:mm, estimado)"; con "mañana" si cambia el día. Hora local del teléfono. */
    fun validUntil(validUntilMs: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val at = Instant.ofEpochMilli(validUntilMs).atZone(zone)
        val prefix = if (at.toLocalDate() != today) "mañana " else ""
        return "Válido ~12 h (hasta $prefix~${hourFmt.format(at)}, estimado)"
    }

    fun statusLabel(status: DeferredStatus): String = when (status) {
        DeferredStatus.FIRMADO, DeferredStatus.ENTREGADO -> "En tránsito"
        DeferredStatus.CONFIRMADO -> "Confirmado"
        DeferredStatus.EXPIRADO -> "Expirado"
        DeferredStatus.RECHAZADO -> "Rechazado"
    }

    // ── Sobres RECIBIDOS ──────────────────────────────────────────────────────────

    /**
     * Estado de un sobre recibido. "Liquidado" solo con comprobante: un CONFIRMADO sin hash no se presenta como
     * cobrado (D2). El rótulo de cobro ("Cobrado") lo añade [estadoRecibidoLabel] según el rol.
     */
    fun recibidoLabel(status: RecibidoStatus, txHash: String?): String = when (status) {
        RecibidoStatus.EN_TRANSITO -> "En tránsito"
        RecibidoStatus.CONFIRMADO -> if (txHash.isNullOrBlank()) "Confirmado · sin comprobante" else "Liquidado"
        RecibidoStatus.EXPIRADO -> "Expirado"
        RecibidoStatus.RECHAZADO -> "Rechazado"
    }

    /**
     * Texto honesto bajo el detalle de un sobre recibido. Un sobre es una promesa firmada, no dinero: solo se habla
     * de "cobrado"/"liquidado" cuando el registro tiene el hash de la transacción.
     */
    fun recibidoExplicacion(
        r: ReceivedSobreRecord,
        liquidador: SobreSettler.Liquidador,
        sinSenal: Boolean,
        liquidando: Boolean,
    ): String = when (r.status) {
        RecibidoStatus.EN_TRANSITO -> when {
            liquidando || r.inFlightHash != null ->
                "Enviando a la red… Hasta que la red lo confirme sigue siendo una promesa firmada, no dinero."
            r.motivo == ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE ->
                "La red ya usó el nonce de este sobre, pero aún no encontramos la transacción que lo prueba. " +
                    "Sigue en tránsito: no es dinero confirmado hasta tener el comprobante."
            // Una wallet que no envía desde la app (passkey/demo) NUNCA "se liquida sola", haya señal o no: va antes que
            // sinSenal para no prometerle un envío automático que su teléfono no hará.
            liquidador is SobreSettler.Liquidador.Pasarela ->
                if (sinSenal) {
                    "Promesa firmada, no confirmada. Esta wallet no envía desde la app: el sobre queda guardado aquí " +
                        "y, cuando haya señal, se liquida desde la pasarela web."
                } else {
                    "Promesa firmada, no confirmada. Esta wallet no envía desde la app: usa la pasarela web para liquidarla."
                }
            sinSenal ->
                "Promesa firmada, no confirmada. Se liquidará sola en cuanto este teléfono tenga señal."
            r.lastQuotedFeeStroops > 0L ->
                "Promesa firmada, no confirmada. La red cobraría ≈ ${SobreSettler.formatXlm(r.lastQuotedFeeStroops)} XLM " +
                    "por enviarla, más de lo que este teléfono paga solo: confírmalo con Liquidar ahora."
            r.sendAttempts >= ReceivedSobrePolicy.MAX_SEND_ATTEMPTS ->
                "Promesa firmada, no confirmada. El envío automático ya lo intentó ${r.sendAttempts} veces; " +
                    "usa Liquidar ahora para intentarlo otra vez."
            !r.lastSendMotivo.isNullOrBlank() ->
                "Promesa firmada, no confirmada.\nÚltimo intento: ${r.lastSendMotivo}\n" +
                    "Se reintenta sola; también puedes usar Liquidar ahora."
            else ->
                "Promesa firmada, no confirmada. Liquidando… este teléfono la envía sola."
        }
        RecibidoStatus.CONFIRMADO -> when {
            r.txHash.isNullOrBlank() ->
                "La red consumió el sobre, pero todavía no tenemos el comprobante de la transacción."
            r.rol == RolRecibido.COBRO -> "Cobrado: la red liquidó este pago a tu favor."
            else -> "Liquidado: la red lo pagó al comercio."
        }
        RecibidoStatus.EXPIRADO -> expiradoExplicacion(r)
        RecibidoStatus.RECHAZADO -> {
            val motivo = r.motivo
            if (motivo.isNullOrBlank()) "La red rechazó este sobre." else "La red rechazó este sobre: $motivo"
        }
    }

    /**
     * Rótulo de un sobre recibido EXPIRADO. Hay dos clases y no valen lo mismo: si la red ya pasó `exp` sin que nadie lo
     * liquidara es definitivo ("Expirado"); si solo lo estimó el reloj de este teléfono
     * ([ReceivedSobrePolicy.isLocalExpiry]) es provisional —con red se vuelve a comprobar— y se rotula "estimado".
     */
    fun expiradoLabel(r: ReceivedSobreRecord): String =
        if (ReceivedSobrePolicy.isLocalExpiry(r)) "Expirado (estimado)" else "Expirado"

    /**
     * Texto honesto de un sobre recibido EXPIRADO, el mismo en todas las pantallas. Un vencimiento solo según la
     * estimación local NO es definitivo: con señal [SobreSettler.settlePending] comprueba en la red si alguien lo liquidó
     * antes de vencer (y entonces pasa a cobrado, con hash). Hasta entonces no se da por perdido ni se pide otro sobre:
     * el pagador podría terminar pagando dos veces.
     */
    fun expiradoExplicacion(r: ReceivedSobreRecord): String = when {
        ReceivedSobrePolicy.isLocalExpiry(r) -> {
            val base = "Venció según la estimación de este teléfono, pero la red aún no lo confirma: con señal se " +
                "comprobará si alguien lo liquidó antes de vencer."
            if (r.rol == RolRecibido.COBRO) {
                "$base Hasta entonces no lo des por perdido ni pidas otro sobre al pagador."
            } else {
                "$base Hasta entonces no lo des por perdido."
            }
        }
        r.rol == RolRecibido.COBRO ->
            "La red pasó la expiración del sobre sin que nadie lo liquidara: no hubo cobro. " +
                "Pídele al pagador que firme uno nuevo."
        else ->
            "La red pasó la expiración del sobre sin que nadie lo liquidara: no hubo pago. " +
                "El pagador tendría que firmar uno nuevo."
    }

    /** Mapeo único de lo que devuelve "Liquidar ahora" ([SobreSettler.settleReceived] con `manual = true`) a la pantalla. */
    fun trasLiquidar(resultado: SobreSettler.Resultado, sobreQr: String): TrasLiquidar = when (resultado) {
        is SobreSettler.Resultado.Actualizado -> TrasLiquidar(avisoActualizado(resultado.recibido), null)
        // Una wallet que no envía desde la app (o con otra dueña) liquida con la pasarela web: se copia el sobre y se abre.
        is SobreSettler.Resultado.NoEnviable ->
            TrasLiquidar(resultado.motivo, sobreQr to (resultado.pasarelaUrl ?: SobreSettler.pasarelaUrlFor(sobreQr)))
        SobreSettler.Resultado.Ocupado ->
            TrasLiquidar("Este sobre ya se está enviando. Espera unos segundos.", null)
        SobreSettler.Resultado.SinSenal ->
            TrasLiquidar("Sin señal: se enviará solo al volver la red.", null)
        is SobreSettler.Resultado.FeeAlta -> TrasLiquidar(
            "La red cobraría ${SobreSettler.formatXlm(resultado.feeStroops)} XLM por enviarlo, más del máximo " +
                "permitido (${SobreSettler.formatXlm(resultado.maxFeeStroops)} XLM). No se envió.",
            null,
        )
    }

    private fun avisoActualizado(r: ReceivedSobreRecord?): String = when {
        r == null -> "Este sobre ya no está guardado en el teléfono."
        r.status == RecibidoStatus.CONFIRMADO -> when {
            r.txHash.isNullOrBlank() -> "La red consumió el sobre; falta encontrar el comprobante."
            r.rol == RolRecibido.COBRO -> "Cobrado: la red liquidó el pago."
            else -> "Liquidado en la red."
        }
        r.status == RecibidoStatus.EXPIRADO ->
            if (ReceivedSobrePolicy.isLocalExpiry(r)) {
                "Venció según la estimación de este teléfono; con señal se comprobará en la red."
            } else {
                "El sobre expiró sin liquidarse."
            }
        r.status == RecibidoStatus.RECHAZADO ->
            if (r.motivo.isNullOrBlank()) "La red rechazó el sobre." else "La red rechazó el sobre: ${r.motivo}"
        r.inFlightHash != null -> "Enviado a la red; esperando su confirmación."
        r.motivo == ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE ->
            "La red ya usó este sobre; seguimos buscando el comprobante."
        !r.lastSendMotivo.isNullOrBlank() -> "Sigue en tránsito. ${r.lastSendMotivo}"
        else -> "Sigue en tránsito."
    }
}
