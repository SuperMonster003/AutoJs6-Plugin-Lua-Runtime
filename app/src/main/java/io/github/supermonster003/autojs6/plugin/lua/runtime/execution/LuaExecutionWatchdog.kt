package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

/**
 * Last-resort reason for terminating the dedicated Lua runtime process.
 *
 * Termination is deliberately process-scoped: a wedged native call cannot be safely detached from
 * the single execution worker or reused by a later Binder session.
 */
internal enum class LuaProcessTerminationReason {
    DEADLINE_CLEANUP_EXPIRED,
    STOP_CLEANUP_EXPIRED,
    WATCHDOG_CONTROL_FAILURE,
}

internal fun interface LuaRuntimeProcessTerminator {
    fun terminate(reason: LuaProcessTerminationReason)
}

/** Runs synchronously after a watchdog token is poisoned and before process termination. */
internal fun interface LuaProcessTerminationObserver {
    fun beforeTermination(token: Any, reason: LuaProcessTerminationReason)

    companion object {
        val NONE = LuaProcessTerminationObserver { _, _ -> Unit }
    }
}

internal fun interface LuaWatchdogTask {
    fun cancel(): Boolean
}

/** Implementations must enqueue [task]; they must never invoke it inline from [schedule]. */
internal fun interface LuaWatchdogScheduler {
    fun schedule(delayNanos: Long, task: Runnable): LuaWatchdogTask
}

/** Per-session handle. Calls are idempotent and are bound to an unforgeable process-local token. */
internal interface LuaExecutionWatchdogLease : AutoCloseable {
    /** Returns false when dispatch must fail closed because the guard could not be armed. */
    fun executionDispatched(): Boolean

    fun stopRequested()

    override fun close()
}

/**
 * Process-wide fail-stop guard for the single Lua worker.
 *
 * The ordinary cancellation probe remains the first line of defence. This guard only terminates
 * the dedicated `:lua_runtime` process when a dispatched worker does not return by the end-to-end
 * deadline plus [cleanupGraceNanos], or after cancel/close/Binder death plus that same grace.
 *
 * A scheduled callback must still own the exact active token before it can poison and terminate the
 * process. Consequently a cancelled callback from an older execution cannot kill a replacement
 * session. Once termination is requested the watchdog stays poisoned, so a terminator that returns
 * unexpectedly cannot admit more work into a process whose worker state is unknown.
 */
internal class LuaExecutionWatchdog(
    private val clock: LuaMonotonicClock,
    private val scheduler: LuaWatchdogScheduler,
    private val terminator: LuaRuntimeProcessTerminator,
    private val terminationObserver: LuaProcessTerminationObserver = LuaProcessTerminationObserver.NONE,
    cleanupGraceMillis: Long,
) {
    data class Snapshot(
        val active: Boolean,
        val dispatched: Boolean,
        val stopRequested: Boolean,
        val poisoned: Boolean,
    )

    private class ActiveExecution(
        val token: Any,
        val createdNanos: Long,
        val timeoutNanos: Long,
    ) {
        var dispatched = false
        var stopRequestedNanos: Long? = null
        var deadlineTask: LuaWatchdogTask? = null
        var stopTask: LuaWatchdogTask? = null
    }

    private val cleanupGraceNanos = millisecondsToNanos(cleanupGraceMillis)
    private val lock = Any()
    private var active: ActiveExecution? = null
    private var poisoned = false

    init {
        require(cleanupGraceMillis > 0L) { "Lua watchdog cleanup grace must be positive" }
    }

    fun tryAcquire(
        token: Any,
        createdNanos: Long,
        timeoutMillis: Long,
    ): LuaExecutionWatchdogLease? {
        require(timeoutMillis > 0L) { "Lua watchdog timeout must be positive" }
        synchronized(lock) {
            if (poisoned || active != null) return null
            active = ActiveExecution(
                token = token,
                createdNanos = createdNanos,
                timeoutNanos = millisecondsToNanos(timeoutMillis),
            )
        }
        return Lease(this, token)
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            active = active != null,
            dispatched = active?.dispatched == true,
            stopRequested = active?.stopRequestedNanos != null,
            poisoned = poisoned,
        )
    }

    private fun executionDispatched(token: Any): Boolean {
        var controlFailure = false
        var immediateTermination: LuaProcessTerminationReason? = null
        val armed = synchronized(lock) {
            val execution = active
            if (execution == null || execution.token !== token) return@synchronized false
            if (poisoned) return@synchronized false
            if (execution.dispatched) return@synchronized true
            execution.dispatched = true
            val deadlineDelayNanos = remainingDelay(
                startedNanos = execution.createdNanos,
                budgetNanos = saturatedAdd(execution.timeoutNanos, cleanupGraceNanos),
            )
            val stopDelayNanos = execution.stopRequestedNanos?.let { stoppedNanos ->
                remainingDelay(stoppedNanos, cleanupGraceNanos)
            }
            immediateTermination = when {
                stopDelayNanos != null && stopDelayNanos <= 0L ->
                    LuaProcessTerminationReason.STOP_CLEANUP_EXPIRED
                deadlineDelayNanos <= 0L ->
                    LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED
                else -> null
            }
            if (immediateTermination != null) {
                poisonIfOwned(execution)
                observeTerminationLocked(token, checkNotNull(immediateTermination))
                return@synchronized false
            }
            try {
                execution.deadlineTask = scheduleTermination(
                    execution = execution,
                    delayNanos = deadlineDelayNanos,
                    reason = LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED,
                )
                stopDelayNanos?.let { delayNanos ->
                    execution.stopTask = scheduleTermination(
                        execution = execution,
                        delayNanos = delayNanos,
                        reason = LuaProcessTerminationReason.STOP_CLEANUP_EXPIRED,
                    )
                }
            } catch (_: Throwable) {
                poisonIfOwned(execution)
                observeTerminationLocked(token, LuaProcessTerminationReason.WATCHDOG_CONTROL_FAILURE)
                controlFailure = true
            }
            !controlFailure
        }
        immediateTermination?.let(::terminateProcess)
        if (controlFailure) terminateProcess(LuaProcessTerminationReason.WATCHDOG_CONTROL_FAILURE)
        return armed
    }

    private fun stopRequested(token: Any) {
        var controlFailure = false
        synchronized(lock) {
            val execution = active
            if (execution == null || execution.token !== token) return
            if (poisoned || execution.stopRequestedNanos != null) return
            execution.stopRequestedNanos = clock.nanoTime()
            if (execution.dispatched) {
                try {
                    execution.stopTask = scheduleTermination(
                        execution = execution,
                        delayNanos = cleanupGraceNanos,
                        reason = LuaProcessTerminationReason.STOP_CLEANUP_EXPIRED,
                    )
                } catch (_: Throwable) {
                    poisonIfOwned(execution)
                    observeTerminationLocked(token, LuaProcessTerminationReason.WATCHDOG_CONTROL_FAILURE)
                    controlFailure = true
                }
            }
        }
        if (controlFailure) terminateProcess(LuaProcessTerminationReason.WATCHDOG_CONTROL_FAILURE)
    }

    private fun finished(token: Any) {
        val tasks = synchronized(lock) {
            val execution = active
            if (execution == null || execution.token !== token) return
            active = null
            listOfNotNull(execution.deadlineTask, execution.stopTask)
        }
        tasks.forEach { task -> runCatching { task.cancel() } }
    }

    private fun scheduleTermination(
        execution: ActiveExecution,
        delayNanos: Long,
        reason: LuaProcessTerminationReason,
    ): LuaWatchdogTask = scheduler.schedule(
        delayNanos,
        Runnable { terminateIfOwned(execution.token, reason) },
    )

    private fun terminateIfOwned(token: Any, reason: LuaProcessTerminationReason) {
        val shouldTerminate = synchronized(lock) {
            val execution = active
            if (poisoned || execution == null || execution.token !== token || !execution.dispatched) {
                false
            } else {
                poisoned = true
                observeTerminationLocked(token, reason)
                true
            }
        }
        if (shouldTerminate) terminateProcess(reason)
    }

    private fun poisonIfOwned(execution: ActiveExecution) {
        if (active === execution) poisoned = true
    }

    /** Called only while [lock] is owned, so a finishing worker cannot clear its record first. */
    private fun observeTerminationLocked(token: Any, reason: LuaProcessTerminationReason) {
        runCatching { terminationObserver.beforeTermination(token, reason) }
    }

    private fun terminateProcess(reason: LuaProcessTerminationReason) {
        runCatching { terminator.terminate(reason) }
    }

    private fun remainingDelay(startedNanos: Long, budgetNanos: Long): Long {
        val elapsed = (clock.nanoTime() - startedNanos).coerceAtLeast(0L)
        return (budgetNanos - elapsed).coerceAtLeast(0L)
    }

    private class Lease(
        private val owner: LuaExecutionWatchdog,
        private val token: Any,
    ) : LuaExecutionWatchdogLease {
        override fun executionDispatched(): Boolean = owner.executionDispatched(token)

        override fun stopRequested() = owner.stopRequested(token)

        override fun close() = owner.finished(token)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        fun millisecondsToNanos(milliseconds: Long): Long {
            require(milliseconds <= Long.MAX_VALUE / NANOS_PER_MILLI) {
                "Lua watchdog duration overflows nanoseconds"
            }
            return milliseconds * NANOS_PER_MILLI
        }

        fun saturatedAdd(left: Long, right: Long): Long =
            if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
