package io.github.supermonster003.autojs6.plugin.lua.runtime

import android.app.Activity
import android.os.Bundle

/** Explicit host activation clears the stopped state without starting a Lua session. */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
