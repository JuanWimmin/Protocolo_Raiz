package com.raiz.app.data.stellar

/**
 * Reglas PURAS para leer `getEvents` de punta a punta (sin red ni Android, se prueban en JVM). Lo que dicen
 * viene MEDIDO contra el RPC de testnet (protocolo 29, 2026-10-26): el RPC escanea ~10 000 ledgers por llamada,
 * devuelve `cursor` AUNQUE la página venga vacía y, al llegar al último ledger, el cursor ya no avanza (no vuelve
 * `null`). Por eso "página vacía" nunca significa "fin": hay que seguir el cursor hasta que se detiene.
 */
object EventsPaging {
    /** Eventos pedidos por página (el máximo que acepta el RPC). */
    const val PAGE_LIMIT = 100L

    /** Una página de `getEvents` ya desligada del SDK: sus eventos y el cursor que devolvió. */
    class Page<E>(val events: List<E>, val cursor: String?)

    /**
     * Lo que dejó [readAll]: los [events] leídos, las [pages] pedidas y si la lectura llegó al último ledger
     * ([complete]). Con `complete == false` la lista es PARCIAL y nunca debe usarse como "todo lo que hay".
     */
    class Read<E>(val events: List<E>, val pages: Int, val complete: Boolean)

    /**
     * Ledger de un cursor de `getEvents`. El cursor es un TOID (`"0023151269589811199-4294967295"`): el ledger va en
     * los 32 bits altos de la primera parte (ese ejemplo es el ledger 5 390 324). `null` si no tiene esa forma.
     */
    fun cursorLedger(cursor: String?): Long? = cursor?.substringBefore('-')?.toLongOrNull()?.shr(32)

    /**
     * ¿La página recién leída fue la última?
     *  - sin cursor → sí;
     *  - el cursor no avanzó respecto al de la página anterior → sí;
     *  - el cursor ya está en el último ledger conocido ([latestLedger]) y la página NO vino llena → sí. Si vino llena
     *    (== [PAGE_LIMIT]) pudo quedar algo del mismo ledger: se pide una más, que vendrá vacía y con el cursor repetido.
     * Un cursor que no se puede interpretar nunca termina la lectura por sí solo (lo hace el cursor repetido o el tope de
     * páginas de [readAll]).
     */
    fun isLastPage(cursor: String?, previousCursor: String?, pageEvents: Int, latestLedger: Long): Boolean {
        if (cursor.isNullOrEmpty()) return true
        if (cursor == previousCursor) return true
        val ledger = cursorLedger(cursor) ?: return false
        return ledger >= latestLedger && pageEvents < PAGE_LIMIT
    }

    /**
     * Recorre las páginas: pide la primera con [first] y las siguientes con [next] (que recibe el cursor de la anterior)
     * hasta que [isLastPage] diga que no queda nada o hasta [maxPages]. Si se agotan las páginas SIN llegar al final,
     * devuelve lo leído con `complete = false`. Un fallo de [first] o [next] se propaga (quien llama decide).
     */
    suspend fun <E> readAll(
        latestLedger: Long,
        maxPages: Int,
        first: suspend () -> Page<E>,
        next: suspend (cursor: String) -> Page<E>,
    ): Read<E> {
        var page = first()
        val all = ArrayList(page.events)
        var pages = 1
        var previous: String? = null
        var cursor = page.cursor
        while (!isLastPage(cursor, previous, page.events.size, latestLedger)) {
            if (pages >= maxPages) return Read(all, pages, complete = false)
            val current = cursor ?: break
            page = next(current)
            pages++
            all.addAll(page.events)
            previous = cursor
            cursor = page.cursor
        }
        return Read(all, pages, complete = true)
    }

    /**
     * ¿Puede el RPC dar TODOS los eventos desde [sinceLedger] hasta [latestLedger]? No si ese ledger está en el futuro
     * del RPC ni si queda antes de lo que retiene ([oldestLedger] más [margin]: la ventana de retención avanza mientras
     * se pide y el RPC rechaza un `startLedger` que acaba de quedar fuera). Sin [oldestLedger] no se puede descartar y
     * se intenta; si el RPC lo rechaza, la lectura falla igualmente.
     */
    fun covers(sinceLedger: Long, oldestLedger: Long?, latestLedger: Long, margin: Long): Boolean {
        if (sinceLedger < 1L || sinceLedger > latestLedger) return false
        return oldestLedger == null || sinceLedger >= oldestLedger + margin
    }
}
