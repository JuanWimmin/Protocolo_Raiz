package com.raiz.app.data.stellar

import android.content.Context
import com.raiz.app.data.model.Deployments
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carga `deployments.json` desde assets/ y deserializa con kotlinx-serialization.
 *
 * El archivo se mantiene sincronizado con el del root del repo (script de deploy
 * lo regenera; copiar manualmente a android/app/src/main/assets/ tras cada deploy,
 * o automatizar con un Gradle task más adelante).
 */
@Singleton
class DeploymentsLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val cached: Deployments by lazy {
        context.assets.open("deployments.json").use { stream ->
            val text = stream.bufferedReader().readText()
            json.decodeFromString(Deployments.serializer(), text)
        }
    }

    fun load(): Deployments = cached
}
