package com.commandagi.humanrobot.drone

import android.content.Context

/**
 * Process-wide owner of the single USB serial link. The settings screen configures it (scan / join /
 * test) and the runtime screen relays the agent’s actions through it — both share one [EspBridge] so
 * the ESP32 stays connected as you move between screens.
 */
object DroneRelay {
    private var _bridge: EspBridge? = null
    private var _controller: DroneController? = null

    fun ensure(context: Context): Pair<EspBridge, DroneController> {
        _bridge?.let { return it to _controller!! }
        val b = EspBridge(context.applicationContext)
        val c = DroneController(b)
        _bridge = b
        _controller = c
        return b to c
    }

    val espBridge: EspBridge? get() = _bridge
    val controller: DroneController? get() = _controller
    val isReady: Boolean get() = _bridge?.link == EspBridge.Link.READY
}
