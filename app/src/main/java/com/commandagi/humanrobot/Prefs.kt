package com.commandagi.humanrobot

import android.content.Context

/** What this phone is driving. The UX mirrors the squircle-vs-rounded-rect preference: a segmented
 *  toggle whose choice swaps the settings shown below it. */
enum class Driver { HUMAN, DRONE;
    companion object {
        fun from(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: HUMAN
    }
}

/** Single source of truth for everything the app persists. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("commandagi", Context.MODE_PRIVATE)

    // Connection
    var apiKey: String?
        get() = sp.getString("api_key", null)?.takeIf { it.isNotBlank() }
        set(v) = sp.edit().putString("api_key", v?.trim()).apply()
    var baseUrl: String?
        get() = sp.getString("base_url", null)?.takeIf { it.isNotBlank() }
        set(v) = sp.edit().putString("base_url", v?.trim()).apply()

    // What am I driving?
    var driver: Driver
        get() = Driver.from(sp.getString("drive_mode", Driver.HUMAN.name))
        set(v) = sp.edit().putString("drive_mode", v.name).apply()

    // Human-experience options
    var dictate: Boolean
        get() = sp.getBoolean("human_dictate", true)
        set(v) = sp.edit().putBoolean("human_dictate", v).apply()
    var keepAwake: Boolean
        get() = sp.getBoolean("human_keep_awake", true)
        set(v) = sp.edit().putBoolean("human_keep_awake", v).apply()
    var haptics: Boolean
        get() = sp.getBoolean("human_haptics", true)
        set(v) = sp.edit().putBoolean("human_haptics", v).apply()
    /** "back" or "front" */
    var cameraFacing: String
        get() = sp.getString("camera_facing", "back") ?: "back"
        set(v) = sp.edit().putString("camera_facing", v).apply()

    // Drone options (last successfully joined AP, for convenience)
    var lastSsid: String?
        get() = sp.getString("drone_ssid", null)
        set(v) = sp.edit().putString("drone_ssid", v).apply()

    // OAuth "Connect to CommandAGI account" — transient PKCE state + connection flag
    var pkceVerifier: String?
        get() = sp.getString("pkce_verifier", null)
        set(v) = sp.edit().putString("pkce_verifier", v).apply()
    var oauthState: String?
        get() = sp.getString("oauth_state", null)
        set(v) = sp.edit().putString("oauth_state", v).apply()
    var connectedViaOAuth: Boolean
        get() = sp.getBoolean("connected_oauth", false)
        set(v) = sp.edit().putBoolean("connected_oauth", v).apply()
}
