package com.raiz.app.ui.profile

import android.widget.Toast
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
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.components.QrCard
import com.raiz.app.ui.components.RaizBottomNav
import com.raiz.app.ui.components.RaizDestination
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGrayLight
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

private enum class ProfileTab(val label: String) { HISTORIAL("Historial"), QR("Mi QR"), CONFIG("Configuración") }

/**
 * Pantalla de Perfil del turista.
 *
 * Header: avatar circular con inicial + public key truncada + botón copiar.
 * Tabs:
 *   - Historial: lista de pagos (entrantes/salientes) desde Horizon.
 *   - Mi QR: código QR de mi address para recibir pagos / mostrar al barrio.
 *   - Configuración: botones placeholder (ver seed, cerrar sesión, ayuda).
 *
 * Bottom nav: comparte la misma de WalletScreen para mantener UX consistente.
 */
@Composable
fun ProfileScreen(
    onNavigateHome: () -> Unit = {},
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
                    if (dest == RaizDestination.Home) onNavigateHome()
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
            Header(publicKey = state.wallet.publicKey)
            BalancesRow(
                usdcStroops = state.wallet.usdcBalanceStroops,
                points = state.wallet.points,
            )
            TabBar(selected = tab, onSelect = { tab = it })

            when (tab) {
                ProfileTab.HISTORIAL -> HistoryTab(
                    state = state,
                    myAccount = state.wallet.publicKey,
                )
                ProfileTab.QR -> QrTab(publicKey = state.wallet.publicKey)
                ProfileTab.CONFIG -> ConfigTab()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Header(publicKey: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar circular con inicial
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(RaizBlack),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = publicKey.first().toString(),
                color = RaizYellow,
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
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.Monospace,
                ),
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
        BalanceTile(
            label = "Saldo",
            value = usdcStroops.formatUsdc(),
            accent = RaizGreen,
            modifier = Modifier.weight(1f),
        )
        BalanceTile(
            label = "Puntos",
            value = points.toString(),
            accent = RaizPurple,
            modifier = Modifier.weight(1f),
        )
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
            text = value,
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
        indicator = { positions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .height(3.dp),
                color = RaizYellow,
            )
        },
    ) {
        ProfileTab.entries.forEach { t ->
            Tab(
                selected = t == selected,
                onClick = { onSelect(t) },
                text = {
                    Text(
                        text = t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (t == selected) RaizBlack else RaizBlack.copy(alpha = 0.5f),
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
private fun HistoryTab(state: ProfileUiState, myAccount: String) {
    when {
        state.historyLoading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = RaizYellow) }

        state.historyError != null -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No pudimos cargar el historial.\n${state.historyError}",
                color = RaizBlack.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        state.history.isEmpty() -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Aún no tienes pagos. Escanea un QR para empezar.",
                color = RaizBlack.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

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
            Text(
                text = "$direction · ${payment.assetCode}",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = "${counterparty.take(8)}…${counterparty.takeLast(6)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                color = RaizBlack.copy(alpha = 0.5f),
            )
        }
        Text(
            text = "$signo${payment.amountStroops.formatUsdc()}",
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
// Tab: Configuración (placeholders)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConfigTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConfigItem(
            icon = Icons.Outlined.Key,
            label = "Ver frase de recuperación",
            subtitle = "Tu seed phrase de 12 palabras",
        )
        ConfigItem(
            icon = Icons.Outlined.HelpOutline,
            label = "Ayuda",
            subtitle = "Cómo funciona el Tip Barrio",
        )
        ConfigItem(
            icon = Icons.Outlined.Logout,
            label = "Cerrar sesión",
            subtitle = "Eliminar wallet de este dispositivo",
            destructive = true,
        )
    }
}

@Composable
private fun ConfigItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    destructive: Boolean = false,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (destructive) Color(0xFFB00020) else RaizBlack,
        )
        Spacer(modifier = Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (destructive) Color(0xFFB00020) else RaizBlack,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
    }
    // Subrayado sutil entre items vía spacer + grayLight no necesario porque
    // ya tenemos fondo blanco redondeado.
    @Suppress("UNUSED_EXPRESSION") RaizGrayLight  // referencia para no warning
    @Suppress("UNUSED_EXPRESSION") context
}

@Composable
@Suppress("unused")
private fun PreviewPaddingValues(): PaddingValues = PaddingValues(0.dp)
