package com.raiz.app.ui.deferred

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.raiz.app.data.deferred.ReceivedSobreRecord
import com.raiz.app.data.deferred.RecibidoCheck
import com.raiz.app.data.deferred.SobreCodec
import com.raiz.app.data.deferred.SobreSettler
import com.raiz.app.ui.deposit.CustomTabs
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import com.raiz.app.ui.util.StellarExpert

/**
 * "Recibir sobre": cualquier wallet verifica sin red un sobre que le mostró otro teléfono y lo guarda. Si el comercio
 * destinatario es esta wallet (COBRO) es un pago para ella; si no (PORTADOR) solo lo lleva. El texto llega por
 * [com.raiz.app.data.deferred.SobreInbox] (Inicio lo escanea) o se pega/escanea aquí. Un sobre es una promesa firmada,
 * no dinero: una wallet semilla lo liquida sola al tener señal (una passkey o en modo demo lo deja a la pasarela web) y
 * solo entonces pasa a "Cobrado"/"Liquidado" (con el hash). Los textos distinguen a las dos: nunca se promete un envío
 * automático a una wallet que no envía desde la app.
 */
@Composable
fun RecibirSobreScreen(
    onBack: () -> Unit,
    onVerRecibidos: () -> Unit,
    onOpenRecibido: (String) -> Unit = {},
    viewModel: RecibirSobreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Escáner propio: a esta pantalla también se llega desde "Mis sobres", sin haber escaneado en Inicio.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        when {
            raw.isEmpty() -> Unit // el usuario canceló el escáner
            SobreCodec.isSobre(raw) -> viewModel.verificar(raw)
            else -> Toast.makeText(context, "Ese QR no es un sobre RAÍZ", Toast.LENGTH_SHORT).show()
        }
    }

    // Efectos de un solo disparo: el ViewModel los deja en el estado; aquí se ejecutan y se consumen.
    val resultado = state as? RecibirSobreUiState.Resultado
    val aviso = resultado?.aviso
    val pasarela = resultado?.pendientePasarela
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            RecibirTopBar(onBack = onBack)
            when (val s = state) {
                is RecibirSobreUiState.Pegar -> PegarContenido(
                    state = s,
                    onTexto = viewModel::onTexto,
                    onPegarPortapapeles = {
                        val pegado = clipboard.getText()?.text?.trim().orEmpty()
                        if (pegado.isEmpty()) {
                            Toast.makeText(context, "El portapapeles está vacío", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.onTexto(pegado)
                        }
                    },
                    onEscanear = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setBeepEnabled(false)
                                .setOrientationLocked(true)
                                .setPrompt("Apunta al QR del sobre"),
                        )
                    },
                    onVerificar = { viewModel.verificar(s.texto) },
                )
                RecibirSobreUiState.Verificando -> VerificandoContenido()
                is RecibirSobreUiState.Resultado -> ResultadoContenido(
                    state = s,
                    onLiquidar = viewModel::liquidarAhora,
                    onPasarela = viewModel::abrirPasarela,
                    onCopiar = { texto ->
                        clipboard.setText(AnnotatedString(texto))
                        Toast.makeText(context, "Sobre copiado", Toast.LENGTH_SHORT).show()
                    },
                    onVerTx = { hash -> StellarExpert.open(context, StellarExpert.txUrl(hash)) },
                    onVerRecibidos = onVerRecibidos,
                    onOpenRecibido = onOpenRecibido,
                    onReiniciar = viewModel::reiniciar,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun RecibirTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
        }
        Text(
            text = "Recibir sobre",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
        )
    }
}

/** Sin sobre en el buzón: escanear o pegar el texto del sobre y verificarlo. */
@Composable
private fun PegarContenido(
    state: RecibirSobreUiState.Pegar,
    onTexto: (String) -> Unit,
    onPegarPortapapeles: () -> Unit,
    onEscanear: () -> Unit,
    onVerificar: () -> Unit,
) {
    // Una wallet passkey o en modo demo no envía desde la app: solo guarda el sobre y lo liquida la pasarela web.
    val soloPasarela = state.liquidador is SobreSettler.Liquidador.Pasarela
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = if (soloPasarela) {
                "Pega el sobre que te mostró otro teléfono, o escanea su QR. Se verifica aquí, sin red, y se guarda " +
                    "en este teléfono. Esta wallet no envía desde la app: para liquidarlo usa la pasarela web cuando haya señal."
            } else {
                "Pega el sobre que te mostró otro teléfono, o escanea su QR. Se verifica aquí, sin red, " +
                    "y se liquida solo en cuanto este teléfono tenga señal."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        Text(
            text = "Un sobre es una promesa firmada, no dinero: solo cuenta como cobrado cuando la red lo liquida.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        if (state.sinSenal) {
            SinSenalAviso(soloPasarela = soloPasarela)
        }
        OutlinedButton(
            onClick = onEscanear,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, RaizGreen),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizGreen),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text("Escanear el QR del sobre", style = MaterialTheme.typography.labelLarge)
        }
        OutlinedTextField(
            value = state.texto,
            onValueChange = onTexto,
            label = { Text("Pega aquí el sobre (raiz:sobre:1:…)") },
            minLines = 4,
            maxLines = 8,
            isError = state.aviso != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.aviso != null) {
            Text(
                text = state.aviso,
                style = MaterialTheme.typography.bodyMedium,
                color = RaizError,
            )
        }
        OutlinedButton(
            onClick = onPegarPortapapeles,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, RaizPurple),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizPurple),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Pegar del portapapeles", style = MaterialTheme.typography.labelLarge)
        }
        Button(
            onClick = onVerificar,
            enabled = state.texto.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizGreen,
                contentColor = RaizWhite,
                disabledContainerColor = RaizGreen.copy(alpha = 0.35f),
                disabledContentColor = RaizWhite.copy(alpha = 0.8f),
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Verificar sin red", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Aviso amarillo de "sin señal" (mismo estilo que el de Pagar). Con una wallet que no envía desde la app
 * ([soloPasarela]) no promete el envío automático: el sobre se guarda y se liquida desde la pasarela web.
 */
@Composable
private fun SinSenalAviso(soloPasarela: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RaizYellow.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = RaizBlack,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column {
            Text(
                text = "Sin señal",
                style = MaterialTheme.typography.labelLarge,
                color = RaizBlack,
            )
            Text(
                text = if (soloPasarela) {
                    "Puedes verificar y guardar el sobre ahora; para liquidarlo usa la pasarela web cuando haya red."
                } else {
                    "Puedes verificar y guardar el sobre ahora; se liquidará solo cuando este teléfono recupere la señal."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun VerificandoContenido() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = RaizGreen)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Verificando el sobre…",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Se comprueban la firma y el reparto sin salir del teléfono.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ResultadoContenido(
    state: RecibirSobreUiState.Resultado,
    onLiquidar: () -> Unit,
    onPasarela: () -> Unit,
    onCopiar: (String) -> Unit,
    onVerTx: (String) -> Unit,
    onVerRecibidos: () -> Unit,
    onOpenRecibido: (String) -> Unit,
    onReiniciar: () -> Unit,
    onBack: () -> Unit,
) {
    when (val check = state.check) {
        is RecibidoCheck.Rechazado -> RechazadoContenido(
            motivo = check.motivo,
            onOtroSobre = onReiniciar,
            onVolver = onBack,
        )
        is RecibidoCheck.Aceptado -> SobreContenido(
            titulo = "Sobre verificado",
            record = check.record,
            state = state,
            yaRegistrado = false,
            onLiquidar = onLiquidar,
            onPasarela = onPasarela,
            onCopiar = onCopiar,
            onVerTx = onVerTx,
            onVerRecibidos = onVerRecibidos,
            onOpenRecibido = onOpenRecibido,
            onListo = onBack,
        )
        is RecibidoCheck.YaRegistrado -> SobreContenido(
            titulo = "Ya tenías este sobre",
            record = check.record,
            state = state,
            yaRegistrado = true,
            onLiquidar = onLiquidar,
            onPasarela = onPasarela,
            onCopiar = onCopiar,
            onVerTx = onVerTx,
            onVerRecibidos = onVerRecibidos,
            onOpenRecibido = onOpenRecibido,
            onListo = onBack,
        )
    }
}

/** Sobre aceptado (o ya registrado): detalle, texto honesto y acciones según quién pueda liquidarlo. */
@Composable
private fun SobreContenido(
    titulo: String,
    record: ReceivedSobreRecord,
    state: RecibirSobreUiState.Resultado,
    yaRegistrado: Boolean,
    onLiquidar: () -> Unit,
    onPasarela: () -> Unit,
    onCopiar: (String) -> Unit,
    onVerTx: (String) -> Unit,
    onVerRecibidos: () -> Unit,
    onOpenRecibido: (String) -> Unit,
    onListo: () -> Unit,
) {
    val (label, color) = estadoRecibidoLabel(record)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = titulo,
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
                liquidador = state.liquidador,
                sinSenal = state.sinSenal,
                liquidando = state.liquidando,
            )
            RecibidoAcciones(
                r = record,
                liquidador = state.liquidador,
                sinSenal = state.sinSenal,
                liquidando = state.liquidando,
                onLiquidar = onLiquidar,
                onPasarela = onPasarela,
                onCopiar = { onCopiar(record.sobreQr) },
                onVerTx = { record.txHash?.let(onVerTx) },
            )
            if (yaRegistrado) {
                TextButton(onClick = { onOpenRecibido(record.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ver el detalle del sobre", style = MaterialTheme.typography.bodyMedium, color = RaizPurple)
                }
            }
            TextButton(onClick = onVerRecibidos, modifier = Modifier.fillMaxWidth()) {
                Text("Ver mis sobres", style = MaterialTheme.typography.bodyMedium, color = RaizGreen)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        Button(
            onClick = onListo,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RaizGreen, contentColor = RaizWhite),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Listo", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** El sobre no pasó la verificación: no se guarda nada y no se entrega nada a cambio. */
@Composable
private fun RechazadoContenido(
    motivo: String,
    onOtroSobre: () -> Unit,
    onVolver: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Sobre rechazado",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
            )
            Text(
                text = motivo,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizError,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(RaizError.copy(alpha = 0.08f))
                    .padding(16.dp),
            )
            Text(
                text = "Este teléfono no lo registró, así que no lo liquidará. " +
                    "No lo des por recibido ni entregues nada a cambio.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
            TextButton(onClick = onOtroSobre, modifier = Modifier.fillMaxWidth()) {
                Text("Pegar otro sobre", style = MaterialTheme.typography.bodyMedium, color = RaizGreen)
            }
        }
        Button(
            onClick = onVolver,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RaizGreen, contentColor = RaizWhite),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Volver", style = MaterialTheme.typography.labelLarge)
        }
    }
}
