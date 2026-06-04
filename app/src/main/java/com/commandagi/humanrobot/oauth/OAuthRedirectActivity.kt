package com.commandagi.humanrobot.oauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.commandagi.humanrobot.Prefs
import com.commandagi.humanrobot.SettingsActivity
import java.util.concurrent.Executors

/**
 * Catches the `commandagi-driver://oauth?code=…` redirect from the browser after the user approves
 * the connection, exchanges the code for an access token (PKCE), saves it, and returns to Settings.
 */
class OAuthRedirectActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val state = data?.getQueryParameter("state")
        val error = data?.getQueryParameter("error")

        if (error != null) { finishWith("Connection cancelled"); return }
        if (code == null) { finishWith("No authorization code returned"); return }
        if (state == null || state != prefs.oauthState) { finishWith("Security check failed (state mismatch)"); return }
        val verifier = prefs.pkceVerifier
        if (verifier.isNullOrBlank()) { finishWith("Missing PKCE verifier — try again"); return }

        val apiBase = prefs.baseUrl ?: com.commandagi.humanrobot.BuildConfig.COMMANDAGI_BASE_URL
        io.execute {
            try {
                val token = OAuthClient.exchange(apiBase, code, verifier)
                prefs.apiKey = token
                prefs.connectedViaOAuth = true
                prefs.pkceVerifier = null
                prefs.oauthState = null
                runOnUiThread { finishWith("Connected to CommandAGI") }
            } catch (e: Exception) {
                runOnUiThread { finishWith("Connect failed: ${e.message}") }
            }
        }
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
