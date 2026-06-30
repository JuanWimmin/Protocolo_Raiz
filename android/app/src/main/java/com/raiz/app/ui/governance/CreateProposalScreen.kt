package com.raiz.app.ui.governance

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite

/**
 * Formulario para crear una propuesta de gasto del fondo del barrio.
 *
 * Campos:
 *   - Descripción (texto libre, máx 200 chars)
 *   - Monto solicitado (USDC → se convierte a stroops ×10_000_000)
 *   - Beneficiario (Stellar address G... de 56 chars)
 *   - Duración (3-14 días, slider)
 *
 * Al confirmar: llama [SorobanClient.createProposal] y vuelve a
 * ProposalsScreen (onSuccess).
 */
@Composable
fun CreateProposalScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: CreateProposalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (state.success) {
            SuccessContent(
                proposalId = state.successProposalId,
                onDone = onSuccess,
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Header con botón atrás ─────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
                }
                Text(
                    text = "Nueva propuesta",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = RaizBlack,
                )
            }

            Text(
                text = "Propón cómo usar el fondo del barrio. Los residentes votarán y, si se alcanza el quórum y mayoría, la tesorería ejecuta la transferencia automáticamente.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )

            // ── Descripción ────────────────────────────────────────────
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::updateDescription,
                label = { Text("Descripción") },
                placeholder = { Text("Ej: Pintar el mural de la plaza central") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                supportingText = {
                    Text(
                        "${state.description.length}/200",
                        style = MaterialTheme.typography.bodySmall,
                        color = RaizBlack.copy(alpha = 0.4f),
                    )
                },
            )

            // ── Monto en USDC ──────────────────────────────────────────
            OutlinedTextField(
                value = state.amountUsdc,
                onValueChange = viewModel::updateAmountUsdc,
                label = { Text("Monto solicitado (USDC)") },
                placeholder = { Text("Ej: 50.00") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                isError = state.amountUsdc.isNotEmpty() && !state.amountValid,
                supportingText = {
                    if (state.amountUsdc.isNotEmpty() && !state.amountValid) {
                        Text("Ingresa un monto mayor a 0", color = Color(0xFFB00020))
                    } else if (state.amountValid) {
                        Text(
                            "${state.amountStroops} stroops",
                            color = RaizBlack.copy(alpha = 0.4f),
                        )
                    }
                },
            )

            // ── Beneficiario ───────────────────────────────────────────
            OutlinedTextField(
                value = state.recipient,
                onValueChange = viewModel::updateRecipient,
                label = { Text("Beneficiario (Stellar address G…)") },
                placeholder = { Text("GABC…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                isError = state.recipient.isNotEmpty() && !state.recipientValid,
                supportingText = {
                    if (state.recipient.isNotEmpty() && !state.recipientValid) {
                        Text(
                            "Debe ser una Stellar address G… de 56 caracteres",
                            color = Color(0xFFB00020),
                        )
                    }
                },
            )

            // ── Duración (slider 3-14 días) ────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Duración de la votación: ${state.durationDays} días",
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizBlack,
                )
                Slider(
                    value = state.durationDays.toFloat(),
                    onValueChange = { viewModel.updateDuration(it.toInt()) },
                    valueRange = 3f..14f,
                    steps = 10, // 3,4,5,6,7,8,9,10,11,12,13,14 = 12 valores, 10 pasos
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = RaizPurple,
                        activeTrackColor = RaizPurple,
                        inactiveTrackColor = RaizBlack.copy(alpha = 0.15f),
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("3 días", style = MaterialTheme.typography.bodySmall, color = RaizBlack.copy(alpha = 0.4f))
                    Text("14 días", style = MaterialTheme.typography.bodySmall, color = RaizBlack.copy(alpha = 0.4f))
                }
            }

            // ── Error global ───────────────────────────────────────────
            if (state.error != null) {
                Text(
                    text = state.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB00020),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── CTA ────────────────────────────────────────────────────
            Button(
                onClick = { viewModel.submit() },
                enabled = state.canSubmit,
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
                if (state.submitting) {
                    CircularProgressIndicator(color = RaizWhite, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Publicar propuesta", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun SuccessContent(proposalId: Long?, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(RaizPurple.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = RaizPurple,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "¡Propuesta publicada!",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
        )
        if (proposalId != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "ID on-chain: #$proposalId",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.5f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Los residentes ya pueden votar. Si alcanza el quórum (30%) y mayoría simple, la tesorería ejecutará la transferencia automáticamente.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizPurple,
                contentColor = RaizWhite,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Ver propuestas", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RaizPurple,
    cursorColor = RaizPurple,
    focusedLabelColor = RaizPurple,
)
