package dev.igun997.fluteisland.core

import org.json.JSONObject

object JiraParser {
    /** Parses the response of `GET /rest/api/3/search/jql`. Throws on malformed input. */
    fun parseSearch(json: String, baseUrl: String): List<Ticket> {
        val issues = JSONObject(json).optJSONArray("issues") ?: return emptyList()
        val base = baseUrl.trimEnd('/')
        return (0 until issues.length()).map { i ->
            val o = issues.getJSONObject(i)
            val key = o.getString("key")
            val f = o.optJSONObject("fields")
            Ticket(
                key = key,
                summary = f?.optString("summary").orEmpty(),
                status = f?.optJSONObject("status")?.optString("name").orEmpty(),
                priority = f?.optJSONObject("priority")?.optString("name").orEmpty(),
                url = "$base/browse/$key",
            )
        }
    }

    fun normalizeBaseUrl(input: String): String {
        val t = input.trim().trimEnd('/')
        return when {
            t.isEmpty() -> ""
            t.startsWith("http://") || t.startsWith("https://") -> t
            else -> "https://$t"
        }
    }

    const val DEFAULT_JQL = "status = Reopened AND assignee = currentUser() ORDER BY updated DESC"
}

object JiraDiff {
    /**
     * Tickets that are in [current] but were not in [previouslySeen]. A null
     * [previouslySeen] means this is the first poll: nothing is "new", we just seed.
     */
    fun newTickets(previouslySeen: Set<String>?, current: List<Ticket>): List<Ticket> =
        if (previouslySeen == null) emptyList() else current.filter { it.key !in previouslySeen }
}
