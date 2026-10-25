package com.raiz.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.raiz.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ¿Hay red? Fuente única para decidir el modo "Firmar sin señal" ANTES de intentar un pago en línea
 * (nunca como respaldo tras un pago fallido: podría cobrar dos veces).
 *
 * En cada evento del sistema se recalcula desde cero con `activeNetwork` + `NET_CAPABILITY_INTERNET`
 * (no se exige VALIDATED: en el emulador puede tardar o no marcarse) y se vuelve a comprobar 600 ms
 * después para absorber el salto Wi-Fi → datos. El emulador tiene dos radios: para quedarse sin señal hay
 * que apagar ambas (`adb shell cmd connectivity airplane-mode enable`).
 *
 * [forceOffline] solo existe en debug (switch de la pantalla de pago) para grabar la demo con red.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext appContext: Context,
) {
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _online = MutableStateFlow(compute())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _forceOffline = MutableStateFlow(false)
    val forceOffline: StateFlow<Boolean> = _forceOffline.asStateFlow()

    /** true cuando no hay red o se forzó en debug. */
    val offline: Flow<Boolean> = combine(_online, _forceOffline) { on, forced -> !on || forced }

    init {
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onUnavailable() = refresh()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
            })
        }
    }

    fun isOffline(): Boolean = !_online.value || _forceOffline.value

    fun isOnline(): Boolean = !isOffline()

    fun setForceOffline(value: Boolean) {
        if (BuildConfig.DEBUG) _forceOffline.value = value
    }

    /** Recalcula ahora y otra vez en 600 ms (los callbacks llegan en hilo de sistema y a veces desordenados). */
    fun refresh() {
        _online.value = compute()
        mainHandler.postDelayed({ _online.value = compute() }, RECHECK_MS)
    }

    private fun compute(): Boolean = runCatching {
        val cm = connectivity ?: return@runCatching false
        val active = cm.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    private companion object {
        const val RECHECK_MS = 600L
    }
}
