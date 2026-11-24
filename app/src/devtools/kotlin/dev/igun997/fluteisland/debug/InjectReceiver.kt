package dev.igun997.fluteisland.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import dev.igun997.fluteisland.core.Alert
import dev.igun997.fluteisland.core.MediaInfo
import dev.igun997.fluteisland.core.Mode
import dev.igun997.fluteisland.core.Panel
import dev.igun997.fluteisland.core.Source
import dev.igun997.fluteisland.core.ThemeMode
import dev.igun997.fluteisland.core.Ticket
import dev.igun997.fluteisland.core.Weather
import dev.igun997.fluteisland.core.WeatherCodes
import dev.igun997.fluteisland.data.JiraSession
import dev.igun997.fluteisland.data.SettingsStore
import dev.igun997.fluteisland.service.IslandRuntime

/**
 * Debug-only remote control, used by scripts/e2e.sh:
 *   adb shell am broadcast -n dev.igun997.fluteisland/.debug.InjectReceiver \
 *       -a dev.igun997.fluteisland.debug.INJECT --es kind notice --es app whatsapp --es title Rina --es text "Halo"
 */
class InjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SettingsStore.init(context)
        val c = IslandRuntime.controller
        val now = IslandRuntime.now()
        fun s(name: String, default: String = "") = intent.getStringExtra(name) ?: default
        when (s("kind")) {
            "notice" -> {
                val source = if (s("app", "other").equals("whatsapp", true)) Source.WHATSAPP else Source.OTHER
                val pkg = if (source == Source.WHATSAPP) "com.whatsapp" else s("pkg", "com.android.chrome")
                IslandRuntime.post(
                    Alert(
                        id = "debug:" + s("id", System.nanoTime().toString()), source = source,
                        appName = s("appName", if (source == Source.WHATSAPP) "WhatsApp" else "Chrome"),
                        packageName = pkg, title = s("title", "Rina"), text = s("text", "Besok jadi ketemu?"), postedAt = now,
                    ),
                )
            }
            "jira" -> IslandRuntime.post(
                Alert(
                    id = "jira:" + s("key", "OPS-42"), source = Source.JIRA, appName = "Jira",
                    packageName = "com.atlassian.android.jira.core", title = s("key", "OPS-42") + " reopened",
                    text = s("summary", "Checkout fails on Samsung A-series"), postedAt = now,
                    actionKey = "https://example.atlassian.net/browse/" + s("key", "OPS-42"),
                ),
            )
            "media" -> c.setMedia(
                MediaInfo(
                    "com.spotify.music", "Spotify", s("title", "Sunset Over Lagoon"), s("artist", "Flute Island"),
                    s("playing", "true") == "true", 42_000, 215_000, now, 1f, albumArt(), 0xFF19C3B1.toInt(),
                ),
                now,
            )
            "nomedia" -> c.setMedia(null, now)
            "weather" -> c.setWeather(
                Weather(s("temp", "29.4").toDouble(), WeatherCodes.kind(s("code", "2").toInt()), s("day", "true") == "true", s("city", "Bandung"), 31.0, 22.0, now),
            )
            "tickets" -> c.setTickets(
                (1..s("count", "3").toInt()).map {
                    Ticket("OPS-${40 + it}", listOf("Login loops on retry", "Checkout fails on Samsung A-series", "Push token not refreshed", "Typo on invoice PDF")[(it - 1) % 4], "Reopened", listOf("High", "Medium", "Low")[(it - 1) % 3], "https://example.atlassian.net/browse/OPS-${40 + it}")
                },
            )
            "mode" -> SettingsStore.update { it.copy(mode = if (s("value") == "tools") Mode.TOOLS else Mode.NORMAL) }
            "theme" -> SettingsStore.update { it.copy(themeMode = ThemeMode.valueOf(s("value", "AUTO").uppercase())) }
            "panel" -> c.setPanel(Panel.valueOf(s("value", "NONE").uppercase()), now)
            "takeover" -> SettingsStore.update { it.copy(takeOver = s("value", "true") == "true") }
            "dump" -> {
                val st = c.snapshot
                android.util.Log.i(
                    "E2E",
                    "state panel=${st.panel} current=${st.current?.title} queue=${st.queue.size} recent=${st.recent.size} tickets=${st.tickets.joinToString(",") { it.key }} " +
                        "media=${st.media?.title} playing=${st.media?.playing} suppressed=${st.suppressed} mode=${st.mode}",
                )
            }
            "clearrecent" -> c.clearRecent(now)
            "cleanshell" -> IslandRuntime.cancelShellNotifications?.invoke()
            "allapps" -> SettingsStore.update { it.copy(allApps = s("value", "true") == "true") }
            "block" -> SettingsStore.update { it.copy(blockedPackages = it.blockedPackages + s("value")) }
            "unblock" -> SettingsStore.update { it.copy(blockedPackages = it.blockedPackages - s("value")) }
            "cover" -> SettingsStore.update { it.copy(coverStatusBar = s("value", "true") == "true") }
            "quiet" -> SettingsStore.update { it.copy(quietMode = s("value", "true") == "true") }
            "scale" -> SettingsStore.update { it.copy(sizeScale = s("value", "1").toFloat()) }
            "clear" -> { c.clearAlerts(); c.setPanel(Panel.NONE, now) }
            "fakemedia" -> FakePlayer.start(context, s("title", "Sunset Over Lagoon"), s("artist", "Flute Island"))
            "stopfakemedia" -> FakePlayer.stop()
            "jirasignout" -> JiraSession.signOut(context)
            "jiratoken" -> JiraSession.saveApiToken(context, s("base"), s("email"), s("token"))
            "jirajql" -> SettingsStore.update { it.copy(jiraJql = s("value"), jiraRev = it.jiraRev + 1) }
        }
    }
}

/** A real MediaSession, so the e2e run exercises the same MediaSessionManager path Spotify would. */
object FakePlayer {
    private var session: MediaSession? = null
    private var playing = true

    fun start(context: Context, title: String, artist: String) {
        stop()
        val s = MediaSession(context.applicationContext, "fluteisland-fake")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPause() { playing = false; publish(s) }
            override fun onPlay() { playing = true; publish(s) }
            override fun onSkipToNext() { publishMetadata(s, "Next Track", artist) }
            override fun onSkipToPrevious() { publishMetadata(s, "Previous Track", artist) }
        })
        session = s
        playing = true
        publishMetadata(s, title, artist)
        s.isActive = true
    }

    fun stop() {
        session?.release()
        session = null
    }

    private fun publishMetadata(s: MediaSession, title: String, artist: String) {
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 215_000)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumArt())
                .build(),
        )
        publish(s)
    }

    private fun publish(s: MediaSession) {
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 42_000, if (playing) 1f else 0f)
                .build(),
        )
    }
}

internal fun albumArt(): Bitmap {
    val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, 160f, 160f, 0xFF19C3B1.toInt(), 0xFFFF7A59.toInt(), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, 160f, 160f, p)
    p.shader = null
    p.color = 0xFFF6E7C8.toInt()
    canvas.drawCircle(80f, 118f, 46f, p)
    return bmp
}
