package com.raiz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raiz.app.data.stellar.WalletManager
import com.raiz.app.ui.pay.PayScreen
import com.raiz.app.ui.profile.ProfileScreen
import com.raiz.app.ui.rewards.RewardsScreen
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.wallet.WalletScreen
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
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var walletManager: WalletManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RaizTheme {
                RaizApp(
                    initiallyHasWallet = walletManager.hasUsableWallet(),
                    onLogout = { walletManager.logout() },
                )
            }
        }
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
                    // No persiste nada — solo entra a wallet con la cuenta
                    // demo activa (raiz-tourist).
                    hasWallet = true
                    nav.navigate(Routes.WALLET) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CREATE_WALLET) {
            CreateWalletScreen(
                onBack = { nav.popBackStack() },
                onWalletReady = {
                    hasWallet = true
                    nav.navigate(Routes.WALLET) {
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
                    nav.navigate(Routes.WALLET) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
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
                onLogout = {
                    onLogout()
                    hasWallet = false
                    nav.navigate(Routes.WELCOME) {
                        popUpTo(Routes.WALLET) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.REWARDS) {
            RewardsScreen(
                onNavigateHome = { goTo(Routes.WALLET) },
                onNavigateProfile = { goTo(Routes.PROFILE) },
            )
        }
    }
}

private object Routes {
    const val WELCOME = "welcome"
    const val CREATE_WALLET = "welcome/create"
    const val IMPORT_WALLET = "welcome/import"

    const val WALLET = "wallet"
    const val PAY_PREFIX = "pay"
    const val PROFILE = "profile"
    const val REWARDS = "rewards"
}
