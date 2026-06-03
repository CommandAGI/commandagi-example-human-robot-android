package com.commandagi.humanrobot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Be a robot. The phone registers itself as a robot on CommandAGI: its camera fills the screen and
 * streams up as the robot's observation, and the move/turn actions the platform sends are shown big
 * at the bottom for you (the human) to perform.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var instruction: TextView
    private lateinit var status: TextView
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private var bridge: CommandAgiBridge? = null
    @Volatile private var lastSentMs = 0L

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else toast("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        instruction = findViewById(R.id.instruction)
        status = findViewById(R.id.status)
        findViewById<View>(R.id.settings).setOnClickListener { promptApiKey() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        if (apiKey().isNullOrBlank()) promptApiKey() else connect()
    }

    // ── settings (API key in SharedPreferences) ─────────────────────────────
    private fun prefs() = getSharedPreferences("commandagi", Context.MODE_PRIVATE)
    private fun apiKey(): String? = prefs().getString("api_key", null)
    private fun baseUrl(): String = prefs().getString("base_url", "https://api.commandagi.com")!!

    private fun promptApiKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(apiKey() ?: "")
            hint = "cagi_…"
        }
        AlertDialog.Builder(this)
            .setTitle("CommandAGI API key")
            .setMessage("Paste an API key (operator scope) from your CommandAGI dashboard.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString("api_key", input.text.toString().trim()).apply()
                connect()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── connect the bridge ──────────────────────────────────────────────────
    private fun connect() {
        val key = apiKey()
        if (key.isNullOrBlank()) return
        bridge?.stop()
        val b = CommandAgiBridge(
            apiKey = key,
            baseUrl = baseUrl(),
            onAction = { action, payload -> runOnUiThread { showInstruction(action, payload) } },
            onStatus = { s, _ -> runOnUiThread { status.text = s } },
        )
        bridge = b
        net.execute {
            try { b.start() } catch (e: Exception) { runOnUiThread { status.text = "Error: ${e.message}" } }
        }
    }

    private fun showInstruction(action: String, payload: JSONObject) {
        instruction.text = when (action) {
            "move" -> "⬆  MOVE FORWARD"
            "back" -> "⬇  MOVE BACKWARD"
            "turn" -> if (payload.optString("dir", "left") == "right") "➡  TURN RIGHT" else "⬅  TURN LEFT"
            "stop" -> "✋  STOP"
            "reset" -> "↺  RETURN TO START"
            else -> action.uppercase()
        }
    }

    // ── camera: fill the screen, stream JPEG frames ─────────────────────────
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> onFrame(image) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
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
