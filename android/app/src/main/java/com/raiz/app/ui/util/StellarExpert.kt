package com.raiz.app.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Helper centralizado para construir y abrir URLs de Stellar Expert (testnet).
 *
 * Uso típico:
 *   StellarExpert.open(context, StellarExpert.txUrl(hash))
 *   StellarExpert.open(context, StellarExpert.addressUrl(address))
 *   StellarExpert.open(context, StellarExpert.contractUrl(contractId))
 *
 * Nada en esta clase debe saber de Compose ni de ViewModels — es pura utilidad
 * de navegación external al explorador de Stellar.
 */
object StellarExpert {

    /** Base URL del explorador de testnet. */
    const val BASE = "https://stellar.expert/explorer/testnet"

    /**
     * URL de una transacción Stellar por su hash.
     * El hash debe ser un txHash de Horizon (64 hex chars).
     * Los txHash de contratos Soroban calculados on-chain no son buscables aquí.
     */
    fun txUrl(hash: String): String = "$BASE/tx/$hash"

    /**
     * URL de una cuenta o contrato según el prefijo de la dirección:
     *   - C… → ruta `/contract/` (smart account passkey o contrato Soroban)
     *   - G… → ruta `/account/` (cuenta clásica Ed25519)
     */
    fun addressUrl(addr: String): String =
        "$BASE/${if (addr.startsWith("C")) "contract" else "account"}/$addr"

    /**
     * URL explícita de un contrato Soroban (C…).
     * Alias semántico de [addressUrl] para los puntos del código donde sabemos
     * que la dirección es un contrato, no una cuenta clásica.
     */
    fun contractUrl(c: String): String = "$BASE/contract/$c"

    /**
     * Lanza un Intent VIEW hacia la URL indicada usando el navegador del sistema.
     * Envuelto en try/catch porque algunos dispositivos no tienen navegador
     * por defecto y lanzarían ActivityNotFoundException.
     */
    fun open(context: Context, url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (e: Exception) {
            Log.w("RAIZ", "StellarExpert: no se pudo abrir el navegador — $url: ${e.message}")
        }
    }
}
