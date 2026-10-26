package com.raiz.app.ui.cobros

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raiz.app.data.deferred.PayMerchantSplit
import com.raiz.app.data.deferred.ReceivedSobrePolicy
import com.raiz.app.data.deferred.ReceivedSobreRecord
import com.raiz.app.data.deferred.ReceivedSobreVerifier
import com.raiz.app.data.deferred.RecibidoStatus
import com.raiz.app.data.deferred.RolRecibido
import com.raiz.app.data.deferred.SobreSettler
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.deferred.DeferredLabels
import com.raiz.app.ui.theme.RaizBackground
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGray
import com.raiz.app.ui.theme.RaizGrayLight
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Componentes de la pantalla Cobros para el cobro sin señal (pago diferido): estado, fila, detalle,
// tope y diálogo de veredicto. Ninguno guarda estado propio: lo que cambia entra por parámetros.

// ─────────────────────────────────────────────────────────────────────────────
// Estado de un sobre recibido
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Etiqueta y color del pill de un sobre recibido.
 *
 * Honestidad: "Cobrado" (el comercio) o "Liquidado" (quien solo lo llevó) SOLO si el registro tiene
 * `txHash`, es decir, un hash cuya auth es este sobre. Mientras tanto es una promesa firmada:
 * "En tránsito" o, si la red ya gastó el nonce pero aún no hay hash, "Nonce consumido · buscando
 * comprobante". Nunca se afirma un cobro sin comprobante.
 */
fun estadoRecibido(r: ReceivedSobreRecord): Pair<String, Color> = when (r.status) {
    RecibidoStatus.EN_TRANSITO -> when {
        r.inFlightHash != null -> "Enviando…" to RaizYellow.copy(alpha = 0.25f)
        r.motivo == ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE ->
            "Nonce consumido · buscando comprobante" to RaizYellow.copy(alpha = 0.25f)
        else -> "En tránsito" to RaizYellow.copy(alpha = 0.25f)
    }
    RecibidoStatus.CONFIRMADO ->
        if (!r.txHash.isNullOrBlank()) {
            (if (r.rol == RolRecibido.COBRO) "Cobrado" else "Liquidado") to RaizGreen.copy(alpha = 0.18f)
        } else {
            "Confirmado · sin comprobante" to RaizGray.copy(alpha = 0.25f)
        }
    // Un vencimiento solo estimado por este teléfono se rotula "estimado": la red aún puede traer un hash.
    RecibidoStatus.EXPIRADO -> DeferredLabels.expiradoLabel(r) to RaizGray.copy(alpha = 0.25f)
    RecibidoStatus.RECHAZADO -> "Rechazado" to RaizError.copy(alpha = 0.15f)
}

// ─────────────────────────────────────────────────────────────────────────────
// Formato
// ─────────────────────────────────────────────────────────────────────────────

/** `GABCDE…WXYZ`: los primeros [inicio] y los últimos [fin] caracteres; si ya es corto, tal cual. */
internal fun abreviarDireccion(valor: String, inicio: Int = 6, fin: Int = 4): String =
    if (valor.length <= inicio + fin + 1) valor else "${valor.take(inicio)}…${valor.takeLast(fin)}"

/** Stroops → texto USDC sin unidad ni ceros de más (200 000 000 → "20"): valor inicial del campo del tope. */
fun stroopsATextoUsdc(stroops: Long): String =
    BigDecimal.valueOf(stroops, RaizConstants.USDC_DECIMALS).stripTrailingZeros().toPlainString()

/** 200 → "2 %", 50 → "0,5 %". */
private fun porcentajeDeBps(bps: Int): String =
    BigDecimal.valueOf(bps.toLong(), 2).stripTrailingZeros().toPlainString().replace('.', ',') + " %"

/**
 * Reparto del sobre. Un registro dañado (monto o bps fuera de rango) no debe tumbar la lista: en ese
 * caso se muestra todo el monto como del comercio.
 */
private fun repartoDe(r: ReceivedSobreRecord): PayMerchantSplit =
    runCatching { r.split }.getOrElse { PayMerchantSplit(tip = 0L, fee = 0L, toMerchant = r.amountStroops) }

private val formatoFecha: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("es"))

private fun fechaRecibido(ms: Long): String =
    formatoFecha.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Solo dígitos y separador decimal (punto o coma), con un tope de largo para el campo del tope. */
private fun filtrarDecimal(texto: String): String =
    texto.filter { it.isDigit() || it == '.' || it == ',' }.take(14)

/**
 * Nota de una línea bajo el monto de la fila: el motivo del estado o del último intento de envío.
 * "Nonce consumido…" ya lo dice el pill, así que no se repite.
 */
private fun notaDe(r: ReceivedSobreRecord): Pair<String, Color>? {
    val motivo = r.motivo?.takeIf { it.isNotBlank() && it != ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE }
    val ultimoEnvio = r.lastSendMotivo?.takeIf { it.isNotBlank() }
    return when {
        r.status == RecibidoStatus.RECHAZADO && motivo != null -> motivo to RaizError
        r.status == RecibidoStatus.EXPIRADO && motivo != null -> motivo to RaizGray
        r.pending && motivo != null -> motivo to RaizGray
        // Un fee alto no es un fallo: pide la confirmación del usuario ("Liquidar ahora").
        r.pending && ultimoEnvio != null ->
            ultimoEnvio to (if (r.lastQuotedFeeStroops > 0L) RaizPurple else RaizError)
        else -> null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Piezas pequeñas
// ─────────────────────────────────────────────────────────────────────────────

/** Pill de estado. Las etiquetas largas ("Nonce consumido · buscando comprobante") se parten en líneas. */
@Composable
private fun PillEstado(etiqueta: String, fondo: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .widthIn(max = 156.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(fondo)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = RaizBlack,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Fila(
    etiqueta: String,
    valor: String,
    negrita: Boolean = false,
    acento: Color = RaizBlack,
    monoespaciado: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (negrita) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (negrita) RaizBlack else RaizBlack.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = valor,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (negrita) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (monoespaciado) FontFamily.Monospace else FontFamily.Default,
            ),
            color = acento,
            textAlign = TextAlign.End,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fila de la lista "Pagos en tránsito"
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fila de un sobre recibido. Comercio (COBRO): "+<lo que recibes> USDC". Portador: "<total> USDC → <comercio>".
 * Un sobre rechazado, o expirado según la red, nunca pagará: su monto va tachado y sin el verde del cobro. Uno que
 * solo venció según la estimación de este teléfono ([ReceivedSobrePolicy.isLocalExpiry]) aún puede traer un hash con
 * red, así que pierde el verde pero NO se tacha: no se presenta como perdido lo que todavía no se comprobó.
 */
@Composable
fun RecibidoRowCobro(r: ReceivedSobreRecord, onClick: () -> Unit) {
    val (etiqueta, fondoPill) = estadoRecibido(r)
    val reparto = repartoDe(r)
    val esCobro = r.rol == RolRecibido.COBRO
    val vencidoEstimado = ReceivedSobrePolicy.isLocalExpiry(r)
    val cerradoSinPago = r.status == RecibidoStatus.RECHAZADO || (r.status == RecibidoStatus.EXPIRADO && !vencidoEstimado)
    val hash = r.txHash?.takeIf { it.isNotBlank() }
    val nota = notaDe(r)
    val titulo = if (esCobro) {
        "+${reparto.toMerchant.formatUsdc()}"
    } else {
        "${(r.amountStroops + reparto.tip).formatUsdc()} → ${abreviarDireccion(r.merchant)}"
    }
    val colorTitulo = when {
        cerradoSinPago || vencidoEstimado -> RaizBlack.copy(alpha = 0.4f)
        esCobro -> RaizGreen
        else -> RaizBlack
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .clickable(onClickLabel = "Ver el detalle del sobre", onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = titulo,
                    style = MaterialTheme.typography.labelLarge.copy(
                        textDecoration = if (cerradoSinPago) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    color = colorTitulo,
                )
                Text(
                    text = "de ${abreviarDireccion(r.payer)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    color = RaizBlack.copy(alpha = 0.6f),
                )
                if (hash != null) {
                    Text(
                        text = "tx ${abreviarDireccion(hash, 8, 6)}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = RaizGreen,
                    )
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            PillEstado(etiqueta = etiqueta, fondo = fondoPill)
        }
        // Las notas van debajo, a todo el ancho: un motivo largo no aprieta el monto ni el pill.
        if (nota != null) {
            Nota(nota.first, nota.second)
        }
        if (r.pending && r.sendAttempts >= ReceivedSobrePolicy.MAX_SEND_ATTEMPTS) {
            Nota("Reintentos agotados: la red lo cerrará (o usa la pasarela)", RaizError)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Detalle de un sobre recibido (contenido del ModalBottomSheet)
// ─────────────────────────────────────────────────────────────────────────────

/** Una línea honesta sobre qué es hoy este sobre. */
private fun leyendaDe(r: ReceivedSobreRecord): String = when (r.status) {
    RecibidoStatus.EN_TRANSITO ->
        "Promesa firmada, no confirmada: no es dinero hasta que la red la liquide con un comprobante."
    RecibidoStatus.CONFIRMADO ->
        if (r.txHash.isNullOrBlank()) {
            "La red cerró este sobre, pero aún no tenemos el comprobante."
        } else {
            "La red liquidó este sobre. El comprobante está en Stellar Expert."
        }
    // Distingue el vencimiento confirmado por la red del que solo estimó este teléfono (no es definitivo).
    RecibidoStatus.EXPIRADO -> DeferredLabels.expiradoExplicacion(r)
    RecibidoStatus.RECHAZADO -> "Este sobre no se puede cobrar. No lo cuentes como pago."
}

/**
 * Detalle del sobre [r]: reparto, vigencia, estado y los botones que corresponden.
 *
 * - EN_TRANSITO con wallet semilla ([liquidadorMotivo] == null): "Liquidar ahora" (deshabilitado sin señal).
 * - EN_TRANSITO con passkey o sin semilla ([liquidadorMotivo] != null): copiar el sobre y abrir la pasarela web.
 * - CONFIRMADO con hash: "Ver en Stellar Expert" ([onVerTx] recibe el hash).
 */
@Composable
fun CobroDetalleSheet(
    r: ReceivedSobreRecord,
    sinSenal: Boolean,
    liquidando: Boolean,
    liquidadorMotivo: String?,
    onLiquidar: () -> Unit,
    onPasarela: () -> Unit,
    onCopiar: () -> Unit,
    onVerTx: (String) -> Unit,
) {
    val (etiqueta, fondoPill) = estadoRecibido(r)
    val reparto = repartoDe(r)
    val esCobro = r.rol == RolRecibido.COBRO
    val hash = r.txHash?.takeIf { it.isNotBlank() }
    val motivo = r.motivo?.takeIf { it.isNotBlank() }
    val ultimoEnvio = r.lastSendMotivo?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (esCobro) "Cobro sin señal" else "Sobre que llevas",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
        )
        PillEstado(etiqueta = etiqueta, fondo = fondoPill)
        Text(
            text = leyendaDe(r),
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(RaizBackground)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Fila("Pagador", abreviarDireccion(r.payer, 8, 6), monoespaciado = true)
            if (esCobro) {
                Fila("Recibes", reparto.toMerchant.formatUsdc(), negrita = true, acento = RaizGreen)
            } else {
                Fila("Comercio", abreviarDireccion(r.merchant, 8, 6), monoespaciado = true)
                Fila("Recibe el comercio", reparto.toMerchant.formatUsdc(), negrita = true)
            }
            if (reparto.tip > 0L) {
                Fila("Tip barrio (${porcentajeDeBps(r.tipBps)})", reparto.tip.formatUsdc(), acento = RaizGreen)
            }
            if (reparto.fee > 0L) {
                Fila("Comisión protocolo (${porcentajeDeBps(r.feeBps)})", reparto.fee.formatUsdc())
            }
            Fila("Total del turista", (r.amountStroops + reparto.tip).formatUsdc(), negrita = true)
            Fila("Válido hasta", "ledger ${r.exp}")
            if (!r.vigenciaComprobada) {
                Text(
                    text = "Vigencia no comprobada (sin hora de red): este teléfono solo lleva el sobre.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = RaizError,
                )
            }
            Fila("Recibido", fechaRecibido(r.receivedAtMs))
            if (hash != null) {
                Fila("Transacción", abreviarDireccion(hash, 8, 6), monoespaciado = true)
            }
        }

        // Estado: por qué sigue así y qué pasó con los envíos.
        val cotizado = r.lastQuotedFeeStroops
        if (motivo != null || ultimoEnvio != null || cotizado > 0L || r.sendAttempts > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (motivo != null) {
                    Nota(motivo, if (r.status == RecibidoStatus.RECHAZADO) RaizError else RaizBlack.copy(alpha = 0.7f))
                }
                if (ultimoEnvio != null) {
                    Nota("Último intento: $ultimoEnvio", RaizBlack.copy(alpha = 0.7f))
                }
                if (cotizado > 0L) {
                    Nota("Fee cotizado: ${SobreSettler.formatXlm(cotizado)} XLM", RaizPurple)
                }
                if (r.sendAttempts > 0) {
                    Nota(
                        "Envíos que llegaron a la red: ${r.sendAttempts} de ${ReceivedSobrePolicy.MAX_SEND_ATTEMPTS}",
                        RaizBlack.copy(alpha = 0.7f),
                    )
                }
                if (r.pending && r.sendAttempts >= ReceivedSobrePolicy.MAX_SEND_ATTEMPTS) {
                    Nota("Reintentos agotados: la red lo cerrará (o usa la pasarela)", RaizError)
                }
            }
        }

        if (r.pending) {
            if (liquidadorMotivo == null) {
                Button(
                    onClick = onLiquidar,
                    enabled = !sinSenal && !liquidando,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RaizBlack,
                        contentColor = RaizWhite,
                        disabledContainerColor = if (liquidando) RaizBlack.copy(alpha = 0.75f) else RaizGrayLight,
                        disabledContentColor = if (liquidando) RaizWhite else RaizGray,
                    ),
                ) {
                    when {
                        liquidando -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = RaizWhite,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("Enviando…", style = MaterialTheme.typography.labelLarge)
                        }
                        sinSenal -> Text(
                            text = "Se enviará solo al volver la señal",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                        else -> Text("Liquidar ahora", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Nota(SobreSettler.AVISO_FEE, RaizBlack.copy(alpha = 0.55f))
            } else {
                Text(
                    text = liquidadorMotivo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.7f),
                )
                // Sin señal la pasarela web no carga: se deshabilita (el sobre se puede copiar con el botón de abajo).
                OutlinedButton(
                    onClick = onPasarela,
                    enabled = !sinSenal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, if (sinSenal) RaizGray else RaizPurple),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizPurple),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = if (sinSenal) "La pasarela necesita señal" else "Copiar sobre y abrir la pasarela",
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (hash != null) {
            OutlinedButton(
                onClick = { onVerTx(hash) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                border = BorderStroke(1.dp, RaizGreen),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizGreen),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text("Ver en Stellar Expert", style = MaterialTheme.typography.labelLarge)
            }
        }

        OutlinedButton(
            onClick = onCopiar,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            border = BorderStroke(1.dp, RaizPurple),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizPurple),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text("Copiar sobre", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Línea de 12 sp para notas y avisos. */
@Composable
private fun Nota(texto: String, color: Color) {
    Text(
        text = texto,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = color,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Tope por pago sin señal
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Diálogo para cambiar el tope por pago sin señal. [actualUsdc] es el valor vigente como texto USDC
 * ([stroopsATextoUsdc]); [onConfirm] recibe lo que escribió el usuario sin validar (el ViewModel lo sanea).
 */
@Composable
fun TopeDialog(
    actualUsdc: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var texto by remember(actualUsdc) { mutableStateOf(actualUsdc) }
    // Guardar solo con un número (la coma decimal de los teclados en español vale como punto).
    val valido = texto.replace(',', '.').toBigDecimalOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RaizWhite,
        title = {
            Text(
                text = "Tope por pago sin señal",
                style = MaterialTheme.typography.titleMedium,
                color = RaizBlack,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Monto máximo que aceptas de un sobre sin verificarlo en la red. " +
                        "Por defecto ${ReceivedSobreVerifier.TOPE_DEFAULT_STROOPS.formatUsdc()}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.8f),
                )
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = filtrarDecimal(it) },
                    singleLine = true,
                    suffix = {
                        Text(
                            text = "USDC",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RaizBlack.copy(alpha = 0.6f),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RaizGreen,
                        unfocusedBorderColor = RaizBlack.copy(alpha = 0.15f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Nota("Con 0 no aceptas cobros sin señal.", RaizBlack.copy(alpha = 0.55f))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(texto) }, enabled = valido) {
                Text(
                    text = "Guardar",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (valido) RaizGreen else RaizGray,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", style = MaterialTheme.typography.labelLarge, color = RaizBlack)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Banner "Sin señal"
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Banner de "Sin señal" de la pantalla de cobros. Con una wallet que no envía desde la app
 * ([soloPasarela], p. ej. passkey) no promete el envío automático: el sobre se guarda y se liquida
 * desde la pasarela web.
 */
@Composable
fun SinSenalCobrosBanner(modifier: Modifier = Modifier, soloPasarela: Boolean = false) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RaizYellow.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = RaizBlack,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column {
            Text(
                text = "Sin señal",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = if (soloPasarela) {
                    "Los sobres verificados se guardan aquí; para liquidarlos usa la pasarela web cuando haya red."
                } else {
                    "Los sobres verificados se enviarán solos cuando vuelva la red."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Veredicto de recibir un sobre
// ─────────────────────────────────────────────────────────────────────────────

private data class TextoVeredicto(
    val titulo: String,
    val lineas: List<String>,
    /** Sobre cuyo detalle se puede abrir desde el diálogo; null si no se guardó. */
    val idDetalle: String?,
    val esError: Boolean,
)

/** Qué se promete (y qué no) según quién puede enviar el sobre desde esta wallet. */
private fun cierreHonesto(liquidadorMotivo: String?): String =
    if (liquidadorMotivo == null) {
        "Promesa firmada, no confirmada. Se cobrará solo al volver la red."
    } else {
        "Promesa firmada, no confirmada. $liquidadorMotivo"
    }

private fun textoDeVeredicto(v: VeredictoUi, liquidadorMotivo: String?): TextoVeredicto = when (v) {
    is VeredictoUi.Aceptado -> {
        val r = v.record
        val reparto = repartoDe(r)
        val esCobro = r.rol == RolRecibido.COBRO
        TextoVeredicto(
            titulo = if (esCobro) "Sobre verificado · en tránsito" else "Sobre recibido · lo llevas",
            lineas = buildList {
                add(
                    if (esCobro) {
                        "Recibes ${reparto.toMerchant.formatUsdc()} de ${abreviarDireccion(r.payer)}"
                    } else {
                        "Pago de ${(r.amountStroops + reparto.tip).formatUsdc()} para ${abreviarDireccion(r.merchant)}"
                    },
                )
                add(cierreHonesto(liquidadorMotivo))
                if (!r.vigenciaComprobada) add("Vigencia no comprobada (sin hora de red).")
                if (liquidadorMotivo == null) {
                    add(
                        "Si el envío cuesta más de ${SobreSettler.formatXlm(SobreSettler.AUTO_MAX_FEE_RECIBIDOS)} XLM " +
                            "de red (los paga tu cuenta), tendrás que tocar “Liquidar ahora” en el detalle.",
                    )
                }
            },
            idDetalle = r.id,
            esError = false,
        )
    }
    is VeredictoUi.YaRegistrado -> TextoVeredicto(
        titulo = "Sobre ya registrado",
        lineas = listOf(
            when (v.record.status) {
                RecibidoStatus.EN_TRANSITO -> "Ya lo tienes en tránsito."
                RecibidoStatus.CONFIRMADO -> "Ya lo tenías registrado y la red lo liquidó."
                RecibidoStatus.EXPIRADO ->
                    if (ReceivedSobrePolicy.isLocalExpiry(v.record)) {
                        "Ya lo tenías registrado. Venció según la estimación de este teléfono; con señal se " +
                            "comprobará en la red si alguien lo liquidó."
                    } else {
                        "Ya lo tenías registrado y expiró sin liquidarse."
                    }
                RecibidoStatus.RECHAZADO -> "Ya lo tenías registrado y fue rechazado."
            },
        ),
        idDetalle = v.record.id,
        esError = false,
    )
    is VeredictoUi.Rechazado -> TextoVeredicto(
        titulo = "Sobre rechazado",
        lineas = listOf(v.motivo, "No se guardó: no lo cuentes como pago."),
        idDetalle = null,
        esError = true,
    )
}

/**
 * Diálogo con el resultado de escanear o pegar un sobre. Lo que promete depende de la wallet: con
 * semilla ([liquidadorMotivo] == null) el teléfono lo envía solo; con passkey dice que hace falta la
 * pasarela. "Ver detalle" abre el sobre guardado ([onVerDetalle] recibe su id).
 */
@Composable
fun VeredictoDialog(
    veredicto: VeredictoUi,
    liquidadorMotivo: String?,
    onVerDetalle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val texto = textoDeVeredicto(veredicto, liquidadorMotivo)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RaizWhite,
        title = {
            Text(
                text = texto.titulo,
                style = MaterialTheme.typography.titleMedium,
                color = if (texto.esError) RaizError else RaizBlack,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                texto.lineas.forEachIndexed { indice, linea ->
                    Text(
                        text = linea,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (indice == 0) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = RaizBlack.copy(alpha = if (indice == 0) 1f else 0.75f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Entendido", style = MaterialTheme.typography.labelLarge, color = RaizGreen)
            }
        },
        dismissButton = {
            val id = texto.idDetalle
            if (id != null) {
                TextButton(onClick = { onVerDetalle(id) }) {
                    Text("Ver detalle", style = MaterialTheme.typography.labelLarge, color = RaizPurple)
                }
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Vistas previas (solo para las herramientas de diseño; no forman parte de la lógica de la app)
// ─────────────────────────────────────────────────────────────────────────────

private fun sobreDeVistaPrevia(
    rol: RolRecibido,
    status: RecibidoStatus,
    txHash: String? = null,
    motivo: String? = null,
): ReceivedSobreRecord {
    val pagador = "GPAGADOR".padEnd(56, 'A')
    val comercio = "GCOMERCIO".padEnd(56, 'B')
    return ReceivedSobreRecord(
        id = "$pagador:7",
        owner = comercio,
        sobreQr = "raiz:sobre:1:AAAA",
        authXdr = "AAAA",
        payer = pagador,
        merchant = comercio,
        amountStroops = 50_000_000L,
        tipBps = 200,
        feeBps = 50,
        exp = 1_234_567L,
        nonce = 7L,
        rol = rol,
        status = status,
        receivedAtMs = 1_761_500_000_000L,
        txHash = txHash,
        motivo = motivo,
    )
}

@Preview(showBackground = true, widthDp = 360, backgroundColor = 0xFFFAFAF7)
@Composable
private fun RecibidoRowCobroPreview() {
    RaizTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RecibidoRowCobro(sobreDeVistaPrevia(RolRecibido.COBRO, RecibidoStatus.EN_TRANSITO)) {}
            RecibidoRowCobro(
                sobreDeVistaPrevia(
                    RolRecibido.COBRO,
                    RecibidoStatus.EN_TRANSITO,
                    motivo = ReceivedSobrePolicy.MOTIVO_BUSCANDO_COMPROBANTE,
                ),
            ) {}
            RecibidoRowCobro(
                sobreDeVistaPrevia(RolRecibido.COBRO, RecibidoStatus.CONFIRMADO, txHash = "a".repeat(64)),
            ) {}
            RecibidoRowCobro(sobreDeVistaPrevia(RolRecibido.PORTADOR, RecibidoStatus.EN_TRANSITO)) {}
            RecibidoRowCobro(
                sobreDeVistaPrevia(
                    RolRecibido.COBRO,
                    RecibidoStatus.RECHAZADO,
                    motivo = ReceivedSobrePolicy.MOTIVO_NONCE_OTRO_PAGO,
                ),
            ) {}
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun CobroDetalleSheetPreview() {
    RaizTheme {
        CobroDetalleSheet(
            r = sobreDeVistaPrevia(RolRecibido.COBRO, RecibidoStatus.EN_TRANSITO).copy(
                lastSendMotivo = "La red cobra 28.65 XLM por este envío; usa Liquidar ahora para confirmar.",
                lastQuotedFeeStroops = 286_500_000L,
            ),
            sinSenal = false,
            liquidando = false,
            liquidadorMotivo = null,
            onLiquidar = {},
            onPasarela = {},
            onCopiar = {},
            onVerTx = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, backgroundColor = 0xFFFAFAF7)
@Composable
private fun SinSenalCobrosBannerPreview() {
    RaizTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SinSenalCobrosBanner()
            SinSenalCobrosBanner(soloPasarela = true)
        }
    }
}
