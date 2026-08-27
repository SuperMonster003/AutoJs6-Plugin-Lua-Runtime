package io.github.supermonster003.autojs6.plugin.lua.runtime

import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaOutputEmitter
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaCancellationProbe
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaHostCapabilityException
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaHostCapabilityFailureKind
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
        assertEquals(arguments, NativeLuaArgumentCodec.decode(NativeLuaArgumentCodec.encode(arguments)))
        assertThrows(IllegalArgumentException::class.java) {
            NativeLuaArgumentCodec.decode(NativeLuaArgumentCodec.encode(arguments) + byteArrayOf(0))
        }
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

    @Test(timeout = 1_000L)
    fun deviceInfoCapabilityGrantAndDenialStayDeterministic() {
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

        val deniedBridge = NativeLuaHostCapabilityBridge(
            LuaRunnerRequest(
                sourceUtf8 = "return 1".toByteArray(),
                sourceName = "device-info-denied.lua",
                arguments = LuaValue.Nil,
                memoryLimitBytes = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
                timeoutMillis = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
                cancellationProbe = LuaCancellationProbe { false },
                hostCapabilityInvoker = LuaHostCapabilityInvoker.REJECTING,
            ),
        )
        val denial = assertThrows(LuaHostCapabilityException::class.java) {
            deniedBridge.invokeDeviceInfo()
        }
        assertEquals(LuaHostCapabilityFailureKind.DENIED, denial.kind)
        assertEquals(HOST_FAILURE_REJECTED, deniedBridge.takeFailureKind())
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

    @Test(timeout = 1_000L)
    fun toastCapabilityGrantDenialAndClosedShapesStayDeterministic() {
        val observedTexts = mutableListOf<String>()
        val bridge = NativeLuaHostCapabilityBridge(
            LuaRunnerRequest(
                sourceUtf8 = "return 1".toByteArray(),
                sourceName = "ui-toast.lua",
                arguments = LuaValue.Nil,
                memoryLimitBytes = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
                timeoutMillis = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
                cancellationProbe = LuaCancellationProbe { false },
                hostCapabilityInvoker = LuaHostCapabilityInvoker { capability, arguments, _, _ ->
                    assertEquals("ui.toast.v1", capability)
                    val fields = (arguments as LuaValue.MapValue).values
                    assertEquals(setOf("text"), fields.keys)
                    observedTexts += (fields["text"] as LuaValue.StringValue).value
                    toastAccepted()
                },
            ),
        )

        bridge.showToast("保存完成".toByteArray())
        bridge.showToast(ByteArray(NativeLuaHostCapabilityBridge.MAX_TOAST_TEXT_BYTES) { 'x'.code.toByte() })
        assertEquals(listOf("保存完成", "x".repeat(1024)), observedTexts)

        listOf(
            LuaValue.Nil,
            LuaValue.MapValue(mapOf("accepted" to LuaValue.BooleanValue(false))),
            LuaValue.MapValue(mapOf("accepted" to LuaValue.StringValue("yes"))),
            LuaValue.MapValue(
                mapOf(
                    "accepted" to LuaValue.BooleanValue(true),
                    "extra" to LuaValue.BooleanValue(true),
                ),
            ),
        ).forEach { acknowledgement ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeLuaHostCapabilityBridge.validateToastAcknowledgement(acknowledgement)
            }
        }

        listOf(
            byteArrayOf(),
            ByteArray(NativeLuaHostCapabilityBridge.MAX_TOAST_TEXT_BYTES + 1),
            byteArrayOf(0xc3.toByte(), 0x28),
        ).forEach { invalidText ->
            assertThrows(IllegalArgumentException::class.java) {
                bridge.showToast(invalidText)
            }
            assertEquals(HOST_FAILURE_INVALID_INPUT, bridge.takeFailureKind())
        }
        assertEquals(2, observedTexts.size)

        val deniedBridge = NativeLuaHostCapabilityBridge(
            LuaRunnerRequest(
                sourceUtf8 = "return 1".toByteArray(),
                sourceName = "ui-toast-denied.lua",
                arguments = LuaValue.Nil,
                memoryLimitBytes = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
                timeoutMillis = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
                cancellationProbe = LuaCancellationProbe { false },
                hostCapabilityInvoker = LuaHostCapabilityInvoker.REJECTING,
            ),
        )
        val denial = assertThrows(LuaHostCapabilityException::class.java) {
            deniedBridge.showToast("denied".toByteArray())
        }
        assertEquals(LuaHostCapabilityFailureKind.DENIED, denial.kind)
        assertEquals(HOST_FAILURE_REJECTED, deniedBridge.takeFailureKind())
    }

    @Test(timeout = 2_000L)
    fun storageCapabilityUsesOnlyFixedShapesAndCanonicalValues() {
        val stored = linkedMapOf<String, LuaValue>()
        val calls = mutableListOf<String>()
        val bridge = NativeLuaHostCapabilityBridge(
            runnerRequest(
                sourceName = "storage-fixed-shapes.lua",
                hostCapabilityInvoker = LuaHostCapabilityInvoker { capability, arguments, _, _ ->
                    assertEquals("storage.kv.v1", capability)
                    val fields = (arguments as LuaValue.MapValue).values
                    val operation = (fields["op"] as LuaValue.StringValue).value
                    calls += operation
                    when (operation) {
                        "get" -> {
                            assertEquals(setOf("op", "key"), fields.keys)
                            val value = stored[(fields["key"] as LuaValue.StringValue).value]
                            if (value == null) {
                                LuaValue.MapValue(mapOf("found" to LuaValue.BooleanValue(false)))
                            } else {
                                LuaValue.MapValue(
                                    linkedMapOf(
                                        "found" to LuaValue.BooleanValue(true),
                                        "value" to value,
                                    ),
                                )
                            }
                        }
                        "put" -> {
                            assertEquals(setOf("op", "key", "value"), fields.keys)
                            stored[(fields["key"] as LuaValue.StringValue).value] = checkNotNull(fields["value"])
                            LuaValue.MapValue(mapOf("stored" to LuaValue.BooleanValue(true)))
                        }
                        "remove" -> {
                            assertEquals(setOf("op", "key"), fields.keys)
                            LuaValue.MapValue(
                                mapOf(
                                    "removed" to LuaValue.BooleanValue(
                                        stored.remove((fields["key"] as LuaValue.StringValue).value) != null,
                                    ),
                                ),
                            )
                        }
                        "clear" -> {
                            assertEquals(setOf("op"), fields.keys)
                            val count = stored.size.toLong()
                            stored.clear()
                            LuaValue.MapValue(mapOf("removedCount" to LuaValue.Int64Value(count)))
                        }
                        else -> error("unexpected operation")
                    }
                },
            ),
        )
        val value = LuaValue.MapValue(
            linkedMapOf(
                "enabled" to LuaValue.BooleanValue(true),
                "items" to LuaValue.ArrayValue(
                    listOf(LuaValue.Int64Value(7L), LuaValue.StringValue("保存")),
                ),
            ),
        )

        assertEquals(null, bridge.storageGet("state".toByteArray()))
        assertTrue(bridge.storagePut("state".toByteArray(), NativeLuaArgumentCodec.encode(value)))
        assertEquals(value, NativeLuaArgumentCodec.decode(checkNotNull(bridge.storageGet("state".toByteArray()))))
        assertTrue(bridge.storageRemove("state".toByteArray()))
        assertFalse(bridge.storageRemove("state".toByteArray()))
        assertTrue(bridge.storagePut("one".toByteArray(), NativeLuaArgumentCodec.encode(LuaValue.Int64Value(1))))
        assertTrue(bridge.storagePut("two".toByteArray(), NativeLuaArgumentCodec.encode(LuaValue.Int64Value(2))))
        assertEquals(2L, bridge.storageClear())
        assertEquals(listOf("get", "put", "get", "remove", "remove", "put", "put", "clear"), calls)

        listOf(
            byteArrayOf(),
            "1bad".toByteArray(),
            "bad/key".toByteArray(),
            "é".toByteArray(),
            ByteArray(65) { 'a'.code.toByte() },
        ).forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { bridge.storageGet(key) }
            assertEquals(HOST_FAILURE_INVALID_INPUT, bridge.takeFailureKind())
        }
        listOf(
            LuaValue.Nil,
            LuaValue.BytesValue(byteArrayOf(1)),
            LuaValue.ArrayValue(listOf(LuaValue.BytesValue(byteArrayOf(1)))),
        ).forEach { rejected ->
            assertThrows(IllegalArgumentException::class.java) {
                bridge.storagePut("bad".toByteArray(), NativeLuaArgumentCodec.encode(rejected))
            }
            assertEquals(HOST_FAILURE_INVALID_INPUT, bridge.takeFailureKind())
        }
    }

    @Test(timeout = 2_000L)
    fun storageBridgeEnforcesExecutionQuotasBeforeDispatch() {
        var getCalls = 0
        val readBridge = NativeLuaHostCapabilityBridge(
            runnerRequest(
                sourceName = "storage-operation-quota.lua",
                hostCapabilityInvoker = LuaHostCapabilityInvoker { _, _, _, _ ->
                    getCalls += 1
                    LuaValue.MapValue(mapOf("found" to LuaValue.BooleanValue(false)))
                },
            ),
        )
        repeat(NativeLuaStorageContract.MAX_OPERATIONS_PER_EXECUTION) {
            assertEquals(null, readBridge.storageGet("key".toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            readBridge.storageGet("key".toByteArray())
        }
        assertEquals(NativeLuaStorageContract.MAX_OPERATIONS_PER_EXECUTION, getCalls)
        assertEquals(HOST_FAILURE_INVALID_INPUT, readBridge.takeFailureKind())

        var mutationCalls = 0
        val mutationBridge = NativeLuaHostCapabilityBridge(
            runnerRequest(
                sourceName = "storage-mutation-quota.lua",
                hostCapabilityInvoker = LuaHostCapabilityInvoker { _, _, _, _ ->
                    mutationCalls += 1
                    LuaValue.MapValue(mapOf("removed" to LuaValue.BooleanValue(false)))
                },
            ),
        )
        repeat(NativeLuaStorageContract.MAX_MUTATIONS_PER_EXECUTION) {
            assertFalse(mutationBridge.storageRemove("key".toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mutationBridge.storageRemove("key".toByteArray())
        }
        assertEquals(NativeLuaStorageContract.MAX_MUTATIONS_PER_EXECUTION, mutationCalls)
        assertEquals(HOST_FAILURE_INVALID_INPUT, mutationBridge.takeFailureKind())

        val largeValue = LuaValue.ArrayValue(
            List(4) { LuaValue.StringValue("x".repeat(60 * 1024)) },
        )
        val privateValue = NativeLuaArgumentCodec.encode(largeValue)
        val canonicalSize = NativeLuaStorageContract.canonicalEncodedBytes(largeValue).size
        val successfulWrites = (NativeLuaStorageContract.MAX_WRITE_BYTES_PER_EXECUTION / canonicalSize).toInt()
        var writeCalls = 0
        val writeBridge = NativeLuaHostCapabilityBridge(
            runnerRequest(
                sourceName = "storage-write-quota.lua",
                hostCapabilityInvoker = LuaHostCapabilityInvoker { _, _, _, _ ->
                    writeCalls += 1
                    LuaValue.MapValue(mapOf("stored" to LuaValue.BooleanValue(true)))
                },
            ),
        )
        repeat(successfulWrites) { index ->
            assertTrue(writeBridge.storagePut("key$index".toByteArray(), privateValue))
        }
        assertThrows(IllegalArgumentException::class.java) {
            writeBridge.storagePut("overflow".toByteArray(), privateValue)
        }
        assertEquals(successfulWrites, writeCalls)
        assertEquals(HOST_FAILURE_INVALID_INPUT, writeBridge.takeFailureKind())
    }

    @Test(timeout = 1_000L)
    fun storageBridgeRejectsMalformedResponsesAndDenialWithoutFallback() {
        val malformed = listOf(
            LuaValue.Nil,
            LuaValue.MapValue(mapOf("found" to LuaValue.StringValue("yes"))),
            LuaValue.MapValue(
                mapOf(
                    "found" to LuaValue.BooleanValue(false),
                    "value" to LuaValue.Int64Value(1),
                ),
            ),
            LuaValue.MapValue(
                mapOf(
                    "found" to LuaValue.BooleanValue(true),
                    "value" to LuaValue.BytesValue(byteArrayOf(1)),
                ),
            ),
        )
        malformed.forEach { response ->
            val bridge = NativeLuaHostCapabilityBridge(
                runnerRequest(
                    sourceName = "storage-malformed.lua",
                    hostCapabilityInvoker = LuaHostCapabilityInvoker { _, _, _, _ -> response },
                ),
            )
            assertThrows(IllegalArgumentException::class.java) { bridge.storageGet("key".toByteArray()) }
            assertEquals(HOST_FAILURE_NONE, bridge.takeFailureKind())
        }

        listOf<(NativeLuaHostCapabilityBridge) -> Unit>(
            { it.storageGet("key".toByteArray()) },
            { it.storagePut("key".toByteArray(), NativeLuaArgumentCodec.encode(LuaValue.Int64Value(1))) },
            { it.storageRemove("key".toByteArray()) },
            { it.storageClear() },
        ).forEach { operation ->
            val denied = NativeLuaHostCapabilityBridge(
                runnerRequest(
                    sourceName = "storage-denied.lua",
                    hostCapabilityInvoker = LuaHostCapabilityInvoker.REJECTING,
                ),
            )
            val failure = assertThrows(LuaHostCapabilityException::class.java) { operation(denied) }
            assertEquals(LuaHostCapabilityFailureKind.DENIED, failure.kind)
            assertEquals(HOST_FAILURE_REJECTED, denied.takeFailureKind())
        }
    }

    @Test(timeout = 1_000L)
    fun moduleSnapshotCapabilityGrantAndDenialStayDeterministic() {
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

        val deniedBridge = NativeLuaHostCapabilityBridge(
            LuaRunnerRequest(
                sourceUtf8 = "return 1".toByteArray(),
                sourceName = "module-snapshot-denied.lua",
                arguments = LuaValue.Nil,
                memoryLimitBytes = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
                timeoutMillis = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
                cancellationProbe = LuaCancellationProbe { false },
                hostCapabilityInvoker = LuaHostCapabilityInvoker.REJECTING,
            ),
        )
        val denial = assertThrows(LuaHostCapabilityException::class.java) {
            deniedBridge.loadModule("helper".toByteArray())
        }
        assertEquals(LuaHostCapabilityFailureKind.DENIED, denial.kind)
        assertEquals(HOST_FAILURE_REJECTED, deniedBridge.takeFailureKind())
    }

    private fun runnerRequest(
        sourceName: String,
        hostCapabilityInvoker: LuaHostCapabilityInvoker,
    ) = LuaRunnerRequest(
        sourceUtf8 = "return 1".toByteArray(),
        sourceName = sourceName,
        arguments = LuaValue.Nil,
        memoryLimitBytes = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
        timeoutMillis = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
        cancellationProbe = LuaCancellationProbe { false },
        hostCapabilityInvoker = hostCapabilityInvoker,
    )

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

    private fun toastAccepted() = LuaValue.MapValue(
        mapOf("accepted" to LuaValue.BooleanValue(true)),
    )

    private companion object {
        const val HOST_FAILURE_NONE = 0
        const val HOST_FAILURE_REJECTED = 3
        const val HOST_FAILURE_INVALID_INPUT = 4
    }
}
