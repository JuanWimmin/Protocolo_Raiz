package com.raiz.app.ui.governance

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.Refresh
import android.app.Activity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.data.model.Proposal
import com.raiz.app.data.model.UserRole
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.ui.components.RaizBottomNav
import com.raiz.app.ui.components.RaizDestination
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Pantalla exclusiva del rol RESIDENT — propuestas activas del barrio.
 *
 * Muestra:
 *   - Lista de propuestas con estado de quórum (X/Y votos mínimos alcanzados).
 *   - Botones "A favor" / "En contra" por propuesta.
 *   - FAB "+" para crear una nueva propuesta.
 *   - Estado especial si el usuario no está registrado on-chain como residente.
 *
 * Pull-to-refresh para refrescar contra el contrato.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalsScreen(
    onNavigateHome: () -> Unit = {},
    onNavigateMap: () -> Unit = {},
    onNavigateProfile: () -> Unit = {},
    onCreateProposal: () -> Unit = {},
    currentRole: UserRole = UserRole.RESIDENT,
    viewModel: ProposalsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedNav by remember { mutableStateOf(RaizDestination.Proposals) }
    // El Activity es necesario para que el Credential Manager muestre el selector
    // de passkey (WebAuthn) al votar. Se ignora en la rama clásica/demo.
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Propuestas",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = RaizBlack,
                        )
                        if (state.barrioName.isNotEmpty()) {
                            Text(
                                text = state.barrioName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = RaizBlack.copy(alpha = 0.6f),
                            )
                        }
                    }
                },
                actions = {
                    if (state.isRegisteredOnChain == true) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Actualizar propuestas",
                                tint = RaizBlack,
                            )
                        }
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
        floatingActionButton = {
            // FAB "+" solo visible si el usuario está registrado on-chain.
            if (state.isRegisteredOnChain == true) {
                FloatingActionButton(
                    onClick = onCreateProposal,
                    containerColor = RaizPurple,
                    contentColor = RaizWhite,
                    shape = CircleShape,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Crear propuesta")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            // Cargando la verificación de registro.
            state.isRegisteredOnChain == null -> LoadingBox(padding)

            // No registrado on-chain como residente.
            state.isRegisteredOnChain == false -> PendingRegistrationBox(
                padding = padding,
                state = state,
                onVerify = viewModel::verifyAsResident,
            )

            // Registrado — muestra propuestas.
            else -> ProposalsList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = state,
                // Pasamos el Activity al ViewModel para que la rama passkey pueda
                // invocar voteWithPasskey(activity, ...). En demo/clásico se ignora.
                onVote = { proposalId, support ->
                    viewModel.vote(proposalId, support, context as? Activity)
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Lista de propuestas
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProposalsList(
    state: ProposalsUiState,
    onVote: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> LoadingBox(PaddingValues(0.dp))
        state.error != null -> ErrorBox(state.error)
        state.proposals.isEmpty() -> EmptyBox()
        else -> LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            items(state.proposals, key = { it.id }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    residentCount = state.residentCount,
                    voteStatus = state.voteState[proposal.id],
                    onVote = { support -> onVote(proposal.id, support) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Card de propuesta — con quórum
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProposalCard(
    proposal: Proposal,
    residentCount: Int,
    voteStatus: VoteStatus?,
    onVote: (Boolean) -> Unit,
) {
    val quorumReached = residentCount > 0 && proposal.reachedQuorum(residentCount)
    // Mínimo de votos necesarios para alcanzar quórum (30%).
    val quorumTarget = if (residentCount > 0) (residentCount * 30 + 99) / 100 else 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RaizWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Descripción
        Text(
            text = proposal.description,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = RaizBlack,
        )

        // Monto solicitado
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Solicita ${proposal.amountStroops.formatUsdc()}",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
            Text(
                text = "${proposal.votesFor}↑ · ${proposal.votesAgainst}↓",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
        }

        // Barra de votos a favor
        LinearProgressIndicator(
            progress = {
                if (proposal.totalVotes == 0) 0f
                else proposal.votesFor.toFloat() / proposal.totalVotes
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = RaizGreen,
            trackColor = RaizBlack.copy(alpha = 0.08f),
        )

        // Estado del quórum
        QuorumRow(
            totalVotes = proposal.totalVotes,
            quorumTarget = quorumTarget,
            quorumReached = quorumReached,
        )

        // Estado del voto en curso / completado / con error
        VoteStatusRow(voteStatus = voteStatus)

        // Botones de voto
        val submitting = voteStatus is VoteStatus.Submitting
        val voted = voteStatus is VoteStatus.Ok
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onVote(true) },
                enabled = !submitting && !voted,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizGreen,
                    contentColor = RaizWhite,
                    disabledContainerColor = RaizGreen.copy(alpha = 0.4f),
                    disabledContentColor = RaizWhite.copy(alpha = 0.7f),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (!voted) {
                    Icon(Icons.Outlined.ThumbUp, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                }
                Text(if (voted) "Ya votaste" else "A favor", style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = { onVote(false) },
                enabled = !submitting && !voted,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RaizBlack,
                    contentColor = RaizWhite,
                    disabledContainerColor = RaizBlack.copy(alpha = 0.4f),
                    disabledContentColor = RaizWhite.copy(alpha = 0.7f),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (!voted) {
                    Icon(Icons.Outlined.ThumbDown, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                }
                Text(if (voted) "Ya votaste" else "En contra", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun QuorumRow(totalVotes: Int, quorumTarget: Int, quorumReached: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val (icon, tint, text) = if (quorumReached) {
            Triple(Icons.Outlined.CheckCircle, RaizGreen, "Quórum alcanzado ($totalVotes/$quorumTarget votos)")
        } else {
            Triple(
                Icons.Outlined.Info,
                RaizBlack.copy(alpha = 0.4f),
                if (quorumTarget > 0) "Quórum: $totalVotes/$quorumTarget votos (30%)"
                else "Quórum: $totalVotes votos",
            )
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (quorumReached) RaizGreen else RaizBlack.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun VoteStatusRow(voteStatus: VoteStatus?) {
    when (voteStatus) {
        VoteStatus.Submitting -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                color = RaizGreen,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Enviando voto a la red…",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )
        }
        VoteStatus.Ok -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = RaizGreen, modifier = Modifier.size(16.dp))
            Text(
                text = "Tu voto quedó on-chain",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizGreen,
            )
        }
        is VoteStatus.Failed -> Text(
            text = voteStatus.message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB00020),
        )
        null -> Unit
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Estados especiales
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingBox(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = RaizGreen)
    }
}

@Composable
private fun EmptyBox() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.HowToVote,
                contentDescription = null,
                tint = RaizPurple.copy(alpha = 0.4f),
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = "No hay propuestas activas en tu barrio.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.6f),
            )
            Text(
                text = "Usa el botón + para crear la primera.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.4f),
            )
        }
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
            text = "No se pudieron cargar las propuestas.\n$message",
            style = MaterialTheme.typography.bodyMedium,
            color = RaizBlack.copy(alpha = 0.7f),
        )
    }
}

/**
 * Banner cuando el usuario no está registrado on-chain como residente.
 *
 * En modo demo el admin firma por el usuario: muestra un botón "Verificar como
 * residente de [barrio]" que mintea el ResidentToken soulbound al instante
 * (mismo patrón que el alta de comerciante). Si no hay barrio elegido, guía al
 * usuario a entrar de nuevo como residente para elegirlo.
 */
@Composable
private fun PendingRegistrationBox(
    padding: PaddingValues,
    state: ProposalsUiState,
    onVerify: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(RaizYellow.copy(alpha = 0.15f))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = RaizBlack.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = "Aún no eres residente verificado",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = RaizBlack,
            )
            Text(
                text = "Para proponer y votar necesitas un token de residencia on-chain (soulbound). En esta demo el admin lo aprueba al instante.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )

            // Error de la última verificación, si lo hubo.
            if (state.verifyError != null) {
                Text(
                    text = state.verifyError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB00020),
                )
            }

            Spacer(modifier = Modifier.size(4.dp))

            if (state.pendingBarrioName != null) {
                // Botón de verificación: el admin demo mintea el soulbound.
                Button(
                    onClick = onVerify,
                    enabled = !state.verifying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RaizPurple,
                        contentColor = RaizWhite,
                        disabledContainerColor = RaizPurple.copy(alpha = 0.4f),
                        disabledContentColor = RaizWhite.copy(alpha = 0.7f),
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (state.verifying) {
                        CircularProgressIndicator(
                            color = RaizWhite,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(Icons.Outlined.HowToVote, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            "Verificar como residente de ${state.pendingBarrioName}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            } else {
                // Sin barrio elegido: no podemos mintear. Guía al usuario.
                Text(
                    text = "Aún no has elegido tu barrio. Vuelve a entrar como residente desde tu perfil para elegirlo y poder verificarte.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack.copy(alpha = 0.7f),
                )
            }
        }
    }
}
