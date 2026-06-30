package com.raiz.app.ui.dashboard

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.ui.util.StellarExpert
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import com.raiz.app.data.model.Execution
import com.raiz.app.data.model.Proposal
import com.raiz.app.data.model.ProposalStatus
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGrayLight
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Sexta pantalla del spec — Dashboard de transparencia pública.
 *
 * Sin login (accesible desde Welcome). Muestra para cada barrio:
 *   - Stats del Pool (pool_balance / total_collected / tx_count / unique_tourists).
 *   - Barra apilada: % usado del fondo (ejecuciones) vs % disponible.
 *   - Lista de ejecuciones registradas en Treasury, con link a Stellar Expert
 *     por txHash (apre el navegador externo).
 *
 * Selector de barrio en chips arriba.
 */
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    onOpenYield: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val nowUnix = remember { System.currentTimeMillis() / 1000L }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TopBar(
                title = "Transparencia",
                onBack = onBack,
                onRefresh = viewModel::refresh,
            )

            BarrioSelector(
                barrios = state.barriosMeta,
                selected = state.selectedBarrioId,
                onSelect = viewModel::selectBarrio,
            )

            // Entrada a "Tesorería que rinde" — siempre visible (no depende de
            // que carguen los datos on-chain del barrio).
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                YieldCta(onClick = onOpenYield)
            }

            when {
                state.loading && state.barrio == null -> LoadingState(PaddingValues(0.dp))
                state.error != null && state.barrio == null -> ErrorState(state.error!!)
                state.barrio != null -> DashboardBody(
                    state = state,
                    nowUnix = nowUnix,
                    onCloseVoting = viewModel::closeVoting,
                    onExecute = viewModel::executeProposal,
                )
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
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
            text = title,
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
private fun BarrioSelector(
    barrios: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        barrios.forEach { (id, name) ->
            AssistChip(
                onClick = { onSelect(id) },
                label = {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (id == selected) RaizWhite else RaizBlack,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (id == selected) RaizGreen else RaizGrayLight.copy(alpha = 0.5f),
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun DashboardBody(
    state: DashboardUiState,
    nowUnix: Long,
    onCloseVoting: (Long) -> Unit,
    onExecute: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("stats") { StatsGrid(state = state) }
        item("usage") { UsageBar(state = state) }
        if (state.vaultSharesStroops > 0L) {
            item("vault-pos") { VaultPositionCard(state.vaultSharesStroops) }
        }

        // ── Propuestas (con countdown + acciones trustless) ──────────
        item("proposals-title") {
            Text(
                text = "Propuestas activas (${state.proposals.size})",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (state.proposals.isEmpty()) {
            item("proposals-empty") {
                Text(
                    text = "No hay propuestas abiertas en este barrio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            items(state.proposals, key = { "prop-${it.id}" }) { p ->
                ProposalRow(
                    proposal = p,
                    nowUnix = nowUnix,
                    action = state.proposalAction[p.id],
                    onCloseVoting = { onCloseVoting(p.id) },
                    onExecute = { onExecute(p.id) },
                )
            }
        }

        // ── Ejecuciones registradas ──────────────────────────────────
        item("executions-title") {
            Text(
                text = "Ejecuciones registradas (${state.executions.size})",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (state.executions.isEmpty()) {
            item("exec-empty") {
                Text(
                    text = "Aún no hay propuestas ejecutadas en este barrio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            items(state.executions, key = { it.txHash + it.executedAt }) { exec ->
                ExecutionRow(exec)
            }
        }

        // ── Contratos verificados (footer de transparencia) ──────────────
        if (state.contractPool.isNotBlank()) {
            item("contracts-title") {
                Text(
                    text = "Contratos verificados",
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizBlack,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item("contracts") {
                ContratosCard(state = state)
            }
        }
    }
}

@Composable
private fun ProposalRow(
    proposal: Proposal,
    nowUnix: Long,
    action: ProposalActionState?,
    onCloseVoting: () -> Unit,
    onExecute: () -> Unit,
) {
    val secondsToClose = (proposal.closesAt - nowUnix).coerceAtLeast(0L)
    val closed = proposal.closesAt <= nowUnix
    val isPassed = proposal.status == ProposalStatus.PASSED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header con id + título + monto
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(RaizPurple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.HowToVote, contentDescription = null, tint = RaizPurple)
            }
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = proposal.description,
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizBlack,
                )
                Text(
                    text = "Pide ${proposal.amountStroops.formatUsdc()} · #${proposal.id}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = RaizBlack.copy(alpha = 0.6f),
                )
            }
        }

        // Votos
        Text(
            text = "${proposal.votesFor} a favor · ${proposal.votesAgainst} en contra",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
        LinearProgressIndicator(
            progress = {
                if (proposal.totalVotes == 0) 0f
                else proposal.votesFor.toFloat() / proposal.totalVotes
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = RaizGreen,
            trackColor = RaizGrayLight,
        )

        // Estado y countdown
        Text(
            text = when {
                isPassed -> "✓ Aprobada — lista para ejecutar"
                proposal.status == ProposalStatus.REJECTED -> "✗ Rechazada"
                proposal.status == ProposalStatus.EXECUTED -> "● Ejecutada"
                closed && proposal.status == ProposalStatus.ACTIVE ->
                    "Tiempo cumplido · Falta cerrar votación"
                else -> "Cierra en ${formatCountdown(secondsToClose)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isPassed -> RaizGreen
                proposal.status == ProposalStatus.REJECTED -> Color(0xFFB00020)
                closed -> RaizYellow
                else -> RaizBlack.copy(alpha = 0.7f)
            },
        )

        // Feedback de acción
        when (action) {
            ProposalActionState.Submitting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = RaizGreen,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text("Enviando a la red…", style = MaterialTheme.typography.bodyMedium, color = RaizBlack.copy(alpha = 0.7f))
            }
            ProposalActionState.Ok -> Text(
                "✓ Acción confirmada on-chain",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizGreen,
            )
            is ProposalActionState.Failed -> Text(
                action.message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB00020),
            )
            null -> Unit
        }

        // Botón según estado
        when {
            isPassed -> Button(
                onClick = onExecute,
                enabled = action !is ProposalActionState.Submitting && action !is ProposalActionState.Ok,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizGreen,
                    contentColor = RaizWhite,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Ejecutar trustless", style = MaterialTheme.typography.labelLarge)
            }
            closed && proposal.status == ProposalStatus.ACTIVE -> Button(
                onClick = onCloseVoting,
                enabled = action !is ProposalActionState.Submitting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizYellow,
                    contentColor = RaizBlack,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Cerrar votación", style = MaterialTheme.typography.labelLarge)
            }
            else -> Unit
        }
    }
}

/** Formato amigable de segundos → "3d 4h" / "12h 30m" / "5m". */
private fun formatCountdown(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stats grid
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatsGrid(state: DashboardUiState) {
    val barrio = state.barrio ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Fondo disponible",
                value = barrio.poolBalanceStroops.formatUsdc(),
                accent = RaizGreen,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Total recaudado",
                value = barrio.totalCollectedStroops.formatUsdc(),
                accent = RaizPurple,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Pagos al barrio",
                value = barrio.txCount.toString(),
                accent = RaizBlack,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Turistas únicos",
                value = barrio.uniqueTourists.toString(),
                accent = RaizBlack,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Comercios RAÍZ",
                value = state.merchantCount.toString(),
                accent = RaizBlack,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Ejecuciones",
                value = state.executions.size.toString(),
                accent = RaizYellow,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.6f),
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            ),
            color = accent,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Barra de uso del fondo
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun UsageBar(state: DashboardUiState) {
    val executedStroops = state.totalExecutedStroops
    val used = state.usedPct
    val available = (100 - used).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Uso del fondo del barrio",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        // Barra apilada: amarillo (ejecutado) + verde (disponible).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(RaizGrayLight),
        ) {
            if (used > 0) {
                Box(
                    modifier = Modifier
                        .weight(used.toFloat())
                        .fillMaxSize()
                        .background(RaizYellow),
                )
            }
            if (available > 0) {
                Box(
                    modifier = Modifier
                        .weight(available.toFloat())
                        .fillMaxSize()
                        .background(RaizGreen),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Ejecutado: ${executedStroops.formatUsdc()} ($used%)",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Disponible: $available%",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizGreen,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Execution row (link a Stellar Expert)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExecutionRow(exec: Execution) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(RaizYellow.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Storefront, contentDescription = null, tint = RaizYellow)
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Propuesta #${exec.proposalId}",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = "${exec.recipient.take(8)}…${exec.recipient.takeLast(6)}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                color = RaizBlack.copy(alpha = 0.5f),
            )
        }
        Text(
            text = exec.amountStroops.formatUsdc(),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = RaizGreen,
        )
        Spacer(modifier = Modifier.size(8.dp))
        // El txHash de Execution es sha256 determinístico del contrato — no es un txHash
        // de Stellar real. Linkeamos al recipient para que el auditor vea el saldo recibido.
        IconButton(onClick = {
            StellarExpert.open(context, StellarExpert.addressUrl(exec.recipient))
        }) {
            Icon(Icons.Outlined.OpenInNew, contentDescription = "Ver recipient en Stellar Expert", tint = RaizGreen)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contratos verificados
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Card footer de transparencia: lista los 4 contratos Soroban desplegados con
 * su dirección C… truncada y un enlace a Stellar Expert para cada uno.
 * Solo se muestra si [DashboardUiState.contractPool] no está vacío (deployments cargados).
 */
@Composable
private fun ContratosCard(state: DashboardUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ContratoFila(nombre = "Pool", address = state.contractPool)
        ContratoFila(nombre = "Governance", address = state.contractGovernance)
        ContratoFila(nombre = "Treasury", address = state.contractTreasury)
        ContratoFila(nombre = "Rewards", address = state.contractRewards)
    }
}

@Composable
private fun ContratoFila(nombre: String, address: String) {
    if (address.isBlank()) return
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { StellarExpert.open(context, StellarExpert.contractUrl(address)) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = nombre,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${address.take(8)}…${address.takeLast(6)}",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = RaizPurple,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Icon(
            imageVector = Icons.Outlined.OpenInNew,
            contentDescription = "Ver contrato $nombre en Stellar Expert",
            tint = RaizPurple,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(color = RaizGreen) }
}

@Composable
private fun ErrorState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No pudimos cargar los datos.\n$message",
            color = RaizBlack.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Tarjeta de entrada a la pantalla "Tesorería que rinde" (DeFindex). */
@Composable
private fun YieldCta(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizGreen)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(RaizWhite.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.TrendingUp, contentDescription = null, tint = RaizWhite)
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Tesorería que rinde",
                style = MaterialTheme.typography.labelLarge,
                color = RaizWhite,
            )
            Text(
                text = "El fondo ocioso genera rendimiento en DeFindex",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizWhite.copy(alpha = 0.85f),
            )
        }
        Icon(Icons.Outlined.OpenInNew, contentDescription = null, tint = RaizWhite)
    }
}

/** Camino A: posición del fondo del barrio en el vault DeFindex (cross-contract). */
@Composable
private fun VaultPositionCard(vaultStroops: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizPurple.copy(alpha = 0.10f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(RaizGreen.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.TrendingUp, contentDescription = null, tint = RaizGreen)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Fondo rindiendo en DeFindex",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = "${vaultStroops.formatUsdc()} del fondo de este barrio generan yield on-chain",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
    }
}
