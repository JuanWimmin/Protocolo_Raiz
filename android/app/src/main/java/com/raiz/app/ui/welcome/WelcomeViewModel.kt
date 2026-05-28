package com.raiz.app.ui.welcome

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado compartido por las 3 pantallas del flujo Welcome.
 *
 * - `generatedWords`: las 12 palabras nuevas mostradas en CreateWalletScreen.
 *   Solo se llenan cuando el usuario elige "Crear wallet".
 * - `creating` / `importing`: spinner mientras se deriva el KeyPair y se
 *   persiste en SecureWalletStore.
 * - `error`: mensaje cuando algo falla (seed inválida, derivación, etc.).
 * - `walletReady`: cuando es true, MainActivity navega a `wallet/` con popUpTo.
 */
data class WelcomeUiState(
    val generatedWords: List<String> = emptyList(),
    val generating: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val walletReady: Boolean = false,
)

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val walletManager: WalletManager,
) : ViewModel() {

    private val _state = MutableStateFlow(WelcomeUiState())
    val state: StateFlow<WelcomeUiState> = _state.asStateFlow()

    /** Llamado al entrar a CreateWalletScreen — genera 12 palabras BIP-39. */
    fun generateNewSeed() {
        if (_state.value.generatedWords.isNotEmpty()) return  // ya generadas
        _state.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            when (val r = walletManager.generateSeedPhrase()) {
                is RaizResult.Success -> _state.update {
                    it.copy(generating = false, generatedWords = r.data)
                }
                is RaizResult.Error -> _state.update {
                    it.copy(generating = false, error = r.message)
                }
            }
        }
    }

    /**
     * Persiste las 12 palabras generadas y deriva la cuenta.
     * Se llama tras "Ya las guardé" en CreateWalletScreen.
     */
    fun confirmCreate() {
        val words = _state.value.generatedWords
        if (words.size != 12) {
            _state.update { it.copy(error = "Faltan palabras") }
            return
        }
        persist(words)
    }

    /**
     * Importa una wallet existente desde 12 palabras tipeadas por el usuario.
     * Hace trim + lowercase + split en blancos.
     */
    fun importWallet(rawPhrase: String) {
        val words = rawPhrase
            .lowercase()
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.size !in setOf(12, 24)) {
            _state.update {
                it.copy(error = "Debe ser una frase BIP-39 de 12 o 24 palabras")
            }
            return
        }
        persist(words)
    }

    private fun persist(words: List<String>) {
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = walletManager.saveWallet(words)) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Wallet creada/importada: ${r.data.publicKey}")
                    _state.update { it.copy(saving = false, walletReady = true) }
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "saveWallet falló: ${r.code} — ${r.message}")
                    _state.update {
                        it.copy(saving = false, error = humanError(r.code, r.message))
                    }
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun humanError(code: RaizErrorCode, msg: String): String = when (code) {
        RaizErrorCode.PARSE_ERROR -> "La frase no es válida. Revisa que las 12 palabras estén bien escritas."
        else -> msg
    }

    private companion object { const val TAG = "RAIZ" }
}
