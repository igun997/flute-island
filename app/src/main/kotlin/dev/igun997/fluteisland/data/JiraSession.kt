package dev.igun997.fluteisland.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import dev.igun997.fluteisland.BuildConfig
import dev.igun997.fluteisland.core.JiraAuth
import dev.igun997.fluteisland.core.JiraOAuth
import dev.igun997.fluteisland.core.JiraParser
import dev.igun997.fluteisland.core.JiraSite
import dev.igun997.fluteisland.core.OAuthCallback
import dev.igun997.fluteisland.core.OAuthTokens
import dev.igun997.fluteisland.core.Ticket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Everything about being signed in to Jira: browser sign-in (OAuth 2.0 3LO through our
 * token proxy, so no client secret ships in the app), token refresh, and the search call.
 * The manual API-token path is kept as a fallback for sites that block third-party apps.
 */
object JiraSession {
    private const val S_ACCESS = "jira_access"
    private const val S_REFRESH = "jira_refresh"
    private const val S_EXPIRES = "jira_expires"
    private const val S_API_TOKEN = "jira_api_token"
    private const val P_STATE = "oauth_state"
    private const val P_SITES = "oauth_sites"

    private val refreshLock = Mutex()

    /** OAuth needs a registered Atlassian app + our proxy; both are build-time config. */
    val oauthAvailable: Boolean get() = BuildConfig.JIRA_CLIENT_ID.isNotBlank() && BuildConfig.JIRA_AUTH_PROXY.isNotBlank()

    private fun proxyCallback() = BuildConfig.JIRA_AUTH_PROXY.trimEnd('/') + "/jira/callback"

    // --- OAuth sign-in -------------------------------------------------------------------

    /** Opens the Atlassian consent page in the browser. */
    fun startSignIn(context: Context) {
        check(oauthAvailable) { "Jira sign-in is not configured in this build." }
        val state = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        prefs(context).edit().putString(P_STATE, state).apply()
        val url = JiraOAuth.authorizeUrl(BuildConfig.JIRA_CLIENT_ID, proxyCallback(), state)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    sealed interface SignInResult {
        data class Connected(val sites: List<JiraSite>) : SignInResult
        data class Failed(val message: String) : SignInResult
    }

    /** Handles the `fluteisland://jira/callback` deep link. */
    suspend fun completeSignIn(context: Context, link: String): SignInResult {
        val expected = prefs(context).getString(P_STATE, null)
        prefs(context).edit().remove(P_STATE).apply() // single use
        return when (val cb = JiraOAuth.parseCallback(link, expected)) {
            OAuthCallback.Invalid -> SignInResult.Failed("That sign-in link was not valid. Please try again.")
            is OAuthCallback.Denied -> SignInResult.Failed("Access was not granted: ${cb.reason}")
            is OAuthCallback.Code -> runCatching {
                val body = JSONObject().put("code", cb.code).put("redirect_uri", proxyCallback()).toString()
                val tokens = JiraOAuth.parseTokens(Http.postJson(BuildConfig.JIRA_AUTH_PROXY.trimEnd('/') + "/jira/token", body), System.currentTimeMillis())
                saveTokens(context, tokens)
                val sites = JiraOAuth.parseSites(
                    Http.get("${JiraOAuth.API_ROOT}/oauth/token/accessible-resources", mapOf("Authorization" to "Bearer ${tokens.accessToken}")),
                )
                if (sites.isEmpty()) {
                    clearTokens(context)
                    SignInResult.Failed("This Atlassian account has no Jira site we can read.")
                } else {
                    prefs(context).edit().putString(P_SITES, sitesToJson(sites)).apply()
                    if (sites.size == 1) selectSite(context, sites.first())
                    SignInResult.Connected(sites)
                }
            }.getOrElse { SignInResult.Failed(describe(it)) }
        }
    }

    fun pendingSites(context: Context): List<JiraSite> =
        prefs(context).getString(P_SITES, null)?.let { runCatching { JiraOAuth.parseSites(it) }.getOrNull() }.orEmpty()

    fun selectSite(context: Context, site: JiraSite) {
        SettingsStore.update {
            it.copy(
                jiraAuth = JiraAuth.OAUTH, jiraBaseUrl = site.url, jiraCloudId = site.id, jiraEmail = "",
                jiraAccountLabel = site.name.ifBlank { site.url.removePrefix("https://") },
                jiraNeedsReauth = false, jiraRev = it.jiraRev + 1,
            )
        }
    }

    // --- API-token fallback ---------------------------------------------------------------

    fun saveApiToken(context: Context, baseUrl: String, email: String, token: String) {
        Secrets.put(context, S_API_TOKEN, token)
        clearOAuthTokens(context)
        val base = JiraParser.normalizeBaseUrl(baseUrl)
        SettingsStore.update {
            it.copy(
                jiraAuth = JiraAuth.API_TOKEN, jiraBaseUrl = base, jiraEmail = email.trim(), jiraCloudId = "",
                jiraAccountLabel = email.trim(), jiraNeedsReauth = false, jiraRev = it.jiraRev + 1,
            )
        }
    }

    fun signOut(context: Context) {
        clearTokens(context)
        Secrets.put(context, S_API_TOKEN, null)
        prefs(context).edit().remove(P_SITES).apply()
        SettingsStore.update {
            it.copy(
                jiraAuth = JiraAuth.NONE, jiraBaseUrl = "", jiraEmail = "", jiraCloudId = "", jiraAccountLabel = "",
                jiraNeedsReauth = false, jiraRev = it.jiraRev + 1,
            )
        }
    }

    // --- Search --------------------------------------------------------------------------

    suspend fun search(context: Context, s: Settings = SettingsStore.value, max: Int = 20): List<Ticket> {
        val query = "jql=${URLEncoder.encode(s.jiraJql.ifBlank { JiraParser.DEFAULT_JQL }, "UTF-8")}&fields=summary,status,priority&maxResults=$max"
        return when (s.jiraAuth) {
            JiraAuth.NONE -> emptyList()
            JiraAuth.API_TOKEN -> {
                val token = Secrets.get(context, S_API_TOKEN) ?: throw IllegalStateException("The Jira API token is missing. Enter it again.")
                val auth = Base64.encodeToString("${s.jiraEmail}:$token".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                JiraParser.parseSearch(Http.get("${s.jiraBaseUrl}/rest/api/3/search/jql?$query", mapOf("Authorization" to "Basic $auth")), s.jiraBaseUrl)
            }
            JiraAuth.OAUTH -> {
                val url = "${JiraOAuth.apiBase(s.jiraCloudId)}/rest/api/3/search/jql?$query"
                var access = accessToken(context)
                val body = try {
                    Http.get(url, mapOf("Authorization" to "Bearer $access"))
                } catch (e: HttpException) {
                    if (e.code != 401) throw e
                    access = refresh(context, force = true)
                    Http.get(url, mapOf("Authorization" to "Bearer $access"))
                }
                JiraParser.parseSearch(body, s.jiraBaseUrl)
            }
        }
    }

    /** Human-readable reason for a failed call. Never includes credentials. */
    fun describe(e: Throwable): String = when {
        e is HttpException && e.code == 401 -> "Jira rejected the sign-in (401)."
        e is HttpException && e.code == 403 -> "Access denied (403). Check the permissions."
        e is HttpException && e.code == 400 -> "Jira rejected the request (400). Check the JQL."
        e is HttpException -> "The server answered ${e.message}."
        e is java.net.UnknownHostException -> "Can't reach the server. Check your connection and the Jira URL."
        e is java.net.SocketTimeoutException -> "The server took too long to answer."
        else -> e.message?.take(120) ?: "Unknown error."
    }

    // --- Token storage / refresh -----------------------------------------------------------

    private suspend fun accessToken(context: Context): String {
        val tokens = loadTokens(context) ?: throw ReauthRequired()
        return if (JiraOAuth.isExpired(tokens, System.currentTimeMillis())) refresh(context, force = false) else tokens.accessToken
    }

    class ReauthRequired : Exception("Please sign in to Jira again.")

    /** Exchanges the refresh token (through the proxy) for a new access token. Atlassian rotates refresh tokens. */
    private suspend fun refresh(context: Context, force: Boolean): String = refreshLock.withLock {
        val current = loadTokens(context) ?: throw ReauthRequired()
        // Another caller may have refreshed while we waited for the lock.
        if (!force && !JiraOAuth.isExpired(current, System.currentTimeMillis())) return current.accessToken
        val refreshToken = current.refreshToken ?: markReauth()
        try {
            val body = JSONObject().put("refresh_token", refreshToken).toString()
            val fresh = JiraOAuth.parseTokens(Http.postJson(BuildConfig.JIRA_AUTH_PROXY.trimEnd('/') + "/jira/refresh", body), System.currentTimeMillis())
            saveTokens(context, fresh.copy(refreshToken = fresh.refreshToken ?: refreshToken))
            fresh.accessToken
        } catch (e: HttpException) {
            if (e.code in 400..403) markReauth() else throw e
        }
    }

    private fun markReauth(): Nothing {
        SettingsStore.update { it.copy(jiraNeedsReauth = true, jiraRev = it.jiraRev + 1) }
        throw ReauthRequired()
    }

    private fun loadTokens(context: Context): OAuthTokens? {
        val access = Secrets.get(context, S_ACCESS) ?: return null
        return OAuthTokens(access, Secrets.get(context, S_REFRESH), Secrets.get(context, S_EXPIRES)?.toLongOrNull() ?: 0L)
    }

    private fun saveTokens(context: Context, t: OAuthTokens) {
        Secrets.put(context, S_ACCESS, t.accessToken)
        Secrets.put(context, S_REFRESH, t.refreshToken)
        Secrets.put(context, S_EXPIRES, t.expiresAt.toString())
    }

    private fun clearOAuthTokens(context: Context) = clearTokens(context)

    private fun clearTokens(context: Context) {
        Secrets.put(context, S_ACCESS, null)
        Secrets.put(context, S_REFRESH, null)
        Secrets.put(context, S_EXPIRES, null)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("jira_oauth", Context.MODE_PRIVATE)

    private fun sitesToJson(sites: List<JiraSite>): String =
        org.json.JSONArray(sites.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url) }).toString()
}
