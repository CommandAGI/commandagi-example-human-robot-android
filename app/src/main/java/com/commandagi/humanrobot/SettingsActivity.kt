package com.commandagi.humanrobot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.commandagi.humanrobot.drone.DroneController
import com.commandagi.humanrobot.drone.DroneRelay
import com.commandagi.humanrobot.drone.EspBridge

/**
 * Settings screen. A segmented "What am I driving?" toggle (Human / Drone) swaps the options shown
 * below it — exactly the squircle-vs-rounded-rect preference pattern. Human mode exposes the
 * human-experience options; Drone mode exposes the ESP32 relay: a wiring diagram, a Wi-Fi scanner,
 * live drone status + test control, and short docs.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var bridge: EspBridge? = null
    private var controller: DroneController? = null

    private lateinit var modeHuman: TextView
    private lateinit var modeDrone: TextView
    private lateinit var camBack: TextView
    private lateinit var camFront: TextView
    private lateinit var diagram: DroneWiringDiagram
    private lateinit var espStatus: TextView
    private lateinit var droneStatus: TextView

    private val ink = Color.parseColor("#202124")
    private val muted = Color.parseColor("#5F6368")

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    bridge?.refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        // API key
        val apiKeyValue = findViewById<TextView>(R.id.apiKeyValue)
        fun renderKey() { apiKeyValue.text = prefs.apiKey?.let { mask(it) } ?: "Tap to set" }
        renderKey()
        apiKeyValue.setOnClickListener { promptApiKey { renderKey() } }

        // Mode toggle
        modeHuman = findViewById(R.id.modeHuman)
        modeDrone = findViewById(R.id.modeDrone)
        modeHuman.setOnClickListener { setMode(Driver.HUMAN) }
        modeDrone.setOnClickListener { setMode(Driver.DRONE) }

        setupHuman()
        setMode(prefs.driver) // wires the drone bridge lazily when Drone is selected
    }

    // ── mode ──────────────────────────────────────────────────────────────────
    private fun setMode(mode: Driver) {
        prefs.driver = mode
        styleSegment(modeHuman, mode == Driver.HUMAN)
        styleSegment(modeDrone, mode == Driver.DRONE)
        findViewById<View>(R.id.humanSettings).visibility = if (mode == Driver.HUMAN) View.VISIBLE else View.GONE
        findViewById<View>(R.id.droneSettings).visibility = if (mode == Driver.DRONE) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.modeHint).text = when (mode) {
            Driver.HUMAN -> "You are the robot. The camera streams up as the robot’s view; move/turn instructions are shown for you to perform."
            Driver.DRONE -> "An ESP32 plugged into this phone relays the agent’s commands to a Wi-Fi AP drone. The phone camera streams up as the observation."
        }
        if (mode == Driver.DRONE) ensureBridge().also { it.refresh() }
    }

    // ── human settings ──────────────────────────────────────────────────────
    private fun setupHuman() {
        bindSwitch(R.id.swDictate, "Dictate directions aloud", prefs.dictate) { prefs.dictate = it }
        bindSwitch(R.id.swKeepAwake, "Keep the screen awake", prefs.keepAwake) { prefs.keepAwake = it }
        bindSwitch(R.id.swHaptics, "Vibrate on a new instruction", prefs.haptics) { prefs.haptics = it }
        camBack = findViewById(R.id.camBack)
        camFront = findViewById(R.id.camFront)
        camBack.setOnClickListener { prefs.cameraFacing = "back"; renderCamera() }
        camFront.setOnClickListener { prefs.cameraFacing = "front"; renderCamera() }
        renderCamera()
    }

    private fun renderCamera() {
        styleSegment(camBack, prefs.cameraFacing == "back")
        styleSegment(camFront, prefs.cameraFacing == "front")
    }

    private fun bindSwitch(id: Int, text: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<SwitchCompat>(id)
        sw.text = text
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    // ── drone settings ────────────────────────────────────────────────────────
    private fun ensureBridge(): EspBridge {
        bridge?.let { return it }
        val (b, c) = DroneRelay.ensure(this)
        bridge = b
        controller = c
        diagram = findViewById(R.id.diagram)
        espStatus = findViewById(R.id.espStatus)
        droneStatus = findViewById(R.id.droneStatus)

        b.onEsp = { detected, name ->
            runOnUiThread {
                diagram.espDetected = detected
                espStatus.text = if (detected) "ESP32 detected: $name" else
                    "No ESP32 detected. Plug one into the phone’s USB-C port."
                // Flow: once an ESP32 is plugged in, open the networks section so you can pick an AP.
                if (detected) expand(R.id.netsHeader, R.id.netsBody, "Wi-Fi networks visible to the ESP32", true)
            }
        }
        b.onScan = { nets -> runOnUiThread { renderNetworks(nets) } }
        b.onLink = { state, detail ->
            runOnUiThread {
                droneStatus.text = detail
                val ready = state == EspBridge.Link.READY
                diagram.droneConnected = ready
                if (ready) {
                    prefs.lastSsid = null // joined ssid lives in the bridge; status reflects it
                    // Flow: protocol check passed → collapse networks, open drone control.
                    expand(R.id.netsHeader, R.id.netsBody, "Wi-Fi networks visible to the ESP32", false)
                    expand(R.id.droneHeader, R.id.droneBody, "Drone status & test control", true)
                    c.start()
                }
            }
        }
        b.onLog = { /* hook for verbose logging if desired */ }

        // Collapsible headers
        wireCollapse(R.id.netsHeader, R.id.netsBody, "Wi-Fi networks visible to the ESP32")
        wireCollapse(R.id.droneHeader, R.id.droneBody, "Drone status & test control")
        wireCollapse(R.id.learnHeader, R.id.learnBody, "About the ESP32 relay")
        findViewById<TextView>(R.id.scanButton).setOnClickListener { b.scan() }
        buildTestGrid(c)
        buildLearn()
        return b
    }

    private fun renderNetworks(nets: List<EspBridge.ScanNet>) {
        val list = findViewById<LinearLayout>(R.id.netList)
        list.removeAllViews()
        if (nets.isEmpty()) {
            list.addView(hint("No networks found. Make sure the drone is powered on, then Scan again."))
            return
        }
        for (n in nets) {
            val row = TextView(this).apply {
                val lock = if (n.open) "open" else "secured"
                val tag = if (bridge?.looksLikeDrone(n.ssid) == true) "  • drone" else ""
                text = "${n.ssid}\n${n.rssi} dBm · $lock$tag"
                textSize = 14f
                setTextColor(ink)
                setPadding(28, 28, 28, 28)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onPickNetwork(n) }
            }
            list.addView(row)
        }
    }

    private fun onPickNetwork(n: EspBridge.ScanNet) {
        if (n.open) { bridge?.join(n.ssid, "") ; return }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Wi-Fi password"
        }
        AlertDialog.Builder(this)
            .setTitle(n.ssid)
            .setMessage("This network is secured. Enter its Wi-Fi password (leave blank if none).")
            .setView(input)
            .setPositiveButton("Connect") { _, _ -> bridge?.join(n.ssid, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildTestGrid(c: DroneController) {
        val grid = findViewById<GridLayout>(R.id.testGrid)
        grid.removeAllViews()
        val buttons = listOf(
            "Takeoff" to { c.testTakeoff() }, "Forward" to { c.testForward() }, "Land" to { c.testLand() },
            "Yaw L" to { c.testYawLeft() }, "Stop" to { c.testStop() }, "Yaw R" to { c.testYawRight() },
            "Left" to { c.testLeft() }, "Back" to { c.testBack() }, "Right" to { c.testRight() },
            "Up" to { c.testUp() }, "Down" to { c.testDown() }, "E-Stop" to { c.testEmergency() },
        )
        for ((label, action) in buttons) {
            val b = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(ink)
                setPadding(0, 36, 0, 36)
                setBackgroundColor(Color.parseColor("#F1F3F4"))
                setOnClickListener { action() }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(6, 6, 6, 6)
            }
            grid.addView(b, lp)
        }
    }

    private fun buildLearn() {
        val body = findViewById<LinearLayout>(R.id.learnBody)
        body.removeAllViews()
        addLearnItem(body, "What is an ESP32?",
            "A $5–10 Wi-Fi microcontroller. Running our open relay firmware, it joins the drone’s own " +
                "Wi-Fi access point and forwards the control packets the phone sends it over USB — so the " +
                "phone can keep its normal network while still flying the drone.")
        addLearnItem(body, "How do I obtain one?",
            "Any ESP32-S3 or ESP32-C3/C6 dev board with native USB works (search “ESP32-S3 DevKitC” or " +
                "“Seeed XIAO ESP32C6”). Connect it to the phone with a USB-C cable or OTG adapter.")
        addLearnItem(body, "How do I install the relay firmware?",
            "Flash the firmware in this repo’s firmware/esp32_drone_link with PlatformIO:\n" +
                "  cd firmware/esp32_drone_link && pio run -t upload\n" +
                "Full steps are in firmware/FLASHING.md. Tap to open the repo.") { openUrl("https://github.com/commandAGI/commandagi-example-human-robot/blob/main/firmware/FLASHING.md") }
    }

    private fun addLearnItem(parent: LinearLayout, title: String, detail: String, onTapBody: (() -> Unit)? = null) {
        val header = TextView(this).apply {
            text = "▸  $title"
            textSize = 14f
            setTextColor(ink)
            setPadding(0, 24, 0, 24)
        }
        val bodyText = TextView(this).apply {
            text = detail
            textSize = 13f
            setTextColor(muted)
            setPadding(0, 0, 0, 16)
            visibility = View.GONE
            onTapBody?.let { setOnClickListener { it() } }
        }
        header.setOnClickListener {
            val open = bodyText.visibility == View.VISIBLE
            bodyText.visibility = if (open) View.GONE else View.VISIBLE
            header.text = (if (open) "▸  " else "▾  ") + title
        }
        parent.addView(header)
        parent.addView(bodyText)
    }

    // ── collapsible section helpers ───────────────────────────────────────────
    private fun wireCollapse(headerId: Int, bodyId: Int, title: String) {
        findViewById<TextView>(headerId).setOnClickListener {
            val body = findViewById<View>(bodyId)
            expand(headerId, bodyId, title, body.visibility != View.VISIBLE)
        }
    }

    private fun expand(headerId: Int, bodyId: Int, title: String, open: Boolean) {
        findViewById<View>(bodyId).visibility = if (open) View.VISIBLE else View.GONE
        findViewById<TextView>(headerId).text = (if (open) "▾  " else "▸  ") + title
    }

    // ── shared bits ───────────────────────────────────────────────────────────
    private fun styleSegment(tv: TextView, selected: Boolean) {
        if (selected) {
            tv.setBackgroundColor(ink); tv.setTextColor(Color.WHITE)
        } else {
            tv.setBackgroundColor(Color.TRANSPARENT); tv.setTextColor(ink)
        }
    }

    private fun promptApiKey(after: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.apiKey ?: "")
            hint = "cagi_…"
        }
        AlertDialog.Builder(this)
            .setTitle("CommandAGI API key")
            .setMessage("Paste an API key (operator scope) from your CommandAGI dashboard.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> prefs.apiKey = input.text.toString().trim(); after() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, 16, 0, 16)
    }

    private fun mask(key: String) = if (key.length <= 10) "••••" else key.take(8) + "…" + key.takeLast(4)
    private fun openUrl(url: String) =
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(usbReceiver, filter)
        }
        if (prefs.driver == Driver.DRONE) bridge?.refresh()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // The bridge is shared (DroneRelay) and stays open so the runtime screen keeps the link.
        bridge?.onEsp = null
        bridge?.onScan = null
        bridge?.onLink = null
    }
}
