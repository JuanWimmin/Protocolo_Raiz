package com.raiz.app.ui.treasury

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.VaultPosition
import com.raiz.app.data.model.VaultStats
import com.raiz.app.data.model.toStroops
import com.raiz.app.data.stellar.DefindexClient
import com.raiz.app.data.stellar.DeploymentsLoader
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

/** Estado de una acción on-chain (deposit/withdraw) en el vault. */
sealed interface TreasuryAction {
    data object Idle : TreasuryAction
    data object Submitting : TreasuryAction
    data class Ok(val message: String) : TreasuryAction
    data class Failed(val message: String) : TreasuryAction
}

/**
 * Posición individual de un barrio en el vault DeFindex.
 *
 * @param barrioId            ID hex del barrio (64 chars).
 * @param nombre              Nombre legible (ej. "Centro Histórico").
 * @param depositadoStroops   Shares del barrio en el vault (en stroops, 7 dec). Dado
 *                            que el vault arranca a precio 1.0 (10^7 stroops/share),
 *                            las shares minteadas equivalen al USDC depositado.
 * @param valorActualStroops  Valor en USDC (stroops): shares × pricePerShare / 10^7.
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
    val stats: VaultStats? = null,
    /** Posición de la reserva del protocolo (cuenta admin) en el vault. */
    val position: VaultPosition? = null,
    /** APY en basis points (1500 = 15%). null si no hay key/endpoint. */
    val apyBps: Int? = null,
    val amountInput: String = "",
    val action: TreasuryAction = TreasuryAction.Idle,
    /** Posición de cada barrio en el vault. Vacía durante la carga inicial. */
    val barriosYield: List<BarrioYieldItem> = emptyList(),
) {
    /**
     * Rendimiento generado (stroops) de la RESERVA DEL PROTOCOLO (tesorería admin):
     * valor actual − shares (las shares minteadas ≈ USDC depositado a precio 1.0).
     */
    val yieldStroops: Long
        get() = ((position?.currentValueStroops ?: 0L) - (position?.shares ?: 0L))
            .coerceAtLeast(0L)
}

/**
 * ViewModel de la pantalla "Tesorería que rinde".
 *
 * Carga los stats globales del vault USDC de DeFindex (APY, TVL, precio por
 * share) y, para cada uno de los 3 barrios del demo, la posición individual
 * en el vault a través de [SorobanClient.getVaultShares].
 *
 * La reserva del protocolo (cuenta admin) sigue siendo gestionable desde la
 * sección secundaria "Reserva del protocolo" en la pantalla.
 *
 * NOTA sobre el cálculo de valorActual: se hace off-chain multiplicando
 * shares × pricePerShareStroops / 10^7 con BigInteger para evitar overflow.
 * NO se usa getVaultValue (que internamente llama a get_asset_amounts_per_shares
 * del vault) para evitar el gotcha de footprint de restore → "Signer required
 * for write call" cuando las entradas tienen TTL expirado.
 */
@HiltViewModel
class YieldViewModel @Inject constructor(
    private val defindexClient: DefindexClient,
    private val sorobanClient: SorobanClient,
    private val walletManager: WalletManager,
    private val deploymentsLoader: DeploymentsLoader,
) : ViewModel() {

    /** Firmante/holder de la reserva = cuenta admin del protocolo. */
    private val treasuryAddress: String by lazy { deploymentsLoader.load().admin }

    private val _state = MutableStateFlow(YieldUiState())
    val state: StateFlow<YieldUiState> = _state.asStateFlow()

    init { refresh() }

    fun onAmountChange(input: String) {
        // Solo dígitos y un punto decimal.
        val clean = input.filter { it.isDigit() || it == '.' }
        _state.update { it.copy(amountInput = clean) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            // ── 1. Stats globales del vault: TVL, oferta total de shares, precio por share ──
            val statsResult = defindexClient.getVaultStats()
            val positionResult = defindexClient.getPosition(treasuryAddress)
            val apy = defindexClient.getApyBps() // best-effort, puede ser null

            val stats = (statsResult as? RaizResult.Success)?.data
            val position = (positionResult as? RaizResult.Success)?.data
            val firstError = (statsResult as? RaizResult.Error)?.message
                ?: (positionResult as? RaizResult.Error)?.message

            // Precio por share en stroops. Si no hay stats usamos 1.0 (= 10^7) para no
            // dividir por cero y mostrar valores coherentes.
            val pricePerShare = stats?.pricePerShareStroops ?: RaizConstants.USDC_STROOPS_PER_UNIT

            Log.i(
                TAG,
                "Yield global: tvl=${stats?.tvlStroops} pps=$pricePerShare apyBps=$apy",
            )

            // ── 2. Posición de cada barrio en el vault ────────────────────────────────
            // getVaultShares() lee directamente del storage del contrato Pool (storage key
            // VaultShares(barrio_id) → i128). Es lectura pura: no llama a ninguna función
            // del vault de DeFindex, así que no puede caer en el gotcha de "write call".
            val barriosYield = DEMO_BARRIOS.map { (barrioId, nombre) ->
                // Reintenta por barrio: el RPC de testnet es flaky en ráfaga y un
                // fallo transitorio NO debe mostrar 0 USDC del fondo del barrio.
                val shares = sharesWithRetry(barrioId, nombre)

                // valorActual = shares × pricePerShare / 10^7.
                // BigInteger para evitar overflow en la multiplicación de dos Long grandes.
                val valorActual = if (shares > 0L) {
                    (shares.toBigInteger() * pricePerShare.toBigInteger() /
                        RaizConstants.USDC_STROOPS_PER_UNIT.toBigInteger()).toLong()
                } else {
                    0L
                }

                // Rendimiento = valor actual − shares depositadas (coercionado a ≥ 0).
                // Las shares minteadas ≈ USDC depositado porque el vault arranca a precio 1.0;
                // conforme el precio sube, la diferencia es el yield acumulado.
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
                    error = if (stats == null) firstError else null,
                    stats = stats,
                    position = position,
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

    /** Deposita el monto del input (USDC) en el vault firmando como tesorería admin. */
    fun deposit() {
        val amountStroops = _state.value.amountInput.toDoubleOrNull()?.toStroops() ?: 0L
        if (amountStroops <= 0L) {
            _state.update { it.copy(action = TreasuryAction.Failed("Ingresa un monto válido en USDC.")) }
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
            when (val r = defindexClient.deposit(signer, amountStroops)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Depósito OK: ${r.data} shares minteadas")
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

    /** Rescata toda la posición de shares de la reserva del protocolo. */
    fun withdrawAll() {
        val shares = _state.value.position?.shares ?: 0L
        if (shares <= 0L) {
            _state.update { it.copy(action = TreasuryAction.Failed("No hay posición que rescatar.")) }
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
            when (val r = defindexClient.withdraw(signer, shares)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Rescate OK: ${r.data} stroops recuperados")
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
        "InsufficientBalance" in raw || "balance" in raw.lowercase() ->
            "Saldo insuficiente de USDC en la tesorería."
        "vault DeFindex no configurado" in raw ->
            "El vault de DeFindex no está configurado."
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
