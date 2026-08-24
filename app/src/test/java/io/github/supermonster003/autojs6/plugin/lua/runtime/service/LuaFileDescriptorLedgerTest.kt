package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import org.autojs.plugin.lua.runtime.api.LuaExecutionRequest
import org.autojs.plugin.lua.runtime.api.LuaProtocolVersion
import org.autojs.plugin.lua.runtime.api.LuaRequestId
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaSha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class LuaFileDescriptorLedgerTest {
    @Test
    fun createFailureBalancesIncomingAndDuplicatedSourceExactlyOnce() {
        val ledger = LuaFileDescriptorLedger()
        val incoming = ledger.acquire(LuaFileDescriptorKind.INCOMING_SOURCE)
        val descriptorCloses = AtomicInteger()
        val source = source(ledger, descriptorCloses)

        try {
            error("create failed after source duplication")
        } catch (_: IllegalStateException) {
            source.close()
        } finally {
            incoming.close()
        }
        source.close()
        incoming.close()

        assertEquals(1, descriptorCloses.get())
        assertBalance(ledger, LuaFileDescriptorKind.INCOMING_SOURCE, acquired = 1L, released = 1L)
        assertBalance(ledger, LuaFileDescriptorKind.DUPLICATED_SOURCE, acquired = 1L, released = 1L)
        assertTrue(ledger.snapshot().isBalanced)
    }

    @Test
    fun busyRejectionBalancesIncomingSourceWithoutCreatingADuplicate() {
        val ledger = LuaFileDescriptorLedger()
        val incoming = ledger.acquire(LuaFileDescriptorKind.INCOMING_SOURCE)

        incoming.close()

        assertBalance(ledger, LuaFileDescriptorKind.INCOMING_SOURCE, acquired = 1L, released = 1L)
        assertBalance(ledger, LuaFileDescriptorKind.DUPLICATED_SOURCE, acquired = 0L, released = 0L)
        assertTrue(ledger.snapshot().isBalanced)
    }

    @Test
    fun sourceReadAndControllerFinishStyleDoubleCloseReleaseOneLease() {
        val ledger = LuaFileDescriptorLedger()
        val descriptorCloses = AtomicInteger()
        val streamCloses = AtomicInteger()
        val source = LuaParcelFileExecutionSource(
            openInput = {
                object : ByteArrayInputStream(SOURCE) {
                    override fun close() {
                        streamCloses.incrementAndGet()
                        super.close()
                    }
                }
            },
            closeDescriptor = { descriptorCloses.incrementAndGet() },
            ownership = ledger.acquire(LuaFileDescriptorKind.DUPLICATED_SOURCE),
        )

        assertEquals(SOURCE.toList(), source.readVerified(REQUEST).toList())
        source.close()
        source.close()

        assertEquals(1, streamCloses.get())
        assertEquals(1, descriptorCloses.get())
        assertBalance(ledger, LuaFileDescriptorKind.DUPLICATED_SOURCE, acquired = 1L, released = 1L)
        assertTrue(ledger.snapshot().isBalanced)
    }

    @Test
    fun hostPayloadsBalanceWhileV1ScalarResultOwnsNoDescriptors() {
        val ledger = LuaFileDescriptorLedger()
        val first = ledger.acquire(LuaFileDescriptorKind.HOST_CALLBACK_PAYLOAD)
        val second = ledger.acquire(LuaFileDescriptorKind.HOST_CALLBACK_PAYLOAD)

        first.close()
        second.close()
        first.close()

        assertBalance(ledger, LuaFileDescriptorKind.HOST_CALLBACK_PAYLOAD, acquired = 2L, released = 2L)
        assertBalance(ledger, LuaFileDescriptorKind.RESULT_CALLBACK_PAYLOAD, acquired = 0L, released = 0L)
        assertEquals(2L, ledger.snapshot().acquired)
        assertEquals(2L, ledger.snapshot().released)
        assertEquals(0L, ledger.snapshot().outstanding)
        assertTrue(ledger.snapshot().isBalanced)
    }

    private fun source(
        ledger: LuaFileDescriptorLedger,
        descriptorCloses: AtomicInteger,
    ) = LuaParcelFileExecutionSource(
        openInput = { ByteArrayInputStream(SOURCE) },
        closeDescriptor = { descriptorCloses.incrementAndGet() },
        ownership = ledger.acquire(LuaFileDescriptorKind.DUPLICATED_SOURCE),
    )

    private fun assertBalance(
        ledger: LuaFileDescriptorLedger,
        kind: LuaFileDescriptorKind,
        acquired: Long,
        released: Long,
    ) {
        val balance = ledger.snapshot().balance(kind)
        assertEquals(acquired, balance.acquired)
        assertEquals(released, balance.released)
        assertEquals(acquired - released, balance.outstanding)
        assertFalse(balance.outstanding < 0L)
    }

    private companion object {
        val SOURCE = "return 1".toByteArray(Charsets.UTF_8)
        val REQUEST = LuaExecutionRequest(
            requestId = LuaRequestId.fromUuid(
                UUID.fromString("00000000-0000-0000-0000-0000000000fd"),
            ),
            protocolVersion = LuaProtocolVersion(
                LuaRuntimeContract.PROTOCOL_MAJOR,
                LuaRuntimeContract.PROTOCOL_MINOR,
            ),
            sourceName = "ledger.lua",
            sourceLengthBytes = SOURCE.size.toLong(),
            sourceSha256 = LuaSha256.digest(SOURCE),
        )
    }
}
