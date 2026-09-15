package io.github.supermonster003.autojs6.plugin.lua.runtime.debug

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.supermonster003.autojs6.plugin.lua.runtime.BuildConfig
import io.github.supermonster003.autojs6.plugin.lua.runtime.LuaProviderMetadata
import io.github.supermonster003.autojs6.plugin.lua.runtime.NativeLuaExecutionRunner
import io.github.supermonster003.autojs6.plugin.lua.runtime.diagnostic.LuaRuntimeCrashDiagnostics
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaExecutionRunner
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.LuaRunnerRequest
import io.github.supermonster003.autojs6.plugin.lua.runtime.service.LuaFileDescriptorLease
import io.github.supermonster003.autojs6.plugin.lua.runtime.service.LuaRuntimeExecutionManager
import io.github.supermonster003.autojs6.plugin.lua.runtime.service.LuaSessionCallerVerifier
import org.autojs.plugin.lua.runtime.api.ILuaExecutionCallback
import org.autojs.plugin.lua.runtime.api.ILuaExecutionSession
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityBroker
import org.autojs.plugin.lua.runtime.api.ILuaRuntimeProvider
import org.autojs.plugin.lua.runtime.api.LuaProtocolVersion
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaRuntimeFamily
import org.autojs.plugin.lua.runtime.api.LuaRuntimeInfo
import org.autojs.plugin.lua.runtime.api.LuaRuntimeLimits
import org.autojs.plugin.lua.runtime.api.LuaRuntimeValidation
import org.autojs.plugin.lua.runtime.api.LuaValue
import java.io.File
import java.security.SecureRandom

/**
 * Explicit-component, same-UID, debug-only control target in the real `:lua_runtime` process.
 *
 * The control Binder only exposes process identity and a debug-only [ILuaRuntimeProvider]. The
 * instrumentation client must pass its source PFD, callback, and broker through that real AIDL
 * boundary and must start the returned remote [ILuaExecutionSession]. INFO/RUNTIME discovery stays
 * disabled, while manager, source verifier, controller, serial worker, and process watchdog are the
 * same implementations used by the production provider.
 */
class LuaRuntimeFaultService : Service() {
    private lateinit var executionManager: LuaRuntimeExecutionManager

    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".fault_test")) {
            "The Lua runtime fault service requires the isolated faultTest variant"
        }
        LuaRuntimeCrashDiagnostics.initialize(this)
        executionManager = LuaRuntimeExecutionManager(
            callerVerifier = SameUidSessionCallerVerifier,
            runner = FaultAwareLuaExecutionRunner,
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        check(intent?.action == null) { "The Lua fault service only accepts actionless binding" }
        return controlBinder
    }

    override fun onDestroy() {
        executionManager.close()
        super.onDestroy()
    }

    private val controlBinder = object : Binder() {
        init {
            attachInterface(null, LuaRuntimeFaultProtocol.DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in LuaRuntimeFaultProtocol.FIRST_TRANSACTION..LuaRuntimeFaultProtocol.LAST_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(LuaRuntimeFaultProtocol.DESCRIPTOR)
            SameUidSessionCallerVerifier.enforceAllowedCaller()
            when (code) {
                LuaRuntimeFaultProtocol.TRANSACTION_IDENTITY -> {
                    checkNotNull(reply).apply {
                        writeNoException()
                        writeInt(Process.myPid())
                        writeLong(LuaRuntimeFaultProcessEpoch.nonce)
                        writeString(currentProcessName())
                    }
                }
                LuaRuntimeFaultProtocol.TRANSACTION_PROVIDER -> {
                    checkNotNull(reply).apply {
                        writeNoException()
                        writeStrongBinder(runtimeProvider)
                    }
                }
                LuaRuntimeFaultProtocol.TRANSACTION_OPEN_FD_COUNT -> {
                    checkNotNull(reply).apply {
                        writeNoException()
                        writeInt(openFileDescriptorCount())
                    }
                }
            }
            return true
        }
    }

    private val runtimeProvider = object : ILuaRuntimeProvider.Stub() {
        override fun getRuntimeInfo(): ByteArray {
            SameUidSessionCallerVerifier.enforceAllowedCaller()
            return LuaRuntimeCodec.encodeRuntimeInfo(debugRuntimeInfo())
        }

        override fun createExecution(
            requestMetadata: ByteArray?,
            source: ParcelFileDescriptor?,
            callback: ILuaExecutionCallback?,
            hostBroker: ILuaHostCapabilityBroker?,
        ): ILuaExecutionSession? {
            val createdNanos = System.nanoTime()
            var incomingOwnership: LuaFileDescriptorLease? = null
            try {
                val ownerUid = SameUidSessionCallerVerifier.enforceAllowedCaller()
                require(requestMetadata != null) { "Lua execution metadata must not be null" }
                require(requestMetadata.size <= LuaRuntimeContract.MAX_METADATA_BYTES) {
                    "Lua execution metadata exceeds the protocol bound"
                }
                require(source != null) { "Lua source descriptor must not be null" }
                incomingOwnership = executionManager.trackIncomingSource()
                require(callback != null) { "Lua execution callback must not be null" }
                require(hostBroker != null) { "Lua host broker must not be null" }
                val runtimeInfo = debugRuntimeInfo()
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
                try {
                    source?.runCatching { close() }
                } finally {
                    incomingOwnership?.close()
                }
            }
        }
    }

    private fun debugRuntimeInfo(): LuaRuntimeInfo = LuaRuntimeInfo(
        protocolMin = PROTOCOL,
        protocolMax = PROTOCOL,
        providerId = "debug-fault-harness",
        providerVersionName = BuildConfig.VERSION_NAME,
        providerVersionCode = BuildConfig.VERSION_CODE.toLong(),
        runtimeFamily = LuaRuntimeFamily.PUC_LUA,
        runtimeSlot = LuaRuntimeContract.RUNTIME_SLOT_LUA54,
        languageVersion = "5.4.8",
        processAbi = currentProcessAbi(),
        supportedAbis = LuaProviderMetadata.supportedAbis,
        capabilities = LuaRuntimeCrashDiagnostics.reportedCapabilities(emptyList()),
        limits = LuaRuntimeLimits(
            maxSourceBytes = LuaRuntimeContract.MAX_SOURCE_BYTES,
            maxMemoryBytes = LuaRuntimeContract.MAX_MEMORY_BYTES,
            maxOutputBytes = LuaRuntimeContract.MAX_OUTPUT_BYTES,
            maxExecutionMillis = LuaRuntimeContract.MAX_TIMEOUT_MILLIS,
            maxConcurrentExecutions = 1,
        ),
    )

    private fun currentProcessAbi(): String {
        val candidates = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return candidates.firstOrNull(LuaProviderMetadata.supportedAbis::contains)
            ?: error("The debug Lua fault process is running on an unpackaged ABI")
    }

    private fun openFileDescriptorCount(): Int = checkNotNull(File("/proc/self/fd").list()) {
        "The debug Lua runtime could not inspect /proc/self/fd"
    }.size

    @Suppress("DEPRECATION")
    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        File("/proc/self/cmdline").readText().trimEnd('\u0000')
    }

    private companion object {
        val PROTOCOL = LuaProtocolVersion(
            LuaRuntimeContract.PROTOCOL_MAJOR,
            LuaRuntimeContract.PROTOCOL_MINOR,
        )
    }
}

private object LuaRuntimeFaultProcessEpoch {
    val nonce: Long = SecureRandom().nextLong()
}

private object SameUidSessionCallerVerifier : LuaSessionCallerVerifier {
    fun enforceAllowedCaller(): Int = Binder.getCallingUid().also { callingUid ->
        check(callingUid == Process.myUid()) { "The debug Lua Binder escaped its owning app UID" }
    }

    override fun enforceSessionOwner(expectedUid: Int) {
        check(enforceAllowedCaller() == expectedUid) {
            "The debug Lua session UID does not match its owner"
        }
    }
}

private object FaultAwareLuaExecutionRunner : LuaExecutionRunner {
    override fun execute(request: LuaRunnerRequest): LuaValue {
        val source = request.sourceUtf8()
        return when {
            source.contentEquals(FAULT_CRASH_SOURCE) -> NativeLuaFaults.crash()
            source.contentEquals(FAULT_WEDGE_SOURCE) -> NativeLuaFaults.wedge()
            else -> NativeLuaExecutionRunner.execute(request)
        }
    }
}

/** Tiny, versionless wire used only to obtain the same-build debug provider Binder. */
object LuaRuntimeFaultProtocol {
    const val DESCRIPTOR =
        "io.github.supermonster003.autojs6.plugin.lua.runtime.debug.LuaRuntimeFaultService"
    const val TRANSACTION_IDENTITY = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_PROVIDER = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_OPEN_FD_COUNT = IBinder.FIRST_CALL_TRANSACTION + 2
    const val FIRST_TRANSACTION = TRANSACTION_IDENTITY
    const val LAST_TRANSACTION = TRANSACTION_OPEN_FD_COUNT
}

private val FAULT_CRASH_SOURCE = "-- AUTOJS_DEBUG_NATIVE_CRASH".toByteArray(Charsets.UTF_8)
private val FAULT_WEDGE_SOURCE = "-- AUTOJS_DEBUG_NATIVE_WEDGE".toByteArray(Charsets.UTF_8)
