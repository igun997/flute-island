package dev.igun997.fluteisland.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class OAuthTokens(val accessToken: String, val refreshToken: String?, val expiresAt: Long)

data class JiraSite(val id: String, val name: String, val url: String)

sealed interface OAuthCallback {
    data class Code(val code: String) : OAuthCallback
    data class Denied(val reason: String) : OAuthCallback
    data object Invalid : OAuthCallback
}

/** Platform-free pieces of Atlassian OAuth 2.0 (3LO) so they can be unit tested. */
object JiraOAuth {
    const val SCOPES = "read:jira-work offline_access"
    const val CALLBACK_URI = "fluteisland://jira/callback"
    const val API_ROOT = "https://api.atlassian.com"

    /** Refresh slightly early so a request never goes out with a token about to expire. */
    const val EXPIRY_SKEW_MS = 60_000L

    fun authorizeUrl(clientId: String, redirectUri: String, state: String): String =
        "https://auth.atlassian.com/authorize?audience=api.atlassian.com" +
            "&client_id=${enc(clientId)}&scope=${enc(SCOPES)}&redirect_uri=${enc(redirectUri)}" +
            "&state=${enc(state)}&response_type=code&prompt=consent"

    /** Interprets the deep link the proxy bounced back to the app. */
    fun parseCallback(url: String, expectedState: String?): OAuthCallback {
        if (!url.startsWith(CALLBACK_URI)) return OAuthCallback.Invalid
        val q = queryOf(url)
        if (expectedState.isNullOrEmpty() || q["state"] != expectedState) return OAuthCallback.Invalid
        q["error"]?.let { return OAuthCallback.Denied(q["error_description"] ?: it) }
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: return OAuthCallback.Invalid
        return OAuthCallback.Code(code)
    }

    fun parseTokens(json: String, now: Long): OAuthTokens {
        val o = JSONObject(json)
        return OAuthTokens(
            accessToken = o.getString("access_token"),
            refreshToken = o.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresAt = now + o.optLong("expires_in", 3600) * 1000,
        )
    }

    fun parseSites(json: String): List<JiraSite> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            JiraSite(o.getString("id"), o.optString("name"), o.getString("url").trimEnd('/'))
        }
    }

    fun apiBase(cloudId: String) = "$API_ROOT/ex/jira/$cloudId"

    fun isExpired(tokens: OAuthTokens, now: Long) = now >= tokens.expiresAt - EXPIRY_SKEW_MS

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun queryOf(url: String): Map<String, String> {
        val raw = url.substringAfter('?', "").substringBefore('#')
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }
}
