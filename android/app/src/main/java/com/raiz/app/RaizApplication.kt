package com.raiz.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class de RAÍZ.
 *
 * Cuando integremos Mapbox, aquí se inicializa el access token público:
 *   MapboxOptions.accessToken = getString(R.string.mapbox_access_token)
 *
 * Cuando integremos kmp-stellar-sdk, aquí se configuran las URLs de Horizon
 * y Soroban RPC para la red activa (leídas desde BuildConfig o deployments.json).
 */
@HiltAndroidApp
class RaizApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO: MapboxOptions.accessToken = getString(R.string.mapbox_access_token)
        // TODO: inicializar SorobanClient con deployments.json
    }
}
