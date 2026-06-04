package com.commandagi.humanrobot.drone

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Turns the high-level action stream (from a CommandAGI agent, or from the in-app test buttons) into
 * the continuous low-level packet stream these AP-mode drones need. A command is held by repeatedly
 * sending its [DroneAction] at [HZ]; momentary commands (e.g. a test-button tap) decay back to a
 * neutral hover after [holdMs].
 */
class DroneController(private val bridge: EspBridge) {
    companion object {
        const val HZ = 25
        const val STICK = 80          // stick deflection from neutral (128) for a full-speed command
    }

    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var current: DroneAction = DroneAction.neutral()
    @Volatile private var holdUntilMs: Long = Long.MAX_VALUE // sustained until changed
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        ticker.scheduleAtFixedRate({
            if (System.currentTimeMillis() > holdUntilMs && !isFlightFlag(current)) {
                current = DroneAction.neutral()
            }
            try { bridge.send(current) } catch (_: Exception) {}
        }, 0, (1000L / HZ), TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        ticker.shutdownNow()
    }

    private fun isFlightFlag(a: DroneAction) = a.takeoff || a.land || a.emergencyStop || a.calibrate

    /** Sustain [action] until the next command (used for agent-driven flight). */
    fun set(action: DroneAction) { current = action; holdUntilMs = Long.MAX_VALUE }

    /** Apply [action] for [holdMs] then return to hover (used for momentary test taps). */
    fun pulse(action: DroneAction, holdMs: Long = 500) {
        current = action; holdUntilMs = System.currentTimeMillis() + holdMs
    }

    private fun mag(speed: Double) = (STICK * speed.coerceIn(0.0, 1.0)).toInt()

    /**
     * Map a CommandAGI control action to a drone command. Mirrors the robot action vocabulary used by
     * the sims and the human-robot path: move / back / turn / stop / reset, plus takeoff / land.
     */
    fun applyAgentAction(action: String, payload: JSONObject) {
        val speed = payload.optDouble("speed", 1.0)
        when (action) {
            "move" -> set(DroneAction(pitch = 128 + mag(speed)))
            "back" -> set(DroneAction(pitch = 128 - mag(speed)))
            "turn" -> {
                val right = payload.optString("dir", "left") == "right"
                set(DroneAction(yaw = if (right) 128 + mag(speed) else 128 - mag(speed)))
            }
            "up" -> set(DroneAction(throttle = 128 + mag(speed)))
            "down" -> set(DroneAction(throttle = 128 - mag(speed)))
            "takeoff" -> pulse(DroneAction(takeoff = true), 700)
            "land" -> pulse(DroneAction(land = true), 700)
            "stop" -> set(DroneAction.neutral())
            "reset" -> pulse(DroneAction(land = true), 700)
            else -> set(DroneAction.neutral())
        }
    }

    // ── test-control helpers (momentary) ──────────────────────────────────────
    fun testTakeoff() = pulse(DroneAction(takeoff = true), 700)
    fun testLand() = pulse(DroneAction(land = true), 700)
    fun testForward() = pulse(DroneAction(pitch = 128 + STICK))
    fun testBack() = pulse(DroneAction(pitch = 128 - STICK))
    fun testLeft() = pulse(DroneAction(roll = 128 - STICK))
    fun testRight() = pulse(DroneAction(roll = 128 + STICK))
    fun testYawLeft() = pulse(DroneAction(yaw = 128 - STICK))
    fun testYawRight() = pulse(DroneAction(yaw = 128 + STICK))
    fun testUp() = pulse(DroneAction(throttle = 128 + STICK))
    fun testDown() = pulse(DroneAction(throttle = 128 - STICK))
    fun testStop() = set(DroneAction.neutral())
    fun testEmergency() = pulse(DroneAction.motorStop(), 400)
}
