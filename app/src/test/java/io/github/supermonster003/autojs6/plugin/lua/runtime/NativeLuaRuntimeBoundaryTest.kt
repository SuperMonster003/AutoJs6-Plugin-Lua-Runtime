package io.github.supermonster003.autojs6.plugin.lua.runtime

import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaOutputEmitter
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaCancellationProbe
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaHostCapabilityInvoker
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerRequest
import org.autojs.plugin.lua.runtime.api.LuaOutputStream
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
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
    fun requestOwnsAVersionedDefensiveArgumentSnapshot() {
        val arguments = LuaValue.MapValue(
            linkedMapOf(
                "enabled" to LuaValue.BooleanValue(true),
                "items" to LuaValue.ArrayValue(
                    listOf(
                        LuaValue.Int64Value(4L),
                        LuaValue.StringValue("ready"),
                    ),
                ),
                "raw" to LuaValue.BytesValue(byteArrayOf(0x00, 0xff.toByte())),
            ),
        )
        val request = request("return 1".toByteArray(), arguments = arguments)

        val firstSnapshot = request.argumentsSnapshot()
        assertTrue(firstSnapshot.size <= NativeLuaArgumentCodec.MAX_SNAPSHOT_BYTES)
        assertArrayEquals(
            byteArrayOf(0x41, 0x36, 0x4c, 0x41, 0x01, 0x08),
            firstSnapshot.copyOfRange(0, 6),
        )

        firstSnapshot.fill(0)
        assertArrayEquals(
            NativeLuaArgumentCodec.encode(arguments),
            request.argumentsSnapshot(),
        )
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
        assertThrows(IllegalArgumentException::class.java) {
            request(
                "return 1".toByteArray(),
                arguments = LuaValue.ArrayValue(listOf(LuaValue.Nil)),
            )
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
    fun sourceOnlyRunnerMapsScalarsAndKeepsOutputBounded() {
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

        val emitted = mutableListOf<Pair<LuaOutputStream, String>>()
        val emitter = LuaOutputEmitter { stream, text ->
            emitted += stream to text
            true
        }
        assertTrue(emitNativeOutput(emitter, LuaOutputStream.STDOUT.wireCode, "hello".toByteArray()))
        assertEquals(listOf(LuaOutputStream.STDOUT to "hello"), emitted)
        assertFalse(emitNativeOutput(emitter, Int.MAX_VALUE, "unknown".toByteArray()))
        assertFalse(emitNativeOutput(emitter, LuaOutputStream.STDERR.wireCode, byteArrayOf(0xc3.toByte(), 0x28)))
        assertFalse(
            emitNativeOutput(
                emitter,
                LuaOutputStream.STDOUT.wireCode,
                ByteArray(LuaRuntimeContract.MAX_OUTPUT_CHUNK_BYTES + 1),
            ),
        )
    }

    @Test
    fun consoleLevelAliasesRetainExactlyTwoWireStreams() {
        assertEquals(
            listOf(LuaOutputStream.STDOUT, LuaOutputStream.STDERR),
            enumValues<LuaOutputStream>().toList(),
        )
        assertEquals(1, LuaOutputStream.STDOUT.wireCode)
        assertEquals(2, LuaOutputStream.STDERR.wireCode)
    }

    @Test
    fun preCancelledRequestFailsBeforeNativeLibraryAdmission() {
        val cancelled = assertThrows(NativeLuaExecutionException::class.java) {
            NativeLuaRuntime.execute(request("return 1".toByteArray(), cancelled = true))
        }
        assertEquals(NativeLuaFailureKind.CANCELLED, cancelled.kind)
    }

    @Test
    fun deviceInfoBridgeUsesOneFixedCapabilityAndExactEmptyArguments() {
        val expected = deviceInfo()
        var observedCapability: String? = null
        var observedArguments: LuaValue? = null
        val runnerRequest = LuaRunnerRequest(
            sourceUtf8 = "return 1".toByteArray(),
            sourceName = "device-info.lua",
            arguments = LuaValue.Nil,
            memoryLimitBytes = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
            timeoutMillis = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
            cancellationProbe = LuaCancellationProbe { false },
            hostCapabilityInvoker = LuaHostCapabilityInvoker { capability, arguments, _, _ ->
                observedCapability = capability
                observedArguments = arguments
                expected
            },
        )

        val encoded = NativeLuaHostCapabilityBridge(runnerRequest).invokeDeviceInfo()

        assertEquals("device.info", observedCapability)
        assertEquals(emptyMap<String, LuaValue>(), (observedArguments as LuaValue.MapValue).values)
        assertArrayEquals(NativeLuaArgumentCodec.encode(expected), encoded)
    }

    @Test
    fun deviceInfoBridgeRejectsShapeExpansionBeforeJni() {
        val wrongKey = LuaValue.MapValue(deviceInfo().values + ("serial" to LuaValue.StringValue("secret")))
        assertThrows(IllegalArgumentException::class.java) {
            NativeLuaHostCapabilityBridge.validateDeviceInfo(wrongKey)
        }
        val wrongDevice = LuaValue.MapValue(
            deviceInfo().values + ("device" to LuaValue.Int64Value(1L)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            NativeLuaHostCapabilityBridge.validateDeviceInfo(wrongDevice)
        }
    }

    @Test
    fun moduleSnapshotBridgeUsesOneFixedCapabilityAndRejectsInvalidPayloads() {
        val observedNames = mutableListOf<String>()
        val runnerRequest = LuaRunnerRequest(
            sourceUtf8 = "return 1".toByteArray(),
            sourceName = "module-snapshot.lua",
            arguments = LuaValue.Nil,
            memoryLimitBytes = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
            timeoutMillis = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
            cancellationProbe = LuaCancellationProbe { false },
            hostCapabilityInvoker = LuaHostCapabilityInvoker { capability, arguments, _, _ ->
                assertEquals("module.snapshot.v1", capability)
                val name = ((arguments as LuaValue.MapValue).values["name"] as LuaValue.StringValue).value
                observedNames += name
                when (name) {
                    "helper" -> LuaValue.MapValue(
                        mapOf(
                            "found" to LuaValue.BooleanValue(true),
                            "source" to LuaValue.BytesValue("return 7".toByteArray()),
                            "sha256" to LuaValue.BytesValue(
                                MessageDigest.getInstance("SHA-256").digest("return 7".toByteArray()),
                            ),
                        ),
                    )
                    "missing" -> LuaValue.MapValue(
                        mapOf("found" to LuaValue.BooleanValue(false)),
                    )
                    "bad" -> LuaValue.MapValue(
                        mapOf(
                            "found" to LuaValue.BooleanValue(true),
                            "source" to LuaValue.BytesValue(byteArrayOf(0xc3.toByte(), 0x28)),
                            "sha256" to LuaValue.BytesValue(ByteArray(32)),
                        ),
                    )
                    else -> LuaValue.MapValue(
                        mapOf(
                            "found" to LuaValue.BooleanValue(true),
                            "source" to LuaValue.BytesValue("return 8".toByteArray()),
                            "sha256" to LuaValue.BytesValue(ByteArray(32)),
                        ),
                    )
                }
            },
        )
        val bridge = NativeLuaHostCapabilityBridge(runnerRequest)

        assertArrayEquals("return 7".toByteArray(), bridge.loadModule("helper".toByteArray()))
        assertEquals(null, bridge.loadModule("missing".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            bridge.loadModule("bad".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.loadModule("digest".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.loadModule("nested.name".toByteArray())
        }
        assertEquals(listOf("helper", "missing", "bad", "digest"), observedNames)
    }

    private fun request(
        source: ByteArray,
        sourceName: String = "native-boundary.lua",
        memoryLimitBytes: Long = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
        timeoutMillis: Long = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
        cancelled: Boolean = false,
        arguments: LuaValue = LuaValue.Nil,
    ) = NativeLuaExecutionRequest(
        sourceUtf8 = source,
        sourceName = sourceName,
        arguments = arguments,
        memoryLimitBytes = memoryLimitBytes,
        timeoutMillis = timeoutMillis,
        cancellationProbe = BooleanSupplier { cancelled },
    )

    private fun deviceInfo() = LuaValue.MapValue(
        linkedMapOf(
            "brand" to LuaValue.StringValue("AutoJs"),
            "manufacturer" to LuaValue.StringValue("AutoJs"),
            "model" to LuaValue.StringValue("Test"),
            "device" to LuaValue.StringValue("test_device"),
            "product" to LuaValue.StringValue("test_product"),
            "sdkInt" to LuaValue.Int64Value(36L),
        ),
    )
}
