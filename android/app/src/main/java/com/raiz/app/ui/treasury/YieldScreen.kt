package com.raiz.app.ui.treasury

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Pantalla "Tesorería que rinde".
 *
 * Muestra:
 *  1. Tarjeta global del vault DeFindex (APY, TVL, precio por share, estrategia).
 *  2. Lista per-barrio: posición de cada uno de los 3 barrios del demo en el vault
 *     (depositado, valor actual, rendimiento).
 *  3. Sección secundaria "Reserva del protocolo": deposit/withdraw del capital
 *     admin del protocolo.
 */
@Composable
fun YieldScreen(
    onBack: () -> Unit,
    viewModel: YieldViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TopBar(onBack = onBack, onRefresh = viewModel::refresh)

            when {
                state.loading && state.stats == null -> CenteredLoader()
                state.error != null && state.stats == null -> ErrorBox(state.error!!)
                else -> Body(state = state, viewModel = viewModel)
            }
        }
    }
}

// ── Barra superior ───────────────────────────────────────────────────────────

@Composable
private fun TopBar(onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
        }
        Text(
            text = "Tesorería que rinde",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refrescar", tint = RaizBlack)
        }
    }
}

// ── Cuerpo principal ─────────────────────────────────────────────────────────

@Composable
private fun Body(state: YieldUiState, viewModel: YieldViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Tarjeta global del vault DeFindex
        VaultGlobalCard(state = state)

        // 2. Posición por barrio
        SectionLabel(texto = "Posición por barrio")

        if (state.barriosYield.isEmpty()) {
            // Solo ocurre si todos los getVaultShares fallaron (red caída, etc.)
            Text(
                text = "No se pudieron cargar los datos de barrios.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.5f),
            )
        } else {
            state.barriosYield.forEach { item ->
                BarrioYieldCard(item = item)
            }
        }

        // 3. Reserva del protocolo (capital admin)
        SectionLabel(texto = "Reserva del protocolo")
        ActionCard(state = state, viewModel = viewModel)

        // Nota informativa al pie
        Text(
            text = "Un solo vault USDC compartido que rinde on-chain. Los fondos de " +
                "cada barrio y la reserva del protocolo aportan al mismo vault DeFindex " +
                "(Blend, auditado OtterSec). Liquidez disponible al rescatar.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

// ── Tarjeta global del vault ─────────────────────────────────────────────────

/**
 * Muestra los stats globales del vault DeFindex: APY, TVL, precio por share
 * y la estrategia (Blend USDC). Contexto visual: "un solo vault compartido".
 */
@Composable
private fun VaultGlobalCard(state: YieldUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(RaizBlack)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Encabezado: nombre del vault + badge APY
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "Vault DeFindex · USDC",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizWhite.copy(alpha = 0.7f),
                )
                Text(
                    text = "Un solo vault compartido que rinde",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = RaizWhite.copy(alpha = 0.45f),
                )
            }
            ApyBadge(apyBps = state.apyBps)
        }

        // TVL y precio por share en fila
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // TVL
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TVL del vault",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = RaizWhite.copy(alpha = 0.5f),
                )
                Text(
                    text = (state.stats?.tvlStroops ?: 0L).formatUsdc(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = RaizYellow,
                )
            }
            // Precio por share
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Precio / share",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = RaizWhite.copy(alpha = 0.5f),
                )
                Text(
                    text = (state.stats?.pricePerShareStroops ?: 0L).formatUsdc(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = RaizWhite,
                )
            }
        }

        // Estrategia
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.TrendingUp,
                contentDescription = null,
                tint = RaizGreen,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Blend (USDC) · auditado por OtterSec",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                color = RaizWhite.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun ApyBadge(apyBps: Int?) {
    val label = apyBps?.let { "%.2f%% APY".format(it / 100.0) } ?: "Rinde on-chain"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(RaizGreen)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = RaizWhite,
        )
    }
}

// ── Card por barrio ──────────────────────────────────────────────────────────

/**
 * Muestra la posición de un barrio en el vault: depositado, valor actual y
 * rendimiento (en verde). Los valores difieren entre barrios según las shares
 * que cada uno tenga acumuladas on-chain.
 */
@Composable
private fun BarrioYieldCard(item: BarrioYieldItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Nombre del barrio con acento púrpura izquierdo (borde decorativo via padding)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(RaizPurple),
            )
            Text(
                text = item.nombre,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
            )
        }

        // Tres métricas en fila: depositado | valor actual | rendimiento
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarrioYieldStat(
                label = "Depositado",
                value = item.depositadoStroops.formatUsdc(),
                valueColor = RaizBlack,
                modifier = Modifier.weight(1f),
            )
            BarrioYieldStat(
                label = "Valor actual",
                value = item.valorActualStroops.formatUsdc(),
                valueColor = RaizBlack,
                modifier = Modifier.weight(1f),
            )
            BarrioYieldStat(
                label = "Rendimiento",
                value = "+${item.rendimientoStroops.formatUsdc()}",
                valueColor = RaizGreen,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Una sola métrica dentro de [BarrioYieldCard]: etiqueta + valor formateado. */
@Composable
private fun BarrioYieldStat(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
            color = RaizBlack.copy(alpha = 0.5f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = valueColor,
        )
    }
}

// ── Sección "Reserva del protocolo": deposit / withdraw del admin ─────────────

@Composable
private fun ActionCard(state: YieldUiState, viewModel: YieldViewModel) {
    val submitting = state.action is TreasuryAction.Submitting
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Mover fondos (admin)",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        OutlinedTextField(
            value = state.amountInput,
            onValueChange = viewModel::onAmountChange,
            label = { Text("Monto en USDC") },
            singleLine = true,
            enabled = !submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = viewModel::deposit,
                enabled = !submitting,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizGreen,
                    contentColor = RaizWhite,
                    disabledContainerColor = RaizGreen.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Depositar", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = viewModel::withdrawAll,
                enabled = !submitting && (state.position?.shares ?: 0L) > 0L,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "Rescatar todo",
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizBlack,
                )
            }
        }
        ActionFeedback(action = state.action)
    }
}

@Composable
private fun ActionFeedback(action: TreasuryAction) {
    when (action) {
        TreasuryAction.Idle -> Unit
        TreasuryAction.Submitting -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = RaizGreen,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Enviando a la red…",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
        }
        is TreasuryAction.Ok -> Text(
            text = "Confirmado on-chain: ${action.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizGreen,
        )
        is TreasuryAction.Failed -> Text(
            text = action.message,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizError,
        )
    }
}

// ── Helpers de layout ────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.labelLarge,
        color = RaizBlack.copy(alpha = 0.6f),
    )
}

// ── Estados loading / error ──────────────────────────────────────────────────

@Composable
private fun CenteredLoader() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = RaizGreen)
    }
}

@Composable
private fun ErrorBox(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No pudimos cargar el vault.\n$message",
            color = RaizBlack.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
