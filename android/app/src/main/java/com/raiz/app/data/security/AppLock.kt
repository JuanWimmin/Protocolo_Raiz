package com.raiz.app.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bloqueo de la app con **biométrico + credencial del dispositivo** (PIN/patrón).
 *
 * Usa el subsistema del SO vía `BiometricPrompt` (la UI vive en MainActivity,
 * que necesita un FragmentActivity). Aquí solo guardamos la preferencia y
 * exponemos si el dispositivo puede autenticar. NO guardamos un PIN propio:
 * el factor de respaldo es el del sistema (lo más seguro y lo que pide
 * "ambos por capas").
 *
 * Es **opt-in** (default OFF) para no estorbar el demo ni la automatización;
 * en un build de producción debería venir ON. El toggle vive en Perfil.
 */
@Singleton
class AppLock @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** ¿El dispositivo puede autenticar (huella/rostro o PIN/patrón/contraseña)? */
    fun canAuthenticate(): Boolean =
        BiometricManager.from(appContext)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** ¿El usuario activó el bloqueo? */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** El bloqueo aplica solo si está activado Y el dispositivo puede autenticar. */
    fun isActive(): Boolean = enabled && canAuthenticate()

    private companion object {
        const val PREFS = "raiz_app_lock"
        const val KEY_ENABLED = "enabled"
    }
}
