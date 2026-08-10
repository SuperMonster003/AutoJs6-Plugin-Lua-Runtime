package io.github.supermonster003.autojs6.plugin.lua.runtime

import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerException
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerFailureKind
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.function.BooleanSupplier

class NativeLuaRuntimeBoundaryTest {
    @Test
    fun requestOwnsADefensiveUtf8SourceSnapshot() {
        val callerBuffer = "return 1".toByteArray()
        val request = request(callerBuffer)

        callerBuffer.fill(0)
        val firstSnapshot = request.sourceSnapshot()
        assertArrayEquals("return 1".toByteArray(), firstSnapshot)

        firstSnapshot.fill(0)
        assertArrayEquals("return 1".toByteArray(), request.sourceSnapshot())
    }

    @Test
    fun requestRejectsMalformedUtf8AndUnsafeLimitsBeforeJni() {
        assertThrows(IllegalArgumentException::class.java) {
            request(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request("return 1".toByteArray(), sourceName = "line\nbreak")
        }
        assertThrows(IllegalArgumentException::class.java) {
            request("return 1".toByteArray(), memoryLimitBytes = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request("return 1".toByteArray(), timeoutMillis = 0L)
        }
    }

    @Test
    fun scalarBridgeValuesMapWithoutBroadeningTheV1Boundary() {
        assertEquals(NativeLuaExecutionValue.Nil, decodeNativeExecutionValue(null))
        assertEquals(
            NativeLuaExecutionValue.BooleanValue(true),
            decodeNativeExecutionValue(true),
        )
        assertEquals(
            NativeLuaExecutionValue.IntegerValue(7L),
            decodeNativeExecutionValue(7L),
        )
        assertEquals(
            NativeLuaExecutionValue.NumberValue(1.5),
            decodeNativeExecutionValue(1.5),
        )
        assertEquals(
            NativeLuaExecutionValue.StringValue("Lua"),
            decodeNativeExecutionValue("Lua".toByteArray()),
        )

        val unsupported = assertThrows(NativeLuaExecutionException::class.java) {
            decodeNativeExecutionValue(emptyList<Any>())
        }
        assertEquals(NativeLuaFailureKind.INTERNAL, unsupported.kind)
    }

    @Test
    fun scalarStringResultRejectsInvalidUtf8AndOversizePayload() {
        val malformed = assertThrows(NativeLuaExecutionException::class.java) {
            decodeNativeExecutionValue(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertEquals(NativeLuaFailureKind.UNSUPPORTED_RESULT, malformed.kind)

        val oversize = assertThrows(NativeLuaExecutionException::class.java) {
            decodeNativeExecutionValue(ByteArray(LuaRuntimeContract.MAX_VALUE_STRING_OR_BYTES + 1))
        }
        assertEquals(NativeLuaFailureKind.RESULT_LIMIT, oversize.kind)
    }

    @Test
    fun sourceOnlyRunnerMapsScalarsWithoutBroadeningArguments() {
        assertEquals(LuaValue.Nil, NativeLuaExecutionValue.Nil.toProtocolValue())
        assertEquals(
            LuaValue.BooleanValue(true),
            NativeLuaExecutionValue.BooleanValue(true).toProtocolValue(),
        )
        assertEquals(
            LuaValue.Int64Value(7L),
            NativeLuaExecutionValue.IntegerValue(7L).toProtocolValue(),
        )
        assertEquals(
            LuaValue.Float64Value(1.5),
            NativeLuaExecutionValue.NumberValue(1.5).toProtocolValue(),
        )
        assertEquals(
            LuaValue.StringValue("Lua"),
            NativeLuaExecutionValue.StringValue("Lua").toProtocolValue(),
        )

        requireSupportedArguments(LuaValue.Nil)
        requireSupportedArguments(LuaValue.MapValue(emptyMap()))
        val unsupported = assertThrows(LuaRunnerException::class.java) {
            requireSupportedArguments(LuaValue.StringValue("not-bound"))
        }
        assertEquals(LuaRunnerFailureKind.UNSUPPORTED_ARGUMENTS, unsupported.kind)
    }

    @Test
    fun preCancelledRequestFailsBeforeNativeLibraryAdmission() {
        val cancelled = assertThrows(NativeLuaExecutionException::class.java) {
            NativeLuaRuntime.execute(request("return 1".toByteArray(), cancelled = true))
        }
        assertEquals(NativeLuaFailureKind.CANCELLED, cancelled.kind)
    }

    private fun request(
        source: ByteArray,
        sourceName: String = "native-boundary.lua",
        memoryLimitBytes: Long = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
        timeoutMillis: Long = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
        cancelled: Boolean = false,
    ) = NativeLuaExecutionRequest(
        sourceUtf8 = source,
        sourceName = sourceName,
        memoryLimitBytes = memoryLimitBytes,
        timeoutMillis = timeoutMillis,
        cancellationProbe = BooleanSupplier { cancelled },
    )
}
