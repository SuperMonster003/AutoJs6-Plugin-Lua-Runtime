package io.github.supermonster003.autojs6.plugin.lua.runtime

import org.autojs.plugin.lua.runtime.api.ILuaExecutionCallback
import org.autojs.plugin.lua.runtime.api.ILuaRuntimeProvider
import org.autojs.plugin.lua.runtime.api.LuaCancellationReason
import org.autojs.plugin.lua.runtime.api.LuaExecutionCancellation
import org.autojs.plugin.lua.runtime.api.LuaExecutionError
import org.autojs.plugin.lua.runtime.api.LuaExecutionErrorCode
import org.autojs.plugin.lua.runtime.api.LuaExecutionFailurePhase
import org.autojs.plugin.lua.runtime.api.LuaExecutionResult
import org.autojs.plugin.lua.runtime.api.LuaProtocolVersion
import org.autojs.plugin.lua.runtime.api.LuaRequestId
import org.autojs.plugin.lua.runtime.api.LuaRetryDisposition
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaRuntimeFamily
import org.autojs.plugin.lua.runtime.api.LuaRuntimeInfo
import org.autojs.plugin.lua.runtime.api.LuaRuntimeLimits
import org.autojs.plugin.lua.runtime.api.LuaValue
import org.autojs.plugin.protocol.wire.TaggedWireDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID

class LuaExecutionStatisticsProtocolBoundaryTest {
    @Test
    fun frozenProtocolRequiresHostEvolutionForExecutionStatistics() {
        assertEquals(1, LuaRuntimeContract.PROTOCOL_MAJOR)
        assertEquals(0, LuaRuntimeContract.PROTOCOL_MINOR)

        val requestId = LuaRequestId.fromUuid(UUID(0L, 1L))
        val result = LuaExecutionResult(requestId, LuaValue.Nil, 7L)
        val error = LuaExecutionError(
            requestId = requestId,
            code = LuaExecutionErrorCode.RUNTIME_ERROR,
            phase = LuaExecutionFailurePhase.EXECUTION,
            message = "protocol boundary",
            retryDisposition = LuaRetryDisposition.DO_NOT_RETRY,
        )
        val cancellation = LuaExecutionCancellation(
            requestId = requestId,
            reason = LuaCancellationReason.REQUESTED,
            elapsedMillis = 8L,
        )
        val protocol = LuaProtocolVersion(1, 0)
        val runtimeInfo = LuaRuntimeInfo(
            protocolMin = protocol,
            protocolMax = protocol,
            providerId = "boundary-test",
            providerVersionName = "1",
            providerVersionCode = 1L,
            runtimeFamily = LuaRuntimeFamily.PUC_LUA,
            runtimeSlot = LuaRuntimeContract.RUNTIME_SLOT_LUA54,
            languageVersion = "5.4.8",
            processAbi = "x86_64",
            supportedAbis = listOf("x86_64"),
            capabilities = listOf("boundary.test"),
            limits = LuaRuntimeLimits(
                maxSourceBytes = 1_024L,
                maxMemoryBytes = 1_024L,
                maxOutputBytes = 1_024L,
                maxExecutionMillis = 1_000L,
                maxConcurrentExecutions = 1,
            ),
            minHostVersionCode = 1L,
            maxHostVersionCode = 1L,
        )

        assertEquals(setOf(1, 2, 3), tags(LuaRuntimeCodec.encodeResult(result)))
        assertEquals((1..5).toSet(), tags(LuaRuntimeCodec.encodeError(error)))
        assertEquals(setOf(1, 2, 3), tags(LuaRuntimeCodec.encodeCancellation(cancellation)))
        assertEquals((1..20).toSet(), tags(LuaRuntimeCodec.encodeRuntimeInfo(runtimeInfo)))

        assertGetterInventory(
            LuaExecutionResult::class.java,
            "getRequestId",
            "getValue",
            "getElapsedMillis",
        )
        assertGetterInventory(
            LuaExecutionError::class.java,
            "getRequestId",
            "getCode",
            "getPhase",
            "getMessage",
            "getRetryDisposition",
        )
        assertGetterInventory(
            LuaExecutionCancellation::class.java,
            "getRequestId",
            "getReason",
            "getElapsedMillis",
        )
        assertGetterInventory(
            LuaRuntimeInfo::class.java,
            "getProtocolMin",
            "getProtocolMax",
            "getProviderId",
            "getProviderVersionName",
            "getProviderVersionCode",
            "getRuntimeFamily",
            "getRuntimeSlot",
            "getLanguageVersion",
            "getProcessAbi",
            "getSupportedAbis",
            "getCapabilities",
            "getLimits",
            "getMinHostVersionCode",
            "getMaxHostVersionCode",
        )

        val getRuntimeInfo = ILuaRuntimeProvider::class.java.getDeclaredMethod("getRuntimeInfo")
        assertEquals(0, getRuntimeInfo.parameterCount)
        assertEquals(ByteArray::class.java, getRuntimeInfo.returnType)

        val callbackMethods = ILuaExecutionCallback::class.java.declaredMethods
        assertEquals(
            setOf("onStarted", "onOutput", "onCompleted", "onFailed", "onCancelled"),
            callbackMethods.mapTo(linkedSetOf()) { it.name },
        )
        assertFalse(callbackMethods.any { it.name.contains("stat", ignoreCase = true) })
        assertEquals(2, callbackMethods.single { it.name == "onCompleted" }.parameterCount)
        assertEquals(1, callbackMethods.single { it.name == "onFailed" }.parameterCount)
        assertEquals(1, callbackMethods.single { it.name == "onCancelled" }.parameterCount)
    }

    private fun tags(bytes: ByteArray): Set<Int> = TaggedWireDocument.decode(bytes).tags

    private fun assertGetterInventory(type: Class<*>, vararg expected: String) {
        val getters = type.declaredMethods
            .filter { method -> method.parameterCount == 0 && method.name.startsWith("get") }
            .mapTo(linkedSetOf()) { method -> method.name }
        assertEquals(expected.toSet(), getters)
        assertFalse(getters.any { name -> name.contains("stat", ignoreCase = true) })
    }
}
