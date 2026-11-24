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

/** What the island does with one notification. */
sealed interface Decision {
    /** Pop it up in the island. */
    data class Show(val alert: Alert) : Decision

    /** Too quiet to interrupt for: file it under Recent, no popup. (Only with "every app uses the island".) */
    data class Quiet(val alert: Alert) : Decision

    /** Nothing to show (empty group header...), but it must not stay in the shade either. */
    data object Dismiss : Decision

    /** Not ours to touch: leave the notification exactly where it is. */
    data object Ignore : Decision
}

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

    /**
     * The island's policy. Ongoing things (calls, music players, downloads, foreground services) are never touched:
     * they are live controls and Android would not let us remove them anyway. With [allApps] every other app goes
     * through the island, the block list is ignored, and quiet notifications are filed under Recent instead of
     * being dropped, so the island is the only place notifications appear.
     */
    fun decide(raw: RawNotice, ownPackage: String, blocked: Set<String>, allApps: Boolean, loud: Boolean): Decision {
        if (raw.packageName == ownPackage) return Decision.Ignore
        if (raw.isOngoing || raw.isMediaStyle || raw.category in DROPPED_CATEGORIES) return Decision.Ignore
        if (!allApps && raw.packageName in blocked) return Decision.Ignore
        if (raw.isGroupSummary) return if (allApps) Decision.Dismiss else Decision.Ignore
        val alert = map(raw, ownPackage, emptySet()) ?: return Decision.Ignore
        return when {
            loud -> Decision.Show(alert)
            allApps -> Decision.Quiet(alert)
            else -> Decision.Ignore
        }
    }

    private fun String?.clean(max: Int): String {
        val s = this?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return if (s.length <= max) s else s.take(max - 1).trimEnd() + "\u2026"
    }
}
