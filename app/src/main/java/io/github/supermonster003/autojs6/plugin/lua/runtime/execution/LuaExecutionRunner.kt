package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.autojs.plugin.lua.runtime.api.LuaValue

/**
 * Blocking, Android-free execution seam. A runner owns no Binder object, file
 * descriptor, worker, or session; the controller invokes it once on its serial
 * worker after source validation.
 */
internal fun interface LuaExecutionRunner {
    @Throws(LuaRunnerException::class)
    fun execute(request: LuaRunnerRequest): LuaValue
}

internal class LuaRunnerRequest(
    sourceUtf8: ByteArray,
    val sourceName: String,
    val arguments: LuaValue,
    val memoryLimitBytes: Long,
    val timeoutMillis: Long,
    val cancellationProbe: LuaCancellationProbe,
) {
    private val sourceBytes = sourceUtf8.copyOf()

    fun sourceUtf8(): ByteArray = sourceBytes.copyOf()
}

internal fun interface LuaCancellationProbe {
    fun isCancellationRequested(): Boolean
}

internal enum class LuaRunnerFailureKind {
    SYNTAX,
    RUNTIME,
    MEMORY_LIMIT,
    CANCELLED,
    DEADLINE_EXCEEDED,
    UNSUPPORTED_ARGUMENTS,
    UNSUPPORTED_RESULT,
    RESULT_LIMIT,
    INTERNAL,
}

internal class LuaRunnerException(
    val kind: LuaRunnerFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Keeps the provider non-runnable until a reviewed native adapter is injected. */
internal object DisabledLuaExecutionRunner : LuaExecutionRunner {
    override fun execute(request: LuaRunnerRequest): LuaValue = throw LuaRunnerException(
        LuaRunnerFailureKind.INTERNAL,
        "The native Lua execution adapter is not deployed",
    )
}
