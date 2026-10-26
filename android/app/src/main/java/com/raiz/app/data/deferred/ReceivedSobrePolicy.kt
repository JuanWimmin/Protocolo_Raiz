package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.xdr.toXdrBase64

/**
 * Transiciones PURAS de un sobre RECIBIDO (sin Android, sin red): las decisiones anti fraude del cobrador
 * viven aquí para poder probarlas en JVM; el [SobreSettler] solo trae a la red los datos y aplica el resultado.
 *
 * Reglas (D2/D3/D4 de A2):
 * - **Cobrado exige un hash** cuya auth sea este sobre ([markConfirmed] recibe el hash, no un nulo). Un nonce
 *   consumido sin hash NO es prueba (el pagador pudo firmar otro sobre con el mismo nonce y la red cobró ese):
 *   el sobre sigue EN_TRANSITO con [MOTIVO_BUSCANDO_COMPROBANTE] y se vuelve a buscar en cada pasada
 *   ([whenNonceConsumed]).
 * - Si la red pasa `exp` sin que aparezca ese hash, un nonce que ya se vio consumido es **RECHAZADO** por posible
 *   fraude; uno nunca consumido simplemente **EXPIRÓ** ([whenPastExpiration]).
 * - **La verdad on-chain manda**: un hash con esta auth pisa un RECHAZADO o un EXPIRADO previo.
 * - RECHAZADO solo por motivos definitivos (firma/árbol que no coinciden, nonce usado en otro pago). Fondos,
 *   trustline, comercio, fees y fallos de red aplazan con motivo ([markSendFailed]).
 */
object ReceivedSobrePolicy {
    /** Envíos que llegaron a la red sin cerrar el sobre, a partir de los cuales el envío AUTOMÁTICO se rinde (el manual no). */
    const val MAX_SEND_ATTEMPTS = 3

    /** Espera mínima entre dos intentos automáticos del mismo sobre (5 min), con la hora de pared. */
    const val RETRY_BACKOFF_MS = 300_000L

    /** Ledgers que deben quedar hasta `exp` (con ledger conocido) para que valga la pena enviar. */
    const val MIN_LEDGERS_BEFORE_EXP = DeferredPolicy.MIN_LEDGERS_BEFORE_EXP

    /**
     * Margen (ledgers) bajo la ventana nominal de un sobre al pedir los eventos de su pago ([eventsSinceLedger]): el pagador
     * pudo firmar con un ancla de hasta 24 h y la red a ~6 s por ledger, es decir, hasta ~2 900 ledgers por debajo de lo
     * que su estimador creía.
     */
    const val EVENTS_DRIFT_MARGIN_LEDGERS = 3_000L

    const val MOTIVO_BUSCANDO_COMPROBANTE = "Nonce consumido en la red: buscando el comprobante"
    const val MOTIVO_NONCE_OTRO_PAGO = "El nonce se usó en otro pago: posible fraude"
    const val MOTIVO_EXPIRADO_RED = "La red pasó la expiración sin que nadie lo liquidara."
    const val MOTIVO_EXPIRADO_LOCAL = "La vigencia terminó sin liquidarse (según la estimación local)."

    /**
     * Qué encontró la red al buscar la tx que liquidó un sobre. Espejo puro de
     * [com.raiz.app.data.stellar.SorobanClient.SobreHashLookup]: quien lo trae de la red lo traduce.
     */
    sealed interface Prueba {
        /** Una tx SUCCESS cuya auth es este sobre: la única prueba de cobro. */
        data class Hash(val txHash: String) : Prueba

        /** Se buscó bien y no hay ninguna. */
        data object Ninguna : Prueba

        /** No se pudo leer o consultar (red, RPC): no es un "no". Se vuelve a intentar. */
        data object NoComprobable : Prueba
    }

    fun idOf(payer: String, nonce: Long): String = "$payer:$nonce"

    /**
     * Registro NUEVO (EN_TRANSITO) de un sobre ya verificado. [sobreQr] es el sobre en su forma canónica;
     * [ledgerRef] el ledger con que se comprobó la vigencia (null si no hubo ninguno).
     */
    fun fromCheck(
        ok: SobreCheck.Ok,
        sobreQr: String,
        owner: String,
        rol: RolRecibido,
        feeBps: Int,
        nowMs: Long,
        ledgerRef: Long?,
        vigenciaComprobada: Boolean,
    ): ReceivedSobreRecord =
        ReceivedSobreRecord(
            id = idOf(ok.args.payer, ok.nonce),
            owner = owner,
            sobreQr = sobreQr,
            authXdr = ok.entry.toXdrBase64(),
            payer = ok.args.payer,
            merchant = ok.args.merchant,
            amountStroops = ok.args.amount,
            tipBps = ok.args.tipBps,
            feeBps = feeBps,
            exp = ok.expirationLedger,
            nonce = ok.nonce,
            rol = rol,
            status = RecibidoStatus.EN_TRANSITO,
            receivedAtMs = nowMs,
            receivedAtLedger = ledgerRef,
            vigenciaComprobada = vigenciaComprobada,
        )

    // ── Vencimiento por estimación local (sin red) ────────────────────────────

    /**
     * EN_TRANSITO con `exp <= L` (L = ledger estimado) pasa a EXPIRADO ([MOTIVO_EXPIRADO_LOCAL]). No toca un sobre con
     * una tx EN VUELO ni uno cuyo nonce ya se vio consumido: eso lo decide la red (una estimación local no puede dar
     * por perdido un cobro que puede estar hecho). El EXPIRADO local no es definitivo: ver [needsNetworkCheck].
     */
    fun refresh(records: List<ReceivedSobreRecord>, estimate: Long?): List<ReceivedSobreRecord> {
        if (estimate == null) return records
        return records.map { r ->
            if (r.pending &&
                r.inFlightHash == null &&
                r.motivo != MOTIVO_BUSCANDO_COMPROBANTE &&
                LedgerMath.isExpiredLocally(r.exp, estimate)
            ) {
                r.copy(status = RecibidoStatus.EXPIRADO, motivo = MOTIVO_EXPIRADO_LOCAL)
            } else {
                r
            }
        }
    }

    /** EXPIRADO solo según la estimación local: la red aún no lo confirmó y todavía puede traer un hash. */
    fun isLocalExpiry(r: ReceivedSobreRecord): Boolean =
        r.status == RecibidoStatus.EXPIRADO && r.motivo == MOTIVO_EXPIRADO_LOCAL

    /** ¿Hay que consultar a la red por este sobre? Los pendientes y los que solo vencieron por estimación local. */
    fun needsNetworkCheck(r: ReceivedSobreRecord): Boolean = r.pending || isLocalExpiry(r)

    // ── Cierres ───────────────────────────────────────────────────────────────

    /**
     * Cobrado: una tx SUCCESS cuya auth es este sobre ([txHash], OBLIGATORIO; en blanco no prueba nada y no cambia el
     * registro). Pisa un EXPIRADO y un RECHAZADO previos (la verdad on-chain manda) y limpia el diario, los motivos y
     * la cotización. Idempotente: un sobre ya CONFIRMADO con hash conserva su primer hash.
     */
    fun markConfirmed(r: ReceivedSobreRecord, txHash: String): ReceivedSobreRecord =
        if (txHash.isBlank() || (r.status == RecibidoStatus.CONFIRMADO && !r.txHash.isNullOrBlank())) {
            r
        } else {
            r.copy(
                status = RecibidoStatus.CONFIRMADO,
                txHash = txHash,
                motivo = null,
                lastSendMotivo = null,
                inFlightHash = null,
                inFlightValidUntilSec = 0L,
                lastQuotedFeeStroops = 0L,
            )
        }

    /**
     * La red consumió el nonce pero aún no hay un hash con esta auth. El estado NO cambia (sigue EN_TRANSITO): solo
     * queda el motivo, y [shouldAutoSend] deja de intentar enviarlo. Sin efecto si ya no está pendiente.
     */
    fun markNonceConsumidoSinHash(r: ReceivedSobreRecord): ReceivedSobreRecord =
        if (r.pending) r.copy(motivo = MOTIVO_BUSCANDO_COMPROBANTE) else r

    /** La red pasó la expiración sin que nadie lo liquidara. Solo pendientes. Cierra el diario. */
    fun markExpiredByNetwork(r: ReceivedSobreRecord): ReceivedSobreRecord =
        if (r.pending) {
            r.copy(
                status = RecibidoStatus.EXPIRADO,
                motivo = MOTIVO_EXPIRADO_RED,
                inFlightHash = null,
                inFlightValidUntilSec = 0L,
            )
        } else {
            r
        }

    /** La red confirma el vencimiento que la estimación local había anunciado (ya no se vuelve a consultar). */
    fun verifyLocalExpiry(r: ReceivedSobreRecord): ReceivedSobreRecord =
        if (isLocalExpiry(r)) r.copy(motivo = MOTIVO_EXPIRADO_RED) else r

    /** Rechazo definitivo con [motivo]. Solo pendientes. Cierra el diario. */
    fun markRejected(r: ReceivedSobreRecord, motivo: String): ReceivedSobreRecord =
        if (r.pending) {
            r.copy(
                status = RecibidoStatus.RECHAZADO,
                motivo = motivo,
                inFlightHash = null,
                inFlightValidUntilSec = 0L,
            )
        } else {
            r
        }

    // ── Qué hacer con lo que la red dice (D2/D4) ──────────────────────────────

    /**
     * El nonce del sobre ya está consumido. Con un hash cuya auth es este sobre: CONFIRMADO. Sin hash: si otra tx
     * del pagador gastó ese mismo nonce con OTRO sobre ([nonceEnOtraTx]): RECHAZADO (posible fraude); si no, o si
     * no se pudo comprobar, sigue en tránsito y se vuelve a buscar en cada pasada.
     */
    fun whenNonceConsumed(r: ReceivedSobreRecord, prueba: Prueba, nonceEnOtraTx: Boolean): ReceivedSobreRecord =
        when (prueba) {
            is Prueba.Hash -> markConfirmed(r, prueba.txHash)
            Prueba.Ninguna ->
                if (nonceEnOtraTx) markRejected(r, MOTIVO_NONCE_OTRO_PAGO) else markNonceConsumidoSinHash(r)
            Prueba.NoComprobable -> markNonceConsumidoSinHash(r)
        }

    /**
     * El ledger real ya pasó `exp` y el nonce no está consumido (la entrada temporal desaparece al vencer, así que
     * solo un HASH prueba un cobro previo). Con hash: CONFIRMADO. Sin hash: un sobre cuyo nonce ya se había visto
     * consumido es RECHAZADO (posible fraude) y cualquier otro EXPIRÓ según la red. Sin poder comprobar: no cambia.
     */
    fun whenPastExpiration(r: ReceivedSobreRecord, prueba: Prueba): ReceivedSobreRecord =
        when (prueba) {
            is Prueba.Hash -> markConfirmed(r, prueba.txHash)
            Prueba.Ninguna -> when {
                isLocalExpiry(r) -> verifyLocalExpiry(r)
                r.motivo == MOTIVO_BUSCANDO_COMPROBANTE -> markRejected(r, MOTIVO_NONCE_OTRO_PAGO)
                else -> markExpiredByNetwork(r)
            }
            Prueba.NoComprobable -> r
        }

    // ── Decisiones de la pasada de consulta (D2/D4) ───────────────────────────

    /** Qué hace una pasada de consulta con un sobre recibido, según lo que la red respondió sobre su nonce. */
    enum class Consulta {
        /** Nada que hacer ahora: sin respuesta de la red, con una tx en vuelo (manda su hash) o ya cerrado. */
        SALTAR,

        /** El nonce está consumido: buscar quién lo gastó ([whenNonceConsumed]). */
        NONCE_CONSUMIDO,

        /** El nonce no está consumido: si el ledger real ya pasó `exp`, buscar el hash antes de expirar ([whenPastExpiration]). */
        MIRAR_EXPIRACION,
    }

    /**
     * [nonceConsumed] = respuesta de la red sobre el nonce de [r] (`null` = no se pudo consultar); solo se pregunta por
     * los pendientes. Un EXPIRADO por estimación local no tiene nonce que mirar (la entrada temporal ya desapareció):
     * solo el ledger real decide si se confirma o se cobró.
     */
    fun consulta(r: ReceivedSobreRecord, nonceConsumed: Boolean?): Consulta = when {
        !needsNetworkCheck(r) -> Consulta.SALTAR
        !r.pending -> Consulta.MIRAR_EXPIRACION
        nonceConsumed == null -> Consulta.SALTAR
        nonceConsumed -> Consulta.NONCE_CONSUMIDO
        r.inFlightHash != null -> Consulta.SALTAR
        else -> Consulta.MIRAR_EXPIRACION
    }

    /** ¿El ledger real ya pasó la expiración? `exp` es el último ledger válido; sin ledger no se sabe (false). */
    fun isPastExpiration(r: ReceivedSobreRecord, ledger: Long?): Boolean = ledger != null && ledger > r.exp

    /**
     * Primer ledger donde pudo liquidarse este sobre: desde ahí hay que leer los pagos del Pool para buscar su hash. Se firmó
     * como mucho una ventana ([LedgerMath.WINDOW_12H]) antes de `exp` y el cobrador no conoce el ancla del pagador, así que
     * se resta además [EVENTS_DRIFT_MARGIN_LEDGERS]. Si lo recibió antes de esa fecha (un sobre con más vigencia que los de
     * RAÍZ) se parte de ese ledger. OJO: no se usa el ledger de recepción como cota —otro pudo liquidarlo ANTES de que
     * llegara a este teléfono—, solo la ventana nominal.
     */
    fun eventsSinceLedger(r: ReceivedSobreRecord): Long {
        val nominal = r.exp - LedgerMath.WINDOW_12H - EVENTS_DRIFT_MARGIN_LEDGERS
        return (r.receivedAtLedger?.let { minOf(nominal, it) } ?: nominal).coerceAtLeast(1L)
    }

    /**
     * Qué hace el [SobreSettler] con cada veredicto de enviar un sobre RECIBIDO (D3): solo [SobreSubmit.Liquidado]
     * confirma por sí mismo y solo [SobreSubmit.RechazadaDefinitiva] (`InvalidAction`) rechaza; fondos, trustline,
     * comercio, fees y fallos de red aplazan con motivo y decide la red.
     */
    sealed interface Reaccion {
        /** SUCCESS con esta auth: cobrado. */
        data class Confirmar(val txHash: String) : Reaccion

        /** Sin veredicto todavía: el diario queda abierto y la próxima pasada lo resuelve por hash. */
        data object Nada : Reaccion

        /** El nonce se consumió o la tx falló: solo si de verdad está consumido se busca quién lo gastó. [fallida] = la tx propia entró y falló (cuenta como envío). */
        data class RevisarNonce(val fallida: Boolean) : Reaccion

        /** La red dice "expirado": comprobar si el ledger real ya pasó `exp` y, antes de expirar, buscar el hash. */
        data object RevisarExpiracion : Reaccion

        data class Rechazar(val motivo: String) : Reaccion

        data class Aplazar(val motivo: String, val cuentaComoEnvio: Boolean) : Reaccion

        data class FeeAlta(val feeStroops: Long, val maxFeeStroops: Long) : Reaccion
    }

    fun reaccion(outcome: SobreSubmit): Reaccion = when (outcome) {
        is SobreSubmit.Liquidado -> Reaccion.Confirmar(outcome.txHash)
        is SobreSubmit.EnVuelo -> Reaccion.Nada
        SobreSubmit.NonceUsado -> Reaccion.RevisarNonce(fallida = false)
        is SobreSubmit.FallidaEnRed -> Reaccion.RevisarNonce(fallida = true)
        SobreSubmit.Expirada -> Reaccion.RevisarExpiracion
        is SobreSubmit.RechazadaDefinitiva -> Reaccion.Rechazar(outcome.motivo)
        is SobreSubmit.RechazadaTransitoria -> Reaccion.Aplazar(outcome.motivo, cuentaComoEnvio = false)
        is SobreSubmit.SinFondosFuente -> Reaccion.Aplazar(outcome.motivo, cuentaComoEnvio = false)
        is SobreSubmit.SinRed -> Reaccion.Aplazar(outcome.motivo, cuentaComoEnvio = false)
        is SobreSubmit.FeeAlta -> Reaccion.FeeAlta(outcome.feeStroops, outcome.maxFeeStroops)
    }

    // ── Envío (SobreSettler) ──────────────────────────────────────────────────

    /** Diario pre-envío: la tx firmada [hash] ya salió (o está por salir) y puede entrar hasta [validUntilSec]. No-op si no está pendiente. */
    fun markInFlight(r: ReceivedSobreRecord, hash: String, validUntilSec: Long): ReceivedSobreRecord =
        if (r.pending) r.copy(inFlightHash = hash, inFlightValidUntilSec = validUntilSec) else r

    /** La tx en vuelo tuvo veredicto (o nunca salió): el diario se cierra. No-op si no está pendiente. */
    fun clearInFlight(r: ReceivedSobreRecord): ReceivedSobreRecord =
        if (r.pending) r.copy(inFlightHash = null, inFlightValidUntilSec = 0L) else r

    /**
     * Un intento de envío no cerró el sobre: guarda [motivo] y la hora ([nowMs]) del backoff. [countAsSend] solo
     * es `true` si el envío LLEGÓ a la red (gasta XLM). No cambia el estado; no-op si no está pendiente.
     */
    fun markSendFailed(r: ReceivedSobreRecord, motivo: String, nowMs: Long, countAsSend: Boolean): ReceivedSobreRecord =
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
    fun markFeeTooHigh(r: ReceivedSobreRecord, motivo: String, nowMs: Long, quotedFeeStroops: Long): ReceivedSobreRecord =
        if (r.pending) {
            markSendFailed(r, motivo, nowMs, countAsSend = false).copy(lastQuotedFeeStroops = quotedFeeStroops)
        } else {
            r
        }

    /**
     * ¿Le toca al envío AUTOMÁTICO intentar este sobre ahora? Pendiente, sin tx en vuelo, sin el nonce ya visto
     * consumido (enviar solo repetiría `ExistingValue`), con menos de [MAX_SEND_ATTEMPTS] envíos que llegaron a la
     * red, con tiempo antes de `exp` (si se conoce el ledger) y pasado el backoff. El manual no pasa por aquí.
     */
    fun shouldAutoSend(r: ReceivedSobreRecord, ledgerHint: Long?, nowMs: Long): Boolean =
        r.pending &&
            r.inFlightHash == null &&
            r.motivo != MOTIVO_BUSCANDO_COMPROBANTE &&
            r.sendAttempts < MAX_SEND_ATTEMPTS &&
            (ledgerHint == null || r.exp - ledgerHint >= MIN_LEDGERS_BEFORE_EXP) &&
            (r.lastAttemptMs == 0L || nowMs - r.lastAttemptMs >= RETRY_BACKOFF_MS)
}
