package com.raiz.app.ui.pay

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.model.PaymentPreview
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.components.RaizSuccessAnimation
import com.raiz.app.ui.security.biometricConfirm
import com.raiz.app.ui.util.StellarExpert
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGrayLight
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Pantalla de pago. Recibe el merchant ya seleccionado (por ahora hardcoded
 * en el VM; futuro: vía deeplink/navegación desde QrScannerScreen).
 *
 * Flow:
 *   1. Muestra merchant + monto + toggle Tip Barrio + total + puntos a ganar.
 *   2. Tap "Confirmar con huella" → llama SorobanClient.payMerchant.
 *   3. Muestra estado Success o Error.
 */
@Composable
fun PayScreen(
    onDone: () -> Unit = {},
    viewModel: PayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // El Activity es necesario para que el Credential Manager pueda mostrar el
    // selector de passkey (WebAuthn). Se captura aquí (composable raíz del screen)
    // para pasarlo a la acción confirm() del ViewModel — patrón idéntico al de
    // CreatePasskeyWalletScreen. En la rama clásica (KeyPair seed/demo) se ignora.
    val context = LocalContext.current

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (val s = state) {
            PayUiState.Loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = RaizGreen)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Buscando comercio…", color = RaizBlack)
            }
            is PayUiState.Editing -> PayEditing(
                state = s,
                onToggleTip = viewModel::toggleTip,
                // Pasamos el Activity al ViewModel; la rama passkey lo usa para WebAuthn,
                // la rama clásica lo ignora (activity: Activity? = null en el ViewModel).
                onConfirm = { viewModel.confirm(context as? Activity) },
                contentPadding = padding,
            )
            is PayUiState.Success -> PaySuccess(
                merchantName    = s.merchantName,
                totalStroops    = s.totalStroops,
                tipStroops      = s.tipStroops,
                pointsEarned    = s.pointsEarned,
                merchantAddress = s.merchantAddress,
                onDone          = onDone,
                contentPadding  = padding,
            )
            is PayUiState.Error -> PayError(
                message = s.message,
                onDone = onDone,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun PayEditing(
    state: PayUiState.Editing,
    onToggleTip: () -> Unit,
    onConfirm: () -> Unit,
    contentPadding: PaddingValues,
) {
    val preview = state.preview
    val activity = LocalContext.current as? FragmentActivity
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Pagar",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
        )

        MerchantCard(preview = preview)

        // Banner amarillo cuando el monto viene fijo desde el QR del comerciante
        if (state.esOrdenDeCobro) {
            OrdenDeCobroBanner(amountStroops = preview.baseAmountStroops)
        }

        TipToggle(
            enabled = state.tipEnabled,
            pointsToEarn = preview.pointsToEarn,
            tipUsdc = preview.tipUsdc,
            onToggle = onToggleTip,
        )

        Breakdown(preview = preview)

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                if (state.requireBiometric) {
                    biometricConfirm(
                        activity = activity,
                        title = "Confirmar pago",
                        subtitle = "Autoriza con huella, rostro o PIN",
                        onSuccess = onConfirm,
                    )
                } else {
                    onConfirm()
                }
            },
            enabled = !state.submitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
                disabledContainerColor = RaizGreen.copy(alpha = 0.5f),
                disabledContentColor = RaizWhite.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(
                    color = RaizWhite,
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(24.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = "Confirmar pago",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun MerchantCard(preview: PaymentPreview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = preview.merchant.name,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = RaizBlack,
                modifier = Modifier.weight(1f),
            )
            if (preview.merchant.verified) {
                Icon(
                    imageVector = Icons.Outlined.Verified,
                    contentDescription = "Verificado",
                    tint = RaizGreen,
                )
            }
        }
        Text(
            text = preview.merchant.category.label,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun TipToggle(
    enabled: Boolean,
    pointsToEarn: Long,
    tipUsdc: Double,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) RaizGreen.copy(alpha = 0.1f) else RaizWhite)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // P8.2: el label del toggle deja claro que el 2% se SUMA al monto
            // del comercio — no se descuenta de él. Es aditivo.
            Text(
                text = "Aportar 2% al barrio",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = if (enabled)
                    "Ganas $pointsToEarn puntos · +${"%.3f".format(tipUsdc)} USDC al pool"
                else
                    "Sin aporte al barrio",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = RaizWhite,
                checkedTrackColor = RaizGreen,
                uncheckedThumbColor = RaizWhite,
                uncheckedTrackColor = RaizGrayLight,
            ),
        )
    }
}

@Composable
private fun Breakdown(preview: PaymentPreview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // P8.2: "Al comercio" deja inequívoco que el merchant recibe ese importe
        // y el tip es ADICIONAL, no descontado. Total = Al comercio + Tip barrio.
        BreakdownRow("Al comercio", preview.baseAmountStroops.formatUsdc())
        if (preview.tipBps > 0) {
            BreakdownRow("Tip barrio (2%)", preview.tipStroops.formatUsdc(), accent = RaizGreen)
        }
        BreakdownRow(
            label = "Total a pagar",
            value = preview.totalStroops.formatUsdc(),
            bold = true,
        )
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    value: String,
    bold: Boolean = false,
    accent: androidx.compose.ui.graphics.Color = RaizBlack,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (bold) RaizBlack else RaizBlack.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            ),
            color = accent,
        )
    }
}

/**
 * Banner amarillo que indica que el monto viene pre-definido en la orden de cobro
 * generada por el comerciante. Aparece entre la card del merchant y el toggle de tip.
 */
@Composable
private fun OrdenDeCobroBanner(amountStroops: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RaizYellow.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
            contentDescription = null,
            tint = RaizBlack,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column {
            Text(
                text = "Orden de cobro",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = amountStroops.formatUsdc(),
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Estado de éxito del pago con animación nativa RAÍZ.
 *
 * Delega el checkmark animado y la cápsula "Tip Barrio" a [RaizSuccessAnimation].
 * Añade los puntos ganados (color púrpura), un enlace a Stellar Expert para ver
 * el comercio on-chain y el botón de retorno al inicio.
 */
@Composable
private fun PaySuccess(
    merchantName: String,
    totalStroops: Long,
    tipStroops: Long,
    pointsEarned: Long,
    merchantAddress: String,
    onDone: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Animación principal: círculo + check + texto + cápsula barrio
        RaizSuccessAnimation(
            titulo = "¡Pago exitoso!",
            subtitulo = "${totalStroops.formatUsdc()} a $merchantName",
            // Solo muestra la cápsula si el Tip Barrio estaba activado
            tipBarrioUsdc = if (tipStroops > 0L) tipStroops.formatUsdc() else null,
        )

        // Puntos ganados — aparece bajo la animación solo si hay tip activo
        if (pointsEarned > 0L) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "+$pointsEarned puntos",
                style = MaterialTheme.typography.headlineMedium,
                color = RaizPurple,
            )
        }

        // Enlace al comercio en Stellar Expert — affordance de verificación on-chain
        if (merchantAddress.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = {
                    StellarExpert.open(context, StellarExpert.addressUrl(merchantAddress))
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(16.dp),
                    tint = RaizGreen,
                )
                Text(
                    text = "Ver comercio en Stellar Expert",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizGreen,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Volver al inicio", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PayError(
    message: String,
    onDone: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pago no completado",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizBlack,
                contentColor = RaizWhite,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Volver", style = MaterialTheme.typography.labelLarge)
        }
    }
}
