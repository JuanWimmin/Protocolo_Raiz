package com.raiz.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Application class de RAÍZ.
 *
 * Instala Conscrypt como primer provider TLS antes de que cualquier otra cosa
 * abra un socket HTTPS. Sin esto, el Stellar SDK (Ktor + CIO + BouncyCastle
 * JSSE) falla con "Trust anchor for certification path not found" porque no
 * resuelve la cadena de certificados del sistema Android.
 *
 * Cuando integremos Mapbox, aquí se inicializa el access token público:
 *   MapboxOptions.accessToken = getString(R.string.mapbox_access_token)
 */
@HiltAndroidApp
class RaizApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installConscrypt()
        // TODO: MapboxOptions.accessToken = getString(R.string.mapbox_access_token)
    }

    private fun installConscrypt() {
        runCatching {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            Log.i("RAIZ", "Conscrypt instalado como provider TLS #1")
        }.onFailure {
            Log.w("RAIZ", "Conscrypt no se pudo instalar: ${it.message}")
        }
    }
}
