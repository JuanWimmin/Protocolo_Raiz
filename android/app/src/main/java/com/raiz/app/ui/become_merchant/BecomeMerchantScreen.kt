package com.raiz.app.ui.become_merchant

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.model.MerchantCategory
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Pantalla "Registrarme como comerciante".
 *
 * El usuario llena nombre, categoría y barrio. Al tap "Registrar", la app
 * firma con el demoAdminKeyPair y llama Pool.register_merchant on-chain.
 * Tras éxito, el rol del usuario se vuelve MERCHANT automáticamente la
 * próxima vez que el RoleResolver consulte (cache invalidado en el VM).
 *
 * Las coordenadas lat/lng las hereda del barrio elegido + un jitter para
 * evitar pines superpuestos en el mapa.
 */
@Composable
fun BecomeMerchantScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: BecomeMerchantViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (state.success) {
            SuccessContent(padding = padding, onDone = onSuccess)
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
                }
                Text(
                    "Soy comerciante",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = RaizBlack,
                )
            }

            Text(
                text = "Registra tu negocio en RAÍZ. Tus clientes podrán pagarte en USDC y, con cada pago con Tip Barrio, aportar al fondo de tu zona.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )

            // ── Nombre ─────────────────────────────────────────────────
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = { Text("Nombre del negocio") },
                placeholder = { Text("Ej: Café Don Aurelio") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RaizGreen,
                    cursorColor = RaizGreen,
                    focusedLabelColor = RaizGreen,
                ),
            )

            // ── Categoría ──────────────────────────────────────────────
            Text(
                "Categoría",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MerchantCategory.entries.toList()) { c ->
                    ChipChoice(
                        label = c.label,
                        selected = state.category == c,
                        onClick = { viewModel.selectCategory(c) },
                    )
                }
            }

            // ── Barrio ─────────────────────────────────────────────────
            Text(
                "Barrio",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.barrioOptions) { (id, name) ->
                    ChipChoice(
                        label = name,
                        selected = state.barrioId == id,
                        onClick = { viewModel.selectBarrio(id) },
                    )
                }
            }

            if (state.error != null) {
                Text(
                    text = state.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB00020),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Aviso modo demo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(RaizYellow.copy(alpha = 0.18f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Demo: el registro se aprueba al instante con la cuenta de admin del seed. En producción pasaría por revisión del admin del barrio.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = RaizBlack.copy(alpha = 0.7f),
                )
            }

            Button(
                onClick = { viewModel.submit() },
                enabled = state.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizGreen,
                    contentColor = RaizWhite,
                    disabledContainerColor = RaizGreen.copy(alpha = 0.4f),
                    disabledContentColor = RaizWhite.copy(alpha = 0.7f),
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(color = RaizWhite, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Storefront, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Registrarme como comerciante", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ChipChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) RaizWhite else RaizBlack,
            )
        },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = if (selected) RaizGreen else RaizBlack.copy(alpha = 0.08f),
        ),
        border = null,
    )
}

@Composable
private fun SuccessContent(padding: PaddingValues, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(RaizGreen.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = RaizGreen,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "¡Negocio registrado!",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Tu address ya es un comercio RAÍZ. Cualquier turista podrá escanear tu QR (Perfil → Mi QR) y pagarte en USDC.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Volver al perfil", style = MaterialTheme.typography.labelLarge)
        }
    }
}

