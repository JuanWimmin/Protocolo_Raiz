package com.raiz.app.ui.wallet

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.ui.util.StellarExpert
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.raiz.app.data.model.PassportData
import com.raiz.app.data.model.UserRole
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
/**
 * Pantalla Home / Wallet.
 *
 * [currentRole] controla qué tabs aparecen en la barra inferior:
 *   - TOURIST  → Inicio · Premios · Mapa · Perfil
 *   - MERCHANT → Inicio · Cobros  · Mapa · Perfil
 *   - RESIDENT → Inicio · Propuestas · Mapa · Perfil
 */
@Composable
fun WalletScreen(
    onPayMerchant: (merchantAddress: String) -> Unit = {},
    /** Llamado cuando el QR escaneado contiene una orden de cobro con monto fijo. */
    onPayMerchantConMonto: (merchantAddress: String, amountStroops: Long) -> Unit = { _, _ -> },
    onNavigateProfile: () -> Unit = {},
    onNavigateRewards: () -> Unit = {},
    onNavigateMap: () -> Unit = {},
    onNavigateDashboard: () -> Unit = {},
    onNavigateProposals: () -> Unit = {},
    onNavigateCobros: () -> Unit = {},
    currentRole: UserRole = UserRole.TOURIST,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf(RaizDestination.Home) }
    val context = LocalContext.current

    // Refresco al volver a la pantalla (ON_RESUME) — detecta cambios hechos en otras pantallas.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Launcher para el escáner de QR de zxing-android-embedded.
    // Distingue dos formatos:
    //   1. Orden de cobro RAÍZ: `raiz:pay?to=<addr>&amount=<stroops>` → onPayMerchantConMonto
    //      Acepta tanto G… (cuenta clásica) como C… (smart account passkey).
    //   2. Dirección Stellar pelada G… o C… → onPayMerchant (cobro abierto)
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        val ordenDeCobro = parsearQrRaizPay(raw)
        when {
            // Orden de cobro con monto fijo (G… o C… aceptados en el campo `to`)
            ordenDeCobro != null ->
                onPayMerchantConMonto(ordenDeCobro.first, ordenDeCobro.second)
            // Dirección Stellar pelada: G… cuenta clásica o C… smart account (passkey)
            raw.isStellarAddress() -> onPayMerchant(raw)
            // QR no reconocido
            raw.isNotEmpty() -> Toast.makeText(
                context,
                "QR inválido: se esperaba una dirección Stellar (G… o C…) o una orden RAÍZ",
                Toast.LENGTH_SHORT,
            ).show()
            // raw vacío = el usuario canceló el escáner
        }
    }

    Scaffold(
        bottomBar = {
            RaizBottomNav(
                selected = selectedTab,
                role = currentRole,
                onSelect = { dest ->
                    selectedTab = dest
                    when (dest) {
                        RaizDestination.Profile   -> onNavigateProfile()
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
        when (val s = state) {
            WalletUiState.Loading -> WalletLoading(padding)
            is WalletUiState.Error -> WalletError(s.message, padding)
            is WalletUiState.Ready -> WalletReady(
                state = s,
                onScanAndPay = {
                    val opts = ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setBeepEnabled(false)
                        .setOrientationLocked(true)
                        .setPrompt("Apunta al QR del comercio")
                    scanLauncher.launch(opts)
                },
                onTransparencyTap = onNavigateDashboard,
                onFundXlm = viewModel::fundWithFriendbot,
                onActivateUsdc = viewModel::activateUsdcTrustline,
                onRequestUsdc = viewModel::requestUsdcFaucet,
                contentPadding = padding,
            )
        }
    }
}

/**
 * Valida que el string es una dirección Stellar en formato Strkey base32.
 *
 * Un address Stellar válido mide exactamente 56 caracteres y comienza con:
 *   - 'G' para cuentas clásicas (ed25519 pública, keypair normal).
 *   - 'C' para contratos / smart accounts (ej. wallets passkey de Soneso).
 *
 * Regex equivalente: ^[GC][A-Z2-7]{55}$
 */
private fun String.isStellarAddress(): Boolean =
    length == 56 && (startsWith("G") || startsWith("C")) &&
        all { it in 'A'..'Z' || it in '2'..'7' }

/**
 * Parsea una URL de orden de cobro RAÍZ.
 *
 * Formato esperado: `raiz:pay?to=<addr>&amount=<stroops>`
 * El campo `to` acepta G… (cuenta clásica) y C… (smart account passkey).
 *
 * @return Pair(address, stroops) si el formato es válido y la dirección es
 *   Stellar (G o C, 56 chars base32); null en cualquier otro caso o monto ≤ 0.
 */
private fun parsearQrRaizPay(raw: String): Pair<String, Long>? {
    if (!raw.startsWith("raiz:pay?")) return null
    val params = raw.removePrefix("raiz:pay?")
        .split("&")
        .mapNotNull { par ->
            val kv = par.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }
        .toMap()
    // P7: acepta tanto G… como C… — un comerciante passkey tiene dirección C…
    val to = params["to"]?.takeIf { it.isStellarAddress() } ?: return null
    val amount = params["amount"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    return to to amount
}

@Composable
private fun WalletReady(
    state: WalletUiState.Ready,
    onScanAndPay: () -> Unit,
    onTransparencyTap: () -> Unit,
    onFundXlm: () -> Unit,
    onActivateUsdc: () -> Unit,
    onRequestUsdc: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val wallet = state.wallet
    val poolBalanceLabel = state.poolBalanceLabel
    val passport = state.passport
    // Aporte al barrio: dato real del passport (suma de tips al pool del
    // barrio). Si aún no cargó, mostramos 0 — sin valores fantasma.
    val contributionStroops = passport?.aportadoAlBarrioStroops ?: 0L

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
            // Pool del barrio activo + link a la transparencia pública.
            Text(
                text = "Pool del barrio · $poolBalanceLabel  ·  Ver transparencia →",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizGreen,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { onTransparencyTap() })
                },
            )

            // Banner de onboarding on-chain. Muestra el paso pendiente
            // (XLM → trustline → USDC) y se oculta cuando el wallet está listo.
            if (state.setupStep != AccountSetupStep.DONE) {
                AccountSetupBanner(
                    step = state.setupStep,
                    inProgress = state.setupInProgress,
                    error = state.setupError,
                    relayerConfigured = state.relayerConfigured,
                    onFundXlm = onFundXlm,
                    onActivateTrustline = onActivateUsdc,
                    onRequestUsdc = onRequestUsdc,
                )
            }

            BalanceCard(
                balanceStroops = wallet.usdcBalanceStroops,
                publicKey = wallet.publicKey,
                onAddressTap = {
                    StellarExpert.open(context, StellarExpert.addressUrl(wallet.publicKey))
                },
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

/**
 * Banner de onboarding on-chain. Una wallet recién creada necesita 3 cosas
 * antes de poder usar RAÍZ:
 *   1. XLM para existir y pagar fees (friendbot, testnet).
 *   2. Trustline USDC para poder recibir el token.
 *   3. USDC en saldo para pagar (admin actúa de faucet en demo).
 *
 * El banner muestra solo el paso actual; tras completarlo, se actualiza al
 * siguiente. Cuando todos están listos, desaparece.
 */
@Composable
private fun AccountSetupBanner(
    step: AccountSetupStep,
    inProgress: Boolean,
    error: String?,
    relayerConfigured: Boolean,
    onFundXlm: () -> Unit,
    onActivateTrustline: () -> Unit,
    onRequestUsdc: () -> Unit,
) {
    val title: String
    val body: String
    val cta: String
    val action: () -> Unit
    // Solo el paso 3 (faucet) depende del relayer — friendbot y el trustline
    // los sigue firmando/pidiendo el propio usuario, sin admin de por medio.
    val usesRelayer = step == AccountSetupStep.REQUEST_USDC
    when (step) {
        AccountSetupStep.FUND_XLM -> {
            title = "Paso 1 · Activa tu cuenta"
            body = "Tu wallet aún no existe en Stellar. Friendbot la fondeará con XLM de testnet (gratis, solo demo)."
            cta = "Fondear con friendbot"
            action = onFundXlm
        }
        AccountSetupStep.ACTIVATE_TRUSTLINE -> {
            title = "Paso 2 · Habilita USDC"
            body = "Stellar pide un trustline al USDC antes de poder recibirlo. Tarda ~5 segundos."
            cta = "Activar trustline"
            action = onActivateTrustline
        }
        AccountSetupStep.REQUEST_USDC -> {
            title = "Paso 3 · Pide USDC de prueba"
            body = "El relayer del barrio te enviará 20 USDC de testnet para que puedas hacer pagos. Solo modo demo."
            cta = "Pedir USDC de prueba"
            action = onRequestUsdc
        }
        AccountSetupStep.DONE -> return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizYellow.copy(alpha = 0.18f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color(0xFFB00020),
            )
        }
        if (usesRelayer && !relayerConfigured) {
            Text(
                text = "Relayer no configurado (raiz.relayer.url / raiz.relayer.key en local.properties)",
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color(0xFFB00020),
            )
        }
        Button(
            onClick = action,
            enabled = !inProgress && (!usesRelayer || relayerConfigured),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
                disabledContainerColor = RaizGreen.copy(alpha = 0.5f),
                disabledContentColor = RaizWhite.copy(alpha = 0.7f),
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (inProgress) {
                CircularProgressIndicator(color = RaizWhite, strokeWidth = 2.dp)
            } else {
                Text(cta, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (inProgress && usesRelayer) {
            Text(
                text = "Verificando con el barrio… puede tardar hasta 1 minuto",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
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
