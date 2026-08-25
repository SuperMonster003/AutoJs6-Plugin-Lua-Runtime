package io.github.supermonster003.autojs6.plugin.lua.runtime.diagnostic

import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaProcessTerminationReason
import org.autojs.plugin.lua.runtime.api.LuaSha256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaCrashDiagnosticTest {
    @Test
    fun fixedRecordRoundTripsWithoutExposingMutableHashBytes() {
        val callerPrefix = ByteArray(8) { it.toByte() }
        val diagnostic = LuaCrashDiagnostic(
            LuaCrashFailureKind.NATIVE_CRASH,
            LuaCrashPhase.NATIVE_EXECUTION,
            callerPrefix,
        )
        callerPrefix.fill(0x7f)

        val encoded = LuaCrashDiagnosticCodec.encode(diagnostic)
        assertEquals(20, encoded.size)
        assertEquals(diagnostic, LuaCrashDiagnosticCodec.decode(encoded))
        assertArrayEquals(ByteArray(8) { it.toByte() }, diagnostic.sourceSha256Prefix())

        val returnedPrefix = diagnostic.sourceSha256Prefix()
        returnedPrefix.fill(0x55)
        assertArrayEquals(ByteArray(8) { it.toByte() }, diagnostic.sourceSha256Prefix())
    }

    @Test
    fun malformedOrOversizedPrivateRecordIsRejectedAndDeleted() {
        val storage = MemoryStorage()
        val store = LuaCrashDiagnosticStore(storage)
        val valid = LuaCrashDiagnosticCodec.encode(diagnostic())

        storage.bytes = valid.copyOf().also { it[8] = (it[8].toInt() xor 0x01).toByte() }
        assertNull(store.read())
        assertEquals(1, storage.deletes)
        assertNull(storage.bytes)

        storage.bytes = valid + byteArrayOf(0)
        assertNull(store.read())
        assertEquals(2, storage.deletes)
        assertNull(storage.bytes)
        assertThrows(IllegalArgumentException::class.java) {
            LuaCrashDiagnosticCodec.decode(valid + byteArrayOf(0))
        }
    }

    @Test
    fun provisionalNativeCrashIsHiddenAndClearedAfterManagedReturn() {
        val storage = MemoryStorage()
        val coordinator = LuaCrashDiagnosticCoordinator(LuaCrashDiagnosticStore(storage))
        val token = Any()
        val lease = coordinator.acquire(token, SOURCE_SHA256)

        lease.sourceValidationStarted()
        lease.nativeExecutionStarted()
        assertEquals(diagnostic(), LuaCrashDiagnosticCodec.decode(checkNotNull(storage.bytes)))
        assertFalse(coordinator.hasReportableDiagnostic())

        lease.nativeExecutionReturned()
        lease.close()
        assertNull(storage.bytes)
        assertFalse(coordinator.hasReportableDiagnostic())
    }

    @Test
    fun watchdogCommitOverwritesProvisionalRecordAndSurvivesWorkerReturn() {
        val storage = MemoryStorage()
        val store = LuaCrashDiagnosticStore(storage)
        val coordinator = LuaCrashDiagnosticCoordinator(store)
        val token = Any()
        val lease = coordinator.acquire(token, SOURCE_SHA256)

        lease.sourceValidationStarted()
        lease.nativeExecutionStarted()
        coordinator.beforeTermination(token, LuaProcessTerminationReason.DEADLINE_CLEANUP_EXPIRED)
        lease.nativeExecutionReturned()
        lease.close()

        assertEquals(
            LuaCrashDiagnostic(
                LuaCrashFailureKind.DEADLINE_CLEANUP_EXPIRED,
                LuaCrashPhase.NATIVE_EXECUTION,
                SOURCE_PREFIX,
            ),
            store.read(),
        )
        assertTrue(coordinator.hasReportableDiagnostic())
    }

    @Test
    fun runtimeInfoFlagIsConditionalContentFreeAndNeverDuplicated() {
        val executionCapabilities = listOf("device.info", "module.snapshot.v1")
        assertEquals(executionCapabilities, withLastAbnormalTerminationFlag(executionCapabilities, false))
        assertEquals(
            executionCapabilities + LAST_ABNORMAL_TERMINATION_FLAG,
            withLastAbnormalTerminationFlag(executionCapabilities, true),
        )
        assertEquals(
            1,
            withLastAbnormalTerminationFlag(
                executionCapabilities + LAST_ABNORMAL_TERMINATION_FLAG,
                true,
            ).count { it == LAST_ABNORMAL_TERMINATION_FLAG },
        )
        assertFalse(LAST_ABNORMAL_TERMINATION_FLAG.contains(SOURCE_PREFIX.joinToString("")))
    }

    private fun diagnostic() = LuaCrashDiagnostic(
        LuaCrashFailureKind.NATIVE_CRASH,
        LuaCrashPhase.NATIVE_EXECUTION,
        SOURCE_PREFIX,
    )

    private class MemoryStorage : LuaCrashDiagnosticStorage {
        var bytes: ByteArray? = null
        var deletes = 0

        override fun read(): ByteArray? = bytes?.copyOf()

        override fun write(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            deletes += 1
            bytes = null
        }
    }

    private companion object {
        val SOURCE_BYTES = ByteArray(32) { it.toByte() }
        val SOURCE_SHA256 = LuaSha256.fromBytes(SOURCE_BYTES)
        val SOURCE_PREFIX = SOURCE_BYTES.copyOf(8)
    }
}
