package com.raiz.app.ui.welcome

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow

/**
 * Genera 12 palabras BIP-39 y las muestra al usuario para que las copie en
 * un lugar seguro. Hasta que confirme con el checkbox "Las guardé" no se
 * habilita el botón continuar.
 *
 * Tras confirmar, se persiste con SecureWalletStore y se navega a Wallet.
 */
@Composable
fun CreateWalletScreen(
    onBack: () -> Unit,
    onWalletReady: () -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var confirmed by remember { mutableStateOf(false) }

    // Generar la seed la primera vez que se entra.
    LaunchedEffect(Unit) { viewModel.generateNewSeed() }

    // Cuando la wallet queda lista, navegar.
    LaunchedEffect(state.walletReady) {
        if (state.walletReady) onWalletReady()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás", tint = RaizBlack)
                }
                Text(
                    text = "Tu frase de respaldo",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = RaizBlack,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.generateNewSeed() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Regenerar", tint = RaizBlack)
                }
            }

            Text(
                text = "Escribe estas 12 palabras en orden y guárdalas en un lugar seguro. Son la única forma de recuperar tu wallet.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.7f),
            )

            // Grid de 12 palabras
            when {
                state.generating -> Box(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = RaizGreen) }

                state.generatedWords.isNotEmpty() -> WordGrid(words = state.generatedWords)

                state.error != null -> Text(
                    text = state.error ?: "",
                    color = Color(0xFFB00020),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Confirmación
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(RaizYellow.copy(alpha = 0.15f))
                    .padding(12.dp),
            ) {
                Checkbox(
                    checked = confirmed,
                    onCheckedChange = { confirmed = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = RaizGreen,
                        uncheckedColor = RaizBlack.copy(alpha = 0.5f),
                    ),
                )
                Text(
                    text = "Ya las guardé en un lugar seguro",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RaizBlack,
                )
            }

            // CTA
            Button(
                onClick = { viewModel.confirmCreate() },
                enabled = confirmed && state.generatedWords.size == 12 && !state.saving,
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
                if (state.saving) {
                    CircularProgressIndicator(color = RaizWhite, strokeWidth = 2.dp)
                } else {
                    Text("Continuar", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun WordGrid(words: List<String>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().height(360.dp),
    ) {
        items(items = words.withIndex().toList(), key = { it.index }) { (idx, word) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(RaizWhite)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${idx + 1}.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = RaizBlack.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text(
                    text = word,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    color = RaizBlack,
                )
            }
        }
    }
}
