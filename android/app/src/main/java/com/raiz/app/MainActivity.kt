package com.raiz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raiz.app.ui.pay.PayScreen
import com.raiz.app.ui.profile.ProfileScreen
import com.raiz.app.ui.rewards.RewardsScreen
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.wallet.WalletScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point. Navega entre las 6 pantallas spec con un NavHost.
 *
 * Rutas:
 *   - wallet                 → home/balance/passport/escanear
 *   - pay/{merchant_address} → pantalla de pago (address viene del QR)
 *   - profile                → perfil con rol + historial + QR
 *   - rewards                → catálogo de premios + canje
 *   - (TODO) barrio_map      → 4ª pantalla con Mapbox
 *
 * Cada pantalla con bottom nav navega entre las anteriores. El NavController
 * único de la app preserva el back stack — al tocar back en cualquier
 * pantalla, se vuelve a la anterior visitada (no a wallet de la fuerza).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RaizTheme {
                RaizApp()
            }
        }
    }
}

@Composable
private fun RaizApp() {
    val nav = rememberNavController()

    // Helper: navega a una ruta principal sin acumular duplicados en el stack.
    fun goTo(route: String) {
        nav.navigate(route) {
            // popUpTo wallet (start dest) inclusive=false → la wallet siempre
            // queda al fondo del stack para que back en cualquier pantalla
            // termine eventualmente en home.
            popUpTo(Routes.WALLET) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(navController = nav, startDestination = Routes.WALLET) {
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
    const val WALLET = "wallet"
    const val PAY_PREFIX = "pay"
    const val PROFILE = "profile"
    const val REWARDS = "rewards"
}
