package dev.igun997.fluteisland.ui.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.igun997.fluteisland.service.IslandAccessibilityService
import dev.igun997.fluteisland.service.IslandListenerService

object Perms {
    /** The accessibility service that hosts the island is switched on in system settings. */
    fun islandHostEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(ctx, IslandAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun listenerGranted(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun openAccessibilitySettings(ctx: Context) = launch(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** Android 13+ greys out accessibility for apps installed from a file until this is allowed in App info. */
    fun openAppInfo(ctx: Context) = launch(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    fun openListenerSettings(ctx: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(ctx, IslandListenerService::class.java).flattenToString(),
                )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        if (!launch(ctx, intent)) launch(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openBatterySettings(ctx: Context) = launch(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    private fun launch(ctx: Context, intent: Intent): Boolean =
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
