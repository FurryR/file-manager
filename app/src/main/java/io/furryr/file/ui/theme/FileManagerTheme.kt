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
import android.graphics.drawable.ColorDrawable
import android.view.View

@Composable
fun FileManagerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = PaletteStore.current(LocalContext.current)
    val colors = if (darkTheme) palette.darkColors() else palette.lightColors()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val surface = colors.surface.toArgb()
            // IME resize animations can expose the window behind the Compose surface.
            window.setBackgroundDrawable(ColorDrawable(surface))
            // Keep the system bars in step with the Compose scheme, including the
            // true-black dark surface and its light icons.
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
