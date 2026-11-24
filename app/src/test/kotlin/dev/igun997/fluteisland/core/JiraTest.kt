package dev.igun997.fluteisland.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JiraTest {
    private val json = """{"issues":[
        {"key":"OPS-12","fields":{"summary":"Login loops","status":{"name":"Reopened"},"priority":{"name":"High"}}},
        {"key":"OPS-7","fields":{"summary":"Typo"}}],"isLast":true}"""

    @Test
    fun parsesIssuesWithMissingFields() {
        val t = JiraParser.parseSearch(json, "https://acme.atlassian.net/")
        assertEquals(2, t.size)
        assertEquals("OPS-12", t[0].key)
        assertEquals("Login loops", t[0].summary)
        assertEquals("Reopened", t[0].status)
        assertEquals("High", t[0].priority)
        assertEquals("https://acme.atlassian.net/browse/OPS-12", t[0].url)
        assertEquals("", t[1].status)
    }

    @Test
    fun emptyOrMissingIssuesIsEmptyList() {
        assertTrue(JiraParser.parseSearch("""{"issues":[]}""", "https://a").isEmpty())
        assertTrue(JiraParser.parseSearch("""{}""", "https://a").isEmpty())
    }

    @Test
    fun normalizesBaseUrl() {
        assertEquals("https://acme.atlassian.net", JiraParser.normalizeBaseUrl(" acme.atlassian.net/ "))
        assertEquals("http://localhost:8089", JiraParser.normalizeBaseUrl("http://localhost:8089/"))
        assertEquals("", JiraParser.normalizeBaseUrl("  "))
    }

    @Test
    fun firstPollSeedsWithoutAlerting() {
        assertTrue(JiraDiff.newTickets(null, listOf(ticket("A-1"))).isEmpty())
    }

    @Test
    fun laterPollsReportOnlyUnseenTickets() {
        val fresh = JiraDiff.newTickets(setOf("A-1"), listOf(ticket("A-1"), ticket("A-2")))
        assertEquals(listOf("A-2"), fresh.map { it.key })
    }
}
