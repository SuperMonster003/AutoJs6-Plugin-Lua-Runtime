package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class LuaExecutionWatchdogTest {
    @Test
    fun endToEndDeadlineIncludesOnlyOneBoundedCleanupGrace() {
        val now = AtomicLong(40.millis)
        val scheduler = ManualScheduler()
        val terminations = mutableListOf<LuaProcessTerminationReason>()
        val watchdog = watchdog(now, scheduler, terminations, cleanupGraceMillis = 20L)
        val lease = checkNotNull(watchdog.tryAcquire(Any(), createdNanos = 0L, timeoutMillis = 100L))

        assertTrue(lease.executionDispatched())
        assertEquals(80.millis, scheduler.entries.single().delayNanos)
        scheduler.run(0)

        assertEquals(listOf(LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED), terminations)
        assertTrue(watchdog.snapshot().poisoned)
        assertNull(watchdog.tryAcquire(Any(), createdNanos = now.get(), timeoutMillis = 100L))
    }

    @Test
    fun stopBeforeDispatchKeepsItsOriginalGraceDeadline() {
        val now = AtomicLong(10.millis)
        val scheduler = ManualScheduler()
        val terminations = mutableListOf<LuaProcessTerminationReason>()
        val watchdog = watchdog(now, scheduler, terminations, cleanupGraceMillis = 100L)
        val lease = checkNotNull(watchdog.tryAcquire(Any(), createdNanos = 0L, timeoutMillis = 1_000L))

        lease.stopRequested()
        now.set(30.millis)
        assertTrue(lease.executionDispatched())

        assertEquals(2, scheduler.entries.size)
        assertEquals(80.millis, scheduler.entries[1].delayNanos)
        scheduler.run(1)
        assertEquals(listOf(LuaProcessTerminationReason.STOP_CLEANUP_EXPIRED), terminations)
    }

    @Test
    fun expiredDeadlineFailsClosedBeforeWorkerDispatchEvenIfTerminatorReturns() {
        val now = AtomicLong(120.millis)
        val scheduler = ManualScheduler()
        val terminations = mutableListOf<LuaProcessTerminationReason>()
        val watchdog = watchdog(now, scheduler, terminations, cleanupGraceMillis = 20L)
        val lease = checkNotNull(watchdog.tryAcquire(Any(), createdNanos = 0L, timeoutMillis = 100L))

        assertFalse(lease.executionDispatched())
        assertTrue(scheduler.entries.isEmpty())
        assertEquals(listOf(LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED), terminations)
        assertTrue(watchdog.snapshot().poisoned)
        assertNull(watchdog.tryAcquire(Any(), createdNanos = now.get(), timeoutMillis = 100L))
    }

    @Test
    fun expiredStopGraceFailsClosedBeforeWorkerDispatch() {
        val now = AtomicLong(0L)
        val scheduler = ManualScheduler()
        val terminations = mutableListOf<LuaProcessTerminationReason>()
        val watchdog = watchdog(now, scheduler, terminations, cleanupGraceMillis = 100L)
        val lease = checkNotNull(watchdog.tryAcquire(Any(), createdNanos = 0L, timeoutMillis = 1_000L))

        lease.stopRequested()
        now.set(100.millis)

        assertFalse(lease.executionDispatched())
        assertTrue(scheduler.entries.isEmpty())
        assertEquals(listOf(LuaProcessTerminationReason.STOP_CLEANUP_EXPIRED), terminations)
        assertTrue(watchdog.snapshot().poisoned)
    }

    @Test
    fun normalFinishCancelsEveryTaskAndAStaleCallbackCannotKillReplacement() {
        val now = AtomicLong(0L)
        val scheduler = ManualScheduler()
        val terminations = mutableListOf<LuaProcessTerminationReason>()
        val watchdog = watchdog(now, scheduler, terminations)
        val first = checkNotNull(watchdog.tryAcquire(Any(), 0L, 100L))

        assertTrue(first.executionDispatched())
        first.stopRequested()
        first.close()
        assertTrue(scheduler.entries.take(2).all { it.cancelled })

        val second = checkNotNull(watchdog.tryAcquire(Any(), 0L, 100L))
        assertTrue(second.executionDispatched())
        scheduler.run(index = 0, evenIfCancelled = true)
        scheduler.run(index = 1, evenIfCancelled = true)
        assertTrue(terminations.isEmpty())
        assertTrue(watchdog.snapshot().active)
        assertFalse(watchdog.snapshot().poisoned)

        scheduler.run(index = 2)
        assertEquals(listOf(LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED), terminations)
    }

    @Test
    fun repeatedStopDoesNotExtendCleanupGrace() {
        val now = AtomicLong(0L)
        val scheduler = ManualScheduler()
        val watchdog = watchdog(now, scheduler, mutableListOf())
        val lease = checkNotNull(watchdog.tryAcquire(Any(), 0L, 1_000L))

        assertTrue(lease.executionDispatched())
        lease.stopRequested()
        now.set(50.millis)
        lease.stopRequested()

        assertEquals(2, scheduler.entries.size)
        assertEquals(DEFAULT_GRACE_MILLIS.millis, scheduler.entries[1].delayNanos)
    }

    @Test
    fun schedulerFailurePoisonsProcessAndFailsDispatchClosed() {
        val now = AtomicLong(0L)
        val terminations = mutableListOf<LuaProcessTerminationReason>()
        val watchdog = LuaExecutionWatchdog(
            clock = LuaMonotonicClock(now::get),
            scheduler = LuaWatchdogScheduler { _, _ -> error("scheduler rejected") },
            terminator = LuaRuntimeProcessTerminator(terminations::add),
            cleanupGraceMillis = DEFAULT_GRACE_MILLIS,
        )
        val lease = checkNotNull(watchdog.tryAcquire(Any(), 0L, 100L))

        assertFalse(lease.executionDispatched())
        assertEquals(listOf(LuaProcessTerminationReason.WATCHDOG_CONTROL_FAILURE), terminations)
        assertTrue(watchdog.snapshot().poisoned)
        lease.close()
        assertNull(watchdog.tryAcquire(Any(), 0L, 100L))
    }

    @Test
    fun terminationObserverRunsBeforeTerminatorAndCannotSuppressFailStop() {
        val now = AtomicLong(0L)
        val scheduler = ManualScheduler()
        val token = Any()
        val events = mutableListOf<String>()
        val watchdog = LuaExecutionWatchdog(
            clock = LuaMonotonicClock(now::get),
            scheduler = scheduler,
            terminator = LuaRuntimeProcessTerminator { events += "terminate" },
            terminationObserver = LuaProcessTerminationObserver { observedToken, reason ->
                assertTrue(observedToken === token)
                assertEquals(LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED, reason)
                events += "diagnostic"
                error("injected diagnostic failure")
            },
            cleanupGraceMillis = DEFAULT_GRACE_MILLIS,
        )
        val lease = checkNotNull(watchdog.tryAcquire(token, 0L, 100L))

        assertTrue(lease.executionDispatched())
        scheduler.run(0)

        assertEquals(listOf("diagnostic", "terminate"), events)
        assertTrue(watchdog.snapshot().poisoned)
    }

    private fun watchdog(
        now: AtomicLong,
        scheduler: ManualScheduler,
        terminations: MutableList<LuaProcessTerminationReason>,
        cleanupGraceMillis: Long = DEFAULT_GRACE_MILLIS,
    ) = LuaExecutionWatchdog(
        clock = LuaMonotonicClock(now::get),
        scheduler = scheduler,
        terminator = LuaRuntimeProcessTerminator(terminations::add),
        cleanupGraceMillis = cleanupGraceMillis,
    )

    private class ManualScheduler : LuaWatchdogScheduler {
        data class Entry(
            val delayNanos: Long,
            val task: Runnable,
            var cancelled: Boolean = false,
        )

        val entries = mutableListOf<Entry>()

        override fun schedule(delayNanos: Long, task: Runnable): LuaWatchdogTask {
            val entry = Entry(delayNanos, task)
            entries += entry
            return LuaWatchdogTask {
                val changed = !entry.cancelled
                entry.cancelled = true
                changed
            }
        }

        fun run(index: Int, evenIfCancelled: Boolean = false) {
            val entry = entries[index]
            if (evenIfCancelled || !entry.cancelled) entry.task.run()
        }
    }

}

private const val DEFAULT_GRACE_MILLIS = 100L
private val Long.millis: Long get() = this * 1_000_000L
private val Int.millis: Long get() = toLong().millis
