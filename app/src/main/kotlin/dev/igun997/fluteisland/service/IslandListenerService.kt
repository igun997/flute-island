package dev.igun997.fluteisland.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.igun997.fluteisland.core.Alert
import dev.igun997.fluteisland.core.Decision
import dev.igun997.fluteisland.core.NotificationMapper
import dev.igun997.fluteisland.core.RawNotice
import dev.igun997.fluteisland.data.SettingsStore

/**
 * Feeds the island: every notification worth showing, and the active media session. The island window itself
 * lives in [IslandAccessibilityService].
 */
class IslandListenerService : NotificationListenerService() {
    private var media: MediaWatcher? = null
    private val takenOver = HashSet<String>()

    /** Last "title|text" per notification key, so re-posts of unchanged notifications stay quiet. */
    private val lastSeen = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        SettingsStore.init(this)
        IslandRuntime.setListenerConnected(true)
        IslandRuntime.cancelShellNotifications = {
            activeNotifications.filter { it.packageName == "com.android.shell" }.forEach { cancelNotification(it.key) }
        }
        media = MediaWatcher(this, ComponentName(this, IslandListenerService::class.java)) { info, controls ->
            if (SettingsStore.value.mediaEnabled) {
                IslandRuntime.mediaControls = controls
                IslandRuntime.controller.setMedia(info, IslandRuntime.now())
            }
        }.also { it.start() }
    }

    override fun onListenerDisconnected() {
        teardown()
        IslandRuntime.setListenerConnected(false)
        // Ask the system to reconnect; some OEMs unbind aggressively.
        runCatching { requestRebind(ComponentName(this, IslandListenerService::class.java)) }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        teardown()
        IslandRuntime.setListenerConnected(false)
        super.onDestroy()
    }

    private fun teardown() {
        media?.stop(); media = null
        IslandRuntime.mediaControls = null
        IslandRuntime.cancelShellNotifications = null
        IslandRuntime.controller.setMedia(null, IslandRuntime.now())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val set = SettingsStore.value
        if (!set.enabled || !set.notificationsEnabled) return

        val decision = NotificationMapper.decide(toRaw(sbn), packageName, set.blockedPackages, set.allApps, isLoudEnough(sbn))
        when (decision) {
            Decision.Ignore -> return
            Decision.Dismiss -> removeFromShade(sbn.key)
            is Decision.Quiet -> {
                if (!isNew(sbn.key, decision.alert)) return
                IslandRuntime.controller.recordOnly(decision.alert)
                removeFromShade(sbn.key)
            }
            is Decision.Show -> {
                if (!isNew(sbn.key, decision.alert)) return
                IslandRuntime.post(decision.alert, sbn.notification.contentIntent)
                // "Take over" (always, in every-app mode): the island is the only place it shows up. The tap action
                // was captured above, so tapping the island still opens the right chat.
                if (set.takeOver || set.allApps) removeFromShade(sbn.key)
            }
        }
    }

    /** Re-posts of an unchanged notification stay quiet. */
    private fun isNew(key: String, alert: Alert): Boolean {
        val signature = alert.title + "|" + alert.text
        if (lastSeen[key] == signature) return false
        lastSeen[key] = signature
        return true
    }

    private fun removeFromShade(key: String) {
        takenOver.add(key)
        runCatching { cancelNotification(key) }
            .onFailure { takenOver.remove(key); android.util.Log.w("IslandTakeover", "cancel failed for $key", it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSeen.remove(sbn.key)
        // We removed this one ourselves; it must stay in the island for its normal time.
        if (takenOver.remove(sbn.key)) return
        IslandRuntime.retract(sbn.key)
    }

    /** Silent / minimised notifications (importance < default) are not worth interrupting for. */
    private fun isLoudEnough(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        val map = runCatching { currentRanking }.getOrNull() ?: return true
        if (!map.getRanking(sbn.key, ranking)) return true
        return ranking.importance >= android.app.NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun toRaw(sbn: StatusBarNotification): RawNotice {
        val n = sbn.notification
        val e = n.extras
        val flags = n.flags
        return RawNotice(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appLabel(sbn.packageName),
            title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            category = n.category,
            isOngoing = flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isMediaStyle = e.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MediaStyle") == true,
            postedAt = sbn.postTime,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
