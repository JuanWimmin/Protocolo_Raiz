package com.raiz.app.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hashes reales de transacción de las ejecuciones que disparó ESTA app
 * (camino feliz de D2): cuando el usuario pulsa "Ejecutar trustless", el
 * `sendTransaction` devuelve el hash y lo guardamos aquí por `proposal_id`.
 *
 * Así el dashboard sigue enlazando a Stellar Expert aunque el evento
 * `execution` ya haya salido de la ventana de retención del RPC (~7 días en
 * testnet). Es una caché local por dispositivo, no una fuente de verdad: el
 * evento del RPC siempre manda cuando está disponible.
 *
 * `proposal_id` es único a nivel de Governance (contador global), y una
 * propuesta solo puede ejecutarse una vez → clave suficiente.
 */
@Singleton
class ExecutionHashStore @Inject constructor(
    @ApplicationContext appContext: Context,
) {
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(proposalId: Long, txHash: String) {
        if (txHash.isBlank()) return
        prefs.edit().putString(key(proposalId), txHash).apply()
    }

    fun get(proposalId: Long): String? = prefs.getString(key(proposalId), null)

    /** Todos los hashes guardados, indexados por proposal_id. */
    fun all(): Map<Long, String> =
        prefs.all.mapNotNull { (k, v) ->
            val id = k.removePrefix(KEY_PREFIX).toLongOrNull() ?: return@mapNotNull null
            val hash = v as? String ?: return@mapNotNull null
            id to hash
        }.toMap()

    private fun key(proposalId: Long) = "$KEY_PREFIX$proposalId"

    private companion object {
        const val PREFS = "raiz_execution_hashes"
        const val KEY_PREFIX = "proposal_"
    }
}
