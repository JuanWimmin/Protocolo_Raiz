package com.raiz.app.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.components.BalanceCard
import com.raiz.app.ui.components.RaizBottomNav
import com.raiz.app.ui.components.RaizDestination
import com.raiz.app.ui.components.StatBox
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizYellow

/**
 * Pantalla Home / Wallet del turista.
 *
 * Estructura:
 *   - Card negro con balance USDC + public key
 *   - 2 stats: puntos acumulados, aporte total al barrio
 *   - CTA amarillo grande "Escanear y pagar"
 *   - Bottom nav fija (4 destinos)
 *
 * Por ahora todos los callbacks (escanear, navegar a mapa, etc.) son no-ops:
 * cuando integremos navegación con NavHost se cablean a las pantallas reales.
 */
@Composable
fun WalletScreen(
    onScanAndPay: () -> Unit = {},
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf(RaizDestination.Home) }

    Scaffold(
        bottomBar = {
            RaizBottomNav(
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            WalletUiState.Loading -> WalletLoading(padding)
            is WalletUiState.Error -> WalletError(s.message, padding)
            is WalletUiState.Ready -> WalletReady(
                wallet = s.wallet,
                onScanAndPay = onScanAndPay,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun WalletReady(
    wallet: WalletState,
    onScanAndPay: () -> Unit,
    contentPadding: PaddingValues,
) {
    // Aporte al barrio: por ahora un placeholder derivado de los puntos.
    // Cuando tengamos PaymentRepository, se calculará sumando los tips
    // emitidos en eventos `payment` del Pool donde el turista figura.
    val contributionStroopsPlaceholder = wallet.points * 100_000L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "RAÍZ",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
        )

        BalanceCard(
            balanceStroops = wallet.usdcBalanceStroops,
            publicKey = wallet.publicKey,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatBox(
                label = "Puntos",
                value = wallet.points.toString(),
                accent = RaizPurple,
                modifier = Modifier.weight(1f),
            )
            StatBox(
                label = "Aporte al barrio",
                value = contributionStroopsPlaceholder.formatUsdc(),
                accent = RaizGreen,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onScanAndPay,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizYellow,
                contentColor = RaizBlack,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text = "Escanear y pagar",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun WalletLoading(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = RaizYellow)
    }
}

@Composable
private fun WalletError(message: String, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Algo falló",
            style = MaterialTheme.typography.headlineMedium,
            color = RaizBlack,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
    }
}
