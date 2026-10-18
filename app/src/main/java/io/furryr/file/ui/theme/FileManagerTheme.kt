package io.furryr.file.ui.theme
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.model.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import android.graphics.drawable.ColorDrawable

@Composable
fun FileManagerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) darkColorScheme() else lightColorScheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // IME resize animations can expose the window behind the Compose surface.
            (view.context as? Activity)?.window?.setBackgroundDrawable(ColorDrawable(colors.surface.toArgb()))
        }
    }
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
