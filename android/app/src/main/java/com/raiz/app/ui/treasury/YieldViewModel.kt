package com.raiz.app.ui.treasury

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.BlendReserveStats
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.toStroops
import com.raiz.app.data.stellar.BlendClient
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de una acción on-chain (deposit/withdraw vía Pool) en la fuente de yield. */
sealed interface TreasuryAction {
    data object Idle : TreasuryAction
    data object Submitting : TreasuryAction
    data class Ok(val message: String) : TreasuryAction
    data class Failed(val message: String) : TreasuryAction
}

/**
 * Posición individual de un barrio en la fuente de yield (F1: Blend v2 vía
 * `yield_adapter`).
 *
 * @param barrioId            ID hex del barrio (64 chars).
 * @param nombre              Nombre legible (ej. "Centro Histórico").
 * @param depositadoStroops   Shares del barrio (bTokens, stroops 7 dec) — `Pool.get_vault_shares`.
 *                            Comparable 1:1 con USDC (misma convención que tenían las
 *                            shares del vault DeFindex: b_rate arranca en ~1.0).
 * @param valorActualStroops  Valor en USDC (stroops) — `Pool.get_vault_value`, ya viene
 *                             calculado on-chain (shares × bRate / 1e12), sin cómputo off-chain.
 * @param rendimientoStroops  Yield acumulado (stroops): (valorActual − depositado)
 *                            coercionado a ≥ 0.
 */
data class BarrioYieldItem(
    val barrioId: String,
    val nombre: String,
    val depositadoStroops: Long,
    val valorActualStroops: Long,
    val rendimientoStroops: Long,
)

data class YieldUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /**
     * TVL/utilización del pool USDC de Blend v2 completo (no solo la
     * posición de RAÍZ) — contexto de riesgo/liquidez. Fuente:
     * [BlendClient.getReserveData], lectura directa contra Blend.
     */
    val reserveStats: BlendReserveStats? = null,
    /**
     * APY en basis points desde `yield_adapter.apy_hint()` (F1). `null` si
     * el deploy F1 todavía no publicó `yield_adapter` en deployments.json.
     * Estimado y variable — se muestra como tal en la UI.
     */
    val apyBps: Int? = null,
    val amountInput: String = "",
    /** Barrio seleccionado para la acción admin (depositar/rescatar). */
    val selectedBarrioId: String = "",
    val action: TreasuryAction = TreasuryAction.Idle,
    /** Posición de cada barrio en la fuente de yield. Vacía durante la carga inicial. */
    val barriosYield: List<BarrioYieldItem> = emptyList(),
) {
    val selectedBarrioItem: BarrioYieldItem?
        get() = barriosYield.firstOrNull { it.barrioId == selectedBarrioId }

    /** Rendimiento agregado (stroops) de TODOS los barrios juntos. */
    val totalYieldStroops: Long
        get() = barriosYield.sumOf { it.rendimientoStroops }
}

/**
 * ViewModel de la pantalla "Tesorería que rinde".
 *
 * F1: la fuente de yield es Blend v2 directo tras el contrato propio
 * `yield_adapter` — ya no hay vault DeFindex ni API key REST para el APY.
 *
 * Fuentes de datos:
 *  - **Contexto del pool Blend** (TVL/utilización de TODO el pool, no solo
 *    RAÍZ) y **APY estimado** del adapter → [BlendClient] (dos lecturas
 *    on-chain independientes, ver su KDoc).
 *  - **Posición por barrio** (shares, valor actual) → `Pool` vía
 *    [SorobanClient.getVaultShares] / [SorobanClient.getVaultValue]. El
 *    Pool delega en el yield_adapter pero conserva nombre/firma — sin
 *    cambios de interfaz en la app.
 *  - **Depositar / rescatar** (sección "Mover fondos"): único camino es vía
 *    Pool ([SorobanClient.depositIdleToVault] / [SorobanClient.redeemFromVault]),
 *    firmado por la cuenta admin, para el barrio seleccionado en la UI.
 */
@HiltViewModel
class YieldViewModel @Inject constructor(
    private val blendClient: BlendClient,
    private val sorobanClient: SorobanClient,
    private val walletManager: WalletManager,
) : ViewModel() {

    private val _state = MutableStateFlow(
        YieldUiState(selectedBarrioId = DEMO_BARRIOS.keys.first()),
    )
    val state: StateFlow<YieldUiState> = _state.asStateFlow()

    init { refresh() }

    fun onAmountChange(input: String) {
        // Solo dígitos y un punto decimal.
        val clean = input.filter { it.isDigit() || it == '.' }
        _state.update { it.copy(amountInput = clean) }
    }

    fun onBarrioSelected(barrioId: String) {
        _state.update { it.copy(selectedBarrioId = barrioId) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            // ── 1. Contexto del pool Blend (TVL/utilización) + APY del adapter propio ──
            val reserveResult = blendClient.getReserveData()
            val apy = blendClient.getAdapterApyBps() // best-effort, null si F1 aún no desplegado

            val reserveStats = (reserveResult as? RaizResult.Success)?.data
            val reserveError = (reserveResult as? RaizResult.Error)?.message

            Log.i(
                TAG,
                "Blend pool: tvl=${reserveStats?.tvlStroops} util=${reserveStats?.utilizationBps}bps apyBps=$apy",
            )

            // ── 2. Posición de cada barrio en la fuente de yield ────────────────────
            // getVaultShares/getVaultValue leen (vía Pool) el storage/adapter — lectura
            // pura, con retry porque el RPC de testnet es flaky en ráfaga.
            val barriosYield = DEMO_BARRIOS.map { (barrioId, nombre) ->
                val shares = sharesWithRetry(barrioId, nombre)
                val valorActual = if (shares > 0L) valueWithRetry(barrioId, nombre) else 0L
                val rendimiento = (valorActual - shares).coerceAtLeast(0L)

                Log.i(
                    TAG,
                    "Barrio $nombre: shares=$shares valorActual=$valorActual rendimiento=$rendimiento",
                )

                BarrioYieldItem(
                    barrioId = barrioId,
                    nombre = nombre,
                    depositadoStroops = shares,
                    valorActualStroops = valorActual,
                    rendimientoStroops = rendimiento,
                )
            }

            _state.update {
                it.copy(
                    loading = false,
                    error = if (reserveStats == null) reserveError else null,
                    reserveStats = reserveStats,
                    apyBps = apy,
                    barriosYield = barriosYield,
                )
            }
        }
    }

    /**
     * Lee las shares de un barrio reintentando hasta [attempts] veces. El RPC de
     * testnet falla de forma transitoria en ráfaga; sin retry un barrio con fondo
     * podría mostrarse en 0 USDC. Solo cae a 0 tras agotar los intentos.
     */
    private suspend fun sharesWithRetry(barrioId: String, nombre: String, attempts: Int = 3): Long {
        repeat(attempts) { i ->
            when (val r = sorobanClient.getVaultShares(barrioId)) {
                is RaizResult.Success -> return r.data
                is RaizResult.Error -> {
                    Log.w(TAG, "getVaultShares($nombre) intento ${i + 1}/$attempts: ${r.message}")
                    if (i < attempts - 1) delay(900)
                }
            }
        }
        return 0L
    }

    /** Mismo patrón de retry que [sharesWithRetry], para `Pool.get_vault_value`. */
    private suspend fun valueWithRetry(barrioId: String, nombre: String, attempts: Int = 3): Long {
        repeat(attempts) { i ->
            when (val r = sorobanClient.getVaultValue(barrioId)) {
                is RaizResult.Success -> return r.data
                is RaizResult.Error -> {
                    Log.w(TAG, "getVaultValue($nombre) intento ${i + 1}/$attempts: ${r.message}")
                    if (i < attempts - 1) delay(900)
                }
            }
        }
        return 0L
    }

    /** Deposita el monto del input (USDC) en Blend, para el barrio seleccionado, firmando como admin. */
    fun deposit() {
        val amountStroops = _state.value.amountInput.toDoubleOrNull()?.toStroops() ?: 0L
        if (amountStroops <= 0L) {
            _state.update { it.copy(action = TreasuryAction.Failed("Ingresa un monto válido en USDC.")) }
            return
        }
        val barrioId = _state.value.selectedBarrioId
        viewModelScope.launch {
            _state.update { it.copy(action = TreasuryAction.Submitting) }
            val signer = walletManager.demoAdminKeyPair()
            if (signer == null) {
                _state.update {
                    it.copy(
                        action = TreasuryAction.Failed(
                            "Tesorería no configurada (falta raiz.admin.secret).",
                        ),
                    )
                }
                return@launch
            }
            when (val r = sorobanClient.depositIdleToVault(signer, barrioId, amountStroops)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Depósito OK: $amountStroops stroops → Blend (barrio $barrioId)")
                    _state.update {
                        it.copy(
                            action = TreasuryAction.Ok("Depósito confirmado on-chain"),
                            amountInput = "",
                        )
                    }
                    refresh()
                }
                is RaizResult.Error -> _state.update {
                    it.copy(action = TreasuryAction.Failed(humanError(r.message)))
                }
            }
        }
    }

    /** Rescata toda la posición de shares del barrio seleccionado. */
    fun withdrawAll() {
        val barrioId = _state.value.selectedBarrioId
        val shares = _state.value.selectedBarrioItem?.depositadoStroops ?: 0L
        if (shares <= 0L) {
            _state.update { it.copy(action = TreasuryAction.Failed("Este barrio no tiene posición que rescatar.")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(action = TreasuryAction.Submitting) }
            val signer = walletManager.demoAdminKeyPair()
            if (signer == null) {
                _state.update {
                    it.copy(
                        action = TreasuryAction.Failed(
                            "Tesorería no configurada (falta raiz.admin.secret).",
                        ),
                    )
                }
                return@launch
            }
            when (val r = sorobanClient.redeemFromVault(signer, barrioId, shares)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Rescate OK: $shares shares del barrio $barrioId")
                    _state.update {
                        it.copy(action = TreasuryAction.Ok("Rescate confirmado on-chain"))
                    }
                    refresh()
                }
                is RaizResult.Error -> _state.update {
                    it.copy(action = TreasuryAction.Failed(humanError(r.message)))
                }
            }
        }
    }

    fun clearAction() {
        _state.update { it.copy(action = TreasuryAction.Idle) }
    }

    private fun humanError(raw: String): String = when {
        "InsufficientBalance" in raw || "InvalidAmount" in raw || "balance" in raw.lowercase() ->
            "Saldo insuficiente de USDC en el fondo del barrio."
        "InsufficientLiquidity" in raw ->
            "Rompería el colchón líquido mínimo del barrio."
        "InsufficientShares" in raw ->
            "El barrio no tiene esas shares para rescatar."
        "VaultNotConfigured" in raw ->
            "El adapter de yield (Blend) no está configurado todavía."
        "Unauthorized" in raw ->
            "No autorizado: solo el admin o la tesorería del barrio pueden mover fondos."
        else -> raw
    }

    private companion object {
        const val TAG = "RAIZ"
        // Mapa hex → nombre legible de los 3 barrios del seed.
        // Mirror de RoleResolver.DEMO_BARRIOS (privado allá, duplicado aquí para
        // no introducir una dependencia circular con la capa data).
        val DEMO_BARRIOS: LinkedHashMap<String, String> = linkedMapOf(
            "ce47120000000000000000000000000000000000000000000000000000000001" to "Centro Histórico",
            "bba17e0000000000000000000000000000000000000000000000000000000002" to "Barrio Norte",
            "c057a9000000000000000000000000000000000000000000000000000000000a" to "Costa Vieja",
        )
    }
}
