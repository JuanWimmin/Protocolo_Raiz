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

/**
 * Un sobre emitido por este teléfono, tal como se guarda. Pertenece a la wallet [payer]: la app puede
 * alojar varias wallets a lo largo del tiempo y cada una ve solo sus sobres.
 */
@Serializable
data class DeferredRecord(
    /** = nonce en decimal (puede ser negativo: String, nunca Long en rutas de navegación). */
    val id: String,
    /** Lo que va en el QR y en "Copiar sobre": `raiz:sobre:1:` + base64url del XDR ([SobreCodec]). */
    val sobreQr: String,
    /** La entrada firmada en base64 estándar (`toXdrBase64()`): para comparar con la auth de una tx. */
    val authXdr: String,
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
    // ── Envío automático desde este teléfono (SobreSettler). TODOS con valor por defecto: los registros ya
    // guardados en `records_v2` (sin estas claves) siguen leyéndose. ──
    /** Envíos que llegaron a la red (PENDING/DUPLICATE) sin cerrar el sobre: gastan XLM. Tope [DeferredPolicy.MAX_SEND_ATTEMPTS]. */
    val sendAttempts: Int = 0,
    /** Último motivo por el que el envío no cerró el sobre (para mostrarlo en la lista); null si nunca falló. */
    val lastSendMotivo: String? = null,
    /** Hora de pared del último intento fallido o aplazado: base del backoff ([DeferredPolicy.RETRY_BACKOFF_MS]). 0 = ninguno. */
    val lastAttemptMs: Long = 0L,
    /** Diario pre-envío: hash de la tx firmada que aún no tiene veredicto. null = ninguna en vuelo. */
    val inFlightHash: String? = null,
    /** `maxTime` (epoch s, hora de la red) de esa tx: pasado este instante ya no puede entrar en un ledger. */
    val inFlightValidUntilSec: Long = 0L,
    /** Última cotización de fee (stroops) que superó el tope del envío automático. 0 = no aplica. */
    val lastQuotedFeeStroops: Long = 0L,
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
    const val MOTIVO_EXPIRADO = "La vigencia del sobre terminó sin liquidarse (según la estimación local)."
    const val MOTIVO_EXPIRADO_RED = "La red pasó la expiración del sobre sin que nadie lo liquidara."

    /** Envíos que llegaron a la red sin cerrar el sobre, a partir de los cuales el envío AUTOMÁTICO se rinde (el manual no). */
    const val MAX_SEND_ATTEMPTS = 3

    /** Espera mínima entre dos intentos automáticos del mismo sobre (5 min), medida con la hora de pared. */
    const val RETRY_BACKOFF_MS = 5 * 60_000L

    /** Ledgers que deben quedar hasta `exp` (con ledger conocido) para que valga la pena enviar: 3 × 5 s = 15 s. */
    const val MIN_LEDGERS_BEFORE_EXP = 3L

    /** Margen (ledgers) bajo el ancla del sobre al pedir los eventos de su pago: ver [eventsSinceLedger]. */
    const val EVENTS_MARGIN_LEDGERS = 20L

    /** Solo se firma si hay estimación y la expiración queda por delante de ella. */
    fun canSign(readiness: Readiness, exp: Long): Boolean =
        readiness is Readiness.Lista && exp > readiness.estimate

    /**
     * FIRMADO/ENTREGADO con `exp ≤ L̂` → EXPIRADO. Nunca toca CONFIRMADO ni RECHAZADO, ni un sobre con una tx
     * EN VUELO: eso lo decide la red al resolver el hash (una estimación local no puede dar por perdido un
     * envío que puede entrar todavía). Ese EXPIRADO es solo una estimación local ([MOTIVO_EXPIRADO]): con red se
     * vuelve a consultar ([needsNetworkCheck]) por si un cobrador lo liquidó justo antes de vencer.
     */
    fun refresh(records: List<DeferredRecord>, estimate: Long?): List<DeferredRecord> {
        if (estimate == null) return records
        return records.map { r ->
            if (r.pending && r.inFlightHash == null && LedgerMath.isExpiredLocally(r.exp, estimate)) {
                r.copy(status = DeferredStatus.EXPIRADO, motivo = MOTIVO_EXPIRADO)
            } else {
                r
            }
        }
    }

    /**
     * EXPIRADO solo según la estimación local ([MOTIVO_EXPIRADO]): la red aún no lo confirmó y todavía puede traer el
     * hash de un cobro hecho justo antes de vencer (la estimación puede ir adelantada y el nonce, temporal, ya no deja
     * rastro). No es definitivo: ver [needsNetworkCheck].
     */
    fun isLocalExpiry(r: DeferredRecord): Boolean =
        r.status == DeferredStatus.EXPIRADO && r.motivo == MOTIVO_EXPIRADO

    /** ¿Hay que consultar a la red por este sobre? Los pendientes y los que solo vencieron por estimación local. */
    fun needsNetworkCheck(r: DeferredRecord): Boolean = r.pending || isLocalExpiry(r)

    fun markDelivered(r: DeferredRecord): DeferredRecord =
        if (r.status == DeferredStatus.FIRMADO) r.copy(status = DeferredStatus.ENTREGADO) else r

    /**
     * Cobrado: la red consumió el nonce de ESTE sobre (o una tx cuya auth es este sobre tuvo éxito). La verdad
     * on-chain manda: pisa un EXPIRADO local (la estimación puede ir atrasada) y también un RECHAZADO (un
     * rechazo previo pudo ser transitorio). [txHash] puede faltar si la prueba vino del nonce y el evento
     * aún no se encontró. Limpia el diario de envío y el último motivo.
     */
    fun markConfirmed(r: DeferredRecord, txHash: String?): DeferredRecord =
        r.copy(
            status = DeferredStatus.CONFIRMADO,
            txHash = txHash ?: r.txHash,
            motivo = null,
            lastSendMotivo = null,
            inFlightHash = null,
            inFlightValidUntilSec = 0L,
            lastQuotedFeeStroops = 0L,
        )

    fun markExpiredByNetwork(r: DeferredRecord): DeferredRecord =
        if (r.pending) {
            r.copy(
                status = DeferredStatus.EXPIRADO,
                motivo = MOTIVO_EXPIRADO_RED,
                inFlightHash = null,
                inFlightValidUntilSec = 0L,
            )
        } else {
            r
        }

    /** La red confirma el vencimiento que la estimación local había anunciado (ya no se vuelve a consultar). */
    fun verifyLocalExpiry(r: DeferredRecord): DeferredRecord =
        if (isLocalExpiry(r)) r.copy(motivo = MOTIVO_EXPIRADO_RED) else r

    fun markRejected(r: DeferredRecord, motivo: String): DeferredRecord =
        if (r.pending) {
            r.copy(
                status = DeferredStatus.RECHAZADO,
                motivo = motivo,
                inFlightHash = null,
                inFlightValidUntilSec = 0L,
            )
        } else {
            r
        }

    // ── Envío automático (SobreSettler) ──────────────────────────────────────

    /** Diario pre-envío: la tx firmada [hash] ya salió (o está por salir) y puede entrar hasta [validUntilSec]. */
    fun markInFlight(r: DeferredRecord, hash: String, validUntilSec: Long): DeferredRecord =
        if (r.pending) r.copy(inFlightHash = hash, inFlightValidUntilSec = validUntilSec) else r

    /** La tx en vuelo tuvo veredicto definitivo (o nunca salió): el diario se cierra. */
    fun clearInFlight(r: DeferredRecord): DeferredRecord =
        if (r.pending) r.copy(inFlightHash = null, inFlightValidUntilSec = 0L) else r

    /**
     * Un intento de envío no cerró el sobre: guarda el [motivo] para mostrarlo y la hora ([nowMs]) para el
     * backoff. [countAsSend] solo es `true` si el envío LLEGÓ a la red (PENDING/DUPLICATE: gasta XLM); los
     * fallos de simulación imponen backoff pero no cuentan. No cambia el estado del sobre; sin efecto si ya
     * no está pendiente.
     */
    fun markSendFailed(r: DeferredRecord, motivo: String, nowMs: Long, countAsSend: Boolean): DeferredRecord =
        if (r.pending) {
            r.copy(
                lastSendMotivo = motivo,
                lastAttemptMs = nowMs,
                sendAttempts = if (countAsSend) r.sendAttempts + 1 else r.sendAttempts,
                lastQuotedFeeStroops = 0L,
            )
        } else {
            r
        }

    /** Como [markSendFailed] (sin contar) y además guarda la cotización [quotedFeeStroops] que superó el tope. */
    fun markFeeTooHigh(r: DeferredRecord, motivo: String, nowMs: Long, quotedFeeStroops: Long): DeferredRecord =
        if (r.pending) {
            markSendFailed(r, motivo, nowMs, countAsSend = false).copy(lastQuotedFeeStroops = quotedFeeStroops)
        } else {
            r
        }

    /**
     * ¿Le toca al envío AUTOMÁTICO intentar este sobre ahora? Pendiente, sin tx en vuelo, con menos de
     * [MAX_SEND_ATTEMPTS] envíos que llegaron a la red, con tiempo suficiente antes de `exp` (si se conoce el
     * ledger) y pasado el backoff del último intento. El envío MANUAL ("Liquidar ahora") no pasa por aquí.
     */
    fun shouldAutoSend(r: DeferredRecord, ledgerHint: Long?, nowMs: Long): Boolean =
        r.pending &&
            r.inFlightHash == null &&
            r.sendAttempts < MAX_SEND_ATTEMPTS &&
            (ledgerHint == null || r.exp - ledgerHint >= MIN_LEDGERS_BEFORE_EXP) &&
            (r.lastAttemptMs == 0L || nowMs - r.lastAttemptMs >= RETRY_BACKOFF_MS)

    // ── Consulta a la red (DeferredPaymentManager.syncWithNetwork) ────────────────

    /**
     * Primer ledger donde pudo liquidarse el sobre: el ancla del estimador con que se firmó ([DeferredRecord.l0]), un
     * ledger REAL visto antes de firmar —y una tx con este sobre solo puede entrar después de firmarlo—. Desde ahí
     * (menos [EVENTS_MARGIN_LEDGERS]) hay que leer los pagos del Pool para buscar el hash de su cobro: la ventana fija de
     * ~11 h de `SorobanClient.tourPaymentEvents` no alcanza a un sobre que vive 16 h.
     */
    fun eventsSinceLedger(r: DeferredRecord): Long = (r.l0 - EVENTS_MARGIN_LEDGERS).coerceAtLeast(1L)

    /** Qué hace una pasada de consulta con un sobre PROPIO, según lo que la red dijo de su nonce. */
    enum class Consulta {
        /** Nada que hacer ahora: ya cerrado por la red o sin respuesta sobre su nonce. */
        SALTAR,

        /** El nonce está consumido: para el pagador basta (CONFIRMADO, con el hash si se encuentra). */
        NONCE_CONSUMIDO,

        /** Sin nonce que mirar: si el ledger real ya pasó `exp`, buscar el hash antes de expirar ([whenPastExpiration]). */
        MIRAR_EXPIRACION,
    }

    /**
     * [nonceConsumed] = respuesta de la red sobre el nonce de [r] (`null` = no se pudo consultar); solo se pregunta por
     * los pendientes. Un EXPIRADO solo por estimación local ([isLocalExpiry]) no tiene nonce que mirar (la entrada
     * temporal desaparece al vencer): decide únicamente el ledger real.
     */
    fun consulta(r: DeferredRecord, nonceConsumed: Boolean?): Consulta = when {
        !needsNetworkCheck(r) -> Consulta.SALTAR
        !r.pending -> Consulta.MIRAR_EXPIRACION
        nonceConsumed == null -> Consulta.SALTAR
        nonceConsumed -> Consulta.NONCE_CONSUMIDO
        else -> Consulta.MIRAR_EXPIRACION
    }

    /**
     * El ledger real ya pasó `exp` (D4): la entrada del nonce es temporal y desaparece al vencer, así que solo el HASH
     * del pago prueba un cobro previo. Con hash → CONFIRMADO (pisa un EXPIRADO local); sin hash tras buscar bien →
     * EXPIRADO según la red (en un EXPIRADO local solo cambia el motivo, y deja de consultarse); sin poder comprobar →
     * no cambia.
     */
    fun whenPastExpiration(r: DeferredRecord, prueba: ReceivedSobrePolicy.Prueba): DeferredRecord =
        when (prueba) {
            is ReceivedSobrePolicy.Prueba.Hash -> markConfirmed(r, prueba.txHash)
            ReceivedSobrePolicy.Prueba.Ninguna -> if (isLocalExpiry(r)) verifyLocalExpiry(r) else markExpiredByNetwork(r)
            ReceivedSobrePolicy.Prueba.NoComprobable -> r
        }
}

/**
 * Sobres emitidos + ancla del estimador, en SharedPreferences (`raiz_deferred`) con kotlinx.serialization.
 * Desviación declarada respecto a "Room/DataStore" de la spec §7.1: el proyecto no los tiene y el patrón
 * de [com.raiz.app.data.local.ExecutionHashStore] basta para decenas de sobres. El archivo es del
 * dispositivo; las vistas por wallet salen de [recordsFor].
 */
@Singleton
class DeferredStore @Inject constructor(
    @ApplicationContext appContext: Context,
) : AnchorStorage {

    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _records = MutableStateFlow(DeferredRecordCodec.decode(prefs.getString(KEY_RECORDS, null)))
    val records: StateFlow<List<DeferredRecord>> = _records.asStateFlow()

    fun all(): List<DeferredRecord> = _records.value

    /** Sobres de una wallet concreta; vacío si no hay wallet. */
    fun recordsFor(payer: String?): List<DeferredRecord> =
        if (payer == null) emptyList() else all().filter { it.payer == payer }

    fun get(id: String): DeferredRecord? = all().firstOrNull { it.id == id }

    fun pendingCount(payer: String?): Int = recordsFor(payer).count { it.pending }

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

    /**
     * Reemplaza un registro por una copia ya calculada. Ojo: [record] puede venir de una lectura anterior y
     * pisar una escritura concurrente; para transiciones de estado usa [mutate].
     */
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

    /**
     * Lee–modifica–escribe UN registro bajo el mismo candado y lo guarda con `commit()`. [f] recibe el
     * registro VIVO en este instante (nunca una copia capturada antes de un envío o una consulta), de modo que
     * el sincronizador, el envío automático y las pantallas no se pisan entre sí. [f] no debe cambiar el `id`.
     *
     * Devuelve el registro resultante (el mismo si [f] no cambió nada), o `null` si el sobre no existe o el disco
     * falló; en ese último caso la memoria NO cambia (sigue igual que el disco).
     */
    @Synchronized
    fun mutate(id: String, f: (DeferredRecord) -> DeferredRecord): DeferredRecord? {
        val current = _records.value
        val old = current.firstOrNull { it.id == id } ?: return null
        val updated = f(old)
        if (updated == old) return old
        if (updated.id != id) return null
        val next = current.map { if (it.id == id) updated else it }
        val ok = prefs.edit().putString(KEY_RECORDS, DeferredRecordCodec.encode(next)).commit()
        if (!ok) return null
        _records.value = next
        return updated
    }

    /**
     * Aplica [f] a TODA la lista bajo el candado (p. ej. el vencimiento local por estimación). Escritura
     * asíncrona (`apply()`): quien la llama puede estar en el hilo principal y el resultado se recalcula solo.
     */
    @Synchronized
    fun refreshAll(f: (List<DeferredRecord>) -> List<DeferredRecord>) {
        val current = _records.value
        val next = f(current)
        if (next == current) return
        prefs.edit().putString(KEY_RECORDS, DeferredRecordCodec.encode(next)).apply()
        _records.value = next
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
        /** v2: el sobre es el XDR firmado con prefijo (ya no el JSON de la spec §5). */
        const val KEY_RECORDS = "records_v2"
        const val KEY_ANCHOR_L0 = "anchor_l0"
        const val KEY_ANCHOR_T0_MS = "anchor_t0_ms"
        const val KEY_ANCHOR_CLOSE = "anchor_close_sec"
        const val KEY_ANCHOR_BOOT = "anchor_boot"
        const val KEY_ANCHOR_WALL_MS = "anchor_wall_ms"
    }
}
