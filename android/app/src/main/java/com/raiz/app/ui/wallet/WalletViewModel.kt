package com.raiz.app.ui.wallet

import androidx.lifecycle.ViewModel
import com.raiz.app.data.model.WalletState
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

sealed interface WalletUiState {
    data object Loading : WalletUiState
    data class Ready(val wallet: WalletState) : WalletUiState
    data class Error(val message: String) : WalletUiState
}

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletManager: WalletManager,
    // TODO: inyectar HorizonStream para suscribirse a balance USDC en tiempo real.
    // TODO: inyectar SorobanClient (o un RewardsRepository) para get_points.
) : ViewModel() {

    // Por ahora arrancamos directo con un mockWallet — el cableado de creación
    // real (passkey/seed) y los streams de balance llegan después con
    // sus pantallas (CreateWalletScreen, conexión a Horizon SSE, etc.).
    private val _state = MutableStateFlow<WalletUiState>(
        WalletUiState.Ready(walletManager.mockWallet()),
    )
    val state: StateFlow<WalletUiState> = _state.asStateFlow()
}
