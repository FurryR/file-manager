package io.furryr.file.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.furryr.file.core.model.FileEntry

/** The one Composable surface a terminal tab gives the shell at commit time. */
typealias TerminalContent = @Composable (enabled: Boolean, modifier: Modifier) -> Unit

/** The shell surface an entering terminal tab commits its body to. */
interface TerminalContext {
    /** Bind the tab body to this tab; the last commit wins. */
    fun commit(content: TerminalContent)

    /** Open the shell's terminal drawer; the shell owns the current state. */
    fun requestOpenDrawer()
}

/**
 * One terminal tab.
 *
 * The shell owns the list through [Terminal]; a tab owns only its body, the
 * title the drawer shows and the moment it can be disposed. Terminal tabs
 * have no node, address or history, but they follow the same lifecycle as a
 * file renderer: [enter] prepares resources and commits the body, [leave]
 * releases the surface, and [dispose] ends the backing PTY or page.
 */
interface TerminalTab {
    /** Stable identity; the shell keys rows and the active tab by this. */
    val id: String

    /** Drawer label; the body may update it while a shell paints a title. */
    val title: String

    /** False when the backing PTY or page is gone; the shell drops the tab. */
    val alive: Boolean

    /**
     * Make this tab current. The first call starts the backing resource and
     * commits its body; later calls reuse the resource and re-commit the
     * body. Called on every activation, so it must be idempotent.
     */
    suspend fun enter(ctx: TerminalContext)

    /** The tab stopped being current; release the view, IME and focus. */
    fun leave() {}

    /** Paste a dropped entry into this tab; false when it cannot take it. */
    fun acceptDrop(entry: FileEntry): Boolean

    /** Stop the body and end the PTY, if this tab owns one. Idempotent. */
    fun dispose()
}
