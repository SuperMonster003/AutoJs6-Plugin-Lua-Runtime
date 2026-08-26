package io.github.supermonster003.autojs6.plugin.lua.runtime

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaCancellationProbe
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaOutputEmitter
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaHostCapabilityInvoker
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerException
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerFailureKind
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerRequest
import io.github.supermonster003.autojs6.plugin.lua.runtime.service.LuaPluginInfoService
import io.github.supermonster003.autojs6.plugin.lua.runtime.service.LuaRuntimeService
import org.autojs.plugin.lua.runtime.api.LuaOutputStream
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class NativeLuaRuntimeInstrumentationTest {
    @Test
    fun providerServicesAreAbsentDuringNativeTests() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".native_test"))
        val packageManager = context.packageManager
        listOf(PLUGIN_INFO_ACTION, LUA_RUNTIME_ACTION).forEach { action ->
            val discovered = queryServices(
                packageManager,
                Intent(action).setPackage(context.packageName),
            )
            assertTrue("Disabled provider was discoverable for $action", discovered.isEmpty())
        }

        listOf(LuaPluginInfoService::class.java, LuaRuntimeService::class.java).forEach { service ->
            assertThrows(PackageManager.NameNotFoundException::class.java) {
                serviceInfo(packageManager, ComponentName(context, service))
            }
        }
    }

    @Test
    fun nativeCoreAndRunnerReturnV1Scalars() {
        assertEquals(NativeLuaExecutionValue.Nil, execute("return"))
        assertEquals(NativeLuaExecutionValue.BooleanValue(true), execute("return true"))
        assertEquals(NativeLuaExecutionValue.IntegerValue(7L), execute("return 7"))
        assertEquals(NativeLuaExecutionValue.NumberValue(1.5), execute("return 1.5"))
        assertEquals(NativeLuaExecutionValue.StringValue("Lua"), execute("return 'Lua'"))
        assertEquals(NativeLuaExecutionValue.StringValue("Lua 5.4"), execute("return _VERSION"))

        assertEquals(
            LuaValue.Int64Value(7L),
            NativeLuaExecutionRunner.execute(runnerRequest("return 7")),
        )

        val output = mutableListOf<Pair<LuaOutputStream, String>>()
        assertEquals(
            LuaValue.Int64Value(8L),
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = """
                        local autojs = require('autojs')
                        autojs.console.log('hello')
                        autojs.console.info('notice')
                        autojs.console.error('problem')
                        autojs.console.warn('warning')
                        return 8
                    """.trimIndent(),
                    outputEmitter = LuaOutputEmitter { stream, text ->
                        output += stream to text
                        true
                    },
                ),
            ),
        )
        assertEquals(
            listOf(
                LuaOutputStream.STDOUT to "hello",
                LuaOutputStream.STDOUT to "notice",
                LuaOutputStream.STDERR to "problem",
                LuaOutputStream.STDERR to "warning",
            ),
            output,
        )

        val deniedModuleCapability = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(runnerRequest("return require('unknown')"))
        }
        assertEquals(LuaRunnerFailureKind.HOST_CAPABILITY, deniedModuleCapability.kind)
    }

    @Test
    fun reviewedTimeFormatAndRandomSubsetStaysNarrow() {
        val earliestAcceptedMillis = System.currentTimeMillis() - CLOCK_SKEW_TOLERANCE_MILLIS
        val value = execute(
            """
                local autojs = require('autojs')
                assert(os == nil)
                assert(string.format('%04d', 7) == '0007')
                math.randomseed(1, 2)
                local sampled = math.random(1, 4)
                assert(math.type(sampled) == 'integer' and sampled >= 1 and sampled <= 4)
                local now = autojs.now()
                assert(math.type(now) == 'integer')
                return now
            """.trimIndent(),
        )
        val latestAcceptedMillis = System.currentTimeMillis() + CLOCK_SKEW_TOLERANCE_MILLIS

        val now = (value as NativeLuaExecutionValue.IntegerValue).value
        assertTrue(
            "autojs.now returned an implausible wall-clock value",
            now in earliestAcceptedMillis..latestAcceptedMillis,
        )
        assertNativeFailure(NativeLuaFailureKind.RUNTIME) {
            execute("return require('autojs').now(1)")
        }
        assertNativeFailure(NativeLuaFailureKind.RUNTIME) {
            execute("return math.randomseed()")
        }
    }

    @Test
    fun nativeRunnerMapsV1ArgumentsIntoTheControlledAutoJsModule() {
        assertEquals(
            LuaValue.Int64Value(12L),
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = "return require('autojs').arguments",
                    arguments = LuaValue.Int64Value(12L),
                ),
            ),
        )

        val output = mutableListOf<Pair<LuaOutputStream, String>>()
        val arguments = LuaValue.MapValue(
            linkedMapOf(
                "enabled" to LuaValue.BooleanValue(true),
                "count" to LuaValue.Int64Value(7L),
                "ratio" to LuaValue.Float64Value(1.5),
                "text" to LuaValue.StringValue("A\u0000\ud83d\ude00"),
                "raw" to LuaValue.BytesValue(byteArrayOf(0x00, 0xff.toByte())),
                "items" to LuaValue.ArrayValue(
                    listOf(
                        LuaValue.Int64Value(4L),
                        LuaValue.StringValue("ready"),
                    ),
                ),
                "\u0000key" to LuaValue.StringValue("binary-key"),
            ),
        )
        assertEquals(
            LuaValue.Int64Value(11L),
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = """
                        local autojs = require('autojs')
                        local a = autojs.arguments
                        assert(type(a) == 'table' and a.enabled == true)
                        assert(math.type(a.count) == 'integer' and a.count == 7)
                        assert(math.type(a.ratio) == 'float' and a.ratio == 1.5)
                        assert(#a.text == 6 and string.byte(a.text, 2) == 0)
                        assert(#a.raw == 2 and string.byte(a.raw, 1) == 0 and string.byte(a.raw, 2) == 255)
                        assert(a.items[1] == 4 and a.items[2] == 'ready' and a.items[3] == nil)
                        assert(a[string.char(0) .. 'key'] == 'binary-key')
                        autojs.console.log('Lua:' .. a.items[2])
                        return a.items[1] + a.count
                    """.trimIndent(),
                    arguments = arguments,
                    outputEmitter = LuaOutputEmitter { stream, text ->
                        output += stream to text
                        true
                    },
                ),
            ),
        )
        assertEquals(listOf(LuaOutputStream.STDOUT to "Lua:ready"), output)
    }

    @Test
    fun nativeRunnerMapsTheFixedDeviceInfoCapability() {
        var calls = 0
        val value = NativeLuaExecutionRunner.execute(
            runnerRequest(
                source = """
                    local info = require('autojs').device.info()
                    assert(info.brand == 'AutoJs')
                    assert(info.manufacturer == 'AutoJs')
                    assert(info.model == 'NativeTest')
                    assert(info.device == 'native_test')
                    assert(info.product == 'native_product')
                    assert(info.sdkInt == 36)
                    return info.manufacturer .. '|' .. info.model .. '|' .. info.sdkInt
                """.trimIndent(),
                hostCapabilityInvoker = LuaHostCapabilityInvoker { capability, arguments, _, _ ->
                    assertEquals("device.info", capability)
                    assertEquals(emptyMap<String, LuaValue>(), (arguments as LuaValue.MapValue).values)
                    calls += 1
                    deviceInfo()
                },
            ),
        )

        assertEquals(LuaValue.StringValue("AutoJs|NativeTest|36"), value)
        assertEquals(1, calls)

        val denial = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest("return require('autojs').device.info()"),
            )
        }
        assertEquals(LuaRunnerFailureKind.HOST_CAPABILITY, denial.kind)
    }

    @Test
    fun nativeRunnerMapsTheFixedToastCapabilityWithoutAResultOrRetry() {
        val observedTexts = mutableListOf<String>()
        val value = NativeLuaExecutionRunner.execute(
            runnerRequest(
                source = """
                    local autojs = require('autojs')
                    assert(type(autojs.ui) == 'table')
                    assert(autojs.ui.toast('保存完成') == nil)
                    assert(autojs.ui.toast('A' .. string.char(0) .. 'B') == nil)
                    return 9
                """.trimIndent(),
                hostCapabilityInvoker = LuaHostCapabilityInvoker { capability, arguments, _, _ ->
                    assertEquals("ui.toast.v1", capability)
                    val fields = (arguments as LuaValue.MapValue).values
                    assertEquals(setOf("text"), fields.keys)
                    observedTexts += (fields["text"] as LuaValue.StringValue).value
                    toastAccepted()
                },
            ),
        )

        assertEquals(LuaValue.Int64Value(9L), value)
        assertEquals(listOf("保存完成", "A\u0000B"), observedTexts)

        var malformedCalls = 0
        val malformedAcknowledgement = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = "require('autojs').ui.toast('bad ack')",
                    hostCapabilityInvoker = LuaHostCapabilityInvoker { _, _, _, _ ->
                        malformedCalls += 1
                        LuaValue.MapValue(mapOf("accepted" to LuaValue.BooleanValue(false)))
                    },
                ),
            )
        }
        assertEquals(LuaRunnerFailureKind.HOST_CAPABILITY, malformedAcknowledgement.kind)
        assertEquals(1, malformedCalls)

        val denial = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest("require('autojs').ui.toast('denied')"),
            )
        }
        assertEquals(LuaRunnerFailureKind.HOST_CAPABILITY, denial.kind)
    }

    @Test
    fun nativeRunnerEnforcesToastTextAndExecutionQuotasBeforeHostDispatch() {
        var calls = 0
        val invoker = LuaHostCapabilityInvoker { capability, arguments, _, _ ->
            assertEquals("ui.toast.v1", capability)
            assertTrue((arguments as LuaValue.MapValue).values["text"] is LuaValue.StringValue)
            calls += 1
            toastAccepted()
        }

        val quotaFailure = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = """
                        local toast = require('autojs').ui.toast
                        for index = 1, 4 do toast('accepted-' .. index) end
                        local child = coroutine.create(function() toast('fifth') end)
                        local resumed = coroutine.resume(child)
                        assert(resumed == false)
                        toast('sixth')
                    """.trimIndent(),
                    hostCapabilityInvoker = invoker,
                ),
            )
        }
        assertEquals(LuaRunnerFailureKind.RUNTIME, quotaFailure.kind)
        assertEquals(4, calls)

        listOf(
            "require('autojs').ui.toast(string.rep('x', 1025))",
            "require('autojs').ui.toast(string.char(0xc3, 0x28))",
            "require('autojs').ui.toast(string.char(0xc0, 0x80))",
            "require('autojs').ui.toast(string.char(0xed, 0xa0, 0x80))",
            "require('autojs').ui.toast(string.char(0xf4, 0x90, 0x80, 0x80))",
            "require('autojs').ui.toast(string.char(0xf0, 0x90))",
        ).forEach { source ->
            val failure = assertThrows(LuaRunnerException::class.java) {
                NativeLuaExecutionRunner.execute(
                    runnerRequest(source = source, hostCapabilityInvoker = invoker),
                )
            }
            assertEquals(LuaRunnerFailureKind.RUNTIME, failure.kind)
            assertEquals(4, calls)
        }

        assertEquals(
            LuaValue.BooleanValue(true),
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = "require('autojs').ui.toast(string.rep('x', 1024)); return true",
                    hostCapabilityInvoker = invoker,
                ),
            ),
        )
        assertEquals(5, calls)
    }

    @Test
    fun nativeRunnerLoadsFrozenModulesOnceAndRejectsDependencyCycles() {
        val calls = linkedMapOf<String, Int>()
        val invoker = LuaHostCapabilityInvoker { capability, arguments, _, _ ->
            assertEquals("module.snapshot.v1", capability)
            val name = ((arguments as LuaValue.MapValue).values["name"] as LuaValue.StringValue).value
            calls[name] = calls.getOrDefault(name, 0) + 1
            val source = when (name) {
                "helper" -> "return { value = 41 }"
                "nothing" -> "return nil"
                "disabled" -> "return false"
                "cycle" -> "return require('cycle')"
                else -> null
            }
            if (source == null) {
                LuaValue.MapValue(mapOf("found" to LuaValue.BooleanValue(false)))
            } else {
                LuaValue.MapValue(
                    mapOf(
                        "found" to LuaValue.BooleanValue(true),
                        "source" to LuaValue.BytesValue(source.toByteArray()),
                        "sha256" to LuaValue.BytesValue(
                            MessageDigest.getInstance("SHA-256").digest(source.toByteArray()),
                        ),
                    ),
                )
            }
        }

        assertEquals(
            LuaValue.Int64Value(41L),
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = """
                        local first = require('helper')
                        local second = require('helper')
                        assert(first == second)
                        assert(require('nothing') == true)
                        assert(require('nothing') == true)
                        assert(require('disabled') == false)
                        assert(require('disabled') == false)
                        return first.value
                    """.trimIndent(),
                    hostCapabilityInvoker = invoker,
                ),
            ),
        )
        assertEquals(mapOf("helper" to 1, "nothing" to 1, "disabled" to 1), calls)

        val cycle = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest("return require('cycle')", hostCapabilityInvoker = invoker),
            )
        }
        assertEquals(LuaRunnerFailureKind.RUNTIME, cycle.kind)
        assertEquals(1, calls["cycle"])

        val missing = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest("return require('missing')", hostCapabilityInvoker = invoker),
            )
        }
        assertEquals(LuaRunnerFailureKind.RUNTIME, missing.kind)
        assertEquals(1, calls["missing"])
    }

    @Test
    fun syntaxAndRuntimeErrorsAreClassified() {
        assertNativeFailure(NativeLuaFailureKind.SYNTAX) { execute("return )") }
        assertNativeFailure(NativeLuaFailureKind.RUNTIME) { execute("error('boom')") }

        val runnerFailure = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(runnerRequest("error('boom')"))
        }
        assertEquals(LuaRunnerFailureKind.RUNTIME, runnerFailure.kind)
    }

    @Test
    fun pcallAndXpcallRemainAbsentInMainAndCoroutines() {
        assertEquals(
            NativeLuaExecutionValue.BooleanValue(true),
            execute(
                """
                    assert(type(pcall) == 'nil' and type(xpcall) == 'nil')
                    local worker = coroutine.create(function()
                        return type(pcall) == 'nil' and type(xpcall) == 'nil'
                    end)
                    local ok, absent = coroutine.resume(worker)
                    return ok and absent and coroutine.status(worker) == 'dead'
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun rejectedNestedPcallCannotCatchOrDispatch() {
        val calls = AtomicInteger()
        val failure = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = """
                        return pcall(function()
                            return pcall(function()
                                return require('autojs').device.info()
                            end)
                        end)
                    """.trimIndent(),
                    hostCapabilityInvoker = LuaHostCapabilityInvoker { _, _, _, _ ->
                        calls.incrementAndGet()
                        deviceInfo()
                    },
                ),
            )
        }
        assertEquals(LuaRunnerFailureKind.RUNTIME, failure.kind)
        assertEquals(0, calls.get())
    }

    @Test
    fun rejectedXpcallCannotRunMessageHandler() {
        val calls = AtomicInteger()
        val failure = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = """
                        return xpcall(
                            function() error('business error') end,
                            function()
                                require('autojs').ui.toast('handler-ran')
                                return 'transformed'
                            end
                        )
                    """.trimIndent(),
                    hostCapabilityInvoker = LuaHostCapabilityInvoker { _, _, _, _ ->
                        calls.incrementAndGet()
                        toastAccepted()
                    },
                ),
            )
        }
        assertEquals(LuaRunnerFailureKind.RUNTIME, failure.kind)
        assertEquals(0, calls.get())
    }

    @Test(timeout = 5_000L)
    fun infiniteLoopIsCancelledByHook() {
        val polls = AtomicInteger()
        assertNativeFailure(NativeLuaFailureKind.CANCELLED) {
            execute(
                source = "while true do end",
                timeoutMillis = 4_000L,
                cancellationProbe = BooleanSupplier { polls.incrementAndGet() >= 5 },
            )
        }
        assertTrue("The cancellation hook was not polled", polls.get() >= 5)
    }

    @Test(timeout = 5_000L)
    fun infiniteLoopHonoursDeadline() {
        assertNativeFailure(NativeLuaFailureKind.DEADLINE_EXCEEDED) {
            execute(source = "while true do end", timeoutMillis = 25L)
        }
    }

    @Test(timeout = 5_000L)
    fun coroutineInfiniteLoopHonoursInheritedDeadlineHook() {
        assertNativeFailure(NativeLuaFailureKind.DEADLINE_EXCEEDED) {
            execute(
                source = """
                    local worker = coroutine.create(function()
                        while true do end
                    end)
                    local resumed = coroutine.resume(worker)
                    return resumed
                """.trimIndent(),
                timeoutMillis = 500L,
            )
        }
    }

    @Test(timeout = 5_000L)
    fun coroutineCancellationCannotBeSwallowedByResume() {
        val polls = AtomicInteger()
        assertNativeFailure(NativeLuaFailureKind.CANCELLED) {
            execute(
                source = """
                    local worker = coroutine.create(function()
                        while true do end
                    end)
                    local resumed = coroutine.resume(worker)
                    return resumed
                """.trimIndent(),
                timeoutMillis = 4_000L,
                cancellationProbe = BooleanSupplier { polls.incrementAndGet() >= 5 },
            )
        }
        assertTrue("The inherited coroutine hook was not polled", polls.get() >= 5)
    }

    @Test(timeout = 5_000L)
    fun coroutineYieldResumeRetainsAllocatorAccounting() {
        assertEquals(
            NativeLuaExecutionValue.IntegerValue(6L),
            execute(
                """
                    local worker = coroutine.create(function()
                        local retained = {}
                        for index = 1, 6 do
                            retained[index] = string.rep(string.char(64 + index), 32 * 1024)
                            coroutine.yield(index, #retained[index])
                        end
                        return #retained
                    end)
                    for expected = 1, 6 do
                        local ok, observed, bytes = coroutine.resume(worker)
                        assert(ok and observed == expected and bytes == 32 * 1024)
                    end
                    local ok, retained = coroutine.resume(worker)
                    assert(ok and retained == 6 and coroutine.status(worker) == 'dead')
                    return retained
                """.trimIndent(),
            ),
        )
    }

    @Test(timeout = 5_000L)
    fun coroutineOomCannotBecomeSuccessfulAndTheProcessRemainsReusable() {
        assertNativeFailure(NativeLuaFailureKind.MEMORY_LIMIT) {
            execute(
                source = """
                    local worker = coroutine.create(function()
                        local retained = {}
                        for index = 1, 256 do
                            retained[index] = string.rep('x', 64 * 1024)
                        end
                        return #retained
                    end)
                    local resumed = coroutine.resume(worker)
                    if resumed then return 1 end
                    return 7
                """.trimIndent(),
                memoryLimitBytes = 1024L * 1024L,
            )
        }
        assertEquals(NativeLuaExecutionValue.IntegerValue(7L), execute("return 7"))
    }

    @Test(timeout = 5_000L)
    fun allocatorLimitFailsClosedAndTheProcessRemainsReusable() {
        assertNativeFailure(NativeLuaFailureKind.MEMORY_LIMIT) {
            execute(
                source = "return string.rep('x', 8 * 1024 * 1024)",
                memoryLimitBytes = 1024L * 1024L,
            )
        }
        assertEquals(NativeLuaExecutionValue.IntegerValue(7L), execute("return 7"))
    }

    @Test
    fun unsupportedResultsAndMalformedArgumentsFailClosed() {
        assertNativeFailure(NativeLuaFailureKind.UNSUPPORTED_RESULT) { execute("return {}") }
        assertNativeFailure(NativeLuaFailureKind.UNSUPPORTED_RESULT) { execute("return 1, 2") }
        assertNativeFailure(NativeLuaFailureKind.RESULT_LIMIT) {
            execute("return string.rep('x', 65537)")
        }

        val malformedArguments = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = "return 7",
                    arguments = LuaValue.ArrayValue(listOf(LuaValue.Nil)),
                ),
            )
        }
        assertEquals(LuaRunnerFailureKind.UNSUPPORTED_ARGUMENTS, malformedArguments.kind)
    }

    private fun execute(
        source: String,
        memoryLimitBytes: Long = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
        timeoutMillis: Long = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
        cancellationProbe: BooleanSupplier = BooleanSupplier { false },
    ): NativeLuaExecutionValue = NativeLuaRuntime.execute(
        NativeLuaExecutionRequest(
            sourceUtf8 = source.toByteArray(),
            sourceName = "native-instrumentation.lua",
            memoryLimitBytes = memoryLimitBytes,
            timeoutMillis = timeoutMillis,
            cancellationProbe = cancellationProbe,
        ),
    )

    private fun runnerRequest(
        source: String,
        arguments: LuaValue = LuaValue.Nil,
        outputEmitter: LuaOutputEmitter = LuaOutputEmitter.REJECTING,
        hostCapabilityInvoker: LuaHostCapabilityInvoker = LuaHostCapabilityInvoker.REJECTING,
    ) = LuaRunnerRequest(
        sourceUtf8 = source.toByteArray(),
        sourceName = "runner-instrumentation.lua",
        arguments = arguments,
        memoryLimitBytes = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
        timeoutMillis = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
        cancellationProbe = LuaCancellationProbe { false },
        outputEmitter = outputEmitter,
        hostCapabilityInvoker = hostCapabilityInvoker,
    )

    private fun deviceInfo() = LuaValue.MapValue(
        linkedMapOf(
            "brand" to LuaValue.StringValue("AutoJs"),
            "manufacturer" to LuaValue.StringValue("AutoJs"),
            "model" to LuaValue.StringValue("NativeTest"),
            "device" to LuaValue.StringValue("native_test"),
            "product" to LuaValue.StringValue("native_product"),
            "sdkInt" to LuaValue.Int64Value(36L),
        ),
    )

    private fun toastAccepted() = LuaValue.MapValue(
        mapOf("accepted" to LuaValue.BooleanValue(true)),
    )

    private fun assertNativeFailure(
        expected: NativeLuaFailureKind,
        action: () -> Unit,
    ) {
        val failure = assertThrows(NativeLuaExecutionException::class.java, action)
        assertEquals(expected, failure.kind)
    }

    @Suppress("DEPRECATION")
    private fun queryServices(packageManager: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            packageManager.queryIntentServices(intent, 0)
        }

    @Suppress("DEPRECATION")
    private fun serviceInfo(packageManager: PackageManager, component: ComponentName) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            )
        } else {
            packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        }

    private companion object {
        const val CLOCK_SKEW_TOLERANCE_MILLIS = 60_000L
        const val PLUGIN_INFO_ACTION = "org.autojs.plugin.INFO"
        const val LUA_RUNTIME_ACTION = "org.autojs.plugin.lua.RUNTIME"
    }
}
