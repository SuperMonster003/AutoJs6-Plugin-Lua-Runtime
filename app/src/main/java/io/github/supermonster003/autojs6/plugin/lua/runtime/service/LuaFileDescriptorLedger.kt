package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Logical ownership classes for every PFD crossing the runtime Binder boundary. */
internal enum class LuaFileDescriptorKind {
    INCOMING_SOURCE,
    DUPLICATED_SOURCE,
    HOST_CALLBACK_PAYLOAD,
    RESULT_CALLBACK_PAYLOAD,
}

internal data class LuaFileDescriptorBalance(
    val acquired: Long,
    val released: Long,
) {
    val outstanding: Long
        get() = acquired - released
}

internal data class LuaFileDescriptorLedgerSnapshot(
    val balances: Map<LuaFileDescriptorKind, LuaFileDescriptorBalance>,
) {
    val acquired: Long
        get() = balances.values.sumOf(LuaFileDescriptorBalance::acquired)

    val released: Long
        get() = balances.values.sumOf(LuaFileDescriptorBalance::released)

    val outstanding: Long
        get() = acquired - released

    val isBalanced: Boolean
        get() = outstanding == 0L && balances.values.all { it.outstanding == 0L }

    fun balance(kind: LuaFileDescriptorKind): LuaFileDescriptorBalance =
        checkNotNull(balances[kind]) { "Missing Lua file-descriptor balance for $kind" }
}

/**
 * Process-local logical ledger. A lease represents one owned PFD and releases at most once even
 * when cleanup converges through source-read, controller-finish, close, and Binder-death paths.
 */
internal class LuaFileDescriptorLedger {
    private class Counters {
        val acquired = AtomicLong()
        val released = AtomicLong()
    }

    private val counters = LuaFileDescriptorKind.entries.associateWith { Counters() }

    fun acquire(kind: LuaFileDescriptorKind): LuaFileDescriptorLease {
        val ownedCounters = checkNotNull(counters[kind])
        val lease = LuaFileDescriptorLease { ownedCounters.released.incrementAndGet() }
        ownedCounters.acquired.incrementAndGet()
        return lease
    }

    fun snapshot(): LuaFileDescriptorLedgerSnapshot = LuaFileDescriptorLedgerSnapshot(
        LuaFileDescriptorKind.entries.associateWith { kind ->
            val ownedCounters = checkNotNull(counters[kind])
            LuaFileDescriptorBalance(
                acquired = ownedCounters.acquired.get(),
                released = ownedCounters.released.get(),
            )
        },
    )
}

internal class LuaFileDescriptorLease(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
