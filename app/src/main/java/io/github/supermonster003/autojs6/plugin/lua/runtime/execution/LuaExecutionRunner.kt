package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.autojs.plugin.lua.runtime.api.LuaOutputStream
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
    val outputEmitter: LuaOutputEmitter = LuaOutputEmitter.REJECTING,
    val hostCapabilityInvoker: LuaHostCapabilityInvoker = LuaHostCapabilityInvoker.REJECTING,
) {
    private val sourceBytes = sourceUtf8.copyOf()

    fun sourceUtf8(): ByteArray = sourceBytes.copyOf()
}

/**
 * Bounded synchronous seam used only by explicitly admitted built-in Lua functions.
 *
 * The native module never supplies an arbitrary capability name. Each reviewed built-in binds a
 * fixed name and a fixed argument shape before reaching this seam.
 */
internal fun interface LuaHostCapabilityInvoker {
    @Throws(LuaHostCapabilityException::class)
    fun invoke(
        capability: String,
        arguments: LuaValue,
        timeoutMillis: Long,
        cancellationProbe: LuaCancellationProbe,
    ): LuaValue

    companion object {
        val REJECTING = LuaHostCapabilityInvoker { _, _, _, _ ->
            throw LuaHostCapabilityException(
                LuaHostCapabilityFailureKind.DENIED,
                "Lua host capabilities are unavailable",
            )
        }
    }
}

internal enum class LuaHostCapabilityFailureKind {
    DENIED,
    CANCELLED,
    DEADLINE_EXCEEDED,
    PROTOCOL,
    HOST,
    INTERNAL,
}

internal class LuaHostCapabilityException(
    val kind: LuaHostCapabilityFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun interface LuaCancellationProbe {
    fun isCancellationRequested(): Boolean
}

/** Synchronous, credit-controlled stdout/stderr bridge owned by the session controller. */
internal fun interface LuaOutputEmitter {
    fun emit(stream: LuaOutputStream, text: String): Boolean

    companion object {
        val REJECTING = LuaOutputEmitter { _, _ -> false }
    }
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
    HOST_CAPABILITY,
    INTERNAL,
}

internal class LuaRunnerException(
    val kind: LuaRunnerFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
