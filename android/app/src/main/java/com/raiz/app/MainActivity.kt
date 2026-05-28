package com.raiz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.wallet.WalletScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point. Por ahora muestra solo la WalletScreen — el NavHost real
 * con las 6 pantallas se monta cuando todas estén implementadas.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RaizTheme {
                WalletScreen(
                    onScanAndPay = {
                        // TODO: navegar a QrScannerScreen → PayScreen
                    },
                )
            }
        }
    }
}
