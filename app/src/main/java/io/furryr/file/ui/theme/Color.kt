package io.furryr.file.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Material 3 schemes, one per selectable colour palette. Each palette owns one
// hue identity: light and dark are two tonal mappings of it, not two
// independent colour sets, so switching theme keeps the same accent family.
//   light: accents tone 47 with vibrant chroma, containers tone 90,
//          on-colours tone 12 / white, surfaces tone 99
//   dark:  accents tone 80, containers tone 30, surface tone 6
//   error: tone 40/80 as specified
// Tone 47 instead of the spec's 40: tone-40 accents read as near-black on a
// tone-99 sheet. White on tone 47 still clears 4.5:1, so buttons and icons
// keep their content colours. Containers and on-colours stay on the M3 tones,
// and the tertiary hue sits opposite the primary so a screen carries more than
// one readable accent.
//
// Dark palettes are AMOLED by default: background, surface, surfaceDim and
// surfaceContainerLowest are #000000, raised containers still step up so
// sheets, dialogs and cards separate without lighting the whole panel. The
// settings toggle can swap the canvas for the M3 baseline tone-6 neutral
// (#141218), which keeps the container ladder visible in lit rooms.

// Non-AMOLED dark canvas: the tone-6 neutral the app used before the palette
// work. Slightly above black, so containers and outlines stay perceptible.
private val MatteBlack = Color(0xFF141218)
private val MatteBlackLowest = Color(0xFF0F0D13)

private fun ColorScheme.withoutAmoledBlack(): ColorScheme = copy(
    background = MatteBlack,
    surface = MatteBlack,
    surfaceDim = MatteBlack,
    surfaceContainerLowest = MatteBlackLowest,
)

// Indigo — royal blue (seed #3071BA).
// Light surfaces sit at tone 99/96/94 so the sheet reads bright, not grey;
// accents stay on the dark scheme's hue at tone 47 with vibrant chroma.
private val IndigoLightColors = lightColorScheme(
    primary = Color(0xFF3071BA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E3FE),
    onPrimaryContainer = Color(0xFF01203E),
    inversePrimary = Color(0xFFABC7FE),
    secondary = Color(0xFF66728E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE2F4),
    onSecondaryContainer = Color(0xFF162032),
    tertiary = Color(0xFF8B6190),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DBF5),
    onTertiaryContainer = Color(0xFF2E1632),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF1C1E21),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF1C1E21),
    surfaceVariant = Color(0xFFDCE2F3),
    onSurfaceVariant = Color(0xFF444B5C),
    surfaceTint = Color(0xFF3071BA),
    inverseSurface = Color(0xFF2F3034),
    inverseOnSurface = Color(0xFFF2F3F6),
    outline = Color(0xFF747C8F),
    outlineVariant = Color(0xFFCED4E5),
    surfaceBright = Color(0xFFFBFCFE),
    surfaceDim = Color(0xFFD8DADF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F8),
    surfaceContainer = Color(0xFFECEEF2),
    surfaceContainerHigh = Color(0xFFE6E8ED),
    surfaceContainerHighest = Color(0xFFE0E2E8),
    scrim = Color.Black
)

// Indigo — royal blue (seed #3071BA).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val IndigoDarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF284777),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = Color(0xFF415F91),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C),
    onTertiaryContainer = Color(0xFFF4D9FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFE2E2E9),
    surface = Color.Black,
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceTint = Color(0xFFAAC7FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3135),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF32353A),
    scrim = Color.Black
)

// Sage — verdant green (seed #427D2A).
private val SageLightColors = lightColorScheme(
    primary = Color(0xFF427D2A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0EAC0),
    onPrimaryContainer = Color(0xFF112400),
    inversePrimary = Color(0xFFA6D38E),
    secondary = Color(0xFF657857),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE6D1),
    onSecondaryContainer = Color(0xFF17230C),
    tertiary = Color(0xFF347A7C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC8E9E9),
    onTertiaryContainer = Color(0xFF012425),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFDFA),
    onBackground = Color(0xFF1C1E1A),
    surface = Color(0xFFFBFDFA),
    onSurface = Color(0xFF1C1E1A),
    surfaceVariant = Color(0xFFDCE5D6),
    onSurfaceVariant = Color(0xFF444E3E),
    surfaceTint = Color(0xFF427D2A),
    inverseSurface = Color(0xFF2F312E),
    inverseOnSurface = Color(0xFFF2F4F1),
    outline = Color(0xFF757F6E),
    outlineVariant = Color(0xFFCED7C8),
    surfaceBright = Color(0xFFFBFDFA),
    surfaceDim = Color(0xFFD8DBD6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F0),
    surfaceContainer = Color(0xFFECEFEA),
    surfaceContainerHigh = Color(0xFFE6E9E4),
    surfaceContainerHighest = Color(0xFFE0E3DE),
    scrim = Color.Black
)

// Sage — verdant green (seed #427D2A).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val SageDarkColors = darkColorScheme(
    primary = Color(0xFF9CD67F),
    onPrimary = Color(0xFF0A3900),
    primaryContainer = Color(0xFF235000),
    onPrimaryContainer = Color(0xFFB7F397),
    inversePrimary = Color(0xFF386A20),
    secondary = Color(0xFFBDCBB1),
    onSecondary = Color(0xFF283420),
    secondaryContainer = Color(0xFF3E4B37),
    onSecondaryContainer = Color(0xFFD9E7CB),
    tertiary = Color(0xFFA0CFD0),
    onTertiary = Color(0xFF003738),
    tertiaryContainer = Color(0xFF1E4E4F),
    onTertiaryContainer = Color(0xFFBBEBEC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFE0E4DA),
    surface = Color.Black,
    onSurface = Color(0xFFE0E4DA),
    surfaceVariant = Color(0xFF43483F),
    onSurfaceVariant = Color(0xFFC3C8BB),
    surfaceTint = Color(0xFF9CD67F),
    inverseSurface = Color(0xFFE0E4DA),
    inverseOnSurface = Color(0xFF2E322B),
    outline = Color(0xFF8D9387),
    outlineVariant = Color(0xFF43483F),
    surfaceBright = Color(0xFF363A33),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF191D17),
    surfaceContainer = Color(0xFF1D211B),
    surfaceContainerHigh = Color(0xFF272B25),
    surfaceContainerHighest = Color(0xFF32362F),
    scrim = Color.Black
)

// Rose — warm rose (seed #B44D6E).
private val RoseLightColors = lightColorScheme(
    primary = Color(0xFFB44D6E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF43061F),
    inversePrimary = Color(0xFFFEB1C5),
    secondary = Color(0xFF94656E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF9DBE0),
    onSecondaryContainer = Color(0xFF35161D),
    tertiary = Color(0xFF976530),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEDCC0),
    onTertiaryContainer = Color(0xFF301B00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFBFC),
    onBackground = Color(0xFF221C1D),
    surface = Color(0xFFFFFBFC),
    onSurface = Color(0xFF221C1D),
    surfaceVariant = Color(0xFFF4DDE2),
    onSurfaceVariant = Color(0xFF5C454B),
    surfaceTint = Color(0xFFB44D6E),
    inverseSurface = Color(0xFF342F30),
    inverseOnSurface = Color(0xFFF7F3F3),
    outline = Color(0xFF8F767B),
    outlineVariant = Color(0xFFE6CFD4),
    surfaceBright = Color(0xFFFFFBFC),
    surfaceDim = Color(0xFFDFD8DA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F2F3),
    surfaceContainer = Color(0xFFF3ECEE),
    surfaceContainerHigh = Color(0xFFEEE6E8),
    surfaceContainerHighest = Color(0xFFE8E0E2),
    scrim = Color.Black
)

// Rose — warm rose (seed #B44D6E).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val RoseDarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C5),
    onPrimary = Color(0xFF551D30),
    primaryContainer = Color(0xFF733347),
    onPrimaryContainer = Color(0xFFFFD9E1),
    inversePrimary = Color(0xFF8E4A5E),
    secondary = Color(0xFFE3BDC3),
    onSecondary = Color(0xFF422930),
    secondaryContainer = Color(0xFF5A4046),
    onSecondaryContainer = Color(0xFFFFD9E0),
    tertiary = Color(0xFFEDBE94),
    onTertiary = Color(0xFF47290A),
    tertiaryContainer = Color(0xFF65401E),
    onTertiaryContainer = Color(0xFFFFDCBE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFECDFE0),
    surface = Color.Black,
    onSurface = Color(0xFFECDFE0),
    surfaceVariant = Color(0xFF514347),
    onSurfaceVariant = Color(0xFFD6C2C6),
    surfaceTint = Color(0xFFFFB1C5),
    inverseSurface = Color(0xFFECDFE0),
    inverseOnSurface = Color(0xFF382E30),
    outline = Color(0xFF9E8C91),
    outlineVariant = Color(0xFF514347),
    surfaceBright = Color(0xFF3F3739),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF22191B),
    surfaceContainer = Color(0xFF261D1F),
    surfaceContainerHigh = Color(0xFF312829),
    surfaceContainerHighest = Color(0xFF3C3334),
    scrim = Color.Black
)

// Amber — warm amber (seed #9E6218).
private val AmberLightColors = lightColorScheme(
    primary = Color(0xFF9E6218),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2F1B01),
    inversePrimary = Color(0xFFF6BB81),
    secondary = Color(0xFF8C6C44),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4DFC9),
    onSecondaryContainer = Color(0xFF2C1D01),
    tertiary = Color(0xFF637731),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDEE7C0),
    onTertiaryContainer = Color(0xFF1C2200),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFCF9),
    onBackground = Color(0xFF211D1A),
    surface = Color(0xFFFFFCF9),
    onSurface = Color(0xFF211D1A),
    surfaceVariant = Color(0xFFF0E0D3),
    onSurfaceVariant = Color(0xFF57493B),
    surfaceTint = Color(0xFF9E6218),
    inverseSurface = Color(0xFF33302D),
    inverseOnSurface = Color(0xFFF6F3F1),
    outline = Color(0xFF8A796B),
    outlineVariant = Color(0xFFE1D2C5),
    surfaceBright = Color(0xFFFFFCF9),
    surfaceDim = Color(0xFFDED9D5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EF),
    surfaceContainer = Color(0xFFF1EDE9),
    surfaceContainerHigh = Color(0xFFECE7E3),
    surfaceContainerHighest = Color(0xFFE7E1DD),
    scrim = Color.Black
)

// Amber — warm amber (seed #9E6218).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val AmberDarkColors = darkColorScheme(
    primary = Color(0xFFFFB870),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    inversePrimary = Color(0xFF8B5000),
    secondary = Color(0xFFDEC1A0),
    onSecondary = Color(0xFF3E2D16),
    secondaryContainer = Color(0xFF57422A),
    onSecondaryContainer = Color(0xFFFADEBD),
    tertiary = Color(0xFFC0CD97),
    onTertiary = Color(0xFF2B3310),
    tertiaryContainer = Color(0xFF414A25),
    onTertiaryContainer = Color(0xFFDCE8B3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFEAE0D5),
    surface = Color.Black,
    onSurface = Color(0xFFEAE0D5),
    surfaceVariant = Color(0xFF50453A),
    onSurfaceVariant = Color(0xFFD4C3B1),
    surfaceTint = Color(0xFFFFB870),
    inverseSurface = Color(0xFFEAE0D5),
    inverseOnSurface = Color(0xFF3B2F26),
    outline = Color(0xFF9C8E80),
    outlineVariant = Color(0xFF50453A),
    surfaceBright = Color(0xFF3C3227),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF221A12),
    surfaceContainer = Color(0xFF261E16),
    surfaceContainerHigh = Color(0xFF31281F),
    surfaceContainerHighest = Color(0xFF3C332A),
    scrim = Color.Black
)

// Graphite — neutral graphite with cool and warm accent tints (seed #697077).
private val GraphiteLightColors = lightColorScheme(
    primary = Color(0xFF697077),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE3E7),
    onPrimaryContainer = Color(0xFF1C2024),
    inversePrimary = Color(0xFFC1C7CE),
    secondary = Color(0xFF6E708A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E1F1),
    onSecondaryContainer = Color(0xFF1C1E2F),
    tertiary = Color(0xFF846871),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEEDEE3),
    onTertiaryContainer = Color(0xFF2B1B21),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFCFD),
    onBackground = Color(0xFF1B1E20),
    surface = Color(0xFFFBFCFD),
    onSurface = Color(0xFF1B1E20),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF454C53),
    surfaceTint = Color(0xFF697077),
    inverseSurface = Color(0xFF2E3133),
    inverseOnSurface = Color(0xFFF2F4F5),
    outline = Color(0xFF767D84),
    outlineVariant = Color(0xFFCFD5DB),
    surfaceBright = Color(0xFFFBFCFD),
    surfaceDim = Color(0xFFD8DADC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F5),
    surfaceContainer = Color(0xFFECEEEF),
    surfaceContainerHigh = Color(0xFFE7E8EA),
    surfaceContainerHighest = Color(0xFFE1E3E5),
    scrim = Color.Black
)

// Graphite — neutral graphite with cool and warm accent tints (seed #697077).
// True-black (AMOLED) canvas; containers keep the M3 elevation order.
private val GraphiteDarkColors = darkColorScheme(
    primary = Color(0xFFC3C7CB),
    onPrimary = Color(0xFF2D3134),
    primaryContainer = Color(0xFF43474B),
    onPrimaryContainer = Color(0xFFE0E3E7),
    inversePrimary = Color(0xFF5A5F63),
    secondary = Color(0xFFC5C6DA),
    onSecondary = Color(0xFF2E3040),
    secondaryContainer = Color(0xFF444657),
    onSecondaryContainer = Color(0xFFE0E1F2),
    tertiary = Color(0xFFD6C0C7),
    onTertiary = Color(0xFF3C2A31),
    tertiaryContainer = Color(0xFF544048),
    onTertiaryContainer = Color(0xFFF4DCE4),
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

    // Dark schemes are authored as true black; AMOLED off lifts the canvas to
    // the tone-6 neutral so the elevation ladder reads in daylight.
    fun darkColors(amoled: Boolean = true): ColorScheme {
    val scheme = when (this) {
    Indigo -> IndigoDarkColors
    Sage -> SageDarkColors
    Rose -> RoseDarkColors
    Amber -> AmberDarkColors
    Graphite -> GraphiteDarkColors
    }
    return if (amoled) scheme else scheme.withoutAmoledBlack()
    }

    companion object {
    fun fromId(id: String?): ColorPalette =
        entries.firstOrNull { it.id == id } ?: Indigo
    }
}
