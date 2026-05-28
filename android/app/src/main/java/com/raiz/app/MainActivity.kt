package com.raiz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point. Placeholder visual con la paleta RAÍZ; será reemplazado por
 * el NavHost real cuando montemos las 6 pantallas.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: barras translúcidas con iconos oscuros sobre el fondo claro.
        enableEdgeToEdge()
        setContent {
            RaizTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    PaletteShowcase()
                }
            }
        }
    }
}

/**
 * Vista temporal — muestra la paleta y un CTA con el estilo aprobado.
 * Sirve de smoke test del theme antes de meter las pantallas reales.
 */
@Composable
private fun PaletteShowcase() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "RAÍZ",
            style = androidx.compose.material3.MaterialTheme.typography.displayLarge,
            color = RaizBlack,
        )
        Text(
            text = "Tu paga, el barrio crece",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = RaizBlack,
        )

        Swatch("Negro #1A1A1A", RaizBlack, RaizWhite)
        Swatch("Amarillo #FBBF24", RaizYellow, RaizBlack)
        Swatch("Púrpura #534AB7", RaizPurple, RaizWhite)
        Swatch("Verde #0F6E56", RaizGreen, RaizWhite)

        Button(
            onClick = { /* no-op por ahora */ },
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizYellow,
                contentColor = RaizBlack,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                "Escanear y pagar",
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun Swatch(
    name: String,
    bg: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(16.dp),
    ) {
        Text(name, color = fg)
    }
}

@Preview(showBackground = true)
@Composable
private fun PalettePreview() {
    RaizTheme { PaletteShowcase() }
}
