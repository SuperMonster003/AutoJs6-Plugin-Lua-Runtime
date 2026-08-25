package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

/** Fixed, content-free logcat contract for process fail-stop aggregation. */
internal object LuaWatchdogLogContract {
    const val LOGCAT_TAG = "AutoJs6LuaWatchdog"
    const val FAIL_STOP_EVENT_TAG = "lua_runtime_fail_stop"
    const val DEADLINE_CLEANUP_EXPIRED_TAG = "deadline_cleanup_expired"
    const val STOP_CLEANUP_EXPIRED_TAG = "stop_cleanup_expired"
    const val WATCHDOG_CONTROL_FAILURE_TAG = "watchdog_control_failure"

    fun message(reason: LuaProcessTerminationReason): String =
        "event=$FAIL_STOP_EVENT_TAG reason=${reason.tag()}"

    private fun LuaProcessTerminationReason.tag(): String = when (this) {
        LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED -> DEADLINE_CLEANUP_EXPIRED_TAG
        LuaProcessTerminationReason.STOP_CLEANUP_EXPIRED -> STOP_CLEANUP_EXPIRED_TAG
        LuaProcessTerminationReason.WATCHDOG_CONTROL_FAILURE -> WATCHDOG_CONTROL_FAILURE_TAG
    }
}

internal fun interface LuaWatchdogEventLogger {
    fun logFailStop(reason: LuaProcessTerminationReason)

    companion object {
        val NONE = LuaWatchdogEventLogger { _ -> Unit }
    }
}
