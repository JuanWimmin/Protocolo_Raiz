package com.raiz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.theme.RaizWhite

/**
 * Caja stat genérica: label arriba (gris), valor abajo en bold. Acento
 * vertical opcional a la izquierda para diferenciar tipos de stat (puntos
 * con púrpura, aporte con verde, etc.).
 */
@Composable
fun StatBox(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = accent,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAFAF7)
@Composable
private fun StatBoxPreview() {
    RaizTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatBox(
                label = "Puntos",
                value = "320",
                accent = com.raiz.app.ui.theme.RaizPurple,
            )
            StatBox(
                label = "Aporte al barrio",
                value = "2.4 USDC",
                accent = com.raiz.app.ui.theme.RaizGreen,
            )
        }
    }
}
