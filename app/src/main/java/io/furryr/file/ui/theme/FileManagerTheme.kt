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
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.view.View

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
            window.statusBarColor = surface
            window.navigationBarColor = surface
            val decor = window.decorView
            val lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decor.systemUiVisibility = if (darkTheme) {
                decor.systemUiVisibility and lightBars.inv()
            } else {
                decor.systemUiVisibility or lightBars
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
