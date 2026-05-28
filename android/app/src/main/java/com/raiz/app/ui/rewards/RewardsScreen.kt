package com.raiz.app.ui.rewards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.model.Reward
import com.raiz.app.ui.components.RaizBottomNav
import com.raiz.app.ui.components.RaizDestination
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGrayLight
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Pantalla de Premios — quinta de las 6 spec.
 *
 * Estructura:
 *   - Header: ícono regalo + "Tus puntos" + valor en grande.
 *   - LazyColumn agrupada por barrio. Cada reward es una card con:
 *       ícono según el nombre (heurística cafe/artesanía/genérico),
 *       nombre + barra de progreso (mis puntos / costo),
 *       costo en pts y stock,
 *       botón "Canjear" si tienes puntos Y stock > 0, o:
 *         "Te faltan X pts" si no alcanzas,
 *         "Agotado" si stock = 0.
 *   - Feedback post-canje: ✓ verde, X error en rojo (auto-dismiss al
 *     tocar la card de nuevo).
 *
 * Bottom nav comparte el patrón de WalletScreen / ProfileScreen.
 */
@Composable
fun RewardsScreen(
    onNavigateHome: () -> Unit = {},
    onNavigateProfile: () -> Unit = {},
    onNavigateMap: () -> Unit = {},
    viewModel: RewardsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedNav by remember { mutableStateOf(RaizDestination.Rewards) }

    Scaffold(
        bottomBar = {
            RaizBottomNav(
                selected = selectedNav,
                onSelect = { dest ->
                    selectedNav = dest
                    when (dest) {
                        RaizDestination.Home -> onNavigateHome()
                        RaizDestination.Profile -> onNavigateProfile()
                        RaizDestination.Map -> onNavigateMap()
                        else -> Unit
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> LoadingContent(padding)
            state.error != null -> ErrorContent(state.error!!, padding)
            state.rewardsByBarrio.isEmpty() -> EmptyContent(padding)
            else -> RewardsList(
                state = state,
                contentPadding = padding,
                onRedeem = viewModel::redeem,
                onDismissFeedback = viewModel::dismissRedeem,
            )
        }
    }
}

@Composable
private fun RewardsList(
    state: RewardsUiState,
    contentPadding: PaddingValues,
    onRedeem: (Reward) -> Unit,
    onDismissFeedback: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") { Header(points = state.points) }

        state.rewardsByBarrio.forEach { (barrioName, rewards) ->
            item("section-$barrioName") {
                Text(
                    text = barrioName,
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizBlack,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(
                items = rewards,
                key = { "${barrioName}-${it.id}" },
            ) { reward ->
                RewardCard(
                    reward = reward,
                    userPoints = state.points,
                    feedback = state.redeemState[reward.id],
                    onRedeem = { onRedeem(reward) },
                    onDismissFeedback = { onDismissFeedback(reward.id) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Header(points: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(RaizBlack)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CardGiftcard,
            contentDescription = null,
            tint = RaizYellow,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Tus puntos",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizGrayLight,
            )
            Text(
                text = "%,d".format(points.coerceAtLeast(0L)),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                ),
                color = RaizWhite,
            )
        }
        Text(
            text = "pts",
            style = MaterialTheme.typography.labelLarge,
            color = RaizPurple,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RewardCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RewardCard(
    reward: Reward,
    userPoints: Long,
    feedback: RedeemStatus?,
    onRedeem: () -> Unit,
    onDismissFeedback: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icono placeholder por nombre del reward
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(RaizGreen.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconFor(reward.name),
                    contentDescription = null,
                    tint = RaizGreen,
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reward.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizBlack,
                )
                Text(
                    text = if (reward.outOfStock) "Agotado" else "Quedan ${reward.stock}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (reward.outOfStock) {
                        Color(0xFFB00020)
                    } else {
                        RaizBlack.copy(alpha = 0.6f)
                    },
                )
            }
            Text(
                text = "${reward.pointsCost} pts",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = RaizPurple,
            )
        }

        LinearProgressIndicator(
            progress = { reward.progressFrom(userPoints) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = RaizGreen,
            trackColor = RaizBlack.copy(alpha = 0.08f),
        )

        // Feedback de canje (si hay)
        when (feedback) {
            is RedeemStatus.Submitting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = RaizGreen,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    "Canjeando…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.7f),
                )
            }
            is RedeemStatus.Ok -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = RaizGreen,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    "Canjeado · entrega con el artesano",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizGreen,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "OK",
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizGreen,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
            }
            is RedeemStatus.Failed -> Text(
                text = feedback.message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB00020),
            )
            null -> Unit
        }

        // Botón
        val canRedeem = reward.canAffordWith(userPoints) && feedback !is RedeemStatus.Submitting
            && feedback !is RedeemStatus.Ok

        Button(
            onClick = {
                if (feedback is RedeemStatus.Failed) onDismissFeedback() else onRedeem()
            },
            enabled = canRedeem || feedback is RedeemStatus.Failed,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
                disabledContainerColor = RaizGreen.copy(alpha = 0.4f),
                disabledContentColor = RaizWhite.copy(alpha = 0.7f),
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = when {
                    feedback is RedeemStatus.Ok -> "¡Disfruta tu premio!"
                    feedback is RedeemStatus.Failed -> "Reintentar"
                    reward.outOfStock -> "Agotado"
                    !reward.canAffordWith(userPoints) ->
                        "Te faltan ${reward.shortfallFrom(userPoints)} pts"
                    else -> "Canjear"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Heurística: icono según el texto del reward. */
private fun iconFor(name: String): ImageVector {
    val lower = name.lowercase()
    return when {
        "café" in lower || "cafe" in lower -> Icons.Outlined.LocalCafe
        else -> Icons.Outlined.Storefront
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Estados auxiliares
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(color = RaizGreen) }
}

@Composable
private fun ErrorContent(message: String, padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No pudimos cargar los premios.\n$message",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun EmptyContent(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.CardGiftcard,
                contentDescription = null,
                tint = RaizBlack.copy(alpha = 0.3f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                "Aún no hay premios en los barrios RAÍZ.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
    }
}
