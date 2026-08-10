package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LuaCallbackDeliveryGateTest {
    @Test
    fun closeRacingWithDeliveryDefersCleanupAndRejectsLateDelivery() {
        val closeCalls = AtomicInteger()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val gate = LuaCallbackDeliveryGate { closeCalls.incrementAndGet() }
        val callbackThread = Thread {
            delivered.set(
                gate.deliver {
                    callbackEntered.countDown()
                    assertTrue(releaseCallback.await(2, TimeUnit.SECONDS))
                },
            )
        }

        callbackThread.start()
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
        gate.close()

        assertEquals(0, closeCalls.get())
        assertFalse(gate.deliver { error("late callback must not run") })

        releaseCallback.countDown()
        callbackThread.join(2_000L)
        assertFalse(callbackThread.isAlive)
        assertTrue(delivered.get())
        assertEquals(1, closeCalls.get())
        gate.close()
        assertEquals(1, closeCalls.get())
    }
}
