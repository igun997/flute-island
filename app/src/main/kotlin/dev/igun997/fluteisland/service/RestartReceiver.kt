package dev.igun997.fluteisland.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot or an app update, make sure the island actually came back. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repair.rebindListener(context)
        Repair.healAfter(context.applicationContext, 10_000)
    }
}
