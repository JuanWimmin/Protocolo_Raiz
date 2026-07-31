package com.raiz.app.data.model

// Los imports de serialización Soroban (Scv, SCVal) viven en SorobanClient.kt,
// donde se hacen las conversiones fromScVal()/toScVal(). Estos modelos son POJOs puros.

/*
 * RAÍZ v2 — Modelo de datos Kotlin
 *
 * Estas data classes son el espejo exacto de los structs Rust de los 4 contratos Soroban.
 * Convención:
 *   - Montos en USDC se manejan como Long (stroops, 7 decimales). 1 USDC = 10_000_000 stroops.
 *   - barrioId es el hex de un BytesN<32> (64 caracteres).
 *   - Las Address de Stellar se manejan como String (G... para cuentas, C... para contratos).
 *   - Cada tipo trae fromScVal() / toScVal() para serializar contra Soroban RPC.
 *
 * Helpers de conversión (USDC <-> stroops) y de puntos al final del archivo.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Constantes del dominio
// ─────────────────────────────────────────────────────────────────────────────

object RaizConstants {
    const val USDC_DECIMALS = 7
    const val USDC_STROOPS_PER_UNIT = 10_000_000L      // 1 USDC = 10^7 stroops

    const val POINTS_PER_STROOP_DIVISOR = 100_000L     // 1 punto por cada 0.01 USDC (100_000 stroops)
    const val DEFAULT_TIP_BPS = 200                    // 2% por defecto
    const val PROTOCOL_FEE_BPS = 50                    // 0.5% fee del protocolo
    const val QUORUM_PCT = 30                          // 30% de residentes para quórum
    const val BPS_DENOMINATOR = 10_000

    // IDs de contratos desplegados (se rellenan tras el deploy en Testnet)
    const val POOL_CONTRACT_ID = "C..."
    const val GOVERNANCE_CONTRACT_ID = "C..."
    const val TREASURY_CONTRACT_ID = "C..."
    const val REWARDS_CONTRACT_ID = "C..."
    const val USDC_SAC_ID = "C..."                     // Stellar Asset Contract de USDC
}

// ─────────────────────────────────────────────────────────────────────────────
// Contrato Pool
// ─────────────────────────────────────────────────────────────────────────────

/** Espejo de BarrioData en Pool. */
data class Barrio(
    val id: String,                 // hex de BytesN<32>
    val name: String,
    val poolBalanceStroops: Long,   // saldo del fondo en stroops USDC
    val totalCollectedStroops: Long,
    val txCount: Long,
    val uniqueTourists: Int,
    val treasuryContract: String    // Address C...
) {
    val poolBalanceUsdc: Double get() = poolBalanceStroops.toUsdc()
    val totalCollectedUsdc: Double get() = totalCollectedStroops.toUsdc()
}

/** Categorías de comercio para el mapa y los filtros. */
enum class MerchantCategory(val symbol: String, val label: String) {
    CAFE("cafe", "Café"),
    RESTAURANTE("restaurante", "Restaurante"),
    ARTESANIA("artesania", "Artesanía"),
    TIENDA("tienda", "Tienda"),
    CULTURA("cultura", "Cultura"),
    HOSPEDAJE("hospedaje", "Hospedaje"),
    OTRO("otro", "Otro");

    companion object {
        fun fromSymbol(s: String) = entries.firstOrNull { it.symbol == s } ?: OTRO
    }
}

/** Espejo de MerchantData en Pool. lat/lng vienen como int * 1e6 desde el contrato. */
data class Merchant(
    val address: String,            // G... cuenta del comercio
    val name: String,
    val barrioId: String,
    val verified: Boolean,
    val latE6: Int,                 // latitud * 1_000_000
    val lngE6: Int,                 // longitud * 1_000_000
    val category: MerchantCategory
) {
    val lat: Double get() = latE6 / 1_000_000.0
    val lng: Double get() = lngE6 / 1_000_000.0

    companion object {
        fun encodeLat(lat: Double): Int = (lat * 1_000_000).toInt()
        fun encodeLng(lng: Double): Int = (lng * 1_000_000).toInt()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contrato Governance
// ─────────────────────────────────────────────────────────────────────────────

/** Espejo de ResidentToken (soulbound — no transferible). */
data class ResidentToken(
    val resident: String,           // G...
    val barrioId: String,
    val issuedAt: Long              // ledger timestamp (segundos unix)
)

enum class ProposalStatus {
    ACTIVE, PASSED, REJECTED, EXECUTED;

    companion object {
        fun fromSymbol(s: String) = when (s.lowercase()) {
            "active" -> ACTIVE
            "passed" -> PASSED
            "rejected" -> REJECTED
            "executed" -> EXECUTED
            else -> ACTIVE
        }
    }
}

/** Espejo de Proposal en Governance. */
data class Proposal(
    val id: Long,
    val barrioId: String,
    val proposer: String,           // G...
    val description: String,
    val amountStroops: Long,        // monto solicitado del pool
    val recipient: String,          // G... beneficiario si pasa
    val votesFor: Int,
    val votesAgainst: Int,
    val createdAt: Long,
    val closesAt: Long,
    val status: ProposalStatus
) {
    val amountUsdc: Double get() = amountStroops.toUsdc()
    val totalVotes: Int get() = votesFor + votesAgainst
    val supportPct: Int get() = if (totalVotes == 0) 0 else (votesFor * 100 / totalVotes)

    /** Cálculo de quórum: necesita knowledge de residentCount del barrio (se pasa aparte). */
    fun reachedQuorum(residentCount: Int): Boolean =
        residentCount > 0 && (totalVotes * 100 / residentCount) >= RaizConstants.QUORUM_PCT

    fun isOpen(nowUnix: Long): Boolean = status == ProposalStatus.ACTIVE && nowUnix < closesAt
}

// ─────────────────────────────────────────────────────────────────────────────
// Contrato Treasury
// ─────────────────────────────────────────────────────────────────────────────

/** Espejo de Execution en Treasury. */
data class Execution(
    val proposalId: Long,
    val barrioId: String,
    val amountStroops: Long,
    val recipient: String,
    val executedAt: Long,
    val txHash: String              // hex de BytesN<32>
) {
    val amountUsdc: Double get() = amountStroops.toUsdc()
}

// ─────────────────────────────────────────────────────────────────────────────
// Contrato Rewards
// ─────────────────────────────────────────────────────────────────────────────

/** Espejo de Reward en Rewards. */
data class Reward(
    val id: Long,
    val barrioId: String,
    val name: String,               // "Mochila artesanal wayuu"
    val artisan: String,            // G... quién entrega
    val pointsCost: Long,
    val stock: Int,
    val imageRef: String            // URL (MVP) o ipfs:// (v2)
)

/** Espejo de Redemption en Rewards. */
data class Redemption(
    val id: Long,
    val tourist: String,
    val rewardId: Long,
    val redeemedAt: Long,
    val claimed: Boolean
)

/** Saldo de puntos de un turista (no es un token transferible). */
data class PointsBalance(
    val tourist: String,
    val points: Long
) {
    /** Cuántos puntos faltan para un reward dado. */
    fun shortfallFor(reward: Reward): Long = (reward.pointsCost - points).coerceAtLeast(0)
    fun canAfford(reward: Reward): Boolean = points >= reward.pointsCost
}

// ─────────────────────────────────────────────────────────────────────────────
// Modelos de UI / agregados (no viven on-chain, se componen en la app)
// ─────────────────────────────────────────────────────────────────────────────

/** Lo que se muestra al confirmar un pago en la pantalla de pago. */
data class PaymentPreview(
    val merchant: Merchant,
    val baseAmountStroops: Long,
    val tipBps: Int
) {
    val tipStroops: Long get() = baseAmountStroops * tipBps / RaizConstants.BPS_DENOMINATOR
    val totalStroops: Long get() = baseAmountStroops + tipStroops
    val pointsToEarn: Long get() = tipStroops / RaizConstants.POINTS_PER_STROOP_DIVISOR

    val baseAmountUsdc: Double get() = baseAmountStroops.toUsdc()
    val tipUsdc: Double get() = tipStroops.toUsdc()
    val totalUsdc: Double get() = totalStroops.toUsdc()
}

/** Pin del mapa: merchant + su aporte acumulado para mostrar al tocar. */
data class MerchantMapPin(
    val merchant: Merchant,
    val contributedToBarrioStroops: Long
)

/** Resumen del dashboard de transparencia de un barrio. */
data class BarrioDashboard(
    val barrio: Barrio,
    val residentCount: Int,
    val activeProposals: List<Proposal>,
    val recentExecutions: List<Execution>,
    val merchantCount: Int
) {
    val participationRate: Int get() =
        if (barrio.txCount == 0L) 0 else (barrio.uniqueTourists * 100 / barrio.txCount.toInt())
}

/** Estado de la wallet del turista. */
data class WalletState(
    val publicKey: String,          // G...
    val usdcBalanceStroops: Long,
    val xlmBalanceStroops: Long,
    val points: Long,
    val authMethod: WalletAuthMethod
) {
    val usdcBalance: Double get() = usdcBalanceStroops.toUsdc()
}

enum class WalletAuthMethod { PASSKEY, SEED_PHRASE }

// ─────────────────────────────────────────────────────────────────────────────
// Wrappers de resultado (para manejar errores de Soroban / red sin excepciones)
// ─────────────────────────────────────────────────────────────────────────────

sealed class RaizResult<out T> {
    data class Success<T>(val data: T) : RaizResult<T>()
    data class Error(val code: RaizErrorCode, val message: String) : RaizResult<Nothing>()
}

enum class RaizErrorCode {
    INSUFFICIENT_BALANCE,
    INSUFFICIENT_POINTS,
    OUT_OF_STOCK,
    NOT_A_RESIDENT,
    ALREADY_VOTED,
    PROPOSAL_CLOSED,
    QUORUM_NOT_REACHED,
    UNAUTHORIZED,
    NETWORK_ERROR,
    SIMULATION_FAILED,
    UNKNOWN
}

// ─────────────────────────────────────────────────────────────────────────────
// Yield del fondo (F1: Blend directo vía YieldAdapter — espejo del contrato
// yield_adapter; ver raiz_v2_spec_contratos.md "Contrato 5")
// ─────────────────────────────────────────────────────────────────────────────

/** Posición de yield de un barrio vía el YieldAdapter (shares = bTokens de Blend). */
data class YieldPosition(
    val barrioId: String,      // hex de 64 chars
    val shares: Long,          // bTokens del barrio (contabilidad del adapter)
    val valueUsdc: Long,       // valor actual en stroops de USDC (shares × bRate / 1e12)
    val apyBps: Int            // APY estimado en basis points (informativo, variable)
)

// ─────────────────────────────────────────────────────────────────────────────
// Helpers de conversión
// ─────────────────────────────────────────────────────────────────────────────

/** stroops (Long) -> USDC (Double). 10^7 stroops = 1 USDC. */
fun Long.toUsdc(): Double = this / RaizConstants.USDC_STROOPS_PER_UNIT.toDouble()

/** USDC (Double) -> stroops (Long). */
fun Double.toStroops(): Long = (this * RaizConstants.USDC_STROOPS_PER_UNIT).toLong()

/** Puntos ganados por un tip dado (en stroops). 1 punto por cada 0.01 USDC. */
fun pointsForTip(tipStroops: Long): Long = tipStroops / RaizConstants.POINTS_PER_STROOP_DIVISOR

/** Formatea stroops como string USDC legible: 42_840_000 -> "4.284 USDC". */
fun Long.formatUsdc(): String {
    val trimmed = "%.3f".format(this.toUsdc()).trimEnd('0').trimEnd('.')
    return "$trimmed USDC"
}
