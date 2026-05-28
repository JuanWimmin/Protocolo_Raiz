package com.raiz.app.data.model

/**
 * Estado de la wallet del turista (solo en memoria + DataStore — la seed
 * phrase real vive en Android Keystore cuando el método es SEED_PHRASE,
 * y en el TEE/passkey provider cuando es PASSKEY).
 */
data class WalletState(
    val publicKey: String,                // G... cuenta Stellar
    val usdcBalanceStroops: Long,         // saldo USDC en stroops
    val xlmBalanceStroops: Long,          // saldo XLM en stroops (para fees)
    val points: Long,                     // puntos acumulados (read del contrato Rewards)
    val authMethod: WalletAuthMethod,
) {
    val usdcBalance: Double get() = usdcBalanceStroops.toUsdc()
}

enum class WalletAuthMethod { PASSKEY, SEED_PHRASE }
