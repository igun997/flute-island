package dev.igun997.fluteisland.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JiraOAuthTest {
    @Test
    fun authorizeUrlCarriesEverythingAtlassianRequires() {
        val url = JiraOAuth.authorizeUrl("cid", "https://proxy.example/jira/callback", "st4te")
        assertTrue(url.startsWith("https://auth.atlassian.com/authorize?"))
        assertTrue("audience=api.atlassian.com" in url)
        assertTrue("client_id=cid" in url)
        assertTrue("scope=read%3Ajira-work%20offline_access" in url)
        assertTrue("redirect_uri=https%3A%2F%2Fproxy.example%2Fjira%2Fcallback" in url)
        assertTrue("state=st4te" in url)
        assertTrue("response_type=code" in url)
        assertTrue("prompt=consent" in url)
    }

    @Test
    fun callbackWithMatchingStateYieldsCode() {
        val r = JiraOAuth.parseCallback("fluteisland://jira/callback?code=abc%20123&state=S", "S")
        assertEquals(OAuthCallback.Code("abc 123"), r)
    }

    @Test
    fun callbackWithWrongOrMissingStateIsRejected() {
        assertEquals(OAuthCallback.Invalid, JiraOAuth.parseCallback("fluteisland://jira/callback?code=x&state=evil", "S"))
        assertEquals(OAuthCallback.Invalid, JiraOAuth.parseCallback("fluteisland://jira/callback?code=x", "S"))
        assertEquals(OAuthCallback.Invalid, JiraOAuth.parseCallback("fluteisland://jira/callback?code=x&state=S", null))
        assertEquals(OAuthCallback.Invalid, JiraOAuth.parseCallback("fluteisland://jira/callback?code=x&state=S", ""))
    }

    @Test
    fun callbackFromAnotherUriIsRejected() {
        assertEquals(OAuthCallback.Invalid, JiraOAuth.parseCallback("https://evil.example/?code=x&state=S", "S"))
    }

    @Test
    fun deniedConsentIsReportedWithReason() {
        val r = JiraOAuth.parseCallback("fluteisland://jira/callback?error=access_denied&error_description=User+said+no&state=S", "S")
        assertEquals(OAuthCallback.Denied("User said no"), r)
    }

    @Test
    fun callbackWithoutCodeIsInvalid() {
        assertEquals(OAuthCallback.Invalid, JiraOAuth.parseCallback("fluteisland://jira/callback?state=S", "S"))
    }

    @Test
    fun parsesTokensAndComputesExpiry() {
        val t = JiraOAuth.parseTokens("""{"access_token":"a","refresh_token":"r","expires_in":3600,"scope":"x"}""", 1_000)
        assertEquals("a", t.accessToken)
        assertEquals("r", t.refreshToken)
        assertEquals(1_000 + 3_600_000L, t.expiresAt)
    }

    @Test
    fun refreshTokenIsOptional() {
        assertNull(JiraOAuth.parseTokens("""{"access_token":"a","expires_in":60}""", 0).refreshToken)
    }

    @Test
    fun expiryHonoursTheSafetyMargin() {
        val t = OAuthTokens("a", "r", expiresAt = 100_000)
        assertFalse(JiraOAuth.isExpired(t, 39_999))
        assertTrue(JiraOAuth.isExpired(t, 40_000))
    }

    @Test
    fun parsesAccessibleResources() {
        val sites = JiraOAuth.parseSites("""[{"id":"c1","name":"acme","url":"https://acme.atlassian.net/","scopes":[]}]""")
        assertEquals(listOf(JiraSite("c1", "acme", "https://acme.atlassian.net")), sites)
    }

    @Test
    fun apiBaseUsesCloudId() {
        assertEquals("https://api.atlassian.com/ex/jira/c1", JiraOAuth.apiBase("c1"))
    }
}
