package com.raiz.app.data.deferred

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * Por qué ESTA wallet tiene el sobre de otro teléfono.
 *
 * - [COBRO]: el comercio destinatario (`args.merchant == owner`). Entrega la mercancía contra el sobre y es quien
 *   cobra al liquidarse. Se le aplican el tope por pago sin señal y el margen de vigencia.
 * - [PORTADOR]: cualquier otra wallet. Solo lleva el sobre hasta una red (o una pasarela); no entrega nada, así
 *   que ni tope ni margen, y puede aceptarlo sin poder comprobar su vigencia.
 */
enum class RolRecibido { COBRO, PORTADOR }

/** Estados de un sobre RECIBIDO. `CONFIRMADO` exige un hash cuya auth sea este sobre (D2 de A2). */
enum class RecibidoStatus { EN_TRANSITO, CONFIRMADO, EXPIRADO, RECHAZADO }

/**
 * Un sobre que ESTA wallet recibió de otro teléfono (escaneado o pegado), tal como se guarda. Es el contrapunto
 * de [DeferredRecord] (sobres propios del pagador): la mecánica de envío del [SobreSettler] es la misma y los
 * campos de envío (`sendAttempts`, `lastSendMotivo`, `lastAttemptMs`, `inFlightHash`, `inFlightValidUntilSec`,
 * `lastQuotedFeeStroops`) significan lo mismo que allí.
 *
 * Diferencia clave de política: aquí "cobrado" exige un hash cuya auth sea ESTE [authXdr]. El nonce consumido
 * sin hash NO basta (el pagador pudo firmar otro sobre con el mismo nonce y la red cobró ese) y deja el sobre
 * en tránsito con un motivo; ver [ReceivedSobrePolicy].
 *
 * Todo con valor por defecto salvo lo que identifica al sobre: un registro guardado por una versión anterior
 * sigue leyéndose.
 */
@Serializable
data class ReceivedSobreRecord(
    /** `"<payer>:<nonce>"`: clave estable e única en el teléfono ([ReceivedSobrePolicy.idOf]). */
    val id: String,
    /** Wallet que recibió el sobre y, si es COBRO, la que cobra. Solo ella lo envía desde la app. */
    val owner: String,
    /** Lo que viaja: `raiz:sobre:1:` + base64url del XDR firmado ([SobreCodec]), en su forma canónica. */
    val sobreQr: String,
    /** La entrada firmada en base64 estándar: para comparar con la auth de una tx. */
    val authXdr: String,
    val payer: String,
    /** Comercio destinatario que fija el árbol firmado (`G…` o `C…`). */
    val merchant: String,
    val amountStroops: Long,
    val tipBps: Int,
    val feeBps: Int,
    /** `signature_expiration_ledger` del sobre. */
    val exp: Long,
    val nonce: Long,
    val rol: RolRecibido,
    val status: RecibidoStatus,
    /** Hora de pared en que se recibió, solo para mostrar. */
    val receivedAtMs: Long,
    /** Ledger de referencia con el que se comprobó la vigencia al recibirlo (real o estimado); null = ninguno. */
    val receivedAtLedger: Long? = null,
    /** false = se aceptó sin poder comprobar la vigencia (portador sin referencia de ledger): solo transporta. */
    val vigenciaComprobada: Boolean = true,
    val txHash: String? = null,
    val motivo: String? = null,
    /** Envíos que llegaron a la red (PENDING/DUPLICATE) sin cerrar el sobre: gastan XLM. Tope [ReceivedSobrePolicy.MAX_SEND_ATTEMPTS]. */
    val sendAttempts: Int = 0,
    val lastSendMotivo: String? = null,
    /** Hora de pared del último intento fallido o aplazado: base del backoff. 0 = ninguno. */
    val lastAttemptMs: Long = 0L,
    /** Diario pre-envío: hash de la tx firmada que aún no tiene veredicto. null = ninguna en vuelo. */
    val inFlightHash: String? = null,
    /** `maxTime` (epoch s, hora de la red) de esa tx. */
    val inFlightValidUntilSec: Long = 0L,
    /** Última cotización de fee (stroops) que superó el tope del envío automático. 0 = no aplica. */
    val lastQuotedFeeStroops: Long = 0L,
) {
    val split: PayMerchantSplit get() = AuthTreeBuilder.split(amountStroops, tipBps, feeBps)
    val tipStroops: Long get() = split.tip
    val feeStroops: Long get() = split.fee
    val toMerchantStroops: Long get() = split.toMerchant
    val totalStroops: Long get() = amountStroops + tipStroops
    val pending: Boolean get() = status == RecibidoStatus.EN_TRANSITO
}

/** Veredicto de recibir un sobre ([ReceivedSobreVerifier.verify]). */
sealed interface RecibidoCheck {
    /** Pasó todas las comprobaciones: [record] es un registro NUEVO, listo para guardar. */
    data class Aceptado(val record: ReceivedSobreRecord) : RecibidoCheck

    /** El mismo sobre (mismo pagador, nonce y XDR) ya estaba guardado: [record] es el guardado, sin tocar. */
    data class YaRegistrado(val record: ReceivedSobreRecord) : RecibidoCheck

    data class Rechazado(val motivo: String) : RecibidoCheck
}

object ReceivedSobreCodec {
    private val format = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(ReceivedSobreRecord.serializer())

    fun encode(list: List<ReceivedSobreRecord>): String = format.encodeToString(listSerializer, list)

    /**
     * Nunca lanza. Un texto corrupto o que no es una lista se lee como lista vacía; si solo algún elemento está
     * dañado se descarta ese y se conservan los demás (un registro ilegible no debe llevarse a los buenos).
     */
    fun decode(json: String?): List<ReceivedSobreRecord> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            format.parseToJsonElement(json).jsonArray.mapNotNull { element ->
                runCatching { format.decodeFromJsonElement(ReceivedSobreRecord.serializer(), element) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
