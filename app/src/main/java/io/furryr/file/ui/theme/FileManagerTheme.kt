package io.furryr.file.ui.theme
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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

@Composable
fun FileManagerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = PaletteStore.current(LocalContext.current)
    val amoled = PaletteStore.amoled(LocalContext.current)
    val colors = if (darkTheme) palette.darkColors(amoled) else palette.lightColors()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val surface = colors.surface.toArgb()
            // IME resize animations can expose the window behind the Compose surface.
            window.setBackgroundDrawable(ColorDrawable(surface))
            // Keep the system bars in step with the Compose scheme, including the
            // near-black or AMOLED dark surface and its light icons.
            window.applyBarColors(surface)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
