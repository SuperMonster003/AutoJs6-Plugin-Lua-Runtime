package io.github.supermonster003.autojs6.plugin.lua.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.supermonster003.autojs6.plugin.lua.runtime.debug.LuaRuntimeFaultProtocol
import io.github.supermonster003.autojs6.plugin.lua.runtime.debug.LuaRuntimeFaultPeerProtocol
import io.github.supermonster003.autojs6.plugin.lua.runtime.debug.LuaRuntimeFaultPeerService
import io.github.supermonster003.autojs6.plugin.lua.runtime.debug.LuaRuntimeFaultService
import io.github.supermonster003.autojs6.plugin.lua.runtime.service.LuaPluginInfoService
import io.github.supermonster003.autojs6.plugin.lua.runtime.service.LuaRuntimeService
import org.autojs.plugin.lua.runtime.api.ILuaExecutionCallback
import org.autojs.plugin.lua.runtime.api.ILuaExecutionSession
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityBroker
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityCallback
import org.autojs.plugin.lua.runtime.api.ILuaRuntimeProvider
import org.autojs.plugin.lua.runtime.api.LuaCancellationReason
import org.autojs.plugin.lua.runtime.api.LuaExecutionCancellation
import org.autojs.plugin.lua.runtime.api.LuaExecutionError
import org.autojs.plugin.lua.runtime.api.LuaExecutionErrorCode
import org.autojs.plugin.lua.runtime.api.LuaExecutionFailurePhase
import org.autojs.plugin.lua.runtime.api.LuaExecutionRequest
import org.autojs.plugin.lua.runtime.api.LuaProtocolVersion
import org.autojs.plugin.lua.runtime.api.LuaRequestId
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaSha256
import org.autojs.plugin.lua.runtime.api.LuaValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LuaRuntimeFaultRecoveryInstrumentationTest {
    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun nativeCrashCausesBinderDeathAndRecoversInANewProcess() {
        assertFaultRecovery(CRASH_SOURCE)
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun nativeWedgeIsKilledByWatchdogAndRecoversInANewProcess() {
        assertFaultRecovery(WEDGE_SOURCE)
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun osFileDescriptorsReturnToBaselineAcrossTerminalAndPeerDeathPaths() {
        assertFaultHarnessConfiguration()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertProductionProvidersDisabled(context)
        val runtime = bind(context)
        try {
            val identity = runtime.client.identity()
            val provider = runtime.client.provider()
            assertEquals(7L, executeReturnSeven(context, provider))
            val baseline = awaitStableOpenFileDescriptorCount(runtime.client)

            repeat(FD_BATCH_REPETITIONS) {
                assertEquals(7L, executeReturnSeven(context, provider))
                executeDigestMismatch(context, provider)
            }
            executeCancellation(context, provider)
            awaitOpenFileDescriptorCount(runtime.client, baseline)

            exercisePeerDeath(context, runtime, provider, identity, baseline, PeerRole.CALLBACK)
            exercisePeerDeath(context, runtime, provider, identity, baseline, PeerRole.BROKER)

            assertEquals(identity, runtime.client.identity())
            assertEquals(baseline, awaitOpenFileDescriptorCount(runtime.client, baseline))
            assertEquals(7L, executeReturnSeven(context, provider))
            assertEquals(baseline, awaitOpenFileDescriptorCount(runtime.client, baseline))
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun hangingPipeSourceIsFailStoppedAndRecoversInANewProcess() {
        assertFaultHarnessConfiguration()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertProductionProvidersDisabled(context)
        val first = bind(context)
        val firstIdentity = first.client.identity()
        val died = CountDownLatch(1)
        first.client.binder.linkToDeath({ died.countDown() }, 0)
        val callback = RecordingExecutionCallback()
        val broker = RejectingHostBroker()
        val pipe = ParcelFileDescriptor.createPipe()
        var session: ILuaExecutionSession? = null
        val startedAtMillis = SystemClock.elapsedRealtime()
        try {
            session = pipe[0].use { readEnd ->
                createExecutionSession(
                    provider = first.client.provider(),
                    descriptor = readEnd,
                    sourceName = "blocked-pipe.lua",
                    sourceLengthBytes = 1L,
                    sourceSha256 = LuaSha256.digest(byteArrayOf(0)),
                    timeoutMillis = FAULT_TIMEOUT_MILLIS,
                    callback = callback,
                    broker = broker,
                )
            }
            startAllowingRemoteDeath(session)
            assertTrue(
                "The :lua_runtime process survived a source pipe blocked past deadline + grace",
                died.await(BLOCKED_SOURCE_DEATH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val elapsedMillis = SystemClock.elapsedRealtime() - startedAtMillis
            assertTrue(
                "Blocked source fail-stop exceeded deadline + cleanup grace + device jitter: ${elapsedMillis}ms",
                elapsedMillis <= BLOCKED_SOURCE_MAX_ELAPSED_MILLIS,
            )
            callback.assertNoCallbacks()
        } finally {
            pipe[1].close()
            runCatching { session?.close() }
            first.close()
        }

        val recovered = bindAfterDeath(context)
        try {
            val recoveredIdentity = recovered.client.identity()
            assertNotEquals(firstIdentity.pid, recoveredIdentity.pid)
            assertNotEquals(firstIdentity.nonce, recoveredIdentity.nonce)
            assertEquals(7L, executeReturnSeven(context, recovered.client.provider()))
        } finally {
            recovered.close()
        }
    }

    private fun assertFaultRecovery(faultSource: ByteArray) {
        assertFaultHarnessConfiguration()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertProductionProvidersDisabled(context)
        val first = bind(context)
        val firstIdentity = first.client.identity()
        assertNotEquals("The fault harness did not enter a remote process", Process.myPid(), firstIdentity.pid)
        assertEquals("${context.packageName}:lua_runtime", firstIdentity.processName)

        val sameProcess = bind(context)
        val sameProcessIdentity = sameProcess.client.identity()
        assertEquals("A second live bind changed the Lua runtime PID", firstIdentity.pid, sameProcessIdentity.pid)
        assertEquals(
            "The process epoch nonce was not stable across live binds",
            firstIdentity.nonce,
            sameProcessIdentity.nonce,
        )
        assertEquals(firstIdentity.processName, sameProcessIdentity.processName)
        first.close()

        val died = CountDownLatch(1)
        sameProcess.client.binder.linkToDeath({ died.countDown() }, 0)
        var faultExecution: RunningExecution? = null
        try {
            try {
                faultExecution = startExecution(
                    context = context,
                    provider = sameProcess.client.provider(),
                    source = faultSource,
                    timeoutMillis = FAULT_TIMEOUT_MILLIS,
                )
            } catch (failure: RemoteException) {
                check(failure is DeadObjectException || !sameProcess.client.binder.isBinderAlive) {
                    "Fault dispatch failed before the runtime process died: $failure"
                }
            }
            assertTrue(
                "The :lua_runtime Binder did not die after a native fault",
                died.await(PROCESS_DEATH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        } finally {
            faultExecution?.callback?.assertStartedWithoutTerminal()
            faultExecution?.closeAfterRemoteDeath()
            sameProcess.close()
        }

        val recovered = bindAfterDeath(context)
        try {
            val recoveredIdentity = recovered.client.identity()
            assertNotEquals(Process.myPid(), recoveredIdentity.pid)
            assertEquals("${context.packageName}:lua_runtime", recoveredIdentity.processName)
            assertNotEquals("The Lua runtime PID was reused without recovery", firstIdentity.pid, recoveredIdentity.pid)
            assertNotEquals("The Lua runtime process nonce did not change", firstIdentity.nonce, recoveredIdentity.nonce)
            assertEquals(7L, executeReturnSeven(context, recovered.client.provider()))
        } finally {
            recovered.close()
        }
    }

    private fun assertFaultHarnessConfiguration() {
        assertTrue(BuildConfig.DEBUG)
        assertTrue(BuildConfig.LUA_NATIVE_ENABLED)
        assertTrue(BuildConfig.LUA_FAULT_HARNESS_ENABLED)
        assertFalse(BuildConfig.LUA_PROVIDER_ENABLED)
    }

    private fun executeDigestMismatch(context: Context, provider: ILuaRuntimeProvider) {
        val callback = RecordingExecutionCallback()
        val broker = RejectingHostBroker()
        val session = withPrivateReadOnlySource(context, RETURN_SEVEN_SOURCE) { descriptor ->
            createExecutionSession(
                provider = provider,
                descriptor = descriptor,
                sourceName = "digest-mismatch.lua",
                sourceLengthBytes = RETURN_SEVEN_SOURCE.size.toLong(),
                sourceSha256 = LuaSha256.digest("different source".toByteArray(Charsets.UTF_8)),
                timeoutMillis = RETURN_TIMEOUT_MILLIS,
                callback = callback,
                broker = broker,
            )
        }
        try {
            startAllowingRemoteDeath(session)
            callback.awaitFailure(
                expectedCode = LuaExecutionErrorCode.SOURCE_DIGEST_MISMATCH,
                expectedPhase = LuaExecutionFailurePhase.SOURCE_VALIDATION,
            )
        } finally {
            runCatching { session.close() }
        }
    }

    private fun executeCancellation(context: Context, provider: ILuaRuntimeProvider) {
        val execution = startExecution(
            context = context,
            provider = provider,
            source = CANCELLATION_SOURCE,
            timeoutMillis = RETURN_TIMEOUT_MILLIS,
        )
        try {
            execution.cancel()
            execution.callback.awaitRequestedCancellation()
        } finally {
            execution.close()
        }
    }

    private fun exercisePeerDeath(
        context: Context,
        runtime: BoundFaultHarness,
        provider: ILuaRuntimeProvider,
        runtimeIdentity: ProcessIdentity,
        fdBaseline: Int,
        role: PeerRole,
    ) {
        val peer = bindPeer(context)
        val peerDied = CountDownLatch(1)
        peer.client.binder.linkToDeath({ peerDied.countDown() }, 0)
        val localCallback = if (role == PeerRole.BROKER) RecordingExecutionCallback() else null
        val localBroker = if (role == PeerRole.CALLBACK) RejectingHostBroker() else null
        val callback: ILuaExecutionCallback = localCallback ?: peer.client.callback()
        val broker: ILuaHostCapabilityBroker = localBroker ?: peer.client.broker()
        assertNotEquals(Process.myPid(), peer.client.identity())
        assertNotEquals(runtimeIdentity.pid, peer.client.identity())
        val session = withPrivateReadOnlySource(context, CANCELLATION_SOURCE) { descriptor ->
            createExecutionSession(
                provider = provider,
                descriptor = descriptor,
                sourceName = "${role.name.lowercase()}-death.lua",
                sourceLengthBytes = CANCELLATION_SOURCE.size.toLong(),
                sourceSha256 = LuaSha256.digest(CANCELLATION_SOURCE),
                timeoutMillis = RETURN_TIMEOUT_MILLIS,
                callback = callback,
                broker = broker,
            )
        }
        try {
            startAllowingRemoteDeath(session)
            if (role == PeerRole.CALLBACK) {
                peer.client.awaitStartedWithoutTerminal()
            } else {
                checkNotNull(localCallback).awaitStarted()
            }
            peer.client.killProcess()
            assertTrue(
                "The isolated ${role.name.lowercase()} peer did not die",
                peerDied.await(PEER_DEATH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            awaitOpenFileDescriptorCount(runtime.client, fdBaseline)
            assertEquals(
                "Peer death killed or replaced the healthy Lua runtime process",
                runtimeIdentity,
                runtime.client.identity(),
            )
            localCallback?.assertStartedWithoutTerminal()
        } finally {
            runCatching { session.close() }
            peer.close()
        }
    }

    private fun awaitStableOpenFileDescriptorCount(client: FaultClient): Int {
        val deadline = SystemClock.elapsedRealtime() + FD_SETTLE_TIMEOUT_MILLIS
        var previous = -1
        var stableSamples = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = client.openFileDescriptorCount()
            if (current == previous) {
                stableSamples += 1
                if (stableSamples >= REQUIRED_STABLE_FD_SAMPLES) return current
            } else {
                previous = current
                stableSamples = 1
            }
            SystemClock.sleep(FD_POLL_MILLIS)
        }
        throw AssertionError("The remote /proc/self/fd count did not stabilize; last=$previous")
    }

    private fun awaitOpenFileDescriptorCount(client: FaultClient, expected: Int): Int {
        val deadline = SystemClock.elapsedRealtime() + FD_SETTLE_TIMEOUT_MILLIS
        var observed = -1
        while (SystemClock.elapsedRealtime() < deadline) {
            observed = client.openFileDescriptorCount()
            if (observed == expected) return observed
            SystemClock.sleep(FD_POLL_MILLIS)
        }
        throw AssertionError(
            "Remote /proc/self/fd did not return to baseline: expected=$expected observed=$observed",
        )
    }

    private fun executeReturnSeven(context: Context, provider: ILuaRuntimeProvider): Long {
        val execution = startExecution(
            context = context,
            provider = provider,
            source = RETURN_SEVEN_SOURCE,
            timeoutMillis = RETURN_TIMEOUT_MILLIS,
        )
        return try {
            execution.callback.awaitReturnSeven().also {
                execution.callback.assertCompletedOnce()
            }
        } finally {
            execution.close()
        }
    }

    private fun startExecution(
        context: Context,
        provider: ILuaRuntimeProvider,
        source: ByteArray,
        timeoutMillis: Long,
    ): RunningExecution {
        val callback = RecordingExecutionCallback()
        val broker = RejectingHostBroker()
        val session = withPrivateReadOnlySource(context, source) { descriptor ->
            createExecutionSession(
                provider = provider,
                descriptor = descriptor,
                sourceName = "fault-recovery.lua",
                sourceLengthBytes = source.size.toLong(),
                sourceSha256 = LuaSha256.digest(source),
                timeoutMillis = timeoutMillis,
                callback = callback,
                broker = broker,
            )
        }
        val running = RunningExecution(session, callback, broker)
        try {
            startAllowingRemoteDeath(session)
        } catch (failure: Throwable) {
            running.close()
            throw failure
        }
        callback.awaitStarted()
        return running
    }

    private fun createExecutionSession(
        provider: ILuaRuntimeProvider,
        descriptor: ParcelFileDescriptor,
        sourceName: String,
        sourceLengthBytes: Long,
        sourceSha256: LuaSha256,
        timeoutMillis: Long,
        callback: ILuaExecutionCallback,
        broker: ILuaHostCapabilityBroker,
    ): ILuaExecutionSession {
        val request = LuaExecutionRequest(
            requestId = LuaRequestId.fromUuid(UUID.randomUUID()),
            protocolVersion = PROTOCOL,
            sourceName = sourceName,
            sourceLengthBytes = sourceLengthBytes,
            sourceSha256 = sourceSha256,
            timeoutMillis = timeoutMillis,
        )
        return checkNotNull(
            provider.createExecution(
                LuaRuntimeCodec.encodeExecutionRequest(request),
                descriptor,
                callback,
                broker,
            ),
        ) { "The debug Lua provider returned a null execution session" }
    }

    private fun startAllowingRemoteDeath(session: ILuaExecutionSession) {
        val startFailure = runCatching { session.start() }.exceptionOrNull()
        if (
            startFailure != null &&
            (startFailure !is RemoteException || session.asBinder().isBinderAlive)
        ) {
            throw startFailure
        }
    }

    private inline fun <T> withPrivateReadOnlySource(
        context: Context,
        source: ByteArray,
        action: (ParcelFileDescriptor) -> T,
    ): T {
        val snapshot = File.createTempFile("lua-fault-client-", ".lua", context.cacheDir)
        try {
            snapshot.outputStream().use { output ->
                output.write(source)
                output.fd.sync()
            }
            return ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY).use(action)
        } finally {
            check(snapshot.delete() || !snapshot.exists()) { "Failed to remove the client source snapshot" }
        }
    }

    private fun bindAfterDeath(context: Context): BoundFaultHarness {
        val deadline = SystemClock.elapsedRealtime() + REBIND_TIMEOUT_MILLIS
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return bind(context)
            } catch (failure: Throwable) {
                lastFailure = failure
                SystemClock.sleep(REBIND_RETRY_MILLIS)
            }
        }
        throw AssertionError("The Lua fault service did not rebind after process death", lastFailure)
    }

    private fun bind(context: Context): BoundFaultHarness {
        val connected = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder.set(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit

            override fun onNullBinding(name: ComponentName) = connected.countDown()
        }
        val intent = Intent().setComponent(ComponentName(context, LuaRuntimeFaultService::class.java))
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "The explicit debug Lua fault service bind was rejected"
        }
        try {
            check(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out binding the debug Lua fault service"
            }
            return BoundFaultHarness(
                context = context,
                connection = connection,
                client = FaultClient(checkNotNull(binder.get()) { "The fault service returned a null Binder" }),
            )
        } catch (failure: Throwable) {
            runCatching { context.unbindService(connection) }
            throw failure
        }
    }

    private fun bindPeer(context: Context): BoundFaultPeer {
        val connected = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder.set(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit

            override fun onNullBinding(name: ComponentName) = connected.countDown()
        }
        val intent = Intent().setComponent(ComponentName(context, LuaRuntimeFaultPeerService::class.java))
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "The explicit debug Lua fault-peer bind was rejected"
        }
        try {
            check(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out binding the debug Lua fault-peer service"
            }
            return BoundFaultPeer(
                context = context,
                connection = connection,
                client = FaultPeerClient(checkNotNull(binder.get()) { "The fault peer returned a null Binder" }),
            )
        } catch (failure: Throwable) {
            runCatching { context.unbindService(connection) }
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun assertProductionProvidersDisabled(context: Context) {
        listOf(LuaPluginInfoService::class.java, LuaRuntimeService::class.java).forEach { service ->
            val component = ComponentName(context, service)
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getServiceInfo(
                    component,
                    android.content.pm.PackageManager.ComponentInfoFlags.of(
                        android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                    ),
                )
            } else {
                context.packageManager.getServiceInfo(
                    component,
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS,
                )
            }
            assertFalse("${service.simpleName} must remain disabled", info.enabled)
        }
    }

    private data class ProcessIdentity(val pid: Int, val nonce: Long, val processName: String)

    private class FaultClient(val binder: IBinder) {
        fun identity(): ProcessIdentity = call(LuaRuntimeFaultProtocol.TRANSACTION_IDENTITY) { reply ->
            ProcessIdentity(
                pid = reply.readInt(),
                nonce = reply.readLong(),
                processName = checkNotNull(reply.readString()),
            )
        }

        fun provider(): ILuaRuntimeProvider = call(LuaRuntimeFaultProtocol.TRANSACTION_PROVIDER) { reply ->
            checkNotNull(ILuaRuntimeProvider.Stub.asInterface(reply.readStrongBinder()))
        }

        fun openFileDescriptorCount(): Int = call(
            LuaRuntimeFaultProtocol.TRANSACTION_OPEN_FD_COUNT,
        ) { reply ->
            reply.readInt().also { count -> check(count > 0) { "The runtime reported an invalid FD count" } }
        }

        private inline fun <T> call(transaction: Int, decode: (Parcel) -> T): T {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LuaRuntimeFaultProtocol.DESCRIPTOR)
                check(binder.transact(transaction, data, reply, 0)) {
                    "The debug Lua control transaction was rejected"
                }
                reply.readException()
                return decode(reply)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private data class PeerSnapshot(val startedCalls: Int, val terminalCalls: Int)

    private class FaultPeerClient(val binder: IBinder) {
        fun identity(): Int = call(LuaRuntimeFaultPeerProtocol.TRANSACTION_IDENTITY) { reply ->
            reply.readInt()
        }

        fun callback(): ILuaExecutionCallback = call(
            LuaRuntimeFaultPeerProtocol.TRANSACTION_CALLBACK,
        ) { reply ->
            checkNotNull(ILuaExecutionCallback.Stub.asInterface(reply.readStrongBinder()))
        }

        fun broker(): ILuaHostCapabilityBroker = call(
            LuaRuntimeFaultPeerProtocol.TRANSACTION_BROKER,
        ) { reply ->
            checkNotNull(ILuaHostCapabilityBroker.Stub.asInterface(reply.readStrongBinder()))
        }

        fun awaitStartedWithoutTerminal() {
            val deadline = SystemClock.elapsedRealtime() + STARTED_TIMEOUT_SECONDS * 1_000L
            var observed = PeerSnapshot(0, 0)
            while (SystemClock.elapsedRealtime() < deadline) {
                observed = snapshot()
                if (observed.startedCalls == 1 && observed.terminalCalls == 0) return
                SystemClock.sleep(PEER_POLL_MILLIS)
            }
            throw AssertionError("Fault callback peer did not observe one start without terminal: $observed")
        }

        fun killProcess() {
            val failure = runCatching {
                call(LuaRuntimeFaultPeerProtocol.TRANSACTION_KILL) { Unit }
            }.exceptionOrNull()
            if (failure != null && (failure !is RemoteException || binder.isBinderAlive)) {
                throw failure
            }
        }

        private fun snapshot(): PeerSnapshot = call(
            LuaRuntimeFaultPeerProtocol.TRANSACTION_SNAPSHOT,
        ) { reply ->
            PeerSnapshot(reply.readInt(), reply.readInt())
        }

        private inline fun <T> call(transaction: Int, decode: (Parcel) -> T): T {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LuaRuntimeFaultPeerProtocol.DESCRIPTOR)
                check(binder.transact(transaction, data, reply, 0)) {
                    "The debug Lua fault-peer transaction was rejected"
                }
                reply.readException()
                return decode(reply)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private class RecordingExecutionCallback : ILuaExecutionCallback.Stub() {
        private val started = CountDownLatch(1)
        private val terminal = CountDownLatch(1)
        private val startedCalls = AtomicInteger()
        private val terminalCalls = AtomicInteger()
        private val completedCalls = AtomicInteger()
        private val value = AtomicReference<LuaValue?>()
        private val error = AtomicReference<LuaExecutionError?>()
        private val cancellation = AtomicReference<LuaExecutionCancellation?>()
        private val failure = AtomicReference<String?>()

        override fun onStarted(metadata: ByteArray?) {
            startedCalls.incrementAndGet()
            try {
                LuaRuntimeCodec.decodeStarted(checkNotNull(metadata))
            } catch (error: Throwable) {
                failure.compareAndSet(null, error.message ?: error.javaClass.name)
            } finally {
                started.countDown()
            }
        }

        override fun onOutput(metadata: ByteArray?) {
            failure.compareAndSet(null, "The recovery execution emitted unexpected output")
        }

        override fun onCompleted(metadata: ByteArray?, payloads: Array<out ParcelFileDescriptor?>?) {
            terminalCalls.incrementAndGet()
            completedCalls.incrementAndGet()
            try {
                check(payloads.isNullOrEmpty()) { "The scalar recovery result included a payload" }
                value.set(LuaRuntimeCodec.decodeResult(checkNotNull(metadata)).value)
            } catch (error: Throwable) {
                failure.set(error.message ?: error.javaClass.name)
            } finally {
                terminal.countDown()
            }
        }

        override fun onFailed(metadata: ByteArray?) {
            terminalCalls.incrementAndGet()
            try {
                error.set(LuaRuntimeCodec.decodeError(checkNotNull(metadata)))
            } catch (decodeFailure: Throwable) {
                failure.set(decodeFailure.message ?: decodeFailure.javaClass.name)
            } finally {
                terminal.countDown()
            }
        }

        override fun onCancelled(metadata: ByteArray?) {
            terminalCalls.incrementAndGet()
            try {
                cancellation.set(LuaRuntimeCodec.decodeCancellation(checkNotNull(metadata)))
            } catch (decodeFailure: Throwable) {
                failure.set(decodeFailure.message ?: decodeFailure.javaClass.name)
            } finally {
                terminal.countDown()
            }
        }

        fun awaitReturnSeven(): Long {
            check(terminal.await(RETURN_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for recovered Lua execution"
            }
            check(failure.get() == null) { checkNotNull(failure.get()) }
            check(terminalCalls.get() == 1 && completedCalls.get() == 1) {
                "Recovery did not emit exactly one completed terminal callback"
            }
            val result = value.get()
            check(result is LuaValue.Int64Value && result.value == 7L) {
                "Recovered Lua runtime returned an unexpected value: $result"
            }
            return result.value
        }

        fun awaitFailure(
            expectedCode: LuaExecutionErrorCode,
            expectedPhase: LuaExecutionFailurePhase,
        ) {
            check(terminal.await(RETURN_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for the expected Lua failure"
            }
            check(failure.get() == null) { checkNotNull(failure.get()) }
            check(startedCalls.get() == 0) { "Source validation failure emitted onStarted" }
            check(terminalCalls.get() == 1 && completedCalls.get() == 0) {
                "Failure path did not emit exactly one failed terminal callback"
            }
            val observed = checkNotNull(error.get()) { "Failure callback did not decode an error" }
            check(observed.code == expectedCode && observed.phase == expectedPhase) {
                "Unexpected Lua failure: $observed"
            }
        }

        fun awaitRequestedCancellation() {
            check(terminal.await(RETURN_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for requested Lua cancellation"
            }
            check(failure.get() == null) { checkNotNull(failure.get()) }
            check(startedCalls.get() == 1) { "Cancellation path did not start exactly once" }
            check(terminalCalls.get() == 1 && completedCalls.get() == 0) {
                "Cancellation path did not emit exactly one cancellation terminal"
            }
            val observed = checkNotNull(cancellation.get()) {
                "Cancellation callback did not decode a cancellation"
            }
            check(observed.reason == LuaCancellationReason.REQUESTED) {
                "Unexpected cancellation: $observed"
            }
        }

        fun awaitStarted() {
            check(started.await(STARTED_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for the remote Lua started callback"
            }
            check(failure.get() == null) { checkNotNull(failure.get()) }
            check(startedCalls.get() == 1) { "Execution emitted a duplicate started callback" }
        }

        fun assertStartedWithoutTerminal() {
            check(startedCalls.get() == 1) { "Fault execution did not emit exactly one started callback" }
            check(terminalCalls.get() == 0) { "Fault execution emitted a terminal callback before death" }
        }

        fun assertNoCallbacks() {
            check(startedCalls.get() == 0) { "Blocked source emitted an unexpected started callback" }
            check(terminalCalls.get() == 0) { "Blocked source emitted an unexpected terminal callback" }
            check(failure.get() == null) { checkNotNull(failure.get()) }
        }

        fun assertCompletedOnce() {
            check(startedCalls.get() == 1) { "Recovery did not emit exactly one started callback" }
            check(terminalCalls.get() == 1 && completedCalls.get() == 1) {
                "Recovery did not emit exactly one completed callback"
            }
        }
    }

    private class RejectingHostBroker : ILuaHostCapabilityBroker.Stub() {
        override fun invoke(
            requestMetadata: ByteArray?,
            payloads: Array<out ParcelFileDescriptor?>?,
            callback: ILuaHostCapabilityCallback?,
        ) {
            error("The debug Lua fault harness exposes no host capabilities")
        }
    }

    private class RunningExecution(
        private val session: ILuaExecutionSession,
        val callback: RecordingExecutionCallback,
        @Suppress("unused") private val broker: RejectingHostBroker,
    ) : AutoCloseable {
        override fun close() {
            runCatching { session.close() }
        }

        fun cancel() {
            session.cancel()
        }

        fun closeAfterRemoteDeath() = close()
    }

    private class BoundFaultHarness(
        private val context: Context,
        private val connection: ServiceConnection,
        val client: FaultClient,
    ) : AutoCloseable {
        override fun close() {
            runCatching { context.unbindService(connection) }
        }
    }

    private class BoundFaultPeer(
        private val context: Context,
        private val connection: ServiceConnection,
        val client: FaultPeerClient,
    ) : AutoCloseable {
        override fun close() {
            runCatching { context.unbindService(connection) }
        }
    }

    private enum class PeerRole {
        CALLBACK,
        BROKER,
    }

    private companion object {
        val PROTOCOL = LuaProtocolVersion(
            LuaRuntimeContract.PROTOCOL_MAJOR,
            LuaRuntimeContract.PROTOCOL_MINOR,
        )
        val RETURN_SEVEN_SOURCE = "return 7".toByteArray(Charsets.UTF_8)
        val CANCELLATION_SOURCE = "while true do end".toByteArray(Charsets.UTF_8)
        val CRASH_SOURCE = "-- AUTOJS_DEBUG_NATIVE_CRASH".toByteArray(Charsets.UTF_8)
        val WEDGE_SOURCE = "-- AUTOJS_DEBUG_NATIVE_WEDGE".toByteArray(Charsets.UTF_8)
        const val TEST_TIMEOUT_MILLIS = 40_000L
        const val FAULT_TIMEOUT_MILLIS = 200L
        const val RETURN_TIMEOUT_MILLIS = 5_000L
        const val FD_BATCH_REPETITIONS = 4
        const val FD_SETTLE_TIMEOUT_MILLIS = 5_000L
        const val FD_POLL_MILLIS = 25L
        const val REQUIRED_STABLE_FD_SAMPLES = 3
        const val PEER_POLL_MILLIS = 25L
        const val BLOCKED_SOURCE_MAX_ELAPSED_MILLIS = 6_000L
        const val PROCESS_DEATH_TIMEOUT_SECONDS = 15L
        const val BLOCKED_SOURCE_DEATH_TIMEOUT_SECONDS = 8L
        const val PEER_DEATH_TIMEOUT_SECONDS = 5L
        const val RETURN_RESULT_TIMEOUT_SECONDS = 8L
        const val STARTED_TIMEOUT_SECONDS = 5L
        const val BIND_TIMEOUT_SECONDS = 5L
        const val REBIND_TIMEOUT_MILLIS = 10_000L
        const val REBIND_RETRY_MILLIS = 100L
    }
}
