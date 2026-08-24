package io.github.supermonster003.autojs6.plugin.lua.runtime.debug

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.supermonster003.autojs6.plugin.lua.runtime.BuildConfig
import org.autojs.plugin.lua.runtime.api.ILuaExecutionCallback
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityBroker
import org.autojs.plugin.lua.runtime.api.ILuaHostCapabilityCallback
import org.autojs.plugin.lua.runtime.api.LuaRuntimeCodec
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only, same-UID Binder peer hosted outside `:lua_runtime`.
 *
 * Instrumentation obtains either the callback or broker Binder, starts a real remote execution,
 * and then terminates only this peer process. That makes callback and broker death independently
 * observable without killing the instrumentation process or widening a production component.
 */
class LuaRuntimeFaultPeerService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startedCalls = AtomicInteger()
    private val terminalCalls = AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.DEBUG && BuildConfig.LUA_FAULT_HARNESS_ENABLED) {
            "The Lua runtime fault peer requires the explicit debug harness"
        }
        check(BuildConfig.LUA_NATIVE_ENABLED && !BuildConfig.LUA_PROVIDER_ENABLED) {
            "The Lua runtime fault peer requires native=true and provider=false"
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        check(intent?.action == null) { "The Lua fault peer only accepts actionless binding" }
        return controlBinder
    }

    private val callback = object : ILuaExecutionCallback.Stub() {
        override fun onStarted(metadata: ByteArray?) {
            LuaRuntimeCodec.decodeStarted(checkNotNull(metadata))
            startedCalls.incrementAndGet()
        }

        override fun onOutput(metadata: ByteArray?) {
            LuaRuntimeCodec.decodeOutput(checkNotNull(metadata))
        }

        override fun onCompleted(
            metadata: ByteArray?,
            payloads: Array<out ParcelFileDescriptor?>?,
        ) {
            payloads.orEmpty().forEach { descriptor -> descriptor?.runCatching { close() } }
            LuaRuntimeCodec.decodeResult(checkNotNull(metadata))
            terminalCalls.incrementAndGet()
        }

        override fun onFailed(metadata: ByteArray?) {
            LuaRuntimeCodec.decodeError(checkNotNull(metadata))
            terminalCalls.incrementAndGet()
        }

        override fun onCancelled(metadata: ByteArray?) {
            LuaRuntimeCodec.decodeCancellation(checkNotNull(metadata))
            terminalCalls.incrementAndGet()
        }
    }

    private val broker = object : ILuaHostCapabilityBroker.Stub() {
        override fun invoke(
            requestMetadata: ByteArray?,
            payloads: Array<out ParcelFileDescriptor?>?,
            callback: ILuaHostCapabilityCallback?,
        ) {
            payloads.orEmpty().forEach { descriptor -> descriptor?.runCatching { close() } }
            error("The debug peer broker exposes no host capabilities")
        }
    }

    private val controlBinder = object : Binder() {
        init {
            attachInterface(null, LuaRuntimeFaultPeerProtocol.DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in LuaRuntimeFaultPeerProtocol.FIRST_TRANSACTION..LuaRuntimeFaultPeerProtocol.LAST_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(LuaRuntimeFaultPeerProtocol.DESCRIPTOR)
            enforceSameUid()
            when (code) {
                LuaRuntimeFaultPeerProtocol.TRANSACTION_IDENTITY -> checkNotNull(reply).apply {
                    writeNoException()
                    writeInt(Process.myPid())
                }
                LuaRuntimeFaultPeerProtocol.TRANSACTION_CALLBACK -> checkNotNull(reply).apply {
                    writeNoException()
                    writeStrongBinder(callback)
                }
                LuaRuntimeFaultPeerProtocol.TRANSACTION_BROKER -> checkNotNull(reply).apply {
                    writeNoException()
                    writeStrongBinder(broker)
                }
                LuaRuntimeFaultPeerProtocol.TRANSACTION_SNAPSHOT -> checkNotNull(reply).apply {
                    writeNoException()
                    writeInt(startedCalls.get())
                    writeInt(terminalCalls.get())
                }
                LuaRuntimeFaultPeerProtocol.TRANSACTION_KILL -> checkNotNull(reply).apply {
                    writeNoException()
                    mainHandler.post { Process.killProcess(Process.myPid()) }
                }
            }
            return true
        }
    }

    private fun enforceSameUid() {
        check(Binder.getCallingUid() == Process.myUid()) {
            "The debug Lua fault peer escaped its owning app UID"
        }
    }
}

/** Tiny, versionless test wire; it is compiled only into an explicitly enabled debug harness. */
object LuaRuntimeFaultPeerProtocol {
    const val DESCRIPTOR =
        "io.github.supermonster003.autojs6.plugin.lua.runtime.debug.LuaRuntimeFaultPeerService"
    const val TRANSACTION_IDENTITY = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_BROKER = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_KILL = IBinder.FIRST_CALL_TRANSACTION + 4
    const val FIRST_TRANSACTION = TRANSACTION_IDENTITY
    const val LAST_TRANSACTION = TRANSACTION_KILL
}
