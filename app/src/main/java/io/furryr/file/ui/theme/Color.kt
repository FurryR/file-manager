package io.furryr.file.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Material 3 schemes, one per selectable colour palette. Every role is a tone
// from the palette's tonal set (40/90 light, 80/30 dark), so contrast and
// container relationships stay inside the M3 contract:
//   primary   tone 40/80, secondary 40/80, tertiary 40/80, error 40/80
//   containers tone 90/30, on-colours tone 10/90
//   neutrals: surface 98/black, containers 90-100 / 10-22
// Dark palettes are true black (AMOLED): background, surface, surfaceDim and
// surfaceContainerLowest are #000000; raised containers still step up so
// sheets, dialogs and cards separate without lighting the whole panel.

// Indigo — calm blue (seed #4E6E8E).
private val IndigoLightColors = lightColorScheme(
    primary = Color(0xFF416180),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE5FF),
    onPrimaryContainer = Color(0xFF001D33),
    inversePrimary = Color(0xFFA9CAEE),
    secondary = Color(0xFF545F6D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E4F4),
    onSecondaryContainer = Color(0xFF111D28),
    tertiary = Color(0xFF745377),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFED6FF),
    onTertiaryContainer = Color(0xFF2C1031),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFAF9FC),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE3EA),
    onSurfaceVariant = Color(0xFF42474D),
    surfaceTint = Color(0xFF416180),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F3),
    outline = Color(0xFF73777E),
    outlineVariant = Color(0xFFC3C7CE),
    surfaceBright = Color(0xFFFAF9FC),
    surfaceDim = Color(0xFFDADADC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3F6),
    surfaceContainer = Color(0xFFEEEDF0),
    surfaceContainerHigh = Color(0xFFE8E8EB),
    surfaceContainerHighest = Color(0xFFE3E2E5),
    scrim = Color.Black
)

// Indigo — calm blue (seed #4E6E8E).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val IndigoDarkColors = darkColorScheme(
    primary = Color(0xFFA9CAEE),
    onPrimary = Color(0xFF0D3350),
    primaryContainer = Color(0xFF284967),
    onPrimaryContainer = Color(0xFFCFE5FF),
    inversePrimary = Color(0xFF416180),
    secondary = Color(0xFFBCC8D7),
    onSecondary = Color(0xFF26313E),
    secondaryContainer = Color(0xFF3C4855),
    onSecondaryContainer = Color(0xFFD8E4F4),
    tertiary = Color(0xFFE2BAE3),
    onTertiary = Color(0xFF432647),
    tertiaryContainer = Color(0xFF5B3C5E),
    onTertiaryContainer = Color(0xFFFED6FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFE3E2E5),
    surface = Color.Black,
    onSurface = Color(0xFFE3E2E5),
    surfaceVariant = Color(0xFF42474D),
    onSurfaceVariant = Color(0xFFC3C7CE),
    surfaceTint = Color(0xFFA9CAEE),
    inverseSurface = Color(0xFFE3E2E5),
    inverseOnSurface = Color(0xFF2F3033),
    outline = Color(0xFF8D9198),
    outlineVariant = Color(0xFF42474D),
    surfaceBright = Color(0xFF38393C),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF1A1C1E),
    surfaceContainer = Color(0xFF1E2022),
    surfaceContainerHigh = Color(0xFF292A2C),
    surfaceContainerHighest = Color(0xFF333537),
    scrim = Color.Black
)

// Sage — muted green (seed #3E7C6B).
private val SageLightColors = lightColorScheme(
    primary = Color(0xFF296959),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFAFF0DB),
    onPrimaryContainer = Color(0xFF002019),
    inversePrimary = Color(0xFF94D3BF),
    secondary = Color(0xFF4C635B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE8DE),
    onSecondaryContainer = Color(0xFF081F19),
    tertiary = Color(0xFF625787),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE7DEFF),
    onTertiaryContainer = Color(0xFF1E1440),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAF7),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF8FAF7),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF404945),
    surfaceTint = Color(0xFF296959),
    inverseSurface = Color(0xFF2E312F),
    inverseOnSurface = Color(0xFFEFF1EE),
    outline = Color(0xFF707975),
    outlineVariant = Color(0xFFBFC9C4),
    surfaceBright = Color(0xFFF8FAF7),
    surfaceDim = Color(0xFFD8DBD8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F1),
    surfaceContainer = Color(0xFFECEEEC),
    surfaceContainerHigh = Color(0xFFE7E9E6),
    surfaceContainerHighest = Color(0xFFE1E3E0),
    scrim = Color.Black
)

// Sage — muted green (seed #3E7C6B).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val SageDarkColors = darkColorScheme(
    primary = Color(0xFF94D3BF),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF075041),
    onPrimaryContainer = Color(0xFFAFF0DB),
    inversePrimary = Color(0xFF296959),
    secondary = Color(0xFFB3CCC2),
    onSecondary = Color(0xFF1E352E),
    secondaryContainer = Color(0xFF354B44),
    onSecondaryContainer = Color(0xFFCFE8DE),
    tertiary = Color(0xFFCCBFF5),
    onTertiary = Color(0xFF332956),
    tertiaryContainer = Color(0xFF4A406E),
    onTertiaryContainer = Color(0xFFE7DEFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFE1E3E0),
    surface = Color.Black,
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF404945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceTint = Color(0xFF94D3BF),
    inverseSurface = Color(0xFFE1E3E0),
    inverseOnSurface = Color(0xFF2E312F),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF404945),
    surfaceBright = Color(0xFF373A38),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF191C1B),
    surfaceContainer = Color(0xFF1D201F),
    surfaceContainerHigh = Color(0xFF272B29),
    surfaceContainerHighest = Color(0xFF323534),
    scrim = Color.Black
)

// Rose — dusty rose (seed #A05C78).
private val RoseLightColors = lightColorScheme(
    primary = Color(0xFF8A4A65),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E5),
    onPrimaryContainer = Color(0xFF390621),
    inversePrimary = Color(0xFFFFB0CE),
    secondary = Color(0xFF765561),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E5),
    onSecondaryContainer = Color(0xFF2C141E),
    tertiary = Color(0xFF8D4E28),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBC9),
    onTertiaryContainer = Color(0xFF331200),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF201A1C),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF201A1C),
    surfaceVariant = Color(0xFFF2DDE3),
    onSurfaceVariant = Color(0xFF514348),
    surfaceTint = Color(0xFF8A4A65),
    inverseSurface = Color(0xFF362F31),
    inverseOnSurface = Color(0xFFFBEEF0),
    outline = Color(0xFF837378),
    outlineVariant = Color(0xFFD6C1C7),
    surfaceBright = Color(0xFFFFF8F8),
    surfaceDim = Color(0xFFE3D7D9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDF0F3),
    surfaceContainer = Color(0xFFF8EBED),
    surfaceContainerHigh = Color(0xFFF2E5E7),
    surfaceContainerHighest = Color(0xFFECDFE2),
    scrim = Color.Black
)

// Rose — dusty rose (seed #A05C78).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val RoseDarkColors = darkColorScheme(
    primary = Color(0xFFFFB0CE),
    onPrimary = Color(0xFF541D36),
    primaryContainer = Color(0xFF6F334D),
    onPrimaryContainer = Color(0xFFFFD9E5),
    inversePrimary = Color(0xFF8A4A65),
    secondary = Color(0xFFE5BCC9),
    onSecondary = Color(0xFF432833),
    secondaryContainer = Color(0xFF5C3E49),
    onSecondaryContainer = Color(0xFFFFD9E5),
    tertiary = Color(0xFFFFB68E),
    onTertiary = Color(0xFF532200),
    tertiaryContainer = Color(0xFF703712),
    onTertiaryContainer = Color(0xFFFFDBC9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFECDFE2),
    surface = Color.Black,
    onSurface = Color(0xFFECDFE2),
    surfaceVariant = Color(0xFF514348),
    onSurfaceVariant = Color(0xFFD6C1C7),
    surfaceTint = Color(0xFFFFB0CE),
    inverseSurface = Color(0xFFECDFE2),
    inverseOnSurface = Color(0xFF362F31),
    outline = Color(0xFF9E8C91),
    outlineVariant = Color(0xFF514348),
    surfaceBright = Color(0xFF3F3739),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF201A1C),
    surfaceContainer = Color(0xFF241E20),
    surfaceContainerHigh = Color(0xFF2F282A),
    surfaceContainerHighest = Color(0xFF3A3335),
    scrim = Color.Black
)

// Amber — warm clay (seed #B07B52).
private val AmberLightColors = lightColorScheme(
    primary = Color(0xFF82542E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC4),
    onPrimaryContainer = Color(0xFF2F1400),
    inversePrimary = Color(0xFFF7BA8C),
    secondary = Color(0xFF725947),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFEDCC5),
    onSecondaryContainer = Color(0xFF29180A),
    tertiary = Color(0xFF60612A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6E7A1),
    onTertiaryContainer = Color(0xFF1C1D00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF201B17),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF201B17),
    surfaceVariant = Color(0xFFF2DFD3),
    onSurfaceVariant = Color(0xFF51443C),
    surfaceTint = Color(0xFF82542E),
    inverseSurface = Color(0xFF352F2C),
    inverseOnSurface = Color(0xFFFAEFE9),
    outline = Color(0xFF83746A),
    outlineVariant = Color(0xFFD5C3B8),
    surfaceBright = Color(0xFFFFF8F5),
    surfaceDim = Color(0xFFE3D8D2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDF1EC),
    surfaceContainer = Color(0xFFF7ECE6),
    surfaceContainerHigh = Color(0xFFF1E6E0),
    surfaceContainerHighest = Color(0xFFEBE0DB),
    scrim = Color.Black
)

// Amber — warm clay (seed #B07B52).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val AmberDarkColors = darkColorScheme(
    primary = Color(0xFFF7BA8C),
    onPrimary = Color(0xFF4C2705),
    primaryContainer = Color(0xFF673D19),
    onPrimaryContainer = Color(0xFFFFDCC4),
    inversePrimary = Color(0xFF82542E),
    secondary = Color(0xFFE1C0AA),
    onSecondary = Color(0xFF402C1D),
    secondaryContainer = Color(0xFF594231),
    onSecondaryContainer = Color(0xFFFEDCC5),
    tertiary = Color(0xFFCACA88),
    onTertiary = Color(0xFF313300),
    tertiaryContainer = Color(0xFF484914),
    onTertiaryContainer = Color(0xFFE6E7A1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFEBE0DB),
    surface = Color.Black,
    onSurface = Color(0xFFEBE0DB),
    surfaceVariant = Color(0xFF51443C),
    onSurfaceVariant = Color(0xFFD5C3B8),
    surfaceTint = Color(0xFFF7BA8C),
    inverseSurface = Color(0xFFEBE0DB),
    inverseOnSurface = Color(0xFF352F2C),
    outline = Color(0xFF9E8E83),
    outlineVariant = Color(0xFF51443C),
    surfaceBright = Color(0xFF3E3834),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF201B17),
    surfaceContainer = Color(0xFF241F1B),
    surfaceContainerHigh = Color(0xFF2E2925),
    surfaceContainerHighest = Color(0xFF3A3430),
    scrim = Color.Black
)

// Graphite — neutral graphite (seed #5E6266).
private val GraphiteLightColors = lightColorScheme(
    primary = Color(0xFF5B5F63),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E3E7),
    onPrimaryContainer = Color(0xFF181C1F),
    inversePrimary = Color(0xFFC3C7CB),
    secondary = Color(0xFF5D5E60),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E2E4),
    onSecondaryContainer = Color(0xFF1A1C1D),
    tertiary = Color(0xFF625D62),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9E0E6),
    onTertiaryContainer = Color(0xFF1E1A1E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8F8),
    onBackground = Color(0xFF1C1B1C),
    surface = Color(0xFFFCF8F8),
    onSurface = Color(0xFF1C1B1C),
    surfaceVariant = Color(0xFFE1E2E6),
    onSurfaceVariant = Color(0xFF44474A),
    surfaceTint = Color(0xFF5B5F63),
    inverseSurface = Color(0xFF313030),
    inverseOnSurface = Color(0xFFF3F0F0),
    outline = Color(0xFF75777A),
    outlineVariant = Color(0xFFC5C6CA),
    surfaceBright = Color(0xFFFCF8F8),
    surfaceDim = Color(0xFFDCD9D9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F3F2),
    surfaceContainer = Color(0xFFF0EDED),
    surfaceContainerHigh = Color(0xFFEBE7E7),
    surfaceContainerHighest = Color(0xFFE5E2E1),
    scrim = Color.Black
)

// Graphite — neutral graphite (seed #5E6266).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val GraphiteDarkColors = darkColorScheme(
    primary = Color(0xFFC3C7CB),
    onPrimary = Color(0xFF2D3134),
    primaryContainer = Color(0xFF43474B),
    onPrimaryContainer = Color(0xFFE0E3E7),
    inversePrimary = Color(0xFF5B5F63),
    secondary = Color(0xFFC6C6C8),
    onSecondary = Color(0xFF2F3032),
    secondaryContainer = Color(0xFF464748),
    onSecondaryContainer = Color(0xFFE3E2E4),
    tertiary = Color(0xFFCDC4CA),
    onTertiary = Color(0xFF342F33),
    tertiaryContainer = Color(0xFF4A454A),
    onTertiaryContainer = Color(0xFFE9E0E6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFE5E2E1),
    surface = Color.Black,
    onSurface = Color(0xFFE5E2E1),
    surfaceVariant = Color(0xFF44474A),
    onSurfaceVariant = Color(0xFFC5C6CA),
    surfaceTint = Color(0xFFC3C7CB),
    inverseSurface = Color(0xFFE5E2E1),
    inverseOnSurface = Color(0xFF313030),
    outline = Color(0xFF8E9194),
    outlineVariant = Color(0xFF44474A),
    surfaceBright = Color(0xFF3A3939),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF1C1B1C),
    surfaceContainer = Color(0xFF201F20),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF353535),
    scrim = Color.Black
)

enum class ColorPalette(val id: String) {
    Indigo("indigo"),
    Sage("sage"),
    Rose("rose"),
    Amber("amber"),
    Graphite("graphite");

    fun lightColors(): ColorScheme = when (this) {
    Indigo -> IndigoLightColors
    Sage -> SageLightColors
    Rose -> RoseLightColors
    Amber -> AmberLightColors
    Graphite -> GraphiteLightColors
    }

    fun darkColors(): ColorScheme = when (this) {
    Indigo -> IndigoDarkColors
    Sage -> SageDarkColors
    Rose -> RoseDarkColors
    Amber -> AmberDarkColors
    Graphite -> GraphiteDarkColors
    }

    companion object {
    fun fromId(id: String?): ColorPalette =
        entries.firstOrNull { it.id == id } ?: Indigo
    }
}
