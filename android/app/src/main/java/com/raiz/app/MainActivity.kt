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
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.wallet.WalletScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point. Navega entre las pantallas con un NavHost mínimo.
 * Cuando estén todas las 6 pantallas, este NavHost se expande con sus rutas.
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
    NavHost(navController = nav, startDestination = Routes.WALLET) {
        composable(Routes.WALLET) {
            WalletScreen(
                onScanAndPay = { nav.navigate(Routes.PAY) },
            )
        }
        composable(Routes.PAY) {
            PayScreen(
                onDone = { nav.popBackStack() },
            )
        }
    }
}

private object Routes {
    const val WALLET = "wallet"
    const val PAY = "pay"
}
