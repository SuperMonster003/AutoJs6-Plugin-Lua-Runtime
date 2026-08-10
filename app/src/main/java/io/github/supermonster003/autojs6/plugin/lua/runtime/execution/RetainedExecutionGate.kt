package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Hard bound for active plus typed-rejection sessions retained in the process. */
internal class RetainedExecutionGate(private val maximumRetained: Int) {
    private val retained = AtomicInteger()

    init {
        require(maximumRetained > 0) { "Retained execution bound must be positive" }
    }

    fun tryAcquire(): RetainedExecutionLease? {
        while (true) {
            val current = retained.get()
            if (current >= maximumRetained) return null
            if (retained.compareAndSet(current, current + 1)) {
                return RetainedExecutionLease(::release)
            }
        }
    }

    fun retainedCount(): Int = retained.get()

    private fun release() {
        val remaining = retained.decrementAndGet()
        check(remaining >= 0) { "Retained execution accounting underflow" }
    }
}

internal class RetainedExecutionLease(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
