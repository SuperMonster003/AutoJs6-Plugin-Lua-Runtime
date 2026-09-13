package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import java.util.zip.ZipFile
import io.github.supermonster003.autojs6.plugin.lua.runtime.BuildConfig
import io.github.supermonster003.autojs6.plugin.lua.runtime.LuaProviderMetadata
import io.github.supermonster003.autojs6.plugin.lua.runtime.NativeLuaRuntime
import io.github.supermonster003.autojs6.plugin.lua.runtime.R
import org.autojs.plugin.common.api.IPluginInfoProvider
import org.autojs.plugin.common.api.PluginCapabilityKeys
import org.autojs.plugin.common.api.PluginInfo
import org.autojs.plugin.lua.runtime.api.LuaPluginIds
import org.autojs.plugin.lua.runtime.api.LuaRuntimeContract

class LuaPluginInfoService : Service() {
    private lateinit var callerVerifier: HostCallerVerifier

    override fun onCreate() {
        super.onCreate()
        callerVerifier = HostCallerVerifier(this)
    }

    // Explicit component-only binding intentionally accepts a null action.
    override fun onBind(intent: Intent?): IBinder = binder

    private fun installedRuntimeAbis(): Array<String> {
        val packaged = mutableSetOf<String>()
        val paths = listOf(applicationInfo.sourceDir) + applicationInfo.splitSourceDirs.orEmpty()
        paths.forEach { path ->
            ZipFile(path).use { apk ->
                LuaProviderMetadata.supportedAbis.forEach { abi ->
                    if (apk.getEntry("lib/$abi/libautojs_lua_runtime.so") != null) packaged += abi
                }
            }
        }
        check(packaged.isNotEmpty()) { "Installed Lua runtime libraries are missing" }
        return LuaProviderMetadata.supportedAbis.filter { it in packaged }.toTypedArray()
    }

    private val binder = object : IPluginInfoProvider.Stub() {
        override fun getInfo(): PluginInfo {
            callerVerifier.enforceAllowedCaller()
            NativeLuaRuntime.requireReady()
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            return PluginInfo(
                name = getString(R.string.app_name),
                description = getString(R.string.plugin_description),
                instruction = getString(R.string.plugin_instruction),
                author = getString(R.string.plugin_author),
                collaborators = null,
                versionName = requireNotNull(packageInfo.versionName) { "Installed plugin version is missing" },
                versionCode = versionCode,
                versionDate = getString(R.string.plugin_version_date),
                id = LuaPluginIds.ID,
                engine = LuaPluginIds.ENGINE,
                variant = LuaPluginIds.VARIANT_PUC_LUA_5_4,
                supportedAbis = installedRuntimeAbis(),
                capabilities = Bundle().apply {
                    putLong(
                        PluginCapabilityKeys.REQUIRES_HOST_VERSION,
                        BuildConfig.REQUIRED_HOST_VERSION_CODE,
                    )
                    putInt("luaRuntimeProtocolMajor", LuaRuntimeContract.PROTOCOL_MAJOR)
                    putInt("luaRuntimeProtocolMinor", LuaRuntimeContract.PROTOCOL_MINOR)
                    putString("runtimeSlot", LuaRuntimeContract.RUNTIME_SLOT_LUA54)
                    putString("languageVersion", LuaProviderMetadata.LANGUAGE_VERSION)
                },
            )
        }
    }
}
