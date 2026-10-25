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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.CloudOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.BuildConfig
import com.raiz.app.data.model.PaymentPreview
import com.raiz.app.data.model.RaizErrorCode
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
 * Pantalla de pago. Recibe el merchant ya seleccionado (nav arg `merchant_address`,
 * desde el escáner de WalletScreen o el botón "Pagar aquí" del mapa).
 *
 * Flow:
 *   1. Muestra merchant + monto + toggle Tip Barrio + total + puntos a ganar.
 *   2. Con señal: "Confirmar pago" → SorobanClient.payMerchant → Success | Error.
 *      Sin señal (o switch de debug): "Firmar sin señal" → sobre de pago diferido → Signed (QR).
 *      La decisión se toma ANTES de confirmar, nunca como respaldo de un pago en línea fallido.
 */
@Composable
fun PayScreen(
    onDone: () -> Unit = {},
    onVerSobres: () -> Unit = {},
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
                onToggleSinSenal = viewModel::toggleSinSenalDebug,
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
            is PayUiState.Signed -> {
                // En cuanto el QR está en pantalla, la promesa puede estar viajando: ENTREGADO.
                LaunchedEffect(s.record.id) { viewModel.onQrShown() }
                DeferredSignedScreen(
                    record = s.record,
                    validUntilLabel = s.validUntilLabel,
                    onVerSobres = onVerSobres,
                    onDone = onDone,
                    contentPadding = padding,
                )
            }
            is PayUiState.Error -> PayError(
                code = s.code,
                origen = s.origen,
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
    onToggleSinSenal: () -> Unit,
    onConfirm: () -> Unit,
    contentPadding: PaddingValues,
) {
    val preview = state.preview
    val activity = LocalContext.current as? FragmentActivity
    val bloqueadoSinSenal = state.sinSenal && state.sinSenalNoDisponible != null
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Contenido con scroll + botón fijo abajo: con los avisos de "sin señal" el botón se saldría.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Pagar",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
            )

            MerchantCard(preview = preview)

            if (state.comercioDesdeCache) {
                CacheBanner()
            }

            // Banner amarillo cuando el monto viene fijo desde el QR del comerciante
            if (state.esOrdenDeCobro) {
                OrdenDeCobroBanner(amountStroops = preview.baseAmountStroops)
            }

            if (state.sinSenal) {
                SinSenalBanner(motivoNoDisponible = state.sinSenalNoDisponible)
            }

            TipToggle(
                enabled = state.tipEnabled,
                pointsToEarn = preview.pointsToEarn,
                tipUsdc = preview.tipUsdc,
                onToggle = onToggleTip,
            )

            Breakdown(preview = preview)

            if (BuildConfig.DEBUG) {
                DebugSinSenalSwitch(checked = state.sinSenalForzado, onToggle = onToggleSinSenal)
            }

            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (state.requireBiometric) {
                    biometricConfirm(
                        activity = activity,
                        title = if (state.sinSenal) "Firmar sin señal" else "Confirmar pago",
                        subtitle = "Autoriza con huella, rostro o PIN",
                        onSuccess = onConfirm,
                    )
                } else {
                    onConfirm()
                }
            },
            enabled = !state.submitting && !bloqueadoSinSenal,
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
                    imageVector = if (state.sinSenal) Icons.Outlined.CloudOff else Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = if (state.sinSenal) "Firmar sin señal" else "Confirmar pago",
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

/** Sin señal: el botón pasa a firmar un sobre de pago diferido; si no se puede, dice por qué. */
@Composable
private fun SinSenalBanner(motivoNoDisponible: String?) {
    Row(
        modifier = Modifier
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
                text = motivoNoDisponible
                    ?: "Vas a firmar un sobre de pago diferido. El comercio lo cobra cuando cualquier " +
                        "dispositivo del territorio tenga señal.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
        }
    }
}

/** Los datos del comercio vienen de la última consulta con señal (nunca inventados). */
@Composable
private fun CacheBanner() {
    Text(
        text = "Datos del comercio de la última vez que hubo señal.",
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
        color = RaizBlack.copy(alpha = 0.55f),
    )
}

/** Solo en debug: simula "sin señal" con red para grabar la demo sin tocar las radios. */
@Composable
private fun DebugSinSenalSwitch(checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RaizPurple.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Firmar sin señal (debug)",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizPurple,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = RaizWhite,
                checkedTrackColor = RaizPurple,
                uncheckedThumbColor = RaizWhite,
                uncheckedTrackColor = RaizGrayLight,
            ),
        )
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
    code: RaizErrorCode,
    origen: ErrorOrigen,
    message: String,
    onDone: () -> Unit,
    contentPadding: PaddingValues,
) {
    // La ayuda de "firmar sin señal" SOLO si el error salió antes de intentar ninguna transacción.
    // Tras un pago fallido podría haber una tx ya enviada: nunca invitar a firmar un sobre encima.
    val ayuda = when {
        code != RaizErrorCode.NETWORK_ERROR -> null
        origen == ErrorOrigen.CARGA ->
            "Si no tienes señal: conéctate un momento para que la app conozca este comercio y vuelve a " +
                "entrar al pago; después podrás firmar sin señal."
        else -> "Antes de volver a intentar, comprueba en Movimientos si el pago salió."
    }
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
        if (ayuda != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = ayuda,
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
        }
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
