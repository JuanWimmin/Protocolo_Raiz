package com.raiz.app.ui.pay

import android.app.Activity
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.deferred.DeferredPaymentManager
import com.raiz.app.data.deferred.DeferredRecord
import com.raiz.app.data.local.MerchantCache
import com.raiz.app.data.model.Merchant
import com.raiz.app.data.model.PaymentPreview
import com.raiz.app.data.model.RaizConstants
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.network.ConnectivityMonitor
import com.raiz.app.data.security.AppLock
import com.raiz.app.data.stellar.PasskeyWalletManager
import com.raiz.app.data.stellar.SorobanClient
import com.raiz.app.data.stellar.WalletManager
import com.raiz.app.ui.deferred.DeferredLabels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** De dónde salió un error: antes de intentar ninguna transacción (carga) o al pagar. */
enum class ErrorOrigen { CARGA, PAGO }

sealed interface PayUiState {
    data object Loading : PayUiState
    data class Editing(
        val preview: PaymentPreview,
        val tipEnabled: Boolean,
        val submitting: Boolean,
        /** Si true, confirmar el pago exige biometría/PIN (lock activado). */
        val requireBiometric: Boolean = false,
        /** Si true, el monto vino pre-llenado desde un QR de orden de cobro. */
        val esOrdenDeCobro: Boolean = false,
        /** Sin señal (o forzado en debug): el botón firma un sobre de pago diferido en vez de pagar en línea. */
        val sinSenal: Boolean = false,
        /** El switch de debug "Firmar sin señal" está activado. */
        val sinSenalForzado: Boolean = false,
        /** Si no es null, sin señal no se puede firmar y este es el motivo (texto para la pantalla). */
        val sinSenalNoDisponible: String? = null,
        /** Los datos del comercio vienen de la última consulta con señal (caché local), no de la red. */
        val comercioDesdeCache: Boolean = false,
    ) : PayUiState
    data class Success(
        val merchantName: String,
        val totalStroops: Long,
        val pointsEarned: Long,
        /** Stroops del Tip Barrio; 0 si el tip estaba desactivado al confirmar. */
        val tipStroops: Long = 0L,
        /** Dirección G… o C… del comercio — para el link a Stellar Expert en la pantalla de éxito. */
        val merchantAddress: String = "",
    ) : PayUiState
    /** Sobre firmado sin señal: el QR se genera en la pantalla al tamaño exacto disponible. */
    data class Signed(val record: DeferredRecord, val validUntilLabel: String) : PayUiState
    data class Error(
        val code: RaizErrorCode,
        val message: String,
        val origen: ErrorOrigen = ErrorOrigen.PAGO,
    ) : PayUiState
}

@HiltViewModel
class PayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walletManager: WalletManager,
    private val sorobanClient: SorobanClient,
    private val appLock: AppLock,
    private val passkeyWalletManager: PasskeyWalletManager,
    private val connectivity: ConnectivityMonitor,
    private val deferred: DeferredPaymentManager,
    private val merchantCache: MerchantCache,
) : ViewModel() {

    // address del merchant, vía nav arg `merchant_address`. Si está vacío
    // usamos el merchant demo (Cafe Don Aurelio) para compat con flow viejo.
    private val merchantAddress: String =
        savedStateHandle.get<String>("merchant_address").orEmpty().ifBlank { DEMO_MERCHANT_ADDR }

    // Monto pre-llenado desde el QR de orden de cobro (stroops).
    // 0 = no vino monto en la URL → usar el default de 5 USDC.
    private val prefilledAmountStroops: Long =
        savedStateHandle.get<Long>("amount_stroops")?.takeIf { it > 0L } ?: 0L

    private val defaultAmountStroops: Long =
        if (prefilledAmountStroops > 0L) prefilledAmountStroops else 50_000_000L  // 5 USDC demo

    private val _state = MutableStateFlow<PayUiState>(PayUiState.Loading)
    val state: StateFlow<PayUiState> = _state.asStateFlow()

    init {
        observeConnectivity()
        // Con red, refrescamos el ancla del estimador al entrar: si la señal se va dentro de esta
        // pantalla, la expiración del sobre se estima desde un ledger reciente.
        viewModelScope.launch { if (!connectivity.isOffline()) sorobanClient.refreshLedgerAnchor() }
        loadMerchant()
    }

    /**
     * La decisión "sin señal" se toma por conectividad ANTES de confirmar, nunca como respaldo tras un
     * pago en línea fallido (podría cobrar dos veces). Mientras `submitting`, no se toca.
     */
    @OptIn(FlowPreview::class)
    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivity.offline.debounce(DEBOUNCE_MS).collect { offline ->
                _state.update { s ->
                    if (s is PayUiState.Editing && !s.submitting) {
                        s.copy(
                            sinSenal = offline,
                            sinSenalForzado = connectivity.forceOffline.value,
                            sinSenalNoDisponible = if (offline) motivoNoDisponible() else null,
                        )
                    } else {
                        s
                    }
                }
            }
        }
    }

    private fun motivoNoDisponible(): String? =
        (deferred.availability() as? DeferredPaymentManager.Availability.NoDisponible)?.motivo

    private fun loadMerchant() {
        viewModelScope.launch {
            // Sin señal: solo lo que la red devolvió alguna vez (caché). Nada de mocks ni placeholders.
            if (connectivity.isOffline()) {
                val cached = merchantCache.merchant(merchantAddress)
                _state.value = if (cached != null) {
                    stateFor(cached, fromCache = true)
                } else {
                    PayUiState.Error(RaizErrorCode.NETWORK_ERROR, MSG_SIN_SENAL_DESCONOCIDO, ErrorOrigen.CARGA)
                }
                return@launch
            }
            Log.i(TAG, "Cargando merchant: $merchantAddress")
            when (val r = sorobanClient.getMerchant(merchantAddress)) {
                is RaizResult.Success -> _state.value = stateFor(r.data)
                is RaizResult.Error -> {
                    // La red falló a mitad: si conocemos el comercio de antes, seguimos con esos datos.
                    val cached = if (r.code == RaizErrorCode.NOT_FOUND) null else merchantCache.merchant(merchantAddress)
                    _state.value = if (cached != null) {
                        stateFor(cached, fromCache = true)
                    } else {
                        PayUiState.Error(r.code, r.message, ErrorOrigen.CARGA)
                    }
                }
            }
        }
    }

    private fun stateFor(m: Merchant, fromCache: Boolean = false): PayUiState.Editing {
        val offline = connectivity.isOffline()
        return PayUiState.Editing(
            preview = PaymentPreview(m, defaultAmountStroops, RaizConstants.DEFAULT_TIP_BPS),
            tipEnabled = true,
            submitting = false,
            requireBiometric = appLock.isActive(),
            esOrdenDeCobro = prefilledAmountStroops > 0L,
            sinSenal = offline,
            sinSenalForzado = connectivity.forceOffline.value,
            sinSenalNoDisponible = if (offline) motivoNoDisponible() else null,
            comercioDesdeCache = fromCache,
        )
    }

    fun toggleTip() {
        _state.update { current ->
            if (current is PayUiState.Editing) {
                val newTipBps = if (current.tipEnabled) 0 else RaizConstants.DEFAULT_TIP_BPS
                current.copy(
                    preview = current.preview.copy(tipBps = newTipBps),
                    tipEnabled = !current.tipEnabled,
                )
            } else {
                current
            }
        }
    }

    /** Solo en debug: simula "sin señal" con red para grabar la demo. */
    fun toggleSinSenalDebug() {
        connectivity.setForceOffline(!connectivity.forceOffline.value)
    }

    /** El QR del sobre ya se mostró: la promesa puede estar viajando. */
    fun onQrShown() {
        (_state.value as? PayUiState.Signed)?.let { deferred.markDelivered(it.record.id) }
    }

    /**
     * Confirma el pago. El [activity] es obligatorio para la rama passkey
     * (Credential Manager necesita un Activity para mostrar WebAuthn).
     * En la rama clásica (KeyPair seed/demo) se ignora.
     */
    fun confirm(activity: Activity? = null) {
        val editing = _state.value as? PayUiState.Editing ?: return
        if (editing.submitting) return

        _state.update { editing.copy(submitting = true) }

        viewModelScope.launch {
            // Pago diferido: firma sin señal y muestra el sobre. Nunca después de intentar en línea.
            if (editing.sinSenal) {
                signDeferred(editing)
                return@launch
            }

            // Rama passkey: el smart account C... firma la llamada pay_merchant vía WebAuthn.
            // Incluye sub-auth para SAC.transfer en una sola ceremonia passkey.
            if (walletManager.isPasskeyWallet() && activity != null) {
                Log.i(
                    TAG,
                    "Pago passkey: ${editing.preview.baseAmountUsdc} USDC " +
                    "tip=${editing.preview.tipBps}bps a ${editing.preview.merchant.name}",
                )
                val result = passkeyWalletManager.payMerchantWithPasskey(
                    activity        = activity,
                    merchantAddress = editing.preview.merchant.address,
                    amountStroops   = editing.preview.baseAmountStroops,
                    tipBps          = editing.preview.tipBps,
                )
                when (result) {
                    is RaizResult.Success -> {
                        Log.i(TAG, "Pago passkey OK")
                        _state.value = PayUiState.Success(
                            merchantName    = editing.preview.merchant.name,
                            totalStroops    = editing.preview.totalStroops,
                            pointsEarned    = editing.preview.pointsToEarn,
                            tipStroops      = editing.preview.tipStroops,
                            merchantAddress = editing.preview.merchant.address,
                        )
                    }
                    is RaizResult.Error -> {
                        Log.e(TAG, "Pago passkey falló: ${result.code} — ${result.message}")
                        _state.value = PayUiState.Error(result.code, result.message, ErrorOrigen.PAGO)
                    }
                }
                return@launch
            }

            // Rama clásica: firma con KeyPair ed25519 (seed BIP-39 o demo).
            val keyPair = walletManager.currentKeyPair()
            if (keyPair == null) {
                _state.value = PayUiState.Error(
                    RaizErrorCode.UNAUTHORIZED,
                    "No hay wallet configurada para firmar pagos.",
                    ErrorOrigen.PAGO,
                )
                return@launch
            }

            Log.i(TAG, "Pagando ${editing.preview.baseAmountUsdc} USDC tip=${editing.preview.tipBps}bps a ${editing.preview.merchant.name}")
            val result = sorobanClient.payMerchant(
                tourist = keyPair,
                merchantAddress = editing.preview.merchant.address,
                amountStroops = editing.preview.baseAmountStroops,
                tipBps = editing.preview.tipBps,
            )
            when (result) {
                is RaizResult.Success -> {
                    Log.i(TAG, "Pago OK")
                    _state.value = PayUiState.Success(
                        merchantName    = editing.preview.merchant.name,
                        totalStroops    = editing.preview.totalStroops,
                        pointsEarned    = editing.preview.pointsToEarn,
                        tipStroops      = editing.preview.tipStroops,
                        merchantAddress = editing.preview.merchant.address,
                    )
                }
                is RaizResult.Error -> {
                    Log.e(TAG, "Pago falló: ${result.code} — ${result.message}")
                    _state.value = PayUiState.Error(result.code, result.message, ErrorOrigen.PAGO)
                }
            }
        }
    }

    private suspend fun signDeferred(editing: PayUiState.Editing) {
        val preview = editing.preview
        Log.i(TAG, "Firmando sobre sin señal: ${preview.baseAmountUsdc} USDC tip=${preview.tipBps}bps a ${preview.merchant.name}")
        when (val r = deferred.sign(preview.merchant, preview.baseAmountStroops, preview.tipBps)) {
            is RaizResult.Success ->
                _state.value = PayUiState.Signed(r.data, DeferredLabels.validUntil(r.data.validUntilMs))
            is RaizResult.Error -> {
                Log.e(TAG, "Firma sin señal falló: ${r.code} — ${r.message}")
                _state.value = PayUiState.Error(r.code, r.message, ErrorOrigen.PAGO)
            }
        }
    }

    private companion object {
        const val TAG = "RAIZ"
        const val DEBOUNCE_MS = 300L
        const val DEMO_MERCHANT_ADDR = "GA7CDZDQJVKUQGMY5ZCTYOCDOXIWEDRPE5YAGHGJZOB4QM6WMUVE4TSN"
        const val MSG_SIN_SENAL_DESCONOCIDO =
            "Sin señal no conocemos este comercio. Conéctate un momento para consultarlo; " +
                "después podrás firmar sin señal."
    }
}
