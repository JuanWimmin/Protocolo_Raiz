package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.Network
import com.soneso.stellar.sdk.xdr.toXdrBase64
import kotlinx.coroutines.CancellationException

/**
 * Verificación de un sobre que ESTA wallet recibe de otro teléfono (cobrador o portador), PURA: no toca Android ni
 * la red; todo lo que necesita (ledger, estimación, índice de lo ya visto, ids del Pool) entra por parámetro.
 *
 * Orden (de la más barata a la más cara, `docs/Pago diferido por enjambre.md` A3 + D11 de A2):
 *  1. el sobre es válido: prefijo, firmante `G…`, raíz `pay_merchant` del Pool, árbol idéntico byte a byte con mi
 *     `fee_bps` y firma ed25519 de esta red ([SobreValidator]);
 *  2. índice `(owner, payer, nonce)`: el mismo sobre → [RecibidoCheck.YaRegistrado] (idempotente); el mismo nonce con
 *     OTRO XDR → rechazo por nonce reutilizado (el pagador firmó dos sobres con un nonce y la red cobra uno solo);
 *  3. rol: COBRO si el comercio del árbol soy yo, PORTADOR si no;
 *  4. tope por pago sin señal — SOLO en COBRO (quien entrega la mercancía);
 *  5. vigencia: con red, el ledger real; sin ella, el estimado más un margen que crece con la edad del ancla. Un
 *     COBRO exige [MARGEN_LEDGERS] de colchón y NUNCA se cobra a ciegas (sin ledger ni estimación se rechaza); un
 *     PORTADOR solo transporta, así que sin referencia se acepta con `vigenciaComprobada = false`.
 */
object ReceivedSobreVerifier {
    /** 360 ledgers = 30 min a 5 s: el comercio no entrega contra un sobre a punto de vencer. */
    const val MARGEN_LEDGERS = 360L

    /** 20 USDC: exposición máxima por sobre recibido sin señal. */
    const val TOPE_DEFAULT_STROOPS = 200_000_000L

    const val MOTIVO_TOPE = "Supera el tope por pago sin señal."
    const val MOTIVO_SIN_HORA = "Sin hora de red: conéctate una vez antes de cobrar sin señal."
    const val MOTIVO_EXPIRADO = "El sobre ya venció."
    const val MOTIVO_VENCE_PRONTO = "Vence demasiado pronto para cobrarlo sin señal."
    const val MOTIVO_NONCE_REUTILIZADO = "Nonce reutilizado: posible fraude. No lo aceptes."
    const val MOTIVO_SIN_WALLET = "Sin wallet propia no se reciben sobres."
    const val MOTIVO_ILEGIBLE = "No pudimos leer el sobre."

    /**
     * Colchón de ledgers que debe quedar hasta `exp`: [MARGEN_LEDGERS] más la deriva que pudo acumular la estimación
     * desde que se tomó el ancla, `ceil(edad × (1/τ_min − 1/τ))` (red 4,5 s frente a los 5 s que se asumen). Con 24 h
     * de edad son 1 920 ledgers más. `null` = ancla recién tomada (ledger real): sin deriva.
     */
    fun margen(anchorAgeMs: Long?): Long {
        val deriva = anchorAgeMs?.let {
            LedgerMath.window(0L, LedgerMath.TAU_MS, LedgerMath.TAU_MIN_MS, it.coerceAtLeast(0L))
        } ?: 0L
        return MARGEN_LEDGERS + deriva
    }

    /**
     * @param ledgerActual ledger real de la red (con red) o null. Si está, manda sobre [estimate].
     * @param estimate ledger estimado sin red ([LedgerEstimator]) o null.
     * @param anchorAgeMs edad del ancla de [estimate]; solo se usa con la estimación (el ledger real es exacto).
     * @param vistos índice de lo ya recibido por [owner]: `(payer, nonce)` → el registro guardado, o null.
     */
    suspend fun verify(
        text: String,
        owner: String,
        topeStroops: Long,
        ledgerActual: Long?,
        estimate: Long?,
        anchorAgeMs: Long?,
        vistos: (payer: String, nonce: Long) -> ReceivedSobreRecord?,
        ids: DeferredIds,
        feeBps: Int,
        network: Network,
        nowMs: Long,
    ): RecibidoCheck {
        // 1. Válido por sí mismo.
        val ok = try {
            when (val check = SobreValidator.validateText(text, ids, feeBps, network)) {
                is SobreCheck.Rechazado -> return RecibidoCheck.Rechazado(check.motivo)
                is SobreCheck.Ok -> check
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return RecibidoCheck.Rechazado(MOTIVO_ILEGIBLE)
        }
        val args = ok.args
        val authXdr = ok.entry.toXdrBase64()

        // 2. Índice: el mismo sobre es idempotente (aunque haya vencido entre tanto); el mismo nonce con otro XDR, fraude.
        vistos(args.payer, ok.nonce)?.let { visto ->
            return if (visto.authXdr == authXdr) {
                RecibidoCheck.YaRegistrado(visto)
            } else {
                RecibidoCheck.Rechazado(MOTIVO_NONCE_REUTILIZADO)
            }
        }

        // 3. Rol.
        val rol = if (args.merchant == owner) RolRecibido.COBRO else RolRecibido.PORTADOR

        // 4. Tope (solo quien entrega la mercancía).
        if (rol == RolRecibido.COBRO && args.amount > topeStroops) return RecibidoCheck.Rechazado(MOTIVO_TOPE)

        // 5. Vigencia.
        val exact = ledgerActual != null
        val ref = ledgerActual ?: estimate
        var comprobada = true
        if (ref == null) {
            // Nunca se cobra a ciegas; un portador solo transporta.
            if (rol == RolRecibido.COBRO) return RecibidoCheck.Rechazado(MOTIVO_SIN_HORA)
            comprobada = false
        } else {
            if (ok.expirationLedger <= ref) return RecibidoCheck.Rechazado(MOTIVO_EXPIRADO)
            if (rol == RolRecibido.COBRO && ok.expirationLedger < ref + margen(if (exact) null else anchorAgeMs)) {
                return RecibidoCheck.Rechazado(MOTIVO_VENCE_PRONTO)
            }
        }

        return RecibidoCheck.Aceptado(
            ReceivedSobrePolicy.fromCheck(
                ok = ok,
                sobreQr = SobreCodec.encode(ok.entry),
                owner = owner,
                rol = rol,
                feeBps = feeBps,
                nowMs = nowMs,
                ledgerRef = ref,
                vigenciaComprobada = comprobada,
            ),
        )
    }
}
