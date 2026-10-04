package com.raiz.app.ui.deposit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raiz.app.data.anchor.AnchorDepositState
import com.raiz.app.data.anchor.AnchorDepositStatus
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.components.RaizSuccessAnimation
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import com.raiz.app.ui.util.StellarExpert

/**
 * Pantalla "Depositar USDC" — on-ramp SEP-24 contra el testanchor del SDF.
 *
 * El USDC que llega aquí es el del anchor de prueba (`testanchor.stellar.org`),
 * SIEMPRE distinto al USDC (Blend) que usa el fondo del barrio — nunca se
 * suma al balance principal de la wallet.
 */
@Composable
fun DepositScreen(
    onBack: () -> Unit,
    viewModel: DepositViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Al volver del Custom Tab (o de otra app) reanuda el polling si hace falta.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Evento one-shot: abre la URL interactiva SEP-24 en una Custom Tab.
    LaunchedEffect(Unit) {
        viewModel.openUrl.collect { url -> CustomTabs.open(context, url) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TopBar(onBack = onBack)

            when (state.phase) {
                DepositPhase.COMPLETED -> CompletedBody(state = state, viewModel = viewModel, onDone = onBack)
                else -> DefaultBody(state = state, viewModel = viewModel)
            }
        }
    }
}

// ── Barra superior ───────────────────────────────────────────────────────────

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
        }
    }
}

// ── Cuerpo por defecto (todas las fases salvo COMPLETED) ──────────────────────

@Composable
private fun DefaultBody(state: DepositUiState, viewModel: DepositViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Depositar USDC",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizBlack,
        )
        Text(
            text = "Anchor de prueba del SDF · ${state.anchorDomain} · SEP-10 + SEP-24",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )

        AnchorBalanceCard(state = state)

        Text(
            text = "Este USDC es el del anchor de prueba, distinto al USDC (Blend) que usa el fondo del barrio.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.5f),
        )

        // También con saldo 0 si la conversión ya arrancó: tras convertir el saldo del anchor
        // queda en 0 y la card debe seguir mostrando el resultado y el hash.
        if (state.phase == DepositPhase.READY &&
            (state.anchorUsdcBalanceStroops > 0L || state.swap !is SwapState.Idle)
        ) {
            ConvertCard(
                state = state,
                onConvert = viewModel::convertAnchorUsdc,
                onRetry = viewModel::retryQuote,
            )
        }

        when (state.phase) {
            DepositPhase.LOADING_INFO -> LoadingCard(texto = "Cargando el anchor de prueba…")
            DepositPhase.PASSKEY_UNSUPPORTED -> PasskeyUnsupportedCard()
            DepositPhase.NEEDS_XLM -> NeedsXlmCard(onFund = viewModel::fundWithFriendbot)
            DepositPhase.READY -> ReadyCard(
                state = state,
                onAmountChange = viewModel::onAmountChange,
                onDeposit = viewModel::startDeposit,
            )
            DepositPhase.PREPARING -> LoadingCard(texto = state.stepLabel ?: "Preparando…")
            DepositPhase.AWAITING_USER, DepositPhase.POLLING -> AwaitingCard(
                state = state,
                onReopen = viewModel::reopenInteractive,
                onCancel = viewModel::reset,
            )
            DepositPhase.TIMED_OUT -> TimedOutCard(
                onResume = viewModel::resumePolling,
                onBack = viewModel::reset,
            )
            DepositPhase.FAILED -> FailedCard(
                message = state.error ?: "Algo falló con el anchor de prueba.",
                onRetry = viewModel::reset,
            )
            DepositPhase.COMPLETED -> Unit // manejado en DepositScreen (CompletedBody)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ── Card negra de saldo (siempre visible salvo COMPLETED) ─────────────────────

@Composable
private fun AnchorBalanceCard(state: DepositUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(RaizBlack)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "USDC · anchor de prueba",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizWhite.copy(alpha = 0.7f),
        )
        Text(
            text = state.anchorUsdcBalanceStroops.formatUsdc(),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizYellow,
        )
        if (state.account.isNotBlank()) {
            Text(
                text = shortAddress(state.account),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizWhite.copy(alpha = 0.5f),
            )
        }
    }
}

// ── Convertir a USDC del fondo (stretch WP3: pool de liquidez de testnet) ─────

/**
 * Card de la conversión "USDC del anchor de prueba → USDC del fondo (Blend)"
 * vía un `PathPaymentStrictSendOperation` NO custodial (el propio usuario firma,
 * destino su misma cuenta). En mainnet este paso no existiría — el USDC de Circle
 * es uno solo; en testnet el anchor de prueba emite otro USDC distinto al de Blend.
 *
 * `send`/`dest` salen de [SwapState.Done] cuando ya terminó (el saldo del anchor
 * ya se refrescó a 0 en ese punto) o del saldo actual (`state.anchorUsdcBalanceStroops`,
 * que no cambia hasta que la conversión termina) en el resto de sub-estados.
 */
@Composable
private fun ConvertCard(
    state: DepositUiState,
    onConvert: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val swap = state.swap
    val send = if (swap is SwapState.Done) swap.sendStroops else state.anchorUsdcBalanceStroops

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, RaizPurple, RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Convertir a USDC del fondo",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            // formatUsdc() ya incluye la unidad ("4.5 USDC").
            text = if (swap is SwapState.Done) {
                "Tus ${send.formatUsdc()} del anchor de prueba ya son USDC del fondo (Blend): con él " +
                    "sí puedes pagar en los comercios de RAÍZ."
            } else {
                "Tienes ${send.formatUsdc()} del anchor de prueba. Con el USDC del fondo (Blend) " +
                    "sí puedes pagar en los comercios de RAÍZ."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )

        when (swap) {
            is SwapState.Idle -> Unit

            is SwapState.Quoting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(color = RaizPurple, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text(
                    text = "Cotizando…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.6f),
                )
            }

            is SwapState.Quoted -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Recibirás ≈ ${swap.destStroops.formatUsdc()} · pool de liquidez de " +
                        "testnet, fee 0,3 %, tolerancia 1 %",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = RaizBlack.copy(alpha = 0.6f),
                )
                Button(
                    onClick = onConvert,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = RaizPurple, contentColor = RaizWhite),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Convertir ${swap.sendStroops.formatUsdc()}", style = MaterialTheme.typography.labelLarge)
                }
            }

            is SwapState.Submitting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(color = RaizPurple, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text(
                    text = state.swapStepLabel ?: "Enviando la conversión…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.6f),
                )
            }

            is SwapState.Done -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "✓ Convertido: recibiste ${if (swap.destIsExact) "" else "≈ "}" +
                        "${swap.destStroops.formatUsdc()} del fondo",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = RaizGreen,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(RaizGreen)
                        .clickable { StellarExpert.open(context, StellarExpert.txUrl(swap.txHash)) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = RaizWhite,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Ver en Stellar Expert · ${shortAddress(swap.txHash)}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                        color = RaizWhite,
                    )
                }
            }

            is SwapState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = swap.message, style = MaterialTheme.typography.bodyMedium, color = RaizError)
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = RaizBlack, contentColor = RaizWhite),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Reintentar", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Text(
            text = "En mainnet este paso no existe: el USDC de Circle es uno solo. En testnet el " +
                "anchor de prueba emite otro USDC.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
            color = RaizBlack.copy(alpha = 0.45f),
        )
    }
}

// ── Fases ─────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingCard(texto: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = RaizGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(text = texto, style = MaterialTheme.typography.bodyMedium, color = RaizBlack.copy(alpha = 0.75f))
    }
}

@Composable
private fun PasskeyUnsupportedCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Disponible pronto para passkey (SEP-45)",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = "Tu wallet passkey es un smart account (C…). SEP-10 clásico solo autentica " +
                "cuentas Ed25519 (G…) — el anchor de prueba aún no soporta SEP-45 (WebAuthn).",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun NeedsXlmCard(onFund: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizYellow.copy(alpha = 0.18f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Tu cuenta aún no existe en Stellar",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = "Necesitas XLM de testnet para poder operar. Friendbot la fondea gratis (solo demo).",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        Button(
            onClick = onFund,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = RaizGreen, contentColor = RaizWhite),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Fondear con friendbot", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ReadyCard(
    state: DepositUiState,
    onAmountChange: (String) -> Unit,
    onDeposit: () -> Unit,
) {
    val min = state.minUsdc ?: 1.0
    val max = state.maxUsdc ?: 10.0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = state.amountInput,
            onValueChange = onAmountChange,
            label = { Text("Monto") },
            suffix = { Text("USDC") },
            supportingText = {
                Text(state.amountError ?: "Mín ${formatLimitLabel(min)} · Máx ${formatLimitLabel(max)} en el anchor de prueba")
            },
            isError = state.amountError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onDeposit,
            enabled = state.amountError == null && state.swap !is SwapState.Submitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RaizYellow,
                contentColor = RaizBlack,
                disabledContainerColor = RaizYellow.copy(alpha = 0.5f),
                disabledContentColor = RaizBlack.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Depositar con el anchor", style = MaterialTheme.typography.labelLarge)
        }
        DepositSteps()
    }
}

@Composable
private fun DepositSteps() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "1 · Trustline al USDC del anchor (una vez)",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.55f),
        )
        Text(
            text = "2 · Autenticación SEP-10 firmada con tu wallet",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.55f),
        )
        Text(
            text = "3 · Depósito interactivo SEP-24 en la web del anchor",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun AwaitingCard(
    state: DepositUiState,
    onReopen: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.status?.let { StatusChip(it) }
        Text(
            text = statusHumanLabel(state.phase, state.status),
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.75f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = RaizGreen, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(
                text = "Esperando al anchor… ${state.elapsedSec}s",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }
        // Tras una restauración del proceso la URL interactiva no se conserva (lleva el
        // token del anchor; SavedStateHandle acabaría en disco) y `more_info_url` llega con
        // la primera respuesta del anchor: hasta entonces el botón no tiene nada que abrir.
        val canReopen = state.interactiveUrl != null || state.status?.moreInfoUrl != null
        OutlinedButton(
            onClick = onReopen,
            enabled = canReopen,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = "Abrir de nuevo la web del anchor",
                color = if (canReopen) RaizBlack else RaizBlack.copy(alpha = 0.4f),
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancelar", color = RaizBlack.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun StatusChip(status: AnchorDepositStatus) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(RaizPurple)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = status.rawStatus,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
            color = RaizWhite,
        )
    }
}

@Composable
private fun TimedOutCard(onResume: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "El anchor sigue procesando",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = "No es un error — algunos depósitos de prueba tardan más. Puedes seguir esperando o volver más tarde.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onResume,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = RaizGreen, contentColor = RaizWhite),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Seguir esperando", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Volver", color = RaizBlack)
            }
        }
    }
}

@Composable
private fun FailedCard(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizError,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = RaizBlack, contentColor = RaizWhite),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Reintentar", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Éxito ─────────────────────────────────────────────────────────────────────

@Composable
private fun CompletedBody(state: DepositUiState, viewModel: DepositViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val amountOut = state.status?.amountOutUsdc ?: state.amountInput
    val hash = state.txHash

    // Con la ConvertCard debajo del chip el contenido ya no cabe centrado en pantallas
    // de 360×640 dp: la columna pasa a ser desplazable (verticalScroll) y los dos
    // Spacer(weight(1f)) que centraban se sustituyen por alturas fijas — weight() dentro
    // de un scroll lanza IllegalStateException (altura no acotada) en Compose.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        RaizSuccessAnimation(
            titulo = "¡Depósito recibido!",
            subtitulo = "$amountOut USDC · anchor de prueba",
        )

        if (!hash.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(RaizGreen)
                    .clickable { StellarExpert.open(context, StellarExpert.txUrl(hash)) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = RaizWhite,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Ver en Stellar Expert · ${shortAddress(hash)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    color = RaizWhite,
                )
            }
        }

        // Solo cuando hay algo que convertir (saldo del anchor ya refrescado > 0) o la
        // conversión ya arrancó (Quoting/Quoted/Submitting/Done/Failed): mientras el
        // refresh post-depósito sigue en vuelo el saldo aún es el previo (0 en el primer
        // depósito) y la card diría "Tienes 0,00 USDC…".
        if (state.swap !is SwapState.Idle || state.anchorUsdcBalanceStroops > 0L) {
            Spacer(modifier = Modifier.height(16.dp))
            ConvertCard(
                state = state,
                onConvert = viewModel::convertAnchorUsdc,
                onRetry = viewModel::retryQuote,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RaizGreen, contentColor = RaizWhite),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Volver a la wallet", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun shortAddress(addr: String): String =
    if (addr.length <= 12) addr else "${addr.take(6)}…${addr.takeLast(6)}"

private fun formatLimitLabel(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun statusHumanLabel(phase: DepositPhase, status: AnchorDepositStatus?): String = when {
    phase == DepositPhase.AWAITING_USER -> "Completa el depósito en la web del anchor"
    status?.state == AnchorDepositState.PENDING_TRUST -> "Falta la trustline: la app la crea ahora"
    else -> "El anchor está procesando…"
}
