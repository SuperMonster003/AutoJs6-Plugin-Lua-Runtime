package io.github.supermonster003.autojs6.plugin.lua.runtime.execution

import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes callback invocation claims without holding a monitor while the
 * callback itself may cross Binder. A close racing with an in-flight callback
 * is completed by that callback's release path.
 */
internal class LuaCallbackDeliveryGate(
    private val closeObserver: () -> Unit,
) : AutoCloseable {
    private enum class State {
        OPEN,
        IN_FLIGHT,
        CLOSE_PENDING,
        CLOSED,
    }

    private val state = AtomicReference(State.OPEN)

    fun deliver(block: () -> Unit): Boolean {
        if (!state.compareAndSet(State.OPEN, State.IN_FLIGHT)) return false
        try {
            block()
        } finally {
            releaseDelivery()
        }
        return true
    }

    override fun close() {
        while (true) {
            when (state.get()) {
                State.OPEN -> if (state.compareAndSet(State.OPEN, State.CLOSED)) {
                    closeObserver()
                    return
                }
                State.IN_FLIGHT -> if (state.compareAndSet(State.IN_FLIGHT, State.CLOSE_PENDING)) {
                    return
                }
                State.CLOSE_PENDING,
                State.CLOSED,
                -> return
            }
        }
    }

    private fun releaseDelivery() {
        while (true) {
            when (state.get()) {
                State.IN_FLIGHT -> if (state.compareAndSet(State.IN_FLIGHT, State.OPEN)) return
                State.CLOSE_PENDING -> if (state.compareAndSet(State.CLOSE_PENDING, State.CLOSED)) {
                    closeObserver()
                    return
                }
                State.OPEN,
                State.CLOSED,
                -> error("Invalid Lua callback-delivery state")
            }
        }
    }
}
