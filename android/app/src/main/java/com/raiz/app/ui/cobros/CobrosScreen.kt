package com.raiz.app.ui.cobros

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.UserRole
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.components.QrCard
import com.raiz.app.ui.components.RaizBottomNav
import com.raiz.app.ui.components.RaizDestination
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite

/**
 * Pantalla de cobros — exclusiva para el rol MERCHANT.
 *
 * Contenido:
 *   1. QR grande del address del comerciante (para que el turista escanee).
 *   2. Resumen: total recibido + número de cobros.
 *   3. Lista de últimos 50 cobros entrantes.
 *
 * El botón de actualizar en el TopAppBar y el pull-to-refresh recargan
 * el historial vía Horizon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CobrosScreen(
    onNavigateHome: () -> Unit = {},
    onNavigateMap: () -> Unit = {},
    onNavigateProfile: () -> Unit = {},
    currentRole: UserRole = UserRole.MERCHANT,
    viewModel: CobrosViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedNav by remember { mutableStateOf(RaizDestination.Cobros) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Mis cobros",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = RaizBlack,
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::load) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Actualizar cobros",
                            tint = RaizBlack,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            RaizBottomNav(
                selected = selectedNav,
                role = currentRole,
                onSelect = { dest ->
                    selectedNav = dest
                    when (dest) {
                        RaizDestination.Home    -> onNavigateHome()
                        RaizDestination.Map     -> onNavigateMap()
                        RaizDestination.Profile -> onNavigateProfile()
                        else -> Unit
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(message = state.error!!)
                else -> CobrosContent(state = state, onMontoCambiado = viewModel::onMontoCambiado)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contenido principal
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CobrosContent(
    state: CobrosUiState,
    onMontoCambiado: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Campo de monto: el comerciante teclea cuánto quiere cobrar.
        // Si está vacío, el QR codifica solo la dirección (cobro abierto).
        item {
            MontoCobroCard(
                montoCobro = state.montoCobro,
                onMontoChange = onMontoCambiado,
                montoStroops = state.montoStroops,
            )
        }

        // QR del comerciante — incluye el monto si se especificó
        item {
            QrCard(
                content = state.qrContent,
                caption = if (state.montoStroops > 0L)
                    "Cobro · ${state.montoStroops.formatUsdc()}"
                else
                    "${state.accountId.take(12)}…${state.accountId.takeLast(8)}",
                sizeDp = 240,
            )
        }

        // Resumen de ventas
        item {
            SalesSummaryCard(
                totalStroops = state.totalReceivedStroops,
                count = state.cobroCount,
            )
        }

        // Últimos cobros
        if (state.ultimosCobros.isNotEmpty()) {
            item {
                Text(
                    text = "Últimos cobros",
                    style = MaterialTheme.typography.labelLarge,
                    color = RaizBlack,
                )
            }
            items(state.ultimosCobros, key = { it.txHash + it.amountStroops }) { cobro ->
                CobroRow(cobro)
            }
        } else {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Aún no tienes cobros. Comparte tu QR con los turistas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RaizBlack.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SalesSummaryCard(totalStroops: Long, count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RaizBlack)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Storefront,
                contentDescription = null,
                tint = RaizGreen,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Ventas recibidas",
                style = MaterialTheme.typography.labelLarge,
                color = RaizWhite.copy(alpha = 0.7f),
            )
        }
        Text(
            text = totalStroops.formatUsdc(),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = RaizGreen,
        )
        Text(
            text = "$count cobros en total",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizWhite.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun CobroRow(cobro: PaymentRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(RaizGreen.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = "Cobro recibido", tint = RaizGreen)
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Recibido · ${cobro.assetCode}",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = "De ${cobro.from.take(8)}…${cobro.from.takeLast(6)}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                color = RaizBlack.copy(alpha = 0.5f),
            )
        }
        Text(
            text = "+${cobro.amountStroops.formatUsdc()}",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = RaizGreen,
        )
    }
}

/**
 * Card con el campo de monto a cobrar.
 *
 * El comerciante teclea el importe en USDC. El ViewModel sanea el texto y
 * calcula los stroops. Cuando [montoStroops] > 0, el QR de arriba codifica
 * la orden de cobro; si está vacío, el QR es solo la dirección (cobro abierto).
 */
@Composable
private fun MontoCobroCard(
    montoCobro: String,
    onMontoChange: (String) -> Unit,
    montoStroops: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Monto a cobrar",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        OutlinedTextField(
            value = montoCobro,
            onValueChange = onMontoChange,
            placeholder = { Text("0.00", color = RaizBlack.copy(alpha = 0.35f)) },
            suffix = {
                Text(
                    text = "USDC",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.6f),
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RaizGreen,
                unfocusedBorderColor = RaizBlack.copy(alpha = 0.15f),
            ),
        )
        // P8.3: texto instructivo claro — el turista REVISA y CONFIRMA antes de pagar.
        // El QR lleva al turista a la pantalla de orden (PayScreen) con el monto y el
        // toggle de Tip Barrio; el pago solo se ejecuta cuando pulsa "Confirmar pago".
        Text(
            text = if (montoStroops > 0L)
                "El turista escaneará este QR, verá el resumen de ${montoStroops.formatUsdc()} y confirmará el pago."
            else
                "Deja vacío para cobro abierto — el turista ingresa el monto antes de confirmar.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (montoStroops > 0L) RaizGreen else RaizBlack.copy(alpha = 0.5f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Estados de carga / error
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = RaizGreen)
    }
}

@Composable
private fun ErrorBox(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No se pudieron cargar los cobros.\n$message",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
    }
}
