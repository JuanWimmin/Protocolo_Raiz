package com.raiz.app.ui.wallet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.PassportData
import com.raiz.app.data.model.PassportLevel
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.model.formatUsdc
import com.raiz.app.data.stellar.DeploymentsLoader
import com.raiz.app.data.stellar.HorizonStream
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface WalletUiState {
    data object Loading : WalletUiState
    data class Ready(
        val wallet: WalletState,
        val poolBalanceLabel: String,
        val passport: PassportData? = null,
        /** true = cuenta SIN trustline USDC. Mostrar banner "Activar para recibir USDC". */
        val needsUsdcTrustline: Boolean = false,
        val activatingTrustline: Boolean = false,
        val trustlineError: String? = null,
    ) : WalletUiState
    data class Error(val message: String) : WalletUiState
}

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val horizonStream: HorizonStream,
    private val deploymentsLoader: DeploymentsLoader,
) : ViewModel() {

    private val deployments by lazy { deploymentsLoader.load() }

    private val _state = MutableStateFlow<WalletUiState>(
        WalletUiState.Ready(
            // Arranca con `points = 0` hasta que getPoints responda; las
            // demás propiedades del wallet vienen del mock por ahora.
            wallet = walletManager.mockWallet().copy(points = 0L),
            poolBalanceLabel = "Conectando…",
        ),
    )
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    init {
        observeUsdcBalance()
        loadCentroPoolBalance()
        loadPassport()
        checkTrustline()
    }

    /** Verifica si el wallet activo tiene trustline USDC. */
    private fun checkTrustline() {
        viewModelScope.launch {
            val accountId = walletManager.currentAccountId() ?: return@launch
            val has = horizonStream.hasUsdcTrustline(accountId)
            Log.i(TAG, "Trustline USDC para $accountId: $has")
            _state.update { current ->
                if (current is WalletUiState.Ready) current.copy(needsUsdcTrustline = !has)
                else current
            }
        }
    }

    /** Activa el trustline USDC en la cuenta del usuario (firma + submit). */
    fun activateUsdcTrustline() {
        viewModelScope.launch {
            _state.update { current ->
                if (current is WalletUiState.Ready)
                    current.copy(activatingTrustline = true, trustlineError = null)
                else current
            }
            val signer = walletManager.currentKeyPair()
            if (signer == null) {
                _state.update { current ->
                    if (current is WalletUiState.Ready) current.copy(
                        activatingTrustline = false,
                        trustlineError = "No hay wallet activa para firmar.",
                    ) else current
                }
                return@launch
            }
            when (val r = horizonStream.enableUsdcTrustline(signer)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Trustline activado")
                    _state.update { current ->
                        if (current is WalletUiState.Ready) current.copy(
                            activatingTrustline = false,
                            needsUsdcTrustline = false,
                            trustlineError = null,
                        ) else current
                    }
                }
                is RaizResult.Error -> _state.update { current ->
                    if (current is WalletUiState.Ready) current.copy(
                        activatingTrustline = false,
                        trustlineError = "No se pudo activar: ${r.message}",
                    ) else current
                }
            }
        }
    }

    /** Polling del balance USDC vía Horizon. Actualiza el WalletState. */
    private fun observeUsdcBalance() {
        viewModelScope.launch {
            val accountId = walletManager.mockWallet().publicKey
            Log.i(TAG, "Suscribiéndome al balance USDC de $accountId")
            horizonStream.usdcBalanceFlow(accountId).collect { stroops ->
                Log.i(TAG, "Balance USDC actualizado: $stroops stroops (${stroops.formatUsdc()})")
                _state.update { current ->
                    if (current is WalletUiState.Ready) {
                        current.copy(wallet = current.wallet.copy(usdcBalanceStroops = stroops))
                    } else current
                }
            }
        }
    }

    /** Smoke test del Soroban RPC: lee saldo del pool Centro Histórico. */
    private fun loadCentroPoolBalance() {
        viewModelScope.launch {
            Log.i(TAG, "Llamando get_pool_balance para Centro Histórico…")
            val result = sorobanClient.getPoolBalance(BARRIO_CENTRO_ID)
            when (result) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Pool balance Centro: ${result.data} stroops (${result.data.formatUsdc()})")
                    _state.update { current ->
                        if (current is WalletUiState.Ready) {
                            current.copy(poolBalanceLabel = "Centro: ${result.data.formatUsdc()}")
                        } else current
                    }
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "getPoolBalance falló: ${result.code} — ${result.message}")
                    _state.update { current ->
                        if (current is WalletUiState.Ready) {
                            current.copy(poolBalanceLabel = "Sin red")
                        } else current
                    }
                }
            }
        }
    }

    /**
     * Carga datos del RAÍZ Passport:
     * 1. Trae los puntos del turista vía Rewards.get_points (REAL).
     * 2. Trae los merchants de los 3 barrios → mapa addr→barrio.
     * 3. Trae el historial de pagos del turista.
     * 4. Calcula aporte al pool, transacciones locales, barrios visitados.
     *
     * Actualiza tanto `wallet.points` como `passport` con la misma fuente.
     */
    private fun loadPassport() {
        viewModelScope.launch {
            val accountId = walletManager.mockWallet().publicKey

            // 1. Puntos reales del contrato Rewards. Esta es la ÚNICA fuente
            //    de "puntos" en toda la app — la UI lee siempre desde aquí.
            val points = when (val r = sorobanClient.getPoints(accountId)) {
                is RaizResult.Success -> r.data
                is RaizResult.Error -> {
                    Log.w(TAG, "getPoints en passport: ${r.message}")
                    0L
                }
            }
            // Propaga al WalletState inmediatamente para que el StatBox
            // "Puntos" se actualice incluso si las otras lecturas se demoran.
            _state.update { current ->
                if (current is WalletUiState.Ready) {
                    current.copy(wallet = current.wallet.copy(points = points))
                } else current
            }

            // 2. Mapa merchant → barrio.
            val merchantToBarrio: Map<String, String> = buildMap {
                BARRIOS.forEach { (id, _) ->
                    when (val r = sorobanClient.listMerchants(id)) {
                        is RaizResult.Success -> r.data.forEach { put(it.address, id) }
                        is RaizResult.Error -> Log.w(TAG, "listMerchants($id): ${r.message}")
                    }
                }
            }

            // 3. History del turista.
            val history = when (val r = horizonStream.paymentHistory(accountId, limit = 50)) {
                is RaizResult.Success -> r.data
                is RaizResult.Error -> {
                    Log.w(TAG, "paymentHistory en passport: ${r.message}")
                    emptyList()
                }
            }

            // 4. Stats agregadas.
            val poolAddr = deployments.pool
            val aportadoStroops = history
                .filter { it.isOutgoing && it.to == poolAddr }
                .sumOf { it.amountStroops }

            val txsLocales: Set<String> = history
                .filter { it.isOutgoing && merchantToBarrio.containsKey(it.to) }
                .map { it.txHash }
                .toSet()

            val barriosVisitados: Set<String> = history
                .filter { it.isOutgoing }
                .mapNotNull { merchantToBarrio[it.to] }
                .toSet()

            val nivel = PassportLevel.fromPoints(points)
            val passport = PassportData(
                nombre = "Viajer@ ${accountId.takeLast(4)}",
                ubicacion = "Colombia 2026",
                nivel = nivel,
                points = points,
                ptsParaSiguienteNivel = nivel.ptsToNext(points),
                aportadoAlBarrioStroops = aportadoStroops,
                transaccionesLocales = txsLocales.size,
                barriosVisitados = barriosVisitados,
            )
            Log.i(TAG, "Passport: pts=$points · aportado=${aportadoStroops.formatUsdc()} tx=${txsLocales.size} barrios=${barriosVisitados.size}")
            _state.update { current ->
                if (current is WalletUiState.Ready) current.copy(passport = passport) else current
            }
        }
    }

    /** Refresca todos los datos derivados del passport (puntos + stats). */
    fun refresh() {
        loadPassport()
    }

    private companion object {
        const val TAG = "RAIZ"
        const val BARRIO_CENTRO_ID = "ce47120000000000000000000000000000000000000000000000000000000001"
        val BARRIOS: LinkedHashMap<String, String> = linkedMapOf(
            BARRIO_CENTRO_ID to "Centro Histórico",
            "bba17e0000000000000000000000000000000000000000000000000000000002" to "Barrio Norte",
            "c057a9000000000000000000000000000000000000000000000000000000000a" to "Costa Vieja",
        )
    }
}
