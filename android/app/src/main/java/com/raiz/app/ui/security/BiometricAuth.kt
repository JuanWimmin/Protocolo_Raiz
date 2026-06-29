package com.raiz.app.ui.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Pide autenticación biométrica/PIN del dispositivo antes de una acción
 * sensible (p. ej. confirmar un pago / firmar una tx).
 *
 * Usa el subsistema del SO (`BiometricPrompt` con huella/rostro o credencial
 * del dispositivo). Si no hay `FragmentActivity` o el dispositivo no puede
 * autenticar, ejecuta `onSuccess` directo para no bloquear el flujo (el
 * llamador decide CUÁNDO exigirlo, normalmente solo si el lock está activo).
 */
fun biometricConfirm(
    activity: FragmentActivity?,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
) {
    if (activity == null) {
        onSuccess()
        return
    }
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            // En error/cancelación no se ejecuta onSuccess: la acción no procede.
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build()
    prompt.authenticate(info)
}
