package com.raiz.app.ui.deferred

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raiz.app.data.deferred.DeferredRecord
import com.raiz.app.data.deferred.DeferredStatus
import com.raiz.app.data.deferred.SobreSettler
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.deposit.CustomTabs
import com.raiz.app.ui.pay.DeferredSignedScreen
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGray
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import com.raiz.app.ui.util.StellarExpert

/**
 * "Mis sobres": los sobres que este teléfono firmó sin señal y, debajo, "Recibidos": los que recibió de otros teléfonos
 * (como comercio destinatario o como portador), con su estado (spec §6, §7.1).
 */
@Composable
fun DeferredListScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenRecibido: (String) -> Unit = {},
    onRecibir: () -> Unit = {},
    viewModel: DeferredListViewModel = hiltViewModel(),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val recibidos by viewModel.recibidos.collectAsStateWithLifecycle()
    // Una wallet passkey o en modo demo no envía desde la app: no se le promete que "se liquidan solos".
    val soloPasarela = remember { viewModel.liquidador() is SobreSettler.Liquidador.Pasarela }
    LaunchedEffect(Unit) { viewModel.refresh() }
    val sorted = remember(records) { records.sortedByDescending { it.signedAtMs } }
    val recibidosOrdenados = remember(recibidos) { recibidos.sortedByDescending { it.receivedAtMs } }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TopBar(onBack = onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "titulo") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Mis sobres",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = RaizBlack,
                        )
                        Text(
                            text = "Pagos firmados sin señal. \"En tránsito\" es una promesa firmada, no dinero confirmado.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RaizBlack.copy(alpha = 0.6f),
                        )
                    }
                }
                if (sorted.isEmpty()) {
                    item(key = "vacio_propios") {
                        Text(
                            text = "Aún no has firmado sobres sin señal.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RaizBlack.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    items(sorted, key = { "propio:${it.id}" }) { r -> SobreRow(r, onClick = { onOpen(r.id) }) }
                }
                item(key = "cabecera_recibidos") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Recibidos",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = RaizBlack,
                            )
                            Text(
                                text = "Sobres de otros teléfonos: los tuyos como comercio y los que llevas. " +
                                    if (soloPasarela) {
                                        "Esta wallet no envía desde la app: para liquidarlos usa la pasarela web."
                                    } else {
                                        "Se liquidan solos al tener señal."
                                    },
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = RaizBlack.copy(alpha = 0.6f),
                            )
                        }
                        TextButton(onClick = onRecibir) {
                            Text("Recibir sobre", style = MaterialTheme.typography.labelLarge, color = RaizGreen)
                        }
                    }
                }
                if (recibidosOrdenados.isEmpty()) {
                    item(key = "vacio_recibidos") {
                        Text(
                            text = "No has recibido sobres.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RaizBlack.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    items(recibidosOrdenados, key = { "recibido:${it.id}" }) { r ->
                        RecibidoRow(r) { onOpenRecibido(r.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
        }
    }
}

@Composable
private fun SobreRow(r: DeferredRecord, onClick: () -> Unit) {
    val (label, color) = when (r.status) {
        DeferredStatus.FIRMADO, DeferredStatus.ENTREGADO -> "En tránsito" to RaizYellow.copy(alpha = 0.25f)
        DeferredStatus.CONFIRMADO -> "Confirmado" to RaizGreen.copy(alpha = 0.18f)
        DeferredStatus.EXPIRADO -> "Expirado" to RaizGray.copy(alpha = 0.25f)
        DeferredStatus.RECHAZADO -> "Rechazado" to RaizError.copy(alpha = 0.15f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RaizWhite)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = r.merchantName, style = MaterialTheme.typography.labelLarge, color = RaizBlack)
            Text(
                text = "${r.totalStroops.formatUsdc()} · ${DeferredLabels.validUntil(r.validUntilMs)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizBlack.copy(alpha = 0.6f),
            )
            r.txHash?.let {
                Text(text = "tx ${it.take(8)}…${it.takeLast(6)}", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp), color = RaizGreen)
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(color)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp), color = RaizBlack)
        }
    }
}

/** Detalle de un sobre (ruta `sobres/{id}`): re-muestra el QR y permite cerrarlo pegando el hash de la pasarela. */
@Composable
fun DeferredDetailRoute(
    id: String,
    onBack: () -> Unit,
    viewModel: DeferredListViewModel = hiltViewModel(),
) {
    val records by viewModel.records.collectAsState()
    val confirm by viewModel.confirm.collectAsState()
    val record = records.firstOrNull { it.id == id }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (record == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Sobre no encontrado", color = RaizBlack)
            }
        } else {
            DeferredSignedScreen(
                record = record,
                validUntilLabel = DeferredLabels.validUntil(record.validUntilMs),
                onVerSobres = onBack,
                onDone = onBack,
                contentPadding = padding,
                titulo = when (record.status) {
                    DeferredStatus.CONFIRMADO -> "Sobre confirmado"
                    DeferredStatus.EXPIRADO -> "Sobre expirado"
                    DeferredStatus.RECHAZADO -> "Sobre rechazado"
                    else -> "Sobre en tránsito"
                },
                extra = {
                    if (record.status != DeferredStatus.CONFIRMADO && record.status != DeferredStatus.RECHAZADO) {
                        PegarHash(
                            state = confirm,
                            onConfirm = { hash -> viewModel.confirmByHash(record.id, hash) },
                            onClear = viewModel::clearConfirm,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun PegarHash(state: ConfirmState?, onConfirm: (String) -> Unit, onClear: () -> Unit) {
    var hash by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "¿La pasarela ya lo liquidó? Pega el hash de la transacción para cerrar el sobre.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack,
        )
        OutlinedTextField(
            value = hash,
            onValueChange = { hash = it; if (state != null) onClear() },
            label = { Text("Hash de la transacción (64 hex)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (state) {
            ConfirmState.Working -> CircularProgressIndicator(color = RaizGreen)
            is ConfirmState.Done -> Text(
                text = if (state.record.status == DeferredStatus.CONFIRMADO) "Confirmado en la red." else "La red rechazó esa transacción.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.record.status == DeferredStatus.CONFIRMADO) RaizGreen else RaizError,
            )
            is ConfirmState.Failed -> Text(text = state.message, style = MaterialTheme.typography.bodyMedium, color = RaizError)
            null -> Unit
        }
        Button(
            onClick = { onConfirm(hash) },
            enabled = hash.isNotBlank() && state !is ConfirmState.Working,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = RaizBlack, contentColor = RaizWhite),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Comprobar en la red", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Detalle de un sobre recibido (ruta `recibido/{id}`): estado honesto, comprobante y acciones. El registro se refresca
 * solo (p. ej. pasa a "Cobrado"/"Liquidado" cuando la red lo confirma y aparece el hash).
 */
@Composable
fun RecibidoDetailRoute(
    id: String,
    onBack: () -> Unit,
    viewModel: DeferredListViewModel = hiltViewModel(),
) {
    val recibidos by viewModel.recibidos.collectAsStateWithLifecycle()
    val sinSenal by viewModel.sinSenal.collectAsStateWithLifecycle()
    val accion by viewModel.accion.collectAsStateWithLifecycle()
    val record = recibidos.firstOrNull { it.id == id }
    val liquidador = remember { viewModel.liquidador() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Efectos de un solo disparo: el ViewModel los deja en el estado; aquí se ejecutan y se consumen.
    val aviso = accion.aviso
    val pasarela = accion.pendientePasarela
    LaunchedEffect(aviso) {
        if (aviso != null) {
            Toast.makeText(context, aviso, Toast.LENGTH_LONG).show()
            viewModel.consumirAviso()
        }
    }
    LaunchedEffect(pasarela) {
        if (pasarela != null) {
            val (sobre, url) = pasarela
            clipboard.setText(AnnotatedString(sobre))
            val abierta = CustomTabs.open(context, url)
            Toast.makeText(
                context,
                if (abierta) "Sobre copiado. Si la pasarela no lo carga sola, pégalo ahí." else "No se pudo abrir la pasarela web.",
                Toast.LENGTH_LONG,
            ).show()
            viewModel.consumirPasarela()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (record == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Sobre no encontrado", color = RaizBlack)
            }
        } else {
            val (label, color) = estadoRecibidoLabel(record)
            val liquidando = accion.liquidandoId == record.id
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                TopBar(onBack = onBack)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "Sobre recibido",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = RaizBlack,
                    )
                    Text(
                        text = recibidoSubtitulo(record),
                        style = MaterialTheme.typography.labelLarge,
                        color = RaizGreen,
                    )
                    RecibidoPill(label = label, color = color)
                    RecibidoDetalle(record)
                    RecibidoNota(
                        r = record,
                        liquidador = liquidador,
                        sinSenal = sinSenal,
                        liquidando = liquidando,
                    )
                    RecibidoAcciones(
                        r = record,
                        liquidador = liquidador,
                        sinSenal = sinSenal,
                        liquidando = liquidando,
                        onLiquidar = { viewModel.liquidarRecibido(record.id) },
                        onPasarela = { viewModel.abrirPasarela(record.id) },
                        onCopiar = {
                            clipboard.setText(AnnotatedString(record.sobreQr))
                            Toast.makeText(context, "Sobre copiado", Toast.LENGTH_SHORT).show()
                        },
                        onVerTx = {
                            record.txHash?.let { hash -> StellarExpert.open(context, StellarExpert.txUrl(hash)) }
                        },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RaizGreen, contentColor = RaizWhite),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Listo", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
