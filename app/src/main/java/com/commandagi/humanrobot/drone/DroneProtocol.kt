package com.commandagi.humanrobot.drone

/**
 * Drone control packet — the payload the ESP32 forwards over UDP to the drone. Port of the
 * `drone-control` action model + the WIFI_8K packet builder (`drone_control/actions.py`,
 * `drone_control/protocols.py`).
 *
 * Channels are 0..255 with 128 neutral (centered sticks). These cheap `WIFI_8K-*` / E99-class
 * AP-mode camera drones expect a continuous stream of these packets to hold a command.
 */
data class DroneAction(
    val roll: Int = 128,     // left / right strafe
    val pitch: Int = 128,    // forward / backward
    val throttle: Int = 128, // up / down
    val yaw: Int = 128,      // rotate left / right
    val takeoff: Boolean = false,
    val land: Boolean = false,
    val emergencyStop: Boolean = false,
    val calibrate: Boolean = false,
    val headless: Boolean = false,
    val flip: Boolean = false,
) {
    companion object {
        fun neutral() = DroneAction()
        fun motorStop() = DroneAction(throttle = 0, emergencyStop = true)
        fun clampByte(v: Int) = v.coerceIn(0, 255)
    }

    fun sanitized() = DroneAction(
        clampByte(roll), clampByte(pitch), clampByte(throttle), clampByte(yaw),
        takeoff, land, emergencyStop, calibrate, headless, flip,
    )
}

/**
 * 9-byte WIFI_8K command observed in captures: `03 66 R P T Y FLAGS XOR 99`.
 * This is the protocol the bulk of these toy drones speak; it is the default the relay uses.
 */
object Wifi8kProtocol {
    const val NAME = "wifi_8k_prefixed_short"
    private const val FLAG_TAKEOFF = 0x01
    private const val FLAG_LAND = 0x02
    private const val FLAG_EMERGENCY = 0x04
    private const val FLAG_FLIP = 0x08
    private const val FLAG_HEADLESS = 0x10
    private const val FLAG_CALIBRATE = 0x80

    fun build(action: DroneAction): ByteArray {
        val a = action.sanitized()
        var flags = 0
        if (a.takeoff) flags = flags or FLAG_TAKEOFF
        if (a.land) flags = flags or FLAG_LAND
        if (a.emergencyStop) flags = flags or FLAG_EMERGENCY
        if (a.flip) flags = flags or FLAG_FLIP
        if (a.headless) flags = flags or FLAG_HEADLESS
        if (a.calibrate) flags = flags or FLAG_CALIBRATE
        val controls = byteArrayOf(
            a.roll.toByte(), a.pitch.toByte(), a.throttle.toByte(), a.yaw.toByte(), (flags and 0xFF).toByte(),
        )
        var xor = 0
        for (b in controls) xor = xor xor (b.toInt() and 0xFF)
        return byteArrayOf(0x03, 0x66) + controls + byteArrayOf((xor and 0xFF).toByte(), 0x99.toByte())
    }
}
