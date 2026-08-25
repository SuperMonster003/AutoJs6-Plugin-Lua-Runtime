package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import android.os.Process
import android.util.Log
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaProcessTerminationReason
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRuntimeProcessTerminator
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaWatchdogEventLogger
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaWatchdogLogContract
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaWatchdogScheduler
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaWatchdogTask
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Process-lifetime scheduler. It must outlive a destroyed service while a worker is draining. */
internal object LuaRuntimeWatchdogScheduler : LuaWatchdogScheduler {
    private val THREAD_SEQUENCE = AtomicInteger()
    private val executor = ScheduledThreadPoolExecutor(
        1,
        { task ->
            Thread(task, "autojs-lua-watchdog-${THREAD_SEQUENCE.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
    ).apply {
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setRemoveOnCancelPolicy(true)
    }

    override fun schedule(delayNanos: Long, task: Runnable): LuaWatchdogTask {
        val future = executor.schedule(task, delayNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
        return LuaWatchdogTask { future.cancel(false) }
    }

}

/**
 * Fail-stop implementation for the manifest-isolated `:lua_runtime` process.
 *
 * `killProcess` is the Android-native path. `Runtime.halt` is an intentionally abrupt fallback;
 * no shutdown hook or finalizer may delay recovery from an untrusted native execution wedge.
 */
internal object AndroidLuaRuntimeProcessTerminator : LuaRuntimeProcessTerminator {
    override fun terminate(reason: LuaProcessTerminationReason) {
        val pid = Process.myPid()
        runCatching { Process.killProcess(pid) }
        Runtime.getRuntime().halt(HALT_STATUS_BASE + reason.ordinal)
    }

    private const val HALT_STATUS_BASE = 70
}

/** Emits one fixed-shape event immediately before the dedicated process is terminated. */
internal object AndroidLuaWatchdogEventLogger : LuaWatchdogEventLogger {
    override fun logFailStop(reason: LuaProcessTerminationReason) {
        Log.e(LuaWatchdogLogContract.LOGCAT_TAG, LuaWatchdogLogContract.message(reason))
    }
}
