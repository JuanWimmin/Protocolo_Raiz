package com.raiz.app.data.stellar

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * "Foto" de una cuenta clásica (G…) leída de Horizon en UNA llamada, con lo justo para decidir
 * el siguiente paso de un depósito. Distingue lo que `HorizonStream.getAssetBalance` no puede:
 * "sin trustline" (`null`) de "saldo 0".
 *
 * @property sequence número de secuencia de la cuenta. Sirve para saber si la foto ya refleja
 *   una transacción propia: la refleja si `sequence >= secuencia de esa transacción`.
 * @property anchorUsdcStroops saldo del USDC del anchor de prueba; `null` = sin trustline.
 * @property fundUsdcStroops saldo del USDC del fondo (Blend); `null` = sin trustline.
 */
data class ClassicAccountSnapshot(
    val sequence: Long,
    val xlmStroops: Long,
    val anchorUsdcStroops: Long?,
    val fundUsdcStroops: Long?,
)

/**
 * Diario de UNA transacción propia que mueve dinero (conversión o envío a la wallet). Lo
 * implementa quien quiera sobrevivir a una respuesta perdida o a la muerte del proceso:
 *
 *  - [signed] se llama tras firmar y ANTES de enviar. Si lanza, la transacción NO se envía.
 *  - [settled] se llama solo con un veredicto DEFINITIVO (aplicada, rechazada o vencida).
 *    Si la función que envía termina sin llamarlo, la transacción sigue "en vuelo": no hay
 *    que construir otra hasta resolver esta por su hash ([DepositPlan.pendingVerdict]).
 */
interface TxJournal {
    suspend fun signed(txHash: String, validUntilSec: Long, sequence: Long)
    suspend fun settled(txHash: String)
}

/**
 * Registro persistido de la transacción en vuelo de una cuenta operativa. No contiene ningún
 * secreto: tipo de tramo, hash, monto y hasta cuándo puede entrar en un ledger.
 *
 * @property validUntilSec `maxTime` de la transacción (epoch s): pasado ese instante ya no
 *   puede aplicarse.
 * @property sequence número de secuencia de la transacción (ver [ClassicAccountSnapshot.sequence]).
 */
data class PendingDepositTx(
    val kind: DepositPlan.Leg,
    val hash: String,
    val amountStroops: Long,
    val validUntilSec: Long,
    val sequence: Long,
) {
    fun encode(): String = listOf(kind.name, hash, amountStroops, validUntilSec, sequence).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "|"
        private val HASH_REGEX = Regex("^[0-9a-fA-F]{64}$")

        /** `null` si el texto está vacío o corrupto — nunca lanza. */
        fun decode(raw: String?): PendingDepositTx? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(SEPARATOR)
            if (parts.size != 5) return null
            val kind = DepositPlan.Leg.entries.firstOrNull { it.name == parts[0] && it != DepositPlan.Leg.NONE }
                ?: return null
            val hash = parts[1].takeIf { HASH_REGEX.matches(it) } ?: return null
            val amount = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
            val validUntil = parts[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val sequence = parts[4].toLongOrNull()?.takeIf { it >= 0L } ?: return null
            return PendingDepositTx(kind, hash, amount, validUntil, sequence)
        }
    }
}

/**
 * Decisiones del depósito SEP-24 "de punta a punta" (anchor → conversión → wallet), como
 * funciones PURAS: sin Android, sin red y sin SDK Stellar, para testearlas en JVM
 * (`DepositPlanTest`). La orquestación vive en `ui/deposit/DepositViewModel.kt`.
 *
 * Idea central: la verdad son los saldos on-chain de la cuenta operativa (la propia wallet en
 * semilla; la "cuenta de depósito" en passkey). Cada tramo mueve TODO lo que hay hacia un
 * destino fijo, así que repetir un tramo es inocuo y basta mirar la foto para saber qué falta.
 */
object DepositPlan {

    /** Tramo que falta para que el dinero quede listo para pagar. */
    enum class Leg {
        NONE,

        /** USDC del anchor → USDC del fondo (path payment clásico, destino la propia cuenta). */
        CONVERT,

        /** USDC del fondo de la cuenta de depósito → smart account C… (`transfer` del SAC). Solo passkey. */
        FORWARD,
    }

    /** Qué disparó el cierre del depósito. */
    enum class Trigger {
        /** El anchor acaba de marcar `completed` en esta sesión. */
        AFTER_DEPOSIT,

        /** Se abrió (o se volvió a) la pantalla Depositar con saldo pendiente. */
        ON_OPEN,

        /** El usuario tocó "Convertir" / "Convertir de todos modos". */
        MANUAL,
    }

    /** Pasos para dejar lista una cuenta de depósito (passkey), en orden. */
    enum class SetupStep { FRIENDBOT, TRUST_ANCHOR, TRUST_FUND }

    /** Estado de una transacción consultada por hash. `UNKNOWN` = no se pudo consultar. */
    enum class TxStatus { SUCCESS, FAILED, NOT_FOUND, UNKNOWN }

    /** Qué hacer con la transacción en vuelo del diario. */
    enum class Verdict {
        /** Entró y tuvo éxito: publicar el resultado y seguir. */
        APPLIED,

        /** No entró ni puede entrar ya (o entró y falló): se puede construir otra. */
        SAFE_TO_RETRY,

        /** Todavía puede entrar: NO construir otra; volver a consultar. */
        KEEP_WAITING,
    }

    /** Margen tras el `maxTime` de una tx antes de darla por vencida (cierre de ledgers + ingesta de Horizon). */
    const val PENDING_MARGIN_SEC = 60L

    /**
     * Ventana en la que un depósito iniciado puede seguir "en camino" sin que haya nada on-chain
     * todavía: el token de la web interactiva del anchor dura 10 min; se dejan 15.
     */
    const val STARTED_GRACE_SEC = 15 * 60L

    /** XLM mínimo de la cuenta operativa para seguir operando (reservas + fees): 5 XLM. */
    const val MIN_XLM_STROOPS = 50_000_000L

    /**
     * Tramo siguiente según los saldos de la cuenta operativa.
     *
     * - Semilla (`needsForward = false`): el USDC del fondo ya está en la wallet del usuario,
     *   así que nunca hay [Leg.FORWARD]; solo queda convertir si hay USDC del anchor.
     * - Passkey: primero se ENVÍA lo que ya sea USDC del fondo (no tiene precio en juego y no
     *   debe quedar rehén de la guarda de la conversión); después se convierte lo del anchor.
     */
    fun nextLeg(needsForward: Boolean, anchorStroops: Long, fundStroops: Long): Leg = when {
        needsForward && fundStroops > 0L -> Leg.FORWARD
        anchorStroops > 0L -> Leg.CONVERT
        else -> Leg.NONE
    }

    /**
     * ¿Se envía la conversión con esta cotización?
     *
     * | guarda \ disparador | AFTER_DEPOSIT | ON_OPEN            | MANUAL |
     * |---|---|---|---|
     * | OK (≥ 97 %)         | sí            | solo passkey       | sí     |
     * | BELOW_AUTO (50–97 %)| no            | no                 | sí     |
     * | BELOW_HARD_FLOOR    | no            | no                 | no     |
     *
     * En semilla, abrir la pantalla con USDC del anchor en la propia cuenta solo OFRECE
     * convertir (sería una sorpresa hacerlo sin que lo pida); en passkey ese saldo vive en una
     * cuenta de tránsito y no tiene otro uso, así que se termina solo.
     */
    fun shouldConvert(trigger: Trigger, needsForward: Boolean, guard: SwapMath.QuoteGuard): Boolean = when (guard) {
        SwapMath.QuoteGuard.BELOW_HARD_FLOOR -> false
        SwapMath.QuoteGuard.BELOW_AUTO -> trigger == Trigger.MANUAL
        SwapMath.QuoteGuard.OK -> when (trigger) {
            Trigger.AFTER_DEPOSIT, Trigger.MANUAL -> true
            Trigger.ON_OPEN -> needsForward
        }
    }

    /**
     * Qué le falta a la cuenta de depósito para poder recibir del anchor y convertir.
     * `snapshot == null` = la cuenta todavía no existe on-chain. Idempotente: se recalcula de la
     * foto y solo devuelve lo que falta.
     */
    fun setupSteps(snapshot: ClassicAccountSnapshot?): List<SetupStep> {
        if (snapshot == null) return listOf(SetupStep.FRIENDBOT, SetupStep.TRUST_ANCHOR, SetupStep.TRUST_FUND)
        return buildList {
            if (snapshot.anchorUsdcStroops == null) add(SetupStep.TRUST_ANCHOR)
            if (snapshot.fundUsdcStroops == null) add(SetupStep.TRUST_FUND)
        }
    }

    /**
     * Veredicto sobre la transacción en vuelo. Regla anti doble envío: mientras la anterior
     * pueda entrar todavía (`now <= validUntil + margen`) NO se construye otra. Pasado ese
     * punto ya no puede aplicarse, y si llegó a aplicarse antes, la foto siguiente (que se
     * relee siempre) ya lo refleja — por eso `UNKNOWN` vencido también es seguro.
     */
    fun pendingVerdict(
        status: TxStatus,
        nowSec: Long,
        validUntilSec: Long,
        marginSec: Long = PENDING_MARGIN_SEC,
    ): Verdict = when (status) {
        TxStatus.SUCCESS -> Verdict.APPLIED
        TxStatus.FAILED -> Verdict.SAFE_TO_RETRY
        TxStatus.NOT_FOUND, TxStatus.UNKNOWN ->
            if (nowSec > validUntilSec + marginSec) Verdict.SAFE_TO_RETRY else Verdict.KEEP_WAITING
    }

    /** ¿La foto ya refleja una transacción propia con esa secuencia? */
    fun isSnapshotFresh(snapshotSequence: Long, minSequence: Long): Boolean = snapshotSequence >= minSequence

    /** ¿`address` es una dirección de contrato Stellar bien formada (`C…`, 56 caracteres base32)? */
    fun isContractAddress(address: String?): Boolean =
        address != null && address.length == 56 && address[0] == 'C' &&
            address.all { it in 'A'..'Z' || it in '2'..'7' }

    /**
     * El destino del envío final SOLO puede ser el smart account guardado en el dispositivo al
     * crear la cuenta de depósito: un `C…` bien formado e idéntico a `storedOwner`. Nunca una
     * dirección que venga de la navegación, de un QR o de una respuesta del anchor.
     */
    fun isValidForwardDestination(destination: String?, storedOwner: String?): Boolean =
        isContractAddress(destination) && destination == storedOwner

    /** ¿La marca "depósito iniciado" sigue vigente? (ver [STARTED_GRACE_SEC]). */
    fun isStartedMarkerFresh(startedAtSec: Long?, nowSec: Long): Boolean {
        if (startedAtSec == null) return false
        val age = nowSec - startedAtSec
        return age in 0L..STARTED_GRACE_SEC
    }

    /** ¿Le queda XLM a la cuenta operativa para reservas y fees? */
    fun hasEnoughXlm(xlmStroops: Long): Boolean = xlmStroops >= MIN_XLM_STROOPS

    // ── Parser de la foto (JSON de `GET /accounts/{id}` de Horizon) ───────────

    /**
     * Convierte el JSON de una cuenta de Horizon en [ClassicAccountSnapshot]. Los montos van de
     * string decimal de 7 cifras a stroops con [SwapMath.amountToStroops] (enteros, sin `Double`).
     * Dos activos con el mismo código y distinto emisor NO se confunden: se compara el issuer.
     *
     * @throws IllegalArgumentException si falta `sequence`, `balances` o el saldo nativo, o si un
     *   monto no es un decimal válido — nunca devuelve un 0 inventado.
     */
    fun parseAccountSnapshot(
        json: JsonObject,
        anchorIssuer: String,
        fundIssuer: String,
        code: String = USDC_CODE,
    ): ClassicAccountSnapshot {
        val sequence = json["sequence"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw IllegalArgumentException("cuenta sin sequence")
        val balances = json["balances"]?.jsonArray ?: throw IllegalArgumentException("cuenta sin balances")

        var xlm: Long? = null
        var anchor: Long? = null
        var fund: Long? = null
        for (element in balances) {
            val line = element.jsonObject
            val type = line["asset_type"]?.jsonPrimitive?.contentOrNull
            val balance = line["balance"]?.jsonPrimitive?.contentOrNull
            if (type == "native") {
                xlm = SwapMath.amountToStroops(balance ?: throw IllegalArgumentException("saldo nativo sin monto"))
                continue
            }
            if (line["asset_code"]?.jsonPrimitive?.contentOrNull != code) continue
            val issuer = line["asset_issuer"]?.jsonPrimitive?.contentOrNull ?: continue
            if (issuer != anchorIssuer && issuer != fundIssuer) continue
            val stroops = SwapMath.amountToStroops(balance ?: throw IllegalArgumentException("trustline sin monto"))
            if (issuer == anchorIssuer) anchor = stroops
            if (issuer == fundIssuer) fund = stroops
        }
        return ClassicAccountSnapshot(
            sequence = sequence,
            xlmStroops = xlm ?: throw IllegalArgumentException("cuenta sin saldo nativo"),
            anchorUsdcStroops = anchor,
            fundUsdcStroops = fund,
        )
    }

    // ── Clasificador de errores de envío a Horizon ────────────────────────────

    /**
     * ¿El error de `POST /transactions` es un RECHAZO DEFINITIVO? Solo si el body trae
     * `result_codes` (la red evaluó la transacción y la rechazó: `tx_failed`, `tx_bad_seq`,
     * `op_under_dest_min`…). Cualquier otra cosa — sin body, un `504 Timeout`, un `503`, un
     * `429` — es AMBIGUA: la transacción puede entrar igualmente y hay que consultarla por hash
     * antes de declarar fallo o construir otra.
     */
    fun isDefinitiveSubmitRejection(body: String?): Boolean =
        !body.isNullOrBlank() && RESULT_CODES_REGEX.containsMatchIn(body)

    /** Fragmento `"result_codes": {...}` del body de error de Horizon (sin el XDR), o `null`. */
    fun resultCodesFragment(body: String?): String? =
        if (body.isNullOrBlank()) null else RESULT_CODES_REGEX.find(body)?.value

    private const val USDC_CODE = "USDC"
    private val RESULT_CODES_REGEX = Regex("\"result_codes\"\\s*:\\s*\\{[^}]*\\}")
}
