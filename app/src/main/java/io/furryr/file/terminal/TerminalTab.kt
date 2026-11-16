package io.furryr.file.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.termux.terminal.TerminalSession
import io.furryr.file.core.model.FileEntry

/** The one Composable surface a terminal tab gives the shell at commit time. */
typealias TerminalContent = @Composable (enabled: Boolean, modifier: Modifier) -> Unit

/** The shell surface an entering terminal tab commits its body to. */
interface TerminalContext {
    /** Bind the tab body to this tab; the last commit wins. */
    fun commit(content: TerminalContent)

    /** Open the shell's terminal drawer; the shell owns the current state. */
    fun requestOpenDrawer()

    /** Ask the shell to unload this tab after the body decides. */
    fun requestClose()
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

    /**
     * Make this tab current. The first call starts the backing resource and
     * commits its body; later calls reuse the resource and re-commit the
     * body. Called on every activation, so it must be idempotent.
     */
    suspend fun enter(ctx: TerminalContext)

    /** The tab stopped being current; release the view, IME and focus. */
    fun blur() {}

    /** Paste a dropped entry into this tab; false when it cannot take it. */
    fun acceptDrop(entry: FileEntry): Boolean

    /** Ask the shell to unload this tab; the body calls it from its own UI. */
    fun requestClose() {}

    /** Ask whether this tab may be removed. No popup: false keeps the tab. */
    fun unload(): Boolean = true

    /** Stop the body and end the PTY, if this tab owns one. Idempotent. */
    fun dispose()
}

/**
 * A tab whose body is Termux's terminal surface.
 *
 * The sheet draws one shared surface for every such tab, so switching tabs
 * only rebinds the session. Composing a surface per tab would move the
 * AndroidView between parents, which Compose cannot do while the view still
 * has a parent.
 */
interface TerminalViewTab : TerminalTab {
    /** The PTY shown now; null while the tab is still connecting. */
    val session: TerminalSession?

    /** Per-tab key/modifier state; the shared surface binds to it. */
    val inputState: TerminalInputState

    /** True while a spawn is in flight. */
    val connecting: Boolean

    /** Spawn failure text, or null. */
    val error: String?

    /** True after the backing session ended abnormally; a normal exit closes the tab. */
    val connectionLost: Boolean

    /** True when the session is an SSH shell on a remote host. */
    val remote: Boolean

    /** Retry the spawn after [error]. */
    fun retry()

    /** Open the terminal drawer from the extra-key row. */
    fun requestOpenDrawer()
}
