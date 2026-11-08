package dev.rsm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The app's Material 3 theme.
 *
 * Dynamic colour is used where the platform has it (API 31+), which on a user's own device is the
 * scheme they already have contrast-checked by living with it. Below that the baseline palettes in
 * `Color.kt` apply.
 *
 * ### Why dark mode is not optional here
 *
 * The version this replaced was `Theme.Material.Light.NoActionBar` with a bare `MaterialTheme()`,
 * i.e. light-only regardless of system setting. Beyond being unpleasant at night, FR-605 AC-7 lists
 * a theme change among the configuration changes that must not lose volume state — a theme the app
 * never responds to makes that criterion untestable rather than satisfied.
 *
 * The reason the platform theme in `res/values-night/` matters too: it owns the very first frame,
 * before Compose has drawn anything. Without a night override the window flashes white on a cold
 * start in dark mode.
 *
 * @param dynamicColor exposed so a screenshot test can pin the palette; production always leaves it
 *   on, since a fixed palette on a device that supports dynamic colour looks like a bug.
 */
@Composable
fun RsmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> RsmDarkColors
        else -> RsmLightColors
    }

    // Typography and shapes are left at the M3 defaults deliberately. Their default type scale is
    // expressed in sp and therefore already honours the 200% system font scaling FR-606 requires;
    // a hand-rolled scale is the usual way that obligation gets broken.
    MaterialTheme(colorScheme = colorScheme, content = content)
}
