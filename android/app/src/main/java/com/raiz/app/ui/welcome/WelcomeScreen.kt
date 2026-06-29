package com.raiz.app.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGrayLight
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Primera pantalla cuando no hay wallet guardada en el dispositivo.
 *
 * Cuatro caminos:
 *   1. Crear wallet → CreateWalletScreen genera 12 palabras BIP-39.
 *   2. Importar wallet → ImportWalletScreen recibe 12 palabras y deriva.
 *   3. Crear con passkey → CreatePasskeyWalletScreen (smart account secp256r1).
 *      Solo visible si BuildConfig.PASSKEY_RP_ID no está vacío Y el dispositivo
 *      tiene Android 9+ (API 28). En builds sin local.properties este botón se
 *      oculta automáticamente.
 *   4. Modo demo → usa la cuenta `raiz-tourist` del seed (sin guardar nada).
 *      Solo disponible si BuildConfig.DEMO_TOURIST_SECRET está configurado.
 */
@Composable
fun WelcomeScreen(
    demoEnabled: Boolean,
    onCreateWallet: () -> Unit,
    onImportWallet: () -> Unit,
    onUseDemo: () -> Unit,
    onSeeDashboard: () -> Unit = {},
    passkeyEnabled: Boolean = false,
    onCreatePasskeyWallet: () -> Unit = {},
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header()
            Spacer(modifier = Modifier.height(16.dp))

            ChoiceCard(
                icon = Icons.Outlined.AddCircleOutline,
                title = "Crear wallet nueva",
                subtitle = "Genera 12 palabras de respaldo y empieza a aportar a tu barrio.",
                accent = RaizGreen,
                onClick = onCreateWallet,
            )
            ChoiceCard(
                icon = Icons.Outlined.RestoreFromTrash,
                title = "Importar wallet",
                subtitle = "Ya tengo mis 12 palabras de respaldo.",
                accent = RaizPurple,
                onClick = onImportWallet,
            )
            if (passkeyEnabled) {
                ChoiceCard(
                    icon = Icons.Outlined.Fingerprint,
                    title = "Crear con passkey",
                    subtitle = "Sin palabras de respaldo. Usa huella, rostro o PIN (Android 9+, smart account).",
                    accent = RaizPurple,
                    onClick = onCreatePasskeyWallet,
                )
            }
            if (demoEnabled) {
                ChoiceCard(
                    icon = Icons.Outlined.PlayCircle,
                    title = "Probar el modo demo",
                    subtitle = "Usar la cuenta de prueba con datos del seed (sin guardar nada).",
                    accent = RaizYellow,
                    onClick = onUseDemo,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Link a la transparencia pública del barrio.
            Text(
                text = "Ver transparencia del barrio →",
                style = MaterialTheme.typography.labelLarge,
                color = RaizGreen,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { onSeeDashboard() })
                },
            )

            Text(
                text = "Tu seed phrase nunca sale del dispositivo.\nEstá cifrada con el Android Keystore.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                color = RaizBlack.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(RaizGreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Eco,
                contentDescription = null,
                tint = RaizWhite,
            )
        }
        Spacer(modifier = Modifier.size(14.dp))
        Column {
            Text(
                text = "RAÍZ",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = RaizBlack,
            )
            Text(
                text = "Tu paga, el barrio crece",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RaizWhite)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent)
        }
        Spacer(modifier = Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
        @Suppress("UNUSED_EXPRESSION") RaizGrayLight  // referenced para evitar warning
    }
}
