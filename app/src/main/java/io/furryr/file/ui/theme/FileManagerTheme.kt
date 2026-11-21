package io.furryr.file.ui.theme
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.view.Window

// LocalView.context is the activity in a plain ComponentActivity, but a
// language/locale wrapper can sit in front of it. Walk the wrappers so the
// window chrome still follows the Compose scheme in both themes — a missed
// cast leaves the platform theme's black window and white icons in light mode.
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

// statusBarColor/navigationBarColor are deprecated for edge-to-edge apps on
// API 35+, but this app targets 28 and still paints its own window chrome;
// there is no replacement that applies below API 35.
@Suppress("DEPRECATION")
private fun Window.applyBarColors(surface: Int) {
    statusBarColor = surface
    navigationBarColor = surface
}

// Root cause of the "top bar changes first" effect: Material3 1.3's
// SingleRowTopAppBar animates its container color with
// spring(stiffness = Spring.StiffnessMediumLow) (AppBar.kt, line 1888), while
// every other surface reads the scheme and switches instantly. Animate the
// scheme itself with the exact same spec so all roles share one curve.
// ShellTopBar additionally makes the app bar transparent (and paints the
// scheme surface behind it) so the app bar does not run a second spring on
// top of this one.
private val ThemeColorAnimation: AnimationSpec<Color> =
    spring(stiffness = Spring.StiffnessMediumLow)

@Composable
private fun animatedThemeColor(target: Color): Color =
    animateColorAsState(target, ThemeColorAnimation, label = "themeColor").value

// MaterialTheme hands out one ColorScheme. Animating every role here makes the
// whole UI cross-fade together on a light/dark or palette change, so no part
// switches ahead of or behind the rest.
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme = target.copy(
    primary = animatedThemeColor(target.primary),
    onPrimary = animatedThemeColor(target.onPrimary),
    primaryContainer = animatedThemeColor(target.primaryContainer),
    onPrimaryContainer = animatedThemeColor(target.onPrimaryContainer),
    inversePrimary = animatedThemeColor(target.inversePrimary),
    secondary = animatedThemeColor(target.secondary),
    onSecondary = animatedThemeColor(target.onSecondary),
    secondaryContainer = animatedThemeColor(target.secondaryContainer),
    onSecondaryContainer = animatedThemeColor(target.onSecondaryContainer),
    tertiary = animatedThemeColor(target.tertiary),
    onTertiary = animatedThemeColor(target.onTertiary),
    tertiaryContainer = animatedThemeColor(target.tertiaryContainer),
    onTertiaryContainer = animatedThemeColor(target.onTertiaryContainer),
    background = animatedThemeColor(target.background),
    onBackground = animatedThemeColor(target.onBackground),
    surface = animatedThemeColor(target.surface),
    onSurface = animatedThemeColor(target.onSurface),
    surfaceVariant = animatedThemeColor(target.surfaceVariant),
    onSurfaceVariant = animatedThemeColor(target.onSurfaceVariant),
    surfaceTint = animatedThemeColor(target.surfaceTint),
    inverseSurface = animatedThemeColor(target.inverseSurface),
    inverseOnSurface = animatedThemeColor(target.inverseOnSurface),
    error = animatedThemeColor(target.error),
    onError = animatedThemeColor(target.onError),
    errorContainer = animatedThemeColor(target.errorContainer),
    onErrorContainer = animatedThemeColor(target.onErrorContainer),
    outline = animatedThemeColor(target.outline),
    outlineVariant = animatedThemeColor(target.outlineVariant),
    scrim = animatedThemeColor(target.scrim),
    surfaceBright = animatedThemeColor(target.surfaceBright),
    surfaceDim = animatedThemeColor(target.surfaceDim),
    surfaceContainer = animatedThemeColor(target.surfaceContainer),
    surfaceContainerHigh = animatedThemeColor(target.surfaceContainerHigh),
    surfaceContainerHighest = animatedThemeColor(target.surfaceContainerHighest),
    surfaceContainerLow = animatedThemeColor(target.surfaceContainerLow),
    surfaceContainerLowest = animatedThemeColor(target.surfaceContainerLowest),
)

@Composable
fun FileManagerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = PaletteStore.current(LocalContext.current)
    val amoled = PaletteStore.amoled(LocalContext.current)
    val targetColors = if (darkTheme) palette.darkColors(amoled) else palette.lightColors()
    // Animate only the window the user can see. A paused activity (the file
    // manager sitting behind Settings while its AMOLED switch is toggled) still
    // observes the shared palette revision, and animating its whole tree per
    // frame doubles the composition work and steals frames from the visible
    // window. Paused colours snap to the target instead; they are not on screen
    // and the next resume shows the final scheme with no pending animation.
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    val colors = if (resumed) animatedColorScheme(targetColors) else targetColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val surface = colors.surface.toArgb()
            // IME resize animations can expose the window behind the Compose surface.
            window.setBackgroundDrawable(ColorDrawable(surface))
            // Keep the system bars in step with the animated Compose surface,
            // including the near-black or AMOLED dark surface and its light icons.
            window.applyBarColors(surface)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                // The icon mode follows the surface that is actually on screen,
                // so it flips where the cross-fade crosses over, not at its start.
                val lightSurface = colors.surface.luminance() > 0.5f
                isAppearanceLightStatusBars = lightSurface
                isAppearanceLightNavigationBars = lightSurface
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
