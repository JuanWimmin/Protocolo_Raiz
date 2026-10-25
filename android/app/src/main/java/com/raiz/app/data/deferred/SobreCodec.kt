package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.xdr.SorobanAuthorizationEntryXdr
import com.soneso.stellar.sdk.xdr.fromXdrBase64
import com.soneso.stellar.sdk.xdr.toXdrBase64
import java.util.Base64

/**
 * El **sobre** que viaja (QR y "Copiar sobre"): `raiz:sobre:1:` + base64url (sin relleno) del XDR de la
 * `SorobanAuthorizationEntry` firmada. Nada más: comercio, monto, aporte y reparto se derivan de la
 * invocación firmada ([PayArgs]), así que no existe la clase de error "lo que se enseña no es lo que se
 * firmó" (ajuste 1 de `docs/Pago diferido por enjambre.md`; sustituye al JSON de la spec §5).
 *
 * Tamaño: 912 bytes de XDR con 3 transfers → 1 216 caracteres + 13 de prefijo = 1 229 (QR versión 29
 * con corrección M, frente a la 38 del JSON).
 */
object SobreCodec {
    const val PREFIX = "raiz:sobre:1:"

    /** Corta entradas basura antes de parsear nada. */
    const val MAX_CHARS = 4_096

    fun encode(entry: SorobanAuthorizationEntryXdr): String {
        val bytes = Base64.getDecoder().decode(entry.toXdrBase64())
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** `true` si el texto parece un sobre RAÍZ (para el escáner, antes de intentar decodificar). */
    fun isSobre(text: String): Boolean = text.trim().startsWith(PREFIX)

    /** Decodifica un sobre; `null` si no lo es o está corrupto. Nunca lanza. */
    fun decode(text: String): SorobanAuthorizationEntryXdr? = runCatching {
        val t = text.trim()
        require(t.length <= MAX_CHARS) { "sobre demasiado largo" }
        require(t.startsWith(PREFIX)) { "sin prefijo" }
        val b64url = t.removePrefix(PREFIX).filterNot { it.isWhitespace() }.trimEnd('=')
        require(b64url.isNotEmpty()) { "vacío" }
        val bytes = Base64.getUrlDecoder().decode(b64url)
        SorobanAuthorizationEntryXdr.fromXdrBase64(Base64.getEncoder().encodeToString(bytes))
    }.getOrNull()
}
