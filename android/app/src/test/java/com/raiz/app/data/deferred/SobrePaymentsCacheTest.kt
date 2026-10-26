package com.raiz.app.data.deferred

import com.raiz.app.data.model.PaymentRecord
import com.raiz.app.data.model.RaizErrorCode
import com.raiz.app.data.model.RaizResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** La caché de pagos del Pool de una pasada: una lectura por cobertura, sin que un sobre viejo bloquee a los demás. */
class SobrePaymentsCacheTest {

    /** Lector falso: apunta cada petición y falla si `sinceLedger` < [retencion] (un ledger que el RPC ya no retiene). */
    private class Lector(private val retencion: Long = 0L) {
        val llamadas = mutableListOf<Pair<String, Long>>()

        suspend fun leer(payer: String, since: Long): RaizResult<List<PaymentRecord>> {
            llamadas += payer to since
            return if (since < retencion) {
                RaizResult.Error(RaizErrorCode.NETWORK_ERROR, "fuera de la retención")
            } else {
                RaizResult.Success(
                    listOf(PaymentRecord("h$since", payer, "GM", 1L, "USDC", "2026-10-26T00:00:00Z", true)),
                )
            }
        }
    }

    @Test
    fun `una lectura que ya cubre lo pedido se reutiliza`() = runTest {
        val lector = Lector()
        val cache = SobrePaymentsCache(lector::leer)
        val primera = cache.payments("GP", 100L)
        assertNotNull(primera)
        assertSame(primera, cache.payments("GP", 100L))
        assertSame(primera, cache.payments("GP", 150L))
        assertSame(primera, cache.payments("GP", 99_999L))
        assertEquals(listOf("GP" to 100L), lector.llamadas)
    }

    @Test
    fun `un sobre que necesita mas atras vuelve a leer y esa lectura sirve a los mas recientes`() = runTest {
        val lector = Lector()
        val cache = SobrePaymentsCache(lector::leer)
        cache.payments("GP", 150L)
        val ancha = cache.payments("GP", 100L)
        assertEquals(listOf("GP" to 150L, "GP" to 100L), lector.llamadas)
        assertSame(ancha, cache.payments("GP", 120L))
        assertSame(ancha, cache.payments("GP", 150L))
        assertEquals(2, lector.llamadas.size)
    }

    @Test
    fun `cada pagador tiene su propia lectura`() = runTest {
        val lector = Lector()
        val cache = SobrePaymentsCache(lector::leer)
        cache.payments("GA", 100L)
        cache.payments("GB", 100L)
        cache.payments("GA", 100L)
        assertEquals(listOf("GA" to 100L, "GB" to 100L), lector.llamadas)
    }

    @Test
    fun `un fallo se recuerda para esa cobertura y las mas largas sin repetir la lectura`() = runTest {
        val lector = Lector(retencion = 1_000L)
        val cache = SobrePaymentsCache(lector::leer)
        assertNull(cache.payments("GP", 500L))
        assertNull(cache.payments("GP", 500L))
        assertNull(cache.payments("GP", 200L))
        assertEquals(listOf("GP" to 500L), lector.llamadas)
    }

    @Test
    fun `un sobre mas viejo que la retencion no bloquea a los recientes, en cualquier orden`() = runTest {
        // Del más antiguo al más reciente (el orden que usan el manager y el settler).
        val ascendente = Lector(retencion = 1_000L)
        val cacheA = SobrePaymentsCache(ascendente::leer)
        assertNull(cacheA.payments("GP", 300L))
        val reciente = cacheA.payments("GP", 5_000L)
        assertNotNull(reciente)
        assertSame(reciente, cacheA.payments("GP", 6_000L))
        assertEquals(listOf("GP" to 300L, "GP" to 5_000L), ascendente.llamadas)

        // Y al revés: el reciente sigue funcionando y el viejo sigue fallando.
        val descendente = Lector(retencion = 1_000L)
        val cacheB = SobrePaymentsCache(descendente::leer)
        assertNotNull(cacheB.payments("GP", 5_000L))
        assertNull(cacheB.payments("GP", 300L))
        assertNull(cacheB.payments("GP", 300L))
        assertEquals(listOf("GP" to 5_000L, "GP" to 300L), descendente.llamadas)
    }

    @Test
    fun `tras un fallo, pedir menos atras se vuelve a intentar`() = runTest {
        val lector = Lector(retencion = 1_000L)
        val cache = SobrePaymentsCache(lector::leer)
        assertNull(cache.payments("GP", 500L)) // falla
        assertNotNull(cache.payments("GP", 1_500L)) // más reciente que el fallo: se reintenta y funciona
        assertEquals(listOf("GP" to 500L, "GP" to 1_500L), lector.llamadas)
    }
}
