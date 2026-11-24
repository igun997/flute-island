package dev.igun997.fluteisland.core

/** Platform-free view of a StatusBarNotification, so the filtering rules are unit-testable. */
data class RawNotice(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val category: String?,
    val isOngoing: Boolean,
    val isGroupSummary: Boolean,
    val isMediaStyle: Boolean,
    val postedAt: Long,
)

object NotificationMapper {
    const val MAX_TEXT = 240
    const val MAX_TITLE = 80

    private val DROPPED_CATEGORIES = setOf(
        "transport", "progress", "service", "sys", "status", "navigation", "call", "alarm", "stopwatch", "location_sharing",
    )

    /** Packages that only ever produce system noise. */
    val DEFAULT_BLOCKED: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.providers.downloads",
        "com.samsung.android.lool",
        "com.samsung.android.app.tips",
        "com.google.android.gms",
    )

    fun sourceFor(packageName: String): Source = when (packageName) {
        "com.whatsapp", "com.whatsapp.w4b" -> Source.WHATSAPP
        else -> Source.OTHER
    }

    /** Returns the alert to show, or null when this notification must not reach the island. */
    fun map(raw: RawNotice, ownPackage: String, blocked: Set<String>): Alert? {
        if (raw.packageName == ownPackage) return null
        if (raw.packageName in blocked) return null
        if (raw.isOngoing || raw.isGroupSummary || raw.isMediaStyle) return null
        if (raw.category in DROPPED_CATEGORIES) return null

        val title = raw.title.clean(MAX_TITLE)
        // MessagingStyle puts the latest message in `text`; bigText is the fallback for long mail/news.
        val body = raw.text.clean(MAX_TEXT).ifEmpty { raw.bigText.clean(MAX_TEXT) }
        if (title.isEmpty() && body.isEmpty()) return null

        return Alert(
            id = raw.key,
            source = sourceFor(raw.packageName),
            appName = raw.appName,
            packageName = raw.packageName,
            title = title.ifEmpty { raw.appName },
            text = body,
            postedAt = raw.postedAt,
            actionKey = raw.key,
        )
    }

    private fun String?.clean(max: Int): String {
        val s = this?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return if (s.length <= max) s else s.take(max - 1).trimEnd() + "\u2026"
    }
}
