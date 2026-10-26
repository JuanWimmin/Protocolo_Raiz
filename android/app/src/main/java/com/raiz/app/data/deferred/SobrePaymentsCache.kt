package com.raiz.app.data.deferred

import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizResult

/**
 * Pagos del Pool de cada pagador, leídos UNA vez por pasada y compartidos por todos los sobres de ese pagador. Cada
 * sobre pide la cobertura que necesita ([payments] con su `sinceLedger`):
 *  - una lectura que ya llega tan atrás se reutiliza;
 *  - si el sobre necesita MÁS atrás, se vuelve a leer desde ahí (la lectura nueva incluye la anterior);
 *  - un fallo se recuerda para esa cobertura y las más largas (no se repite en la pasada), pero NO bloquea a los
 *    sobres más recientes: pedir menos atrás puede funcionar donde pedir más no (un sobre más viejo que la retención del
 *    RPC no se puede comprobar jamás; los demás sí).
 * Quien recorre varios sobres de un pagador los ordena del más antiguo al más reciente: la primera lectura (la más
 * ancha) sirve a todos los demás.
 *
 * No es seguro entre hilos: vive dentro de una pasada.
 */
class SobrePaymentsCache(
    private val read: suspend (payer: String, sinceLedger: Long) -> RaizResult<List<PaymentRecord>>,
) {
    private class Lectura(val sinceLedger: Long, val payments: List<PaymentRecord>)

    private val lecturas = HashMap<String, Lectura>()
    private val fallos = HashMap<String, Long>()

    /**
     * Pagos de [payer] COMPLETOS desde [sinceLedger] hasta el último ledger, o `null` si no se pudo garantizar esa lectura
     * (red caída, ledger fuera de la retención del RPC, lectura incompleta): `null` es "no se pudo comprobar", nunca "no hay".
     */
    suspend fun payments(payer: String, sinceLedger: Long): List<PaymentRecord>? {
        lecturas[payer]?.let { if (it.sinceLedger <= sinceLedger) return it.payments }
        fallos[payer]?.let { if (sinceLedger <= it) return null }
        return when (val r = read(payer, sinceLedger)) {
            is RaizResult.Success -> {
                lecturas[payer] = Lectura(sinceLedger, r.data)
                r.data
            }
            is RaizResult.Error -> {
                fallos[payer] = maxOf(fallos[payer] ?: Long.MIN_VALUE, sinceLedger)
                null
            }
        }
    }
}
