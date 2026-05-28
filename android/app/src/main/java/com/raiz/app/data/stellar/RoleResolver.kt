package com.raiz.app.data.stellar

import android.util.Log
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.RoleContext
import com.raiz.app.data.model.UserRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detecta el rol del usuario consultando on-chain:
 *
 *   1. ¿Tiene ResidentToken en Governance? → RESIDENT (priorizado)
 *   2. ¿Su address aparece en alguno de los barrios via list_merchants? → MERCHANT
 *   3. Si nada → TOURIST
 *
 * Para el demo del hackathon usamos los 3 barrios del seed
 * (Centro Histórico, Barrio Norte, Costa Vieja). En producción esto
 * cambiaría a un índice global de barrios o solo el barrio activo.
 *
 * El resolver es idempotente; cachea el resultado entre llamadas para el
 * mismo address para evitar re-consultas innecesarias.
 */
@Singleton
class RoleResolver @Inject constructor(
    private val sorobanClient: SorobanClient,
) {
    private val cache = mutableMapOf<String, RoleContext>()

    suspend fun resolve(address: String): RoleContext {
        cache[address]?.let { return it }

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
            }
        }

        // 2. ¿Es comerciante en alguno de los barrios conocidos?
        for ((barrioId, barrioName) in DEMO_BARRIOS) {
            when (val r = sorobanClient.listMerchants(barrioId)) {
                is RaizResult.Success -> {
                    val isMerchant = r.data.any { it.address == address }
                    if (isMerchant) {
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
                is RaizResult.Error -> Log.w(TAG, "listMerchants($barrioName) falló: ${r.message}")
            }
        }

        // 3. Default
        val ctx = RoleContext(role = UserRole.TOURIST)
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
