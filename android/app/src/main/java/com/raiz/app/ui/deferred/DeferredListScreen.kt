package com.raiz.app.ui.deferred

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.deferred.DeferredRecord
import com.raiz.app.data.deferred.DeferredStatus
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.pay.DeferredSignedScreen
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGray
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/** Historial "Mis sobres": los sobres firmados sin señal por este teléfono y su estado (spec §6, §7.1). */
@Composable
fun DeferredListScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: DeferredListViewModel = hiltViewModel(),
) {
    val records by viewModel.records.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val sorted = remember(records) { records.sortedByDescending { it.signedAtMs } }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TopBar(onBack = onBack)
            Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Spacer(modifier = Modifier.height(12.dp))
            if (sorted.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Aún no has firmado sobres sin señal.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RaizBlack.copy(alpha = 0.6f),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sorted, key = { it.id }) { r -> SobreRow(r, onClick = { onOpen(r.id) }) }
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
