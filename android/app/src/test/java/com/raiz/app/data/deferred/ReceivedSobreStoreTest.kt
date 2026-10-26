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
 * Almacén de sobres recibidos ([ReceivedSobreStore]): escrituras atómicas sobre el registro VIVO (D5), índice
 * `(owner, payer, nonce)`, guardado idempotente que no pisa y tope por wallet. SharedPreferences en memoria.
 */
class ReceivedSobreStoreTest {

    private val hash1 = "ab".repeat(32)

    private fun record(
        payer: String = "GPAYER",
        nonce: Long = 7L,
        owner: String = "GMERCHANT",
        status: RecibidoStatus = RecibidoStatus.EN_TRANSITO,
    ) = ReceivedSobreRecord(
        id = ReceivedSobrePolicy.idOf(payer, nonce),
        owner = owner,
        sobreQr = "raiz:sobre:1:e30",
        authXdr = "AAAA",
        payer = payer,
        merchant = "GMERCHANT",
        amountStroops = 25_000_000L,
        tipBps = 200,
        feeBps = 50,
        exp = 1_000L,
        nonce = nonce,
        rol = RolRecibido.COBRO,
        status = status,
        receivedAtMs = 1_000L,
    )

    private fun newStore(prefs: MemoryPrefs = MemoryPrefs()): Pair<ReceivedSobreStore, MemoryPrefs> {
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                assertEquals("raiz_received_sobres", name)
                return prefs
            }
        }
        return ReceivedSobreStore(context) to prefs
    }

    // ── saveDurable ─────────────────────────────────────────────────────────

    @Test
    fun `saveDurable guarda con commit y un almacen nuevo lee lo escrito`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record()))
        assertTrue("el sobre recibido debe ir con commit(), no con apply()", prefs.lastWriteWasCommit)
        assertEquals("records_v1", prefs.lastKey)
        val (reabierto, _) = newStore(prefs)
        assertEquals(listOf(record()), reabierto.all())
        assertEquals(record(), reabierto.get("GPAYER:7"))
    }

    @Test
    fun `saveDurable no pisa el mismo sobre ya guardado, que puede llevar un diario abierto`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record()))
        store.mutate("GPAYER:7") { ReceivedSobrePolicy.markInFlight(it, hash1, 77L) }
        val commits = prefs.commits
        // Un segundo guardado del mismo sobre es idempotente: true, pero NO borra el diario.
        assertTrue(store.saveDurable(record()))
        assertEquals(hash1, store.get("GPAYER:7")!!.inFlightHash)
        assertEquals(commits, prefs.commits)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `saveDurable rechaza el sobre de otra wallet con el mismo id y no le quita el suyo`() {
        val (store, _) = newStore()
        assertTrue(store.saveDurable(record(owner = "GMERCHANT")))
        assertFalse(store.saveDurable(record(owner = "GOTRA")))
        assertEquals("GMERCHANT", store.get("GPAYER:7")!!.owner)
        // Mismo id y mismo dueño pero otro XDR: tampoco se pisa.
        assertFalse(store.saveDurable(record().copy(authXdr = "BBBB")))
        assertEquals("AAAA", store.get("GPAYER:7")!!.authXdr)
    }

    @Test
    fun `si el disco falla saveDurable devuelve false y la memoria sigue igual que el disco`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record(nonce = 1L)))
        prefs.failCommits = true
        assertFalse(store.saveDurable(record(nonce = 2L)))
        assertEquals(listOf("GPAYER:1"), store.all().map { it.id })
        prefs.failCommits = false
        assertTrue(store.saveDurable(record(nonce = 2L)))
        assertEquals(listOf("GPAYER:1", "GPAYER:2"), store.all().map { it.id })
    }

    // ── mutate ──────────────────────────────────────────────────────────────

    @Test
    fun `mutate parte del registro vivo y no pisa una escritura anterior`() {
        val (store, _) = newStore()
        assertTrue(store.saveDurable(record()))
        val viejo = store.get("GPAYER:7")!!
        store.mutate("GPAYER:7") { ReceivedSobrePolicy.markInFlight(it, hash1, 77L) }
        val siguiente = store.mutate("GPAYER:7") {
            ReceivedSobrePolicy.markSendFailed(it, "Fondos insuficientes", 5_000L, countAsSend = true)
        }!!
        assertEquals(hash1, siguiente.inFlightHash)
        assertEquals(1, siguiente.sendAttempts)
        assertEquals(siguiente, store.get("GPAYER:7"))
        assertNull("la copia vieja no cambia", viejo.inFlightHash)
    }

    @Test
    fun `mutate devuelve null si no existe o cambia la identidad, y no escribe`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record()))
        val commits = prefs.commits
        assertNull(store.mutate("GPAYER:99") { ReceivedSobrePolicy.markNonceConsumidoSinHash(it) })
        assertNull(store.mutate("GPAYER:7") { it.copy(id = "otro") })
        assertNull(store.mutate("GPAYER:7") { it.copy(owner = "GOTRA") })
        assertEquals(commits, prefs.commits)
        assertEquals(record(), store.get("GPAYER:7"))
    }

    @Test
    fun `mutate sin cambios devuelve el mismo registro y no escribe`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record(status = RecibidoStatus.CONFIRMADO)))
        val commits = prefs.commits
        val antes = store.get("GPAYER:7")!!
        assertSame(antes, store.mutate("GPAYER:7") { ReceivedSobrePolicy.markInFlight(it, hash1, 9L) })
        assertEquals(commits, prefs.commits)
    }

    @Test
    fun `si el disco falla mutate devuelve null y la memoria sigue igual`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record()))
        prefs.failCommits = true
        assertNull(store.mutate("GPAYER:7") { ReceivedSobrePolicy.markInFlight(it, hash1, 5L) })
        assertNull(store.get("GPAYER:7")!!.inFlightHash)
        prefs.failCommits = false
        assertNotNull(store.mutate("GPAYER:7") { ReceivedSobrePolicy.markInFlight(it, hash1, 5L) })
        assertEquals(hash1, store.get("GPAYER:7")!!.inFlightHash)
    }

    @Test
    fun `cien hilos anotando intentos a la vez no pierden ninguno`() {
        val (store, _) = newStore()
        assertTrue(store.saveDurable(record()))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(100)
        repeat(100) {
            pool.execute {
                start.await()
                store.mutate("GPAYER:7") { ReceivedSobrePolicy.markSendFailed(it, "x", 1L, countAsSend = true) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(100, store.get("GPAYER:7")!!.sendAttempts)
    }

    // ── refreshAll ──────────────────────────────────────────────────────────

    @Test
    fun `refreshAll aplica a toda la lista y solo escribe si algo cambia`() {
        val (store, prefs) = newStore()
        assertTrue(store.saveDurable(record(nonce = 1L)))
        assertTrue(store.saveDurable(record(nonce = 2L, status = RecibidoStatus.CONFIRMADO)))
        val writes = prefs.writes
        store.refreshAll { it } // sin cambios
        assertEquals(writes, prefs.writes)

        store.refreshAll { ReceivedSobrePolicy.refresh(it, estimate = 2_000L) }
        assertEquals(RecibidoStatus.EXPIRADO, store.get("GPAYER:1")!!.status)
        assertEquals(RecibidoStatus.CONFIRMADO, store.get("GPAYER:2")!!.status)
        assertEquals(writes + 1, prefs.writes)
        // Lo escrito sobrevive a un almacén nuevo.
        assertEquals(RecibidoStatus.EXPIRADO, newStore(prefs).first.get("GPAYER:1")!!.status)
    }

    // ── Lecturas ────────────────────────────────────────────────────────────

    @Test
    fun `el indice distingue wallet, pagador y nonce`() {
        val (store, _) = newStore()
        assertTrue(store.saveDurable(record(nonce = 1L)))
        assertTrue(store.saveDurable(record(nonce = 2L)))
        assertTrue(store.saveDurable(record(payer = "GOTROPAGADOR", nonce = 2L)))
        assertTrue(store.saveDurable(record(payer = "GP3", nonce = 5L, owner = "GOTRA")))

        assertEquals(record(nonce = 2L), store.seen("GMERCHANT", "GPAYER", 2L))
        assertEquals(record(payer = "GOTROPAGADOR", nonce = 2L), store.seen("GMERCHANT", "GOTROPAGADOR", 2L))
        assertNull(store.seen("GOTRA", "GPAYER", 2L))
        assertNull(store.seen("GMERCHANT", "GPAYER", 3L))
        assertEquals(3, store.recordsFor("GMERCHANT").size)
        assertEquals(1, store.recordsFor("GOTRA").size)
        assertTrue(store.recordsFor(null).isEmpty())
        assertTrue(store.recordsFor("GNADIE").isEmpty())
        assertNull(store.get("zzz"))

        store.mutate("GPAYER:1") { ReceivedSobrePolicy.markConfirmed(it, hash1) }
        assertEquals(2, store.pendingCount("GMERCHANT"))
        assertEquals(1, store.pendingCount("GOTRA"))
        assertEquals(0, store.pendingCount(null))
    }

    @Test
    fun `recordsFlow refleja cada cambio`() {
        val (store, _) = newStore()
        assertTrue(store.recordsFlow.value.isEmpty())
        assertTrue(store.saveDurable(record()))
        assertEquals(1, store.recordsFlow.value.size)
        store.mutate("GPAYER:7") { ReceivedSobrePolicy.markConfirmed(it, hash1) }
        assertEquals(RecibidoStatus.CONFIRMADO, store.recordsFlow.value.single().status)
    }

    // ── Tope por wallet y archivo corrupto ──────────────────────────────────

    @Test
    fun `el tope por wallet tiene un valor por omision, persiste y no se mezcla`() {
        val (store, prefs) = newStore()
        assertEquals(200_000_000L, store.topeStroops("GA"))
        store.setTopeStroops("GA", 50_000_000L)
        store.setTopeStroops("GB", 0L)
        assertEquals(50_000_000L, store.topeStroops("GA"))
        assertEquals(0L, store.topeStroops("GB"))
        assertEquals(200_000_000L, store.topeStroops("GC"))
        store.setTopeStroops("GA", -5L)
        assertEquals(0L, store.topeStroops("GA"))
        assertEquals(0L, newStore(prefs).first.topeStroops("GB"))
        assertEquals("tope_GA", prefs.lastKey)
    }

    @Test
    fun `un archivo corrupto se lee vacio y no impide guardar`() {
        val prefs = MemoryPrefs().also { it.putRaw("records_v1", "{basura") }
        val (store, _) = newStore(prefs)
        assertTrue(store.all().isEmpty())
        assertTrue(store.saveDurable(record()))
        assertEquals(1, store.all().size)
    }
}

/** SharedPreferences en memoria con contadores de escrituras y un interruptor para simular un disco que falla. */
private class MemoryPrefs : SharedPreferences {
    private val data = HashMap<String, Any?>()
    @Volatile var failCommits = false
    @Volatile var commits = 0
    @Volatile var writes = 0
    @Volatile var lastWriteWasCommit = false
    @Volatile var lastKey: String? = null

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
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = MemoryEditor()

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()

        override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[key!!] = values }
        override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
        override fun remove(key: String?) = apply { pending.remove(key!!) }
        override fun clear() = apply { pending.clear() }

        private fun flush() {
            synchronized(data) {
                data.putAll(pending)
                writes++
                pending.keys.lastOrNull()?.let { lastKey = it }
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
