package com.raiz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGrayLight
import com.raiz.app.ui.theme.RaizTheme
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite

/**
 * Card negro con el balance USDC del turista. Pieza visual central de
 * WalletScreen. El monto se formatea como "5.000 USDC" (3 decimales sin
 * ceros sobrantes).
 *
 * Diseño aprobado: fondo `RaizBlack`, texto blanco, acento amarillo en el
 * ícono de wallet.
 *
 * [onAddressTap] — callback opcional para abrir la dirección en Stellar Expert.
 * Si se omite, la fila de dirección sigue siendo visible pero no es tappable.
 */
@Composable
fun BalanceCard(
    balanceStroops: Long,
    publicKey: String,
    modifier: Modifier = Modifier,
    onAddressTap: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(RaizBlack)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: ícono + etiqueta
        Icon(
            imageVector = Icons.Outlined.AccountBalanceWallet,
            contentDescription = null,
            tint = RaizGreen,
        )
        Text(
            text = "Tu saldo",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizGrayLight,
        )
        // Balance grande
        Text(
            text = balanceStroops.formatUsdc(),
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 44.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = RaizWhite,
        )
        // Public key truncada — tappable para abrir Stellar Expert
        Row(
            modifier = Modifier
                .clickable(onClick = onAddressTap)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = publicKey.take(8) + "…" + publicKey.takeLast(6),
                style = MaterialTheme.typography.bodyMedium,
                color = RaizGrayLight,
            )
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = "Ver dirección en Stellar Expert",
                tint = RaizGrayLight.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAFAF7)
@Composable
private fun BalanceCardPreview() {
    RaizTheme {
        BalanceCard(
            balanceStroops = 50_000_000L,
            publicKey = "GBLS7PL5Y65DHQIPMJO6HVQLX4FXEEHQDWHGSBUTGT4V6ZV2IOACYC2P",
            modifier = Modifier.padding(16.dp),
        )
    }
}
