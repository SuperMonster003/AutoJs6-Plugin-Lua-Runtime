package io.github.supermonster003.autojs6.plugin.lua.runtime.diagnostic

import android.content.Context
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaProcessTerminationObserver
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaProcessTerminationReason
import org.autojs.plugin.lua.runtime.api.LuaSha256

internal interface LuaExecutionCrashDiagnosticLease : AutoCloseable {
    fun sourceValidationStarted()

    fun nativeExecutionStarted()

    fun nativeExecutionReturned()

    override fun close()

    companion object {
        val NONE = object : LuaExecutionCrashDiagnosticLease {
            override fun sourceValidationStarted() = Unit
            override fun nativeExecutionStarted() = Unit
            override fun nativeExecutionReturned() = Unit
            override fun close() = Unit
        }
    }
}

internal class LuaCrashDiagnosticCoordinator(
    private val store: LuaCrashDiagnosticStore,
) : LuaProcessTerminationObserver {
    private class ActiveExecution(
        val token: Any,
        val sourceHashPrefix: ByteArray,
    ) {
        var phase = LuaCrashPhase.QUEUE
        var provisionalNativeCrashWritten = false
        var terminationCommitted = false
    }

    private val lock = Any()
    private var active: ActiveExecution? = null

    fun acquire(token: Any, sourceSha256: LuaSha256): LuaExecutionCrashDiagnosticLease {
        val execution = ActiveExecution(
            token = token,
            sourceHashPrefix = sourceSha256.toByteArray().copyOf(LuaCrashDiagnostic.SOURCE_HASH_PREFIX_BYTES),
        )
        synchronized(lock) {
            check(active == null) { "Lua crash diagnostic already owns an active execution" }
            active = execution
        }
        return Lease(this, token)
    }

    fun hasReportableDiagnostic(): Boolean = synchronized(lock) {
        val execution = active
        if (execution?.provisionalNativeCrashWritten == true && !execution.terminationCommitted) {
            false
        } else {
            store.read() != null
        }
    }

    override fun beforeTermination(token: Any, reason: LuaProcessTerminationReason) = synchronized(lock) {
        val execution = active
        if (execution == null || execution.token !== token || execution.terminationCommitted) return
        store.write(
            LuaCrashDiagnostic(
                failureKind = reason.toCrashFailureKind(),
                phase = execution.phase,
                sourceSha256Prefix = execution.sourceHashPrefix,
            ),
        )
        execution.terminationCommitted = true
    }

    private fun sourceValidationStarted(token: Any) = synchronized(lock) {
        owned(token).phase = LuaCrashPhase.SOURCE_VALIDATION
    }

    private fun nativeExecutionStarted(token: Any) = synchronized(lock) {
        val execution = owned(token)
        execution.phase = LuaCrashPhase.NATIVE_EXECUTION
        store.write(
            LuaCrashDiagnostic(
                failureKind = LuaCrashFailureKind.NATIVE_CRASH,
                phase = execution.phase,
                sourceSha256Prefix = execution.sourceHashPrefix,
            ),
        )
        execution.provisionalNativeCrashWritten = true
    }

    private fun nativeExecutionReturned(token: Any) = synchronized(lock) {
        val execution = owned(token)
        if (!execution.terminationCommitted && execution.provisionalNativeCrashWritten) {
            store.clear()
            execution.provisionalNativeCrashWritten = false
        }
    }

    private fun finished(token: Any) = synchronized(lock) {
        val execution = active
        if (execution == null || execution.token !== token) return
        try {
            if (!execution.terminationCommitted && execution.provisionalNativeCrashWritten) {
                store.clear()
            }
        } finally {
            active = null
        }
    }

    private fun owned(token: Any): ActiveExecution {
        val execution = active
        check(execution != null && execution.token === token) {
            "Lua crash diagnostic lease no longer owns the active execution"
        }
        return execution
    }

    private class Lease(
        private val owner: LuaCrashDiagnosticCoordinator,
        private val token: Any,
    ) : LuaExecutionCrashDiagnosticLease {
        override fun sourceValidationStarted() = owner.sourceValidationStarted(token)
        override fun nativeExecutionStarted() = owner.nativeExecutionStarted(token)
        override fun nativeExecutionReturned() = owner.nativeExecutionReturned(token)
        override fun close() = owner.finished(token)
    }
}

internal object LuaRuntimeCrashDiagnostics : LuaProcessTerminationObserver {
    @Volatile
    private var coordinator: LuaCrashDiagnosticCoordinator? = null

    fun initialize(context: Context) = synchronized(this) {
        if (coordinator == null) {
            coordinator = LuaCrashDiagnosticCoordinator(LuaCrashDiagnosticStore.forContext(context))
        }
    }

    fun requireInitialized() {
        check(coordinator != null) { "Lua runtime crash diagnostics were not initialized" }
    }

    fun acquire(token: Any, sourceSha256: LuaSha256): LuaExecutionCrashDiagnosticLease =
        current().acquire(token, sourceSha256)

    fun reportedCapabilities(executionCapabilities: Collection<String>): List<String> =
        withLastAbnormalTerminationFlag(executionCapabilities, current().hasReportableDiagnostic())

    override fun beforeTermination(token: Any, reason: LuaProcessTerminationReason) {
        current().beforeTermination(token, reason)
    }

    private fun current(): LuaCrashDiagnosticCoordinator = checkNotNull(coordinator) {
        "Lua runtime crash diagnostics were not initialized"
    }
}

internal fun withLastAbnormalTerminationFlag(
    executionCapabilities: Collection<String>,
    present: Boolean,
): List<String> {
    val capabilities = executionCapabilities.toMutableList()
    if (present && LAST_ABNORMAL_TERMINATION_FLAG !in capabilities) {
        capabilities += LAST_ABNORMAL_TERMINATION_FLAG
    }
    return capabilities.toList()
}

private fun LuaProcessTerminationReason.toCrashFailureKind(): LuaCrashFailureKind = when (this) {
    LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED ->
        LuaCrashFailureKind.DEADLINE_CLEANUP_EXPIRED
    LuaProcessTerminationReason.STOP_CLEANUP_EXPIRED ->
        LuaCrashFailureKind.STOP_CLEANUP_EXPIRED
    LuaProcessTerminationReason.WATCHDOG_CONTROL_FAILURE ->
        LuaCrashFailureKind.WATCHDOG_CONTROL_FAILURE
}
