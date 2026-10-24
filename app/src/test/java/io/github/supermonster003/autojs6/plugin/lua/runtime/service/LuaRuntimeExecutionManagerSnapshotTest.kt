package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class LuaRuntimeExecutionManagerSnapshotTest {
    @Test
    fun snapshotSurvivesLastSessionFinishingDuringClose() {
        val live = ConcurrentHashMap.newKeySet<String>().apply { add("last") }
        // The last execution finishes right after the size is read, as onFinished removes it outside the lock.
        val racing = object : Collection<String> by live {
            override val size: Int
                get() = live.size.also { live.clear() }
        }

        val snapshot = snapshotOf(racing)

        assertTrue(snapshot.size <= 1)
        assertTrue(snapshot.all { it == "last" })
    }

    @Test
    fun snapshotKeepsEverySessionStillLive() {
        val live = ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf("a", "b", "c")) }

        assertEquals(setOf("a", "b", "c"), snapshotOf(live).toSet())
    }
}
