package io.github.supermonster003.autojs6.plugin.lua.runtime

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaCancellationProbe
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerException
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerFailureKind
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerRequest
import io.github.supermonster003.autojs6.plugin.lua.runtime.service.LuaPluginInfoService
import io.github.supermonster003.autojs6.plugin.lua.runtime.service.LuaRuntimeService
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

@RunWith(AndroidJUnit4::class)
class NativeLuaRuntimeInstrumentationTest {
    @Test
    fun providerRemainsDisabledDuringNativeTests() {
        assertTrue(BuildConfig.LUA_NATIVE_ENABLED)
        assertFalse(BuildConfig.LUA_PROVIDER_ENABLED)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        listOf(PLUGIN_INFO_ACTION, LUA_RUNTIME_ACTION).forEach { action ->
            val discovered = queryServices(
                packageManager,
                Intent(action).setPackage(context.packageName),
            )
            assertTrue("Disabled provider was discoverable for $action", discovered.isEmpty())
        }

        listOf(LuaPluginInfoService::class.java, LuaRuntimeService::class.java).forEach { service ->
            val info = serviceInfo(packageManager, ComponentName(context, service))
            assertFalse("${service.simpleName} must remain disabled", info.enabled)
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
    fun unsupportedResultsAndArgumentsFailClosed() {
        assertNativeFailure(NativeLuaFailureKind.UNSUPPORTED_RESULT) { execute("return {}") }
        assertNativeFailure(NativeLuaFailureKind.UNSUPPORTED_RESULT) { execute("return 1, 2") }
        assertNativeFailure(NativeLuaFailureKind.RESULT_LIMIT) {
            execute("return string.rep('x', 65537)")
        }

        val unsupportedArguments = assertThrows(LuaRunnerException::class.java) {
            NativeLuaExecutionRunner.execute(
                runnerRequest(
                    source = "return 7",
                    arguments = LuaValue.StringValue("not-bound"),
                ),
            )
        }
        assertEquals(LuaRunnerFailureKind.UNSUPPORTED_ARGUMENTS, unsupportedArguments.kind)
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
    ) = LuaRunnerRequest(
        sourceUtf8 = source.toByteArray(),
        sourceName = "runner-instrumentation.lua",
        arguments = arguments,
        memoryLimitBytes = LuaRuntimeContract.DEFAULT_MEMORY_BYTES,
        timeoutMillis = LuaRuntimeContract.DEFAULT_TIMEOUT_MILLIS,
        cancellationProbe = LuaCancellationProbe { false },
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
        const val PLUGIN_INFO_ACTION = "org.autojs.plugin.INFO"
        const val LUA_RUNTIME_ACTION = "org.autojs.plugin.lua.RUNTIME"
    }
}
