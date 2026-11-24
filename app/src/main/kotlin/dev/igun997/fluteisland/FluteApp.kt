package dev.igun997.fluteisland

import android.app.Application
import dev.igun997.fluteisland.data.CrashGuard
import dev.igun997.fluteisland.data.SettingsStore

class FluteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        CrashGuard.install(this)
    }
}
