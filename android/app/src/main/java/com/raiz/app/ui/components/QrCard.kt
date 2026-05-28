package com.raiz.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.raiz.app.ui.qr.QrUtils
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizWhite

/**
 * Card blanca con el QR de un contenido (típicamente una Stellar address)
 * + un caption opcional debajo. La generación del bitmap se hace con
 * `remember(content)` para no rehacerla en cada recomposición.
 */
@Composable
fun QrCard(
    content: String,
    caption: String? = null,
    sizeDp: Int = 240,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) { QrUtils.generate(content, sizePx = sizeDp * 3) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(RaizWhite)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR de $content",
            modifier = Modifier
                .size(sizeDp.dp)
                .aspectRatio(1f),
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
    }
}
