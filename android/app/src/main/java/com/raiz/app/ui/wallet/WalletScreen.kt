package com.raiz.app.ui.wallet

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.raiz.app.data.model.PassportData
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.components.BalanceCard
import com.raiz.app.ui.components.PassportCard
import com.raiz.app.ui.components.RaizBottomNav
import com.raiz.app.ui.components.RaizDestination
import com.raiz.app.ui.components.SellosCatalog
import com.raiz.app.ui.components.StatBox
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
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
    onPayMerchant: (merchantAddress: String) -> Unit = {},
    onNavigateProfile: () -> Unit = {},
    onNavigateRewards: () -> Unit = {},
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf(RaizDestination.Home) }
    val context = LocalContext.current

    // Launcher para el escáner de QR de zxing-android-embedded.
    // Valida que el contenido sea una Stellar address G... antes de navegar
    // a PayScreen. Si el contenido no es válido, muestra un Toast.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        val addr = raw.takeIf { it.isStellarPublicKey() }
        if (addr != null) {
            onPayMerchant(addr)
        } else if (raw.isNotEmpty()) {
            Toast.makeText(
                context,
                "QR inválido: se esperaba una cuenta G… de Stellar",
                Toast.LENGTH_SHORT,
            ).show()
        }
        // Si raw está vacío, el usuario canceló — no hacemos nada.
    }

    Scaffold(
        bottomBar = {
            RaizBottomNav(
                selected = selectedTab,
                onSelect = { dest ->
                    selectedTab = dest
                    when (dest) {
                        RaizDestination.Profile -> onNavigateProfile()
                        RaizDestination.Rewards -> onNavigateRewards()
                        else -> Unit
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            WalletUiState.Loading -> WalletLoading(padding)
            is WalletUiState.Error -> WalletError(s.message, padding)
            is WalletUiState.Ready -> WalletReady(
                wallet = s.wallet,
                poolBalanceLabel = s.poolBalanceLabel,
                passport = s.passport,
                onScanAndPay = {
                    val opts = ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setBeepEnabled(false)
                        .setOrientationLocked(true)
                        .setPrompt("Apunta al QR del comercio")
                    scanLauncher.launch(opts)
                },
                contentPadding = padding,
            )
        }
    }
}

/** Heurística: G... de 56 chars base32 = Stellar public key. */
private fun String.isStellarPublicKey(): Boolean =
    length == 56 && startsWith("G") && all { it in 'A'..'Z' || it in '2'..'7' }

@Composable
private fun WalletReady(
    wallet: WalletState,
    poolBalanceLabel: String,
    passport: PassportData?,
    onScanAndPay: () -> Unit,
    contentPadding: PaddingValues,
) {
    // Aporte al barrio: prefiere el dato real del passport si ya cargó;
    // si no, fallback derivado de los puntos del wallet (placeholder).
    val contributionStroops = passport?.aportadoAlBarrioStroops
        ?: (wallet.points * 100_000L)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        // Contenido scrolleable
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "RAÍZ",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
            )
            // Smoke test del cableado Soroban: muestra el balance del pool del
            // Centro Histórico leído del contrato Pool en testnet.
            Text(
                text = "Pool del barrio · $poolBalanceLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizGreen,
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
                    value = contributionStroops.formatUsdc(),
                    accent = RaizGreen,
                    modifier = Modifier.weight(1f),
                )
            }

            // RAÍZ Passport — solo cuando ya cargó la data.
            if (passport != null) {
                PassportCard(
                    data = passport,
                    sellos = SellosCatalog.DEFAULT,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // CTA fijo abajo, fuera del scroll.
        Button(
            onClick = onScanAndPay,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
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
        CircularProgressIndicator(color = RaizGreen)
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
