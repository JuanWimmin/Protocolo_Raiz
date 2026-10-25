package com.raiz.app.ui.deferred

import com.raiz.app.data.deferred.DeferredStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
}
