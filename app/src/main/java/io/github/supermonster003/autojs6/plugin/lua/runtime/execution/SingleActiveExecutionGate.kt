package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local admission gate. Rejected executions are never queued and the
 * owner must release itself, preventing an old session from releasing a newer one.
 */
internal class SingleActiveExecutionGate<T : Any> {
    private val active = AtomicReference<T?>()

    fun tryAcquire(candidate: T): Boolean = active.compareAndSet(null, candidate)

    fun release(owner: T): Boolean = active.compareAndSet(owner, null)

    fun current(): T? = active.get()
}
