package com.raiz.app.data.deferred

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Matemática pura del estimador de ledger sin red (spec `docs/pago_diferido_v0.md` §3.1).
 *
 * ```
 * L̂(t) = L0 + floor((t − t0) / τ)      τ = 5 s (medido el 25-oct-2026: 5,000 s exactos)
 * E     = L̂(t) + W                     W = W_deseada · (τ / τ_min), τ_min = 4,5 s
 * ```
 * Ventana v0: 12 h → W_deseada = 8 640 → W = 9 600 ledgers. Si la red cerrara más rápido (4,5 s) la ventana
 * efectiva sería ≈ 10,8 h; si más lento, más larga (inofensivo). El tope de la red es
 * `E − L ≤ max_entry_ttl − 1 = 3 110 399`: tres órdenes de magnitud por encima.
 */
object LedgerMath {
    const val TAU_MS = 5_000L
    const val TAU_MIN_MS = 4_500L
    const val W_DESIRED_12H = 8_640L
    const val MAX_ANCHOR_AGE_MS = 24 * 3_600_000L
    const val MAX_CLOCK_DRIFT_MS = 15 * 60_000L

    /** 9 600 ledgers para 12 h. */
    val WINDOW_12H: Long = window(W_DESIRED_12H)

    fun window(desiredLedgers: Long, tauMs: Long = TAU_MS, tauMinMs: Long = TAU_MIN_MS): Long =
        desiredLedgers * tauMs / tauMinMs

    fun estimate(l0: Long, t0Ms: Long, nowMs: Long, tauMs: Long = TAU_MS): Long =
        l0 + Math.floorDiv(nowMs - t0Ms, tauMs)

    fun expiration(l0: Long, t0Ms: Long, nowMs: Long, windowLedgers: Long = WINDOW_12H): Long =
        estimate(l0, t0Ms, nowMs) + windowLedgers

    /** Hora de pared prudente para mostrar "válido hasta": la ventana al cierre más rápido observado. */
    fun validUntilWallMs(wallNowMs: Long, windowLedgers: Long = WINDOW_12H): Long =
        wallNowMs + windowLedgers * TAU_MIN_MS

    fun isExpiredLocally(exp: Long, estimate: Long): Boolean = exp <= estimate
}

/**
 * Ancla del estimador: el último ledger visto con red y el instante **monotónico** en que se vio.
 * [wallMs] solo sirve para detectar saltos del reloj (nunca para estimar) y [closeTimeSec] para el campo
 * `t0` del sobre. [bootId] (BOOT_COUNT) detecta un reinicio: `elapsedRealtime` vuelve a cero.
 */
data class LedgerAnchor(
    val l0: Long,
    val t0Ms: Long,
    val closeTimeSec: Long,
    val bootId: String,
    val wallMs: Long,
)

interface AnchorStorage {
    fun loadAnchor(): LedgerAnchor?
    fun saveAnchor(anchor: LedgerAnchor)
}

/** Si se puede firmar un sobre ahora y con qué estimación de ledger. */
sealed interface Readiness {
    data class Lista(val anchor: LedgerAnchor, val estimate: Long) : Readiness
    data class NoLista(val motivo: String) : Readiness
}

/**
 * Estimador de ledger sin red. Se alimenta con [record] cada vez que la app lee el último ledger con señal
 * (`SorobanClient`) y responde [readiness] / [expiration] cuando no la hay. Reloj monotónico
 * (`SystemClock.elapsedRealtime`), nunca la hora de pared, que el usuario puede mover.
 */
@Singleton
class LedgerEstimator internal constructor(
    private val storage: AnchorStorage,
    private val monoClock: () -> Long,
    private val bootId: () -> String,
    private val wallClock: () -> Long,
) {
    @Inject
    constructor(store: DeferredStore, @ApplicationContext context: Context) : this(
        storage = store,
        monoClock = SystemClock::elapsedRealtime,
        bootId = { bootCount(context) },
        wallClock = System::currentTimeMillis,
    )

    /** Registra un ledger visto con red. Conserva el máximo dentro del mismo arranque. */
    @Synchronized
    fun record(sequence: Long, closeTimeSec: Long?) {
        if (sequence <= 0) return
        val boot = bootId()
        val current = storage.loadAnchor()
        if (current != null && current.bootId == boot && current.l0 >= sequence) return
        val wall = wallClock()
        storage.saveAnchor(
            LedgerAnchor(
                l0 = sequence,
                t0Ms = monoClock(),
                closeTimeSec = closeTimeSec ?: (wall / 1000L),
                bootId = boot,
                wallMs = wall,
            ),
        )
    }

    fun anchor(): LedgerAnchor? = storage.loadAnchor()?.takeIf { it.bootId == bootId() }

    fun readiness(nowMs: Long = monoClock(), wallNowMs: Long = wallClock()): Readiness {
        val stored = storage.loadAnchor() ?: return Readiness.NoLista(MOTIVO_SIN_ANCLA)
        if (stored.bootId != bootId()) return Readiness.NoLista(MOTIVO_REINICIO)
        val deltaMono = nowMs - stored.t0Ms
        if (deltaMono < 0) return Readiness.NoLista(MOTIVO_RELOJ)
        if (deltaMono > LedgerMath.MAX_ANCHOR_AGE_MS) return Readiness.NoLista(MOTIVO_ANTIGUA)
        val deltaWall = wallNowMs - stored.wallMs
        if (abs(deltaWall - deltaMono) > LedgerMath.MAX_CLOCK_DRIFT_MS) return Readiness.NoLista(MOTIVO_RELOJ)
        return Readiness.Lista(stored, LedgerMath.estimate(stored.l0, stored.t0Ms, nowMs))
    }

    fun estimate(nowMs: Long = monoClock()): Long? = (readiness(nowMs) as? Readiness.Lista)?.estimate

    fun expiration(windowLedgers: Long = LedgerMath.WINDOW_12H, nowMs: Long = monoClock()): Long? =
        estimate(nowMs)?.plus(windowLedgers)

    fun validUntilWallMs(windowLedgers: Long = LedgerMath.WINDOW_12H): Long =
        LedgerMath.validUntilWallMs(wallClock(), windowLedgers)

    companion object {
        const val MOTIVO_SIN_ANCLA =
            "Necesitamos haber tenido señal al menos una vez para estimar la vigencia del sobre. " +
                "Conéctate un momento y vuelve a intentar."
        const val MOTIVO_REINICIO =
            "El teléfono se reinició sin señal: necesitamos red un momento para estimar la vigencia."
        const val MOTIVO_ANTIGUA =
            "La app lleva más de 24 h sin ver la red: no podemos estimar la vigencia del sobre."
        const val MOTIVO_RELOJ =
            "El reloj del teléfono saltó: conéctate un momento para volver a estimar la vigencia."

        fun bootCount(context: Context): String = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()
        }.getOrDefault("?")
    }
}
