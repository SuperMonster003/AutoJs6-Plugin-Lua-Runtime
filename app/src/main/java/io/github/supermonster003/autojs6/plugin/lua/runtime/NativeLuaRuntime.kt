package io.github.supermonster003.autojs6.plugin.lua.runtime

import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaExecutionRunner
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaOutputEmitter
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerException
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerFailureKind
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerRequest
import org.autojs.plugin.lua.runtime.api.LuaOutputStream
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaValue
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.function.BooleanSupplier

/**
 * Synchronous, process-local JNI boundary for one isolated Lua execution.
 *
 * This object does not own Binder sessions, descriptors, workers, output credits, or host
 * capabilities. The caller must serialize executions and hold the verified source snapshot for
 * the duration of [execute]. Every native call creates and closes its own `lua_State` before it
 * returns or throws.
 */
internal object NativeLuaRuntime {
    private val loadResult: Result<Unit> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            check(BuildConfig.LUA_NATIVE_ENABLED) {
                "The pinned native runtime is disabled for this build"
            }
            System.loadLibrary("autojs_lua_runtime")
        }
    }

    fun requireReady() {
        check(BuildConfig.LUA_PROVIDER_ENABLED) {
            "The discoverable Lua provider is disabled until the R3 execution gate passes"
        }
        requireNativeLoaded()
        check(nativeProbe(PROBE_MEMORY_LIMIT_BYTES)) {
            "The bounded PUC Lua runtime probe failed"
        }
    }

    fun languageVersion(): String {
        requireReady()
        return nativeLanguageVersion().also { actual ->
            check(actual == LuaProviderMetadata.LANGUAGE_VERSION) {
                "Pinned language version drift: expected ${LuaProviderMetadata.LANGUAGE_VERSION}, got $actual"
            }
        }
    }

    /**
     * Runs exactly one text chunk on the calling thread.
     *
     * The provider flag is deliberately not required here: a default-disabled build may exercise
     * the native core in isolated tests without making either discovery service visible. Production
    * service admission remains guarded by [requireReady].
     */
    fun execute(request: NativeLuaExecutionRequest): NativeLuaExecutionValue {
        val cancelledBeforeDispatch = try {
            request.cancellationProbe.getAsBoolean()
        } catch (failure: Throwable) {
            throw NativeLuaExecutionException(
                NativeLuaFailureKind.INTERNAL,
                "Lua cancellation probe failed before native dispatch",
                failure,
            )
        }
        if (cancelledBeforeDispatch) {
            throw NativeLuaExecutionException(
                NativeLuaFailureKind.CANCELLED,
                "Lua execution was cancelled before native dispatch",
            )
        }
        requireNativeLoaded()
        val source = request.sourceSnapshot()
        val sourceName = encodeStrictUtf8(request.sourceName, "Lua source name")
        val rawValue = try {
            nativeExecute(
                source = source,
                sourceNameUtf8 = sourceName,
                memoryLimitBytes = request.memoryLimitBytes,
                timeoutMillis = request.timeoutMillis,
                cancellationProbe = request.cancellationProbe,
                outputEmitter = request.outputEmitter,
            )
        } catch (failure: IllegalStateException) {
            throw decodeBridgeFailure(failure)
        }
        return decodeNativeExecutionValue(rawValue)
    }

    private fun requireNativeLoaded() {
        check(BuildConfig.LUA_NATIVE_ENABLED) {
            "The pinned native runtime is disabled for this build"
        }
        loadResult.getOrThrow()
    }

    private external fun nativeLanguageVersion(): String

    private external fun nativeProbe(memoryLimitBytes: Long): Boolean

    private external fun nativeExecute(
        source: ByteArray,
        sourceNameUtf8: ByteArray,
        memoryLimitBytes: Long,
        timeoutMillis: Long,
        cancellationProbe: NativeLuaCancellationProbe,
        outputEmitter: NativeLuaOutputEmitter,
    ): Any?

    private const val PROBE_MEMORY_LIMIT_BYTES = 1024L * 1024L
}

/**
 * Default-off adapter for the Android-free execution seam.
 *
 * The Binder service selects this object only in native-enabled builds. Provider discovery stays
 * independently disabled until the Binder/PFD, watchdog, recovery, and device gates pass.
 */
internal object NativeLuaExecutionRunner : LuaExecutionRunner {
    override fun execute(request: LuaRunnerRequest): LuaValue {
        requireSupportedArguments(request.arguments)
        val nativeRequest = try {
            NativeLuaExecutionRequest(
                sourceUtf8 = request.sourceUtf8(),
                sourceName = request.sourceName,
                memoryLimitBytes = request.memoryLimitBytes,
                timeoutMillis = request.timeoutMillis,
                cancellationProbe = BooleanSupplier {
                    request.cancellationProbe.isCancellationRequested()
                },
                outputEmitter = NativeLuaOutputEmitter { streamWireCode, textUtf8 ->
                    emitNativeOutput(request.outputEmitter, streamWireCode, textUtf8)
                },
            )
        } catch (failure: IllegalArgumentException) {
            throw LuaRunnerException(
                LuaRunnerFailureKind.INTERNAL,
                "The prevalidated Lua runner request was rejected by the native boundary",
                failure,
            )
        }

        val nativeValue = try {
            NativeLuaRuntime.execute(nativeRequest)
        } catch (failure: NativeLuaExecutionException) {
            throw LuaRunnerException(
                failure.kind.toRunnerFailureKind(),
                "Native Lua execution failed",
                failure,
            )
        } catch (failure: Throwable) {
            throw LuaRunnerException(
                LuaRunnerFailureKind.INTERNAL,
                "The native Lua execution adapter failed",
                failure,
            )
        }
        return nativeValue.toProtocolValue()
    }
}

internal fun requireSupportedArguments(arguments: LuaValue) {
    val representsNoArguments = arguments === LuaValue.Nil ||
        arguments is LuaValue.MapValue && arguments.values.isEmpty()
    if (!representsNoArguments) {
        throw LuaRunnerException(
            LuaRunnerFailureKind.UNSUPPORTED_ARGUMENTS,
            "Native Lua argument binding is not deployed",
        )
    }
}

internal fun NativeLuaExecutionValue.toProtocolValue(): LuaValue = when (this) {
    NativeLuaExecutionValue.Nil -> LuaValue.Nil
    is NativeLuaExecutionValue.BooleanValue -> LuaValue.BooleanValue(value)
    is NativeLuaExecutionValue.IntegerValue -> LuaValue.Int64Value(value)
    is NativeLuaExecutionValue.NumberValue -> LuaValue.Float64Value(value)
    is NativeLuaExecutionValue.StringValue -> LuaValue.StringValue(value)
}

private fun NativeLuaFailureKind.toRunnerFailureKind(): LuaRunnerFailureKind = when (this) {
    NativeLuaFailureKind.SYNTAX -> LuaRunnerFailureKind.SYNTAX
    NativeLuaFailureKind.RUNTIME -> LuaRunnerFailureKind.RUNTIME
    NativeLuaFailureKind.MEMORY_LIMIT -> LuaRunnerFailureKind.MEMORY_LIMIT
    NativeLuaFailureKind.CANCELLED -> LuaRunnerFailureKind.CANCELLED
    NativeLuaFailureKind.DEADLINE_EXCEEDED -> LuaRunnerFailureKind.DEADLINE_EXCEEDED
    NativeLuaFailureKind.RESULT_LIMIT -> LuaRunnerFailureKind.RESULT_LIMIT
    NativeLuaFailureKind.UNSUPPORTED_RESULT -> LuaRunnerFailureKind.UNSUPPORTED_RESULT
    NativeLuaFailureKind.INTERNAL -> LuaRunnerFailureKind.INTERNAL
}

internal class NativeLuaExecutionRequest(
    sourceUtf8: ByteArray,
    val sourceName: String,
    val memoryLimitBytes: Long,
    val timeoutMillis: Long,
    val cancellationProbe: NativeLuaCancellationProbe,
    val outputEmitter: NativeLuaOutputEmitter = NativeLuaOutputEmitter.REJECTING,
) {
    private val stableSource = sourceUtf8.copyOf()

    init {
        require(stableSource.size.toLong() <= LuaRuntimeContract.MAX_SOURCE_BYTES) {
            "Lua source exceeds the protocol byte limit"
        }
        requireStrictUtf8(stableSource, "Lua source")
        val sourceNameUtf8 = encodeStrictUtf8(sourceName, "Lua source name")
        require(sourceName.isNotBlank()) { "Lua source name must not be blank" }
        require(sourceName.none { Character.isISOControl(it.code) }) {
            "Lua source name contains control characters"
        }
        require(sourceNameUtf8.size <= LuaRuntimeContract.MAX_SOURCE_NAME_BYTES) {
            "Lua source name exceeds the protocol byte limit"
        }
        require(memoryLimitBytes in 1L..LuaRuntimeContract.MAX_MEMORY_BYTES) {
            "Lua memory limit is outside the protocol range"
        }
        require(timeoutMillis in 1L..LuaRuntimeContract.MAX_TIMEOUT_MILLIS) {
            "Lua timeout is outside the protocol range"
        }
    }

    internal fun sourceSnapshot(): ByteArray = stableSource.copyOf()
}

/** Called synchronously from the executing JNI thread; implementations must not block. */
internal typealias NativeLuaCancellationProbe = BooleanSupplier

/** Called synchronously by JNI with a bounded UTF-8 byte snapshot. */
internal fun interface NativeLuaOutputEmitter {
    fun emitUtf8(streamWireCode: Int, textUtf8: ByteArray): Boolean

    companion object {
        val REJECTING = NativeLuaOutputEmitter { _, _ -> false }
    }
}

internal fun emitNativeOutput(
    outputEmitter: LuaOutputEmitter,
    streamWireCode: Int,
    textUtf8: ByteArray,
): Boolean {
    if (textUtf8.isEmpty() || textUtf8.size > LuaRuntimeContract.MAX_OUTPUT_CHUNK_BYTES) return false
    val stream = LuaOutputStream.values().singleOrNull { it.wireCode == streamWireCode } ?: return false
    val text = try {
        decodeStrictUtf8(textUtf8, "Lua console output")
    } catch (_: IllegalArgumentException) {
        return false
    }
    return outputEmitter.emit(stream, text)
}

internal sealed interface NativeLuaExecutionValue {
    data object Nil : NativeLuaExecutionValue
    data class BooleanValue(val value: Boolean) : NativeLuaExecutionValue
    data class IntegerValue(val value: Long) : NativeLuaExecutionValue
    data class NumberValue(val value: Double) : NativeLuaExecutionValue
    data class StringValue(val value: String) : NativeLuaExecutionValue
}

internal enum class NativeLuaFailureKind {
    SYNTAX,
    RUNTIME,
    MEMORY_LIMIT,
    CANCELLED,
    DEADLINE_EXCEEDED,
    RESULT_LIMIT,
    UNSUPPORTED_RESULT,
    INTERNAL,
}

internal class NativeLuaExecutionException(
    val kind: NativeLuaFailureKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("$kind: $message", cause)

internal fun decodeNativeExecutionValue(value: Any?): NativeLuaExecutionValue = when (value) {
    null -> NativeLuaExecutionValue.Nil
    is Boolean -> NativeLuaExecutionValue.BooleanValue(value)
    is Long -> NativeLuaExecutionValue.IntegerValue(value)
    is Double -> {
        if (!value.isFinite()) {
            throw NativeLuaExecutionException(
                NativeLuaFailureKind.UNSUPPORTED_RESULT,
                "Lua returned a non-finite number",
            )
        }
        NativeLuaExecutionValue.NumberValue(value)
    }
    is ByteArray -> {
        if (value.size > LuaRuntimeContract.MAX_VALUE_STRING_OR_BYTES) {
            throw NativeLuaExecutionException(
                NativeLuaFailureKind.RESULT_LIMIT,
                "Lua string result exceeds the V1 scalar limit",
            )
        }
        val text = try {
            decodeStrictUtf8(value, "Lua string result")
        } catch (failure: IllegalArgumentException) {
            throw NativeLuaExecutionException(
                NativeLuaFailureKind.UNSUPPORTED_RESULT,
                "Lua string result is not valid UTF-8",
                failure,
            )
        }
        NativeLuaExecutionValue.StringValue(text)
    }
    else -> throw NativeLuaExecutionException(
        NativeLuaFailureKind.INTERNAL,
        "Native Lua returned an unknown JVM value type",
    )
}

private fun decodeBridgeFailure(failure: IllegalStateException): NativeLuaExecutionException {
    val encoded = failure.message.orEmpty()
    val separator = encoded.indexOf(BRIDGE_FAILURE_SEPARATOR)
    val kindText = if (separator >= 0) encoded.substring(0, separator) else ""
    val diagnostic = if (separator >= 0) encoded.substring(separator + 1) else "Native Lua failed"
    val kind = runCatching { NativeLuaFailureKind.valueOf(kindText) }
        .getOrDefault(NativeLuaFailureKind.INTERNAL)
    return NativeLuaExecutionException(kind, diagnostic.take(MAX_BRIDGE_DIAGNOSTIC_CHARS), failure)
}

private fun requireStrictUtf8(value: ByteArray, label: String) {
    decodeStrictUtf8(value, label)
}

private fun decodeStrictUtf8(value: ByteArray, label: String): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString()
} catch (failure: CharacterCodingException) {
    throw IllegalArgumentException("$label is not valid UTF-8", failure)
}

private fun encodeStrictUtf8(value: String, label: String): ByteArray = try {
    val buffer = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value))
    ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
} catch (failure: CharacterCodingException) {
    throw IllegalArgumentException("$label is not valid UTF-8", failure)
}

private const val BRIDGE_FAILURE_SEPARATOR = '|'
private const val MAX_BRIDGE_DIAGNOSTIC_CHARS = 256
