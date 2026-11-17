package io.furryr.file.terminal

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "Terminal"

/**
 * The one owner of terminal tabs.
 *
 * The sheet only renders this object and calls it. A tab owns its body; no
 * caller edits the list, picks an index or closes a tab on its own. Entering
 * and leaving are tab transitions, not sheet side effects: only the active
 * tab is entered, and a switch always leaves the previous tab first.
 */
internal object Terminal {
    val tabs = mutableStateListOf<TerminalTab>()

    var activeId by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val enterJobs = mutableMapOf<String, Job>()
    private val contents = mutableStateMapOf<String, TerminalContent>()
    private var drawerOpener: (() -> Unit)? = null
    private var closeRequester: ((String) -> Unit)? = null

    val active: TerminalTab?
        get() = tabs.firstOrNull { it.id == activeId }

    /** The sheet installs the opener for its current drawer state. */
    fun setDrawerOpener(opener: (() -> Unit)?) {
        drawerOpener = opener
    }

    /** The sheet installs the unload action a tab body can request. */
    fun setCloseRequester(requester: ((String) -> Unit)?) {
        closeRequester = requester
    }

    /**
     * Ask the shell to unload [id]. Unlike a tab's own [TerminalContext], this
     * also works for a background tab that is not currently entered. When no
     * shell is composed, remove the tab directly.
     */
    fun requestClose(id: String) {
        val requester = closeRequester
        if (requester != null) requester(id) else unload(id)
    }

    /**
     * Unload a tab whose backing resource ended on its own.
     *
     * Unlike [requestClose] this never reaches the shell, so a sheet that is
     * open at that moment stays open: it creates the terminal for the current
     * page instead of retracting as if the user closed the last tab. The
     * tab's own veto still applies through [unload].
     */
    fun discard(id: String): Boolean = unload(id)

    fun contentFor(id: String): TerminalContent? = contents[id]

    /** Reset each tab's non-persistable, Activity-bound resources. */
    fun resetTabs() {
        tabs.forEach { tab ->
            try {
                tab.reset()
            } catch (error: Throwable) {
                Log.w(TAG, "cannot reset terminal tab", error)
            }
        }
    }

    fun open(tab: TerminalTab) {
        tabs += tab
        switchTo(tab)
    }

    fun activate(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        switchTo(tab)
    }

    /** Remove [id] and dispose its body; false when the tab vetoed unloading. */
    fun unload(id: String): Boolean {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return false
        val tab = tabs[index]
        if (!tab.unload()) return false
        tabs.removeAt(index)
        enterJobs.remove(id)?.cancel()
        tab.blur()
        tab.dispose()
        contents.remove(id)
        if (activeId == id) {
            val next = tabs.getOrNull(index) ?: tabs.lastOrNull()
            activeId = next?.id
            next?.let(::launchEnter)
        }
        return true
    }

    private fun switchTo(tab: TerminalTab) {
        val previous = active
        if (previous?.id == tab.id) return
        previous?.let {
            enterJobs.remove(it.id)?.cancel()
            it.blur()
        }
        activeId = tab.id
        launchEnter(tab)
    }

    private fun launchEnter(tab: TerminalTab) {
        enterJobs.remove(tab.id)?.cancel()
        val job = scope.launch {
            // Commit is the tab's "I have a body" signal. A content tab that
            // does not commit (its page is gone) is discarded; the sheet then
            // creates the terminal the user asked for instead of retracting.
            var committed = false
            val context = object : TerminalContext {
                override fun commit(content: TerminalContent) {
                    committed = true
                    contents[tab.id] = content
                }

                override fun requestOpenDrawer() {
                    drawerOpener?.invoke()
                }

                override fun requestClose() {
                    Terminal.requestClose(tab.id)
                }
            }
            try {
                tab.enter(context)
                if (tab !is TerminalViewTab && !committed) discard(tab.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Terminal tab enter failed", e)
            }
        }
        enterJobs[tab.id] = job
        job.invokeOnCompletion {
            if (enterJobs[tab.id] === job) enterJobs.remove(tab.id)
        }
    }
}
