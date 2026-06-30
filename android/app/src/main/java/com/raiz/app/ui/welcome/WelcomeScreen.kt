package com.raiz.app.ui.welcome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Pantalla de entrada cuando no hay wallet guardada en el dispositivo.
 *
 * Dos flujos principales:
 *   1. "Crear cuenta"   → [CrearCuentaScreen]: elige método de protección (passkey vs. seed).
 *   2. "Iniciar sesión" → [IniciarSesionScreen]: passkey existente o importar seed.
 *
 * Accesos secundarios:
 *   - "Probar modo demo": acceso rápido como turista (solo si DEMO_TOURIST_SECRET configurado).
 *   - "Ver transparencia del barrio": Dashboard público accesible sin login.
 *
 * @param demoEnabled       true si BuildConfig.DEMO_TOURIST_SECRET no está vacío.
 * @param onSignIn          Usuario ya tiene cuenta → IniciarSesionScreen.
 * @param onCreateAccount   Usuario nuevo         → CrearCuentaScreen.
 * @param onUseDemo         Modo demo como turista (sin guardar wallet).
 * @param onSeeDashboard    Navega al Dashboard de transparencia pública.
 */
@Composable
fun WelcomeScreen(
    demoEnabled: Boolean,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onUseDemo: () -> Unit,
    onSeeDashboard: () -> Unit = {},
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            // Branding RAÍZ
            Header()

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Pagos turísticos en USDC que construyen tu barrio, on-chain y transparentes.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(40.dp))

            // CTA principal: usuario nuevo crea su cuenta
            Button(
                onClick = onCreateAccount,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizYellow,
                    contentColor = RaizBlack,
                ),
            ) {
                Text(
                    text = "Crear cuenta",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // CTA secundario: usuario ya registrado vuelve a entrar
            OutlinedButton(
                onClick = onSignIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, RaizBlack.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizBlack),
            ) {
                Text(
                    text = "Iniciar sesión",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // Modo demo — enlace secundario (solo si está habilitado en BuildConfig)
            if (demoEnabled) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Probar modo demo",
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizYellow,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .pointerInput(Unit) { detectTapGestures(onTap = { onUseDemo() }) }
                        .padding(vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Enlace público — no requiere wallet
            Text(
                text = "Ver transparencia del barrio →",
                style = MaterialTheme.typography.labelLarge,
                color = RaizGreen,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { onSeeDashboard() })
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tu seed phrase nunca sale del dispositivo.\nCifrada con el Android Keystore.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                color = RaizBlack.copy(alpha = 0.4f),
            )
        }
    }
}

// ── Branding header ────────────────────────────────────────────────────────────

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
