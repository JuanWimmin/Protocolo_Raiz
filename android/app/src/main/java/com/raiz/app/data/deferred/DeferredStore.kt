package com.raiz.app.data.deferred

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Estados del sobre del lado del pagador (spec §6). */
enum class DeferredStatus { FIRMADO, ENTREGADO, CONFIRMADO, EXPIRADO, RECHAZADO }

/** Un sobre emitido por este teléfono, tal como se guarda. */
@Serializable
data class DeferredRecord(
    /** = nonce en decimal (puede ser negativo: String, nunca Long en rutas de navegación). */
    val id: String,
    val envelopeB64url: String,
    val payer: String,
    val merchantAddress: String,
    val merchantName: String,
    val merchantVerified: Boolean,
    val amountStroops: Long,
    val tipBps: Int,
    val feeBps: Int,
    val exp: Long,
    val l0: Long,
    val t0: Long,
    /** Hora de pared, solo para mostrar. */
    val signedAtMs: Long,
    val validUntilMs: Long,
    val status: DeferredStatus,
    val txHash: String? = null,
    val motivo: String? = null,
) {
    val tipStroops: Long get() = AuthTreeBuilder.split(amountStroops, tipBps, feeBps).tip
    val feeStroops: Long get() = AuthTreeBuilder.split(amountStroops, tipBps, feeBps).fee
    val totalStroops: Long get() = amountStroops + tipStroops
    val pending: Boolean get() = status == DeferredStatus.FIRMADO || status == DeferredStatus.ENTREGADO
}

object DeferredRecordCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(DeferredRecord.serializer())

    fun encode(records: List<DeferredRecord>): String = json.encodeToString(listSerializer, records)

    /** Nunca lanza: un archivo corrupto se lee como lista vacía. */
    fun decode(raw: String?): List<DeferredRecord> =
        raw?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
}

/** Transiciones puras de la máquina de estados (spec §6); testeables sin Android. */
object DeferredPolicy {
    const val MOTIVO_EXPIRADO = "La vigencia del sobre terminó sin liquidarse."

    /** Solo se firma si hay estimación y la expiración queda por delante de ella. */
    fun canSign(readiness: Readiness, exp: Long): Boolean =
        readiness is Readiness.Lista && exp > readiness.estimate

    /** FIRMADO/ENTREGADO con `exp ≤ L̂` → EXPIRADO. Nunca toca CONFIRMADO ni RECHAZADO. */
    fun refresh(records: List<DeferredRecord>, estimate: Long?): List<DeferredRecord> {
        if (estimate == null) return records
        return records.map { r ->
            if (r.pending && LedgerMath.isExpiredLocally(r.exp, estimate)) {
                r.copy(status = DeferredStatus.EXPIRADO, motivo = MOTIVO_EXPIRADO)
            } else {
                r
            }
        }
    }

    fun markDelivered(r: DeferredRecord): DeferredRecord =
        if (r.status == DeferredStatus.FIRMADO) r.copy(status = DeferredStatus.ENTREGADO) else r

    /** Un hash confirmado manda sobre un EXPIRADO local (la estimación puede ir atrasada). */
    fun markConfirmed(r: DeferredRecord, txHash: String): DeferredRecord =
        if (r.status == DeferredStatus.RECHAZADO) r
        else r.copy(status = DeferredStatus.CONFIRMADO, txHash = txHash, motivo = null)

    fun markRejected(r: DeferredRecord, motivo: String): DeferredRecord =
        if (r.pending) r.copy(status = DeferredStatus.RECHAZADO, motivo = motivo) else r
}

/**
 * Sobres emitidos + ancla del estimador, en SharedPreferences (`raiz_deferred`) con kotlinx.serialization.
 * Desviación declarada respecto a "Room/DataStore" de la spec §7.1: el proyecto no los tiene y el patrón
 * de [com.raiz.app.data.local.ExecutionHashStore] basta para decenas de sobres.
 */
@Singleton
class DeferredStore @Inject constructor(
    @ApplicationContext appContext: Context,
) : AnchorStorage {

    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _records = MutableStateFlow(DeferredRecordCodec.decode(prefs.getString(KEY_RECORDS, null)))
    val records: StateFlow<List<DeferredRecord>> = _records.asStateFlow()

    fun all(): List<DeferredRecord> = _records.value

    fun get(id: String): DeferredRecord? = all().firstOrNull { it.id == id }

    fun pendingCount(): Int = all().count { it.pending }

    /**
     * Guarda con `commit()` y relee antes de dar el sobre por emitido: si devuelve false, NO se muestra el QR
     * (un sobre que viaja sin quedar registrado en el teléfono no se podría cerrar nunca).
     */
    @Synchronized
    fun saveDurable(record: DeferredRecord): Boolean {
        val next = all().filter { it.id != record.id } + record
        val ok = prefs.edit().putString(KEY_RECORDS, DeferredRecordCodec.encode(next)).commit()
        if (!ok) {
            prefs.edit().remove(KEY_RECORDS).commit()
            return false
        }
        val back = DeferredRecordCodec.decode(prefs.getString(KEY_RECORDS, null))
        if (back.none { it.id == record.id }) return false
        _records.value = back
        return true
    }

    @Synchronized
    fun update(record: DeferredRecord) {
        val next = all().map { if (it.id == record.id) record else it }
        prefs.edit().putString(KEY_RECORDS, DeferredRecordCodec.encode(next)).apply()
        _records.value = next
    }

    @Synchronized
    fun replaceAll(records: List<DeferredRecord>) {
        prefs.edit().putString(KEY_RECORDS, DeferredRecordCodec.encode(records)).apply()
        _records.value = records
    }

    // ── AnchorStorage ─────────────────────────────────────────────────────

    override fun loadAnchor(): LedgerAnchor? {
        if (!prefs.contains(KEY_ANCHOR_L0)) return null
        return LedgerAnchor(
            l0 = prefs.getLong(KEY_ANCHOR_L0, 0L),
            t0Ms = prefs.getLong(KEY_ANCHOR_T0_MS, 0L),
            closeTimeSec = prefs.getLong(KEY_ANCHOR_CLOSE, 0L),
            bootId = prefs.getString(KEY_ANCHOR_BOOT, "") ?: "",
            wallMs = prefs.getLong(KEY_ANCHOR_WALL_MS, 0L),
        )
    }

    override fun saveAnchor(anchor: LedgerAnchor) {
        prefs.edit()
            .putLong(KEY_ANCHOR_L0, anchor.l0)
            .putLong(KEY_ANCHOR_T0_MS, anchor.t0Ms)
            .putLong(KEY_ANCHOR_CLOSE, anchor.closeTimeSec)
            .putString(KEY_ANCHOR_BOOT, anchor.bootId)
            .putLong(KEY_ANCHOR_WALL_MS, anchor.wallMs)
            .apply()
    }

    private companion object {
        const val PREFS = "raiz_deferred"
        const val KEY_RECORDS = "records_v1"
        const val KEY_ANCHOR_L0 = "anchor_l0"
        const val KEY_ANCHOR_T0_MS = "anchor_t0_ms"
        const val KEY_ANCHOR_CLOSE = "anchor_close_sec"
        const val KEY_ANCHOR_BOOT = "anchor_boot"
        const val KEY_ANCHOR_WALL_MS = "anchor_wall_ms"
    }
}
