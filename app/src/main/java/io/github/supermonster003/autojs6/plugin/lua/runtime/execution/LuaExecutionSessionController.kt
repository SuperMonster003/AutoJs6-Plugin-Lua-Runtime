package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.autojs.plugin.lua.runtime.api.LuaCancellationReason
import org.autojs.plugin.lua.runtime.api.LuaExecutionCancellation
import org.autojs.plugin.lua.runtime.api.LuaExecutionError
import org.autojs.plugin.lua.runtime.api.LuaExecutionErrorCode
import org.autojs.plugin.lua.runtime.api.LuaExecutionFailurePhase
import org.autojs.plugin.lua.runtime.api.LuaExecutionRequest
import org.autojs.plugin.lua.runtime.api.LuaExecutionResult
import org.autojs.plugin.lua.runtime.api.LuaExecutionStarted
import org.autojs.plugin.lua.runtime.api.LuaOutputChunk
import org.autojs.plugin.lua.runtime.api.LuaOutputStream
import org.autojs.plugin.lua.runtime.api.LuaRetryDisposition
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaRuntimeInfo
import org.autojs.plugin.lua.runtime.api.LuaRuntimeValidation
import org.autojs.plugin.lua.runtime.api.LuaValue
import java.util.concurrent.atomic.AtomicBoolean

internal interface LuaExecutionObserver : AutoCloseable {
    fun onStarted(started: LuaExecutionStarted)

    fun onOutput(output: LuaOutputChunk)

    fun onCompleted(result: LuaExecutionResult)

    fun onFailed(error: LuaExecutionError)

    fun onCancelled(cancellation: LuaExecutionCancellation)

    override fun close()
}

internal fun interface LuaMonotonicClock {
    fun nanoTime(): Long
}

internal fun interface LuaStartLease : AutoCloseable {
    override fun close()
}

/**
 * Thread-safe lifecycle owner. Binder methods only mutate this state; source
 * validation and the blocking runner execute on the injected zero-queue worker.
 */
internal class LuaExecutionSessionController(
    private val request: LuaExecutionRequest,
    private val runtimeInfo: LuaRuntimeInfo,
    private val source: LuaExecutionSource,
    private val runner: LuaExecutionRunner,
    private val dispatcher: LuaExecutionDispatcher,
    private val watchdog: LuaExecutionWatchdogLease,
    private val observer: LuaExecutionObserver,
    private val initialFailure: LuaExecutionError? = null,
    private val clock: LuaMonotonicClock = LuaMonotonicClock(System::nanoTime),
    private val createdNanos: Long = clock.nanoTime(),
    private val onFinished: () -> Unit,
) {
    private enum class Phase {
        CREATED,
        DISPATCHED,
        RUNNING,
        FINISHED,
    }

    data class Snapshot(
        val startRequested: Boolean,
        val cancelRequested: Boolean,
        val closeRequested: Boolean,
        val terminalClaimed: Boolean,
        val observerAvailable: Boolean,
        val outstandingOutputCredits: Int,
        val emittedOutputBytes: Long,
        val finished: Boolean,
    )

    private val lock = Any()
    private val cleanupStarted = AtomicBoolean(false)
    private val callbackGate = LuaCallbackDeliveryGate {
        runCatching { observer.close() }
        Unit
    }
    private var phase = Phase.CREATED
    private var startRequested = false
    private var cancelRequested = false
    private var closeRequested = false
    private var terminalClaimed = false
    private var observerAvailable = true
    private var startedCallbackSent = false
    private var pendingFailure = initialFailure
    private var outstandingOutputCredits = 0
    private var emittedOutputBytes = 0L
    private var nextOutputSequence = 0L
    private var startLease: LuaStartLease? = null

    fun start(): Boolean {
        var immediateFailure: LuaExecutionError? = null
        var leaseToCancel: LuaStartLease? = null
        val shouldDispatch = synchronized(lock) {
            if (startRequested || closeRequested || terminalClaimed || phase == Phase.FINISHED) {
                false
            } else {
                startRequested = true
                leaseToCancel = startLease
                startLease = null
                immediateFailure = pendingFailure
                if (immediateFailure == null) {
                    phase = Phase.DISPATCHED
                    true
                } else {
                    terminalClaimed = true
                    phase = Phase.FINISHED
                    false
                }
            }
        }
        leaseToCancel?.closeQuietly()
        immediateFailure?.let { failure ->
            deliver { it.onFailed(failure) }
            finish()
            return true
        }
        if (!shouldDispatch) return false
        try {
            if (!watchdog.executionDispatched()) {
                handleDispatchUnavailable(
                    code = LuaExecutionErrorCode.INTERNAL,
                    message = "The Lua execution watchdog could not be armed",
                )
            } else if (!dispatcher.dispatch(Runnable(::runOnWorker))) {
                handleDispatchUnavailable(
                    code = LuaExecutionErrorCode.BUSY,
                    message = "The serial Lua execution worker is unavailable",
                    retryDisposition = LuaRetryDisposition.NEW_REQUEST_MAY_SUCCEED,
                )
            }
        } catch (_: Throwable) {
            handleDispatchUnavailable(
                code = LuaExecutionErrorCode.INTERNAL,
                message = "The serial Lua execution worker rejected dispatch internally",
            )
        }
        return true
    }

    fun armStartLease(lease: LuaStartLease): Boolean {
        val armed = synchronized(lock) {
            if (phase != Phase.CREATED || startRequested || closeRequested || terminalClaimed) {
                false
            } else {
                check(startLease == null) { "Lua session start lease is already armed" }
                startLease = lease
                true
            }
        }
        if (!armed) lease.closeQuietly()
        return armed
    }

    fun expireIfNotStarted(): Boolean {
        var leaseToCancel: LuaStartLease? = null
        val expired = synchronized(lock) {
            if (phase != Phase.CREATED || startRequested || closeRequested || terminalClaimed) {
                false
            } else {
                closeRequested = true
                cancelRequested = true
                observerAvailable = false
                terminalClaimed = true
                phase = Phase.FINISHED
                leaseToCancel = startLease
                startLease = null
                true
            }
        }
        if (!expired) return false
        leaseToCancel?.closeQuietly()
        source.close()
        finish()
        return true
    }

    /** Credit grants are additive; calls after terminal/close are harmless no-ops. */
    fun grantOutputCredits(count: Int): Boolean {
        var stopDispatchedWork = false
        val accepted = synchronized(lock) {
            if (closeRequested || terminalClaimed || phase == Phase.FINISHED) {
                false
            } else if (
                count <= 0 ||
                count > LuaRuntimeContract.MAX_OUTSTANDING_OUTPUT_CREDITS - outstandingOutputCredits
            ) {
                val failure = protocolFailure("Invalid or overflowing output-credit grant")
                pendingFailure = failure
                if (startRequested) {
                    cancelRequested = true
                    stopDispatchedWork = true
                }
                false
            } else {
                outstandingOutputCredits += count
                true
            }
        }
        if (stopDispatchedWork) watchdog.stopRequestedQuietly()
        if (!accepted && synchronized(lock) { startRequested }) source.close()
        return accepted
    }

    fun cancel(): Boolean {
        var finishWithoutWorker = false
        var cancellation: LuaExecutionCancellation? = null
        val changed = synchronized(lock) {
            if (cancelRequested || closeRequested || terminalClaimed || phase == Phase.FINISHED) {
                false
            } else {
                cancelRequested = true
                if (!terminalClaimed && phase == Phase.CREATED) {
                    terminalClaimed = true
                    cancellation = cancellation(LuaCancellationReason.REQUESTED)
                    phase = Phase.FINISHED
                    finishWithoutWorker = true
                }
                true
            }
        }
        if (!changed) return false
        watchdog.stopRequestedQuietly()
        source.close()
        cancellation?.let { value -> deliver { it.onCancelled(value) } }
        if (finishWithoutWorker) finish()
        return true
    }

    fun close(): Boolean = abandonObserver()

    fun observerDied(): Boolean = abandonObserver()

    /** Emits one already-bounded runner chunk through the session's credit-controlled callback. */
    fun emitOutput(stream: LuaOutputStream, text: String): Boolean {
        var output: LuaOutputChunk? = null
        var failed = false
        synchronized(lock) {
            if (
                !startedCallbackSent || closeRequested || cancelRequested || terminalClaimed ||
                phase == Phase.FINISHED
            ) {
                return false
            }
            val candidate = LuaOutputChunk(
                requestId = request.requestId,
                sequence = nextOutputSequence,
                stream = stream,
                text = text,
            )
            val outputBytes = text.toByteArray(Charsets.UTF_8).size.toLong()
            val invalid = runCatching { LuaRuntimeValidation.validateOutput(candidate) }.isFailure ||
                outstandingOutputCredits <= 0 ||
                outputBytes > request.outputByteLimit - emittedOutputBytes ||
                nextOutputSequence == Long.MAX_VALUE
            if (invalid) {
                cancelRequested = true
                pendingFailure = protocolFailure("Runner output violated sequence, credit, or byte limits")
                failed = true
            } else {
                outstandingOutputCredits -= 1
                emittedOutputBytes += outputBytes
                nextOutputSequence += 1L
                output = candidate
            }
        }
        if (failed) {
            watchdog.stopRequestedQuietly()
            return false
        }
        return output?.let { value -> deliver { it.onOutput(value) } } ?: false
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            startRequested = startRequested,
            cancelRequested = cancelRequested,
            closeRequested = closeRequested,
            terminalClaimed = terminalClaimed,
            observerAvailable = observerAvailable,
            outstandingOutputCredits = outstandingOutputCredits,
            emittedOutputBytes = emittedOutputBytes,
            finished = phase == Phase.FINISHED && cleanupStarted.get(),
        )
    }

    private fun runOnWorker() {
        synchronized(lock) {
            if (phase == Phase.FINISHED) return
            phase = Phase.RUNNING
        }
        try {
            if (convergePendingStop()) return
            val timeoutBeforeSource = remainingTimeoutMillis()
            if (timeoutBeforeSource <= 0L) {
                claimAndDeliverFailure(timeoutFailure(LuaExecutionFailurePhase.QUEUE))
                return
            }
            val sourceUtf8 = try {
                source.readVerified(request)
            } catch (error: LuaSourceException) {
                if (!convergePendingStop()) claimAndDeliverFailure(sourceFailure(error))
                return
            } catch (error: Throwable) {
                if (!convergePendingStop()) claimAndDeliverFailure(
                    executionError(
                        LuaExecutionErrorCode.DESCRIPTOR_FAILED,
                        LuaExecutionFailurePhase.SOURCE_VALIDATION,
                        "Lua source validation failed",
                    ),
                )
                return
            }
            if (convergePendingStop()) return
            if (remainingTimeoutMillis() <= 0L) {
                claimAndDeliverFailure(timeoutFailure(LuaExecutionFailurePhase.SOURCE_VALIDATION))
                return
            }
            if (!sendStarted()) return
            if (convergePendingStop()) return
            val runnerTimeoutMillis = remainingTimeoutMillis()
            if (runnerTimeoutMillis <= 0L) {
                claimAndDeliverFailure(timeoutFailure(LuaExecutionFailurePhase.EXECUTION))
                return
            }
            val result = try {
                runner.execute(
                    LuaRunnerRequest(
                        sourceUtf8 = sourceUtf8,
                        sourceName = request.sourceName,
                        arguments = request.arguments,
                        memoryLimitBytes = request.memoryByteLimit,
                        timeoutMillis = runnerTimeoutMillis,
                        cancellationProbe = LuaCancellationProbe(::isCancellationRequested),
                        outputEmitter = LuaOutputEmitter(::emitOutput),
                    ),
                )
            } catch (error: LuaRunnerException) {
                handleRunnerFailure(error)
                return
            } catch (_: Throwable) {
                claimAndDeliverFailure(
                    executionError(
                        LuaExecutionErrorCode.INTERNAL,
                        LuaExecutionFailurePhase.EXECUTION,
                        "The Lua execution runner failed internally",
                    ),
                )
                return
            }
            if (!convergePendingStop()) {
                if (remainingTimeoutMillis() <= 0L) {
                    claimAndDeliverFailure(timeoutFailure(LuaExecutionFailurePhase.EXECUTION))
                } else {
                    claimAndDeliverResult(result)
                }
            }
        } finally {
            finish()
        }
    }

    private fun sendStarted(): Boolean {
        val started = synchronized(lock) {
            if (closeRequested || cancelRequested || terminalClaimed || !observerAvailable) {
                null
            } else {
                startedCallbackSent = true
                nextOutputSequence = 0L
                LuaExecutionStarted(
                    requestId = request.requestId,
                    protocolVersion = request.protocolVersion,
                    runtimeSlot = runtimeInfo.runtimeSlot,
                    languageVersion = runtimeInfo.languageVersion,
                    firstOutputSequence = 0L,
                    queueElapsedMillis = elapsedMillis(),
                )
            }
        } ?: return false
        return deliver { it.onStarted(started) }
    }

    private fun handleRunnerFailure(error: LuaRunnerException) {
        if (convergePendingStop()) return
        if (remainingTimeoutMillis() <= 0L) {
            claimAndDeliverFailure(timeoutFailure(LuaExecutionFailurePhase.EXECUTION))
        } else {
            claimAndDeliverFailure(runnerFailure(error.kind))
        }
    }

    private fun claimAndDeliverResult(value: LuaValue) {
        val result = LuaExecutionResult(request.requestId, value, elapsedMillis())
        val valid = runCatching { LuaRuntimeValidation.validateResult(result) }.isSuccess
        if (!valid) {
            claimAndDeliverFailure(runnerFailure(LuaRunnerFailureKind.UNSUPPORTED_RESULT))
            return
        }
        if (claimTerminal()) {
            deliver { it.onCompleted(result) }
        } else {
            convergePendingStop()
        }
    }

    private fun claimAndDeliverFailure(
        error: LuaExecutionError,
        ownsCancellation: Boolean = false,
    ) {
        if (claimTerminal(ownsCancellation)) {
            deliver { it.onFailed(error) }
        } else if (!ownsCancellation) {
            convergePendingStop()
        }
    }

    private fun claimAndDeliverCancellation() {
        if (claimTerminal(ownsCancellation = true)) {
            deliver { it.onCancelled(cancellation(LuaCancellationReason.REQUESTED)) }
        }
    }

    private fun claimTerminal(ownsCancellation: Boolean = false): Boolean = synchronized(lock) {
        if (terminalClaimed || closeRequested || !observerAvailable || (cancelRequested && !ownsCancellation)) {
            false
        } else {
            terminalClaimed = true
            true
        }
    }

    private fun isCancellationRequested(): Boolean = synchronized(lock) {
        cancelRequested || closeRequested || !observerAvailable
    }

    private fun convergePendingStop(): Boolean {
        var failure: LuaExecutionError? = null
        var cancellationRequested = false
        val suppressed = synchronized(lock) {
            when {
                closeRequested || !observerAvailable || terminalClaimed -> true
                cancelRequested -> {
                    failure = pendingFailure
                    cancellationRequested = failure == null
                    false
                }
                else -> false
            }
        }
        if (suppressed) return true
        failure?.let {
            claimAndDeliverFailure(it, ownsCancellation = true)
            return true
        }
        if (cancellationRequested) {
            claimAndDeliverCancellation()
            return true
        }
        return false
    }

    private fun abandonObserver(): Boolean {
        var finishWithoutWorker = false
        val changed = synchronized(lock) {
            if (closeRequested && !observerAvailable) {
                false
            } else {
                closeRequested = true
                cancelRequested = true
                observerAvailable = false
                terminalClaimed = true
                if (phase == Phase.CREATED) {
                    phase = Phase.FINISHED
                    finishWithoutWorker = true
                }
                true
            }
        }
        if (!changed) return false
        watchdog.stopRequestedQuietly()
        source.close()
        callbackGate.close()
        if (finishWithoutWorker) finish()
        return true
    }

    private fun handleDispatchUnavailable(
        code: LuaExecutionErrorCode,
        message: String,
        retryDisposition: LuaRetryDisposition = LuaRetryDisposition.DO_NOT_RETRY,
    ) {
        var terminal: (() -> Unit)? = null
        synchronized(lock) {
            if (phase == Phase.FINISHED) return
            phase = Phase.FINISHED
            if (!closeRequested && observerAvailable && !terminalClaimed) {
                terminalClaimed = true
                terminal = if (cancelRequested) {
                    { deliver { it.onCancelled(cancellation(LuaCancellationReason.REQUESTED)) } }
                } else {
                    val failure = executionError(
                        code,
                        LuaExecutionFailurePhase.QUEUE,
                        message,
                        retryDisposition,
                    )
                    ({ deliver { it.onFailed(failure) } })
                }
            }
        }
        source.close()
        terminal?.invoke()
        finish()
    }

    private fun deliver(block: (LuaExecutionObserver) -> Unit): Boolean {
        val allowed = synchronized(lock) { observerAvailable && !closeRequested }
        if (!allowed) return false
        return try {
            callbackGate.deliver { block(observer) }
        } catch (_: Throwable) {
            observerDied()
            false
        }
    }

    private fun finish() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        watchdog.closeQuietly()
        val leaseToCancel = synchronized(lock) {
            phase = Phase.FINISHED
            startLease.also { startLease = null }
        }
        leaseToCancel?.closeQuietly()
        source.close()
        callbackGate.close()
        runCatching { onFinished() }
    }

    private fun sourceFailure(error: LuaSourceException): LuaExecutionError = when (error.kind) {
        LuaSourceFailureKind.DIGEST_MISMATCH -> executionError(
            LuaExecutionErrorCode.SOURCE_DIGEST_MISMATCH,
            LuaExecutionFailurePhase.SOURCE_VALIDATION,
            "Lua source digest mismatch",
        )
        LuaSourceFailureKind.INVALID_UTF8 -> executionError(
            LuaExecutionErrorCode.INVALID_REQUEST,
            LuaExecutionFailurePhase.SOURCE_VALIDATION,
            "Lua source is not valid UTF-8 text",
        )
        LuaSourceFailureKind.EARLY_EOF,
        LuaSourceFailureKind.TRAILING_BYTES,
        LuaSourceFailureKind.DESCRIPTOR_IO,
        -> executionError(
            LuaExecutionErrorCode.DESCRIPTOR_FAILED,
            LuaExecutionFailurePhase.SOURCE_VALIDATION,
            "Lua source descriptor did not match its declared extent",
        )
    }

    private fun runnerFailure(kind: LuaRunnerFailureKind): LuaExecutionError = when (kind) {
        LuaRunnerFailureKind.SYNTAX -> executionError(
            LuaExecutionErrorCode.SYNTAX_ERROR,
            LuaExecutionFailurePhase.LOAD,
            "Lua source contains a syntax error",
        )
        LuaRunnerFailureKind.RUNTIME -> executionError(
            LuaExecutionErrorCode.RUNTIME_ERROR,
            LuaExecutionFailurePhase.EXECUTION,
            "Lua execution failed",
        )
        LuaRunnerFailureKind.MEMORY_LIMIT -> executionError(
            LuaExecutionErrorCode.MEMORY_LIMIT,
            LuaExecutionFailurePhase.EXECUTION,
            "Lua execution exceeded its memory limit",
        )
        LuaRunnerFailureKind.DEADLINE_EXCEEDED -> executionError(
            LuaExecutionErrorCode.TIMEOUT,
            LuaExecutionFailurePhase.EXECUTION,
            "Lua execution exceeded its deadline",
        )
        LuaRunnerFailureKind.UNSUPPORTED_ARGUMENTS -> executionError(
            LuaExecutionErrorCode.INTERNAL,
            LuaExecutionFailurePhase.LOAD,
            "The Lua execution adapter does not yet implement argument binding",
        )
        LuaRunnerFailureKind.UNSUPPORTED_RESULT -> executionError(
            LuaExecutionErrorCode.RESULT_LIMIT,
            LuaExecutionFailurePhase.EXECUTION,
            "Lua returned a value outside the V1 value model",
        )
        LuaRunnerFailureKind.RESULT_LIMIT -> executionError(
            LuaExecutionErrorCode.RESULT_LIMIT,
            LuaExecutionFailurePhase.EXECUTION,
            "Lua result exceeded the V1 result limit",
        )
        LuaRunnerFailureKind.CANCELLED -> executionError(
            LuaExecutionErrorCode.INTERNAL,
            LuaExecutionFailurePhase.CLEANUP,
            "Lua cancellation did not have an owning request",
        )
        LuaRunnerFailureKind.INTERNAL -> executionError(
            LuaExecutionErrorCode.INTERNAL,
            LuaExecutionFailurePhase.EXECUTION,
            "The Lua execution runner failed internally",
        )
    }

    private fun timeoutFailure(phase: LuaExecutionFailurePhase): LuaExecutionError = executionError(
        LuaExecutionErrorCode.TIMEOUT,
        phase,
        "Lua execution exhausted its end-to-end deadline",
    )

    private fun protocolFailure(message: String): LuaExecutionError = executionError(
        LuaExecutionErrorCode.PROTOCOL_VIOLATION,
        LuaExecutionFailurePhase.EXECUTION,
        message,
    )

    private fun executionError(
        code: LuaExecutionErrorCode,
        phase: LuaExecutionFailurePhase,
        message: String,
        retryDisposition: LuaRetryDisposition = LuaRetryDisposition.DO_NOT_RETRY,
    ): LuaExecutionError = LuaExecutionError(
        requestId = request.requestId,
        code = code,
        phase = phase,
        message = message,
        retryDisposition = retryDisposition,
    )

    private fun cancellation(reason: LuaCancellationReason): LuaExecutionCancellation =
        LuaExecutionCancellation(request.requestId, reason, elapsedMillis())

    private fun elapsedMillis(): Long = ((clock.nanoTime() - createdNanos).coerceAtLeast(0L) / NANOS_PER_MILLI)

    private fun remainingTimeoutMillis(): Long {
        val elapsedNanos = (clock.nanoTime() - createdNanos).coerceAtLeast(0L)
        val remainingNanos = request.timeoutMillis * NANOS_PER_MILLI - elapsedNanos
        return if (remainingNanos < NANOS_PER_MILLI) 0L else remainingNanos / NANOS_PER_MILLI
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

private fun LuaStartLease.closeQuietly() {
    runCatching { close() }
}

private fun LuaExecutionWatchdogLease.stopRequestedQuietly() {
    runCatching { stopRequested() }
}

private fun LuaExecutionWatchdogLease.closeQuietly() {
    runCatching { close() }
}
