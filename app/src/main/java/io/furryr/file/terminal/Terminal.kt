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

    val active: TerminalTab?
        get() = tabs.firstOrNull { it.id == activeId }

    /** The sheet installs the opener for its current drawer state. */
    fun setDrawerOpener(opener: (() -> Unit)?) {
        drawerOpener = opener
    }

    fun contentFor(id: String): TerminalContent? = contents[id]

    fun open(tab: TerminalTab) {
        tabs += tab
        switchTo(tab)
    }

    fun activate(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        switchTo(tab)
    }

    /** Remove [id] and dispose its body; the neighbour becomes active. */
    fun close(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        enterJobs.remove(id)?.cancel()
        tab.leave()
        tab.dispose()
        contents.remove(id)
        if (activeId == id) {
            val next = tabs.getOrNull(index) ?: tabs.lastOrNull()
            activeId = next?.id
            next?.let(::launchEnter)
        }
    }

    /** Drop tabs whose backing PTY or page is gone. */
    fun prune() {
        tabs.filterNot { it.alive }.forEach { close(it.id) }
    }

    private fun switchTo(tab: TerminalTab) {
        val previous = active
        if (previous?.id == tab.id) return
        previous?.let {
            enterJobs.remove(it.id)?.cancel()
            it.leave()
        }
        activeId = tab.id
        launchEnter(tab)
    }

    private fun launchEnter(tab: TerminalTab) {
        enterJobs.remove(tab.id)?.cancel()
        val job = scope.launch {
            try {
                tab.enter(contextFor(tab.id))
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

    private fun contextFor(id: String): TerminalContext = object : TerminalContext {
        override fun commit(content: TerminalContent) {
            contents[id] = content
        }

        override fun requestOpenDrawer() {
            drawerOpener?.invoke()
        }
    }
}
