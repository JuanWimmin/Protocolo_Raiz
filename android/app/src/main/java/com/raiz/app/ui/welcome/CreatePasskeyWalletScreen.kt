package com.raiz.app.ui.welcome

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.stellar.PasskeyWalletManager
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Estado de la pantalla de creación de smart wallet con passkey.
 *
 * @param loading     true mientras el SDK está procesando (WebAuthn + deploy).
 * @param createdWallet resultado exitoso — cuando no es null, la pantalla muestra
 *                     el C... del contrato y el botón "Continuar".
 * @param error       mensaje de error para el usuario, o null si no hay.
 */
data class CreatePasskeyUiState(
    val loading: Boolean = false,
    val createdWallet: WalletState? = null,
    val error: String? = null,
)

@HiltViewModel
class CreatePasskeyWalletViewModel @Inject constructor(
    private val passkeyManager: PasskeyWalletManager,
) : ViewModel() {

    private val _state = MutableStateFlow(CreatePasskeyUiState())
    val state: StateFlow<CreatePasskeyUiState> = _state.asStateFlow()

    /**
     * Inicia la creación de la smart wallet.
     *
     * @param activity  Activity activa para que el Credential Manager del SO pueda
     *                  mostrar el diálogo de passkey. Se obtiene desde la UI via
     *                  `LocalContext.current as? Activity`.
     * @param userName  Etiqueta visible para el usuario en el selector de passkey del SO.
     */
    fun createWallet(activity: Activity, userName: String = "RAIZ Turista") {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = passkeyManager.createSmartWallet(activity, userName)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Smart wallet passkey lista: ${r.data.publicKey}")
                    _state.update { it.copy(loading = false, createdWallet = r.data) }
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "createSmartWallet falló: ${r.code} — ${r.message}")
                    _state.update {
                        it.copy(loading = false, error = humanError(r.code, r.message))
                    }
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun humanError(code: RaizErrorCode, msg: String): String = when (code) {
        RaizErrorCode.NETWORK_ERROR -> "Error de red al desplegar el contrato. Revisa tu conexión."
        else -> msg
    }

    private companion object { const val TAG = "RAIZ" }
}

// ── Composable ─────────────────────────────────────────────────────────────────

/**
 * Pantalla que guía al usuario por el flujo de creación de smart wallet con passkey.
 *
 * Flujo:
 *   1. Explica qué es una passkey.
 *   2. Botón "Crear con passkey" → llama al ViewModel que invoca [PasskeyWalletManager].
 *   3. El SO muestra el diálogo de creación de passkey (biométrico o PIN).
 *   4. El SDK despliega el contrato en Soroban vía el relayer de Soneso.
 *   5. Si todo va bien, muestra la dirección C... y el botón "Continuar".
 *
 * IMPORTANTE: Solo se muestra si [com.raiz.app.BuildConfig.PASSKEY_RP_ID] no está
 * vacío Y el dispositivo tiene API >= 28 (Android 9). En builds sin `passkey.rp.id`
 * en local.properties, este screen no es accesible desde [WelcomeScreen].
 *
 * @param onBack        Navegación de regreso a WelcomeScreen.
 * @param onWalletReady Navegación tras crear la wallet exitosamente (a ChooseRoleScreen).
 */
@Composable
fun CreatePasskeyWalletScreen(
    onBack: () -> Unit,
    onWalletReady: () -> Unit,
) {
    val vm: CreatePasskeyWalletViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    // El Activity context es necesario para el Credential Manager. Se obtiene
    // del LocalContext (que en el contexto de setContent { } es siempre la Activity).
    val context = LocalContext.current

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Ícono
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(RaizPurple.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    tint = RaizPurple,
                    modifier = Modifier.size(40.dp),
                )
            }

            Text(
                text = "Wallet con passkey",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
                textAlign = TextAlign.Center,
            )

            // Estado: si la wallet ya fue creada, muestra la dirección
            if (state.createdWallet != null) {
                SuccessBlock(
                    contractId = state.createdWallet!!.publicKey,
                    onContinue = onWalletReady,
                )
            } else {
                // Explicación
                InfoBlock()

                // Error
                state.error?.let { ErrorMessage(it) }

                // Botón principal
                if (state.loading) {
                    CircularProgressIndicator(
                        color = RaizPurple,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "Registrando passkey y desplegando contrato…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RaizBlack.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Button(
                        onClick = {
                            // Intentamos obtener la Activity. Si por alguna razón el
                            // contexto no es una Activity (edge case), lo registramos
                            // y devolvemos un error amigable.
                            val activity = context as? Activity
                            if (activity != null) {
                                vm.createWallet(activity)
                            } else {
                                Log.e("RAIZ", "CreatePasskeyWallet: contexto no es Activity")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RaizPurple),
                    ) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = RaizWhite)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "Crear con passkey",
                            style = MaterialTheme.typography.labelLarge,
                            color = RaizWhite,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Enlace de regreso
                androidx.compose.material3.TextButton(onClick = onBack) {
                    Text("← Volver", color = RaizBlack.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun InfoBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizPurple.copy(alpha = 0.06f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = RaizPurple)
        Text(
            text = "Tu passkey se genera en el hardware de este dispositivo (huella, rostro o PIN). " +
                   "La clave privada nunca sale del chip de seguridad.\n\n" +
                   "A diferencia de la seed phrase de 12 palabras, no hay nada que anotar — " +
                   "pero si pierdes el dispositivo necesitarás recuperarla desde tu gestor de claves " +
                   "(Google Password Manager, iCloud Keychain, etc.).",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = RaizBlack.copy(alpha = 0.75f),
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Text(
                text = "Atención: este dispositivo tiene Android ${Build.VERSION.RELEASE} " +
                       "(API ${Build.VERSION.SDK_INT}). Se requiere Android 9 o superior.",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFFE53935),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SuccessBlock(contractId: String, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(RaizGreen.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Fingerprint,
                contentDescription = null,
                tint = RaizGreen,
                modifier = Modifier.size(32.dp),
            )
        }

        Text(
            text = "Wallet creada",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = RaizGreen,
        )

        Text(
            text = "Dirección del smart account:",
            style = MaterialTheme.typography.labelMedium,
            color = RaizBlack.copy(alpha = 0.6f),
        )

        // Dirección C... truncada para no desbordar
        val displayAddress = if (contractId.length > 20) {
            "${contractId.take(8)}…${contractId.takeLast(8)}"
        } else contractId

        Text(
            text = displayAddress,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = RaizBlack,
        )

        Text(
            text = "Esta es tu dirección de smart account en Stellar.\n" +
                   "Los pagos y recompensas RAÍZ llegarán aquí.",
            style = MaterialTheme.typography.bodySmall,
            color = RaizBlack.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RaizGreen),
        ) {
            Text("Continuar  →", style = MaterialTheme.typography.labelLarge, color = RaizWhite)
        }
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color(0xFFFFEBEE))
            .padding(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = androidx.compose.ui.graphics.Color(0xFFB71C1C),
        )
    }
}
