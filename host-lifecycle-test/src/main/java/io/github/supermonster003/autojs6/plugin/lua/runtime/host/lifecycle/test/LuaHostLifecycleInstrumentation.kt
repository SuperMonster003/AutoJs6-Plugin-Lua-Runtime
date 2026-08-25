package io.github.supermonster003.autojs6.plugin.lua.runtime.host.lifecycle.test

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.autojs.plugin.lua.runtime.api.ILuaExecutionCallback
import org.autojs.plugin.lua.runtime.api.ILuaExecutionSession
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityBroker
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityCallback
import org.autojs.plugin.lua.runtime.api.ILuaRuntimeProvider
import org.autojs.plugin.lua.runtime.api.LuaExecutionCancellation
import org.autojs.plugin.lua.runtime.api.LuaExecutionError
import org.autojs.plugin.lua.runtime.api.LuaPluginActions
import org.autojs.plugin.lua.runtime.api.LuaProtocolVersion
import org.autojs.plugin.lua.runtime.api.LuaRequestId
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaSha256
import org.autojs.plugin.lua.runtime.api.LuaValue
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Standalone, debug-signed instrumentation whose real target is AutoJs6.
 *
 * The `arm` phase deliberately remains alive inside the Host process after a native Lua
 * execution starts. The external emulator-only orchestrator then replaces or uninstalls the
 * target package, so both callback and broker Binder objects die with the actual Host UID.
 * After the Host is available again, `verify` proves that the abandoned session was released
 * and that its old watchdog cannot terminate later executions. The non-destructive `smoke` mode
 * runs the real Host Lua engine against an explicitly version-pinned release candidate.
 */
class LuaHostLifecycleInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    private var armedBinding: BoundProvider? = null
    private var armedSession: ILuaExecutionSession? = null
    private var armedCallback: RecordingCallback? = null
    private var armedBroker: RejectingHostBroker? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = Bundle(arguments ?: Bundle.EMPTY)
        start()
    }

    override fun onStart() {
        val mode = arguments.getString(ARG_MODE).orEmpty()
        val runId = arguments.getString(ARG_RUN_ID).orEmpty()
        try {
            require(runId.matches(RUN_ID_PATTERN)) { "A simple non-empty runId is required" }
            check(targetContext.packageName == HOST_PACKAGE) {
                "Lifecycle instrumentation is not running in the AutoJs6 target"
            }
            when (mode) {
                MODE_ARM -> arm(runId)
                MODE_VERIFY -> verify(runId)
                MODE_SMOKE -> smoke(runId)
                else -> error("Unknown lifecycle mode: $mode")
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "$FAIL_MARKER runId=$runId mode=$mode", failure)
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("failure", failure.stackTraceToString())
                    putString("runId", runId)
                    putString("mode", mode)
                },
            )
        }
    }

    private fun arm(runId: String) {
        val context = targetContext
        val binding = bindProvider(context)
        val callback = RecordingCallback()
        val broker = RejectingHostBroker()
        val session = createExecution(
            context = context,
            provider = binding.provider,
            source = INFINITE_SOURCE,
            sourceName = "host-lifecycle-arm.lua",
            timeoutMillis = ARM_TIMEOUT_MILLIS,
            callback = callback,
            broker = broker,
        )
        armedBinding = binding
        armedSession = session
        armedCallback = callback
        armedBroker = broker
        session.start()
        callback.awaitStarted()
        check(binding.provider.asBinder().isBinderAlive) { "Provider Binder died while arming" }
        Log.i(
            TAG,
            "$ARMED_MARKER runId=$runId hostPid=${Process.myPid()} hostUid=${Process.myUid()} " +
                "providerBinderAlive=true timeoutMillis=$ARM_TIMEOUT_MILLIS",
        )

        // Package replacement/uninstall must be the event which ends this target process.
        CountDownLatch(1).await()
        error("Host lifecycle arm latch returned without package death")
    }

    private fun verify(runId: String) {
        bindProvider(targetContext).use { binding ->
            val providerBinder = binding.provider.asBinder()
            val providerDied = CountDownLatch(1)
            val providerDeathRecipient = IBinder.DeathRecipient { providerDied.countDown() }
            providerBinder.linkToDeath(providerDeathRecipient, 0)
            try {
                executeReturnSeven(targetContext, binding.provider, "host-lifecycle-recovery-first.lua")
                val firstCompletedAt = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "$RECOVERY_MARKER runId=$runId phase=first providerBinderAlive=${providerBinder.isBinderAlive}",
                )

                // This is longer than the armed execution deadline plus the runtime's two-second
                // cleanup grace. Any stale lease from the dead Host would kill this same Binder.
                SystemClock.sleep(STALE_WATCHDOG_PROOF_MILLIS)
                check(!providerDied.await(0L, TimeUnit.MILLISECONDS)) {
                    "The old Host session watchdog killed the later provider Binder"
                }
                check(providerBinder.isBinderAlive && providerBinder.pingBinder()) {
                    "Provider Binder did not survive the stale-watchdog proof window"
                }
                executeReturnSeven(targetContext, binding.provider, "host-lifecycle-recovery-second.lua")
                val elapsedMillis = SystemClock.elapsedRealtime() - firstCompletedAt
                check(elapsedMillis >= STALE_WATCHDOG_PROOF_MILLIS) {
                    "The stale-watchdog proof window was shortened"
                }
                Log.i(
                    TAG,
                    "$VERIFY_MARKER runId=$runId hostPid=${Process.myPid()} hostUid=${Process.myUid()} " +
                        "providerBinderAlive=true proofMillis=$elapsedMillis executions=2",
                )
            } finally {
                runCatching { providerBinder.unlinkToDeath(providerDeathRecipient, 0) }
            }
        }
        finish(
            Activity.RESULT_OK,
            Bundle().apply {
                putString("stream", "$VERIFY_MARKER runId=$runId\n")
                putString("runId", runId)
                putInt("executions", 2)
            },
        )
    }

    private fun smoke(runId: String) {
        val context = targetContext
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            "Official Provider smoke requires API 24 or newer"
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Official Provider smoke must not block the Android main thread"
        }
        val expectedHostVersionCode = requiredVersionArgument(ARG_EXPECTED_HOST_VERSION_CODE)
        val expectedProviderVersionCode = requiredVersionArgument(ARG_EXPECTED_PROVIDER_VERSION_CODE)
        val packageManager = context.packageManager
        val hostVersionCode = packageVersionCode(packageManager, HOST_PACKAGE)
        val providerVersionCode = packageVersionCode(packageManager, PROVIDER_PACKAGE)
        check(hostVersionCode == expectedHostVersionCode) {
            "Host versionCode mismatch: expected=$expectedHostVersionCode actual=$hostVersionCode"
        }
        check(providerVersionCode == expectedProviderVersionCode) {
            "Provider versionCode mismatch: expected=$expectedProviderVersionCode actual=$providerVersionCode"
        }

        val infoService = singleEnabledService(context, LuaPluginActions.INFO)
        val runtimeService = singleEnabledService(context, LuaRuntimeContract.SERVICE_ACTION)
        requireOfficialService(infoService, LuaPluginActions.INFO)
        requireOfficialService(runtimeService, LuaRuntimeContract.SERVICE_ACTION)
        check(packageManager.checkSignatures(HOST_PACKAGE, PROVIDER_PACKAGE) == PackageManager.SIGNATURE_MATCH) {
            "Official Provider and Host do not have the same signer"
        }

        val stdout = "lua-rc2-console-${UUID.randomUUID()}"
        val engineClass = context.classLoader.loadClass(HOST_ENGINE_CLASS)
        val engine = engineClass.getConstructor(Context::class.java).newInstance(context)
        try {
            invokeReflective(engineClass.getMethod("init"), engine)
            val firstResult = executeHostLua(
                context = context,
                engine = engine,
                sourceName = "official-provider-first",
                sourceText = "return 7",
            )
            requireInt64Result(firstResult, 7L, "return 7")

            val deviceResult = executeHostLua(
                context = context,
                engine = engine,
                sourceName = "official-provider-device-info",
                sourceText = """
                    local autojs = require("autojs")
                    local device = autojs.device.info()
                    if type(device.brand) ~= "string"
                        or type(device.manufacturer) ~= "string"
                        or type(device.model) ~= "string"
                        or type(device.device) ~= "string"
                        or type(device.product) ~= "string"
                        or type(device.sdkInt) ~= "number" then
                        error("device.info result mismatch")
                    end
                    autojs.console.log("$stdout")
                    return device.sdkInt
                """.trimIndent(),
            )
            requireInt64Result(deviceResult, Build.VERSION.SDK_INT.toLong(), "device.info")
            awaitConsoleMarker(context, stdout)
        } finally {
            invokeReflective(engineClass.getMethod("destroy"), engine)
        }

        Log.i(
            TAG,
            "$SMOKE_MARKER runId=$runId hostPid=${Process.myPid()} hostUid=${Process.myUid()} " +
                "hostVersionCode=$hostVersionCode providerVersionCode=$providerVersionCode " +
                "executions=2 discovery=pass result=pass console=pass",
        )
        finish(
            Activity.RESULT_OK,
            Bundle().apply {
                putString("stream", "$SMOKE_MARKER runId=$runId\n")
                putString("runId", runId)
                putLong("hostVersionCode", hostVersionCode)
                putLong("providerVersionCode", providerVersionCode)
                putInt("executions", 2)
                putString("discovery", "pass")
                putString("result", "pass")
                putString("console", "pass")
            },
        )
    }

    private fun requiredVersionArgument(name: String): Long =
        arguments.getString(name)?.toLongOrNull()?.takeIf { it > 0L }
            ?: error("A positive $name instrumentation argument is required")

    @Suppress("DEPRECATION")
    private fun packageVersionCode(packageManager: PackageManager, packageName: String): Long {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun singleEnabledService(context: Context, action: String): ServiceInfo {
        val services = context.packageManager.queryIntentServices(
            Intent(action).setPackage(PROVIDER_PACKAGE),
            0,
        ).map { it.serviceInfo }
        check(services.size == 1) {
            "Expected exactly one enabled official Provider service for $action; found ${services.size}"
        }
        return services.single().also { service ->
            check(service.enabled) { "Official Provider service for $action is disabled" }
        }
    }

    private fun requireOfficialService(service: ServiceInfo, action: String) {
        check(service.packageName == PROVIDER_PACKAGE) {
            "Unexpected Provider package for $action: ${service.packageName}"
        }
        check(service.processName == RUNTIME_PROCESS) {
            "Unexpected Provider process for $action: ${service.processName}"
        }
        check(service.permission == LuaRuntimeContract.PLUGIN_PERMISSION) {
            "Unexpected Provider permission for $action: ${service.permission}"
        }
        check(service.exported) { "Official Provider service for $action is not exported" }
    }

    private fun executeHostLua(
        context: Context,
        engine: Any,
        sourceName: String,
        sourceText: String,
    ): Any? {
        val sourceDirectory = File(
            context.cacheDir,
            "lua-official-provider-smoke-${UUID.randomUUID()}",
        )
        check(sourceDirectory.mkdir()) { "Cannot create the official Provider smoke directory" }
        val sourceFile = sourceDirectory.resolve("$sourceName.lua")
        try {
            sourceFile.outputStream().use { output ->
                output.write(sourceText.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            val sourceClass = context.classLoader.loadClass(HOST_FILE_SOURCE_CLASS)
            val source = sourceClass.getConstructor(File::class.java).newInstance(sourceFile)
            val execute = engine.javaClass.methods.firstOrNull { method ->
                method.name == "execute" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes.single().name == HOST_SCRIPT_SOURCE_CLASS
            } ?: engine.javaClass.methods.firstOrNull { method ->
                method.name == "execute" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes.single().isAssignableFrom(source.javaClass)
            } ?: error("The real Host Lua engine has no compatible execute method")
            return invokeReflective(execute, engine, source)
        } finally {
            check(sourceFile.delete() || !sourceFile.exists()) {
                "Failed to remove the official Provider smoke source"
            }
            check(sourceDirectory.delete() || !sourceDirectory.exists()) {
                "Failed to remove the official Provider smoke directory"
            }
        }
    }

    private fun requireInt64Result(result: Any?, expected: Long, execution: String) {
        val valueResult = checkNotNull(result) { "$execution returned null instead of Lua Int64" }
        check(valueResult.javaClass.name == LUA_INT64_VALUE_CLASS) {
            "$execution returned ${valueResult.javaClass.name} instead of Lua Int64"
        }
        val value = invokeReflective(
            valueResult.javaClass.getMethod("getValue"),
            valueResult,
        ) as? Number
        check(value?.toLong() == expected) {
            "$execution returned $value instead of $expected"
        }
    }

    private fun awaitConsoleMarker(context: Context, marker: String) {
        val serviceClass = context.classLoader.loadClass(HOST_SCRIPT_ENGINE_SERVICE_CLASS)
        val service = checkNotNull(invokeReflective(serviceClass.getMethod("getInstance"), null)) {
            "The real Host ScriptEngineService is unavailable"
        }
        val console = checkNotNull(
            invokeReflective(serviceClass.getMethod("getGlobalConsole"), service),
        ) { "The real Host global console is unavailable" }
        val consoleClass = context.classLoader.loadClass(HOST_CONSOLE_CLASS)
        check(consoleClass.isInstance(console)) {
            "Lua output sink is not backed by the real Host ConsoleImpl"
        }
        val entriesMethod = consoleClass.getMethod("getLogEntries")
        val deadline = SystemClock.elapsedRealtime() + CONSOLE_TIMEOUT_MILLIS
        do {
            val entries = invokeReflective(entriesMethod, console) as? List<*>
                ?: error("The real Host ConsoleImpl returned an invalid log entry list")
            val matched = synchronized(entries) {
                entries.any { entry ->
                    if (entry == null) return@any false
                    val level = entry.javaClass.getField("level").getInt(entry)
                    val content = entry.javaClass.getField("content").get(entry) as? CharSequence
                        ?: return@any false
                    level == Log.INFO && content.toString().endsWith(marker)
                }
            }
            if (matched) return
            SystemClock.sleep(CONSOLE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("The real Host console did not receive the official Provider marker")
    }

    private fun invokeReflective(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException ?: failure
    }

    private fun executeReturnSeven(context: Context, provider: ILuaRuntimeProvider, sourceName: String) {
        val callback = RecordingCallback()
        val broker = RejectingHostBroker()
        val session = createExecution(
            context = context,
            provider = provider,
            source = RETURN_SEVEN_SOURCE,
            sourceName = sourceName,
            timeoutMillis = RETURN_TIMEOUT_MILLIS,
            callback = callback,
            broker = broker,
        )
        try {
            session.start()
            callback.awaitReturnSeven()
        } finally {
            runCatching { session.close() }
        }
    }

    private fun createExecution(
        context: Context,
        provider: ILuaRuntimeProvider,
        source: ByteArray,
        sourceName: String,
        timeoutMillis: Long,
        callback: ILuaExecutionCallback,
        broker: ILuaHostCapabilityBroker,
    ): ILuaExecutionSession {
        // The runtime-info call also proves that the Binder sees the real target UID as allowed.
        LuaRuntimeCodec.decodeRuntimeInfo(provider.runtimeInfo)
        val request = org.autojs.plugin.lua.runtime.api.LuaExecutionRequest(
            requestId = LuaRequestId.fromUuid(UUID.randomUUID()),
            protocolVersion = PROTOCOL,
            sourceName = sourceName,
            sourceLengthBytes = source.size.toLong(),
            sourceSha256 = LuaSha256.digest(source),
            timeoutMillis = timeoutMillis,
        )
        return withPrivateReadOnlySource(context, source) { descriptor ->
            checkNotNull(
                provider.createExecution(
                    LuaRuntimeCodec.encodeExecutionRequest(request),
                    descriptor,
                    callback,
                    broker,
                ),
            ) { "Lua provider returned a null execution session" }
        }
    }

    private inline fun <T> withPrivateReadOnlySource(
        context: Context,
        source: ByteArray,
        action: (ParcelFileDescriptor) -> T,
    ): T {
        val snapshot = File.createTempFile("lua-host-lifecycle-", ".lua", context.cacheDir)
        try {
            snapshot.outputStream().use { output ->
                output.write(source)
                output.fd.sync()
            }
            return ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY).use(action)
        } finally {
            check(snapshot.delete() || !snapshot.exists()) {
                "Failed to remove the Host lifecycle source snapshot"
            }
        }
    }

    private fun bindProvider(context: Context): BoundProvider {
        val connected = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder.set(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit

            override fun onBindingDied(name: ComponentName) = Unit

            override fun onNullBinding(name: ComponentName) = connected.countDown()
        }
        val intent = Intent().setComponent(ComponentName(PROVIDER_PACKAGE, PROVIDER_SERVICE))
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "AutoJs6 could not bind the official Lua runtime service"
        }
        try {
            check(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out binding the official Lua runtime service"
            }
            val provider = ILuaRuntimeProvider.Stub.asInterface(
                checkNotNull(binder.get()) { "Official Lua runtime returned a null binding" },
            )
            return BoundProvider(context, connection, provider)
        } catch (failure: Throwable) {
            runCatching { context.unbindService(connection) }
            throw failure
        }
    }

    private class RecordingCallback : ILuaExecutionCallback.Stub() {
        private val started = CountDownLatch(1)
        private val terminal = CountDownLatch(1)
        private val startedCalls = AtomicInteger()
        private val terminalCalls = AtomicInteger()
        private val value = AtomicReference<LuaValue?>()
        private val error = AtomicReference<LuaExecutionError?>()
        private val cancellation = AtomicReference<LuaExecutionCancellation?>()
        private val decodeFailure = AtomicReference<Throwable?>()

        override fun onStarted(metadata: ByteArray?) {
            startedCalls.incrementAndGet()
            runCatching { LuaRuntimeCodec.decodeStarted(checkNotNull(metadata)) }
                .onFailure { failure -> decodeFailure.compareAndSet(null, failure) }
            started.countDown()
        }

        override fun onOutput(metadata: ByteArray?) {
            runCatching { LuaRuntimeCodec.decodeOutput(checkNotNull(metadata)) }
                .onFailure { failure -> decodeFailure.compareAndSet(null, failure) }
        }

        override fun onCompleted(metadata: ByteArray?, payloads: Array<out ParcelFileDescriptor?>?) {
            terminalCalls.incrementAndGet()
            try {
                check(payloads.isNullOrEmpty()) { "Scalar Lua result unexpectedly carried payloads" }
                value.set(LuaRuntimeCodec.decodeResult(checkNotNull(metadata)).value)
            } catch (failure: Throwable) {
                decodeFailure.compareAndSet(null, failure)
            } finally {
                payloads.orEmpty().forEach { it?.runCatching { close() } }
                terminal.countDown()
            }
        }

        override fun onFailed(metadata: ByteArray?) {
            terminalCalls.incrementAndGet()
            runCatching { error.set(LuaRuntimeCodec.decodeError(checkNotNull(metadata))) }
                .onFailure { failure -> decodeFailure.compareAndSet(null, failure) }
            terminal.countDown()
        }

        override fun onCancelled(metadata: ByteArray?) {
            terminalCalls.incrementAndGet()
            runCatching { cancellation.set(LuaRuntimeCodec.decodeCancellation(checkNotNull(metadata))) }
                .onFailure { failure -> decodeFailure.compareAndSet(null, failure) }
            terminal.countDown()
        }

        fun awaitStarted() {
            check(started.await(STARTED_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for Lua onStarted"
            }
            decodeFailure.get()?.let { throw it }
            check(startedCalls.get() == 1 && terminalCalls.get() == 0) {
                "Armed Lua execution did not remain uniquely active"
            }
        }

        fun awaitReturnSeven() {
            check(terminal.await(RETURN_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for Lua return 7"
            }
            decodeFailure.get()?.let { throw it }
            check(startedCalls.get() == 1 && terminalCalls.get() == 1) {
                "Recovered Lua execution did not emit one started and one terminal callback"
            }
            check(error.get() == null && cancellation.get() == null) {
                "Recovered Lua execution failed: error=${error.get()} cancellation=${cancellation.get()}"
            }
            val result = value.get()
            check(result is LuaValue.Int64Value && result.value == 7L) {
                "Recovered Lua execution returned $result instead of 7"
            }
        }
    }

    private class RejectingHostBroker : ILuaHostCapabilityBroker.Stub() {
        override fun invoke(
            requestMetadata: ByteArray?,
            payloads: Array<out ParcelFileDescriptor?>?,
            callback: ILuaHostCapabilityCallback?,
        ) {
            payloads.orEmpty().forEach { it?.runCatching { close() } }
            error("Host lifecycle scripts expose no Host capabilities")
        }
    }

    private class BoundProvider(
        private val context: Context,
        private val connection: ServiceConnection,
        val provider: ILuaRuntimeProvider,
    ) : AutoCloseable {
        override fun close() {
            runCatching { context.unbindService(connection) }
        }
    }

    private companion object {
        const val TAG = "AutoJs6LuaHostLifecycle"
        const val ARMED_MARKER = "LUA_HOST_LIFECYCLE_ARMED"
        const val RECOVERY_MARKER = "LUA_HOST_LIFECYCLE_RECOVERY"
        const val VERIFY_MARKER = "LUA_HOST_LIFECYCLE_VERIFY_PASS"
        const val SMOKE_MARKER = "LUA_HOST_OFFICIAL_SMOKE_PASS"
        const val FAIL_MARKER = "LUA_HOST_LIFECYCLE_FAIL"
        const val ARG_MODE = "mode"
        const val ARG_RUN_ID = "runId"
        const val ARG_EXPECTED_HOST_VERSION_CODE = "expectedHostVersionCode"
        const val ARG_EXPECTED_PROVIDER_VERSION_CODE = "expectedProviderVersionCode"
        const val MODE_ARM = "arm"
        const val MODE_VERIFY = "verify"
        const val MODE_SMOKE = "smoke"
        const val HOST_PACKAGE = "org.autojs.autojs6"
        const val PROVIDER_PACKAGE = "io.github.supermonster003.autojs6.plugin.lua.runtime"
        const val RUNTIME_PROCESS = "$PROVIDER_PACKAGE:lua_runtime"
        const val PROVIDER_SERVICE =
            "$PROVIDER_PACKAGE.service.LuaRuntimeService"
        const val HOST_ENGINE_CLASS = "org.autojs.autojs.engine.LuaPluginScriptEngine"
        const val HOST_FILE_SOURCE_CLASS = "org.autojs.autojs.script.LuaFileSource"
        const val HOST_SCRIPT_SOURCE_CLASS = "org.autojs.autojs.script.LuaScriptSource"
        const val HOST_SCRIPT_ENGINE_SERVICE_CLASS = "org.autojs.autojs.engine.ScriptEngineService"
        const val HOST_CONSOLE_CLASS = "org.autojs.autojs.core.console.ConsoleImpl"
        const val LUA_INT64_VALUE_CLASS = "org.autojs.plugin.lua.runtime.api.LuaValue\$Int64Value"
        const val ARM_TIMEOUT_MILLIS = 4_000L
        const val RETURN_TIMEOUT_MILLIS = 5_000L
        const val STALE_WATCHDOG_PROOF_MILLIS = 7_000L
        const val CONSOLE_TIMEOUT_MILLIS = 2_000L
        const val CONSOLE_POLL_MILLIS = 10L
        const val BIND_TIMEOUT_SECONDS = 5L
        const val STARTED_TIMEOUT_SECONDS = 5L
        const val RETURN_RESULT_TIMEOUT_SECONDS = 8L
        val RUN_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,80}")
        val PROTOCOL = LuaProtocolVersion(
            LuaRuntimeContract.PROTOCOL_MAJOR,
            LuaRuntimeContract.PROTOCOL_MINOR,
        )
        val INFINITE_SOURCE = "while true do end".toByteArray(Charsets.UTF_8)
        val RETURN_SEVEN_SOURCE = "return 7".toByteArray(Charsets.UTF_8)
    }
}
