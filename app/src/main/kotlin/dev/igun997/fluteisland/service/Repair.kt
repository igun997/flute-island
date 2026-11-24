package dev.igun997.fluteisland.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Bringing the island back. When the process crashes, Android flags the accessibility service as crashed and will not
 * start it again by itself; the user has to switch it off and on. With the optional WRITE_SECURE_SETTINGS permission
 * (one adb command, see the README) the app can do that switch itself, which is what makes it self-healing.
 */
object Repair {
    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private var lastAttempt = 0L

    fun canSelfRepair(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun hostEnabledInSettings(context: Context): Boolean {
        val me = ComponentName(context, IslandAccessibilityService::class.java)
        return Settings.Secure.getString(context.contentResolver, KEY).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Switches the accessibility service off and on again. Returns false when we are not allowed to. */
    fun restartHost(context: Context): Boolean {
        if (!canSelfRepair(context)) return false
        val now = System.currentTimeMillis()
        if (now - lastAttempt < 15_000) return true // one attempt at a time
        lastAttempt = now
        val resolver = context.contentResolver
        val me = ComponentName(context, IslandAccessibilityService::class.java)
        val others = Settings.Secure.getString(resolver, KEY).orEmpty().split(':')
            .filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        return runCatching {
            Settings.Secure.putString(resolver, KEY, others.joinToString(":"))
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    Settings.Secure.putString(resolver, KEY, (others + me.flattenToString()).joinToString(":"))
                    Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
            }, 700)
        }.isSuccess
    }

    fun rebindListener(context: Context) {
        runCatching { NotificationListenerService.requestRebind(ComponentName(context, IslandListenerService::class.java)) }
    }

    /**
     * If the accessibility service is switched on in settings but is not running a few seconds later, it was crashed or
     * killed: restart it (when allowed). Called whenever the app wakes up.
     */
    fun healAfter(context: Context, delayMs: Long = 25_000) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (hostEnabledInSettings(context) && !IslandRuntime.hostConnected.value) restartHost(context)
        }, delayMs)
    }
}
