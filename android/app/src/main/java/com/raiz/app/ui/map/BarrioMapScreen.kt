package com.raiz.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation
import com.raiz.app.data.model.Merchant
import com.raiz.app.ui.components.RaizBottomNav
import com.raiz.app.ui.components.RaizDestination
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite

/**
 * Cuarta pantalla del spec: mapa de los comercios RAÍZ.
 *
 * - MapboxMap centrado en Cartagena por defecto (~10.42, -75.55) con zoom 13.
 * - CircleAnnotation verde por cada merchant — sin necesidad de drawables.
 * - Tap a un pin abre ModalBottomSheet con nombre, categoría, badge verificado
 *   y botón "Pagar aquí" que navega a PayScreen con el address pre-cargado.
 *
 * El bottom nav comparte el patrón de las otras pantallas; tab "Mapa"
 * permanece activo aquí.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarrioMapScreen(
    onPayMerchant: (merchantAddress: String) -> Unit,
    onNavigateHome: () -> Unit = {},
    onNavigateProfile: () -> Unit = {},
    onNavigateRewards: () -> Unit = {},
    viewModel: BarrioMapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedMerchant by remember { mutableStateOf<Merchant?>(null) }
    var selectedNav by remember { mutableStateOf(RaizDestination.Map) }
    val sheetState = rememberModalBottomSheetState()

    val viewportState = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(state.initialLng, state.initialLat))
            zoom(state.initialZoom)
            pitch(0.0)
            bearing(0.0)
        }
    }

    Scaffold(
        bottomBar = {
            RaizBottomNav(
                selected = selectedNav,
                onSelect = { dest ->
                    selectedNav = dest
                    when (dest) {
                        RaizDestination.Home -> onNavigateHome()
                        RaizDestination.Rewards -> onNavigateRewards()
                        RaizDestination.Profile -> onNavigateProfile()
                        else -> Unit
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                mapViewportState = viewportState,
            ) {
                // Un círculo verde por cada merchant. CircleAnnotation no
                // necesita drawable — usamos color + borde blanco para
                // contrastar con el fondo del mapa.
                state.merchants.forEach { m ->
                    CircleAnnotation(
                        point = Point.fromLngLat(m.lng, m.lat),
                    ) {
                        circleColor = RaizGreen
                        circleStrokeColor = RaizWhite
                        circleStrokeWidth = 4.0
                        circleRadius = 14.0
                        // El click handler se registra vía interactionsState,
                        // no como param de CircleAnnotation. El callback retorna
                        // true para consumir el tap y evitar que el mapa lo
                        // procese como gesto adicional.
                        interactionsState.onClicked {
                            android.util.Log.i("RAIZ", "Pin tap: ${m.name}")
                            selectedMerchant = m
                            true
                        }
                    }
                }
            }

            // Overlay de loading mientras los merchants cargan.
            if (state.loading) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(RaizWhite)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = RaizGreen,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        "Buscando comercios…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RaizBlack,
                    )
                }
            }

            // Contador de comercios en una píldora arriba a la izquierda.
            if (!state.loading) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(RaizBlack)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = RaizGreen,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        "${state.merchants.size} comercios",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RaizWhite,
                    )
                }
            }
        }
    }

    // Bottom sheet con el merchant seleccionado.
    selectedMerchant?.let { m ->
        ModalBottomSheet(
            onDismissRequest = { selectedMerchant = null },
            sheetState = sheetState,
            containerColor = RaizWhite,
        ) {
            MerchantSheet(
                merchant = m,
                onPayHere = {
                    val addr = m.address
                    selectedMerchant = null
                    onPayMerchant(addr)
                },
            )
        }
    }
}

@Composable
private fun MerchantSheet(
    merchant: Merchant,
    onPayHere: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(RaizGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = RaizGreen)
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    merchant.name,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = RaizBlack,
                )
                Text(
                    merchant.category.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.6f),
                )
            }
            if (merchant.verified) {
                Icon(Icons.Outlined.Verified, contentDescription = "Verificado", tint = RaizGreen)
            }
        }

        Text(
            text = "Comercio RAÍZ — aceptan pago en USDC con Tip Barrio del 2% que va al fondo comunitario.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )

        Button(
            onClick = onPayHere,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
            Spacer(modifier = Modifier.size(10.dp))
            Text("Pagar aquí", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
