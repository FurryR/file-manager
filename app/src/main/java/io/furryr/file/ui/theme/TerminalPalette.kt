package io.furryr.file.ui.theme

// ANSI palette handed to the embedded terminal. A scheme is self-contained:
// 16 ANSI colours plus the default foreground, background and cursor. The
// terminal therefore keeps its own identity and does not follow the app's
// Material palette.
class TerminalPalette(
    val ansi: IntArray,
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

// Well-known terminal themes: the xterm.js default plus the schemes published
// by Solarized, Dracula, Nord, One Dark, Gruvbox, Tokyo Night, Monokai,
// Catppuccin and GitHub, so users recognise them from other terminals.
enum class TerminalScheme(
    val id: String,
    val displayName: String,
    private val ansi: IntArray,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
) {
    // xterm.js defaults (Tango ANSI set).
    Xterm(
        id = "xterm",
        displayName = "Xterm",
        ansi = intArrayOf(
            0xFF2E3436.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(),
            0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt(),
            0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(),
            0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt(),
        ),
        foreground = 0xFFFFFFFF.toInt(),
        background = 0xFF000000.toInt(),
        cursor = 0xFFFFFFFF.toInt(),
    ),

    Dracula(
        id = "dracula",
        displayName = "Dracula",
        ansi = intArrayOf(
            0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
            0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(),
            0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
            0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt(),
        ),
        foreground = 0xFFF8F8F2.toInt(),
        background = 0xFF282A36.toInt(),
        cursor = 0xFFF8F8F2.toInt(),
    ),

    SolarizedDark(
        id = "solarized_dark",
        displayName = "Solarized Dark",
        ansi = intArrayOf(
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
            0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt(),
        ),
        foreground = 0xFF839496.toInt(),
        background = 0xFF002B36.toInt(),
        cursor = 0xFF839496.toInt(),
    ),

    SolarizedLight(
        id = "solarized_light",
        displayName = "Solarized Light",
        ansi = intArrayOf(
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
            0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt(),
        ),
        foreground = 0xFF657B83.toInt(),
        background = 0xFFFDF6E3.toInt(),
        cursor = 0xFF657B83.toInt(),
    ),

    Nord(
        id = "nord",
        displayName = "Nord",
        ansi = intArrayOf(
            0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
            0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt(),
        ),
        foreground = 0xFFD8DEE9.toInt(),
        background = 0xFF2E3440.toInt(),
        cursor = 0xFFD8DEE9.toInt(),
    ),

    OneDark(
        id = "one_dark",
        displayName = "One Dark",
        ansi = intArrayOf(
            0xFF1E2127.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFD19A66.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
            0xFF5C6370.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFD19A66.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFFFFFFF.toInt(),
        ),
        foreground = 0xFFABB2BF.toInt(),
        background = 0xFF282C34.toInt(),
        cursor = 0xFF528BFF.toInt(),
    ),

    GruvboxDark(
        id = "gruvbox_dark",
        displayName = "Gruvbox Dark",
        ansi = intArrayOf(
            0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
            0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
            0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
            0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt(),
        ),
        foreground = 0xFFEBDBB2.toInt(),
        background = 0xFF282828.toInt(),
        cursor = 0xFFEBDBB2.toInt(),
    ),

    TokyoNight(
        id = "tokyo_night",
        displayName = "Tokyo Night",
        ansi = intArrayOf(
            0xFF15161E.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
            0xFF414868.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFC0CAF5.toInt(),
        ),
        foreground = 0xFFC0CAF5.toInt(),
        background = 0xFF1A1B26.toInt(),
        cursor = 0xFFC0CAF5.toInt(),
    ),

    Monokai(
        id = "monokai",
        displayName = "Monokai",
        ansi = intArrayOf(
            0xFF272822.toInt(), 0xFFF92672.toInt(), 0xFFA6E22E.toInt(), 0xFFF4BF75.toInt(),
            0xFF66D9EF.toInt(), 0xFFAE81FF.toInt(), 0xFFA1EFE4.toInt(), 0xFFF8F8F2.toInt(),
            0xFF75715E.toInt(), 0xFFF92672.toInt(), 0xFFA6E22E.toInt(), 0xFFF4BF75.toInt(),
            0xFF66D9EF.toInt(), 0xFFAE81FF.toInt(), 0xFFA1EFE4.toInt(), 0xFFF9F8F5.toInt(),
        ),
        foreground = 0xFFF8F8F2.toInt(),
        background = 0xFF272822.toInt(),
        cursor = 0xFFF8F8F0.toInt(),
    ),

    CatppuccinMocha(
        id = "catppuccin_mocha",
        displayName = "Catppuccin Mocha",
        ansi = intArrayOf(
            0xFF45475A.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
            0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFBAC2DE.toInt(),
            0xFF585B70.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
            0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFA6ADC8.toInt(),
        ),
        foreground = 0xFFCDD6F4.toInt(),
        background = 0xFF1E1E2E.toInt(),
        cursor = 0xFFF5E0DC.toInt(),
    ),

    GithubLight(
        id = "github_light",
        displayName = "GitHub Light",
        ansi = intArrayOf(
            0xFF24292F.toInt(), 0xFFCF222E.toInt(), 0xFF116329.toInt(), 0xFF4D2D00.toInt(),
            0xFF0969DA.toInt(), 0xFF8250DF.toInt(), 0xFF1B7C83.toInt(), 0xFF6E7781.toInt(),
            0xFF57606A.toInt(), 0xFFA40E26.toInt(), 0xFF1A7F37.toInt(), 0xFF633C01.toInt(),
            0xFF218BFF.toInt(), 0xFFA475F9.toInt(), 0xFF3192AA.toInt(), 0xFF8C959F.toInt(),
        ),
        foreground = 0xFF24292F.toInt(),
        background = 0xFFFFFFFF.toInt(),
        cursor = 0xFF24292F.toInt(),
    );

    fun palette(): TerminalPalette = TerminalPalette(ansi, foreground, background, cursor)

    companion object {
        fun fromId(id: String?): TerminalScheme =
            entries.firstOrNull { it.id == id } ?: Xterm
    }
}
