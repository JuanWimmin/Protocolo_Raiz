package com.raiz.app.ui.deferred

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raiz.app.data.deferred.ReceivedSobrePolicy
import com.raiz.app.data.deferred.ReceivedSobreRecord
import com.raiz.app.data.deferred.RecibidoStatus
import com.raiz.app.data.deferred.RolRecibido
import com.raiz.app.data.deferred.SobreSettler
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGray
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import java.util.Locale

// Piezas compartidas por "Recibir sobre", la sección "Recibidos" de Mis sobres y el detalle de un sobre recibido.
// Un sobre es una promesa firmada, no dinero: "Cobrado"/"Liquidado" solo con el hash de la transacción (D2).

/** `GABCDE…WXYZ`: 6 primeros y 4 últimos caracteres de una dirección Stellar. */
internal fun direccionCorta(addr: String): String =
    if (addr.length <= 12) addr else "${addr.take(6)}…${addr.takeLast(4)}"

/** `1234abcd…56789a`: igual que en la lista de sobres propios. */
internal fun hashCorto(hash: String): String =
    if (hash.length <= 16) hash else "${hash.take(8)}…${hash.takeLast(6)}"

/** Subtítulo según por qué este teléfono tiene el sobre: el pago es suyo (COBRO) o solo lo lleva (PORTADOR). */
fun recibidoSubtitulo(r: ReceivedSobreRecord): String = when (r.rol) {
    RolRecibido.COBRO -> "Es un pago para ti"
    RolRecibido.PORTADOR -> "Lo llevas para ${direccionCorta(r.merchant)}"
}

/**
 * Rótulo y color del estado de un sobre recibido (mismos colores que la lista de sobres propios).
 * "Cobrado" (COBRO) / "Liquidado" (PORTADOR) SOLO con `txHash`; un nonce consumido sin comprobante sigue en tránsito.
 */
fun estadoRecibidoLabel(r: ReceivedSobreRecord): Pair<String, Color> = when (r.status) {
    RecibidoStatus.EN_TRANSITO -> {
        val texto = when {
            r.inFlightHash != null -> "Enviando…"
            r.motivo == ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE -> "Nonce consumido · buscando comprobante"
            else -> DeferredLabels.recibidoLabel(r.status, r.txHash)
        }
        texto to RaizYellow.copy(alpha = 0.25f)
    }
    RecibidoStatus.CONFIRMADO -> {
        val conComprobante = !r.txHash.isNullOrBlank()
        val texto = if (r.rol == RolRecibido.COBRO && conComprobante) {
            "Cobrado"
        } else {
            DeferredLabels.recibidoLabel(r.status, r.txHash)
        }
        // El verde de éxito es solo del cobro con comprobante: "Confirmado · sin comprobante" va en gris.
        texto to (if (conComprobante) RaizGreen.copy(alpha = 0.18f) else RaizGray.copy(alpha = 0.25f))
    }
    // Un vencimiento solo estimado por este teléfono se rotula "estimado": la red aún puede traer un hash.
    RecibidoStatus.EXPIRADO -> DeferredLabels.expiradoLabel(r) to RaizGray.copy(alpha = 0.25f)
    RecibidoStatus.RECHAZADO -> DeferredLabels.recibidoLabel(r.status, r.txHash) to RaizError.copy(alpha = 0.15f)
}

/** Píldora de estado (la misma de las filas de Mis sobres). */
@Composable
fun RecibidoPill(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Fila de la sección "Recibidos" de Mis sobres.
 * Línea 1: COBRO "+<al comercio> de <pagador>" / PORTADOR "Llevas · <total> → <comercio>".
 * Línea 2: el motivo (o el último motivo de envío) y, si ya hay comprobante, el hash abreviado en verde.
 */
@Composable
fun RecibidoRow(r: ReceivedSobreRecord, onClick: () -> Unit) {
    val (label, color) = estadoRecibidoLabel(r)
    val linea1 = when (r.rol) {
        RolRecibido.COBRO -> "+${r.toMerchantStroops.formatUsdc()} de ${direccionCorta(r.payer)}"
        RolRecibido.PORTADOR -> "Llevas · ${r.totalStroops.formatUsdc()} → ${direccionCorta(r.merchant)}"
    }
    // "Nonce consumido…" ya lo dice el pill: no se repite como segunda línea.
    val detalle = r.motivo?.takeIf { it != ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE } ?: r.lastSendMotivo
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = linea1, style = MaterialTheme.typography.labelLarge, color = RaizBlack)
            if (!detalle.isNullOrBlank()) {
                Text(
                    text = detalle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = RaizBlack.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            r.txHash?.let { hash ->
                Text(
                    text = "tx ${hashCorto(hash)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = RaizGreen,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        RecibidoPill(label = label, color = color, modifier = Modifier.widthIn(max = 132.dp))
    }
}

/** Tarjeta blanca con los datos del sobre recibido (mismo estilo que el detalle del sobre propio). */
@Composable
fun RecibidoDetalle(r: ReceivedSobreRecord) {
    val (estado, _) = estadoRecibidoLabel(r)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Fila("Pagador", direccionCorta(r.payer))
        Fila(
            "Comercio",
            if (r.rol == RolRecibido.COBRO) "Tú · ${direccionCorta(r.merchant)}" else direccionCorta(r.merchant),
            bold = true,
        )
        Fila("Al comercio", r.amountStroops.formatUsdc())
        if (r.tipStroops > 0L) {
            Fila("Tip barrio (${bpsLabel(r.tipBps)})", r.tipStroops.formatUsdc(), accent = RaizGreen)
        }
        Fila("Total", r.totalStroops.formatUsdc(), bold = true)
        if (r.feeStroops > 0L) {
            Nota(
                "El protocolo retiene ${bpsLabel(r.feeBps)} del monto del comercio (${r.feeStroops.formatUsdc()}): " +
                    "el comercio recibe ${r.toMerchantStroops.formatUsdc()}.",
            )
        }
        Fila("Válido hasta", "ledger ${r.exp}")
        if (!r.vigenciaComprobada) {
            Nota(
                "No se pudo comprobar su vigencia al recibirlo (sin hora de red): este teléfono solo lo lleva.",
                RaizError,
            )
        }
        Fila("Estado", estado)
        r.txHash?.let { hash -> Fila("Transacción", hashCorto(hash), accent = RaizGreen) }
    }
}

/** Texto honesto bajo el detalle: promesa firmada mientras no haya comprobante (ver DeferredLabels.recibidoExplicacion). */
@Composable
fun RecibidoNota(
    r: ReceivedSobreRecord,
    liquidador: SobreSettler.Liquidador,
    sinSenal: Boolean,
    liquidando: Boolean,
) {
    val color = when (r.status) {
        RecibidoStatus.RECHAZADO -> RaizError
        // Verde solo con comprobante: un nonce consumido sin hash no es un cobro confirmado.
        RecibidoStatus.CONFIRMADO -> if (r.txHash.isNullOrBlank()) RaizBlack.copy(alpha = 0.7f) else RaizGreen
        else -> RaizBlack.copy(alpha = 0.7f)
    }
    Text(
        text = DeferredLabels.recibidoExplicacion(r, liquidador, sinSenal, liquidando),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

/**
 * Acciones de un sobre recibido.
 *  - Pendiente y wallet semilla (Liquidador.DesdeLaApp): "Liquidar ahora" (deshabilitado sin señal) con el aviso del
 *    fee de red, y "Copiar sobre".
 *  - Pendiente y wallet passkey/demo (Liquidador.Pasarela): el motivo y "Copiar sobre y abrir la pasarela"; sin señal
 *    la pasarela no carga, así que el botón solo copia el sobre para llevarlo allí cuando haya red.
 *  - Con comprobante: "Ver en Stellar Expert".
 */
@Composable
fun RecibidoAcciones(
    r: ReceivedSobreRecord,
    liquidador: SobreSettler.Liquidador,
    sinSenal: Boolean,
    liquidando: Boolean,
    onLiquidar: () -> Unit,
    onPasarela: () -> Unit,
    onCopiar: () -> Unit,
    onVerTx: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (r.pending) {
            when (liquidador) {
                SobreSettler.Liquidador.DesdeLaApp -> {
                    Button(
                        onClick = onLiquidar,
                        enabled = !sinSenal && !liquidando,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RaizBlack,
                            contentColor = RaizWhite,
                            disabledContainerColor = RaizBlack.copy(alpha = 0.25f),
                            disabledContentColor = RaizWhite.copy(alpha = 0.8f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (liquidando) {
                            CircularProgressIndicator(
                                color = RaizWhite,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Text("Liquidar ahora", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Nota(SobreSettler.AVISO_FEE)
                    OutlinedButton(
                        onClick = onCopiar,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, RaizPurple),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizPurple),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Copiar sobre", style = MaterialTheme.typography.labelLarge)
                    }
                }
                is SobreSettler.Liquidador.Pasarela -> {
                    Nota(liquidador.motivo, RaizBlack.copy(alpha = 0.7f))
                    OutlinedButton(
                        // Sin señal la pasarela no carga: solo se copia el sobre (para pegarlo allí cuando haya red).
                        onClick = if (sinSenal) onCopiar else onPasarela,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, RaizPurple),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizPurple),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = if (sinSenal) "Copiar sobre (la pasarela necesita señal)" else "Copiar sobre y abrir la pasarela",
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        if (r.status == RecibidoStatus.CONFIRMADO && !r.txHash.isNullOrBlank()) {
            OutlinedButton(
                onClick = onVerTx,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, RaizGreen),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizGreen),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Ver en Stellar Expert", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Fila(
    label: String,
    value: String,
    bold: Boolean = false,
    accent: Color = RaizBlack,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            color = if (bold) RaizBlack else RaizBlack.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            color = accent,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Nota(text: String, color: Color = RaizBlack.copy(alpha = 0.55f)) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
        color = color,
    )
}

private fun bpsLabel(bps: Int): String {
    val pct = bps / 100.0
    return if (pct == pct.toLong().toDouble()) "${pct.toLong()} %" else String.format(Locale.US, "%.1f %%", pct).replace('.', ',')
}
