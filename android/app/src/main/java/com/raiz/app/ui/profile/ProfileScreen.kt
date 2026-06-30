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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.material3.ButtonDefaults
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.model.PaymentRecord
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

/**
 * Tabs de perfil — post-refactor.
 *
 *   HISTORIAL    → pagos del usuario (salientes + entrantes)
 *   QR           → QR de cobro (también útil para el comerciante)
 *   CONFIGURACION → seguridad (biométrico), logout,
 *                  DemoRoleSwitch (solo si isDemoMode)
 */
private enum class ProfileTab(val label: String) {
    HISTORIAL("Historial"),
    QR("Mi QR"),
    CONFIGURACION("Configuración"),
}

/**
 * Pantalla de Perfil — versión limpia.
 *
 * La votación de propuestas se movió a [ProposalsScreen].
 * Las ventas del comerciante se movieron a [CobrosScreen].
 *
 * [currentRole] determina los tabs del bottom nav.
 * [onDemoRoleChange] se llama cuando el juez cambia el rol en el demo
 * para que [RaizApp] actualice la nav global sin persistir el cambio.
 */
@Composable
fun ProfileScreen(
    onNavigateHome: () -> Unit = {},
    onNavigateRewards: () -> Unit = {},
    onNavigateMap: () -> Unit = {},
    onNavigateProposals: () -> Unit = {},
    onNavigateCobros: () -> Unit = {},
    onBecomeMerchant: () -> Unit = {},
    onLogout: () -> Unit = {},
    currentRole: UserRole = UserRole.TOURIST,
    onDemoRoleChange: (UserRole) -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(ProfileTab.HISTORIAL) }
    var selectedNav by remember { mutableStateOf(RaizDestination.Profile) }

    Scaffold(
        bottomBar = {
            RaizBottomNav(
                selected = selectedNav,
                role = currentRole,
                onSelect = { dest ->
                    selectedNav = dest
                    when (dest) {
                        RaizDestination.Home      -> onNavigateHome()
                        RaizDestination.Rewards   -> onNavigateRewards()
                        RaizDestination.Map       -> onNavigateMap()
                        RaizDestination.Proposals -> onNavigateProposals()
                        RaizDestination.Cobros    -> onNavigateCobros()
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
                ProfileTab.QR -> QrTab(publicKey = state.wallet.publicKey)
                ProfileTab.CONFIGURACION -> ConfigTab(
                    state = state,
                    appLockEnabled = state.appLockEnabled,
                    appLockAvailable = state.appLockAvailable,
                    onToggleLock = viewModel::setAppLockEnabled,
                    onLogout = onLogout,
                    onBecomeMerchant = onBecomeMerchant,
                    onDemoRoleChange = { role: UserRole? ->
                        // Actualiza el chip del header en ProfileScreen
                        viewModel.setRoleOverride(role)
                        // Propaga al nivel de RaizApp para actualizar la nav inferior
                        onDemoRoleChange(role ?: UserRole.TOURIST)
                    },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
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
                    text = publicKey.firstOrNull()?.toString() ?: "?",
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
        UserRole.TOURIST  -> RaizYellow
        UserRole.RESIDENT -> RaizPurple
        UserRole.MERCHANT -> RaizGreen
    }
    AssistChip(
        onClick = { /* informativo */ },
        label = {
            Text(role.label, color = RaizBlack, style = MaterialTheme.typography.labelLarge)
        },
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
private fun BalanceTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
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
                    Text(t.label, style = MaterialTheme.typography.labelLarge)
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
            items(
                state.history,
                key = { it.txHash + it.from + it.to + it.amountStroops },
            ) { p ->
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
            Text(
                "$direction · ${payment.assetCode}",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                "${counterparty.take(8)}…${counterparty.takeLast(6)}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
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
private fun QrTab(publicKey: String) {
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
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab: Configuración
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConfigTab(
    state: ProfileUiState,
    appLockEnabled: Boolean,
    appLockAvailable: Boolean,
    onToggleLock: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onBecomeMerchant: () -> Unit,
    /** null = restaurar rol real; UserRole = simular ese rol (solo demo). */
    onDemoRoleChange: (UserRole?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

        // Demo role switch — solo visible cuando no hay wallet real guardada.
        if (state.isDemoMode) {
            DemoRoleSwitch(
                current = state.effectiveRole,
                onSelect = onDemoRoleChange,
            )
        }

        // Cambio a comerciante (turista que quiere registrar su negocio).
        if (state.effectiveRole == UserRole.TOURIST) {
            OutlinedButton(
                onClick = onBecomeMerchant,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = RaizGreen,
                ),
            ) {
                Text("Registrarme como comerciante", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Seguridad — bloqueo biométrico.
        SecuritySettingsCard(
            enabled = appLockEnabled,
            available = appLockAvailable,
            onToggle = onToggleLock,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Logout.
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFB00020),
            ),
        ) {
            Text("Cerrar sesión y borrar wallet", style = MaterialTheme.typography.labelLarge)
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
            text = "Cambia de rol para mostrar las diferentes vistas al jurado — la nav inferior se adapta en tiempo real.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoChip(
                label = "Turista",
                selected = current == UserRole.TOURIST,
                onClick = { onSelect(null) },
            )
            DemoChip(
                label = "Residente",
                selected = current == UserRole.RESIDENT,
                onClick = { onSelect(UserRole.RESIDENT) },
            )
            DemoChip(
                label = "Comerciante",
                selected = current == UserRole.MERCHANT,
                onClick = { onSelect(UserRole.MERCHANT) },
            )
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

@Composable
private fun SecuritySettingsCard(
    enabled: Boolean,
    available: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = RaizGreen)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Bloqueo biométrico",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = if (available) {
                    "Pide huella, rostro o PIN del dispositivo al abrir la app."
                } else {
                    "Configura un bloqueo de pantalla en tu teléfono para activarlo."
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
        Switch(
            checked = enabled && available,
            enabled = available,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RaizWhite,
                checkedTrackColor = RaizGreen,
            ),
        )
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
    ) {
        CircularProgressIndicator(color = RaizGreen)
    }
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
