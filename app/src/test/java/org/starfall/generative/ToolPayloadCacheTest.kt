package org.starfall.generative

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.ToolPayload
import org.starfall.generative.shared.model.ToolPayloadCache
import org.starfall.generative.shared.model.ToolPayloadSource

class ToolPayloadCacheTest {
    private class Source(override val key: Any, private val text: String) : ToolPayloadSource {
        val reads = AtomicInteger(0)
        override suspend fun load(): String {
            reads.incrementAndGet()
            return text
        }
    }

    @Test fun thousandsOfSavedShortPayloadsAreReclaimableAndReloadAfterEviction() = runBlocking {
        val previousLimit = ToolPayloadCache.maxChars
        try {
            ToolPayloadCache.clear()
            ToolPayloadCache.maxChars = 64
            var first: ToolPayload? = null
            val origin = Source("first", "value-0")
            repeat(2_000) { index ->
                val payload = ToolPayload.of("value-$index")
                payload.attachSource(if (index == 0) origin else Source(index, "value-$index"))
                assertFalse("Saved tool text must not remain strongly held", payload.isResident)
                if (index == 0) first = payload
            }
            assertNull("Oldest value must leave the bounded cache", first!!.peek())
            assertEquals("value-0", first!!.text())
            assertEquals(1, origin.reads.get())
            ToolPayloadCache.clear()
            assertNull(first!!.peek())
            assertEquals("value-0", first!!.text())
            assertEquals(2, origin.reads.get())
        } finally {
            ToolPayloadCache.maxChars = previousLimit
            ToolPayloadCache.clear()
        }
    }

    @Test fun coldLoadedSmallPayloadIsAlsoEvictable() = runBlocking {
        ToolPayloadCache.clear()
        val origin = Source("stored-short", "from-room")
        val loaded = ToolPayload.stored("from-room", origin)
        assertFalse(loaded.isResident)
        assertEquals("from-room", loaded.peek())
        ToolPayloadCache.clear()
        assertNull(loaded.peek())
        assertEquals("from-room", loaded.text())
        assertEquals(1, origin.reads.get())
        ToolPayloadCache.clear()
    }

    @Test fun inFlightFailedSaveAndTemporaryTextRemainStrongWhenCacheIsCleared() = runBlocking {
        val pending = ToolPayload.of("unsaved arguments")
        val failedSave = ToolPayload.of("transaction failed")
        val temporary = ToolPayload.of("temporary chat result")
        ToolPayloadCache.clear()
        for (payload in listOf(pending, failedSave, temporary)) {
            assertTrue(payload.isUnrecoverable)
            assertTrue(payload.isResident)
            assertNotNull(payload.peek())
            assertEquals(payload.peek(), payload.text())
        }
    }

    @Test fun slowBackingStoreReloadNeverRunsOnCallingThread() = runBlocking {
        ToolPayloadCache.clear()
        val callingThread = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val payload = ToolPayload.stored(7, object : ToolPayloadSource {
            override val key: Any = "slow-store"
            override suspend fun load(): String {
                assertNotEquals("Storage must not run on the caller's thread", callingThread, Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Store was never released" }
                return "delayed"
            }
        })
        try {
            val reload = async(start = CoroutineStart.UNDISPATCHED) { payload.text() }
            assertFalse("Reload must suspend instead of blocking the caller", reload.isCompleted)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("delayed", reload.await())
        } finally {
            release.countDown()
            ToolPayloadCache.clear()
        }
    }
}
