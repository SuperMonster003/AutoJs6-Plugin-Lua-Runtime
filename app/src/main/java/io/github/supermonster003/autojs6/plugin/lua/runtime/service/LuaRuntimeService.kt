package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.supermonster003.autojs6.plugin.lua.runtime.LuaProviderMetadata
import io.github.supermonster003.autojs6.plugin.lua.runtime.NativeLuaRuntime
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.DisabledLuaExecutionRunner
import org.autojs.plugin.lua.runtime.api.ILuaExecutionCallback
import org.autojs.plugin.lua.runtime.api.ILuaExecutionSession
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityBroker
import org.autojs.plugin.lua.runtime.api.ILuaRuntimeProvider
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaRuntimeValidation

/**
 * R3 service boundary. Discovery is disabled by the manifest resource until
 * native execution, cancellation, descriptor ownership, and conformance gates pass.
 */
class LuaRuntimeService : Service() {
    private lateinit var callerVerifier: HostCallerVerifier
    private lateinit var executionManager: LuaRuntimeExecutionManager

    override fun onCreate() {
        super.onCreate()
        callerVerifier = HostCallerVerifier(this)
        // Intentionally fail-closed. A reviewed native adapter is injected only
        // after R3 execution and conformance gates pass.
        executionManager = LuaRuntimeExecutionManager(
            callerVerifier = callerVerifier,
            runner = DisabledLuaExecutionRunner,
        )
    }

    // Explicit component-only binding intentionally accepts a null action.
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        executionManager.close()
        super.onDestroy()
    }

    private val binder = object : ILuaRuntimeProvider.Stub() {
        override fun getRuntimeInfo(): ByteArray {
            callerVerifier.enforceAllowedCaller()
            NativeLuaRuntime.requireReady()
            return LuaRuntimeCodec.encodeRuntimeInfo(
                LuaProviderMetadata.runtimeInfo(this@LuaRuntimeService),
            )
        }

        override fun createExecution(
            requestMetadata: ByteArray?,
            source: ParcelFileDescriptor?,
            callback: ILuaExecutionCallback?,
            hostBroker: ILuaHostCapabilityBroker?,
        ): ILuaExecutionSession? {
            val createdNanos = System.nanoTime()
            try {
                val ownerUid = callerVerifier.enforceAllowedCaller()
                require(requestMetadata != null) { "Lua execution metadata must not be null" }
                require(requestMetadata.size <= LuaRuntimeContract.MAX_METADATA_BYTES) {
                    "Lua execution metadata exceeds the protocol bound"
                }
                require(source != null) { "Lua source descriptor must not be null" }
                require(callback != null) { "Lua execution callback must not be null" }
                require(hostBroker != null) { "Lua host broker must not be null" }
                NativeLuaRuntime.requireReady()
                val runtimeInfo = LuaProviderMetadata.runtimeInfo(this@LuaRuntimeService)
                val request = LuaRuntimeCodec.decodeExecutionRequest(requestMetadata)
                LuaRuntimeValidation.validateRequestAgainst(
                    request,
                    runtimeInfo,
                    request.protocolVersion,
                )
                return executionManager.create(
                    createdNanos = createdNanos,
                    ownerUid = ownerUid,
                    runtimeInfo = runtimeInfo,
                    request = request,
                    incomingSource = source,
                    callback = callback,
                    hostBroker = hostBroker,
                )
            } finally {
                source?.runCatching { close() }
            }
        }
    }
}
