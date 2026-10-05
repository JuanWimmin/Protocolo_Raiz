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
import androidx.compose.ui.graphics.Color
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
import com.raiz.app.data.stellar.SwapMath
import com.raiz.app.ui.components.RaizSuccessAnimation
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import com.raiz.app.ui.util.StellarExpert

/**
 * Pantalla "Depositar USDC" — on-ramp SEP-24 contra el testanchor del SDF, para TODAS las
 * wallets (semilla y passkey).
 *
 * El USDC que entrega el anchor de prueba (`testanchor.stellar.org`) es SIEMPRE distinto al
 * USDC (Blend) que usa el fondo del barrio — nunca se suma al balance principal de la wallet.
 * Tras el depósito la app lo convierte al USDC del fondo (sola, si el precio pasa la guarda) y,
 * en wallets passkey, lo envía de la cuenta de depósito al smart account.
 */
@Composable
fun DepositScreen(
    onBack: () -> Unit,
    viewModel: DepositViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Al volver del Custom Tab (o de otra app) reanuda el polling / el cierre si hace falta.
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
                else -> DefaultBody(state = state, viewModel = viewModel, onDone = onBack)
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
private fun DefaultBody(state: DepositUiState, viewModel: DepositViewModel, onDone: () -> Unit) {
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
            text = "El anchor de prueba entrega su propio USDC. La app lo convierte al USDC del fondo " +
                "(Blend), que es el que sirve para pagar.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.5f),
        )

        // Lo que haya quedado a medio camino se muestra (y se termina) aunque el anchor no
        // responda: ni la conversión ni el envío a la wallet dependen de él.
        val showSettlement = state.phase == DepositPhase.READY || state.phase == DepositPhase.ANCHOR_UNAVAILABLE
        if (showSettlement && state.isPasskey && hasDepositInTransit(state)) {
            DepositInTransitCard(state = state, onFinish = viewModel::retryQuote)
        }
        if (showSettlement && showConvertCard(state)) {
            ConvertCard(
                state = state,
                onConvert = viewModel::convertAnchorUsdc,
                onRetry = viewModel::retryQuote,
                onDemoUsdc = viewModel::requestDemoUsdc,
            )
        }
        if (showSettlement && showForwardCard(state)) {
            ForwardCard(state = state, onRetry = viewModel::retryForward)
        }

        when (state.phase) {
            DepositPhase.LOADING_INFO -> LoadingCard(texto = "Cargando el anchor de prueba…")
            DepositPhase.ANCHOR_UNAVAILABLE -> AnchorUnavailableCard(
                state = state,
                onRetry = viewModel::retryAnchor,
                onDemoUsdc = viewModel::requestDemoUsdc,
                onDone = onDone,
            )
            DepositPhase.NEEDS_XLM -> NeedsXlmCard(onFund = viewModel::fundWithFriendbot)
            DepositPhase.READY -> {
                if (state.isPasskey) PasskeyInfoCard()
                ReadyCard(
                    state = state,
                    onAmountChange = viewModel::onAmountChange,
                    onDeposit = viewModel::startDeposit,
                )
            }
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

/**
 * La card de conversión aparece con saldo del anchor que convertir o cuando la conversión ya
 * arrancó: tras convertir el saldo queda en 0 y la card debe seguir mostrando el resultado y
 * el hash.
 */
private fun showConvertCard(state: DepositUiState): Boolean =
    state.anchorUsdcBalanceStroops > 0L || state.swap !is SwapState.Idle

/** La card del envío a la wallet solo existe en passkey, con USDC del fondo pendiente o un envío en curso/terminado. */
private fun showForwardCard(state: DepositUiState): Boolean =
    state.isPasskey && (state.fundPendingStroops > 0L || state.forward !is ForwardState.Idle)

/** Passkey: ¿queda dinero en la cuenta de depósito (o una tx propia sin confirmar)? */
private fun hasDepositInTransit(state: DepositUiState): Boolean =
    state.anchorUsdcBalanceStroops > 0L || state.fundPendingStroops > 0L ||
        state.swap is SwapState.Quoting || state.swap is SwapState.Quoted ||
        state.swap is SwapState.Submitting || state.swap is SwapState.Confirming ||
        state.forward is ForwardState.Sending || state.forward is ForwardState.Confirming ||
        state.forward is ForwardState.Failed

/**
 * Passkey: queda dinero en la cuenta de depósito, el cierre no está corriendo y ninguna otra
 * card ofrece ya la acción (convertir, reintentar) ni hay una transacción confirmándose: hace
 * falta un tap en "Terminar depósito".
 */
private fun needsFinishTap(state: DepositUiState): Boolean {
    val anotherCardOffersAction = state.swap is SwapState.Quoted || state.swap is SwapState.Failed ||
        state.forward is ForwardState.Failed
    return state.isPasskey && !state.settling && !anotherCardOffersAction &&
        state.swap !is SwapState.Confirming && state.forward !is ForwardState.Confirming &&
        (state.anchorUsdcBalanceStroops > 0L || state.fundPendingStroops > 0L)
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
                // Passkey: la cuenta que recibe del anchor no es la wallet del usuario sino su
                // cuenta de depósito; se rotula para que una G… desconocida no confunda.
                text = if (state.isPasskey) {
                    "Cuenta de depósito · ${shortAddress(state.account)}"
                } else {
                    shortAddress(state.account)
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizWhite.copy(alpha = 0.5f),
            )
        }
    }
}

// ── Passkey: aviso de depósito en camino ──────────────────────────────────────

/**
 * Wallet passkey con dinero todavía en la cuenta de depósito (USDC del anchor sin convertir,
 * USDC del fondo sin enviar, o una transacción propia sin confirmar). La app lo termina sola;
 * el botón solo aparece si la cadena se detuvo sin que otra card ofrezca ya la acción.
 */
@Composable
private fun DepositInTransitCard(state: DepositUiState, onFinish: () -> Unit) {
    val needsTap = needsFinishTap(state)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizYellow.copy(alpha = 0.18f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Tienes un depósito en camino",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = "Lo terminamos de llevar a tu wallet.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        if (needsTap) {
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = RaizGreen, contentColor = RaizWhite),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Terminar depósito", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ── Convertir a USDC del fondo (pool de liquidez de testnet) ──────────────────

/**
 * Card de la conversión "USDC del anchor de prueba → USDC del fondo (Blend)"
 * vía un `PathPaymentStrictSendOperation` NO custodial (firma la cuenta operativa,
 * destino esa misma cuenta). En mainnet este paso no existiría — el USDC de Circle
 * es uno solo; en testnet el anchor de prueba emite otro USDC distinto al de Blend.
 *
 * Tras un depósito la conversión arranca sola si la cotización pasa la guarda de precio
 * (≥ 97 % de lo enviado). Si no la pasa, la card muestra la cotización, el aviso y los
 * botones; por debajo del 50 % solo deja volver a cotizar.
 *
 * `send` sale de [SwapState.Done] cuando ya terminó (el saldo del anchor ya se refrescó a 0
 * en ese punto) o del saldo actual en el resto de sub-estados.
 */
@Composable
private fun ConvertCard(
    state: DepositUiState,
    onConvert: () -> Unit,
    onRetry: () -> Unit,
    onDemoUsdc: () -> Unit,
) {
    val swap = state.swap
    val send = if (swap is SwapState.Done) swap.sendStroops else state.anchorUsdcBalanceStroops
    val where = if (state.isPasskey) "tu cuenta de depósito" else "tu cuenta"

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
        // Sin saldo conocido (no se pudo leer la cuenta, o la conversión se recuperó por su hash
        // antes de leerla) no se afirma "Tienes 0 USDC": habla solo el estado de más abajo.
        if (swap is SwapState.Done || send > 0L) {
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
        }

        when (swap) {
            is SwapState.Idle -> Unit

            is SwapState.Quoting -> SpinnerRow(texto = "Cotizando…", color = RaizPurple)

            is SwapState.Quoted -> when (swap.guard) {
                SwapMath.QuoteGuard.OK -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

                // Fuera de la guarda de precio: NO se convierte sola.
                SwapMath.QuoteGuard.BELOW_AUTO, SwapMath.QuoteGuard.BELOW_HARD_FLOOR -> PriceGuardNotice(
                    swap = swap,
                    where = where,
                    faucet = state.demoFaucet,
                    onConvert = onConvert,
                    onRetry = onRetry,
                    onDemoUsdc = onDemoUsdc,
                )
            }

            is SwapState.Submitting ->
                SpinnerRow(texto = state.swapStepLabel ?: "Enviando la conversión…", color = RaizPurple)

            is SwapState.Confirming -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "La conversión salió pero aún no se confirma (tx ${shortHash(swap.txHash)}). " +
                        if (state.settling) "No la repitas: lo estamos comprobando." else "No la repitas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.7f),
                )
                // El sondeo se detuvo sin veredicto. Volver a comprobar es seguro: primero se
                // resuelve esa tx por su hash y no se construye otra mientras pueda entrar.
                if (!state.settling) CheckNowButton(onClick = onRetry)
            }

            is SwapState.Done -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = when {
                        swap.destStroops <= 0L -> "✓ Convertido a USDC del fondo"
                        swap.destIsExact -> "✓ Convertido: recibiste ${swap.destStroops.formatUsdc()} del fondo"
                        else -> "✓ Convertido: recibiste ≈ ${swap.destStroops.formatUsdc()} del fondo"
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = RaizGreen,
                )
                TxChip(hash = swap.txHash)
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

/**
 * Aviso de la guarda de precio: la cotización del pool está lejos de 1 a 1 y por eso la app no
 * convirtió sola. Entre el 50 % y el 97 % se puede convertir a mano ("Convertir de todos modos");
 * por debajo del 50 % solo se ofrece volver a cotizar.
 */
@Composable
private fun PriceGuardNotice(
    swap: SwapState.Quoted,
    where: String,
    faucet: DemoFaucetState,
    onConvert: () -> Unit,
    onRetry: () -> Unit,
    onDemoUsdc: () -> Unit,
) {
    val percent = SwapMath.ratioBps(swap.sendStroops, swap.destStroops) / 100
    val belowHardFloor = swap.guard == SwapMath.QuoteGuard.BELOW_HARD_FLOOR
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "El cambio está lejos de 1 a 1: por ${swap.sendStroops.formatUsdc()} del anchor " +
                "recibirías ≈ ${swap.destStroops.formatUsdc()} del fondo ($percent %). " +
                if (belowHardFloor) {
                    "Es menos de la mitad: por seguridad no se puede convertir ahora."
                } else {
                    "Por eso no lo convertimos solos."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = RaizError,
        )
        Text(
            text = "El precio lo pone un pool de testnet que RAÍZ no controla. Tu USDC sigue en $where.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.6f),
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = RaizBlack, contentColor = RaizWhite),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Volver a cotizar", style = MaterialTheme.typography.labelLarge)
        }
        if (!belowHardFloor) {
            OutlinedButton(
                onClick = onConvert,
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, RaizPurple),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizPurple),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Convertir de todos modos", style = MaterialTheme.typography.labelLarge)
            }
        } else {
            // Sin conversión posible, la única forma de seguir probando RAÍZ es el USDC demo del
            // relayer: la misma contingencia que cuando el anchor no responde.
            Text(
                text = "Mientras tanto puedes seguir probando RAÍZ con USDC demo.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizBlack.copy(alpha = 0.6f),
            )
            DemoFaucetFallback(faucet = faucet, onDemoUsdc = onDemoUsdc)
        }
    }
}

/**
 * Contingencia "USDC demo (relayer)": botón, progreso y resultado. La usan el aviso de anchor
 * caído y el de pool de liquidez sin precio — los dos casos en los que el depósito normal no
 * puede terminar en USDC del fondo por causas ajenas a RAÍZ.
 */
@Composable
private fun DemoFaucetFallback(faucet: DemoFaucetState, onDemoUsdc: () -> Unit) {
    if (faucet is DemoFaucetState.Done) {
        Text(
            // formatUsdc() ya incluye la unidad ("20 USDC").
            text = "✓ Recibiste ${faucet.amountStroops.formatUsdc()} demo",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = RaizGreen,
        )
        TxChip(hash = faucet.txHash)
        return
    }
    OutlinedButton(
        onClick = onDemoUsdc,
        enabled = faucet !is DemoFaucetState.Requesting,
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, RaizGreen),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizGreen),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text("Usar USDC demo (relayer)", style = MaterialTheme.typography.labelLarge)
    }
    if (faucet is DemoFaucetState.Requesting) {
        SpinnerRow(texto = faucet.stepLabel ?: "Pidiendo USDC demo al relayer…", color = RaizGreen)
    }
    if (faucet is DemoFaucetState.Failed) {
        Text(text = faucet.message, style = MaterialTheme.typography.bodyMedium, color = RaizError)
    }
}

/** "Comprobar ahora": relanza el cierre cuando una transacción quedó sin veredicto y nadie la sondea. */
@Composable
private fun CheckNowButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, RaizBlack),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RaizBlack),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text("Comprobar ahora", style = MaterialTheme.typography.labelLarge)
    }
}

// ── Passkey: envío del USDC del fondo a la wallet ─────────────────────────────

/**
 * Último tramo del depósito de una wallet passkey: la cuenta de depósito envía el USDC del
 * fondo al smart account (`transfer` del SAC). El smart account solo recibe: sin huella.
 */
@Composable
private fun ForwardCard(state: DepositUiState, onRetry: () -> Unit) {
    val forward = state.forward
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, RaizGreen, RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Envío a tu wallet",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        when (forward) {
            is ForwardState.Idle -> Text(
                text = "Hay ${state.fundPendingStroops.formatUsdc()} del fondo en tu cuenta de depósito, " +
                    "listos para pasar a tu wallet.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )

            is ForwardState.Sending -> SpinnerRow(texto = "Enviando a tu wallet…", color = RaizGreen)

            is ForwardState.Confirming -> if (forward.stalled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "El envío salió pero aún no se confirma (tx ${shortHash(forward.txHash)}). " +
                            if (state.settling) "No lo repitas: lo estamos comprobando." else "No lo repitas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RaizBlack.copy(alpha = 0.7f),
                    )
                    if (!state.settling) CheckNowButton(onClick = onRetry)
                }
            } else {
                SpinnerRow(texto = "Confirmando el envío…", color = RaizGreen)
            }

            is ForwardState.Done -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    // formatUsdc() ya incluye la unidad.
                    text = "✓ Llegó a tu wallet: ${forward.amountStroops.formatUsdc()}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = RaizGreen,
                )
                TxChip(hash = forward.txHash)
                Text(
                    text = "Ya puedes pagar en los comercios de RAÍZ.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.7f),
                )
            }

            is ForwardState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = forward.message, style = MaterialTheme.typography.bodyMedium, color = RaizError)
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
    }
}

// ── Piezas compartidas ────────────────────────────────────────────────────────

@Composable
private fun SpinnerRow(texto: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text(
            text = texto,
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )
    }
}

/** Chip verde "Ver en Stellar Expert · abc123…xyz789": abre la transacción en el explorador. */
@Composable
private fun TxChip(hash: String) {
    val context = LocalContext.current
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

/**
 * Wallet passkey: explica en una frase la cuenta de depósito. SEP-10 clásico solo autentica
 * cuentas Ed25519 (G…); la autenticación directa del smart account (SEP-45) — que el anchor de
 * prueba SÍ ofrece ya — está en el roadmap de RAÍZ.
 */
@Composable
private fun PasskeyInfoCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Así llega a tu wallet passkey",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = "El anchor entrega el USDC a una cuenta de depósito que RAÍZ crea para ti en este " +
                "teléfono; en cuanto llega, la app lo convierte y lo pasa a tu wallet, sin pedirte la huella.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
        Text(
            text = "La conexión directa de tu wallet passkey con el anchor (SEP-45) está en el roadmap " +
                "de RAÍZ: el anchor de prueba ya la ofrece, falta implementarla en la app.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = RaizBlack.copy(alpha = 0.5f),
        )
    }
}

/**
 * El anchor de prueba no respondió (toml / `/info`). Se puede reintentar o, como contingencia
 * — también en builds release —, pedir USDC demo (Blend) al relayer directo a la wallet.
 */
@Composable
private fun AnchorUnavailableCard(
    state: DepositUiState,
    onRetry: () -> Unit,
    onDemoUsdc: () -> Unit,
    onDone: () -> Unit,
) {
    val faucet = state.demoFaucet
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizWhite)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "El anchor de prueba no responde",
            style = MaterialTheme.typography.labelLarge,
            color = RaizBlack,
        )
        Text(
            text = "Revisa tu conexión y reintenta. Si el anchor sigue caído, puedes seguir probando " +
                "RAÍZ con USDC demo.",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )

        if (faucet !is DemoFaucetState.Done) {
            Button(
                onClick = onRetry,
                enabled = faucet !is DemoFaucetState.Requesting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = RaizBlack, contentColor = RaizWhite),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Reintentar", style = MaterialTheme.typography.labelLarge)
            }
        }
        DemoFaucetFallback(faucet = faucet, onDemoUsdc = onDemoUsdc)
        if (faucet is DemoFaucetState.Done) {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = RaizGreen, contentColor = RaizWhite),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Volver a la wallet", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            text = "USDC demo: USDC del fondo (Blend) que te envía el relayer de RAÍZ. Solo para pruebas.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
            color = RaizBlack.copy(alpha = 0.45f),
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
    // Un cierre en curso o una tx propia sin confirmar y un depósito nuevo no se solapan
    // (comparten cuenta y secuencia).
    val busy = state.settling ||
        state.swap is SwapState.Submitting || state.swap is SwapState.Confirming ||
        state.forward is ForwardState.Sending || state.forward is ForwardState.Confirming
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
            enabled = state.amountError == null && !busy,
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
        DepositSteps(isPasskey = state.isPasskey)
    }
}

@Composable
private fun DepositSteps(isPasskey: Boolean) {
    val steps = if (isPasskey) {
        listOf(
            "1 · Cuenta de depósito lista para recibir (una vez)",
            "2 · Autenticación SEP-10 firmada por tu cuenta de depósito",
            "3 · Depósito interactivo SEP-24 en la web del anchor",
            "4 · Conversión al USDC del fondo y envío a tu wallet",
        )
    } else {
        listOf(
            "1 · Trustline al USDC del anchor (una vez)",
            "2 · Autenticación SEP-10 firmada con tu wallet",
            "3 · Depósito interactivo SEP-24 en la web del anchor",
            "4 · Conversión automática al USDC del fondo",
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { step ->
            Text(
                text = step,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizBlack.copy(alpha = 0.55f),
            )
        }
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

/**
 * "¡Depósito recibido!" y, debajo, el cierre a la vista: el chip del pago del anchor, la card
 * de la conversión (con su chip) y — en wallets passkey — la del envío a la wallet (con su chip
 * y el "✓ Llegó a tu wallet"). Los tres chips abren Stellar Expert.
 */
@Composable
private fun CompletedBody(state: DepositUiState, viewModel: DepositViewModel, onDone: () -> Unit) {
    val amountOut = state.status?.amountOutUsdc ?: state.amountInput
    val hash = state.txHash

    // Con las cards debajo del chip el contenido ya no cabe centrado en pantallas
    // de 360×640 dp: la columna es desplazable (verticalScroll) y los dos
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
            TxChip(hash = hash)
        }

        // Solo cuando hay algo que convertir (saldo del anchor ya refrescado > 0) o la
        // conversión ya arrancó (Quoting/Quoted/Submitting/Done/Failed): mientras el
        // refresh post-depósito sigue en vuelo el saldo aún es el previo (0 en el primer
        // depósito) y la card diría "Tienes 0,00 USDC…".
        if (showConvertCard(state)) {
            Spacer(modifier = Modifier.height(16.dp))
            ConvertCard(
                state = state,
                onConvert = viewModel::convertAnchorUsdc,
                onRetry = viewModel::retryQuote,
                onDemoUsdc = viewModel::requestDemoUsdc,
            )
        }

        if (showForwardCard(state)) {
            Spacer(modifier = Modifier.height(16.dp))
            ForwardCard(state = state, onRetry = viewModel::retryForward)
        }

        // El cierre arrancó pero Horizon aún no refleja el pago del anchor: todavía no hay
        // card que mostrar (diría "Tienes 0 USDC…"), solo que la app sigue trabajando.
        if (state.settling && !showConvertCard(state) && !showForwardCard(state)) {
            Spacer(modifier = Modifier.height(16.dp))
            SpinnerRow(texto = "Preparando la conversión…", color = RaizPurple)
        }

        // Passkey: el cierre se detuvo con dinero todavía en la cuenta de depósito y sin otra
        // card que ofrezca la acción.
        if (needsFinishTap(state)) {
            Spacer(modifier = Modifier.height(16.dp))
            DepositInTransitCard(state = state, onFinish = viewModel::retryQuote)
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

        // El botón nunca se deshabilita: salir no pierde nada, lo pendiente se retoma después.
        if (state.settling) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Puedes salir: lo que falte se termina al volver a Depositar.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = RaizBlack.copy(alpha = 0.5f),
            )
        }
    }
}

private fun shortAddress(addr: String): String =
    if (addr.length <= 12) addr else "${addr.take(6)}…${addr.takeLast(6)}"

/** Inicio de un hash para los avisos de "aún no se confirma" ("abc123…"). */
private fun shortHash(hash: String): String = if (hash.length <= 6) hash else "${hash.take(6)}…"

private fun formatLimitLabel(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun statusHumanLabel(phase: DepositPhase, status: AnchorDepositStatus?): String = when {
    phase == DepositPhase.AWAITING_USER -> "Completa el depósito en la web del anchor"
    status?.state == AnchorDepositState.PENDING_TRUST -> "Falta la trustline: la app la crea ahora"
    else -> "El anchor está procesando…"
}
