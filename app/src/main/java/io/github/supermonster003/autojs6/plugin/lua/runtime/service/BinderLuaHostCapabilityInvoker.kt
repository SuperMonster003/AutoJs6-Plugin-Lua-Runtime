package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import android.os.Binder
import android.os.ParcelFileDescriptor
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaCancellationProbe
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaHostCapabilityException
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaHostCapabilityFailureKind
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaHostCapabilityInvoker
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityBroker
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityCallback
import org.autojs.plugin.lua.runtime.api.LuaHostCallError
import org.autojs.plugin.lua.runtime.api.LuaHostCallPolicy
import org.autojs.plugin.lua.runtime.api.LuaHostCallRequest
import org.autojs.plugin.lua.runtime.api.LuaHostCallResult
import org.autojs.plugin.lua.runtime.api.LuaHostErrorCode
import org.autojs.plugin.lua.runtime.api.LuaRequestId
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import org.autojs.plugin.lua.runtime.api.LuaValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-execution client for the host-owned capability broker.
 *
 * Binder callbacks are asynchronous, but Lua's reviewed built-ins are synchronous. This adapter
 * therefore waits only inside the provider's serial worker and rechecks both the runner deadline
 * and cancellation every short interval. It never retries a dispatched host call.
 */
internal class BinderLuaHostCapabilityInvoker(
    private val broker: ILuaHostCapabilityBroker,
    private val executionId: LuaRequestId,
    allowedCapabilities: Collection<String>,
    private val ownerUid: Int,
    private val callerVerifier: LuaSessionCallerVerifier,
) : LuaHostCapabilityInvoker, AutoCloseable {
    private val policy = LuaHostCallPolicy(executionId, allowedCapabilities)
    private val sequence = AtomicLong()

    override fun invoke(
        capability: String,
        arguments: LuaValue,
        timeoutMillis: Long,
        cancellationProbe: LuaCancellationProbe,
    ): LuaValue {
        if (timeoutMillis <= 0L) throw deadlineExceeded()
        if (isCancelled(cancellationProbe)) throw cancelled()

        val callId = "provider-${sequence.incrementAndGet()}"
        val request = LuaHostCallRequest(executionId, callId, capability, arguments)
        try {
            policy.open(request)
        } catch (failure: IllegalArgumentException) {
            throw LuaHostCapabilityException(
                LuaHostCapabilityFailureKind.DENIED,
                "Lua host capability was not granted",
                failure,
            )
        } catch (failure: Throwable) {
            throw LuaHostCapabilityException(
                LuaHostCapabilityFailureKind.PROTOCOL,
                "Lua host capability request was rejected before dispatch",
                failure,
            )
        }

        val terminal = AtomicReference<Outcome?>()
        val latch = CountDownLatch(1)
        val callback = object : ILuaHostCapabilityCallback.Stub() {
            override fun onCompleted(
                resultMetadata: ByteArray?,
                payloads: Array<out ParcelFileDescriptor?>?,
            ) {
                val descriptors = payloads.orEmpty()
                var identityToken: Long? = null
                try {
                    callerVerifier.enforceSessionOwner(ownerUid)
                    identityToken = Binder.clearCallingIdentity()
                    if (descriptors.isNotEmpty()) {
                        throw IllegalArgumentException("Lua protocol V1 host results cannot contain descriptors")
                    }
                    val result = LuaRuntimeCodec.decodeHostResult(
                        resultMetadata ?: throw IllegalArgumentException("Lua host result is null"),
                    )
                    policy.complete(result, descriptors.size)
                    publish(Outcome.Completed(result))
                } catch (failure: Throwable) {
                    publish(Outcome.ProtocolFailure(failure))
                } finally {
                    identityToken?.let(Binder::restoreCallingIdentity)
                    descriptors.forEach { descriptor -> runCatching { descriptor?.close() } }
                }
            }

            override fun onFailed(errorMetadata: ByteArray?) {
                var identityToken: Long? = null
                try {
                    callerVerifier.enforceSessionOwner(ownerUid)
                    identityToken = Binder.clearCallingIdentity()
                    val error = LuaRuntimeCodec.decodeHostError(
                        errorMetadata ?: throw IllegalArgumentException("Lua host error is null"),
                    )
                    policy.fail(error)
                    publish(Outcome.HostFailure(error))
                } catch (failure: Throwable) {
                    publish(Outcome.ProtocolFailure(failure))
                } finally {
                    identityToken?.let(Binder::restoreCallingIdentity)
                }
            }

            private fun publish(outcome: Outcome) {
                if (terminal.compareAndSet(null, outcome)) latch.countDown()
            }
        }

        try {
            broker.invoke(
                LuaRuntimeCodec.encodeHostRequest(request),
                emptyArray<ParcelFileDescriptor>(),
                callback,
            )
        } catch (failure: Throwable) {
            throw LuaHostCapabilityException(
                LuaHostCapabilityFailureKind.HOST,
                "Lua host capability dispatch failed",
                failure,
            )
        }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        while (terminal.get() == null) {
            if (isCancelled(cancellationProbe)) throw cancelled()
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw deadlineExceeded()
            try {
                latch.await(minOf(remainingNanos, POLL_NANOS), TimeUnit.NANOSECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LuaHostCapabilityException(
                    LuaHostCapabilityFailureKind.CANCELLED,
                    "Lua host capability wait was interrupted",
                    failure,
                )
            }
        }

        return when (val outcome = checkNotNull(terminal.get())) {
            is Outcome.Completed -> outcome.result.value
            is Outcome.HostFailure -> throw outcome.error.toException()
            is Outcome.ProtocolFailure -> throw LuaHostCapabilityException(
                LuaHostCapabilityFailureKind.PROTOCOL,
                "Lua host capability callback violated the protocol",
                outcome.failure,
            )
        }
    }

    override fun close() {
        policy.close()
    }

    private fun isCancelled(probe: LuaCancellationProbe): Boolean = try {
        probe.isCancellationRequested()
    } catch (failure: Throwable) {
        throw LuaHostCapabilityException(
            LuaHostCapabilityFailureKind.INTERNAL,
            "Lua host capability cancellation probe failed",
            failure,
        )
    }

    private fun LuaHostCallError.toException(): LuaHostCapabilityException {
        val kind = when (code) {
            LuaHostErrorCode.CANCELLED -> LuaHostCapabilityFailureKind.CANCELLED
            LuaHostErrorCode.CAPABILITY_DENIED,
            LuaHostErrorCode.CAPABILITY_UNAVAILABLE,
            -> LuaHostCapabilityFailureKind.DENIED
            LuaHostErrorCode.INVALID_REQUEST -> LuaHostCapabilityFailureKind.PROTOCOL
            LuaHostErrorCode.QUOTA_EXCEEDED -> LuaHostCapabilityFailureKind.HOST
            LuaHostErrorCode.INTERNAL -> LuaHostCapabilityFailureKind.HOST
        }
        return LuaHostCapabilityException(kind, "Lua host capability failed: ${code.name}")
    }

    private sealed interface Outcome {
        data class Completed(val result: LuaHostCallResult) : Outcome
        data class HostFailure(val error: LuaHostCallError) : Outcome
        data class ProtocolFailure(val failure: Throwable) : Outcome
    }

    private companion object {
        const val POLL_MILLIS = 20L
        const val NANOS_PER_MILLI = 1_000_000L
        const val POLL_NANOS = POLL_MILLIS * NANOS_PER_MILLI

        fun deadlineAfter(timeoutMillis: Long): Long {
            val now = System.nanoTime()
            val duration = timeoutMillis.coerceAtMost(Long.MAX_VALUE / NANOS_PER_MILLI) * NANOS_PER_MILLI
            return if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration
        }

        fun cancelled() = LuaHostCapabilityException(
            LuaHostCapabilityFailureKind.CANCELLED,
            "Lua host capability call was cancelled",
        )

        fun deadlineExceeded() = LuaHostCapabilityException(
            LuaHostCapabilityFailureKind.DEADLINE_EXCEEDED,
            "Lua host capability call exceeded its deadline",
        )
    }
}
