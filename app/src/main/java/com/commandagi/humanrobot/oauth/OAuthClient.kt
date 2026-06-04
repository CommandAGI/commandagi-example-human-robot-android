package com.commandagi.humanrobot.oauth

import android.net.Uri
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Connect to CommandAGI account" — OAuth 2.0 Authorization Code flow with PKCE (RFC 7636). The app
 * is a public client: no secret, just a code_verifier/challenge pair. The browser handles login +
 * consent; the issued access token is a normal CommandAGI key the app uses as a Bearer.
 */
object OAuthClient {
    const val CLIENT_ID = "personal-driver"
    const val REDIRECT_URI = "commandagi-driver://oauth"
    const val SCOPE = "operator"

    data class Pkce(val verifier: String, val challenge: String)

    fun newPkce(): Pkce {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val verifier = b64url(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, b64url(digest))
    }

    fun randomState(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b); return b64url(b)
    }

    /** The web authorize URL the browser opens. [webBase] is e.g. https://commandagi.com. */
    fun authorizeUrl(webBase: String, challenge: String, state: String): Uri =
        Uri.parse(webBase.trimEnd('/') + "/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

    /** Exchange the authorization code for an access token. [apiBase] e.g. https://api.commandagi.com. */
    fun exchange(apiBase: String, code: String, verifier: String): String {
        val body = JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("client_id", CLIENT_ID)
            .put("redirect_uri", REDIRECT_URI)
            .put("code_verifier", verifier)
            .toString()
        val req = Request.Builder()
            .url(apiBase.trimEnd('/') + "/oauth/token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        OkHttpClient().newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) throw RuntimeException("token exchange ${res.code}: $text")
            return JSONObject(text).getString("access_token")
        }
    }

    /** Derive the web origin (where the consent screen lives) from the API base. */
    fun webBaseFrom(apiBase: String): String =
        apiBase.replace("https://api-dev.", "https://dev.").replace("https://api.", "https://")

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
