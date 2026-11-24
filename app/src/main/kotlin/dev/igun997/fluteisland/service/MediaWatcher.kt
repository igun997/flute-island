package dev.igun997.fluteisland.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.igun997.fluteisland.core.MediaInfo

/**
 * Follows the phone's active media sessions (Spotify, YouTube Music, podcasts...) and
 * publishes the most relevant one. Needs notification-listener access, which the host
 * service already has.
 */
class MediaWatcher(
    private val context: Context,
    private val listener: ComponentName,
    private val onChange: (MediaInfo?, MediaControls?) -> Unit,
) {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var started = false

    private var artKey: String? = null
    private var artBitmap: Bitmap? = null
    private var artColor = 0

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> setControllers(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = handler.post { refresh() }.let { }
    }

    fun start() {
        if (started) return
        started = true
        runCatching { manager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler) }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = emptyList()
        artKey = null; artBitmap = null
        onChange(null, null)
    }

    private fun refresh() {
        if (!started) return
        setControllers(runCatching { manager.getActiveSessions(listener) }.getOrDefault(emptyList()))
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback, ) } }
        controllers = list
        list.forEach { runCatching { it.registerCallback(callback, handler) } }
        publish()
    }

    private fun isActive(c: MediaController): Boolean = when (c.playbackState?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
        else -> false
    }

    private fun hasTitle(c: MediaController): Boolean = !c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()

    private fun publish() {
        if (!started) return
        val usable = controllers.filter { hasTitle(it) }
        // Playing beats paused; Spotify wins a tie; otherwise the most recently active (list order).
        val chosen = usable.filter { isActive(it) }.let { playing ->
            playing.firstOrNull { it.packageName == SPOTIFY } ?: playing.firstOrNull()
        } ?: usable.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        if (chosen == null) {
            onChange(null, null)
            return
        }
        onChange(info(chosen), controls(chosen))
    }

    private fun info(c: MediaController): MediaInfo {
        val md = c.metadata
        val st = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM)).orEmpty()
        val playing = isActive(c)
        val now = System.currentTimeMillis()
        val speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        val position = when {
            st == null -> 0L
            playing -> st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * speed).toLong()
            else -> st.position
        }.coerceAtLeast(0L)
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 0L

        val key = "${c.packageName}|$title|$artist"
        if (key != artKey) {
            artKey = key
            val raw = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            artBitmap = raw?.let { scaleForIsland(it) }
            artColor = raw?.let { averageColor(it) } ?: 0
        }
        return MediaInfo(
            packageName = c.packageName,
            appName = appLabel(c.packageName),
            title = title,
            artist = artist,
            playing = playing,
            positionMs = position.coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE),
            durationMs = duration,
            positionAt = now,
            speed = speed,
            art = artBitmap,
            artColor = artColor,
        )
    }

    private fun controls(c: MediaController) = object : MediaControls {
        override fun previous() = c.transportControls.skipToPrevious()
        override fun next() = c.transportControls.skipToNext()
        override fun togglePlayPause() {
            if (isActive(c)) c.transportControls.pause() else c.transportControls.play()
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun scaleForIsland(src: Bitmap): Bitmap {
        val side = 192
        if (src.width <= side && src.height <= side) return src
        val ratio = side.toFloat() / maxOf(src.width, src.height)
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun averageColor(src: Bitmap): Int = runCatching {
        Bitmap.createScaledBitmap(src, 1, 1, true).getPixel(0, 0) or (0xFF shl 24)
    }.getOrDefault(0)

    companion object {
        const val SPOTIFY = "com.spotify.music"
    }
}
