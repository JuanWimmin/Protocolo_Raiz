package com.raiz.app.ui.become_merchant

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation
import com.mapbox.maps.plugin.gestures.gestures
import com.raiz.app.data.model.MerchantCategory
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Pantalla "Registrarme como comerciante" — flujo en dos pasos:
 *
 *   Paso 1 (INFO):     Nombre del negocio, categoría y barrio.
 *   Paso 2 (LOCATION): El comerciante fija la ubicación tocando el mapa
 *                      o buscando una dirección (geocodificación Mapbox).
 *
 * Las coordenadas resultantes (latE6 / lngE6) se pasan a
 * [Pool.register_merchant] sin modificar la firma de SorobanClient.
 * Si el comerciante no elige, se usa el centro geográfico del barrio.
 */
@Composable
fun BecomeMerchantScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: BecomeMerchantViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // En el paso de ubicación, el botón Atrás del sistema retrocede al paso 1
    // en lugar de cerrar la pantalla (comportamiento que mantiene los datos).
    BackHandler(enabled = state.step == OnboardingStep.LOCATION) {
        viewModel.backToInfoStep()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        when {
            state.success -> SuccessContent(padding = padding, onDone = onSuccess)
            state.step == OnboardingStep.INFO -> StepInfo(
                state = state,
                padding = padding,
                onBack = onBack,
                onUpdateName = viewModel::updateName,
                onSelectCategory = viewModel::selectCategory,
                onSelectBarrio = viewModel::selectBarrio,
                onNext = viewModel::goToLocationStep,
            )
            else -> StepLocation(
                state = state,
                padding = padding,
                onBack = viewModel::backToInfoStep,
                onAddressQueryChanged = viewModel::onAddressQueryChanged,
                onSearchAddress = viewModel::searchAddress,
                onMapTapped = viewModel::onMapTapped,
                onSubmit = viewModel::submit,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Paso 1: Datos del negocio
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepInfo(
    state: BecomeMerchantUiState,
    padding: PaddingValues,
    onBack: () -> Unit,
    onUpdateName: (String) -> Unit,
    onSelectCategory: (MerchantCategory) -> Unit,
    onSelectBarrio: (String) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Cabecera ───────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
            }
            Text(
                "Soy comerciante",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
            )
        }

        // Indicador de progreso textual
        Text(
            "Paso 1 de 2 · Datos del negocio",
            style = MaterialTheme.typography.labelMedium,
            color = RaizGreen,
        )

        Text(
            text = "Registra tu negocio en RAÍZ. Tus clientes podrán pagarte en USDC y, con cada pago con Tip Barrio, aportar al fondo de tu zona.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )

        // ── Nombre ─────────────────────────────────────────────────────
        OutlinedTextField(
            value = state.name,
            onValueChange = onUpdateName,
            label = { Text("Nombre del negocio") },
            placeholder = { Text("Ej: Café Don Aurelio") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RaizGreen,
                cursorColor = RaizGreen,
                focusedLabelColor = RaizGreen,
            ),
        )

        // ── Categoría ──────────────────────────────────────────────────
        Text(
            "Categoría",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MerchantCategory.entries.toList()) { c ->
                ChipChoice(
                    label = c.label,
                    selected = state.category == c,
                    onClick = { onSelectCategory(c) },
                )
            }
        }

        // ── Barrio ─────────────────────────────────────────────────────
        Text(
            "Barrio",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.barrioOptions) { (id, name) ->
                ChipChoice(
                    label = name,
                    selected = state.barrioId == id,
                    onClick = { onSelectBarrio(id) },
                )
            }
        }

        if (state.error != null) {
            Text(
                text = state.error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB00020),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        DemoNote()

        // Avanza al paso de ubicación — aún no registra on-chain.
        Button(
            onClick = onNext,
            enabled = state.canGoNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
                disabledContainerColor = RaizGreen.copy(alpha = 0.4f),
                disabledContentColor = RaizWhite.copy(alpha = 0.7f),
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Outlined.LocationOn, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("Siguiente: Elegir ubicación", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Paso 2: Ubicación del negocio
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepLocation(
    state: BecomeMerchantUiState,
    padding: PaddingValues,
    onBack: () -> Unit,
    onAddressQueryChanged: (String) -> Unit,
    onSearchAddress: () -> Unit,
    onMapTapped: (latE6: Int, lngE6: Int) -> Unit,
    onSubmit: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    // Coordenadas elegidas — extraídas a vals locales para que Kotlin
    // pueda smart-castear sin problema dentro de los bloques if/when.
    val pickedLatE6 = state.pickedLatE6
    val pickedLngE6 = state.pickedLngE6

    // Mapa centrado en el barrio al entrar al paso.
    // rememberMapViewportState persiste mientras el composable esté en pantalla.
    val viewportState = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(state.barrioCenterLng, state.barrioCenterLat))
            zoom(15.0)
            pitch(0.0)
            bearing(0.0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Cabecera ───────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
            }
            Text(
                "Soy comerciante",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
            )
        }

        // Indicador de progreso textual
        Text(
            "Paso 2 de 2 · Ubicación del negocio",
            style = MaterialTheme.typography.labelMedium,
            color = RaizGreen,
        )

        Text(
            text = "Toca el mapa para fijar el pin o escribe la dirección y pulsa Buscar.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )

        // ── Búsqueda por dirección ─────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.addressQuery,
                onValueChange = onAddressQueryChanged,
                placeholder = { Text("Ej: Calle del Cuartel 36, Cartagena") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        onSearchAddress()
                    },
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RaizGreen,
                    cursorColor = RaizGreen,
                    focusedLabelColor = RaizGreen,
                ),
            )
            // Botón Buscar: dispara la geocodificación en el ViewModel.
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSearchAddress()
                },
                enabled = state.addressQuery.isNotBlank() && !state.geocodingLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizGreen,
                    contentColor = RaizWhite,
                    disabledContainerColor = RaizGreen.copy(alpha = 0.35f),
                    disabledContentColor = RaizWhite.copy(alpha = 0.6f),
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            ) {
                if (state.geocodingLoading) {
                    CircularProgressIndicator(
                        color = RaizWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(Icons.Outlined.Search, contentDescription = "Buscar dirección")
                }
            }
        }

        // ── Mapa picker ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                mapViewportState = viewportState,
            ) {
                // Registra el listener de tap UNA sola vez (clave Unit).
                // Convierte las coordenadas a latE6/lngE6 y notifica al ViewModel.
                MapEffect(Unit) { mapView ->
                    mapView.gestures.addOnMapClickListener { tappedPoint ->
                        onMapTapped(
                            (tappedPoint.latitude() * 1_000_000).toInt(),
                            (tappedPoint.longitude() * 1_000_000).toInt(),
                        )
                        true // consume el evento: evita acciones secundarias del SDK
                    }
                }

                // Centra el mapa cuando el ViewModel actualiza las coordenadas
                // (tap en el mapa o resultado de geocodificación).
                // MapEffect keyed en las coords se re-ejecuta cada vez que cambian.
                // setCamera es inmediato (sin animación); para Mapbox v11 es la API
                // más portable: flyTo está en el plugin de animación (CameraAnimationsPlugin).
                MapEffect(state.pickedLatE6, state.pickedLngE6) { mapView ->
                    val lat = state.pickedLatE6 ?: return@MapEffect
                    val lng = state.pickedLngE6 ?: return@MapEffect
                    mapView.mapboxMap.setCamera(
                        CameraOptions.Builder()
                            .center(Point.fromLngLat(lng / 1_000_000.0, lat / 1_000_000.0))
                            .zoom(17.0)
                            .build()
                    )
                }

                // Pin verde en la ubicación elegida.
                // CircleAnnotation no necesita drawable: solo color + radio.
                if (pickedLatE6 != null && pickedLngE6 != null) {
                    CircleAnnotation(
                        point = Point.fromLngLat(
                            pickedLngE6 / 1_000_000.0,
                            pickedLatE6 / 1_000_000.0,
                        ),
                    ) {
                        circleColor = RaizGreen
                        circleStrokeColor = RaizWhite
                        circleStrokeWidth = 4.0
                        circleRadius = 14.0
                    }
                }
            }

            // Tooltip flotante mientras no hay pin fijado.
            if (pickedLatE6 == null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(RaizBlack.copy(alpha = 0.65f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        "Toca el mapa para fijar la ubicación",
                        style = MaterialTheme.typography.bodySmall,
                        color = RaizWhite,
                    )
                }
            }
        }

        // ── Coordenadas / dirección elegida ───────────────────────────
        if (pickedLatE6 != null && pickedLngE6 != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = "Ubicación fijada",
                    tint = RaizGreen,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    // Muestra la dirección si vino de geocodificación,
                    // o las coordenadas si vino de tap directo en el mapa.
                    text = state.pickedAddress
                        ?: "%.5f, %.5f".format(
                            pickedLatE6 / 1_000_000.0,
                            pickedLngE6 / 1_000_000.0,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = RaizGreen,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── Mensaje de error ──────────────────────────────────────────
        if (state.error != null) {
            Text(
                text = state.error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB00020),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        DemoNote()

        // ── Botón de registro final ────────────────────────────────────
        // Usa state.finalLatE6 / state.finalLngE6 (elegido o centro del barrio).
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
                disabledContainerColor = RaizGreen.copy(alpha = 0.4f),
                disabledContentColor = RaizWhite.copy(alpha = 0.7f),
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(color = RaizWhite, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Storefront, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Registrarme como comerciante", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Componentes reutilizables
// ─────────────────────────────────────────────────────────────────────────────

/** Aviso de modo demo — compartido entre los dos pasos. */
@Composable
private fun DemoNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RaizYellow.copy(alpha = 0.18f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Demo: el registro se aprueba al instante con la cuenta de admin del seed. En producción pasaría por revisión del admin del barrio.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = RaizBlack.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ChipChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) RaizWhite else RaizBlack,
            )
        },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = if (selected) RaizGreen else RaizBlack.copy(alpha = 0.08f),
        ),
        border = null,
    )
}

@Composable
private fun SuccessContent(padding: PaddingValues, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(RaizGreen.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = RaizGreen,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "¡Negocio registrado!",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Tu address ya es un comercio RAÍZ. Cualquier turista podrá escanear tu QR (Perfil → Mi QR) y pagarte en USDC.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Volver al perfil", style = MaterialTheme.typography.labelLarge)
        }
    }
}
