package com.raiz.app.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caché local de hashes reales de transacción de las ejecuciones del Treasury
 * (D2 del SOW). Se alimenta de dos fuentes:
 *   1. el camino feliz: cuando ESTA app dispara `execute_proposal`, el hash se
 *      conoce al firmar/enviar;
 *   2. los eventos `execution` leídos del RPC mientras siguen en su ventana de
 *      retención (~7 días en testnet).
 *
 * Así el dashboard sigue enlazando a Stellar Expert cuando el evento ya salió de
 * la ventana. Es una caché por dispositivo, no una fuente de verdad.
 *
 * La clave incluye el **contrato Treasury**: `proposal_id` es un contador que
 * vuelve a empezar en cada redeploy, y sin el contrato en la clave un hash viejo
 * se mostraría como transacción verificada de una ejecución distinta.
 */
@Singleton
class ExecutionHashStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(treasuryId: String, proposalId: Long, txHash: String) {
        if (txHash.isBlank()) return
        prefs.edit().putString(key(treasuryId, proposalId), txHash).apply()
    }

    /** Hashes guardados para ese contrato Treasury, indexados por proposal_id. */
    fun all(treasuryId: String): Map<Long, String> {
        val prefix = prefix(treasuryId)
        return prefs.all.mapNotNull { (k, v) ->
            if (!k.startsWith(prefix)) return@mapNotNull null
            val id = k.removePrefix(prefix).toLongOrNull() ?: return@mapNotNull null
            val hash = v as? String ?: return@mapNotNull null
            id to hash
        }.toMap()
    }

    /**
     * Archivo versionado con el repo (`assets/execution_hashes.json`): hash real de
     * `execute_proposal` por contrato Treasury y proposal_id, cada uno verificado en
     * Horizon (tx exitosa, función y argumento correctos). Es el RESPALDO para las
     * ejecuciones cuyo evento ya salió de la ventana del RPC y que este dispositivo
     * no capturó: sin él, "cada ejecución enlaza a su transacción real" solo sería
     * cierto durante 7 días. El evento del RPC y la caché local tienen prioridad, y
     * la UI lo etiqueta como archivo — no se hace pasar por un evento en vivo.
     */
    fun archive(treasuryId: String): Map<Long, String> = archiveByTreasury[treasuryId].orEmpty()

    private val archiveByTreasury: Map<String, Map<Long, String>> by lazy {
        runCatching {
            val text = appContext.assets.open(ARCHIVE_ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            root.keys().asSequence()
                .filter { !it.startsWith("_") }
                .associateWith { treasury ->
                    val byProposal = root.getJSONObject(treasury)
                    byProposal.keys().asSequence().mapNotNull { id ->
                        val hash = byProposal.optString(id).takeIf { h -> h.length == 64 }
                        val pid = id.toLongOrNull()
                        if (hash == null || pid == null) null else pid to hash
                    }.toMap()
                }
        }.getOrElse { emptyMap() }
    }

    private fun prefix(treasuryId: String) = "exec_${treasuryId}_"
    private fun key(treasuryId: String, proposalId: Long) = "${prefix(treasuryId)}$proposalId"

    private companion object {
        const val PREFS = "raiz_execution_hashes"
        const val ARCHIVE_ASSET = "execution_hashes.json"
    }
}
