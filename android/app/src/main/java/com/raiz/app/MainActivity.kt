package com.raiz.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raiz.app.data.security.AppLock
import com.raiz.app.data.stellar.WalletManager
import com.raiz.app.ui.become_merchant.BecomeMerchantScreen
import com.raiz.app.ui.dashboard.DashboardScreen
import com.raiz.app.ui.map.BarrioMapScreen
import com.raiz.app.ui.pay.PayScreen
import com.raiz.app.ui.profile.ProfileScreen
import com.raiz.app.ui.rewards.RewardsScreen
import com.raiz.app.ui.security.LockScreen
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.treasury.YieldScreen
import com.raiz.app.ui.wallet.WalletScreen
import com.raiz.app.ui.welcome.ChooseRoleScreen
import com.raiz.app.ui.welcome.CreateWalletScreen
import com.raiz.app.ui.welcome.ImportWalletScreen
import com.raiz.app.ui.welcome.WelcomeScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Entry point.
 *
 * Si el usuario tiene wallet guardada (SecureWalletStore) o hay demo
 * configurado → arranca en `wallet`. Sino arranca en `welcome` con las
 * opciones de crear / importar / demo.
 *
 * **Bloqueo de la app:** si está activado (Perfil → Mi QR → seguridad) y hay
 * wallet, se exige autenticación biométrica/PIN del dispositivo antes de
 * mostrar la app, y se re-bloquea al volver de segundo plano. Usa el
 * subsistema del SO vía `BiometricPrompt` (no guardamos PIN propio).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var walletManager: WalletManager
    @Inject lateinit var appLock: AppLock

    /** true = la app está bloqueada y requiere desbloqueo ahora. */
    private var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked = appLock.isActive() && walletManager.hasUsableWallet()
        setContent {
            RaizTheme {
                if (locked) {
                    LockScreen(onUnlock = ::requestUnlock)
                    LaunchedEffect(Unit) { requestUnlock() }
                } else {
                    RaizApp(
                        initiallyHasWallet = walletManager.hasUsableWallet(),
                        onLogout = { walletManager.logout() },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-bloquea al ir a segundo plano (si el bloqueo aplica).
        if (appLock.isActive() && walletManager.hasUsableWallet()) {
            locked = true
        }
    }

    private fun requestUnlock() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                }
                // En error/cancelación queda bloqueado; el botón de LockScreen reintenta.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear RAÍZ")
            .setSubtitle("Confirma con huella, rostro o el PIN de tu dispositivo")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}

@Composable
private fun RaizApp(
    initiallyHasWallet: Boolean,
    onLogout: () -> Unit,
) {
    val nav = rememberNavController()
    // Sigue el flag por si el usuario hace logout durante la sesión.
    var hasWallet by remember { mutableStateOf(initiallyHasWallet) }

    LaunchedEffect(Unit) { /* placeholder por si queremos splash más adelante */ }

    fun goTo(route: String) {
        nav.navigate(route) {
            popUpTo(Routes.WALLET) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val start = if (hasWallet) Routes.WALLET else Routes.WELCOME

    NavHost(navController = nav, startDestination = start) {
        // ── Welcome flow ─────────────────────────────────────────────────
        composable(Routes.WELCOME) {
            WelcomeScreen(
                demoEnabled = BuildConfig.DEMO_TOURIST_SECRET.isNotBlank(),
                onCreateWallet = { nav.navigate(Routes.CREATE_WALLET) },
                onImportWallet = { nav.navigate(Routes.IMPORT_WALLET) },
                onUseDemo = {
                    hasWallet = true
                    nav.navigate(Routes.WALLET) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
                onSeeDashboard = { nav.navigate(Routes.DASHBOARD) },
            )
        }
        composable(Routes.CREATE_WALLET) {
            CreateWalletScreen(
                onBack = { nav.popBackStack() },
                onWalletReady = {
                    hasWallet = true
                    nav.navigate(Routes.CHOOSE_ROLE) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.IMPORT_WALLET) {
            ImportWalletScreen(
                onBack = { nav.popBackStack() },
                onWalletReady = {
                    hasWallet = true
                    nav.navigate(Routes.CHOOSE_ROLE) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CHOOSE_ROLE) {
            ChooseRoleScreen(
                onTourist = {
                    nav.navigate(Routes.WALLET) { popUpTo(Routes.CHOOSE_ROLE) { inclusive = true } }
                },
                onMerchant = {
                    nav.navigate(Routes.WALLET) { popUpTo(Routes.CHOOSE_ROLE) { inclusive = true } }
                    nav.navigate(Routes.BECOME_MERCHANT)
                },
                onResident = {
                    nav.navigate(Routes.WALLET) { popUpTo(Routes.CHOOSE_ROLE) { inclusive = true } }
                },
            )
        }

        // ── App principal ────────────────────────────────────────────────
        composable(Routes.WALLET) {
            WalletScreen(
                onPayMerchant = { merchantAddress ->
                    nav.navigate("${Routes.PAY_PREFIX}/$merchantAddress")
                },
                onNavigateProfile = { goTo(Routes.PROFILE) },
                onNavigateRewards = { goTo(Routes.REWARDS) },
                onNavigateMap = { goTo(Routes.MAP) },
                onNavigateDashboard = { nav.navigate(Routes.DASHBOARD) },
            )
        }
        composable(
            route = "${Routes.PAY_PREFIX}/{merchant_address}",
            arguments = listOf(navArgument("merchant_address") { type = NavType.StringType }),
        ) {
            PayScreen(onDone = { nav.popBackStack() })
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                onNavigateHome = { goTo(Routes.WALLET) },
                onNavigateRewards = { goTo(Routes.REWARDS) },
                onNavigateMap = { goTo(Routes.MAP) },
                onBecomeMerchant = { nav.navigate(Routes.BECOME_MERCHANT) },
                onLogout = {
                    onLogout()
                    hasWallet = false
                    nav.navigate(Routes.WELCOME) {
                        popUpTo(Routes.WALLET) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.BECOME_MERCHANT) {
            BecomeMerchantScreen(
                onBack = { nav.popBackStack() },
                onSuccess = {
                    // Vuelve a la pantalla anterior (Perfil o Inicio según el origen).
                    nav.popBackStack()
                },
            )
        }
        composable(Routes.REWARDS) {
            RewardsScreen(
                onNavigateHome = { goTo(Routes.WALLET) },
                onNavigateProfile = { goTo(Routes.PROFILE) },
                onNavigateMap = { goTo(Routes.MAP) },
            )
        }
        composable(Routes.MAP) {
            BarrioMapScreen(
                onPayMerchant = { merchantAddress ->
                    nav.navigate("${Routes.PAY_PREFIX}/$merchantAddress")
                },
                onNavigateHome = { goTo(Routes.WALLET) },
                onNavigateProfile = { goTo(Routes.PROFILE) },
                onNavigateRewards = { goTo(Routes.REWARDS) },
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onBack = { nav.popBackStack() },
                onOpenYield = { nav.navigate(Routes.YIELD) },
            )
        }
        composable(Routes.YIELD) {
            YieldScreen(onBack = { nav.popBackStack() })
        }
    }
}

private object Routes {
    const val WELCOME = "welcome"
    const val CREATE_WALLET = "welcome/create"
    const val IMPORT_WALLET = "welcome/import"
    const val CHOOSE_ROLE = "welcome/role"

    const val WALLET = "wallet"
    const val PAY_PREFIX = "pay"
    const val PROFILE = "profile"
    const val REWARDS = "rewards"
    const val MAP = "map"
    const val DASHBOARD = "dashboard"
    const val BECOME_MERCHANT = "become_merchant"
    const val YIELD = "yield"
}
