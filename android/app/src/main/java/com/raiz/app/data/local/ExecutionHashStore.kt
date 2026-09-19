package com.raiz.app.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext appContext: Context,
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

    private fun prefix(treasuryId: String) = "exec_${treasuryId}_"
    private fun key(treasuryId: String, proposalId: Long) = "${prefix(treasuryId)}$proposalId"

    private companion object {
        const val PREFS = "raiz_execution_hashes"
    }
}
