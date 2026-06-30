package com.raiz.app.ui.governance

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.stellar.PasskeyWalletManager
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado del formulario de creación de propuesta.
 *
 * [amountUsdc] es el texto del campo — el usuario escribe "5.00" y nosotros
 * lo convertimos a stroops en [amountStroops] para la llamada on-chain.
 *
 * Validaciones síncronas incluidas en [canSubmit] para habilitar el botón.
 */
data class CreateProposalUiState(
    val description: String = "",
    val amountUsdc: String = "",
    val recipient: String = "",
    val durationDays: Int = 7,
    val submitting: Boolean = false,
    val success: Boolean = false,
    val successProposalId: Long? = null,
    val error: String? = null,
) {
    /** El monto USDC texto es un double positivo válido. */
    val amountValid: Boolean
        get() = amountUsdc.toDoubleOrNull()?.let { it > 0 } == true

    /** El recipient es una Stellar public key G... de 56 chars base32. */
    val recipientValid: Boolean
        get() = recipient.length == 56 &&
                recipient.startsWith("G") &&
                recipient.all { it in 'A'..'Z' || it in '2'..'7' }

    val canSubmit: Boolean
        get() = description.trim().isNotEmpty() &&
                amountValid &&
                recipientValid &&
                durationDays in 3..14 &&
                !submitting

    /** Cantidad en stroops lista para el contrato. */
    val amountStroops: Long
        get() = ((amountUsdc.toDoubleOrNull() ?: 0.0) *
                RaizConstants.USDC_STROOPS_PER_UNIT).toLong()
}

/**
 * ViewModel de CreateProposalScreen.
 *
 * Al iniciarse, resuelve el barrioId del residente (igual que ProposalsViewModel).
 * En demo, usa el barrio demo y demoResidentKeyPair para firmar.
 *
 * Llama [SorobanClient.createProposal] — firma que el otro agente expone.
 */
@HiltViewModel
class CreateProposalViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val passkeyWalletManager: PasskeyWalletManager,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateProposalUiState())
    val state: StateFlow<CreateProposalUiState> = _state.asStateFlow()

    /** barrioId del residente resuelto en init. */
    private var resolvedBarrioId: String? = null

    init {
        resolveBarrio()
    }

    private fun resolveBarrio() {
        viewModelScope.launch {
            val address = walletManager.currentAccountId() ?: return@launch

            if (walletManager.isDemoMode) {
                resolvedBarrioId = ProposalsUiState.DEMO_BARRIO_ID
                return@launch
            }

            when (val r = sorobanClient.getResident(address)) {
                is RaizResult.Success -> resolvedBarrioId = r.data.barrioId
                is RaizResult.Error   -> {
                    Log.w(TAG, "No se pudo determinar barrio del residente: ${r.message}")
                    // En caso de error, si hay barrio conocido desde RoleResolver
                    // se podría usar. Por ahora se deja null y el submit lo captura.
                }
            }
        }
    }

    // ── Mutaciones del formulario ──────────────────────────────────────────

    fun updateDescription(v: String) =
        _state.update { it.copy(description = v.take(200), error = null) }

    fun updateAmountUsdc(v: String) =
        _state.update {
            // Solo dígitos y un punto decimal
            it.copy(amountUsdc = v.filter { c -> c.isDigit() || c == '.' }, error = null)
        }

    fun updateRecipient(v: String) =
        _state.update { it.copy(recipient = v.trim().uppercase(), error = null) }

    fun updateDuration(days: Int) =
        _state.update { it.copy(durationDays = days.coerceIn(3, 14), error = null) }

    // ── Submit ────────────────────────────────────────────────────────────

    /**
     * Publica la propuesta on-chain.
     *
     * Rama passkey: el smart account C... firma [Governance.create_proposal] vía
     * WebAuthn. [activity] es obligatorio en esta rama. Como [contractCall] no
     * expone el valor de retorno de Soroban, el [successProposalId] queda null;
     * la pantalla de éxito igualmente se muestra y el usuario puede refrescar
     * ProposalsScreen para ver la nueva propuesta con su ID asignado.
     *
     * Rama demo/clásica: firma con KeyPair ed25519 y devuelve el ID on-chain.
     */
    fun submit(activity: Activity? = null) {
        val s = _state.value
        if (!s.canSubmit) return

        val barrioId = resolvedBarrioId ?: run {
            _state.update { it.copy(error = "No se pudo determinar tu barrio. Intenta de nuevo.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }

            // Rama passkey: el smart account C... firma vía WebAuthn.
            if (walletManager.isPasskeyWallet() && activity != null) {
                Log.i(
                    TAG,
                    "createProposalWithPasskey barrio=$barrioId desc=${s.description} " +
                    "amount=${s.amountStroops} recipient=${s.recipient} dur=${s.durationDays}",
                )
                when (val r = passkeyWalletManager.createProposalWithPasskey(
                    activity      = activity,
                    barrioId      = barrioId,
                    description   = s.description.trim(),
                    amountStroops = s.amountStroops,
                    recipient     = s.recipient,
                    durationDays  = s.durationDays,
                )) {
                    is RaizResult.Success -> {
                        // contractCall no expone el u64 de retorno (limitación del SDK);
                        // mostramos éxito sin ID — la lista de propuestas lo revelará tras refresh.
                        Log.i(TAG, "Propuesta passkey publicada (id disponible tras refresh)")
                        _state.update {
                            it.copy(submitting = false, success = true, successProposalId = null)
                        }
                    }
                    is RaizResult.Error -> {
                        Log.e(TAG, "createProposalWithPasskey falló: ${r.code} — ${r.message}")
                        _state.update { it.copy(submitting = false, error = r.message) }
                    }
                }
                return@launch
            }

            // Rama clásica / demo: firma con KeyPair ed25519.
            val signer = if (walletManager.isDemoMode) {
                walletManager.demoResidentKeyPair()
            } else {
                walletManager.currentKeyPair()
            }

            if (signer == null) {
                _state.update {
                    it.copy(submitting = false, error = "No hay keypair disponible para firmar.")
                }
                return@launch
            }

            Log.i(
                TAG,
                "createProposal barrio=$barrioId desc=${s.description} " +
                "amount=${s.amountStroops} recipient=${s.recipient} dur=${s.durationDays}",
            )

            when (val r = sorobanClient.createProposal(
                proposer      = signer,
                barrioId      = barrioId,
                description   = s.description.trim(),
                amountStroops = s.amountStroops,
                recipient     = s.recipient,
                durationDays  = s.durationDays,
            )) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Propuesta creada id=${r.data}")
                    _state.update {
                        it.copy(submitting = false, success = true, successProposalId = r.data)
                    }
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "createProposal falló: ${r.code} — ${r.message}")
                    _state.update { it.copy(submitting = false, error = r.message) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
