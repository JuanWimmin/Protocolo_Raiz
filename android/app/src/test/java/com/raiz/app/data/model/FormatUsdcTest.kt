package com.raiz.app.data.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `formatUsdc()` debe usar punto decimal sea cual sea el locale del dispositivo: con es-CO
 * (separador coma) "5,000" quedaba como "5, USDC" tras recortar los ceros.
 */
class FormatUsdcTest {

    @Test
    fun usaPuntoDecimalAunqueElLocaleUseComa() {
        val previo = Locale.getDefault()
        try {
            Locale.setDefault(Locale("es", "CO"))
            assertEquals("5 USDC", 50_000_000L.formatUsdc())
            assertEquals("4.543 USDC", 45_429_362L.formatUsdc())
            assertEquals("0 USDC", 0L.formatUsdc())
            assertEquals("856.643 USDC", 8_566_429_362L.formatUsdc())
        } finally {
            Locale.setDefault(previo)
        }
    }
}
