package com.raiz.app.data.model

/**
 * Rol del usuario en RAÍZ. Se detecta automáticamente consultando los
 * contratos:
 *   - RESIDENT: tiene `ResidentToken` minteado en Governance (soulbound).
 *   - MERCHANT: su address aparece en `Pool.list_merchants(barrio)` de
 *     algún barrio registrado.
 *   - TOURIST: cualquier otro caso (por defecto). Puede pagar con tip,
 *     acumular puntos, canjear premios.
 *
 * Un mismo address podría ser MERCHANT y RESIDENT a la vez en teoría
 * (un comerciante residente del barrio). Si tuviera ambos, priorizamos
 * RESIDENT porque la sección de gobernanza es más activa.
 */
enum class UserRole {
    TOURIST,
    RESIDENT,
    MERCHANT,
    ;

    val label: String
        get() = when (this) {
            TOURIST -> "Turista"
            RESIDENT -> "Residente del barrio"
            MERCHANT -> "Comerciante"
        }
}

/**
 * Contexto que acompaña al rol detectado — útil para la UI condicional.
 *   - barrioId: el barrio al que pertenece (residente o comerciante).
 *   - barrioName: nombre legible del barrio (Centro Histórico, etc.).
 */
data class RoleContext(
    val role: UserRole,
    val barrioId: String? = null,
    val barrioName: String? = null,
)
