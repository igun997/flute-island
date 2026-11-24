package dev.igun997.fluteisland.ui.control

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.igun997.fluteisland.core.ThemeMode
import dev.igun997.fluteisland.data.SettingsStore

enum class Screen { HOME, APPS, JIRA, CITY }

@Composable
fun ControlRoot() {
    val settings by SettingsStore.flow.collectAsState()
    val system = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.AUTO -> system
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    ControlTheme(dark) {
        val colors = LocalControlColors.current
        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

        Box(Modifier.fillMaxSize().background(colors.bg)) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val deeper = targetState.ordinal > initialState.ordinal
                    if (deeper) {
                        (slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(260))) togetherWith
                            (slideOutHorizontally(tween(320)) { -it / 6 } + fadeOut(tween(160)))
                    } else {
                        (slideInHorizontally(tween(320)) { -it / 6 } + fadeIn(tween(260))) togetherWith
                            (slideOutHorizontally(tween(320)) { it / 4 } + fadeOut(tween(160)))
                    }
                },
                label = "screen",
            ) { s ->
                when (s) {
                    Screen.HOME -> HomeScreen(dark = dark, onNavigate = { screen = it })
                    Screen.APPS -> AppsScreen(onBack = { screen = Screen.HOME })
                    Screen.JIRA -> JiraScreen(onBack = { screen = Screen.HOME })
                    Screen.CITY -> CityScreen(onBack = { screen = Screen.HOME })
                }
            }
        }
    }
}
