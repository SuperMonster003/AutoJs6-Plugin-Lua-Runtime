package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaExecutionObserver
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaExecutionRunner
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaExecutionSessionController
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaExecutionSource
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaExecutionWatchdog
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaExecutionWatchdogLease
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaMonotonicClock
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaSerialExecutionWorker
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaSourceVerifier
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaStartLease
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.RetainedExecutionGate
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.RetainedExecutionLease
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.SingleActiveExecutionGate
import org.autojs.plugin.lua.runtime.api.ILuaExecutionCallback
import org.autojs.plugin.lua.runtime.api.ILuaExecutionSession
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityBroker
import org.autojs.plugin.lua.runtime.api.LuaExecutionCancellation
import org.autojs.plugin.lua.runtime.api.LuaExecutionError
import org.autojs.plugin.lua.runtime.api.LuaExecutionErrorCode
import org.autojs.plugin.lua.runtime.api.LuaExecutionFailurePhase
import org.autojs.plugin.lua.runtime.api.LuaExecutionRequest
import org.autojs.plugin.lua.runtime.api.LuaExecutionResult
import org.autojs.plugin.lua.runtime.api.LuaExecutionStarted
import org.autojs.plugin.lua.runtime.api.LuaOutputChunk
import org.autojs.plugin.lua.runtime.api.LuaRetryDisposition
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import org.autojs.plugin.lua.runtime.api.LuaRuntimeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Owns process-local admission, worker lifetime, Binder peers, and source PFDs. */
internal class LuaRuntimeExecutionManager(
    private val callerVerifier: HostCallerVerifier,
    private val runner: LuaExecutionRunner,
) : AutoCloseable {
    private val sessions = ConcurrentHashMap.newKeySet<RemoteLuaExecutionSession>()
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val startLeaseScheduler = ScheduledThreadPoolExecutor(
        1,
        { task ->
            Thread(task, "autojs-lua-session-lease-${LEASE_THREAD_SEQUENCE.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
    ).apply {
        setRemoveOnCancelPolicy(true)
    }

    fun create(
        createdNanos: Long,
        ownerUid: Int,
        runtimeInfo: LuaRuntimeInfo,
        request: LuaExecutionRequest,
        incomingSource: ParcelFileDescriptor,
        callback: ILuaExecutionCallback,
        hostBroker: ILuaHostCapabilityBroker,
    ): ILuaExecutionSession = synchronized(lifecycleLock) {
        check(!closed.get()) { "Lua execution manager is closed" }
        val retainedLease = ProcessExecutionResources.tryRetainSession() ?: error(
            "Lua runtime retained-session capacity is exhausted"
        )
        val token = Any()
        var activeAcquired = false
        var watchdogLease: LuaExecutionWatchdogLease? = null
        var ownedSource: LuaParcelFileExecutionSource? = null
        try {
            activeAcquired = ProcessExecutionResources.active.tryAcquire(token)
            if (!activeAcquired) {
                return newRemoteSession(
                    ownerUid = ownerUid,
                    runtimeInfo = runtimeInfo,
                    request = request,
                    source = EmptyLuaExecutionSource,
                    callback = callback,
                    hostBroker = hostBroker,
                    initialFailure = busyError(request),
                    createdNanos = createdNanos,
                    retainedLease = retainedLease,
                    watchdogLease = RejectedSessionWatchdogLease,
                    onFinished = {},
                )
            }
            val admittedWatchdog = ProcessExecutionResources.watchdog.tryAcquire(
                token = token,
                createdNanos = createdNanos,
                timeoutMillis = request.timeoutMillis,
            ) ?: error("The Lua runtime watchdog is unavailable or poisoned")
            watchdogLease = admittedWatchdog
            val admittedSource = LuaParcelFileExecutionSource.duplicateOf(incomingSource)
            ownedSource = admittedSource
            newRemoteSession(
                ownerUid = ownerUid,
                runtimeInfo = runtimeInfo,
                request = request,
                source = admittedSource,
                callback = callback,
                hostBroker = hostBroker,
                initialFailure = null,
                createdNanos = createdNanos,
                retainedLease = retainedLease,
                watchdogLease = admittedWatchdog,
                onFinished = { ProcessExecutionResources.active.release(token) },
            )
        } catch (error: Throwable) {
            ownedSource?.close()
            watchdogLease?.runCatching { close() }
            if (activeAcquired) ProcessExecutionResources.active.release(token)
            retainedLease.close()
            throw error
        }
    }

    override fun close() {
        val snapshot = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            sessions.toList()
        }
        snapshot.forEach(RemoteLuaExecutionSession::serviceDestroyed)
        startLeaseScheduler.shutdownNow()
    }

    private fun newRemoteSession(
        ownerUid: Int,
        runtimeInfo: LuaRuntimeInfo,
        request: LuaExecutionRequest,
        source: LuaExecutionSource,
        callback: ILuaExecutionCallback,
        hostBroker: ILuaHostCapabilityBroker,
        initialFailure: LuaExecutionError?,
        createdNanos: Long,
        retainedLease: RetainedExecutionLease,
        watchdogLease: LuaExecutionWatchdogLease,
        onFinished: () -> Unit,
    ): RemoteLuaExecutionSession {
        val observer = BinderLuaExecutionObserver(callback, hostBroker)
        lateinit var remote: RemoteLuaExecutionSession
        val controller = LuaExecutionSessionController(
            request = request,
            runtimeInfo = runtimeInfo,
            source = source,
            runner = runner,
            dispatcher = ProcessExecutionResources.worker,
            watchdog = watchdogLease,
            observer = observer,
            initialFailure = initialFailure,
            createdNanos = createdNanos,
            onFinished = {
                sessions.remove(remote)
                retainedLease.close()
                onFinished()
            },
        )
        remote = RemoteLuaExecutionSession(ownerUid, callerVerifier, controller)
        sessions += remote
        try {
            observer.linkToPeers(controller::observerDied)
            armStartLease(controller, request, createdNanos)
        } catch (error: Throwable) {
            controller.observerDied()
            throw error
        }
        return remote
    }

    private fun armStartLease(
        controller: LuaExecutionSessionController,
        request: LuaExecutionRequest,
        createdNanos: Long,
    ) {
        val leaseNanos = minOf(request.timeoutMillis, MAX_START_LEASE_MILLIS) * NANOS_PER_MILLI
        val elapsedNanos = (System.nanoTime() - createdNanos).coerceAtLeast(0L)
        val future = startLeaseScheduler.schedule(
            { controller.expireIfNotStarted() },
            (leaseNanos - elapsedNanos).coerceAtLeast(0L),
            TimeUnit.NANOSECONDS,
        )
        controller.armStartLease(
            LuaStartLease {
                future.cancel(false)
                Unit
            },
        )
    }

    private fun busyError(request: LuaExecutionRequest): LuaExecutionError = LuaExecutionError(
        requestId = request.requestId,
        code = LuaExecutionErrorCode.BUSY,
        phase = LuaExecutionFailurePhase.QUEUE,
        message = "The Lua runtime already has an active execution",
        retryDisposition = LuaRetryDisposition.NEW_REQUEST_MAY_SUCCEED,
    )

    private companion object {
        const val MAX_START_LEASE_MILLIS = 30_000L
        const val NANOS_PER_MILLI = 1_000_000L
        val LEASE_THREAD_SEQUENCE = AtomicInteger()
    }
}

private object ProcessExecutionResources {
    val active = SingleActiveExecutionGate<Any>()
    val worker = LuaSerialExecutionWorker()
    val watchdog = LuaExecutionWatchdog(
        clock = LuaMonotonicClock(System::nanoTime),
        scheduler = LuaRuntimeWatchdogScheduler,
        terminator = AndroidLuaRuntimeProcessTerminator,
        cleanupGraceMillis = WATCHDOG_CLEANUP_GRACE_MILLIS,
    )
    private val retainedSessions = RetainedExecutionGate(MAX_RETAINED_SESSIONS)

    fun tryRetainSession(): RetainedExecutionLease? = retainedSessions.tryAcquire()

    private const val MAX_RETAINED_SESSIONS = 2
    private const val WATCHDOG_CLEANUP_GRACE_MILLIS = 2_000L
}

/** A typed BUSY session never dispatches and therefore owns no process watchdog slot. */
private object RejectedSessionWatchdogLease : LuaExecutionWatchdogLease {
    override fun executionDispatched(): Boolean = false

    override fun stopRequested() = Unit

    override fun close() = Unit
}

private class RemoteLuaExecutionSession(
    private val ownerUid: Int,
    private val callerVerifier: HostCallerVerifier,
    private val controller: LuaExecutionSessionController,
) : ILuaExecutionSession.Stub() {
    override fun start() {
        callerVerifier.enforceSessionOwner(ownerUid)
        controller.start()
    }

    override fun grantOutputCredits(count: Int) {
        callerVerifier.enforceSessionOwner(ownerUid)
        controller.grantOutputCredits(count)
    }

    override fun cancel() {
        callerVerifier.enforceSessionOwner(ownerUid)
        controller.cancel()
    }

    override fun close() {
        callerVerifier.enforceSessionOwner(ownerUid)
        controller.close()
    }

    fun serviceDestroyed() {
        controller.close()
    }
}

private class BinderLuaExecutionObserver(
    private val callback: ILuaExecutionCallback,
    hostBroker: ILuaHostCapabilityBroker,
) : LuaExecutionObserver {
    private val deathHandler = AtomicReference<(() -> Unit)?>(null)
    private val closed = AtomicBoolean(false)
    private val peerBinders = listOf(callback.asBinder(), hostBroker.asBinder()).distinct()
    private val linkedBinders = ConcurrentHashMap.newKeySet<IBinder>()
    private val deathRecipient = IBinder.DeathRecipient { deathHandler.get()?.invoke() }
    private val monitorLock = Any()

    fun linkToPeers(onDeath: () -> Unit) = synchronized(monitorLock) {
        check(!closed.get()) { "Lua Binder peer monitor is closed" }
        check(deathHandler.compareAndSet(null, onDeath)) { "Lua Binder peers are already monitored" }
        try {
            peerBinders.forEach { binder ->
                binder.linkToDeath(deathRecipient, 0)
                linkedBinders += binder
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun onStarted(started: LuaExecutionStarted) {
        callback.onStarted(LuaRuntimeCodec.encodeStarted(started))
    }

    override fun onOutput(output: LuaOutputChunk) {
        callback.onOutput(LuaRuntimeCodec.encodeOutput(output))
    }

    override fun onCompleted(result: LuaExecutionResult) {
        callback.onCompleted(
            LuaRuntimeCodec.encodeResult(result),
            emptyArray<ParcelFileDescriptor>(),
        )
    }

    override fun onFailed(error: LuaExecutionError) {
        callback.onFailed(LuaRuntimeCodec.encodeError(error))
    }

    override fun onCancelled(cancellation: LuaExecutionCancellation) {
        callback.onCancelled(LuaRuntimeCodec.encodeCancellation(cancellation))
    }

    override fun close() = synchronized(monitorLock) {
        if (!closed.compareAndSet(false, true)) return
        deathHandler.set(null)
        linkedBinders.toList().forEach { binder ->
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            linkedBinders.remove(binder)
        }
    }
}

private class LuaParcelFileExecutionSource private constructor(
    private val descriptor: ParcelFileDescriptor,
) : LuaExecutionSource {
    private val readClaimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun readVerified(request: LuaExecutionRequest): ByteArray {
        check(readClaimed.compareAndSet(false, true)) { "Lua source descriptor was already consumed" }
        try {
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                LuaSourceVerifier.read(input, request)
            }
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { descriptor.close() }
    }

    companion object {
        fun duplicateOf(incoming: ParcelFileDescriptor): LuaParcelFileExecutionSource =
            LuaParcelFileExecutionSource(ParcelFileDescriptor.dup(incoming.fileDescriptor))
    }
}

private object EmptyLuaExecutionSource : LuaExecutionSource {
    override fun readVerified(request: LuaExecutionRequest): ByteArray =
        error("A rejected Lua session has no source descriptor")

    override fun close() = Unit
}
