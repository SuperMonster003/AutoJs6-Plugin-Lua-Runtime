package io.github.supermonster003.autojs6.plugin.lua.runtime.host.lifecycle.test

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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
import org.autojs.plugin.lua.runtime.api.LuaProtocolVersion
import org.autojs.plugin.lua.runtime.api.LuaRequestId
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaSha256
import org.autojs.plugin.lua.runtime.api.LuaValue
import java.io.File
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
 * and that its old watchdog cannot terminate later executions.
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
        const val FAIL_MARKER = "LUA_HOST_LIFECYCLE_FAIL"
        const val ARG_MODE = "mode"
        const val ARG_RUN_ID = "runId"
        const val MODE_ARM = "arm"
        const val MODE_VERIFY = "verify"
        const val HOST_PACKAGE = "org.autojs.autojs6"
        const val PROVIDER_PACKAGE = "io.github.supermonster003.autojs6.plugin.lua.runtime"
        const val PROVIDER_SERVICE =
            "$PROVIDER_PACKAGE.service.LuaRuntimeService"
        const val ARM_TIMEOUT_MILLIS = 4_000L
        const val RETURN_TIMEOUT_MILLIS = 5_000L
        const val STALE_WATCHDOG_PROOF_MILLIS = 7_000L
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
