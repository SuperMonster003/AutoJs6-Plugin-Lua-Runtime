package io.github.supermonster003.autojs6.plugin.lua.runtime

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.supermonster003.autojs6.plugin.lua.runtime.diagnostic.LuaRuntimeCrashDiagnostics
import org.autojs.plugin.lua.runtime.api.LuaProtocolVersion
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract
import org.autojs.plugin.lua.runtime.api.LuaRuntimeFamily
import org.autojs.plugin.lua.runtime.api.LuaRuntimeInfo
import org.autojs.plugin.lua.runtime.api.LuaRuntimeLimits

internal object LuaProviderMetadata {
    const val HOST_PACKAGE_NAME = "org.autojs.autojs6"
    const val PROVIDER_ID = "official-puc-lua54"
    const val LANGUAGE_VERSION = "5.4.8"

    val supportedAbis = listOf("arm64-v8a", "x86_64")
    private val EXECUTION_CAPABILITIES = listOf(
        NativeLuaHostCapabilityBridge.DEVICE_INFO_CAPABILITY,
        NativeLuaHostCapabilityBridge.MODULE_SNAPSHOT_CAPABILITY,
        NativeLuaHostCapabilityBridge.STORAGE_KV_CAPABILITY,
        NativeLuaHostCapabilityBridge.UI_TOAST_CAPABILITY,
    )

    fun runtimeInfo(context: Context): LuaRuntimeInfo {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val protocol = LuaProtocolVersion(
            LuaRuntimeContract.PROTOCOL_MAJOR,
            LuaRuntimeContract.PROTOCOL_MINOR,
        )
        return LuaRuntimeInfo(
            protocolMin = protocol,
            protocolMax = protocol,
            providerId = PROVIDER_ID,
            providerVersionName = packageInfo.versionName.orEmpty(),
            providerVersionCode = versionCode,
            runtimeFamily = LuaRuntimeFamily.PUC_LUA,
            runtimeSlot = LuaRuntimeContract.RUNTIME_SLOT_LUA54,
            languageVersion = NativeLuaRuntime.languageVersion(),
            processAbi = processAbi(),
            supportedAbis = supportedAbis,
            capabilities = LuaRuntimeCrashDiagnostics.reportedCapabilities(EXECUTION_CAPABILITIES),
            limits = LuaRuntimeLimits(
                maxSourceBytes = LuaRuntimeContract.MAX_SOURCE_BYTES,
                maxMemoryBytes = LuaRuntimeContract.MAX_MEMORY_BYTES,
                maxOutputBytes = LuaRuntimeContract.MAX_OUTPUT_BYTES,
                maxExecutionMillis = LuaRuntimeContract.MAX_TIMEOUT_MILLIS,
                maxConcurrentExecutions = 1,
            ),
            minHostVersionCode = BuildConfig.REQUIRED_HOST_VERSION_CODE,
        )
    }

    private fun processAbi(): String {
        val candidates = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS
        } else {
            Build.SUPPORTED_32_BIT_ABIS
        }
        return candidates.firstOrNull(supportedAbis::contains)
            ?: error("The Lua runtime process is not running on a packaged ABI")
    }
}
