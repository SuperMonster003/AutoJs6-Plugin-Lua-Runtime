package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleActiveExecutionGateTest {
    @Test
    fun rejectsWithoutQueueAndOnlyOwnerCanRelease() {
        val gate = SingleActiveExecutionGate<Any>()
        val first = Any()
        val second = Any()

        assertTrue(gate.tryAcquire(first))
        assertFalse(gate.tryAcquire(second))
        assertSame(first, gate.current())
        assertFalse(gate.release(second))
        assertTrue(gate.release(first))
        assertNull(gate.current())
        assertTrue(gate.tryAcquire(second))
    }
}
