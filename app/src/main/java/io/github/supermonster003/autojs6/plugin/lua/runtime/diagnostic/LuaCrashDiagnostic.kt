package io.github.supermonster003.autojs6.plugin.lua.runtime.diagnostic

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal const val LAST_ABNORMAL_TERMINATION_FLAG =
    "diagnostic.last-abnormal-termination.v1"

internal enum class LuaCrashFailureKind(val storageCode: Int) {
    NATIVE_CRASH(1),
    DEADLINE_CLEANUP_EXPIRED(2),
    STOP_CLEANUP_EXPIRED(3),
    WATCHDOG_CONTROL_FAILURE(4),
    ;

    companion object {
        fun fromStorageCode(code: Int): LuaCrashFailureKind? = entries.singleOrNull {
            it.storageCode == code
        }
    }
}

internal enum class LuaCrashPhase(val storageCode: Int) {
    QUEUE(1),
    SOURCE_VALIDATION(2),
    NATIVE_EXECUTION(3),
    ;

    companion object {
        fun fromStorageCode(code: Int): LuaCrashPhase? = entries.singleOrNull {
            it.storageCode == code
        }
    }
}

/** Fixed, content-free record retained across death of the isolated runtime process. */
internal class LuaCrashDiagnostic(
    val failureKind: LuaCrashFailureKind,
    val phase: LuaCrashPhase,
    sourceSha256Prefix: ByteArray,
) {
    private val hashPrefix = sourceSha256Prefix.copyOf()

    init {
        require(hashPrefix.size == SOURCE_HASH_PREFIX_BYTES) {
            "Lua crash source hash prefix must contain exactly $SOURCE_HASH_PREFIX_BYTES bytes"
        }
    }

    fun sourceSha256Prefix(): ByteArray = hashPrefix.copyOf()

    override fun equals(other: Any?): Boolean = other is LuaCrashDiagnostic &&
        failureKind == other.failureKind && phase == other.phase && hashPrefix.contentEquals(other.hashPrefix)

    override fun hashCode(): Int = 31 * listOf(failureKind, phase).hashCode() + hashPrefix.contentHashCode()

    override fun toString(): String =
        "LuaCrashDiagnostic(failureKind=$failureKind, phase=$phase, sourceHashPrefixBytes=${hashPrefix.size})"

    companion object {
        const val SOURCE_HASH_PREFIX_BYTES = 8
    }
}

/**
 * Canonical 20-byte private-file format:
 * magic(4), version(1), failure(1), phase(1), hashLength(1), hashPrefix(8), CRC32(4).
 */
internal object LuaCrashDiagnosticCodec {
    const val ENCODED_BYTES = 20

    fun encode(value: LuaCrashDiagnostic): ByteArray {
        val payload = ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(VERSION.toByte())
            .put(value.failureKind.storageCode.toByte())
            .put(value.phase.storageCode.toByte())
            .put(LuaCrashDiagnostic.SOURCE_HASH_PREFIX_BYTES.toByte())
            .put(value.sourceSha256Prefix())
            .array()
        return ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(payload)
            .putInt(crc32(payload).toInt())
            .array()
    }

    fun decode(bytes: ByteArray): LuaCrashDiagnostic {
        require(bytes.size == ENCODED_BYTES) { "Lua crash diagnostic size is invalid" }
        val expectedChecksum = ByteBuffer.wrap(bytes, PAYLOAD_BYTES, CHECKSUM_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int
            .toLong() and UNSIGNED_INT_MASK
        val payload = bytes.copyOfRange(0, PAYLOAD_BYTES)
        require(crc32(payload) == expectedChecksum) { "Lua crash diagnostic checksum is invalid" }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "Lua crash diagnostic magic is invalid" }
        require(buffer.get().toInt() and UNSIGNED_BYTE_MASK == VERSION) {
            "Lua crash diagnostic version is unsupported"
        }
        val failureCode = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        val phaseCode = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        require(
            buffer.get().toInt() and UNSIGNED_BYTE_MASK == LuaCrashDiagnostic.SOURCE_HASH_PREFIX_BYTES,
        ) { "Lua crash diagnostic hash prefix length is invalid" }
        val prefix = ByteArray(LuaCrashDiagnostic.SOURCE_HASH_PREFIX_BYTES).also(buffer::get)
        check(!buffer.hasRemaining()) { "Lua crash diagnostic payload has trailing bytes" }
        return LuaCrashDiagnostic(
            failureKind = requireNotNull(LuaCrashFailureKind.fromStorageCode(failureCode)) {
                "Lua crash diagnostic failure kind is unknown"
            },
            phase = requireNotNull(LuaCrashPhase.fromStorageCode(phaseCode)) {
                "Lua crash diagnostic phase is unknown"
            },
            sourceSha256Prefix = prefix,
        )
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().run {
        update(bytes)
        value
    }

    private const val MAGIC = 0x41364C44 // A6LD
    private const val VERSION = 1
    private const val PAYLOAD_BYTES = 16
    private const val CHECKSUM_BYTES = 4
    private const val UNSIGNED_BYTE_MASK = 0xff
    private const val UNSIGNED_INT_MASK = 0xffff_ffffL
}

internal interface LuaCrashDiagnosticStorage {
    fun read(): ByteArray?

    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    @Throws(IOException::class)
    fun delete()
}

internal class LuaCrashDiagnosticStore(
    private val storage: LuaCrashDiagnosticStorage,
) {
    @Synchronized
    fun read(): LuaCrashDiagnostic? {
        val bytes = try {
            storage.read()
        } catch (_: IOException) {
            return null
        } ?: return null
        return try {
            LuaCrashDiagnosticCodec.decode(bytes)
        } catch (_: IllegalArgumentException) {
            runCatching { storage.delete() }
            null
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun write(value: LuaCrashDiagnostic) {
        storage.write(LuaCrashDiagnosticCodec.encode(value))
    }

    @Synchronized
    @Throws(IOException::class)
    fun clear() {
        storage.delete()
    }

    companion object {
        fun forContext(context: Context): LuaCrashDiagnosticStore = LuaCrashDiagnosticStore(
            AtomicLuaCrashDiagnosticStorage(context.applicationContext.noBackupFilesDir),
        )
    }
}

private class AtomicLuaCrashDiagnosticStorage(root: File) : LuaCrashDiagnosticStorage {
    private val directory = File(root, DIRECTORY_NAME)
    private val atomicFile: AtomicFile

    init {
        if (directory.exists()) {
            require(directory.isDirectory) { "Lua crash diagnostic path is not a directory" }
        } else if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Lua crash diagnostic directory could not be created")
        }
        atomicFile = AtomicFile(File(directory, FILE_NAME))
    }

    override fun read(): ByteArray? {
        val input = try {
            atomicFile.openRead()
        } catch (_: FileNotFoundException) {
            return null
        }
        return input.use { stream ->
            val bounded = ByteArray(LuaCrashDiagnosticCodec.ENCODED_BYTES + 1)
            var size = 0
            while (size < bounded.size) {
                val count = stream.read(bounded, size, bounded.size - size)
                if (count < 0) break
                if (count == 0) continue
                size += count
            }
            bounded.copyOf(size)
        }
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size == LuaCrashDiagnosticCodec.ENCODED_BYTES) {
            "Lua crash diagnostic writer received an invalid record size"
        }
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    override fun delete() {
        atomicFile.delete()
        if (atomicFile.baseFile.exists()) {
            throw IOException("Lua crash diagnostic file could not be deleted")
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "lua-runtime-diagnostics"
        const val FILE_NAME = "last-abnormal-termination.v1"
    }
}
