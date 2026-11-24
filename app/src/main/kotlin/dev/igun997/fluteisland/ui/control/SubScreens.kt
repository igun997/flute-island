@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.igun997.fluteisland.ui.control

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.igun997.fluteisland.core.City
import dev.igun997.fluteisland.core.JiraAuth
import dev.igun997.fluteisland.core.JiraParser
import dev.igun997.fluteisland.core.JiraSite
import dev.igun997.fluteisland.data.JiraSession
import dev.igun997.fluteisland.data.SettingsStore
import dev.igun997.fluteisland.data.WeatherRepo
import dev.igun997.fluteisland.service.EngineStatus
import dev.igun997.fluteisland.service.IslandRuntime
import dev.igun997.fluteisland.ui.Glyphs
import dev.igun997.fluteisland.ui.island.Anim
import dev.igun997.fluteisland.ui.island.GlyphIcon
import dev.igun997.fluteisland.ui.island.LottieIcon
import dev.igun997.fluteisland.ui.island.rememberAppIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bridges the OAuth deep link (handled in MainActivity) to the Jira screen. */
object JiraSignIn {
    sealed interface State {
        data object Idle : State
        data object Working : State
        data class Done(val result: JiraSession.SignInResult) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun begin() { _state.value = State.Working }
    fun finish(result: JiraSession.SignInResult) { _state.value = State.Done(result) }
    fun consume() { _state.value = State.Idle }
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    val c = LocalControlColors.current
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(c.surface).clickable(onClickLabel = "Back", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyphs.ArrowBack, c.text, 22) }
        Spacer(Modifier.width(14.dp))
        Text(title, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SubScroll(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SubHeader(title, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

// --- Apps ------------------------------------------------------------------------------------

private data class AppEntry(val pkg: String, val label: String)

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val c = LocalControlColors.current
    val ctx = LocalContext.current
    val settings by SettingsStore.flow.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val apps by produceState(emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val pinned = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.instagram.android", "com.google.android.gm", "com.spotify.music")
            pm.queryIntentActivities(launcher, 0)
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                .sortedWith(compareBy({ pinned.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.label.lowercase() }))
        }
    }
    val shown = remember(apps, query) { apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) } }

    Column(Modifier.fillMaxSize()) {
        SubHeader("Apps", onBack)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Field(query, { query = it }, "Search apps") }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(shown, key = { it.pkg }) { app ->
                val allowed = app.pkg !in settings.blockedPackages
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .clickable { SettingsStore.update { s -> s.copy(blockedPackages = if (allowed) s.blockedPackages + app.pkg else s.blockedPackages - app.pkg) } }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = rememberAppIcon(app.pkg)
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceHigh)) {
                        icon?.let { Image(it, null, Modifier.fillMaxSize()) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(app.pkg, color = c.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Switch(
                        checked = allowed, onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.outline),
                    )
                }
            }
        }
    }
}

// --- City ------------------------------------------------------------------------------------

@Composable
fun CityScreen(onBack: () -> Unit) {
    val c = LocalControlColors.current
    val settings by SettingsStore.flow.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<City>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); error = null; return@LaunchedEffect }
        delay(400) // debounce
        busy = true
        try {
            results = WeatherRepo.search(query)
            error = if (results.isEmpty()) "No city found for \"${query.trim()}\"." else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = JiraSession.describe(e)
        } finally {
            busy = false
        }
    }

    SubScroll("City", onBack) {
        FluidCard {
            SectionHeader("Current", settings.city.name, Glyphs.Cloud)
        }
        Field(query, { query = it }, "Search a city", placeholder = "e.g. Bandung")
        AnimatedVisibility(busy) { Hint("Searching\u2026") }
        error?.let { Hint(it, c.coral) }
        results.forEach { city ->
            FluidCard(onClick = {
                SettingsStore.update { it.copy(city = city) }
                EngineStatus.weatherRefresh.tryEmit(Unit)
                onBack()
            }) {
                Text(city.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("%.2f, %.2f".format(city.lat, city.lon), color = c.muted, fontSize = 12.sp)
            }
        }
    }
}

// --- Jira ------------------------------------------------------------------------------------

private val JQL_PRESETS = listOf(
    "Mine, reopened" to JiraParser.DEFAULT_JQL,
    "Anyone, reopened" to "status = Reopened ORDER BY updated DESC",
    "Mine, not done" to "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC",
)

@Composable
fun JiraScreen(onBack: () -> Unit) {
    val c = LocalControlColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow.collectAsState()
    val signIn by JiraSignIn.state.collectAsState()
    val pollError by EngineStatus.jiraError.collectAsState()

    var showToken by rememberSaveable { mutableStateOf(settings.jiraAuth == JiraAuth.API_TOKEN || !JiraSession.oauthAvailable) }
    var base by rememberSaveable { mutableStateOf(settings.jiraBaseUrl.takeIf { settings.jiraAuth == JiraAuth.API_TOKEN } ?: "") }
    var email by rememberSaveable { mutableStateOf(settings.jiraEmail) }
    var token by rememberSaveable { mutableStateOf("") }
    var jql by rememberSaveable { mutableStateOf(settings.jiraJql) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var sites by remember { mutableStateOf(JiraSession.pendingSites(ctx)) }

    // A finished browser sign-in lands here via MainActivity.
    LaunchedEffect(signIn) {
        (signIn as? JiraSignIn.State.Done)?.let { done ->
            when (val r = done.result) {
                is JiraSession.SignInResult.Connected -> { sites = r.sites; testResult = null }
                is JiraSession.SignInResult.Failed -> { testOk = false; testResult = r.message }
            }
            JiraSignIn.consume()
        }
    }

    SubScroll("Jira", onBack) {
        // --- account ---
        FluidCard {
            val status = when {
                settings.jiraNeedsReauth -> "Sign-in expired. Please sign in again."
                settings.jiraConfigured -> "Connected \u00B7 ${settings.jiraAccountLabel.ifBlank { settings.jiraBaseUrl }}"
                else -> "Not connected"
            }
            SectionHeader("Account", status, Glyphs.Ticket)
            if (signIn is JiraSignIn.State.Working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LottieIcon(Anim.LOADING, Modifier.size(32.dp), tint = c.accent)
                    Spacer(Modifier.width(10.dp))
                    Hint("Finishing sign-in\u2026")
                }
            } else if (settings.jiraConfigured) {
                Hint("Reopened tickets are checked every ${settings.jiraPollMinutes} min while Tools mode is on.")
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Sign out", Modifier.fillMaxWidth(), danger = true) { JiraSession.signOut(ctx); sites = emptyList() }
            } else if (JiraSession.oauthAvailable) {
                Hint("You'll sign in on Atlassian's own page. No API token to copy or paste.")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Sign in with Atlassian", Modifier.fillMaxWidth()) { runCatching { JiraSession.startSignIn(ctx) } }
            } else {
                Hint("Atlassian sign-in is not set up in this build yet. Use an API token below.")
            }
            pollError?.let { Spacer(Modifier.height(8.dp)); Hint(it, c.coral) }
        }

        // --- more than one Jira site on the account ---
        if (sites.size > 1 && settings.jiraAuth != JiraAuth.OAUTH) {
            FluidCard {
                SectionHeader("Choose a site", "Your Atlassian account has several")
                sites.forEach { site ->
                    NavRow(site.name.ifBlank { site.url }, subtitle = site.url) { JiraSession.selectSite(ctx, site) }
                }
            }
        }

        // --- query ---
        FluidCard {
            SectionHeader("What counts as reopened", "A JQL query", Glyphs.Search)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JQL_PRESETS.forEach { (label, q) -> Chip(label, jql == q) { jql = q; SettingsStore.update { it.copy(jiraJql = q, jiraRev = it.jiraRev + 1) } } }
            }
            Spacer(Modifier.height(10.dp))
            Field(jql, { jql = it }, "JQL", singleLine = false)
            Spacer(Modifier.height(8.dp))
            SliderRow("Check every", "${settings.jiraPollMinutes} min", settings.jiraPollMinutes.toFloat(), 1f..60f) { v ->
                SettingsStore.update { it.copy(jiraPollMinutes = v.toInt()) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Save query", Modifier.weight(1f)) { SettingsStore.update { it.copy(jiraJql = jql.trim(), jiraRev = it.jiraRev + 1) } }
                SecondaryButton(if (testing) "Testing\u2026" else "Test now", Modifier.weight(1f)) {
                    if (testing) return@SecondaryButton
                    testing = true; testResult = null
                    scope.launch {
                        SettingsStore.update { it.copy(jiraJql = jql.trim()) }
                        try {
                            val found = JiraSession.search(ctx, SettingsStore.value)
                            testOk = true
                            testResult = if (found.isEmpty()) "Works. Nothing matches right now." else "Works. ${found.size} ticket${if (found.size == 1) "" else "s"}: ${found.take(3).joinToString { it.key }}"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            testOk = false
                            testResult = JiraSession.describe(e)
                        } finally {
                            testing = false
                        }
                    }
                }
            }
            testResult?.let { Spacer(Modifier.height(8.dp)); Hint(it, if (testOk) c.success else c.coral) }
        }

        // --- API token fallback ---
        FluidCard(onClick = if (showToken) null else ({ showToken = true })) {
            SectionHeader(
                "API token", if (JiraSession.oauthAvailable) "Advanced: only if browser sign-in is blocked on your site" else "Enter your Atlassian details",
                Glyphs.Settings,
            )
            AnimatedVisibility(showToken) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(base, { base = it }, "Jira address", keyboard = KeyboardType.Uri, placeholder = "yourcompany.atlassian.net")
                    Field(email, { email = it }, "Account email", keyboard = KeyboardType.Email)
                    Field(token, { token = it }, "API token", secret = true)
                    Hint("Create one at id.atlassian.com > Security > API tokens. It is stored encrypted on this phone.")
                    PrimaryButton("Save and connect", Modifier.fillMaxWidth(), enabled = base.isNotBlank() && email.isNotBlank() && token.isNotBlank()) {
                        JiraSession.saveApiToken(ctx, base, email, token)
                        token = ""
                        testResult = "Saved. Use \"Test now\" to check it."; testOk = true
                    }
                }
            }
        }
    }
}
