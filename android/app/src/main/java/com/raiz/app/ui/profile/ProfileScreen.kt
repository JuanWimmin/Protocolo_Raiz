package com.raiz.app.ui.profile

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.Proposal
import com.raiz.app.data.model.UserRole
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.components.QrCard
import com.raiz.app.ui.components.RaizBottomNav
import com.raiz.app.ui.components.RaizDestination
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

private enum class ProfileTab(val label: String) {
    HISTORIAL("Historial"),
    ROL("Mi rol"),
    QR("Mi QR"),
}

@Composable
fun ProfileScreen(
    onNavigateHome: () -> Unit = {},
    onNavigateRewards: () -> Unit = {},
    onNavigateMap: () -> Unit = {},
    onBecomeMerchant: () -> Unit = {},
    onLogout: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(ProfileTab.HISTORIAL) }
    var selectedNav by remember { mutableStateOf(RaizDestination.Profile) }

    Scaffold(
        bottomBar = {
            RaizBottomNav(
                selected = selectedNav,
                onSelect = { dest ->
                    selectedNav = dest
                    when (dest) {
                        RaizDestination.Home -> onNavigateHome()
                        RaizDestination.Rewards -> onNavigateRewards()
                        RaizDestination.Map -> onNavigateMap()
                        else -> Unit
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Header(publicKey = state.wallet.publicKey, role = state.effectiveRole)
            BalancesRow(
                usdcStroops = state.wallet.usdcBalanceStroops,
                points = state.wallet.points,
            )
            TabBar(selected = tab, onSelect = { tab = it })

            when (tab) {
                ProfileTab.HISTORIAL -> HistoryTab(state = state)
                ProfileTab.ROL -> RoleTab(
                    state = state,
                    onOverride = viewModel::setRoleOverride,
                    onVote = viewModel::vote,
                    onBecomeMerchant = onBecomeMerchant,
                )
                ProfileTab.QR -> QrTab(
                    publicKey = state.wallet.publicKey,
                    onLogout = onLogout,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header con chip de rol
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Header(publicKey: String, role: UserRole) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(RaizBlack),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = publicKey.first().toString(),
                    color = RaizGreen,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Mi wallet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.6f),
                )
                Text(
                    text = "${publicKey.take(8)}…${publicKey.takeLast(6)}",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    color = RaizBlack,
                )
            }
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(publicKey))
                Toast.makeText(context, "Dirección copiada", Toast.LENGTH_SHORT).show()
            }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copiar dirección",
                    tint = RaizBlack,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        RoleChip(role = role)
    }
}

@Composable
private fun RoleChip(role: UserRole) {
    val color = when (role) {
        UserRole.TOURIST -> RaizYellow
        UserRole.RESIDENT -> RaizPurple
        UserRole.MERCHANT -> RaizGreen
    }
    AssistChip(
        onClick = { /* nada — informativo */ },
        label = { Text(role.label, color = RaizBlack, style = MaterialTheme.typography.labelLarge) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.18f),
            labelColor = RaizBlack,
        ),
        border = null,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Balances row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BalancesRow(usdcStroops: Long, points: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BalanceTile("Saldo", usdcStroops.formatUsdc(), RaizGreen, Modifier.weight(1f))
        BalanceTile("Puntos", points.toString(), RaizPurple, Modifier.weight(1f))
    }
}

@Composable
private fun BalanceTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = RaizBlack.copy(alpha = 0.6f))
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = accent,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tabs
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TabBar(selected: ProfileTab, onSelect: (ProfileTab) -> Unit) {
    // Usamos el TabRow default — el indicator custom de Material 3 requiere
    // pasar la lista de TabPositions con tabIndicatorOffset; pisamos los
    // textos si lo hacemos mal. Mejor el default y solo coloreamos el text.
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = RaizBlack,
    ) {
        ProfileTab.entries.forEach { t ->
            Tab(
                selected = t == selected,
                onClick = { onSelect(t) },
                selectedContentColor = RaizBlack,
                unselectedContentColor = RaizBlack.copy(alpha = 0.5f),
                text = {
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab: Historial
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryTab(state: ProfileUiState) {
    when {
        state.historyLoading -> CenteredSpinner()
        state.historyError != null -> CenteredText(
            "No pudimos cargar el historial.\n${state.historyError}",
        )
        state.history.isEmpty() -> CenteredText(
            "Aún no tienes pagos. Escanea un QR para empezar.",
        )
        else -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.history, key = { it.txHash + it.from + it.to + it.amountStroops }) { p ->
                PaymentRow(payment = p)
            }
        }
    }
}

@Composable
private fun PaymentRow(payment: PaymentRecord) {
    val accent = if (payment.isOutgoing) RaizBlack else RaizGreen
    val arrow = if (payment.isOutgoing) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward
    val direction = if (payment.isOutgoing) "Enviado" else "Recibido"
    val counterparty = if (payment.isOutgoing) payment.to else payment.from
    val signo = if (payment.isOutgoing) "−" else "+"

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
                .size(36.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(arrow, contentDescription = direction, tint = accent)
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("$direction · ${payment.assetCode}", style = MaterialTheme.typography.labelLarge, color = RaizBlack)
            Text(
                "${counterparty.take(8)}…${counterparty.takeLast(6)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                color = RaizBlack.copy(alpha = 0.5f),
            )
        }
        Text(
            "$signo${payment.amountStroops.formatUsdc()}",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = accent,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab: Mi QR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun QrTab(publicKey: String, onLogout: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Comparte este QR para recibir pagos en USDC",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        QrCard(
            content = publicKey,
            caption = "${publicKey.take(12)}…${publicKey.takeLast(8)}",
            sizeDp = 240,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Logout — borra la wallet local y vuelve a Welcome.
        androidx.compose.material3.OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFB00020),
            ),
        ) {
            Text("Cerrar sesión y borrar wallet", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab: Mi rol — contenido condicional
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RoleTab(
    state: ProfileUiState,
    onOverride: (UserRole?) -> Unit,
    onVote: (Long, Boolean) -> Unit,
    onBecomeMerchant: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Demo switch (solo si el rol detectado real es turista)
        if (state.detectedRole?.role == UserRole.TOURIST) {
            DemoRoleSwitch(current = state.effectiveRole, onSelect = onOverride)
        }

        when (state.effectiveRole) {
            UserRole.TOURIST -> TouristSection(onBecomeMerchant = onBecomeMerchant)
            UserRole.RESIDENT -> ResidentSection(state = state, onVote = onVote)
            UserRole.MERCHANT -> MerchantSection(state = state)
        }
    }
}

@Composable
private fun DemoRoleSwitch(current: UserRole, onSelect: (UserRole?) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Modo demo · ver como…",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = "Tu address real es turista. Para mostrar las otras vistas en el demo, simula otro rol — las lecturas usan datos reales de los barrios.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoChip("Turista", current == UserRole.TOURIST) { onSelect(null) }
            DemoChip("Residente", current == UserRole.RESIDENT) { onSelect(UserRole.RESIDENT) }
            DemoChip("Comerciante", current == UserRole.MERCHANT) { onSelect(UserRole.MERCHANT) }
        }
    }
}

@Composable
private fun DemoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) RaizYellow else RaizBlack.copy(alpha = 0.05f),
            labelColor = RaizBlack,
        ),
        border = null,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Sección: Turista
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TouristSection(onBecomeMerchant: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(RaizWhite)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Estás pagando como turista",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = "Cada pago con Tip Barrio aporta al fondo del comercio que visitas y te da puntos para canjear por artesanías del lugar.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "¿Vives en un barrio RAÍZ? Pide a tu admin local que te mintea el token de residencia — desde ahí podrás proponer y votar en qué se invierte el fondo.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }

        // Atajo a registrarse como comerciante. Visible siempre que el rol
        // efectivo sea turista — es la única forma user-facing de probar el
        // flow E2E en 2 teléfonos (uno paga, el otro recibe).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(RaizWhite)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Storefront,
                    contentDescription = null,
                    tint = RaizGreen,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "¿Tienes un negocio?",
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizBlack,
                )
            }
            Text(
                text = "Regístralo en RAÍZ. Aparecerá en el mapa de tu barrio y podrás recibir pagos en USDC con QR.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
            Button(
                onClick = onBecomeMerchant,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizGreen,
                    contentColor = RaizWhite,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Registrarme como comerciante", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sección: Residente — propuestas activas + voto
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ResidentSection(
    state: ProfileUiState,
    onVote: (Long, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Tu barrio · ${state.activeBarrioName}",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = "Como residente puedes proponer cómo se invierte el fondo del barrio y votar en propuestas abiertas.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )

        when {
            state.proposalsLoading -> CenteredSpinner()
            state.proposalsError != null -> CenteredText(
                "No pudimos cargar propuestas.\n${state.proposalsError}",
            )
            state.residentProposals.isEmpty() -> Text(
                text = "No hay propuestas activas en este barrio.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
            else -> state.residentProposals.forEach { p ->
                ProposalCard(
                    p = p,
                    voteStatus = state.voteState[p.id],
                    onVote = { support -> onVote(p.id, support) },
                )
            }
        }
    }
}

@Composable
private fun ProposalCard(
    p: Proposal,
    voteStatus: VoteStatus?,
    onVote: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(p.description, style = MaterialTheme.typography.labelLarge, color = RaizBlack)
        Row {
            Text(
                "Pide ${p.amountStroops.formatUsdc()}",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            Text(
                "${p.votesFor} a favor · ${p.votesAgainst} en contra",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
        LinearProgressIndicator(
            progress = { if (p.totalVotes == 0) 0f else p.votesFor.toFloat() / p.totalVotes },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = RaizGreen,
            trackColor = RaizBlack.copy(alpha = 0.08f),
        )

        // Estado del voto en curso / completado / con error
        when (voteStatus) {
            VoteStatus.Submitting -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = RaizGreen,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Enviando voto a la red…", style = MaterialTheme.typography.bodyMedium, color = RaizBlack.copy(alpha = 0.7f))
            }
            VoteStatus.Ok -> Text(
                "✓ Tu voto quedó on-chain",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizGreen,
            )
            is VoteStatus.Failed -> Text(
                voteStatus.message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB00020),
            )
            null -> Unit
        }

        val voting = voteStatus is VoteStatus.Submitting
        val alreadyOk = voteStatus is VoteStatus.Ok
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onVote(true) },
                enabled = !voting && !alreadyOk,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizGreen,
                    contentColor = RaizWhite,
                    disabledContainerColor = RaizGreen.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Outlined.ThumbUp, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("A favor", style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = { onVote(false) },
                enabled = !voting && !alreadyOk,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizBlack,
                    contentColor = RaizWhite,
                    disabledContainerColor = RaizBlack.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Outlined.ThumbDown, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("En contra", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sección: Comerciante — ventas recibidas
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MerchantSection(state: ProfileUiState) {
    val incoming = state.history.filter { !it.isOutgoing }
    val incomingTotal = incoming.sumOf { it.amountStroops }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Tu negocio · ${state.activeBarrioName}",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = "Recibes pagos en USDC vía la red Stellar. RAÍZ retiene un 0.5% como fee del protocolo; el 2% del tip va al pool del barrio.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )

        // Resumen ventas
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(RaizWhite)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storefront, contentDescription = null, tint = RaizGreen)
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Ventas recibidas",
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizBlack,
                )
            }
            Text(
                text = incomingTotal.formatUsdc(),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizGreen,
            )
            Text(
                text = "${incoming.size} pagos en total",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }

        if (incoming.isEmpty()) {
            Text(
                text = "Aún no recibes pagos en esta cuenta. Comparte tu QR (tab Mi QR) para que te paguen.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        } else {
            Text(
                text = "Últimos cobros",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            incoming.take(5).forEach { p ->
                PaymentRow(payment = p)
                HorizontalDivider(color = Color.Transparent, thickness = 4.dp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CenteredSpinner() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(color = RaizGreen) }
}

@Composable
private fun CenteredText(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = RaizBlack.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
