package io.github.supermonster003.autojs6.plugin.lua.runtime.debug

import io.github.supermonster003.autojs6.plugin.lua.runtime.BuildConfig

/** Debug-only JNI entry points. This class is absent from every release source set. */
internal object NativeLuaFaults {
    private val loaded: Unit by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        requireEnabled()
        System.loadLibrary("autojs_lua_runtime")
    }

    fun crash(): Nothing {
        loaded
        nativeCrash()
        error("The native crash fault unexpectedly returned")
    }

    fun wedge(): Nothing {
        loaded
        nativeWedge()
        error("The native wedge fault unexpectedly returned")
    }

    private fun requireEnabled() {
        check(BuildConfig.DEBUG) { "The Lua fault harness requires a debug build" }
        check(BuildConfig.LUA_FAULT_HARNESS_ENABLED) { "The Lua fault harness is disabled" }
        check(BuildConfig.LUA_NATIVE_ENABLED) { "The Lua native runtime is disabled" }
        check(!BuildConfig.LUA_PROVIDER_ENABLED) {
            "The Lua fault harness cannot run while the production provider is enabled"
        }
    }

    private external fun nativeCrash()

    private external fun nativeWedge()
}
