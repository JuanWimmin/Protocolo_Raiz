package com.raiz.app.data.stellar

import android.util.Log
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.RoleContext
import com.raiz.app.data.model.UserRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detecta el rol del usuario consultando on-chain:
 *
 *   1. ¿Tiene ResidentToken en Governance? → RESIDENT (priorizado)
 *   2. ¿Está registrado como comercio? → MERCHANT. Primero la consulta directa
 *      `get_merchant(address)` (lee UNA entrada: no depende de que el resto de comercios del
 *      barrio estén vivos) y, si no responde, el recorrido por los barrios con list_merchants.
 *   3. Si nada → TOURIST
 *
 * Para el demo del hackathon usamos los 3 barrios del seed
 * (Centro Histórico, Barrio Norte, Costa Vieja). En producción esto
 * cambiaría a un índice global de barrios o solo el barrio activo.
 *
 * El resolver es idempotente; cachea el resultado entre llamadas para el
 * mismo address para evitar re-consultas innecesarias. Si alguna consulta FALLA
 * (red, RPC) el TOURIST final es un "no sé", no un hecho: se devuelve pero NO se
 * cachea, y la siguiente llamada vuelve a consultar.
 */
@Singleton
class RoleResolver @Inject constructor(
    private val sorobanClient: SorobanClient,
) {
    private val cache = mutableMapOf<String, RoleContext>()

    suspend fun resolve(address: String): RoleContext {
        cache[address]?.let { return it }

        // `uncertain`: alguna consulta falló por algo distinto de "no existe". Entonces el TOURIST
        // del final no es un hecho sino un "no sé": se devuelve pero NO se cachea, para que la
        // siguiente llamada (p. ej. el refresco del perfil) lo reintente. Cachearlo dejaba a un
        // comercio registrado viendo "Registrarme como comerciante" hasta reiniciar la app.
        var uncertain = false

        // 1. ¿Es residente?
        when (val r = sorobanClient.getResident(address)) {
            is RaizResult.Success -> {
                val ctx = RoleContext(
                    role = UserRole.RESIDENT,
                    barrioId = r.data.barrioId,
                    barrioName = barrioNameFor(r.data.barrioId),
                )
                Log.i(TAG, "Rol detectado: RESIDENT @ ${ctx.barrioName}")
                cache[address] = ctx
                return ctx
            }
            is RaizResult.Error -> {
                // NOT_FOUND es lo esperado para no-residentes. Otros errores
                // los tratamos como "no determinable" pero seguimos con
                // detección merchant para no bloquear.
                if (r.code != RaizErrorCode.NOT_FOUND) {
                    Log.w(TAG, "getResident falló: ${r.message}")
                    uncertain = true
                }
            }
        }

        // 2a. ¿Es comerciante? Consulta directa get_merchant(address): lee una sola entrada, así
        //     que un comercio registrado se reconoce aunque otro Merchant(..) del barrio esté
        //     archivado o list_merchants falle. NOT_FOUND = no está registrado; cualquier otro
        //     error = no se sabe (no cachear) y se sigue con el recorrido por barrios.
        when (val m = sorobanClient.getMerchant(address)) {
            is RaizResult.Success -> {
                val ctx = RoleContext(
                    role = UserRole.MERCHANT,
                    barrioId = m.data.barrioId,
                    barrioName = barrioNameFor(m.data.barrioId),
                )
                Log.i(TAG, "Rol detectado: MERCHANT @ ${ctx.barrioName} (get_merchant)")
                cache[address] = ctx
                return ctx
            }
            is RaizResult.Error -> if (m.code != RaizErrorCode.NOT_FOUND) {
                Log.w(TAG, "getMerchant falló: ${m.message}")
                uncertain = true
            }
        }

        // 2b. Respaldo: itera los barrios ON-CHAIN (list_barrios). Si el
        //     contrato aún no expone list_barrios (deploy previo) o lo devuelve
        //     vacío, cae al fallback de los barrios del seed.
        val barrioIds = when (val lb = sorobanClient.listBarrios()) {
            is RaizResult.Success -> lb.data.ifEmpty { DEMO_BARRIOS.keys.toList() }
            is RaizResult.Error -> DEMO_BARRIOS.keys.toList()
        }
        for (barrioId in barrioIds) {
            when (val r = sorobanClient.listMerchants(barrioId)) {
                is RaizResult.Success -> {
                    val isMerchant = r.data.any { it.address == address }
                    if (isMerchant) {
                        val barrioName = barrioNameFor(barrioId)
                        val ctx = RoleContext(
                            role = UserRole.MERCHANT,
                            barrioId = barrioId,
                            barrioName = barrioName,
                        )
                        Log.i(TAG, "Rol detectado: MERCHANT @ $barrioName")
                        cache[address] = ctx
                        return ctx
                    }
                }
                is RaizResult.Error -> {
                    Log.w(TAG, "listMerchants(${barrioNameFor(barrioId)}) falló: ${r.message}")
                    uncertain = true
                }
            }
        }

        // 3. Default
        val ctx = RoleContext(role = UserRole.TOURIST)
        if (uncertain) {
            Log.w(TAG, "Rol no determinable (alguna consulta falló): TOURIST provisional, sin cachear")
            return ctx
        }
        Log.i(TAG, "Rol detectado: TOURIST")
        cache[address] = ctx
        return ctx
    }

    /** Forzar invalidación de cache (p.ej. tras logout o swap de wallet). */
    fun invalidate() {
        cache.clear()
    }

    private fun barrioNameFor(barrioId: String): String =
        DEMO_BARRIOS[barrioId] ?: barrioId.take(8) + "…"

    private companion object {
        const val TAG = "RAIZ"
        // Mapa hex → nombre legible. Los 3 barrios del seed.
        val DEMO_BARRIOS: LinkedHashMap<String, String> = linkedMapOf(
            "ce47120000000000000000000000000000000000000000000000000000000001" to "Centro Histórico",
            "bba17e0000000000000000000000000000000000000000000000000000000002" to "Barrio Norte",
            "c057a9000000000000000000000000000000000000000000000000000000000a" to "Costa Vieja",
        )
    }
}
