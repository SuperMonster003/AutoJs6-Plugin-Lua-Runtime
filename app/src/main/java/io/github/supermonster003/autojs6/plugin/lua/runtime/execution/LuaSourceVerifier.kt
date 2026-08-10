package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import org.autojs.plugin.lua.runtime.api.LuaExecutionRequest
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaSha256
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal enum class LuaSourceFailureKind {
    EARLY_EOF,
    TRAILING_BYTES,
    DIGEST_MISMATCH,
    INVALID_UTF8,
    DESCRIPTOR_IO,
}

internal class LuaSourceException(
    val kind: LuaSourceFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Reads one bounded source exactly once and admits strict UTF-8 text only. */
internal object LuaSourceVerifier {
    fun read(input: InputStream, request: LuaExecutionRequest): ByteArray {
        val declaredLength = request.sourceLengthBytes
        require(declaredLength in 0L..LuaRuntimeContract.MAX_SOURCE_BYTES) {
            "Lua source length is outside the protocol bound"
        }
        require(declaredLength <= Int.MAX_VALUE.toLong()) {
            "Lua source length cannot be represented by this provider"
        }
        val source = ByteArray(declaredLength.toInt())
        try {
            var offset = 0
            while (offset < source.size) {
                val count = input.read(source, offset, source.size - offset)
                if (count <= 0) {
                    throw LuaSourceException(
                        LuaSourceFailureKind.EARLY_EOF,
                        "Lua source ended before its declared length",
                    )
                }
                offset += count
            }
            if (input.read() != -1) {
                throw LuaSourceException(
                    LuaSourceFailureKind.TRAILING_BYTES,
                    "Lua source contains bytes after its declared length",
                )
            }
        } catch (error: LuaSourceException) {
            throw error
        } catch (error: Exception) {
            throw LuaSourceException(
                LuaSourceFailureKind.DESCRIPTOR_IO,
                "Lua source descriptor could not be read",
                error,
            )
        }
        if (LuaSha256.digest(source) != request.sourceSha256) {
            throw LuaSourceException(
                LuaSourceFailureKind.DIGEST_MISMATCH,
                "Lua source digest differs from its request metadata",
            )
        }
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source))
        } catch (error: CharacterCodingException) {
            throw LuaSourceException(
                LuaSourceFailureKind.INVALID_UTF8,
                "Lua source is not strict UTF-8 text",
                error,
            )
        }
        return source
    }
}

internal interface LuaExecutionSource : AutoCloseable {
    @Throws(LuaSourceException::class)
    fun readVerified(request: LuaExecutionRequest): ByteArray

    override fun close()
}
