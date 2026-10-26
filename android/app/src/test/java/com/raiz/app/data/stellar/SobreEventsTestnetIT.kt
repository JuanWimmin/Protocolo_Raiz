package com.raiz.app.data.stellar

import com.raiz.app.data.model.RaizConstants
import com.soneso.stellar.sdk.rpc.SorobanServer
import com.soneso.stellar.sdk.rpc.requests.GetEventsRequest
import com.soneso.stellar.sdk.rpc.responses.GetTransactionStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Prueba de integración SOLO DE LECTURA contra el RPC público de **testnet** del lector de eventos que usa la búsqueda
 * del hash de un sobre ([SorobanClient.sobrePaymentEvents]): el cableado del SDK (`getHealth`, `getEvents`, cursor) con
 * [EventsPaging.readAll] sobre TODA la retención del Pool. APAGADA por defecto; no usa claves ni gasta nada:
 *
 * ```
 * RAIZ_EVENTS_IT=1
 * gradlew.bat :app:testDebugUnitTest --tests "*SobreEventsTestnetIT" --rerun -i      (desde android/)
 * ```
 * `--rerun` porque Gradle no rastrea las variables de entorno del JVM de test.
 *
 * Lo que fija (medido el 2026-10-26): la retención es de ~120 960 ledgers (`getHealth.oldestLedger`..`latestLedger`),
 * el RPC escanea ~10 000 ledgers por página (≈ 13 páginas para toda la retención) aunque estén vacías, y el cursor
 * termina por ledger, no por `null`. Con la ventana de ~11 h de `tourPaymentEvents` (8 000 ledgers) un sobre de 16 h
 * no cabe; esta lectura sí.
 */
class SobreEventsTestnetIT {

    private fun poolId(): String {
        val raw = javaClass.getResourceAsStream("/deferred_auth_fixture.json")!!.bufferedReader().readText()
        return Json.parseToJsonElement(raw).jsonObject["pool"]!!.jsonPrimitive.content
    }

    @Test
    fun `lee toda la retencion del Pool paginando hasta el ultimo ledger`() = runBlocking {
        assumeTrue("Apagada por defecto: RAIZ_EVENTS_IT=1", System.getenv("RAIZ_EVENTS_IT") == "1")
        val server = SorobanServer(RaizConstants.TESTNET_SOROBAN_RPC_URL)
        val health = server.getHealth()
        val latest = health.latestLedger ?: server.getLatestLedger().sequence
        val oldest = checkNotNull(health.oldestLedger) { "getHealth no informó oldestLedger" }
        val margin = 200L
        val since = oldest + margin
        assertTrue(EventsPaging.covers(since, oldest, latest, margin))
        assertFalse("un ledger anterior a la retención no se puede leer", EventsPaging.covers(oldest, oldest, latest, margin))

        val filter = GetEventsRequest.EventFilter(
            type = GetEventsRequest.EventFilterType.CONTRACT,
            contractIds = listOf(poolId()),
            topics = emptyList(),
        )
        val read = EventsPaging.readAll(
            latestLedger = latest,
            maxPages = SOBRE_EVENTS_MAX_PAGES_IT,
            first = {
                val p = server.getEvents(
                    GetEventsRequest(
                        startLedger = since,
                        endLedger = null,
                        filters = listOf(filter),
                        pagination = GetEventsRequest.Pagination(cursor = null, limit = EventsPaging.PAGE_LIMIT),
                    ),
                )
                EventsPaging.Page(p.events, p.cursor)
            },
            next = { cursor ->
                val p = server.getEvents(
                    GetEventsRequest(
                        startLedger = null,
                        endLedger = null,
                        filters = listOf(filter),
                        pagination = GetEventsRequest.Pagination(cursor = cursor, limit = EventsPaging.PAGE_LIMIT),
                    ),
                )
                EventsPaging.Page(p.events, p.cursor)
            },
        )
        println("SobreEventsTestnetIT: latest=$latest oldest=$oldest span=${latest - since} → ${read.events.size} eventos en ${read.pages} páginas, complete=${read.complete}")

        assertTrue("la lectura debe llegar al último ledger", read.complete)
        // El RPC escanea ~10 000 ledgers por página aunque estén vacías: toda la retención no cabe en una.
        assertTrue("esperaba varias páginas, fueron ${read.pages}", read.pages >= (latest - since) / 10_100L)
        assertTrue(read.events.all { it.transactionHash.matches(Regex("[0-9a-f]{64}")) })

        // Las txs de los eventos más antiguos siguen consultables: la retención de txs alcanza a la de eventos.
        val masAntiguo = read.events.minByOrNull { it.ledger }
        if (masAntiguo != null) {
            val tx = server.getTransaction(masAntiguo.transactionHash)
            assertNotNull(tx.status)
            assertEquals(GetTransactionStatus.SUCCESS, tx.status)
        }
    }

    private companion object {
        /** Mismo tope que `SorobanClient.SOBRE_EVENTS_MAX_PAGES` (privado allí). */
        const val SOBRE_EVENTS_MAX_PAGES_IT = 40
    }
}
