package com.raiz.app.data.deferred

import com.soneso.stellar.sdk.xdr.TransactionResultCodeXdr

/**
 * Veredicto de enviar UN sobre firmado como transacción propia ([com.raiz.app.data.stellar.SorobanClient.submitSobre]).
 *
 * Es un tipo cerrado para que quien lo reciba (el [SobreSettler]) esté obligado a decidir qué hacer con
 * CADA caso. La regla de oro: solo [Liquidado] prueba el cobro por sí mismo; [NonceUsado] y [FallidaEnRed]
 * NO (el nonce pudo consumirlo otro sobre) y se resuelven consultando a la red.
 */
sealed interface SobreSubmit {
    /** La tx entró y tuvo éxito (SUCCESS) con esta auth: el sobre está cobrado. */
    data class Liquidado(val txHash: String) : SobreSubmit

    /**
     * Sin veredicto todavía: la tx salió y puede entrar aún. El diario queda ABIERTO; no se debe
     * reconstruir otra hasta resolver este hash.
     */
    data class EnVuelo(val txHash: String, val validUntilSec: Long) : SobreSubmit

    /** `Error(Auth, ExistingValue)` o nonce consumido justo antes de enviar. Nunca significa "cobrado" por sí solo. */
    data object NonceUsado : SobreSubmit

    /** `Error(Auth, InvalidInput)`: la expiración del sobre ya pasó (o excede el tope de la red). */
    data object Expirada : SobreSubmit

    /** SOLO `Error(Auth, InvalidAction)`: la firma no corresponde al árbol. No se arregla reintentando. */
    data class RechazadaDefinitiva(val motivo: String) : SobreSubmit

    /** Fondos (#10), trustline (#13), comercio (#4/#5), otros códigos del Pool u otro Auth: no gastó nada; aplazar con motivo. */
    data class RechazadaTransitoria(val motivo: String) : SobreSubmit

    /** La tx entró en un ledger y falló (el fee se pagó). */
    data class FallidaEnRed(val txHash: String) : SobreSubmit

    /** La cuenta que envía no existe o no tiene XLM para el fee: el envío debe hacerse por la pasarela web. */
    data class SinFondosFuente(val motivo: String) : SobreSubmit

    /** La cotización de la simulación supera el tope pedido: no se envió nada (la simulación es gratis). */
    data class FeeAlta(val feeStroops: Long, val maxFeeStroops: Long) : SobreSubmit

    /** RPC caído, red saturada, tx vencida sin entrar, secuencia/vigencia obsoletas (hay que reconstruir). */
    data class SinRed(val motivo: String) : SobreSubmit
}

/**
 * Textos y clasificadores PUROS de los errores de la red al liquidar un sobre (sin Android, sin red ni SDK de
 * transporte): testeables en JVM ([SobreErrorsTest]).
 *
 * Textos reales de la red (medidos en testnet, ver `landing/pasarela.html` y `DeferredAuthTestnetIT`):
 * - `Error(Auth, ExistingValue)` = nonce ya usado (NUNCA "cobrado").
 * - `Error(Auth, InvalidInput)` = expirado.
 * - `Error(Auth, InvalidAction)` = la firma no corresponde al árbol (sobre alterado, o cambió la tarifa o el admin).
 * - `Error(Contract, #4)` comercio no registrado, `#5` no verificado, `#10` fondos insuficientes del pagador,
 *   `#13` sin trustline. Ojo: `#7` del Pool es `InvalidAmount`, NO fondos.
 */
object SobreErrors {
    const val MSG_EXISTING =
        "La red ya ejecutó un sobre con este nonce (este u otro). No se da por cobrado sin un hash cuya auth sea este sobre."
    const val MSG_INVALID_INPUT = "Expirado (o expiración fuera del tope de la red)."
    const val MSG_AUTH =
        "La firma no corresponde al árbol: sobre alterado o cambió la tarifa o el admin del Pool. El pagador debe firmar de nuevo."
    const val MSG_AUTH_OTRO = "La red rechazó la autorización; se volverá a intentar."
    const val MSG_4 = "Comercio no registrado en el Pool."
    const val MSG_5 = "Comercio no verificado."
    const val MSG_10 = "Fondos insuficientes del pagador en USDC del fondo."
    const val MSG_13 = "El pagador no tiene trustline al USDC del fondo."
    const val MSG_SIN_XLM = "Tu cuenta no tiene XLM para enviar; usa la pasarela web."
    const val MSG_FALLO_RED = "La transacción entró y falló."

    // ── Textos propios del envío (no vienen de la simulación) ─────────────────
    const val MSG_NO_CONTRACT_FN = "El sobre no es una invocación de contrato"
    const val MSG_SIN_CREDENCIALES = "El sobre no lleva credenciales de dirección"
    const val MSG_SIN_HORA_RED = "No se pudo leer la hora de la red; se volverá a intentar."
    const val MSG_SIN_DIARIO = "No se pudo anotar el envío en el teléfono; no se envió nada."
    const val MSG_SATURADA = "La red está saturada; se reintentará."
    const val MSG_VENCIO = "El envío venció sin entrar en la red."
    const val MSG_RECONSTRUIR = "La red descartó el envío por secuencia o vigencia obsoletas; se armará de nuevo."
    const val MSG_BAD_AUTH = "La cuenta que envía no pudo firmar la transacción."
    const val MSG_NO_ACCOUNT = "La cuenta que envía no existe en la red."
    const val MSG_SIM_FALLIDA = "Simulación fallida"

    private val AUTH_EXISTING = Regex("""Error\(\s*Auth\s*,\s*ExistingValue\s*\)""")
    private val AUTH_INVALID_INPUT = Regex("""Error\(\s*Auth\s*,\s*InvalidInput\s*\)""")
    private val AUTH_INVALID_ACTION = Regex("""Error\(\s*Auth\s*,\s*InvalidAction\s*\)""")
    private val AUTH_ANY = Regex("""Error\(\s*Auth\s*,""")
    private val CONTRACT_CODE = Regex("""Error\(\s*Contract\s*,\s*#(\d+)\s*\)""")

    /** Primer `Error(Tipo, Detalle)` del texto de una simulación fallida: nunca el texto completo ni XDR. */
    private val HOST_ERROR = Regex("""Error\(\w+, [^)]{1,60}\)""")

    /** Errores del Pool que dicen "este sobre no es válido": barrio inexistente, monto inválido, tip inválido. */
    private val POOL_INVALID_CODES = listOf(6, 7, 8)

    fun hostErrorFragment(message: String?): String? = message?.let { HOST_ERROR.find(it)?.value }

    /**
     * Clasifica el texto de una simulación fallida (modo enforce). El host ENCADENA frames: un fallo del
     * contrato aparece junto a `Error(Auth, InvalidAction)` del frame que lo envuelve, por eso el orden es:
     *
     * 1. los dos Auth explícitos (`ExistingValue`, `InvalidInput`);
     * 2. los códigos de contrato (#10, #13, #5, #4, luego #6/#7/#8) — ganan a un Auth genérico;
     * 3. `InvalidAction` → rechazo DEFINITIVO (único caso);
     * 4. cualquier otro `Error(Auth,` → transitorio;
     * 5. el resto → sin red / sin veredicto.
     */
    fun classifySimulation(error: String): SobreSubmit {
        if (AUTH_EXISTING.containsMatchIn(error)) return SobreSubmit.NonceUsado
        if (AUTH_INVALID_INPUT.containsMatchIn(error)) return SobreSubmit.Expirada

        val codes = CONTRACT_CODE.findAll(error).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        when {
            10 in codes -> return SobreSubmit.RechazadaTransitoria(MSG_10)
            13 in codes -> return SobreSubmit.RechazadaTransitoria(MSG_13)
            5 in codes -> return SobreSubmit.RechazadaTransitoria(MSG_5)
            4 in codes -> return SobreSubmit.RechazadaTransitoria(MSG_4)
        }
        for (pool in POOL_INVALID_CODES) {
            if (pool in codes) return SobreSubmit.RechazadaTransitoria("Sobre inválido para el Pool (#$pool)")
        }

        if (AUTH_INVALID_ACTION.containsMatchIn(error)) return SobreSubmit.RechazadaDefinitiva(MSG_AUTH)
        if (AUTH_ANY.containsMatchIn(error)) return SobreSubmit.RechazadaTransitoria(MSG_AUTH_OTRO)

        val fragment = hostErrorFragment(error)
        return SobreSubmit.SinRed(if (fragment != null) "$MSG_SIM_FALLIDA: $fragment" else MSG_SIM_FALLIDA)
    }

    /**
     * Clasifica el rechazo inmediato de `sendTransaction` (`ERROR`) por el código de resultado de la tx.
     * `null` = el RPC no entregó un resultado legible.
     */
    fun classifySend(code: TransactionResultCodeXdr?): SobreSubmit = when (code) {
        TransactionResultCodeXdr.txINSUFFICIENT_BALANCE,
        TransactionResultCodeXdr.txINSUFFICIENT_FEE,
        -> SobreSubmit.SinFondosFuente(MSG_SIN_XLM)

        TransactionResultCodeXdr.txBAD_SEQ,
        TransactionResultCodeXdr.txTOO_LATE,
        -> SobreSubmit.SinRed(MSG_RECONSTRUIR)

        TransactionResultCodeXdr.txBAD_AUTH -> SobreSubmit.RechazadaTransitoria(MSG_BAD_AUTH)
        TransactionResultCodeXdr.txNO_ACCOUNT -> SobreSubmit.RechazadaTransitoria(MSG_NO_ACCOUNT)
        else -> SobreSubmit.SinRed("La red rechazó el envío (${code?.name ?: "sin detalle"}).")
    }
}
