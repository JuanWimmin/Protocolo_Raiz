package com.raiz.app.data.deferred

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sobres RECIBIDOS de otros teléfonos, en SharedPreferences (`raiz_received_sobres`) con kotlinx.serialization, igual
 * que [DeferredStore] hace con los emitidos. El archivo es del dispositivo; las vistas por wallet salen de
 * [recordsFor]. También guarda el tope por pago sin señal de cada wallet (`tope_<owner>`).
 *
 * Toda transición parte del registro VIVO ([mutate]) bajo el mismo candado: el sincronizador, el envío automático y
 * las pantallas no se pisan entre sí (D5). El `id` (`<payer>:<nonce>`) es único en todo el teléfono.
 */
@Singleton
class ReceivedSobreStore @Inject constructor(
    @ApplicationContext appContext: Context,
) {
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _records = MutableStateFlow(ReceivedSobreCodec.decode(prefs.getString(KEY_RECORDS, null)))
    val recordsFlow: StateFlow<List<ReceivedSobreRecord>> = _records.asStateFlow()

    fun all(): List<ReceivedSobreRecord> = _records.value

    /** Sobres recibidos por una wallet concreta; vacío si no hay wallet. */
    fun recordsFor(owner: String?): List<ReceivedSobreRecord> =
        if (owner == null) emptyList() else all().filter { it.owner == owner }

    fun get(id: String): ReceivedSobreRecord? = all().firstOrNull { it.id == id }

    /** Índice `(owner, payer, nonce)`: ¿esta wallet ya recibió un sobre de ese pagador con ese nonce? */
    fun seen(owner: String, payer: String, nonce: Long): ReceivedSobreRecord? =
        all().firstOrNull { it.owner == owner && it.payer == payer && it.nonce == nonce }

    fun pendingCount(owner: String?): Int = recordsFor(owner).count { it.pending }

    /**
     * Guarda un sobre NUEVO con `commit()` y relee antes de darlo por recibido: si devuelve false, el sobre NO
     * debe darse por recibido (uno que viaja sin quedar registrado en el teléfono no se podría cerrar nunca).
     *
     * Nunca pisa un registro existente: si el `id` ya está, devuelve `true` solo cuando es el MISMO sobre de la
     * MISMA wallet (idempotente: el guardado puede llevar un diario de envío abierto que un registro nuevo borraría)
     * y `false` si lo tiene otra wallet de este teléfono (sobreescribirlo le quitaría su sobre).
     */
    @Synchronized
    fun saveDurable(record: ReceivedSobreRecord): Boolean {
        val current = _records.value
        current.firstOrNull { it.id == record.id }?.let { existing ->
            return existing.owner == record.owner && existing.authXdr == record.authXdr
        }
        val next = current + record
        val ok = prefs.edit().putString(KEY_RECORDS, ReceivedSobreCodec.encode(next)).commit()
        if (!ok) return false
        val back = ReceivedSobreCodec.decode(prefs.getString(KEY_RECORDS, null))
        if (back.none { it.id == record.id }) return false
        _records.value = back
        return true
    }

    /**
     * Lee–modifica–escribe UN registro bajo el mismo candado y lo guarda con `commit()`. [f] recibe el registro VIVO
     * en este instante (nunca una copia capturada antes de un envío o una consulta). [f] no debe cambiar el `id` ni
     * el `owner`.
     *
     * Devuelve el registro resultante (el mismo si [f] no cambió nada), o `null` si el sobre no existe, [f] cambió su
     * identidad o el disco falló; en este último caso la memoria NO cambia (sigue igual que el disco).
     */
    @Synchronized
    fun mutate(id: String, f: (ReceivedSobreRecord) -> ReceivedSobreRecord): ReceivedSobreRecord? {
        val current = _records.value
        val old = current.firstOrNull { it.id == id } ?: return null
        val updated = f(old)
        if (updated == old) return old
        if (updated.id != id || updated.owner != old.owner) return null
        val next = current.map { if (it.id == id) updated else it }
        val ok = prefs.edit().putString(KEY_RECORDS, ReceivedSobreCodec.encode(next)).commit()
        if (!ok) return null
        _records.value = next
        return updated
    }

    /**
     * Aplica [f] a TODA la lista bajo el candado (p. ej. el vencimiento local por estimación). Escritura asíncrona
     * (`apply()`): quien la llama puede estar en el hilo principal y el resultado se recalcula solo.
     */
    @Synchronized
    fun refreshAll(f: (List<ReceivedSobreRecord>) -> List<ReceivedSobreRecord>) {
        val current = _records.value
        val next = f(current)
        if (next == current) return
        prefs.edit().putString(KEY_RECORDS, ReceivedSobreCodec.encode(next)).apply()
        _records.value = next
    }

    // ── Tope por pago sin señal (por wallet) ──────────────────────────────

    /** Monto máximo (stroops) que [owner] acepta cobrar en un solo sobre sin señal; por omisión 20 USDC. */
    fun topeStroops(owner: String): Long =
        prefs.getLong(KEY_TOPE_PREFIX + owner, ReceivedSobreVerifier.TOPE_DEFAULT_STROOPS)

    /** Guarda el tope de [owner]. 0 = no aceptar cobros sin señal; un valor negativo se toma como 0. */
    fun setTopeStroops(owner: String, stroops: Long) {
        prefs.edit().putLong(KEY_TOPE_PREFIX + owner, stroops.coerceAtLeast(0L)).commit()
    }

    private companion object {
        const val PREFS = "raiz_received_sobres"
        const val KEY_RECORDS = "records_v1"
        const val KEY_TOPE_PREFIX = "tope_"
    }
}
