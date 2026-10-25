package com.raiz.app.ui.deferred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raiz.app.data.deferred.DeferredPaymentManager
import com.raiz.app.data.deferred.DeferredRecord
import com.raiz.app.data.model.RaizResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Resultado del cierre manual por hash (v0: el usuario pega el hash que le devolvió la pasarela). */
sealed interface ConfirmState {
    data object Working : ConfirmState
    data class Done(val record: DeferredRecord) : ConfirmState
    data class Failed(val message: String) : ConfirmState
}

@HiltViewModel
class DeferredListViewModel @Inject constructor(
    private val manager: DeferredPaymentManager,
) : ViewModel() {

    val records: StateFlow<List<DeferredRecord>> = manager.records

    private val _confirm = MutableStateFlow<ConfirmState?>(null)
    val confirm: StateFlow<ConfirmState?> = _confirm.asStateFlow()

    init {
        refresh()
    }

    /** Marca EXPIRADO lo que ya venció según la estimación local. */
    fun refresh() {
        manager.refreshStatuses()
    }

    fun record(id: String): DeferredRecord? = records.value.firstOrNull { it.id == id }

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
}
