package com.raiz.app.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite

/**
 * Pantalla de selección de método de protección para una cuenta nueva.
 *
 * Muestra las opciones disponibles para crear el wallet del usuario:
 *   - Con passkey (recomendado): WebAuthn secp256r1, sin palabras de respaldo.
 *     Solo visible si el dispositivo y la compilación lo soportan [passkeyEnabled].
 *   - Con frase semilla: 12 palabras BIP-39 cifradas en el Android Keystore.
 *
 * Esta pantalla NO crea la wallet: delega en [CreatePasskeyWalletScreen] o
 * [CreateWalletScreen] según la elección del usuario.
 *
 * @param passkeyEnabled  true si el dispositivo tiene API >= 28 y rpId configurado.
 * @param onBack          Vuelve a [WelcomeScreen].
 * @param onPasskey       Navega a [CreatePasskeyWalletScreen].
 * @param onSeedPhrase    Navega a [CreateWalletScreen].
 */
@Composable
fun CrearCuentaScreen(
    passkeyEnabled: Boolean,
    onBack: () -> Unit,
    onPasskey: () -> Unit,
    onSeedPhrase: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Barra superior con botón atrás
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowBack,
                        contentDescription = "Volver",
                        tint = RaizBlack,
                    )
                }
                Text(
                    text = "Crea tu cuenta",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = RaizBlack,
                )
            }

            Spacer(modifier = Modifier.size(8.dp))

            Text(
                text = "Elige cómo quieres proteger tu wallet RAÍZ.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.size(24.dp))

            // Opción passkey — solo si el dispositivo y la config lo soportan
            if (passkeyEnabled) {
                AuthMethodCard(
                    icon = Icons.Outlined.Fingerprint,
                    title = "Con passkey",
                    subtitle = "Sin palabras de respaldo. Tu huella, rostro o PIN crean un smart account en Stellar. Requiere Android 9+.",
                    accent = RaizPurple,
                    badge = "Recomendado",
                    onClick = onPasskey,
                )
                Spacer(modifier = Modifier.size(12.dp))
            }

            // Opción frase semilla — siempre disponible
            AuthMethodCard(
                icon = Icons.Outlined.VpnKey,
                title = "Con frase semilla",
                subtitle = "12 palabras BIP-39 cifradas con el Android Keystore. Anótalas en papel y guárdalas en un lugar seguro.",
                accent = RaizGreen,
                badge = null,
                onClick = onSeedPhrase,
            )

            Spacer(modifier = Modifier.weight(1f))

            Spacer(modifier = Modifier.size(24.dp))

            Text(
                text = "Tu seed phrase nunca sale del dispositivo.\nCifrada con el Android Keystore.",
                style = MaterialTheme.typography.bodySmall,
                color = RaizBlack.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// ── Card de opción de autenticación ───────────────────────────────────────────

/**
 * Tarjeta de opción de método de autenticación.
 *
 * @param icon      Icono representativo del método.
 * @param title     Título breve (ej. "Con passkey").
 * @param subtitle  Descripción del método.
 * @param accent    Color temático de la opción.
 * @param badge     Texto del badge opcional (ej. "Recomendado"). null para ocultarlo.
 * @param onClick   Acción al pulsar la tarjeta.
 */
@Composable
private fun AuthMethodCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    badge: String? = null,
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
        // Icono con fondo de acento
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(26.dp),
            )
        }

        Spacer(modifier = Modifier.size(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = RaizBlack,
                )
                // Badge opcional "Recomendado"
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
    }
}
