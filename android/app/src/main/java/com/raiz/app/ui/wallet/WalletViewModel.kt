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
        val passport: PassportData? = null,        // null mientras se carga
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
            wallet = walletManager.mockWallet(),
            poolBalanceLabel = "Conectando…",
        ),
    )
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    init {
        observeUsdcBalance()
        loadCentroPoolBalance()
        loadPassport()
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
                        val updatedWallet = current.wallet.copy(usdcBalanceStroops = stroops)
                        // El passport refleja el saldo en su header — lo
                        // actualizamos también si ya estaba cargado.
                        val updatedPassport = current.passport?.copy(
                            saldoStroops = stroops,
                            nivel = PassportLevel.fromStroops(stroops),
                            ptsParaSiguienteNivel = PassportLevel.fromStroops(stroops).ptsToNext(stroops),
                        )
                        current.copy(wallet = updatedWallet, passport = updatedPassport)
                    } else {
                        current
                    }
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
     * 1. Trae los merchants de los 3 barrios → mapa addr→barrio.
     * 2. Trae el historial de pagos del turista.
     * 3. Calcula aporte al pool, transacciones locales, barrios visitados.
     */
    private fun loadPassport() {
        viewModelScope.launch {
            val accountId = walletManager.mockWallet().publicKey

            // 1. Construir mapa merchant → barrio (los 3 barrios del seed).
            val merchantToBarrio: Map<String, String> = buildMap {
                BARRIOS.forEach { (id, _) ->
                    when (val r = sorobanClient.listMerchants(id)) {
                        is RaizResult.Success -> r.data.forEach { put(it.address, id) }
                        is RaizResult.Error -> Log.w(TAG, "listMerchants($id): ${r.message}")
                    }
                }
            }

            // 2. History del turista.
            val history = when (val r = horizonStream.paymentHistory(accountId, limit = 50)) {
                is RaizResult.Success -> r.data
                is RaizResult.Error -> {
                    Log.w(TAG, "paymentHistory en passport: ${r.message}")
                    emptyList()
                }
            }

            // 3. Stats. `to == deployments.pool` ⇒ tip al pool del barrio.
            //    Para "transacciones locales" agrupamos por txHash y contamos
            //    las txs únicas en las que el turista pagó a algún merchant
            //    conocido.
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

            val saldoStroops = (state.value as? WalletUiState.Ready)?.wallet?.usdcBalanceStroops ?: 0L
            val nivel = PassportLevel.fromStroops(saldoStroops)

            val passport = PassportData(
                // Mientras no haya display name configurable, usamos las
                // últimas 4 letras del public key como "alias" — corto y único.
                nombre = "Viajer@ ${accountId.takeLast(4)}",
                ubicacion = "Colombia 2026",
                nivel = nivel,
                saldoStroops = saldoStroops,
                ptsParaSiguienteNivel = nivel.ptsToNext(saldoStroops),
                aportadoAlBarrioStroops = aportadoStroops,
                transaccionesLocales = txsLocales.size,
                barriosVisitados = barriosVisitados,
            )
            Log.i(TAG, "Passport: aportado=${aportadoStroops.formatUsdc()} tx=${txsLocales.size} barrios=${barriosVisitados.size}")
            _state.update { current ->
                if (current is WalletUiState.Ready) current.copy(passport = passport) else current
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
        const val BARRIO_CENTRO_ID = "ce47120000000000000000000000000000000000000000000000000000000001"
        // (id → label) usado al cargar merchants para el passport.
        val BARRIOS: LinkedHashMap<String, String> = linkedMapOf(
            BARRIO_CENTRO_ID to "Centro Histórico",
            "bba17e0000000000000000000000000000000000000000000000000000000002" to "Barrio Norte",
            "c057a9000000000000000000000000000000000000000000000000000000000a" to "Costa Vieja",
        )
    }
}
