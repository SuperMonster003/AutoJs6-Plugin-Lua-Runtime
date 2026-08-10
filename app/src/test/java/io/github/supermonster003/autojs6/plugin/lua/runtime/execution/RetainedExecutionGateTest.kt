package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetainedExecutionGateTest {
    @Test
    fun hardCapAndIdempotentLeasePreventUnboundedBusySessions() {
        val gate = RetainedExecutionGate(2)
        val first = checkNotNull(gate.tryAcquire())
        val second = checkNotNull(gate.tryAcquire())

        assertEquals(2, gate.retainedCount())
        assertNull(gate.tryAcquire())
        first.close()
        first.close()
        assertEquals(1, gate.retainedCount())
        val replacement = checkNotNull(gate.tryAcquire())
        assertEquals(2, gate.retainedCount())
        second.close()
        replacement.close()
        assertEquals(0, gate.retainedCount())
    }
}
