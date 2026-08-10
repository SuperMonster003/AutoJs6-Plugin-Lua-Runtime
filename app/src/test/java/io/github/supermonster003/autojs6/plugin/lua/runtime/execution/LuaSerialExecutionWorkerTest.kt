package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LuaSerialExecutionWorkerTest {
    @Test
    fun occupiedWorkerRejectsInsteadOfQueueing() {
        val worker = LuaSerialExecutionWorker("lua-worker-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            assertTrue(
                worker.dispatch(
                    Runnable {
                        entered.countDown()
                        release.await(2, TimeUnit.SECONDS)
                        finished.countDown()
                    },
                ),
            )
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(worker.dispatch(Runnable {}))
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            worker.close()
        }
        assertFalse(worker.dispatch(Runnable {}))
    }
}
