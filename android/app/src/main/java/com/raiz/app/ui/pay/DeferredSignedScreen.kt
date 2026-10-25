package com.raiz.app.ui.pay

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raiz.app.data.deferred.DeferredRecord
import com.raiz.app.data.deferred.DeferredStatus
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.qr.QrUtils
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Pantalla del sobre firmado sin señal (spec §7.1): QR del sobre (base64url a secas, igual que
 * "Copiar sobre"), detalle honesto y vigencia estimada. La reutiliza el historial para re-mostrar un sobre.
 *
 * El QR se genera al ancho EXACTO disponible, con módulos de tamaño entero y sin reescalado
 * ([QrUtils.generateCrisp] + `FilterQuality.None`): con ≈ 2 000 caracteres (versión 38) es lo que decide
 * si una cámara lo lee desde una pantalla.
 */
@Composable
fun DeferredSignedScreen(
    record: DeferredRecord,
    validUntilLabel: String,
    onVerSobres: () -> Unit,
    onDone: () -> Unit,
    contentPadding: PaddingValues,
    titulo: String = "Sobre firmado",
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = titulo,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
            )

            StatusChip(record)

            if (record.status == DeferredStatus.EXPIRADO) {
                Text(
                    text = "Este sobre expiró sin liquidarse. Firma uno nuevo cuando quieras pagar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizError,
                )
            } else {
                QrCardCrisp(content = record.envelopeB64url)
            }

            DetalleSobre(record = record)

            Text(
                text = validUntilLabel,
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = "Es una promesa firmada. El comercio la cobra cuando cualquier dispositivo del " +
                    "territorio tenga señal. Hasta entonces no es dinero confirmado.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )

            extra()

            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(record.envelopeB64url))
                    Toast.makeText(context, "Sobre copiado", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, RaizPurple),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizPurple),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Copiar sobre", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onVerSobres, modifier = Modifier.fillMaxWidth()) {
                Text("Ver mis sobres", style = MaterialTheme.typography.bodyMedium, color = RaizGreen)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RaizGreen, contentColor = RaizWhite),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Listo", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusChip(record: DeferredRecord) {
    val (texto, fondo) = when (record.status) {
        DeferredStatus.FIRMADO, DeferredStatus.ENTREGADO -> "Promesa firmada, no confirmada" to RaizYellow.copy(alpha = 0.18f)
        DeferredStatus.CONFIRMADO -> "Confirmado en la red" to RaizGreen.copy(alpha = 0.15f)
        DeferredStatus.EXPIRADO -> "Expirado" to RaizBlack.copy(alpha = 0.08f)
        DeferredStatus.RECHAZADO -> "Rechazado" to RaizError.copy(alpha = 0.12f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fondo)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text = texto, style = MaterialTheme.typography.labelLarge, color = RaizBlack)
    }
}

/** Tarjeta blanca con el QR al ancho exacto disponible (módulos enteros, sin filtrado). */
@Composable
private fun QrCardCrisp(content: String) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val targetPx = constraints.maxWidth
        val bitmap by produceState<Bitmap?>(initialValue = null, key1 = content, key2 = targetPx) {
            value = withContext(Dispatchers.Default) {
                runCatching { QrUtils.generateCrisp(content, targetPx = targetPx) }.getOrNull()
            }
        }
        val bmp = bitmap
        if (bmp == null) {
            Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RaizGreen)
            }
        } else {
            val side = with(LocalDensity.current) { bmp.width.toDp() }
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "QR del sobre de pago diferido",
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(side),
            )
        }
    }
}

@Composable
private fun DetalleSobre(record: DeferredRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Fila("Comercio", record.merchantName, bold = true)
        if (!record.merchantVerified) {
            Text(
                text = "Este comercio no figura como verificado: la red no liquidará el sobre.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizError,
            )
        }
        Fila("Al comercio", record.amountStroops.formatUsdc())
        if (record.tipStroops > 0L) {
            Fila("Tip barrio (${bpsLabel(record.tipBps)})", record.tipStroops.formatUsdc(), accent = RaizGreen)
        }
        Fila("Total", record.totalStroops.formatUsdc(), bold = true)
        if (record.feeStroops > 0L) {
            Text(
                text = "El protocolo retiene ${bpsLabel(record.feeBps)} del monto del comercio (${record.feeStroops.formatUsdc()}).",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizBlack.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun Fila(
    label: String,
    value: String,
    bold: Boolean = false,
    accent: androidx.compose.ui.graphics.Color = RaizBlack,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            color = if (bold) RaizBlack else RaizBlack.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            color = accent,
        )
    }
}

private fun bpsLabel(bps: Int): String {
    val pct = bps / 100.0
    return if (pct == pct.toLong().toDouble()) "${pct.toLong()} %" else String.format(Locale.US, "%.1f %%", pct).replace('.', ',')
}
