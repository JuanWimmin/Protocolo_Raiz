package com.raiz.app.ui.cobros

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.UserRole
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.components.QrCard
import com.raiz.app.ui.components.RaizBottomNav
import com.raiz.app.ui.components.RaizDestination
import com.raiz.app.ui.deposit.CustomTabs
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.util.StellarExpert

/**
 * Pantalla de cobros — exclusiva para el rol MERCHANT.
 *
 * Contenido:
 *   1. Monto a cobrar + QR grande del address del comerciante (para que el turista escanee).
 *   2. Cobro sin señal: "Escanear sobre" / "Pegar sobre" (pago diferido). El sobre se verifica aquí, sin red,
 *      y el teléfono lo envía solo al volver la señal; "Pagos en tránsito" lista los sobres recibidos.
 *   3. Resumen de ventas: total recibido + número de cobros.
 *   4. Lista de últimos 50 cobros entrantes.
 *
 * La pantalla se pinta SIEMPRE: sin señal las ventas no cargan (su sección lo dice en línea), pero el cobro
 * con sobres sigue disponible. El botón de actualizar del TopAppBar recarga ventas y sobres.
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedNav by remember { mutableStateOf(RaizDestination.Cobros) }
    var mostrarTope by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Al volver a la pantalla (p. ej. de la pasarela web o del escáner) la red pudo haber cerrado sobres.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) viewModel.alReanudar()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Escáner de QR de zxing-android-embedded: el sobre (`raiz:sobre:1:…`) llega como texto al ViewModel.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        // raw vacío = el usuario canceló el escáner
        if (raw.isNotEmpty()) viewModel.onSobreTexto(raw)
    }

    // Eventos de un solo disparo: aviso (Toast) y apertura de la pasarela web.
    val aviso = state.aviso
    LaunchedEffect(aviso) {
        if (aviso != null) {
            Toast.makeText(context, aviso, Toast.LENGTH_LONG).show()
            viewModel.consumirAviso()
        }
    }
    val pasarela = state.pendientePasarela
    LaunchedEffect(pasarela) {
        if (pasarela != null) {
            clipboard.setText(AnnotatedString(pasarela.first))
            Toast.makeText(context, "Sobre copiado", Toast.LENGTH_SHORT).show()
            CustomTabs.open(context, pasarela.second)
            viewModel.consumirPasarela()
        }
    }

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
            // Siempre se pinta el contenido: sin señal solo la sección de ventas muestra su error en línea.
            CobrosContent(
                state = state,
                onMontoCambiado = viewModel::onMontoCambiado,
                onEscanear = {
                    val opciones = ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setBeepEnabled(false)
                        .setOrientationLocked(true)
                        .setPrompt("Apunta al QR del sobre")
                    scanLauncher.launch(opciones)
                },
                onPegar = { viewModel.onPegarSobre(clipboard.getText()?.text) },
                onCambiarTope = { mostrarTope = true },
                onAbrirDetalle = viewModel::abrirDetalle,
                onReintentarVentas = viewModel::load,
            )
        }
    }

    // Resultado de escanear o pegar un sobre.
    state.veredicto?.let { veredicto ->
        VeredictoDialog(
            veredicto = veredicto,
            liquidadorMotivo = state.liquidadorMotivo,
            onVerDetalle = { id ->
                viewModel.descartarVeredicto()
                viewModel.abrirDetalle(id)
            },
            onDismiss = viewModel::descartarVeredicto,
        )
    }

    if (mostrarTope) {
        TopeDialog(
            actualUsdc = stroopsATextoUsdc(state.topeStroops),
            onConfirm = { texto ->
                viewModel.onTopeTexto(texto)
                mostrarTope = false
            },
            onDismiss = { mostrarTope = false },
        )
    }

    // Detalle de un sobre recibido. El registro se busca en cada recomposición: se actualiza solo
    // cuando el SobreSettler lo liquida mientras el detalle está abierto.
    val detalle = state.detalleId?.let { id -> state.recibidos.firstOrNull { it.id == id } }
    if (detalle != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.abrirDetalle(null) },
            sheetState = sheetState,
            containerColor = RaizWhite,
        ) {
            CobroDetalleSheet(
                r = detalle,
                sinSenal = state.sinSenal,
                liquidando = detalle.id in state.liquidando,
                liquidadorMotivo = state.liquidadorMotivo,
                onLiquidar = { viewModel.liquidarAhora(detalle.id) },
                onPasarela = { viewModel.abrirPasarela(detalle.id) },
                onCopiar = {
                    clipboard.setText(AnnotatedString(detalle.sobreQr))
                    Toast.makeText(context, "Sobre copiado", Toast.LENGTH_SHORT).show()
                },
                onVerTx = { hash -> StellarExpert.open(context, StellarExpert.txUrl(hash)) },
            )
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
    onEscanear: () -> Unit,
    onPegar: () -> Unit,
    onCambiarTope: () -> Unit,
    onAbrirDetalle: (String) -> Unit,
    onReintentarVentas: () -> Unit,
) {
    // Una wallet que no envía desde la app (passkey) liquida los sobres con la pasarela web.
    val soloPasarela = state.liquidadorMotivo != null
    val errorVentas = state.error

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
            if (state.accountId.isNotBlank()) {
                QrCard(
                    content = state.qrContent,
                    caption = if (state.montoStroops > 0L)
                        "Cobro · ${state.montoStroops.formatUsdc()}"
                    else
                        "${state.accountId.take(12)}…${state.accountId.takeLast(8)}",
                    sizeDp = 240,
                )
            } else {
                SinWalletCard()
            }
        }

        // Sin señal: el cobro con sobres sigue disponible; se dice qué pasa con lo que se reciba.
        if (state.sinSenal) {
            item { SinSenalCobrosBanner(soloPasarela = soloPasarela) }
        }

        // Cobro sin señal: escanear o pegar el sobre que muestra el teléfono del turista.
        item {
            AccionesSobre(
                verificando = state.verificando,
                soloPasarela = soloPasarela,
                onEscanear = onEscanear,
                onPegar = onPegar,
            )
        }

        // Pagos en tránsito: los sobres recibidos de otros teléfonos y su estado.
        item {
            EncabezadoTransito(
                pendientes = state.enTransito,
                topeStroops = state.topeStroops,
                onCambiarTope = onCambiarTope,
            )
        }
        if (state.recibidos.isEmpty()) {
            item {
                Text(
                    text = "Aún no has recibido ningún sobre.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.5f),
                )
            }
        } else {
            items(state.recibidos, key = { it.id }) { recibido ->
                RecibidoRowCobro(recibido) { onAbrirDetalle(recibido.id) }
            }
        }

        // Ventas: carga y error en línea (sin señal fallan y no deben tapar el resto de la pantalla).
        when {
            state.loading -> item { VentasCargando() }
            errorVentas != null -> item {
                VentasError(message = errorVentas, sinSenal = state.sinSenal, onRetry = onReintentarVentas)
            }
            else -> {
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
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Cobro sin señal
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Botones para recibir un sobre. "Escanear sobre" abre la cámara; "Pegar sobre" lee el portapapeles
 * (el turista también puede mandar el sobre como texto). Mientras se verifica no se aceptan más.
 */
@Composable
private fun AccionesSobre(
    verificando: Boolean,
    soloPasarela: Boolean,
    onEscanear: () -> Unit,
    onPegar: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onEscanear,
            enabled = !verificando,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
                disabledContainerColor = RaizGreen.copy(alpha = 0.7f),
                disabledContentColor = RaizWhite,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (verificando) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = RaizWhite,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text("Verificando sobre…", style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(imageVector = Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.size(10.dp))
                Text("Escanear sobre", style = MaterialTheme.typography.labelLarge)
            }
        }
        OutlinedButton(
            onClick = onPegar,
            enabled = !verificando,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            border = BorderStroke(1.dp, RaizPurple),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizPurple),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentPaste,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text("Pegar sobre", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = if (soloPasarela) {
                "Cobra sin señal: el sobre se verifica aquí y se guarda; para liquidarlo usa la pasarela web."
            } else {
                "Cobra sin señal: el sobre se verifica aquí y se envía solo cuando vuelva la red."
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = RaizBlack.copy(alpha = 0.6f),
        )
    }
}

/** Cabecera de "Pagos en tránsito" con el contador de pendientes y el tope por pago sin señal. */
@Composable
private fun EncabezadoTransito(
    pendientes: Int,
    topeStroops: Long,
    onCambiarTope: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Pagos en tránsito",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
                modifier = Modifier.weight(1f),
            )
            if (pendientes > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(RaizPurple.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "$pendientes por liquidar",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = RaizPurple,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Tope sin señal: ${topeStroops.formatUsdc()} ·",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizBlack.copy(alpha = 0.6f),
            )
            TextButton(
                onClick = onCambiarTope,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = "Cambiar",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = RaizPurple,
                )
            }
        }
    }
}

/** Sin wallet activa no hay dirección que codificar (ZXing no puede generar un QR vacío). */
@Composable
private fun SinWalletCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(RaizWhite)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No hay una wallet activa: inicia sesión para mostrar tu QR de cobro.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ventas
// ─────────────────────────────────────────────────────────────────────────────

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
// Estados de carga / error de la sección de ventas (en línea: no tapan el resto de la pantalla)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VentasCargando() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = RaizGreen)
    }
}

@Composable
private fun VentasError(message: String, sinSenal: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (sinSenal) "Las ventas no están disponibles sin señal." else "No se pudieron cargar las ventas.",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        TextButton(onClick = onRetry) {
            Text(
                text = "Reintentar",
                style = MaterialTheme.typography.labelLarge,
                color = RaizGreen,
            )
        }
    }
}
