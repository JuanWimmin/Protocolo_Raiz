package com.raiz.app.data.deferred

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Buzón en memoria entre quien ESCANEA un sobre (Inicio) y la pantalla que lo verifica (Recibir sobre).
 *
 * Un sobre mide ≈ 1,2 KB: pasarlo como argumento de navegación lo metería en la ruta (y en el estado guardado
 * del back stack). En su lugar, quien lo escanea lo deja en [pendingText] y navega a `recibir`; la pantalla lo
 * recoge UNA sola vez con [take]. No persiste: si el proceso muere antes de recogerlo, el usuario vuelve a
 * escanear (el sobre sigue en el teléfono del pagador, no se perdió nada).
 */
@Singleton
class SobreInbox @Inject constructor() {

    private val slot = AtomicReference<String?>(null)

    /** Texto (`raiz:sobre:1:…`) pendiente de verificar, o null si no hay ninguno. */
    var pendingText: String?
        get() = slot.get()
        set(value) {
            slot.set(value)
        }

    /** Devuelve el texto pendiente y lo limpia en un solo paso: dos pantallas no pueden recibir el mismo sobre. */
    fun take(): String? = slot.getAndSet(null)
}
