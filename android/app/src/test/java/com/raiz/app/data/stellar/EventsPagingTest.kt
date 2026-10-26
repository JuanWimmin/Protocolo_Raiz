package com.raiz.app.data.stellar

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas puras de la lectura de `getEvents` de punta a punta ([EventsPaging]). La secuencia de páginas de
 * `readAll recorre la secuencia medida en testnet` es la que devolvió el RPC real el 2026-10-26.
 */
class EventsPagingTest {

    /** Cursor TOID como los del RPC: ledger en los 32 bits altos, 4294967295 de sufijo, 19 dígitos. */
    private fun cursor(ledger: Long): String = ((ledger shl 32) or 0xFFFFFFFFL).toString().padStart(19, '0') + "-4294967295"

    private fun eventos(n: Int, prefijo: String = "e") = List(n) { "$prefijo$it" }

    @Test
    fun `el ledger de un cursor sale de los 32 bits altos`() {
        // Cursor real de testnet (2026-10-26): el ledger 5 390 324.
        assertEquals("0023151269589811199-4294967295", cursor(5_390_324L))
        assertEquals(5_390_324L, EventsPaging.cursorLedger("0023151269589811199-4294967295"))
        assertEquals(5_390_324L, EventsPaging.cursorLedger(cursor(5_390_324L)))
        assertEquals(1L, EventsPaging.cursorLedger(cursor(1L)))
        assertNull(EventsPaging.cursorLedger(null))
        assertNull(EventsPaging.cursorLedger(""))
        assertNull(EventsPaging.cursorLedger("basura"))
    }

    @Test
    fun `sin cursor, o con el cursor repetido, la pagina es la ultima`() {
        assertTrue(EventsPaging.isLastPage(null, null, pageEvents = 100, latestLedger = 10L))
        assertTrue(EventsPaging.isLastPage("", null, pageEvents = 100, latestLedger = 10L))
        val c = cursor(5_000_000L)
        assertTrue(EventsPaging.isLastPage(c, previousCursor = c, pageEvents = 100, latestLedger = 9_000_000L))
        assertFalse(EventsPaging.isLastPage(c, previousCursor = cursor(4_990_000L), pageEvents = 0, latestLedger = 9_000_000L))
    }

    @Test
    fun `una pagina vacia con el cursor aun lejos del ultimo ledger NO es el fin`() {
        // El RPC escanea ~10 000 ledgers por llamada y devuelve cursor aunque no haya eventos.
        assertFalse(EventsPaging.isLastPage(cursor(5_360_329L), null, pageEvents = 0, latestLedger = 5_390_330L))
    }

    @Test
    fun `el cursor en el ultimo ledger termina salvo que la pagina viniera llena`() {
        val ultimo = 5_390_330L
        assertTrue(EventsPaging.isLastPage(cursor(ultimo), cursor(5_390_326L), pageEvents = 0, latestLedger = ultimo))
        assertTrue(EventsPaging.isLastPage(cursor(ultimo), cursor(5_390_326L), pageEvents = 99, latestLedger = ultimo))
        assertTrue(EventsPaging.isLastPage(cursor(ultimo + 3), cursor(5_390_326L), pageEvents = 7, latestLedger = ultimo))
        // Página llena (== PAGE_LIMIT): pudo quedar algo del mismo ledger, se pide otra.
        assertFalse(EventsPaging.isLastPage(cursor(ultimo), cursor(5_390_326L), pageEvents = 100, latestLedger = ultimo))
    }

    @Test
    fun `un cursor ilegible no termina la lectura por si solo`() {
        assertFalse(EventsPaging.isLastPage("basura", null, pageEvents = 0, latestLedger = 10L))
        assertTrue(EventsPaging.isLastPage("basura", "basura", pageEvents = 0, latestLedger = 10L))
    }

    @Test
    fun `readAll recorre la secuencia medida en testnet hasta que el cursor alcanza el ultimo ledger`() = runTest {
        // startLedger 5 350 330, último ledger 5 390 330: 5 páginas, solo la 4ª trae eventos (5 del Pool).
        val latest = 5_390_330L
        val paginas = listOf(
            EventsPaging.Page(emptyList<String>(), cursor(5_360_329L)),
            EventsPaging.Page(emptyList(), cursor(5_370_328L)),
            EventsPaging.Page(emptyList(), cursor(5_380_327L)),
            EventsPaging.Page(eventos(5), cursor(5_390_326L)),
            EventsPaging.Page(emptyList(), cursor(5_390_330L)),
        )
        var pedidas = 0
        val cursoresPedidos = mutableListOf<String>()
        val read = EventsPaging.readAll(
            latestLedger = latest,
            maxPages = 40,
            first = { paginas[pedidas++] },
            next = { c -> cursoresPedidos += c; paginas[pedidas++] },
        )
        assertTrue(read.complete)
        assertEquals(5, read.pages)
        assertEquals(5, pedidas)
        assertEquals(eventos(5), read.events)
        // Cada página se pide con el cursor de la anterior.
        assertEquals(listOf(cursor(5_360_329L), cursor(5_370_328L), cursor(5_380_327L), cursor(5_390_326L)), cursoresPedidos)
    }

    @Test
    fun `readAll no pide mas paginas si la primera ya llega al ultimo ledger`() = runTest {
        var pedidas = 0
        val read = EventsPaging.readAll(
            latestLedger = 100L,
            maxPages = 40,
            first = { pedidas++; EventsPaging.Page(eventos(3), cursor(100L)) },
            next = { error("no debe pedirse otra página") },
        )
        assertTrue(read.complete)
        assertEquals(1, read.pages)
        assertEquals(3, read.events.size)
        assertEquals(1, pedidas)
    }

    @Test
    fun `readAll termina cuando el cursor se repite`() = runTest {
        val c = cursor(50L)
        val paginas = listOf(EventsPaging.Page(eventos(2), c), EventsPaging.Page(emptyList(), c))
        var i = 0
        val read = EventsPaging.readAll(latestLedger = 1_000L, maxPages = 40, first = { paginas[i++] }, next = { paginas[i++] })
        assertTrue(read.complete)
        assertEquals(2, read.pages)
        assertEquals(2, read.events.size)
    }

    @Test
    fun `readAll con una pagina llena en el ultimo ledger pide otra y termina con la vacia`() = runTest {
        val paginas = listOf(
            EventsPaging.Page(eventos(100), cursor(100L)),
            EventsPaging.Page(emptyList(), cursor(100L)),
        )
        var i = 0
        val read = EventsPaging.readAll(latestLedger = 100L, maxPages = 40, first = { paginas[i++] }, next = { paginas[i++] })
        assertTrue(read.complete)
        assertEquals(2, read.pages)
        assertEquals(100, read.events.size)
    }

    @Test
    fun `readAll respeta el tope de paginas y avisa de que la lectura es incompleta`() = runTest {
        var ledger = 1_000L
        val read = EventsPaging.readAll(
            latestLedger = 9_000_000L,
            maxPages = 5,
            first = { EventsPaging.Page(eventos(1, "p0-"), cursor(ledger)) },
            next = { ledger += 10_000L; EventsPaging.Page(eventos(1, "p$ledger-"), cursor(ledger)) },
        )
        assertFalse(read.complete)
        assertEquals(5, read.pages)
        assertEquals(5, read.events.size)
    }

    @Test
    fun `un fallo al pedir una pagina se propaga a quien lee`() = runTest {
        var fallo: Throwable? = null
        try {
            EventsPaging.readAll<String>(
                latestLedger = 9_000_000L,
                maxPages = 5,
                first = { EventsPaging.Page(eventos(1), cursor(1_000L)) },
                next = { throw IllegalStateException("RPC caído") },
            )
        } catch (e: IllegalStateException) {
            fallo = e
        }
        assertEquals("RPC caído", fallo?.message)
    }

    @Test
    fun `covers exige un ledger dentro de la retencion y no en el futuro`() {
        val margen = 200L
        // Retención de testnet medida: oldest 5 269 371, latest 5 390 330.
        assertTrue(EventsPaging.covers(5_380_000L, oldestLedger = 5_269_371L, latestLedger = 5_390_330L, margin = margen))
        assertTrue(EventsPaging.covers(5_269_571L, oldestLedger = 5_269_371L, latestLedger = 5_390_330L, margin = margen))
        assertFalse(EventsPaging.covers(5_269_570L, oldestLedger = 5_269_371L, latestLedger = 5_390_330L, margin = margen))
        assertFalse(EventsPaging.covers(5_000_000L, oldestLedger = 5_269_371L, latestLedger = 5_390_330L, margin = margen))
        // Un ledger que el RPC aún no tiene no se puede leer.
        assertFalse(EventsPaging.covers(5_390_331L, oldestLedger = 5_269_371L, latestLedger = 5_390_330L, margin = margen))
        assertTrue(EventsPaging.covers(5_390_330L, oldestLedger = 5_269_371L, latestLedger = 5_390_330L, margin = margen))
        // Sin el dato de retención se intenta (si el RPC lo rechaza, la lectura falla igual).
        assertTrue(EventsPaging.covers(10L, oldestLedger = null, latestLedger = 5_390_330L, margin = margen))
        assertFalse(EventsPaging.covers(0L, oldestLedger = null, latestLedger = 5_390_330L, margin = margen))
    }
}
