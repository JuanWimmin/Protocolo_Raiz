package com.raiz.app.ui.treasury

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
import com.raiz.app.ui.theme.RaizGrayLight
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Pantalla "Tesorería que rinde".
 *
 * Muestra el fondo de la tesorería RAÍZ puesto a rendir en el vault USDC de
 * DeFindex: valor actual, rendimiento generado, APY y precio por share — todo
 * leído on-chain. Permite depositar/rescatar firmando como tesorería.
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

@Composable
private fun Body(state: YieldUiState, viewModel: YieldViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard(state = state)
        StatsRow(state = state)
        StrategyCard()
        ActionCard(state = state, viewModel = viewModel)

        Text(
            text = "Capital de reserva de la tesorería RAÍZ rindiendo on-chain en DeFindex. " +
                "El fondo de cada barrio también rinde vía el contrato Pool " +
                "(deposit_idle_to_vault) — míralo en Transparencia.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

// ── Hero: valor actual + rendimiento + APY ───────────────────────────────────

@Composable
private fun HeroCard(state: YieldUiState) {
    val valueStroops = state.position?.currentValueStroops ?: 0L
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(RaizBlack)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Fondo rindiendo en DeFindex",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizWhite.copy(alpha = 0.7f),
        )
        Text(
            text = valueStroops.formatUsdc(),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
            ),
            color = RaizWhite,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.TrendingUp,
                    contentDescription = null,
                    tint = RaizYellow,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "+${state.yieldStroops.formatUsdc()} generado",
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizYellow,
                )
            }
            ApyBadge(apyBps = state.apyBps)
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

// ── Stats: APY · precio por share · TVL ──────────────────────────────────────

@Composable
private fun StatsRow(state: YieldUiState) {
    val stats = state.stats
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(
            label = "APY",
            value = state.apyBps?.let { "%.2f%%".format(it / 100.0) } ?: "—",
            accent = RaizGreen,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Precio / share",
            value = (stats?.pricePerShareStroops ?: 0L).formatUsdc(),
            accent = RaizPurple,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "TVL del vault",
            value = (stats?.tvlStroops ?: 0L).formatUsdc(),
            accent = RaizBlack,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
            color = RaizBlack.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            ),
            color = accent,
        )
    }
}

@Composable
private fun StrategyCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RaizPurple.copy(alpha = 0.08f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.TrendingUp, contentDescription = null, tint = RaizPurple)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Estrategia: Blend (USDC)",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = "Vault de DeFindex auditado por OtterSec · liquidez disponible al rescatar",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Acciones: depositar / rescatar ───────────────────────────────────────────

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
            text = "Mover fondos",
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
                Text("Rescatar todo", style = MaterialTheme.typography.labelLarge, color = RaizBlack)
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
            CircularProgressIndicator(color = RaizGreen, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(8.dp))
            Text("Enviando a la red…", style = MaterialTheme.typography.bodyMedium, color = RaizBlack.copy(alpha = 0.7f))
        }
        is TreasuryAction.Ok -> Text(
            "✓ ${action.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizGreen,
        )
        is TreasuryAction.Failed -> Text(
            action.message,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizError,
        )
    }
}

// ── Estados auxiliares ───────────────────────────────────────────────────────

@Composable
private fun CenteredLoader() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(color = RaizGreen) }
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
            "No pudimos cargar el vault.\n$message",
            color = RaizBlack.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
