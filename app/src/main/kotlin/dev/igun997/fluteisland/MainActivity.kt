package dev.igun997.fluteisland

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.igun997.fluteisland.data.JiraSession
import dev.igun997.fluteisland.data.SettingsStore
import dev.igun997.fluteisland.ui.control.ControlRoot
import dev.igun997.fluteisland.ui.control.JiraSignIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        enableEdgeToEdge()
        setContent { ControlRoot() }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** The token proxy sends the browser back here as fluteisland://jira/callback?code=...&state=... */
    private fun handle(intent: Intent?) {
        val link = intent?.data?.takeIf { it.scheme == "fluteisland" } ?: return
        setIntent(Intent(this, MainActivity::class.java)) // don't replay the link on rotation
        JiraSignIn.begin()
        lifecycleScope.launch { JiraSignIn.finish(JiraSession.completeSignIn(applicationContext, link.toString())) }
    }
}
