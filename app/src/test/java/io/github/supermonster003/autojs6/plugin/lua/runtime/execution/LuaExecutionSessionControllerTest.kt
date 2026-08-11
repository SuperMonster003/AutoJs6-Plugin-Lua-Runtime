package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.autojs.plugin.lua.runtime.api.LuaExecutionCancellation
import org.autojs.plugin.lua.runtime.api.LuaExecutionError
import org.autojs.plugin.lua.runtime.api.LuaExecutionErrorCode
import org.autojs.plugin.lua.runtime.api.LuaExecutionFailurePhase
import org.autojs.plugin.lua.runtime.api.LuaExecutionRequest
import org.autojs.plugin.lua.runtime.api.LuaExecutionResult
import org.autojs.plugin.lua.runtime.api.LuaExecutionStarted
import org.autojs.plugin.lua.runtime.api.LuaOutputChunk
import org.autojs.plugin.lua.runtime.api.LuaOutputStream
import org.autojs.plugin.lua.runtime.api.LuaProtocolVersion
import org.autojs.plugin.lua.runtime.api.LuaRequestId
import org.autojs.plugin.lua.runtime.api.LuaRetryDisposition
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaRuntimeFamily
import org.autojs.plugin.lua.runtime.api.LuaRuntimeInfo
import org.autojs.plugin.lua.runtime.api.LuaRuntimeLimits
import org.autojs.plugin.lua.runtime.api.LuaSha256
import org.autojs.plugin.lua.runtime.api.LuaValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LuaExecutionSessionControllerTest {
    @Test
    fun startIsIdempotentAndCompletionIsUnique() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val source = FakeSource()
        val runnerCalls = AtomicInteger()
        val finished = AtomicInteger()
        val watchdog = RecordingWatchdogLease()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            source = source,
            runner = LuaExecutionRunner {
                runnerCalls.incrementAndGet()
                LuaValue.StringValue("ok")
            },
            watchdog = watchdog,
            onFinished = { finished.incrementAndGet() },
        )

        assertTrue(controller.start())
        assertFalse(controller.start())
        dispatcher.runAccepted()

        assertEquals(1, runnerCalls.get())
        assertEquals(listOf("started", "completed"), observer.events)
        assertEquals(1, finished.get())
        assertTrue(source.closed)
        assertTrue(observer.closed)
        assertTrue(controller.snapshot().finished)
        assertEquals(1, watchdog.dispatched.get())
        assertEquals(0, watchdog.stops.get())
        assertEquals(1, watchdog.closes.get())
    }

    @Test
    fun cancellationOwnsLateRunnerResult() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val runnerEntered = CountDownLatch(1)
        val releaseRunner = CountDownLatch(1)
        val finished = AtomicInteger()
        val watchdog = RecordingWatchdogLease()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner {
                runnerEntered.countDown()
                releaseRunner.await(2, TimeUnit.SECONDS)
                LuaValue.StringValue("late")
            },
            watchdog = watchdog,
            onFinished = { finished.incrementAndGet() },
        )

        assertTrue(controller.start())
        val worker = Thread(dispatcher::runAccepted)
        worker.start()
        assertTrue(runnerEntered.await(2, TimeUnit.SECONDS))
        assertTrue(controller.cancel())
        assertFalse(controller.cancel())
        assertEquals(listOf("started"), observer.events)
        assertEquals(1, watchdog.stops.get())
        assertEquals(0, watchdog.closes.get())
        releaseRunner.countDown()
        worker.join(2_000L)

        assertEquals(listOf("started", "cancelled"), observer.events)
        assertEquals(1, finished.get())
        assertTrue(controller.snapshot().terminalClaimed)
        assertEquals(1, watchdog.closes.get())
    }

    @Test
    fun closeBeforeWorkerRunSuppressesEveryCallbackAndReleasesAfterDrain() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val runnerCalls = AtomicInteger()
        val finished = AtomicInteger()
        val watchdog = RecordingWatchdogLease()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner {
                runnerCalls.incrementAndGet()
                LuaValue.Nil
            },
            watchdog = watchdog,
            onFinished = { finished.incrementAndGet() },
        )

        assertTrue(controller.start())
        assertTrue(controller.close())
        assertFalse(controller.close())
        assertEquals(0, finished.get())
        assertEquals(1, watchdog.stops.get())
        assertEquals(0, watchdog.closes.get())
        dispatcher.runAccepted()

        assertEquals(0, runnerCalls.get())
        assertTrue(observer.events.isEmpty())
        assertEquals(1, finished.get())
        assertEquals(1, watchdog.closes.get())
    }

    @Test
    fun outputConsumesOneBoundedCredit() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner { request ->
                assertTrue(request.outputEmitter.emit(LuaOutputStream.STDOUT, "hello"))
                assertFalse(request.outputEmitter.emit(LuaOutputStream.STDOUT, "without-credit"))
                LuaValue.StringValue("late-result")
            },
        )

        assertTrue(controller.grantOutputCredits(1))
        assertTrue(controller.start())
        dispatcher.runAccepted()

        assertEquals(listOf("started", "output", "failed"), observer.events)
        assertEquals(0, controller.snapshot().outstandingOutputCredits)
        assertEquals(5L, controller.snapshot().emittedOutputBytes)
    }

    @Test
    fun invalidCreditBeforeStartFailsWithoutDispatch() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val runnerCalls = AtomicInteger()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner {
                runnerCalls.incrementAndGet()
                LuaValue.Nil
            },
        )

        assertFalse(controller.grantOutputCredits(0))
        assertTrue(controller.start())

        assertFalse(dispatcher.hasAcceptedTask())
        assertEquals(0, runnerCalls.get())
        assertEquals(listOf("failed"), observer.events)
        assertEquals(LuaExecutionErrorCode.PROTOCOL_VIOLATION, observer.lastError?.code)
    }

    @Test
    fun unexpectedDispatcherFailureTerminatesAndCleansTheSession() {
        val observer = RecordingObserver()
        val source = FakeSource()
        val finished = AtomicInteger()
        val controller = controller(
            dispatcher = LuaExecutionDispatcher { error("dispatcher failed") },
            observer = observer,
            source = source,
            onFinished = { finished.incrementAndGet() },
        )

        assertTrue(controller.start())

        assertEquals(listOf("failed"), observer.events)
        assertEquals(LuaExecutionErrorCode.INTERNAL, observer.lastError?.code)
        assertTrue(source.closed)
        assertEquals(1, finished.get())
    }

    @Test
    fun watchdogControlFailureRejectsBeforeWorkerDispatch() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val watchdog = RecordingWatchdogLease(armSucceeds = false)
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            watchdog = watchdog,
        )

        assertTrue(controller.start())

        assertFalse(dispatcher.hasAcceptedTask())
        assertEquals(listOf("failed"), observer.events)
        assertEquals(LuaExecutionErrorCode.INTERNAL, observer.lastError?.code)
        assertEquals(1, watchdog.dispatched.get())
        assertEquals(1, watchdog.closes.get())
    }

    @Test
    fun unstartedSessionLeaseExpiresWithoutManufacturingCallback() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val source = FakeSource()
        val finished = AtomicInteger()
        val leaseCloses = AtomicInteger()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            source = source,
            onFinished = { finished.incrementAndGet() },
        )

        assertTrue(controller.armStartLease(LuaStartLease {
            leaseCloses.incrementAndGet()
            Unit
        }))
        assertTrue(controller.expireIfNotStarted())
        assertFalse(controller.expireIfNotStarted())

        assertTrue(observer.events.isEmpty())
        assertTrue(source.closed)
        assertTrue(observer.closed)
        assertEquals(1, leaseCloses.get())
        assertEquals(1, finished.get())
        assertFalse(controller.start())
    }

    @Test
    fun startCancelsItsCreationLeaseExactlyOnce() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val leaseCloses = AtomicInteger()
        val controller = controller(dispatcher = dispatcher, observer = observer)

        assertTrue(controller.armStartLease(LuaStartLease {
            leaseCloses.incrementAndGet()
            Unit
        }))
        assertTrue(controller.start())
        assertFalse(controller.armStartLease(LuaStartLease {
            leaseCloses.incrementAndGet()
            Unit
        }))
        dispatcher.runAccepted()

        assertEquals(2, leaseCloses.get())
        assertEquals(listOf("started", "completed"), observer.events)
    }

    @Test
    fun observerFailureBeforeRunnerContainsLateWork() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver(failOnStarted = true)
        val runnerCalls = AtomicInteger()
        val finished = AtomicInteger()
        val watchdog = RecordingWatchdogLease()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner {
                runnerCalls.incrementAndGet()
                LuaValue.Nil
            },
            watchdog = watchdog,
            onFinished = { finished.incrementAndGet() },
        )

        assertTrue(controller.start())
        dispatcher.runAccepted()

        assertEquals(0, runnerCalls.get())
        assertEquals(listOf("started"), observer.events)
        assertFalse(controller.snapshot().observerAvailable)
        assertEquals(1, finished.get())
    }

    @Test
    fun closeDoesNotWaitOnInFlightCallbackAndSuppressesRunner() {
        val dispatcher = ManualDispatcher()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closeChangedState = AtomicBoolean(false)
        val observer = RecordingObserver(
            onStartedAction = {
                callbackEntered.countDown()
                releaseCallback.await(2, TimeUnit.SECONDS)
            },
        )
        val runnerCalls = AtomicInteger()
        val finished = AtomicInteger()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner {
                runnerCalls.incrementAndGet()
                LuaValue.Nil
            },
            onFinished = { finished.incrementAndGet() },
        )

        assertTrue(controller.start())
        val worker = Thread(dispatcher::runAccepted)
        worker.start()
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
        val closeThread = Thread {
            closeChangedState.set(controller.close())
            closeReturned.countDown()
        }
        closeThread.start()
        val closeWasNonBlocking = closeReturned.await(2, TimeUnit.SECONDS)
        releaseCallback.countDown()
        closeThread.join(2_000L)
        worker.join(2_000L)

        assertTrue(closeWasNonBlocking)
        assertTrue(closeChangedState.get())
        assertFalse(closeThread.isAlive)
        assertFalse(worker.isAlive)
        assertEquals(0, runnerCalls.get())
        assertEquals(listOf("started"), observer.events)
        assertTrue(observer.closed)
        assertEquals(1, finished.get())
    }

    @Test
    fun callbackDeathBeforeWorkerRunSuppressesLateCallbacks() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val runnerCalls = AtomicInteger()
        val finished = AtomicInteger()
        val watchdog = RecordingWatchdogLease()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner {
                runnerCalls.incrementAndGet()
                LuaValue.Nil
            },
            watchdog = watchdog,
            onFinished = { finished.incrementAndGet() },
        )

        assertTrue(controller.start())
        assertTrue(controller.observerDied())
        assertFalse(controller.observerDied())
        assertEquals(1, watchdog.stops.get())
        assertEquals(0, watchdog.closes.get())
        dispatcher.runAccepted()

        assertEquals(0, runnerCalls.get())
        assertTrue(observer.events.isEmpty())
        assertEquals(1, finished.get())
        assertFalse(controller.snapshot().observerAvailable)
        assertEquals(1, watchdog.closes.get())
    }

    @Test
    fun deadlineSpentBeforeWorkerRunIsNotGrantedAgainToRunner() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val runnerCalls = AtomicInteger()
        val now = AtomicLong(0L)
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner {
                runnerCalls.incrementAndGet()
                LuaValue.Nil
            },
            clock = LuaMonotonicClock(now::get),
        )

        assertTrue(controller.start())
        now.set(REQUEST.timeoutMillis * 1_000_000L)
        dispatcher.runAccepted()

        assertEquals(0, runnerCalls.get())
        assertEquals(listOf("failed"), observer.events)
        assertEquals(LuaExecutionErrorCode.TIMEOUT, observer.lastError?.code)
    }

    @Test
    fun runnerReceivesOnlyRemainingEndToEndBudget() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val now = AtomicLong(0L)
        val runnerBudget = AtomicLong(-1L)
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner { runnerRequest ->
                runnerBudget.set(runnerRequest.timeoutMillis)
                LuaValue.Nil
            },
            clock = LuaMonotonicClock(now::get),
        )

        assertTrue(controller.start())
        now.set(10L * 1_000_000L)
        dispatcher.runAccepted()

        assertEquals(REQUEST.timeoutMillis - 10L, runnerBudget.get())
        assertEquals(listOf("started", "completed"), observer.events)
    }

    @Test
    fun preRejectedBusySessionFailsOnlyAfterStart() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val failure = LuaExecutionError(
            requestId = REQUEST_ID,
            code = LuaExecutionErrorCode.BUSY,
            phase = LuaExecutionFailurePhase.QUEUE,
            message = "busy",
            retryDisposition = LuaRetryDisposition.NEW_REQUEST_MAY_SUCCEED,
        )
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            initialFailure = failure,
        )

        assertTrue(observer.events.isEmpty())
        assertTrue(controller.start())
        assertFalse(dispatcher.hasAcceptedTask())
        assertEquals(listOf("failed"), observer.events)
        assertEquals(LuaExecutionErrorCode.BUSY, observer.lastError?.code)
    }

    @Test
    fun hostCapabilityRunnerFailureKeepsItsExecutionCodeAndPhase() {
        val dispatcher = ManualDispatcher()
        val observer = RecordingObserver()
        val controller = controller(
            dispatcher = dispatcher,
            observer = observer,
            runner = LuaExecutionRunner {
                throw LuaRunnerException(
                    LuaRunnerFailureKind.HOST_CAPABILITY,
                    "sanitized host failure",
                )
            },
        )

        assertTrue(controller.start())
        dispatcher.runAccepted()

        assertEquals(listOf("started", "failed"), observer.events)
        assertEquals(LuaExecutionErrorCode.HOST_CAPABILITY_FAILED, observer.lastError?.code)
        assertEquals(LuaExecutionFailurePhase.HOST_CALL, observer.lastError?.phase)
    }

    private fun controller(
        dispatcher: LuaExecutionDispatcher,
        observer: RecordingObserver,
        source: FakeSource = FakeSource(),
        runner: LuaExecutionRunner = LuaExecutionRunner { LuaValue.Nil },
        initialFailure: LuaExecutionError? = null,
        clock: LuaMonotonicClock = LuaMonotonicClock { 1_000_000L },
        watchdog: LuaExecutionWatchdogLease = RecordingWatchdogLease(),
        onFinished: () -> Unit = {},
    ): LuaExecutionSessionController = LuaExecutionSessionController(
        request = REQUEST,
        runtimeInfo = RUNTIME_INFO,
        source = source,
        runner = runner,
        dispatcher = dispatcher,
        watchdog = watchdog,
        observer = observer,
        initialFailure = initialFailure,
        clock = clock,
        onFinished = onFinished,
    )

    private class ManualDispatcher : LuaExecutionDispatcher {
        private var accepted: Runnable? = null

        override fun dispatch(task: Runnable): Boolean {
            if (accepted != null) return false
            accepted = task
            return true
        }

        fun runAccepted() {
            val task = checkNotNull(accepted) { "No task was accepted" }
            accepted = null
            task.run()
        }

        fun hasAcceptedTask(): Boolean = accepted != null
    }

    private class FakeSource : LuaExecutionSource {
        var closed = false
            private set

        override fun readVerified(request: LuaExecutionRequest): ByteArray = "return 1".toByteArray()

        override fun close() {
            closed = true
        }
    }

    private class RecordingWatchdogLease(
        private val armSucceeds: Boolean = true,
    ) : LuaExecutionWatchdogLease {
        val dispatched = AtomicInteger()
        val stops = AtomicInteger()
        val closes = AtomicInteger()

        override fun executionDispatched(): Boolean {
            dispatched.incrementAndGet()
            return armSucceeds
        }

        override fun stopRequested() {
            stops.incrementAndGet()
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    private class RecordingObserver(
        private val failOnStarted: Boolean = false,
        private val onStartedAction: () -> Unit = {},
    ) : LuaExecutionObserver {
        val events = mutableListOf<String>()
        var lastError: LuaExecutionError? = null
            private set
        var closed = false
            private set

        override fun onStarted(started: LuaExecutionStarted) {
            events += "started"
            onStartedAction()
            if (failOnStarted) error("observer died")
        }

        override fun onOutput(output: LuaOutputChunk) {
            events += "output"
        }

        override fun onCompleted(result: LuaExecutionResult) {
            events += "completed"
        }

        override fun onFailed(error: LuaExecutionError) {
            events += "failed"
            lastError = error
        }

        override fun onCancelled(cancellation: LuaExecutionCancellation) {
            events += "cancelled"
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val REQUEST_ID = LuaRequestId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val PROTOCOL = LuaProtocolVersion(
            LuaRuntimeContract.PROTOCOL_MAJOR,
            LuaRuntimeContract.PROTOCOL_MINOR,
        )
        val SOURCE = "return 1".toByteArray()
        val REQUEST = LuaExecutionRequest(
            requestId = REQUEST_ID,
            protocolVersion = PROTOCOL,
            sourceName = "source.lua",
            sourceLengthBytes = SOURCE.size.toLong(),
            sourceSha256 = LuaSha256.digest(SOURCE),
        )
        val RUNTIME_INFO = LuaRuntimeInfo(
            protocolMin = PROTOCOL,
            protocolMax = PROTOCOL,
            providerId = "test-lua-runtime",
            providerVersionName = "1.0.0",
            providerVersionCode = 1L,
            runtimeFamily = LuaRuntimeFamily.PUC_LUA,
            runtimeSlot = LuaRuntimeContract.RUNTIME_SLOT_LUA54,
            languageVersion = "5.4.8",
            processAbi = "x86_64",
            supportedAbis = listOf("x86_64"),
            capabilities = emptyList(),
            limits = LuaRuntimeLimits(
                maxSourceBytes = LuaRuntimeContract.MAX_SOURCE_BYTES,
                maxMemoryBytes = LuaRuntimeContract.MAX_MEMORY_BYTES,
                maxOutputBytes = LuaRuntimeContract.MAX_OUTPUT_BYTES,
                maxExecutionMillis = LuaRuntimeContract.MAX_TIMEOUT_MILLIS,
                maxConcurrentExecutions = 1,
            ),
        )
    }
}
