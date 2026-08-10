package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun interface LuaExecutionDispatcher {
    /** Returns false instead of queueing when the single worker is occupied. */
    fun dispatch(task: Runnable): Boolean
}

internal class LuaSerialExecutionWorker(
    threadName: String = "autojs-lua-execution",
) : LuaExecutionDispatcher, AutoCloseable {
    // Let execute() create the core thread with its firstTask. Prestarting a core thread with a
    // SynchronousQueue creates a startup window where no consumer is waiting and the first task is
    // spuriously rejected, even though the worker is idle.
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        DaemonThreadFactory(threadName),
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun dispatch(task: Runnable): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private class DaemonThreadFactory(private val baseName: String) : ThreadFactory {
    private val sequence = AtomicInteger()

    override fun newThread(task: Runnable): Thread = Thread(task).apply {
        name = "$baseName-${sequence.incrementAndGet()}"
        isDaemon = true
    }
}
