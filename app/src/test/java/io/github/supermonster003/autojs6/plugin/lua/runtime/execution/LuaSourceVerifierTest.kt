package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.autojs.plugin.lua.runtime.api.LuaExecutionRequest
import org.autojs.plugin.lua.runtime.api.LuaProtocolVersion
import org.autojs.plugin.lua.runtime.api.LuaRequestId
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaSha256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

class LuaSourceVerifierTest {
    @Test
    fun acceptsExactStrictUtf8Source() {
        val source = "return '你好'".toByteArray(Charsets.UTF_8)

        assertArrayEquals(
            source,
            LuaSourceVerifier.read(ByteArrayInputStream(source), request(source)),
        )
    }

    @Test
    fun rejectsEarlyEof() {
        val source = "abc".toByteArray()

        val failure = expectSourceFailure {
            LuaSourceVerifier.read(
                ByteArrayInputStream(source),
                request(source, declaredLength = source.size.toLong() + 1L),
            )
        }

        assertEquals(LuaSourceFailureKind.EARLY_EOF, failure.kind)
    }

    @Test
    fun rejectsTrailingBytes() {
        val source = "abc".toByteArray()

        val failure = expectSourceFailure {
            LuaSourceVerifier.read(
                ByteArrayInputStream(source),
                request(source.copyOf(2), declaredLength = 2L),
            )
        }

        assertEquals(LuaSourceFailureKind.TRAILING_BYTES, failure.kind)
    }

    @Test
    fun rejectsDigestMismatch() {
        val source = "abc".toByteArray()
        val different = "abd".toByteArray()

        val failure = expectSourceFailure {
            LuaSourceVerifier.read(
                ByteArrayInputStream(source),
                request(source, digest = LuaSha256.digest(different)),
            )
        }

        assertEquals(LuaSourceFailureKind.DIGEST_MISMATCH, failure.kind)
    }

    @Test
    fun rejectsMalformedUtf8() {
        val source = byteArrayOf(0xc3.toByte(), 0x28)

        val failure = expectSourceFailure {
            LuaSourceVerifier.read(ByteArrayInputStream(source), request(source))
        }

        assertEquals(LuaSourceFailureKind.INVALID_UTF8, failure.kind)
    }

    private fun request(
        source: ByteArray,
        declaredLength: Long = source.size.toLong(),
        digest: LuaSha256 = LuaSha256.digest(source),
    ): LuaExecutionRequest = LuaExecutionRequest(
        requestId = LuaRequestId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        protocolVersion = LuaProtocolVersion(
            LuaRuntimeContract.PROTOCOL_MAJOR,
            LuaRuntimeContract.PROTOCOL_MINOR,
        ),
        sourceName = "source.lua",
        sourceLengthBytes = declaredLength,
        sourceSha256 = digest,
    )

    private fun expectSourceFailure(block: () -> Unit): LuaSourceException = try {
        block()
        throw AssertionError("Expected LuaSourceException")
    } catch (error: LuaSourceException) {
        error
    }
}
