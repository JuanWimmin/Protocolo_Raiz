package com.raiz.app.data.deferred

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Escrituras atómicas del almacén de sobres propios (D5): [DeferredStore.mutate] lee–modifica–escribe bajo el
 * mismo candado sobre el registro VIVO. SharedPreferences en memoria; sin Android real.
 */
class DeferredStoreTest {

    private fun record(id: String, status: DeferredStatus = DeferredStatus.ENTREGADO) = DeferredRecord(
        id = id,
        sobreQr = "raiz:sobre:1:e30",
        authXdr = "AAAA",
        payer = "GPAYER",
        merchantAddress = "GMERCHANT",
        merchantName = "Cafe Don Aurelio",
        merchantVerified = true,
        amountStroops = 25_000_000L,
        tipBps = 200,
        feeBps = 50,
        exp = 1_000L,
        l0 = 100L,
        t0 = 1_761_393_600L,
        signedAtMs = 1_000L,
        validUntilMs = 2_000L,
        status = status,
    )

    private fun newStore(prefs: FakePrefs = FakePrefs()): Pair<DeferredStore, FakePrefs> {
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        return DeferredStore(context) to prefs
    }

    @Test
    fun `mutate parte del registro vivo y no pisa una escritura anterior`() {
        val (store, _) = newStore()
        assertTrue(store.saveDurable(record("a")))
        val viejo = store.get("a")!!

        // Otro escritor anota el diario mientras alguien aún sostiene la copia vieja.
        store.mutate("a") { DeferredPolicy.markInFlight(it, "ab".repeat(32), 77L) }
        val siguiente = store.mutate("a") { DeferredPolicy.markSendFailed(it, "Fondos insuficientes", 5_000L, countAsSend = true) }!!

        assertEquals("ab".repeat(32), siguiente.inFlightHash)
        assertEquals(77L, siguiente.inFlightValidUntilSec)
        assertEquals(1, siguiente.sendAttempts)
        assertEquals("Fondos insuficientes", siguiente.lastSendMotivo)
        assertEquals(siguiente, store.get("a"))
        assertNull("la copia vieja no cambia", viejo.inFlightHash)
    }

    @Test
    fun `mutate devuelve null si el sobre no existe y no toca nada`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record("a")))
        val commits = prefs.commits
        assertNull(store.mutate("zzz") { DeferredPolicy.markConfirmed(it, null) })
        assertEquals(commits, prefs.commits)
        assertEquals(listOf("a"), store.all().map { it.id })
    }

    @Test
    fun `mutate sin cambios devuelve el mismo registro y no escribe`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record("a", DeferredStatus.CONFIRMADO)))
        val commits = prefs.commits
        val antes = store.get("a")!!
        // Un sobre cerrado no admite diario ni fallos de envío: la política devuelve el mismo registro.
        val r = store.mutate("a") { DeferredPolicy.markInFlight(it, "cd".repeat(32), 9L) }
        assertSame(antes, r)
        assertEquals(commits, prefs.commits)
    }

    @Test
    fun `mutate guarda con commit y un archivo nuevo lee lo escrito`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record("a")))
        store.mutate("a") {
            DeferredPolicy.markInFlight(it, "ab".repeat(32), 1_761_400_000L)
        }
        assertTrue("el diario debe ir con commit(), no con apply()", prefs.lastWriteWasCommit)

        // Un proceso nuevo (otro DeferredStore sobre el mismo archivo) ve el diario.
        val (reabierto, _) = newStore(prefs)
        val r = reabierto.get("a")!!
        assertEquals("ab".repeat(32), r.inFlightHash)
        assertEquals(1_761_400_000L, r.inFlightValidUntilSec)
    }

    @Test
    fun `si el disco falla mutate devuelve null y la memoria sigue igual que el disco`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record("a")))
        prefs.failCommits = true
        assertNull(store.mutate("a") { DeferredPolicy.markInFlight(it, "ab".repeat(32), 5L) })
        assertNull(store.get("a")!!.inFlightHash)
        prefs.failCommits = false
        assertNotNull(store.mutate("a") { DeferredPolicy.markInFlight(it, "ab".repeat(32), 5L) })
        assertEquals("ab".repeat(32), store.get("a")!!.inFlightHash)
    }

    @Test
    fun `mutate no admite cambiar el id`() {
        val (store, _) = newStore()
        assertTrue(store.saveDurable(record("a")))
        assertNull(store.mutate("a") { it.copy(id = "otro") })
        assertEquals(listOf("a"), store.all().map { it.id })
    }

    @Test
    fun `refreshAll aplica a toda la lista y solo escribe si algo cambia`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record("a")))
        assertTrue(store.saveDurable(record("b", DeferredStatus.CONFIRMADO)))
        val writes = prefs.writes
        store.refreshAll { it } // sin cambios
        assertEquals(writes, prefs.writes)

        store.refreshAll { DeferredPolicy.refresh(it, estimate = 2_000L) }
        assertEquals(DeferredStatus.EXPIRADO, store.get("a")!!.status)
        assertEquals(DeferredStatus.CONFIRMADO, store.get("b")!!.status)
        assertEquals(writes + 1, prefs.writes)
    }

    @Test
    fun `cien hilos anotando intentos a la vez no pierden ninguno`() {
        val (store, _) = newStore()
        assertTrue(store.saveDurable(record("a")))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(100)
        repeat(100) {
            pool.execute {
                start.await()
                store.mutate("a") { DeferredPolicy.markSendFailed(it, "x", 1L, countAsSend = true) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(100, store.get("a")!!.sendAttempts)
    }

    @Test
    fun `un registro de la app anterior sin los campos nuevos se relee con valores por defecto`() {
        val viejo = """
            [{"id":"-7","sobreQr":"raiz:sobre:1:e30","authXdr":"AAAA","payer":"GPAYER",
            "merchantAddress":"GMERCHANT","merchantName":"Cafe","merchantVerified":true,
            "amountStroops":25000000,"tipBps":200,"feeBps":50,"exp":1000,"l0":100,"t0":1761393600,
            "signedAtMs":1000,"validUntilMs":2000,"status":"FIRMADO"}]
        """.trimIndent().replace("\n", "")
        val prefs = FakePrefs().also { it.putRaw("records_v2", viejo) }
        val (store, _) = newStore(prefs)
        val r = store.get("-7")!!
        assertEquals(DeferredStatus.FIRMADO, r.status)
        assertEquals(0, r.sendAttempts)
        assertNull(r.inFlightHash)
        assertFalse(r.copy(status = DeferredStatus.CONFIRMADO).pending)
        // Y se puede seguir mutando.
        assertNotNull(store.mutate("-7") { DeferredPolicy.markDelivered(it) })
        assertEquals(DeferredStatus.ENTREGADO, store.get("-7")!!.status)
    }
}

/** SharedPreferences en memoria con contadores de escrituras y un interruptor para simular un disco que falla. */
private class FakePrefs : SharedPreferences {
    private val data = HashMap<String, Any?>()
    @Volatile var failCommits = false
    @Volatile var commits = 0
    @Volatile var writes = 0
    @Volatile var lastWriteWasCommit = false

    fun putRaw(key: String, value: String) {
        synchronized(data) { data[key] = value }
    }

    override fun getAll(): MutableMap<String, *> = synchronized(data) { HashMap(data) }
    override fun getString(key: String?, defValue: String?): String? = synchronized(data) { data[key] as? String ?: defValue }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = synchronized(data) { data[key] as? Int ?: defValue }
    override fun getLong(key: String?, defValue: Long): Long = synchronized(data) { data[key] as? Long ?: defValue }
    override fun getFloat(key: String?, defValue: Float): Float = synchronized(data) { data[key] as? Float ?: defValue }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(data) { data[key] as? Boolean ?: defValue }
    override fun contains(key: String?): Boolean = synchronized(data) { data.containsKey(key) }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false

        override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[key!!] = values }
        override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
        override fun remove(key: String?) = apply { removed.add(key!!) }
        override fun clear() = apply { clear = true }

        private fun flush() {
            synchronized(data) {
                if (clear) data.clear()
                removed.forEach { data.remove(it) }
                data.putAll(pending)
                writes++
            }
        }

        override fun commit(): Boolean {
            if (failCommits) return false
            flush()
            commits++
            lastWriteWasCommit = true
            return true
        }

        override fun apply() {
            flush()
            lastWriteWasCommit = false
        }
    }
}
