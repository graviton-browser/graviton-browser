package net.plan99.graviton

import com.google.common.jimfs.Jimfs
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryManagerTest {
    val example1 = HistoryEntry("com.github.spotbugs", Instant.now(), "com.github.spotbugs:spotbugs:jar:1.2.3", "zzz.jar:xxx.jar", "zzz app", "for sleeping")
    val example2 = HistoryEntry("net.plan99.graviton:ex", Instant.now(), "net.plan99.graviton:ex:jar:1.2.1", "a.jar:b.jar", "example app", "for examples")

    @Test
    fun happyPath() {
        val jimfs = Jimfs.newFileSystem()
        // Check it can be started with no history file.
        var manager = HistoryManager(jimfs.getPath("/"))
        // Not found.
        assertNull(manager.search("com.foo.bar"))
        // Record and re-fetch an entry.
        manager.recordHistoryEntry(example1)
        assertEquals(example1.resolvedArtifact, manager.search("com.github.spotbugs")?.resolvedArtifact)
        // Re-load.
        manager = HistoryManager(jimfs.getPath("/"))
        // It's still found.
        assertEquals(example1.resolvedArtifact, manager.search("com.github.spotbugs")?.resolvedArtifact)
        // Overflow the history list.
        repeat(manager.maxHistorySize + 1) {
            manager.recordHistoryEntry(example2.copy(coordinateFragment = "$it"))
        }
        assertEquals(manager.maxHistorySize, manager.history.size)
        // Now example 1 can't be found anymore, it's gone.
        assertNull(manager.search("com.github.spotbugs"))
    }

    @Test
    fun updateTime() {
        val clock = Clock.fixed(Instant.now(), ZoneId.of("Z"))
        val manager = HistoryManager(Jimfs.newFileSystem().getPath("/"), clock = clock)
        manager.recordHistoryEntry(example1)
        manager.recordHistoryEntry(example2)
        manager.clock = Clock.offset(clock, Duration.ofHours(1))
        // Check if we re-record the entry, it re-arranges the history list
        val e = manager.recordHistoryEntry(example1)
        assertEquals(e, manager.history[0])
        assertEquals(e.lastRefreshTime, manager.clock.instant())
        assertEquals(2, manager.history.size)
        // Now check we stop getting cached results when the entry expires.
        assertEquals(e.resolvedArtifact, manager.search(e.coordinateFragment)?.resolvedArtifact)
        manager.clock = Clock.offset(clock, Duration.ofDays(2))
        assertNull(manager.search(e.coordinateFragment))
    }
}