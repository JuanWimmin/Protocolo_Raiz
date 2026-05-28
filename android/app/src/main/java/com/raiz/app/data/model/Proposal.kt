package com.raiz.app.data.model

/** Espejo de ProposalStatus del contrato Governance. */
enum class ProposalStatus {
    ACTIVE, PASSED, REJECTED, EXECUTED;

    companion object {
        /** Symbol → enum. Acepta "Active", "active", etc. */
        fun fromSymbol(s: String) = when (s.lowercase()) {
            "active" -> ACTIVE
            "passed" -> PASSED
            "rejected" -> REJECTED
            "executed" -> EXECUTED
            else -> ACTIVE
        }
    }
}

/** Espejo del struct Proposal en Governance. */
data class Proposal(
    val id: Long,
    val barrioId: String,
    val proposer: String,
    val description: String,
    val amountStroops: Long,
    val recipient: String,
    val votesFor: Int,
    val votesAgainst: Int,
    val createdAt: Long,
    val closesAt: Long,
    val status: ProposalStatus,
) {
    val amountUsdc: Double get() = amountStroops.toUsdc()
    val totalVotes: Int get() = votesFor + votesAgainst
    val supportPct: Int get() = if (totalVotes == 0) 0 else (votesFor * 100 / totalVotes)
    fun reachedQuorum(residentCount: Int): Boolean =
        residentCount > 0 && (totalVotes * 100 / residentCount) >= RaizConstants.QUORUM_PCT
    fun isOpen(nowUnix: Long): Boolean = status == ProposalStatus.ACTIVE && nowUnix < closesAt
}

/** Espejo de ResidentToken (soulbound). */
data class ResidentToken(
    val resident: String,
    val barrioId: String,
    val issuedAt: Long,
)
