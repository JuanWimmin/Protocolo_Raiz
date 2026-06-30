package com.raiz.app.ui.become_resident

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Barrios disponibles para residir (hex de 32 bytes → nombre legible).
 * Coincide con el seed (`scripts/seed_testnet.sh`) y con [RoleResolver].
 */
private val RESIDENT_BARRIOS: Map<String, String> = linkedMapOf(
    "ce47120000000000000000000000000000000000000000000000000000000001" to "Centro Histórico",
    "bba17e0000000000000000000000000000000000000000000000000000000002" to "Barrio Norte",
    "c057a9000000000000000000000000000000000000000000000000000000000a" to "Costa Vieja",
)

/**
 * Pantalla "Soy residente" — el usuario elige a qué barrio pertenece.
 *
 * Es el espejo de onboarding del flujo de comerciante, pero SIN registro
 * on-chain aquí: la residencia (soulbound) la mintea el admin más tarde,
 * cuando el usuario pulsa "Verificar como residente" en Propuestas. Esta
 * pantalla solo persiste el rol RESIDENT + el barrio elegido y entra a la app.
 *
 * @param onBack    Vuelve a la selección de rol.
 * @param onConfirm Recibe el hex del barrio elegido. El caller (MainActivity)
 *                  persiste el rol, guarda el barrio pendiente y navega.
 */
@Composable
fun BecomeResidentScreen(
    onBack: () -> Unit,
    onConfirm: (barrioId: String) -> Unit,
) {
    // Barrio seleccionado (null = aún no eligió → botón deshabilitado).
    var selectedBarrio by remember { mutableStateOf<String?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Cabecera ───────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
                }
                Text(
                    "Soy residente",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = RaizBlack,
                )
            }

            Text(
                text = "Elige el barrio donde resides. Podrás proponer y votar cómo se usa su fondo comunitario.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )

            Text(
                "Tu barrio",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )

            // ── Lista de barrios (elegir 1) ────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RESIDENT_BARRIOS.forEach { (id, name) ->
                    BarrioOption(
                        name = name,
                        selected = selectedBarrio == id,
                        onClick = { selectedBarrio = id },
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            DemoNote()

            // ── Confirmar ──────────────────────────────────────────────────
            val barrio = selectedBarrio
            Button(
                onClick = { barrio?.let(onConfirm) },
                enabled = barrio != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizPurple,
                    contentColor = RaizWhite,
                    disabledContainerColor = RaizPurple.copy(alpha = 0.4f),
                    disabledContentColor = RaizWhite.copy(alpha = 0.7f),
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Outlined.HowToVote, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = barrio?.let { "Entrar como residente de ${RESIDENT_BARRIOS[it]}" }
                        ?: "Elige tu barrio",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Componentes
// ─────────────────────────────────────────────────────────────────────────────

/** Card seleccionable de barrio (estilo radio). */
@Composable
private fun BarrioOption(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) RaizPurple.copy(alpha = 0.12f) else RaizWhite)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(RaizPurple.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.LocationCity,
                contentDescription = null,
                tint = RaizPurple,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = RaizBlack,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (selected) "Seleccionado" else null,
            tint = if (selected) RaizPurple else RaizBlack.copy(alpha = 0.3f),
        )
    }
}

/** Aviso de modo demo — coherente con el flujo de comerciante. */
@Composable
private fun DemoNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RaizYellow.copy(alpha = 0.18f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Demo: tu residencia se aprueba al instante (el admin del seed firma por ti) cuando pulses \"Verificar\" en Propuestas. En producción pasaría por validación de documentos de residencia.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = RaizBlack.copy(alpha = 0.7f),
        )
    }
}
