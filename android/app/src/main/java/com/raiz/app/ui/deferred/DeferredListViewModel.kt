package com.raiz.app.ui.deferred

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.deferred.DeferredPaymentManager
import com.raiz.app.data.deferred.DeferredRecord
import com.raiz.app.data.deferred.ReceivedSobreRecord
import com.raiz.app.data.deferred.SobreSettler
import com.raiz.app.data.model.RaizResult
import com.raiz.app.data.network.ConnectivityMonitor
import com.raiz.app.data.stellar.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Resultado del cierre manual por hash (v0: el usuario pega el hash que le devolvió la pasarela). */
sealed interface ConfirmState {
    data object Working : ConfirmState
    data class Done(val record: DeferredRecord) : ConfirmState
    data class Failed(val message: String) : ConfirmState
}

/**
 * Estado de las acciones sobre un sobre RECIBIDO ("Liquidar ahora", pasarela web).
 * [aviso] y [pendientePasarela] son efectos de un solo uso: la pantalla los ejecuta y los consume.
 */
data class AccionRecibido(
    /** Id del sobre que se está liquidando ahora mismo, o null. */
    val liquidandoId: String? = null,
    val aviso: String? = null,
    /** (texto del sobre que se copia al portapapeles, URL de la pasarela web), el mismo orden que usa Cobros. */
    val pendientePasarela: Pair<String, String>? = null,
)

@HiltViewModel
class DeferredListViewModel @Inject constructor(
    private val manager: DeferredPaymentManager,
    private val settler: SobreSettler,
    private val walletManager: WalletManager,
    private val connectivity: ConnectivityMonitor,
) : ViewModel() {

    /** Los sobres son de la wallet activa, no de la app. */
    private val payer: String? = manager.currentPayer()

    /** Dueña de los sobres recibidos que se muestran: la wallet activa. */
    private val owner: String? = walletManager.currentAccountId()

    val records: StateFlow<List<DeferredRecord>> = manager.recordsFlow
        .map { list -> list.filter { it.payer == payer } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, manager.recordsFor(payer))

    /** Sobres que esta wallet recibió de otros teléfonos (como comercio destinatario o como portador). */
    val recibidos: StateFlow<List<ReceivedSobreRecord>> = settler.receivedFlow
        .map { list -> list.filter { it.owner == owner } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settler.receivedFor(owner))

    /** true sin red (o con el "sin señal" forzado de debug): "Liquidar ahora" no se puede usar. */
    val sinSenal: StateFlow<Boolean> = connectivity.offline
        .stateIn(viewModelScope, SharingStarted.Eagerly, connectivity.isOffline())

    private val _confirm = MutableStateFlow<ConfirmState?>(null)
    val confirm: StateFlow<ConfirmState?> = _confirm.asStateFlow()

    private val _accion = MutableStateFlow(AccionRecibido())
    val accion: StateFlow<AccionRecibido> = _accion.asStateFlow()

    init {
        refresh()
    }

    /**
     * Sin red: marca EXPIRADO según la estimación local (sobres propios y recibidos). Con red: la red cierra los
     * sobres (nonce, expiración, hash del pago).
     */
    fun refresh() {
        settler.refreshStatuses()
        viewModelScope.launch { runCatching { settler.settlePending(payer) } }
    }

    fun record(id: String): DeferredRecord? = records.value.firstOrNull { it.id == id }

    fun recibido(id: String): ReceivedSobreRecord? = recibidos.value.firstOrNull { it.id == id }

    fun confirmByHash(id: String, hash: String) {
        if (_confirm.value is ConfirmState.Working) return
        _confirm.value = ConfirmState.Working
        viewModelScope.launch {
            _confirm.value = when (val r = manager.confirmByHash(id, hash)) {
                is RaizResult.Success -> ConfirmState.Done(r.data)
                is RaizResult.Error -> ConfirmState.Failed(r.message)
            }
        }
    }

    fun clearConfirm() {
        _confirm.value = null
    }

    /** Quién puede enviar desde esta app: wallet semilla (DesdeLaApp) o la pasarela web (passkey/demo). */
    fun liquidador(): SobreSettler.Liquidador = settler.liquidador()

    /** "Liquidar ahora" de un sobre recibido: envío manual (tope de fee de 100 XLM, sin backoff). Exige red. */
    fun liquidarRecibido(id: String) {
        if (_accion.value.liquidandoId != null) return
        val record = recibido(id) ?: return
        _accion.update { it.copy(liquidandoId = id, aviso = null) }
        viewModelScope.launch {
            val tras = try {
                DeferredLabels.trasLiquidar(settler.settleReceived(id, manual = true), record.sobreQr)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Mis sobres: liquidar un sobre recibido falló (${e.javaClass.simpleName})")
                TrasLiquidar("No se pudo liquidar el sobre. Inténtalo de nuevo.", null)
            }
            _accion.update { it.copy(liquidandoId = null, aviso = tras.aviso, pendientePasarela = tras.pasarela) }
        }
    }

    /** Wallet passkey/demo: copia el sobre y abre la pasarela web (el efecto lo ejecuta la pantalla). */
    fun abrirPasarela(id: String) {
        val record = recibido(id) ?: return
        _accion.update { it.copy(pendientePasarela = record.sobreQr to settler.pasarelaUrl(record.sobreQr)) }
    }

    fun consumirAviso() {
        _accion.update { it.copy(aviso = null) }
    }

    fun consumirPasarela() {
        _accion.update { it.copy(pendientePasarela = null) }
    }

    private companion object {
        const val TAG = "RAIZ"
    }
}
