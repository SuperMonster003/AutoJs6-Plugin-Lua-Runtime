package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaCancellationProbe
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaHostCapabilityException
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaHostCapabilityFailureKind
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityBroker
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityCallback
import org.autojs.plugin.lua.runtime.api.LuaHostCallResult
import org.autojs.plugin.lua.runtime.api.LuaRequestId
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import org.autojs.plugin.lua.runtime.api.LuaValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class BinderLuaHostCapabilityInvokerInstrumentationTest {
    @Test
    fun completedCallIsPinnedToExecutionCallIdAndOwnerUid() {
        val executionId = requestId(1)
        var verifiedUid: Int? = null
        val broker = object : ILuaHostCapabilityBroker.Stub() {
            override fun invoke(
                requestMetadata: ByteArray?,
                payloads: Array<out ParcelFileDescriptor?>?,
                callback: ILuaHostCapabilityCallback?,
            ) {
                assertTrue(payloads.orEmpty().isEmpty())
                val request = LuaRuntimeCodec.decodeHostRequest(checkNotNull(requestMetadata))
                checkNotNull(callback).onCompleted(
                    LuaRuntimeCodec.encodeHostResult(
                        LuaHostCallResult(request.executionId, request.callId, LuaValue.Int64Value(36L)),
                    ),
                    emptyArray(),
                )
            }
        }
        val invoker = BinderLuaHostCapabilityInvoker(
            broker = broker,
            executionId = executionId,
            allowedCapabilities = listOf("device.info"),
            ownerUid = Process.myUid(),
            callerVerifier = LuaSessionCallerVerifier { expected ->
                assertEquals(Process.myUid(), expected)
                verifiedUid = expected
            },
        )

        assertEquals(
            LuaValue.Int64Value(36L),
            invoker.invoke(
                "device.info",
                LuaValue.MapValue(emptyMap()),
                1_000L,
                LuaCancellationProbe { false },
            ),
        )
        assertEquals(Process.myUid(), verifiedUid)
        invoker.close()
    }

    @Test
    fun ungrantedCapabilityFailsBeforeBinderDispatch() {
        val dispatched = AtomicBoolean(false)
        val broker = object : ILuaHostCapabilityBroker.Stub() {
            override fun invoke(
                requestMetadata: ByteArray?,
                payloads: Array<out ParcelFileDescriptor?>?,
                callback: ILuaHostCapabilityCallback?,
            ) {
                dispatched.set(true)
            }
        }
        val invoker = BinderLuaHostCapabilityInvoker(
            broker,
            requestId(2),
            emptyList(),
            Process.myUid(),
            LuaSessionCallerVerifier { },
        )

        val failure = assertThrows(LuaHostCapabilityException::class.java) {
            invoker.invoke(
                "device.info",
                LuaValue.MapValue(emptyMap()),
                1_000L,
                LuaCancellationProbe { false },
            )
        }
        assertEquals(LuaHostCapabilityFailureKind.DENIED, failure.kind)
        assertFalse(dispatched.get())
        invoker.close()
    }

    @Test
    fun cancellationAfterDispatchStopsTheBoundedWaitWithoutRetry() {
        val dispatches = java.util.concurrent.atomic.AtomicInteger()
        val broker = object : ILuaHostCapabilityBroker.Stub() {
            override fun invoke(
                requestMetadata: ByteArray?,
                payloads: Array<out ParcelFileDescriptor?>?,
                callback: ILuaHostCapabilityCallback?,
            ) {
                dispatches.incrementAndGet()
            }
        }
        val invoker = BinderLuaHostCapabilityInvoker(
            broker,
            requestId(3),
            listOf("device.info"),
            Process.myUid(),
            LuaSessionCallerVerifier { },
        )

        val failure = assertThrows(LuaHostCapabilityException::class.java) {
            invoker.invoke(
                "device.info",
                LuaValue.MapValue(emptyMap()),
                1_000L,
                LuaCancellationProbe { dispatches.get() > 0 },
            )
        }
        assertEquals(LuaHostCapabilityFailureKind.CANCELLED, failure.kind)
        assertEquals(1, dispatches.get())
        invoker.close()
    }

    @Test
    fun rejectedHostPayloadIsClosedAndLogicallyBalanced() {
        val executionId = requestId(4)
        val ledger = LuaFileDescriptorLedger()
        val pipe = ParcelFileDescriptor.createPipe()
        val payload = pipe[0]
        val writer = pipe[1]
        val broker = object : ILuaHostCapabilityBroker.Stub() {
            override fun invoke(
                requestMetadata: ByteArray?,
                payloads: Array<out ParcelFileDescriptor?>?,
                callback: ILuaHostCapabilityCallback?,
            ) {
                val request = LuaRuntimeCodec.decodeHostRequest(checkNotNull(requestMetadata))
                checkNotNull(callback).onCompleted(
                    LuaRuntimeCodec.encodeHostResult(
                        LuaHostCallResult(request.executionId, request.callId, LuaValue.Nil),
                    ),
                    arrayOf(payload),
                )
            }
        }
        val invoker = BinderLuaHostCapabilityInvoker(
            broker = broker,
            executionId = executionId,
            allowedCapabilities = listOf("device.info"),
            ownerUid = Process.myUid(),
            callerVerifier = LuaSessionCallerVerifier { },
            descriptorLedger = ledger,
        )

        try {
            val failure = assertThrows(LuaHostCapabilityException::class.java) {
                invoker.invoke(
                    "device.info",
                    LuaValue.MapValue(emptyMap()),
                    1_000L,
                    LuaCancellationProbe { false },
                )
            }
            assertEquals(LuaHostCapabilityFailureKind.PROTOCOL, failure.kind)
            assertFalse(payload.fileDescriptor.valid())
            val balance = ledger.snapshot().balance(LuaFileDescriptorKind.HOST_CALLBACK_PAYLOAD)
            assertEquals(1L, balance.acquired)
            assertEquals(1L, balance.released)
            assertTrue(ledger.snapshot().isBalanced)
        } finally {
            invoker.close()
            runCatching { payload.close() }
            runCatching { writer.close() }
        }
    }

    private fun requestId(marker: Byte): LuaRequestId =
        LuaRequestId.fromBytes(ByteArray(16).also { it[15] = marker })
}
