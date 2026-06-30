package com.raiz.app.ui.welcome

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.stellar.PasskeyWalletManager
import com.raiz.app.ui.theme.RaizBlack
import com.raiz.app.ui.theme.RaizError
import com.raiz.app.ui.theme.RaizGreen
import com.raiz.app.ui.theme.RaizPurple
import com.raiz.app.ui.theme.RaizWhite
import com.raiz.app.ui.theme.RaizYellow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Stub compilable para signInWithPasskey ────────────────────────────────────
//
// Esta extension provee la firma esperada para que el archivo compile SIN que
// PasskeyWalletManager tenga aún el método miembro real.
//
// Regla Kotlin: los métodos MIEMBRO tienen prioridad sobre extensiones con la
// misma firma. En cuanto el agente kmp-stellar-integration añada
//   suspend fun PasskeyWalletManager.signInWithPasskey(activity: Activity): RaizResult<WalletState>
// como función MIEMBRO de la clase, esta extensión queda ignorada
// automáticamente — sin necesidad de cambiar este archivo.
private suspend fun PasskeyWalletManager.signInWithPasskey(
    activity: Activity,
): RaizResult<WalletState> =
    // Stub temporal: devuelve un error descriptivo hasta que la implementación real llegue.
    RaizResult.Error(
        code = RaizErrorCode.UNKNOWN,
        message = "Sign-in con passkey en desarrollo. " +
                  "Usa «Con frase semilla» por ahora o espera la actualización.",
    )

// ── Estado UI ─────────────────────────────────────────────────────────────────

/**
 * Estado de la pantalla de inicio de sesión.
 *
 * @param loading        true mientras se autentica la passkey (bloquea interacción).
 * @param success        true cuando el sign-in fue exitoso → la pantalla navega a la app.
 * @param error          Mensaje de error para el usuario; null si no hay error activo.
 * @param noAccountFound true cuando el indexer de Soneso no encontró smart account
 *                       para la passkey presentada (código NOT_FOUND). Se muestra
 *                       una sección con CTA "Crear cuenta" en lugar del banner de error.
 */
data class SignInUiState(
    val loading: Boolean = false,
    val success: Boolean = false,
    val error: String? = null,
    val noAccountFound: Boolean = false,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * ViewModel para el flujo de inicio de sesión con passkey.
 *
 * Para el caso "importar frase semilla", la navegación la maneja directamente
 * la UI (no hay estado en el VM) — la pantalla [ImportWalletScreen] con su
 * propio [WelcomeViewModel] se encarga de esa lógica.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val passkeyManager: PasskeyWalletManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    /**
     * Inicia el flujo de autenticación con passkey.
     *
     * Llama a [PasskeyWalletManager.signInWithPasskey] (extensión compilable hasta
     * que el agente kmp-stellar-integration añada el método miembro real).
     *
     * @param activity Activity activa — requerida por el Credential Manager del SO.
     */
    fun signIn(activity: Activity) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null, noAccountFound = false) }
        viewModelScope.launch {
            // La extensión-stub queda ignorada automáticamente ahora que
            // PasskeyWalletManager.signInWithPasskey(Activity) es un método MIEMBRO real.
            when (val r = passkeyManager.signInWithPasskey(activity)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Sign-in passkey exitoso: ${r.data.publicKey}")
                    _state.update { it.copy(loading = false, success = true) }
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "signIn falló: ${r.code} — ${r.message}")
                    // NOT_FOUND significa que el indexer de Soneso no encontró un smart
                    // account para esta passkey → mostrar CTA "Crear cuenta" en lugar
                    // del banner de error genérico.
                    if (r.code == RaizErrorCode.NOT_FOUND) {
                        _state.update { it.copy(loading = false, noAccountFound = true) }
                    } else {
                        _state.update {
                            it.copy(loading = false, error = mensajeError(r.code, r.message))
                        }
                    }
                }
            }
        }
    }

    /** Descarta el error mostrado al usuario. */
    fun dismissError() = _state.update { it.copy(error = null) }

    /** Descarta el banner "no encontramos cuenta" (el usuario decidió cancelar o reintentar). */
    fun dismissNoAccount() = _state.update { it.copy(noAccountFound = false) }

    private fun mensajeError(code: RaizErrorCode, msg: String): String = when (code) {
        RaizErrorCode.NETWORK_ERROR -> "Error de red. Revisa tu conexión e inténtalo de nuevo."
        RaizErrorCode.NOT_FOUND ->
            "No encontramos una cuenta con esta passkey."
        else -> msg
    }

    private companion object { const val TAG = "RAIZ" }
}

// ── Composable principal ───────────────────────────────────────────────────────

/**
 * Pantalla de inicio de sesión para usuarios que YA tienen una cuenta RAÍZ.
 *
 * Dos opciones:
 *   1. **Con passkey** (solo si [passkeyEnabled]): autentica la passkey guardada en
 *      el dispositivo y conecta al smart account C... en Stellar.
 *   2. **Con frase semilla**: navega a [ImportWalletScreen] para recuperar la cuenta
 *      desde las 12 palabras BIP-39.
 *
 * Flujo passkey:
 *   - Tap "Con passkey" → [SignInViewModel.signIn] → sistema operativo muestra
 *     el selector de passkeys → éxito → [onWalletReady] → app principal.
 *   - Error → mensaje inline; el usuario puede reintentar o elegir semilla.
 *   - Passkey no registrada en RAÍZ (NOT_FOUND) → [NoAccountBanner] con botón
 *     "Crear cuenta" que llama [onNoAccount].
 *
 * @param passkeyEnabled  true si passkey está disponible en este dispositivo/build.
 * @param onBack          Vuelve a [WelcomeScreen].
 * @param onWalletReady   Llamado cuando el sign-in con passkey tiene éxito.
 *                        MainActivity resuelve el rol on-chain y navega a WALLET.
 * @param onImportSeed    Navega a [ImportWalletScreen] (variante login).
 * @param onNoAccount     Llamado cuando el indexer no encontró cuenta para la passkey.
 *                        MainActivity navega al flujo de REGISTRO (cuenta nueva).
 */
@Composable
fun IniciarSesionScreen(
    passkeyEnabled: Boolean,
    onBack: () -> Unit,
    onWalletReady: () -> Unit,
    onImportSeed: () -> Unit,
    onNoAccount: () -> Unit = {},
) {
    val vm: SignInViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Cuando el sign-in tiene éxito, navega hacia la app.
    LaunchedEffect(state.success) {
        if (state.success) onWalletReady()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Barra superior con botón atrás
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !state.loading) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowBack,
                        contentDescription = "Volver",
                        tint = RaizBlack.copy(alpha = if (state.loading) 0.4f else 1f),
                    )
                }
                Text(
                    text = "Entra a tu cuenta",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = RaizBlack,
                )
            }

            Spacer(modifier = Modifier.size(8.dp))

            Text(
                text = "Usa el mismo método con el que creaste tu wallet.",
                style = MaterialTheme.typography.bodyMedium,
                color = RaizBlack.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.size(24.dp))

            // Opción passkey — solo si está disponible en el dispositivo
            if (passkeyEnabled) {
                SignInCard(
                    icon = Icons.Outlined.Fingerprint,
                    title = "Con passkey",
                    subtitle = if (state.loading) "Autenticando con el sistema operativo…"
                               else "Usa tu huella, rostro o PIN para reconectar tu smart account.",
                    accent = RaizPurple,
                    loading = state.loading,
                    enabled = !state.loading,
                    onClick = {
                        val activity = context as? Activity
                        if (activity != null) {
                            vm.signIn(activity)
                        } else {
                            Log.e("RAIZ", "IniciarSesionScreen: contexto no es Activity")
                        }
                    },
                )

                // Mensaje de error inline (red caída, cancelación, etc.)
                if (state.error != null) {
                    Spacer(modifier = Modifier.size(8.dp))
                    ErrorBanner(
                        message = state.error!!,
                        onDismiss = { vm.dismissError() },
                    )
                }

                // Banner especial: passkey presentada pero no registrada en RAÍZ.
                // Muestra un CTA claro para que el usuario cree su cuenta.
                if (state.noAccountFound) {
                    Spacer(modifier = Modifier.size(8.dp))
                    NoAccountBanner(
                        onCreateAccount = {
                            vm.dismissNoAccount()
                            onNoAccount()
                        },
                        onDismiss = { vm.dismissNoAccount() },
                    )
                }

                Spacer(modifier = Modifier.size(12.dp))
            }

            // Opción frase semilla — siempre disponible (navega fuera de esta pantalla)
            SignInCard(
                icon = Icons.Outlined.VpnKey,
                title = "Con frase semilla",
                subtitle = "Escribe tus 12 palabras BIP-39 para recuperar el acceso a tu cuenta.",
                accent = RaizGreen,
                loading = false,
                enabled = !state.loading,   // deshabilitado mientras passkey está en progreso
                onClick = onImportSeed,
            )

            Spacer(modifier = Modifier.weight(1f))

            Spacer(modifier = Modifier.size(24.dp))

            Text(
                text = "¿Aún no tienes cuenta? Vuelve atrás y elige «Crear cuenta».",
                style = MaterialTheme.typography.bodySmall,
                color = RaizBlack.copy(alpha = 0.45f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// ── Subcomponentes privados ────────────────────────────────────────────────────

/**
 * Tarjeta de opción de inicio de sesión.
 *
 * Cuando [loading] es true, el ícono se reemplaza por un [CircularProgressIndicator]
 * y la tarjeta no responde a tap.
 *
 * @param icon      Ícono del método de autenticación.
 * @param title     Título de la opción.
 * @param subtitle  Descripción o estado actual.
 * @param accent    Color de acento (púrpura para passkey, verde para semilla).
 * @param loading   true mientras se procesa el sign-in passkey.
 * @param enabled   false mientras otra opción está en proceso.
 * @param onClick   Acción al pulsar (ignorada si [loading] o !enabled).
 */
@Composable
private fun SignInCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bgAlpha = if (enabled) 1f else 0.55f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RaizWhite.copy(alpha = bgAlpha))
            .then(
                if (enabled && !loading) {
                    Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
                } else {
                    Modifier
                },
            )
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ícono o spinner según el estado
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (enabled) 0.14f else 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = accent,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent.copy(alpha = if (enabled) 1f else 0.5f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Spacer(modifier = Modifier.size(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = RaizBlack.copy(alpha = if (enabled) 1f else 0.5f),
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = RaizBlack.copy(alpha = if (enabled) 0.6f else 0.35f),
            )
        }
    }
}

/**
 * Banner de error inline que el usuario puede descartar al tocarlo.
 *
 * @param message   Texto del error a mostrar.
 * @param onDismiss Llamado cuando el usuario toca el banner para cerrarlo.
 */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RaizError.copy(alpha = 0.1f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
            .padding(12.dp),
    ) {
        Text(
            text = "$message  (toca para cerrar)",
            style = MaterialTheme.typography.bodySmall,
            color = RaizError,
        )
    }
}

/**
 * Banner que aparece cuando el indexer de Soneso no encontró un smart account
 * para la passkey presentada (código NOT_FOUND).
 *
 * Distingue claramente "passkey existe en el dispositivo pero no en RAÍZ" de
 * un error de red o cancelación, para guiar al usuario a crear una cuenta nueva
 * en lugar de reintentar indefinidamente.
 *
 * @param onCreateAccount Llamado al pulsar "Crear cuenta" → flujo de registro.
 * @param onDismiss       Llamado al tocar el área de "cerrar" → descarta el banner.
 */
@Composable
private fun NoAccountBanner(
    onCreateAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RaizYellow.copy(alpha = 0.12f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "No encontramos una cuenta con esta passkey",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = RaizBlack,
        )
        Text(
            text = "Puede que hayas creado la cuenta en otro dispositivo, " +
                "o que esta passkey no este registrada en RAIZ aun.",
            style = MaterialTheme.typography.bodySmall,
            color = RaizBlack.copy(alpha = 0.7f),
        )

        // Botón "Crear cuenta" — navega al flujo de REGISTRO
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(RaizYellow)
                .pointerInput(Unit) { detectTapGestures(onTap = { onCreateAccount() }) }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Crear cuenta",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = RaizBlack,
            )
        }

        // Enlace secundario para descartar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Reintentar con otra passkey",
                style = MaterialTheme.typography.bodySmall,
                color = RaizBlack.copy(alpha = 0.5f),
            )
        }
    }
}
