package com.commandagi.humanrobot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.commandagi.humanrobot.drone.DroneRelay
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Runtime screen. The phone registers as a robot on CommandAGI; its camera fills the screen and
 * streams up as the robot’s observation. What happens with the control actions the platform sends
 * depends on the "What am I driving?" setting:
 *  • Human — the move/turn instruction is shown big at the bottom (and optionally spoken / vibrated).
 *  • Drone — the action is relayed to an ESP32 → Wi-Fi AP drone, and echoed on screen as telemetry.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var instruction: TextView
    private lateinit var status: TextView
    private lateinit var dictateToggle: TextView
    private lateinit var dronePanel: View
    private lateinit var droneLink: TextView
    private lateinit var droneTelemetry: TextView
    private lateinit var prefs: Prefs
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private var bridge: CommandAgiBridge? = null
    private var tts: TextToSpeech? = null
    private var boundFacing: String = ""
    @Volatile private var lastSentMs = 0L

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else toast("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        previewView = findViewById(R.id.preview)
        instruction = findViewById(R.id.instruction)
        status = findViewById(R.id.status)
        dictateToggle = findViewById(R.id.dictateToggle)
        dronePanel = findViewById(R.id.dronePanel)
        droneLink = findViewById(R.id.droneLink)
        droneTelemetry = findViewById(R.id.droneTelemetry)
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        dictateToggle.setOnClickListener {
            prefs.dictate = !prefs.dictate
            renderDictateToggle()
        }
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        applyKeepAwake()
        // Camera facing may have changed in Settings.
        if (boundFacing != prefs.cameraFacing && hasCamera()) startCamera()
        applyModeUi()
        if (apiKey().isNullOrBlank()) {
            instruction.text = "Connect to CommandAGI in Settings"
        } else if (bridge == null) {
            connect()
        }
    }

    /**
     * Human mode: fullscreen camera + big move directions + a dictation quick-toggle. Drone mode: a
     * status + remote-control panel over the lower half (the camera = the observation streamed up).
     */
    private fun applyModeUi() {
        val drone = prefs.driver == Driver.DRONE
        instruction.visibility = if (drone) View.GONE else View.VISIBLE
        dronePanel.visibility = if (drone) View.VISIBLE else View.GONE
        dictateToggle.visibility = if (drone) View.GONE else View.VISIBLE
        renderDictateToggle()
        if (drone) {
            val (b, c) = DroneRelay.ensure(this)
            c.start()
            buildDroneControls(c)
            b.onLink = { _, detail -> runOnUiThread { droneLink.text = "Drone link: $detail" } }
            droneLink.text = "Drone link: " + if (DroneRelay.isReady) "connected" else "not connected (open Settings)"
            status.text = if (DroneRelay.isReady) "Driving a drone" else "Drone mode"
        } else if (bridge?.connected != true) {
            status.text = if (apiKey().isNullOrBlank()) "Not connected" else "Connecting…"
        }
    }

    private fun renderDictateToggle() {
        dictateToggle.text = if (prefs.dictate) "Dictate: on" else "Dictate: off"
    }

    private var droneControlsBuilt = false
    private fun buildDroneControls(c: com.commandagi.humanrobot.drone.DroneController) {
        if (droneControlsBuilt) return
        droneControlsBuilt = true
        val grid = findViewById<android.widget.GridLayout>(R.id.droneControls)
        grid.removeAllViews()
        val buttons = listOf<Pair<String, () -> Unit>>(
            "Takeoff" to { c.testTakeoff() }, "Forward" to { c.testForward() }, "Land" to { c.testLand() },
            "Yaw L" to { c.testYawLeft() }, "Stop" to { c.testStop() }, "Yaw R" to { c.testYawRight() },
            "Left" to { c.testLeft() }, "Back" to { c.testBack() }, "Right" to { c.testRight() },
        )
        for ((label, action) in buttons) {
            val b = TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 30, 0, 30)
                setBackgroundColor(0x33FFFFFF)
                setOnClickListener { action() }
            }
            val lp = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1, 1f)
                setMargins(6, 6, 6, 6)
            }
            grid.addView(b, lp)
        }
    }

    // ── settings access ───────────────────────────────────────────────────────
    private fun apiKey(): String? = prefs.apiKey ?: BuildConfig.COMMANDAGI_API_KEY.takeIf { it.isNotBlank() }
    private fun baseUrl(): String = prefs.baseUrl ?: BuildConfig.COMMANDAGI_BASE_URL

    private fun applyKeepAwake() {
        // While streaming we keep the screen on; the human "Keep awake" toggle additionally holds it
        // on between instructions. Either way the relevant flag is set while this screen is live.
        if (prefs.keepAwake || prefs.driver == Driver.DRONE) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ── connect the cloud bridge ──────────────────────────────────────────────
    private fun connect() {
        val key = apiKey()
        if (key.isNullOrBlank()) return
        bridge?.stop()
        val b = CommandAgiBridge(
            apiKey = key,
            baseUrl = baseUrl(),
            onAction = { action, payload -> runOnUiThread { handleAction(action, payload) } },
            onStatus = { s, _ -> runOnUiThread { status.text = s } },
        )
        bridge = b
        net.execute {
            try { b.start() } catch (e: Exception) { runOnUiThread { status.text = "Error: ${e.message}" } }
        }
    }

    private fun handleAction(action: String, payload: JSONObject) {
        when (prefs.driver) {
            Driver.HUMAN -> {
                val text = humanText(action, payload)
                instruction.text = text
                if (prefs.dictate) tts?.speak(spoken(action, payload), TextToSpeech.QUEUE_FLUSH, null, "instr")
                if (prefs.haptics) vibrate()
            }
            Driver.DRONE -> {
                DroneRelay.controller?.applyAgentAction(action, payload)
                val ready = DroneRelay.isReady
                droneTelemetry.text = "Last command: " + droneText(action, payload) +
                    (if (ready) "  · relayed" else "  · (no drone — connect in Settings)")
            }
        }
    }

    private fun humanText(action: String, payload: JSONObject): String = when (action) {
        "move" -> "⬆  MOVE FORWARD"
        "back" -> "⬇  MOVE BACKWARD"
        "turn" -> if (payload.optString("dir", "left") == "right") "➡  TURN RIGHT" else "⬅  TURN LEFT"
        "stop" -> "✋  STOP"
        "reset" -> "↺  RETURN TO START"
        else -> action.uppercase()
    }

    private fun spoken(action: String, payload: JSONObject): String = when (action) {
        "move" -> "Move forward"
        "back" -> "Move backward"
        "turn" -> if (payload.optString("dir", "left") == "right") "Turn right" else "Turn left"
        "stop" -> "Stop"
        "reset" -> "Return to start"
        else -> action
    }

    private fun droneText(action: String, payload: JSONObject): String = when (action) {
        "turn" -> "TURN " + payload.optString("dir", "left").uppercase()
        else -> action.uppercase()
    }

    private fun vibrate() {
        val effect = VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
        }
    }

    // ── camera: fill the screen, stream JPEG frames ───────────────────────────
    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val facing = prefs.cameraFacing
        boundFacing = facing
        val selector = if (facing == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> onFrame(image) }
            provider.unbindAll()
            try {
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                // Fall back to the back camera if the requested lens is unavailable.
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                boundFacing = "back"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val b = bridge
            if (b != null && b.connected && now - lastSentMs >= 150) { // ~6 fps
                lastSentMs = now
                b.sendFrame(image.toJpeg(70))
            }
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge?.stop()
        tts?.shutdown()
        analysisExecutor.shutdown()
        net.shutdown()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Convert a YUV_420_888 ImageProxy to JPEG bytes. */
private fun ImageProxy.toJpeg(quality: Int): ByteArray {
    val y = planes[0].buffer
    val u = planes[1].buffer
    val v = planes[2].buffer
    val ySize = y.remaining(); val uSize = u.remaining(); val vSize = v.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    y.get(nv21, 0, ySize)
    v.get(nv21, ySize, vSize)
    u.get(nv21, ySize + vSize, uSize)
    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, width, height, null)
        .compressToJpeg(Rect(0, 0, width, height), quality, out)
    return out.toByteArray()
}
