package io.furryr.file.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

// ANSI palette handed to the embedded terminal. The 16 base colours keep their
// conventional hue families (red stays red, green stays green); each app
// palette only nudges hue by at most 8 degrees and scales chroma, so a palette
// switch restyles the terminal without breaking `ls`/`git` colours.
class TerminalPalette(
    private val ansi: IntArray,
    val foreground: Int,
    val background: Int,
    val cursor: Int
) {
    // TerminalColors.mCurrentColors layout: 16 ANSI + 16..255 colour cube +
    // default foreground (256), background (257) and cursor (258).
    fun applyTo(colors: IntArray) {
        if (colors.size < 259) return
        ansi.copyInto(colors, 0, 0, 16)
        colors[256] = foreground
        colors[257] = background
        colors[258] = cursor
    }
}

fun ColorScheme.terminalPalette(palette: ColorPalette): TerminalPalette {
    val dark = surface.luminance() < 0.5f
    return TerminalPalette(
        ansi = palette.terminalAnsi(dark),
        foreground = onSurface.toArgb(),
        background = surface.toArgb(),
        cursor = primary.toArgb()
    )
}

private fun ColorPalette.terminalAnsi(dark: Boolean): IntArray = when (this) {
    ColorPalette.Indigo -> if (dark) IndigoDarkAnsi else IndigoLightAnsi
    ColorPalette.Sage -> if (dark) SageDarkAnsi else SageLightAnsi
    ColorPalette.Rose -> if (dark) RoseDarkAnsi else RoseLightAnsi
    ColorPalette.Amber -> if (dark) AmberDarkAnsi else AmberLightAnsi
    ColorPalette.Graphite -> if (dark) GraphiteDarkAnsi else GraphiteLightAnsi
}

private val IndigoDarkAnsi = intArrayOf(
    0xFF333537.toInt(), // black
    0xFFC98C85.toInt(), // red
    0xFF7FA483.toInt(), // green
    0xFFB69760.toInt(), // yellow
    0xFF819EBD.toInt(), // blue
    0xFFAF91B7.toInt(), // magenta
    0xFF70A4A4.toInt(), // cyan
    0xFFDDDCDF.toInt(), // white
    0xFF6A6A6D.toInt(), // bright black
    0xFFFFB4AB.toInt(), // bright red
    0xFF96D5A0.toInt(), // bright green
    0xFFEFBF67.toInt(), // bright yellow
    0xFF99CBFF.toInt(), // bright blue
    0xFFE6B5F5.toInt(), // bright magenta
    0xFF79D5D5.toInt(), // bright cyan
    0xFFFAF9FC.toInt() // bright white
)

private val IndigoLightAnsi = intArrayOf(
    0xFF242628.toInt(), // black
    0xFF8A554F.toInt(), // red
    0xFF486B4E.toInt(), // green
    0xFF795F2E.toInt(), // yellow
    0xFF4A6682.toInt(), // blue
    0xFF755A7D.toInt(), // magenta
    0xFF386B6B.toInt(), // cyan
    0xFFF1F0F3.toInt(), // white
    0xFF515255.toInt(), // bright black
    0xFF75322C.toInt(), // bright red
    0xFF125129.toInt(), // bright green
    0xFF5E4200.toInt(), // bright yellow
    0xFF064A77.toInt(), // bright blue
    0xFF5E376D.toInt(), // bright magenta
    0xFF004F50.toInt(), // bright cyan
    0xFFC6C6C9.toInt() // bright white
)

private val SageDarkAnsi = intArrayOf(
    0xFF323534.toInt(), // black
    0xFFCC8B83.toInt(), // red
    0xFF7FA57E.toInt(), // green
    0xFFB7965B.toInt(), // yellow
    0xFF7AA0BE.toInt(), // blue
    0xFFAF91BC.toInt(), // magenta
    0xFF6CA69E.toInt(), // cyan
    0xFFDBDDDB.toInt(), // white
    0xFF686B69.toInt(), // bright black
    0xFFFFB4AB.toInt(), // bright red
    0xFF97D598.toInt(), // bright green
    0xFFF1BF61.toInt(), // bright yellow
    0xFF8DCDFE.toInt(), // bright blue
    0xFFE4B5FB.toInt(), // bright magenta
    0xFF74D7CC.toInt(), // bright cyan
    0xFFF8FAF7.toInt() // bright white
)

private val SageLightAnsi = intArrayOf(
    0xFF232625.toInt(), // black
    0xFF8C544E.toInt(), // red
    0xFF486C49.toInt(), // green
    0xFF7B5F29.toInt(), // yellow
    0xFF416783.toInt(), // blue
    0xFF745981.toInt(), // magenta
    0xFF326D66.toInt(), // cyan
    0xFFEFF1EE.toInt(), // white
    0xFF505351.toInt(), // bright black
    0xFF77312A.toInt(), // bright red
    0xFF165123.toInt(), // bright green
    0xFF5E4200.toInt(), // bright yellow
    0xFF004B6F.toInt(), // bright blue
    0xFF5D3772.toInt(), // bright magenta
    0xFF00504A.toInt(), // bright cyan
    0xFFC5C7C4.toInt() // bright white
)

private val RoseDarkAnsi = intArrayOf(
    0xFF3A3335.toInt(), // black
    0xFFD4877E.toInt(), // red
    0xFF84A56C.toInt(), // green
    0xFFB9964E.toInt(), // yellow
    0xFF7D9DCB.toInt(), // blue
    0xFFBA8CBC.toInt(), // magenta
    0xFF5DA7A8.toInt(), // cyan
    0xFFE6DADC.toInt(), // white
    0xFF71686A.toInt(), // bright black
    0xFFFFB4AB.toInt(), // bright red
    0xFFA2D580.toInt(), // bright green
    0xFFF1BF52.toInt(), // bright yellow
    0xFFA4C9FF.toInt(), // bright blue
    0xFFF4AFF8.toInt(), // bright magenta
    0xFF5DD8DA.toInt(), // bright cyan
    0xFFFFF8F8.toInt() // bright white
)

private val RoseLightAnsi = intArrayOf(
    0xFF2B2426.toInt(), // black
    0xFF92514A.toInt(), // red
    0xFF4D6C39.toInt(), // green
    0xFF7C5F1C.toInt(), // yellow
    0xFF45658F.toInt(), // blue
    0xFF7E5581.toInt(), // magenta
    0xFF1B6E6F.toInt(), // cyan
    0xFFFBEEF0.toInt(), // white
    0xFF595052.toInt(), // bright black
    0xFF7B2D26.toInt(), // bright red
    0xFF26500B.toInt(), // bright green
    0xFF5C4300.toInt(), // bright yellow
    0xFF004883.toInt(), // bright blue
    0xFF68306F.toInt(), // bright magenta
    0xFF004F50.toInt(), // bright cyan
    0xFFCFC4C6.toInt() // bright white
)

private val AmberDarkAnsi = intArrayOf(
    0xFF3A3430.toInt(), // black
    0xFFC98C85.toInt(), // red
    0xFF8AA377.toInt(), // green
    0xFFAE9A60.toInt(), // yellow
    0xFF869DBF.toInt(), // blue
    0xFFB490B4.toInt(), // magenta
    0xFF72A59A.toInt(), // cyan
    0xFFE5DBD5.toInt(), // white
    0xFF716965.toInt(), // bright black
    0xFFFFB4AB.toInt(), // bright red
    0xFFA9D28C.toInt(), // bright green
    0xFFE3C467.toInt(), // bright yellow
    0xFFA4C9FF.toInt(), // bright blue
    0xFFEDB3EF.toInt(), // bright magenta
    0xFF7DD6C4.toInt(), // bright cyan
    0xFFFFF8F5.toInt() // bright white
)

private val AmberLightAnsi = intArrayOf(
    0xFF2A2521.toInt(), // black
    0xFF8A554F.toInt(), // red
    0xFF536A43.toInt(), // green
    0xFF73622E.toInt(), // yellow
    0xFF4E6484.toInt(), // blue
    0xFF79597A.toInt(), // magenta
    0xFF3A6C62.toInt(), // cyan
    0xFFFAEFE9.toInt(), // white
    0xFF58514D.toInt(), // bright black
    0xFF75322C.toInt(), // bright red
    0xFF2D4F18.toInt(), // bright green
    0xFF574500.toInt(), // bright yellow
    0xFF1B4879.toInt(), // bright blue
    0xFF633568.toInt(), // bright magenta
    0xFF005046.toInt(), // bright cyan
    0xFFCFC4BF.toInt() // bright white
)

private val GraphiteDarkAnsi = intArrayOf(
    0xFF353535.toInt(), // black
    0xFFC98C85.toInt(), // red
    0xFF7FA483.toInt(), // green
    0xFFB69760.toInt(), // yellow
    0xFF819EBD.toInt(), // blue
    0xFFAF91B8.toInt(), // magenta
    0xFF70A4A3.toInt(), // cyan
    0xFFDFDCDC.toInt(), // white
    0xFF6C6A6A.toInt(), // bright black
    0xFFFFB4AB.toInt(), // bright red
    0xFF96D5A0.toInt(), // bright green
    0xFFEFBF67.toInt(), // bright yellow
    0xFF98CBFE.toInt(), // bright blue
    0xFFE6B5F5.toInt(), // bright magenta
    0xFF79D5D4.toInt(), // bright cyan
    0xFFFCF8F8.toInt() // bright white
)

private val GraphiteLightAnsi = intArrayOf(
    0xFF262626.toInt(), // black
    0xFF8A554F.toInt(), // red
    0xFF486B4E.toInt(), // green
    0xFF795F2E.toInt(), // yellow
    0xFF496682.toInt(), // blue
    0xFF755A7D.toInt(), // magenta
    0xFF386B6B.toInt(), // cyan
    0xFFF3F0F0.toInt(), // white
    0xFF535252.toInt(), // bright black
    0xFF75322C.toInt(), // bright red
    0xFF125129.toInt(), // bright green
    0xFF5E4200.toInt(), // bright yellow
    0xFF014A76.toInt(), // bright blue
    0xFF5E376E.toInt(), // bright magenta
    0xFF00504F.toInt(), // bright cyan
    0xFFC9C6C6.toInt() // bright white
)

