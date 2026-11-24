@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.igun997.fluteisland.ui.control

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.igun997.fluteisland.BuildConfig
import dev.igun997.fluteisland.R
import dev.igun997.fluteisland.core.Alert
import dev.igun997.fluteisland.core.IslandController
import dev.igun997.fluteisland.core.JiraAuth
import dev.igun997.fluteisland.core.MediaInfo
import dev.igun997.fluteisland.core.Mode
import dev.igun997.fluteisland.core.Panel
import dev.igun997.fluteisland.core.Source
import dev.igun997.fluteisland.core.ThemeMode
import dev.igun997.fluteisland.core.Ticket
import dev.igun997.fluteisland.core.Weather
import dev.igun997.fluteisland.core.WeatherKind
import dev.igun997.fluteisland.data.JiraSession
import dev.igun997.fluteisland.data.SettingsStore
import dev.igun997.fluteisland.service.IslandRuntime
import dev.igun997.fluteisland.ui.Glyphs
import dev.igun997.fluteisland.ui.island.Anim
import dev.igun997.fluteisland.ui.island.GlyphIcon
import dev.igun997.fluteisland.ui.island.IslandActions
import dev.igun997.fluteisland.ui.island.IslandData
import dev.igun997.fluteisland.ui.island.IslandMetrics
import dev.igun997.fluteisland.ui.island.IslandPalette
import dev.igun997.fluteisland.ui.island.IslandSurface
import dev.igun997.fluteisland.ui.island.LottieIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(dark: Boolean, onNavigate: (Screen) -> Unit) {
    val c = LocalControlColors.current
    val ctx = LocalContext.current
    val settings by SettingsStore.flow.collectAsState()
    val overlayShown by IslandRuntime.overlayShown.collectAsState()
    val listenerUp by IslandRuntime.listenerConnected.collectAsState()
    val hostUp by IslandRuntime.hostConnected.collectAsState()
    val connected = listenerUp && hostUp

    // Permission state is re-read every time the user comes back from system settings.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val overlayOk = remember(tick) { Perms.islandHostEnabled(ctx) }
    val listenerOk = remember(tick) { Perms.listenerGranted(ctx) }
    val ready = overlayOk && listenerOk

    val gridState = rememberLazyStaggeredGridState()
    val parallax by remember { derivedStateOf { if (gridState.firstVisibleItemIndex == 0) gridState.firstVisibleItemScrollOffset.toFloat() else 0f } }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(340.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalItemSpacing = 14.dp,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Hero(dark, ready, parallax, Modifier.layout16())
        }
        item(span = StaggeredGridItemSpan.FullLine) {
            PreviewCard(dark, Modifier.entrance(1))
        }
        item { SetupCard(overlayOk, listenerOk, connected, overlayShown, Modifier.entrance(2)) }
        item { ModeCard(settings.mode, Modifier.entrance(3)) }
        item { NotificationsCard(onNavigate, Modifier.entrance(4)) }
        item { WeatherCard(onNavigate, Modifier.entrance(5)) }
        item { JiraCard(onNavigate, Modifier.entrance(6)) }
        item { AppearanceCard(Modifier.entrance(7)) }
        item { TryItCard(ready, Modifier.entrance(8)) }
        item { AboutCard(Modifier.entrance(9)) }
    }
}

/** The hero bleeds to the screen edges, so cancel the grid's side padding. */
private fun Modifier.layout16(): Modifier = this.layout { measurable, constraints ->
    val extra = 16.dp.roundToPx() * 2
    val p = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Hero(dark: Boolean, ready: Boolean, parallax: Float, modifier: Modifier) {
    val c = LocalControlColors.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxWidth().height(260.dp + topInset).clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))) {
        Image(
            painterResource(if (dark) R.drawable.hero_dark else R.drawable.hero_light),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = parallax * 0.35f; scaleX = 1.08f; scaleY = 1.08f },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to c.bg.copy(alpha = if (dark) 0.92f else 0.85f))))
        // Quick flip: tap = the other theme, long-press = back to following the phone.
        val themeMode = SettingsStore.flow.collectAsState().value.themeMode
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = topInset + 10.dp, end = 16.dp).size(44.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.28f))
                .semantics { contentDescription = "Flip light and dark theme" }
                .combinedClickable(
                    onClickLabel = "Flip light and dark theme",
                    onLongClickLabel = "Follow phone theme",
                    onClick = { SettingsStore.update { it.copy(themeMode = if (dark) ThemeMode.LIGHT else ThemeMode.DARK) } },
                    onLongClick = { SettingsStore.update { it.copy(themeMode = ThemeMode.AUTO) } },
                ),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(if (dark) Glyphs.Sun else Glyphs.Moon, Color.White, 22)
            if (themeMode == ThemeMode.AUTO) Box(Modifier.align(Alignment.BottomEnd).padding(9.dp).size(7.dp).clip(CircleShape).background(c.accent))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (ready) c.success else c.coral)
                Spacer(Modifier.width(8.dp))
                Text(if (ready) "Island is live" else "Setup needed", color = if (ready) c.success else c.coral, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text("Flute Island", color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("A light, fast dynamic island for any phone.", color = c.muted, fontSize = 15.sp)
        }
    }
}

// --- Live preview ---------------------------------------------------------------------------

private enum class Demo(val label: String) { IDLE("Idle"), MUSIC("Music"), MESSAGE("Message"), JIRA("Jira"), WEATHER("Weather"), TOOLS("Tools") }

private val demoWeather get() = Weather(29.0, WeatherKind.PARTLY, true, "Bandung", 31.0, 22.0, System.currentTimeMillis())
private fun demoMedia() = MediaInfo("com.spotify.music", "Spotify", "Sunset Over Lagoon", "Flute Island", true, 42_000, 215_000, System.currentTimeMillis(), 1f, null, 0xFF19C3B1.toInt())
private val demoTickets = listOf(
    Ticket("OPS-41", "Login loops on retry", "Reopened", "High", ""),
    Ticket("OPS-42", "Checkout fails on Samsung A-series", "Reopened", "Medium", ""),
)

@Composable
private fun PreviewCard(dark: Boolean, modifier: Modifier) {
    val c = LocalControlColors.current
    val settings by SettingsStore.flow.collectAsState()
    val controller = remember { IslandController(alertMs = { 60_000L }) }
    val state by controller.state.collectAsState()
    var demo by remember { mutableStateOf(Demo.MESSAGE) }

    // Keep the stage in sync with the chosen demo.
    LaunchedEffect(demo, dark) {
        val now = System.currentTimeMillis()
        controller.clearAlerts()
        controller.setPanel(Panel.NONE, now)
        controller.setMedia(null, now)
        controller.setWeather(demoWeather)
        controller.setMode(if (demo == Demo.TOOLS) Mode.TOOLS else Mode.NORMAL, now)
        controller.setJiraConfigured(true)
        controller.setTickets(if (demo == Demo.TOOLS) demoTickets else emptyList())
        when (demo) {
            Demo.IDLE -> Unit
            Demo.MUSIC -> controller.setMedia(demoMedia(), now)
            Demo.MESSAGE -> controller.postAlert(Alert("demo", Source.WHATSAPP, "WhatsApp", "com.whatsapp", "Rina", "Besok jadi ketemu jam 10?", now), now)
            Demo.JIRA -> controller.postAlert(Alert("demo-j", Source.JIRA, "Jira", "com.atlassian.android.jira.core", "OPS-42 reopened", "Checkout fails on Samsung A-series", now), now)
            Demo.WEATHER -> controller.setPanel(Panel.WEATHER, now)
            Demo.TOOLS -> controller.setPanel(Panel.TOOLS, now)
        }
    }

    FluidCard(modifier) {
        SectionHeader("Live preview", "Tap the island, swipe it up, or pick a scene")
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(212.dp).clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(if (dark) Color(0xFF0D2D36) else Color(0xFFBFE7F2), if (dark) Color(0xFF081218) else Color(0xFFF4E3C3)))),
        ) {
            val metrics = IslandMetrics(maxWidth.value, maxWidth.value / 2f, 15f, 56f, true)
            val data = IslandData(
                celsius = settings.celsius, tickets = state.tickets, ticketsLoaded = true, jiraConfigured = true,
                haptics = false, media = state.media, weather = state.weather, blendCamera = settings.blendCamera, recent = state.recent,
            )
            val actions = remember(controller) {
                IslandActions(
                    onTap = { controller.togglePanel(System.currentTimeMillis()) },
                    onSwipeUp = { controller.setPanel(Panel.NONE, System.currentTimeMillis()); controller.dismissAlert(System.currentTimeMillis()) },
                    onSwipeDown = { controller.togglePanel(System.currentTimeMillis()) },
                    onMediaToggle = {
                        val m = controller.snapshot.media
                        if (m != null) controller.setMedia(m.copy(playing = !m.playing, positionAt = System.currentTimeMillis()), System.currentTimeMillis())
                    },
                )
            }
            // Same expiry rules as the real island.
            LaunchedEffect(state.current, state.currentUntil) {
                val due = controller.nextDeadline() ?: return@LaunchedEffect
                delay((due - System.currentTimeMillis()).coerceAtLeast(0))
                controller.tick(System.currentTimeMillis())
            }
            val presentation = IslandController.present(state, 0)
            IslandSurface(presentation, metrics, IslandPalette.of(if (settings.themeMode == ThemeMode.AUTO) dark else settings.themeMode == ThemeMode.DARK), data, actions)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Demo.values().forEach { d -> Chip(d.label, demo == d) { demo = d } }
        }
    }
}

// --- Cards ----------------------------------------------------------------------------------

@Composable
private fun SetupCard(overlayOk: Boolean, listenerOk: Boolean, connected: Boolean, overlayShown: Boolean, modifier: Modifier) {
    val c = LocalControlColors.current
    val ctx = LocalContext.current
    FluidCard(modifier) {
        val done = overlayOk && listenerOk
        SectionHeader(
            if (done) "All set" else "Finish setup",
            if (done) (if (connected) "The island is running" else "Waiting for Android to start the services") else "Two switches make the island work",
            Glyphs.Check,
        )
        PermissionRow("Accessibility", "Draws the island above the status bar and spots games. It reads no screen content.", overlayOk) { Perms.openAccessibilitySettings(ctx) }
        if (!overlayOk) {
            Spacer(Modifier.height(2.dp))
            Hint("Can't switch it on? Open App info, tap the menu, then \"Allow restricted settings\", and try again.")
            Spacer(Modifier.height(6.dp))
            SecondaryButton("Open App info", Modifier.fillMaxWidth()) { Perms.openAppInfo(ctx) }
        }
        Spacer(Modifier.height(6.dp))
        PermissionRow("Notification access", "Reads WhatsApp, Spotify and other alerts", listenerOk) { Perms.openListenerSettings(ctx) }
        if (done) {
            Spacer(Modifier.height(10.dp))
            Hint("Phone kills it in the background? Exempt Flute Island from battery optimisation.")
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Battery settings", Modifier.fillMaxWidth()) { Perms.openBatterySettings(ctx) }
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onGrant: () -> Unit) {
    val c = LocalControlColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Crossfade(granted, label = "perm") { ok ->
                if (ok) LottieIcon(Anim.SUCCESS, Modifier.size(44.dp), iterations = 1)
                else Box(Modifier.size(28.dp).clip(CircleShape).background(c.coral.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.coral))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = c.muted, fontSize = 13.sp)
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            PrimaryButton("Allow", onClick = onGrant)
        }
    }
}

@Composable
private fun ModeCard(mode: Mode, modifier: Modifier) {
    val c = LocalControlColors.current
    FluidCard(modifier) {
        SectionHeader("Mode", "Long-press the island to switch", Glyphs.Wrench)
        Segmented(listOf(Mode.NORMAL to "Everyday", Mode.TOOLS to "Tools"), mode) { m -> SettingsStore.update { it.copy(mode = m) } }
        Spacer(Modifier.height(10.dp))
        AnimatedContent(mode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "modeText") { m ->
            Hint(
                if (m == Mode.NORMAL) "Messages, music and weather. Quiet and battery-friendly."
                else "Adds your Jira board: a reopened ticket pops up and is one tap away.",
            )
        }
    }
}

@Composable
private fun NotificationsCard(onNavigate: (Screen) -> Unit, modifier: Modifier) {
    val settings by SettingsStore.flow.collectAsState()
    val blocked = settings.blockedPackages.size
    FluidCard(modifier) {
        SectionHeader("Alerts", null, Glyphs.Bell)
        SwitchRow("Notifications", "WhatsApp and other apps", settings.notificationsEnabled) { v -> SettingsStore.update { it.copy(notificationsEnabled = v) } }
        SwitchRow("Stay out of the way", "Hide in games and fullscreen apps; hold alerts until you are back", settings.quietMode) { v -> SettingsStore.update { it.copy(quietMode = v) } }
        SwitchRow("Take over notifications", "The island replaces the shade: notifications are cleared once shown, and kept under Recent", settings.takeOver) { v -> SettingsStore.update { it.copy(takeOver = v) } }
        SwitchRow("Now playing", "Spotify and other players", settings.mediaEnabled) { v -> SettingsStore.update { it.copy(mediaEnabled = v) } }
        NavRow("Apps", "$blocked muted", "Choose which apps may use the island") { onNavigate(Screen.APPS) }
        SliderRow("Alert stays for", "${settings.alertSeconds} s", settings.alertSeconds.toFloat(), 2f..12f, steps = 9) { v ->
            SettingsStore.update { it.copy(alertSeconds = v.toInt()) }
        }
    }
}

@Composable
private fun WeatherCard(onNavigate: (Screen) -> Unit, modifier: Modifier) {
    val settings by SettingsStore.flow.collectAsState()
    val state by IslandRuntime.controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    FluidCard(modifier) {
        SectionHeader(
            "Weather", state.weather?.let { "${dev.igun997.fluteisland.core.Temperature.format(it.tempC, settings.celsius)} in ${it.city}" } ?: "Shows next to the camera",
            Glyphs.Cloud,
            trailing = {
                state.weather?.let { w -> LottieIcon(Anim.weather(w.kind, w.isDay), Modifier.size(44.dp)) }
            },
        )
        SwitchRow("Weather chip", "Temperature and icon on the idle island", settings.showWeather) { v -> SettingsStore.update { it.copy(showWeather = v) } }
        NavRow("City", settings.city.name.substringBefore(',')) { onNavigate(Screen.CITY) }
        Spacer(Modifier.height(6.dp))
        Segmented(listOf(true to "\u00B0C", false to "\u00B0F"), settings.celsius) { v -> SettingsStore.update { it.copy(celsius = v) } }
        val err by dev.igun997.fluteisland.service.EngineStatus.weatherError.collectAsState()
        err?.let { Spacer(Modifier.height(8.dp)); Hint(it, LocalControlColors.current.coral) }
    }
}

@Composable
private fun JiraCard(onNavigate: (Screen) -> Unit, modifier: Modifier) {
    val c = LocalControlColors.current
    val ctx = LocalContext.current
    val settings by SettingsStore.flow.collectAsState()
    val status = when {
        settings.jiraNeedsReauth -> "Sign-in expired"
        settings.jiraConfigured -> "Connected \u00B7 ${settings.jiraAccountLabel.ifBlank { settings.jiraBaseUrl }}"
        else -> "Not connected"
    }
    FluidCard(modifier, onClick = { onNavigate(Screen.JIRA) }) {
        SectionHeader("Jira", status, Glyphs.Ticket) { GlyphIcon(Glyphs.ChevronRight, c.muted, 22) }
        if (!settings.jiraConfigured) {
            if (JiraSession.oauthAvailable) {
                PrimaryButton("Sign in with Atlassian", Modifier.fillMaxWidth()) { JiraSession.startSignIn(ctx) }
            } else {
                Hint("Tap to connect with an API token.")
            }
        } else {
            Hint(if (settings.mode == Mode.TOOLS) "Tools mode is on. Reopened tickets will pop up." else "Switch to Tools mode to get reopened-ticket alerts.")
        }
    }
}

@Composable
private fun AppearanceCard(modifier: Modifier) {
    val settings by SettingsStore.flow.collectAsState()
    FluidCard(modifier) {
        SectionHeader("Look & feel", "Theme follows your phone by default", Glyphs.Settings)
        Segmented(listOf(ThemeMode.AUTO to "Auto", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), settings.themeMode) { m -> SettingsStore.update { it.copy(themeMode = m) } }
        Spacer(Modifier.height(8.dp))
        SwitchRow("Blend with camera", "Keep the small pill black so it merges with the lens", settings.blendCamera) { v -> SettingsStore.update { it.copy(blendCamera = v) } }
        SwitchRow("Idle island", "Keep a small pill visible", settings.showIdle) { v -> SettingsStore.update { it.copy(showIdle = v) } }
        SwitchRow("Haptics", "A small tick on long-press", settings.haptics) { v -> SettingsStore.update { it.copy(haptics = v) } }
        SliderRow("Size", "${(settings.sizeScale * 100).toInt()}%", settings.sizeScale, 0.85f..1.25f) { v ->
            SettingsStore.update { it.copy(sizeScale = (v * 20).toInt() / 20f) }
        }
        SliderRow("Distance from top", "${settings.topOffsetDp} dp", settings.topOffsetDp.toFloat(), 0f..48f) { v ->
            SettingsStore.update { it.copy(topOffsetDp = v.toInt()) }
        }
    }
}

@Composable
private fun TryItCard(ready: Boolean, modifier: Modifier) {
    val c = LocalControlColors.current
    val scope = rememberCoroutineScope()
    FluidCard(modifier) {
        SectionHeader("Try it", if (ready) "Fires the real island at the top of your screen" else "Finish setup first", Glyphs.Play)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Message") {
                IslandRuntime.post(Alert("test:msg", Source.WHATSAPP, "WhatsApp", "com.whatsapp", "Rina", "Besok jadi ketemu jam 10?", System.currentTimeMillis()))
            }
            SecondaryButton("Jira ticket") {
                IslandRuntime.post(Alert("test:jira", Source.JIRA, "Jira", "com.atlassian.android.jira.core", "OPS-42 reopened", "Checkout fails on Samsung A-series", System.currentTimeMillis()))
            }
            SecondaryButton("Music") {
                val m = demoMedia()
                IslandRuntime.controller.setMedia(m, System.currentTimeMillis())
                scope.launch {
                    delay(10_000)
                    if (IslandRuntime.controller.snapshot.media?.title == m.title) IslandRuntime.controller.setMedia(null, System.currentTimeMillis())
                }
            }
            SecondaryButton("Weather") { IslandRuntime.controller.setPanel(Panel.WEATHER, System.currentTimeMillis()) }
        }
    }
}

@Composable
private fun AboutCard(modifier: Modifier) {
    val c = LocalControlColors.current
    FluidCard(modifier) {
        Text("Flute Island ${BuildConfig.VERSION_NAME}", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Hint("Built to stay light: no background polling unless a feature needs it, animations pause when nothing is moving.")
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(0.dp))
    }
}
