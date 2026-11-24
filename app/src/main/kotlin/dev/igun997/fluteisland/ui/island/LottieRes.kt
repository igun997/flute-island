package dev.igun997.fluteisland.ui.island

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import dev.igun997.fluteisland.R
import dev.igun997.fluteisland.core.WeatherKind

object Anim {
    fun weather(kind: WeatherKind, isDay: Boolean): Int = when (kind) {
        WeatherKind.CLEAR -> if (isDay) R.raw.lottie_weather_sun else R.raw.lottie_weather_moon
        WeatherKind.PARTLY -> R.raw.lottie_weather_partly
        WeatherKind.CLOUDY, WeatherKind.FOG -> R.raw.lottie_weather_cloud
        WeatherKind.DRIZZLE, WeatherKind.RAIN -> R.raw.lottie_weather_rain
        WeatherKind.SNOW -> R.raw.lottie_weather_snow
        WeatherKind.STORM -> R.raw.lottie_weather_storm
    }

    val LOADING = R.raw.lottie_loading
    val TICKET = R.raw.lottie_ticket_reopen
    val SUCCESS = R.raw.lottie_success_check
}

/**
 * Looping (or frozen) Lottie. Frozen = a single still frame, so idle UI costs nothing per frame.
 * [tint] recolours white-authored animations (equalizer, spinner) to the current theme.
 */
@Composable
fun LottieIcon(
    res: Int,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    stillProgress: Float = 0.3f,
    iterations: Int = LottieConstants.IterateForever,
    tint: Color? = null,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(res))
    val dynamic = if (tint != null) {
        rememberLottieDynamicProperties(
            rememberLottieDynamicProperty(LottieProperty.COLOR_FILTER, SimpleColorFilter(tint.toArgb()), "**"),
        )
    } else null
    if (playing) {
        com.airbnb.lottie.compose.LottieAnimation(
            composition = composition,
            iterations = iterations,
            modifier = modifier,
            dynamicProperties = dynamic,
        )
    } else {
        LottieAnimation(
            composition = composition,
            progress = { stillProgress },
            modifier = modifier,
            dynamicProperties = dynamic,
        )
    }
}
