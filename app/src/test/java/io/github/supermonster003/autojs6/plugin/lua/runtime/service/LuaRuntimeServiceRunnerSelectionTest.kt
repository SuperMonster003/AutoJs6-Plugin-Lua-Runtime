package io.github.supermonster003.autojs6.plugin.lua.runtime.service

import io.github.supermonster003.autojs6.plugin.lua.runtime.NativeLuaExecutionRunner
import io.github.supermonster003.autojs6.plugin.lua.runtime.execution.DisabledLuaExecutionRunner
import org.junit.Assert.assertSame
import org.junit.Test

class LuaRuntimeServiceRunnerSelectionTest {
    @Test
    fun nativeRunnerSelectionIsExplicitAndDoesNotLoadTheLibrary() {
        assertSame(DisabledLuaExecutionRunner, selectLuaExecutionRunner(nativeEnabled = false))
        assertSame(NativeLuaExecutionRunner, selectLuaExecutionRunner(nativeEnabled = true))
    }
}
