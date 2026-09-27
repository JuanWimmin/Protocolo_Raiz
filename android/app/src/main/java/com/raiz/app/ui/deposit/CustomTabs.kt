package com.raiz.app.ui.deposit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.toArgb
import com.raiz.app.ui.theme.RaizBlack

/**
 * Abre la URL interactiva SEP-24 del anchor de prueba en una Custom Tab
 * (Chrome/navegador del sistema embebido, sin salir de RAÍZ).
 *
 * La SPA del anchor (React "SEP-24 Reference UI") necesita cookies y JS
 * completo — un WebView propio complicaría el manejo de sesión sin ganar
 * nada; Custom Tabs reutiliza el navegador ya logueado del usuario y trae
 * back button / toolbar coherentes con la paleta RAÍZ.
 */
object CustomTabs {

    /**
     * Lanza [url] en una Custom Tab con la toolbar en [RaizBlack].
     * Si el dispositivo no tiene ningún navegador con soporte Custom Tabs
     * (raro, pero posible en emuladores mínimos), cae a un Intent VIEW normal;
     * si tampoco hay navegador instalado, se traga la excepción y loguea.
     *
     * Solo se abren URLs `https` (SEP-24 lo exige): `launchUrl` y `ACTION_VIEW` son
     * intents implícitos, así que otro esquema (`market:`, `tel:`, uno de otra app)
     * se despacharía a quien lo reclame con un valor que RAÍZ no controla, y
     * `file://` lanzaría `FileUriExposedException` (no es `ActivityNotFoundException`).
     * El ViewModel ya filtra antes de emitir; esto es la segunda línea.
     *
     * @return `false` si la URL se rechazó por esquema (no se abrió nada).
     */
    fun open(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            Log.w(TAG, "CustomTabs.open: esquema no permitido '${uri.scheme}'; no se abre")
            return false
        }
        val colorParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(RaizBlack.toArgb())
            .build()
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(colorParams)
            .build()
        try {
            intent.launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "CustomTabs.open: sin Custom Tabs disponibles, fallback a ACTION_VIEW — ${e.message}")
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }.onFailure {
                Log.w(TAG, "CustomTabs.open: no se pudo abrir ningún navegador — ${it.message}")
            }
        }
        return true
    }

    private const val TAG = "RAIZ"
}
