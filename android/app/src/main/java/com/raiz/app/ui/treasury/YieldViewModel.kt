package com.raiz.app.ui.treasury

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.BlendReserveStats
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.toStroops
import com.raiz.app.data.relayer.RelayerClient
import com.raiz.app.data.stellar.BlendClient
import com.raiz.app.data.stellar.SorobanClient
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
    /** false si falta `raiz.relayer.url` / `raiz.relayer.key` en local.properties. */
    val relayerConfigured: Boolean = true,
    /**
     * true cuando `GET /v1/health` ya respondió (ok o error) al menos una vez
     * en esta pantalla. Hasta entonces "Mover fondos" está en modo lectura
     * con el aviso "Comprobando el relayer…" (H6): las lecturas on-chain no
     * esperan al relayer.
     */
    val relayerChecked: Boolean = false,
    /**
     * `false` (valor INICIAL) hasta que el relayer confirme que expone
     * `/v1/vault/deposit` y `/v1/vault/redeem` (`RelayerHealth.vaultEndpoints`).
     * Mientras sea `false`, "Mover fondos" queda en modo lectura.
     */
    val vaultEndpoints: Boolean = false,
    /**
     * Motivo del modo lectura cuando [relayerChecked] y no [vaultEndpoints]:
     * `"relayer sin vault"` (health ok, endpoints apagados) o
     * `"relayer no disponible: …"` (health falló). Se muestra como
     * "Tesorería en modo lectura (<motivo>)."
     */
    val vaultUnavailableReason: String? = null,
    /**
     * `idempotency-key` del intento en curso (H1): se conserva en los
     * reintentos (misma key + mismo body → el relayer devuelve el mismo
     * resultado, no mueve fondos dos veces) y se descarta al llegar el éxito
     * o al cambiar el body ([attemptFingerprint]: acción, barrio, monto/shares).
     */
    val attemptKey: String? = null,
    /** Huella del body enviado con [attemptKey]. */
    val attemptFingerprint: String? = null,
    /**
     * > 0 mientras los botones de "Mover fondos" están bloqueados tras un error
     * "transacción pendiente" (timeout de Ktor o `TX_TIMEOUT` con hash, H1d):
     * epoch ms en que se vuelven a habilitar. 0 = sin bloqueo. Yield es el único
     * flujo sin guard on-chain (un depósito repetido SÍ mueve fondos dos veces),
     * por eso solo aquí se espera antes de dejar reintentar.
     */
    val retryAvailableAtMs: Long = 0L,
) {
    val selectedBarrioItem: BarrioYieldItem?
        get() = barriosYield.firstOrNull { it.barrioId == selectedBarrioId }

    /** Rendimiento agregado (stroops) de TODOS los barrios juntos. */
    val totalYieldStroops: Long
        get() = barriosYield.sumOf { it.rendimientoStroops }

    /** true mientras dura el bloqueo de [retryAvailableAtMs]. */
    val retryLocked: Boolean
        get() = retryAvailableAtMs > 0L
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
 *  - **Depositar / rescatar** (sección "Mover fondos"): vía `raiz-relayer`
 *    ([RelayerClient.vaultDeposit] / [RelayerClient.vaultRedeem]), que firma
 *    server-side como admin — la app ya no guarda esa clave (D1 del SOW).
 *    Si el relayer no expone `/v1/vault/deposit` y `/v1/vault/redeem` (ver [RelayerClient.health]), la
 *    sección queda en modo lectura.
 *
 * Las lecturas on-chain y `health()` corren en coroutines separadas (H6): la
 * pantalla publica APY/posiciones en cuanto llegan, y el relayer solo decide
 * si "Mover fondos" sale del modo lectura.
 */
@HiltViewModel
class YieldViewModel @Inject constructor(
    private val blendClient: BlendClient,
    private val sorobanClient: SorobanClient,
    private val relayerClient: RelayerClient,
) : ViewModel() {

    private val _state = MutableStateFlow(
        YieldUiState(
            selectedBarrioId = DEMO_BARRIOS.keys.first(),
            relayerConfigured = relayerClient.isConfigured(),
            // Modo lectura hasta que health() responda (H6).
            vaultEndpoints = false,
        ),
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

    /**
     * Recarga las lecturas on-chain (Blend + posición por barrio) y, en
     * paralelo, la salud del relayer. Las primeras se publican con
     * `loading=false` en cuanto terminan, sin esperar al relayer (H6).
     */
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

            // Lecturas puras listas: se publican YA, sin esperar a health() (H6).
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
        refreshRelayerHealth()
    }

    /**
     * Salud del relayer (feature-flag de "Mover fondos") en su propia
     * coroutine: solo toca `relayerConfigured` / `relayerChecked` /
     * `vaultEndpoints` / `vaultUnavailableReason`. `health()` tiene su
     * propio timeout corto (10 s) y caché de 30 s en [RelayerClient].
     */
    private fun refreshRelayerHealth() {
        val configured = relayerClient.isConfigured()
        if (!configured) {
            _state.update {
                it.copy(relayerConfigured = false, relayerChecked = true, vaultEndpoints = false, vaultUnavailableReason = null)
            }
            return
        }
        viewModelScope.launch {
            val (vaultEndpoints, reason) = when (val h = relayerClient.health()) {
                is RaizResult.Success ->
                    if (h.data.ok && h.data.vaultEndpoints) true to null else false to "relayer sin vault"
                is RaizResult.Error -> {
                    Log.w(TAG, "relayerClient.health() falló: ${h.message}")
                    false to "relayer no disponible: ${h.message}"
                }
            }
            Log.i(TAG, "Relayer: configured=true vaultEndpoints=$vaultEndpoints reason=$reason")
            _state.update {
                it.copy(
                    relayerConfigured = true,
                    relayerChecked = true,
                    vaultEndpoints = vaultEndpoints,
                    vaultUnavailableReason = reason,
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

    /** Deposita el monto del input (USDC) en Blend, para el barrio seleccionado, vía el relayer. */
    fun deposit() {
        val amountStroops = _state.value.amountInput.toDoubleOrNull()?.toStroops() ?: 0L
        if (amountStroops <= 0L) {
            _state.update { it.copy(action = TreasuryAction.Failed("Ingresa un monto válido en USDC.")) }
            return
        }
        if (!relayerAvailableForVault()) return
        val barrioId = _state.value.selectedBarrioId
        // H1: misma key mientras el body (acción, barrio, monto) no cambie.
        val attemptKey = attemptKeyFor("deposit|$barrioId|$amountStroops")
        viewModelScope.launch {
            _state.update { it.copy(action = TreasuryAction.Submitting) }
            when (val r = relayerClient.vaultDeposit(barrioId, amountStroops, idempotencyKey = attemptKey)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Depósito OK (relayer): $amountStroops stroops → Blend (barrio $barrioId) tx=${r.data}")
                    _state.update {
                        it.copy(
                            action = TreasuryAction.Ok("Depósito confirmado on-chain"),
                            amountInput = "",
                            attemptKey = null,
                            attemptFingerprint = null,
                        )
                    }
                    refresh()
                }
                is RaizResult.Error -> onVaultError(r)
            }
        }
    }

    /** Rescata toda la posición de shares del barrio seleccionado, vía el relayer. */
    fun withdrawAll() {
        val barrioId = _state.value.selectedBarrioId
        val shares = _state.value.selectedBarrioItem?.depositadoStroops ?: 0L
        if (shares <= 0L) {
            _state.update { it.copy(action = TreasuryAction.Failed("Este barrio no tiene posición que rescatar.")) }
            return
        }
        if (!relayerAvailableForVault()) return
        // H1: misma key mientras el body (acción, barrio, shares) no cambie.
        val attemptKey = attemptKeyFor("redeem|$barrioId|$shares")
        viewModelScope.launch {
            _state.update { it.copy(action = TreasuryAction.Submitting) }
            when (val r = relayerClient.vaultRedeem(barrioId, shares, idempotencyKey = attemptKey)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Rescate OK (relayer): $shares shares del barrio $barrioId tx=${r.data}")
                    _state.update {
                        it.copy(
                            action = TreasuryAction.Ok("Rescate confirmado on-chain"),
                            attemptKey = null,
                            attemptFingerprint = null,
                        )
                    }
                    refresh()
                }
                is RaizResult.Error -> onVaultError(r)
            }
        }
    }

    /**
     * Devuelve la `idempotency-key` del intento: la del estado si la huella del
     * body coincide (reintento del mismo intento), o una nueva si el usuario
     * cambió barrio/monto/acción. La guarda en el estado.
     */
    private fun attemptKeyFor(fingerprint: String): String {
        val s = _state.value
        val key = s.attemptKey?.takeIf { s.attemptFingerprint == fingerprint }
            ?: RelayerClient.newIdempotencyKey()
        _state.update { it.copy(attemptKey = key, attemptFingerprint = fingerprint) }
        return key
    }

    /**
     * Error de deposit/redeem. Si es de la familia "transacción pendiente"
     * (timeout de Ktor o `TX_TIMEOUT` con hash), además bloquea los botones
     * [PENDING_RETRY_COOLDOWN_MS] (H1d) — el reintento reutilizará la misma
     * key y el relayer responderá lo cacheado — y al terminar refresca las
     * posiciones por si la tx se aplicó. `attemptKey` se conserva siempre.
     */
    private fun onVaultError(r: RaizResult.Error) {
        val pending = RelayerClient.isPendingTransactionError(r)
        val until = if (pending) System.currentTimeMillis() + PENDING_RETRY_COOLDOWN_MS else 0L
        _state.update { it.copy(action = TreasuryAction.Failed(humanError(r.message)), retryAvailableAtMs = until) }
        if (!pending) return
        Log.w(TAG, "Vault: transacción pendiente, botones bloqueados ${PENDING_RETRY_COOLDOWN_MS / 1000} s")
        viewModelScope.launch {
            delay(PENDING_RETRY_COOLDOWN_MS)
            // Solo desbloquea si nadie fijó un bloqueo posterior.
            _state.update { if (it.retryAvailableAtMs == until) it.copy(retryAvailableAtMs = 0L) else it }
            refresh()
        }
    }

    /**
     * Gate compartido de [deposit] / [withdrawAll]: si el relayer no está
     * configurado, todavía no respondió a `health()`, no expone los endpoints
     * `/v1/vault/deposit` y `/v1/vault/redeem`, o hay una transacción pendiente
     * en cooldown, deja la acción en modo lectura con un mensaje en vez de
     * intentar una llamada que fallaría igual — sin crash, sin bloquear el
     * resto de la pantalla.
     */
    private fun relayerAvailableForVault(): Boolean {
        val s = _state.value
        if (!s.relayerConfigured) {
            _state.update {
                it.copy(
                    action = TreasuryAction.Failed(
                        "Relayer no configurado (raiz.relayer.url / raiz.relayer.key en local.properties)",
                    ),
                )
            }
            return false
        }
        if (!s.relayerChecked) {
            _state.update { it.copy(action = TreasuryAction.Failed("Comprobando el relayer… espera un momento.")) }
            return false
        }
        if (!s.vaultEndpoints) {
            _state.update {
                it.copy(action = TreasuryAction.Failed(readOnlyMessage(s.vaultUnavailableReason)))
            }
            return false
        }
        if (s.retryLocked) {
            _state.update {
                it.copy(action = TreasuryAction.Failed("Transacción pendiente… espera un minuto antes de reintentar."))
            }
            return false
        }
        return true
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

    companion object {
        private const val TAG = "RAIZ"
        /** Bloqueo de los botones tras un error "transacción pendiente" (H1d). */
        const val PENDING_RETRY_COOLDOWN_MS = 60_000L

        /** Texto del modo lectura de "Mover fondos"; mismo string en YieldScreen y en el gate. */
        fun readOnlyMessage(reason: String?): String =
            "Tesorería en modo lectura (${reason ?: "relayer sin vault"})."

        // Mapa hex → nombre legible de los 3 barrios del seed.
        // Mirror de RoleResolver.DEMO_BARRIOS (privado allá, duplicado aquí para
        // no introducir una dependencia circular con la capa data).
        private val DEMO_BARRIOS: LinkedHashMap<String, String> = linkedMapOf(
            "ce47120000000000000000000000000000000000000000000000000000000001" to "Centro Histórico",
            "bba17e0000000000000000000000000000000000000000000000000000000002" to "Barrio Norte",
            "c057a9000000000000000000000000000000000000000000000000000000000a" to "Costa Vieja",
        )
    }
}
