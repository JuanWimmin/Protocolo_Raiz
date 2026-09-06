package com.raiz.app.data.relayer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Espejo Kotlin del contrato HTTP de `raiz-relayer` (D1 del SOW Instaward:
 * la app ya no firma flujos admin con la clave en el APK, se los pide a este
 * servicio). Fuente de verdad: `raiz-relayer/README.md` (sección Endpoints),
 * `raiz-relayer/src/types.ts` y `raiz-relayer/src/errors.ts`.
 *
 * Toda respuesta es
 *   `{ ok: true, txHash, ledger, ...extras }`
 * o
 *   `{ ok: false, error: { code, message, retryable, details? } }`
 *
 * `ignoreUnknownKeys = true` (ver [RelayerJson]) permite decodificar cualquier
 * respuesta contra [RelayerEnvelope] aunque traiga campos extra específicos
 * de un endpoint (p. ej. `merchant` solo aparece en register-merchant).
 */

/**
 * Configuración `Json` ÚNICA para hablar con el relayer: la usan el
 * `HttpClient` de `di/DataModule.kt` (serializar los bodies), [RelayerClient]
 * (decodificar las respuestas a mano, para que un 502 `text/html` o un 200
 * vacío sean un `NETWORK_ERROR` y no una excepción) y `RelayerClientTest`.
 *
 * - `ignoreUnknownKeys`: el relayer puede añadir campos sin romper la app.
 * - `explicitNulls = false`: los campos opcionales ausentes decodifican a
 *   `null` y los `null` no se serializan en los requests.
 */
val RelayerJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class RelayerErrorBody(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: JsonObject? = null,
)

@Serializable
data class RelayerEnvelope(
    val ok: Boolean,
    val txHash: String? = null,
    val ledger: Long? = null,
    val error: RelayerErrorBody? = null,
    // Solo /v1/faucet.
    val amountStroops: String? = null,
    val asset: String? = null,
    val method: String? = null,
    // Solo /v1/register-merchant.
    val merchant: JsonObject? = null,
)

@Serializable
data class RelayerFaucetInfo(
    val enabled: Boolean,
    val amountStroops: String,
    val adminUsdcStroops: String,
    val remainingToday: Int,
)

/** Respuesta de `GET /v1/health` — feature-flag de la app para los flujos admin. */
@Serializable
data class RelayerHealth(
    val ok: Boolean,
    val network: String,
    val protocolVersion: Int,
    val admin: String,
    val contracts: Map<String, String>,
    val faucet: RelayerFaucetInfo,
    val limits: Map<String, Int>,
    val vaultEndpoints: Boolean,
    val queue: JsonObject? = null,
    val version: String,
    val uptimeSeconds: Long,
)

// ── Requests ─────────────────────────────────────────────────────────────

/** `POST /v1/register-merchant` → `Pool.register_merchant(MerchantData)`. */
@Serializable
data class RegisterMerchantRequest(
    val address: String,
    val name: String,
    val barrioId: String,
    val latE6: Int,
    val lngE6: Int,
    val category: String,
)

/** `POST /v1/mint-resident` → `Governance.mint_resident(barrio_admin, resident, barrio_id)`. */
@Serializable
data class MintResidentRequest(
    val address: String,
    val barrioId: String,
)

/** `POST /v1/faucet` → 20 USDC de Blend a `address` (G… payment / C… sac_transfer). */
@Serializable
data class FaucetRequest(
    val address: String,
)

/** `POST /v1/vault/deposit` → `Pool.deposit_idle_to_vault(admin, barrio_id, amount)`. */
@Serializable
data class VaultDepositRequest(
    val barrioId: String,
    /** i128 en stroops, como string decimal (ver README § vault). */
    val amountStroops: String,
)

/** `POST /v1/vault/redeem` → `Pool.redeem_from_vault(admin, barrio_id, shares)`. */
@Serializable
data class VaultRedeemRequest(
    val barrioId: String,
    /** i128, como string decimal. */
    val shares: String,
)
