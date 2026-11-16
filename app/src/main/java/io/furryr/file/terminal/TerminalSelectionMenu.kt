package io.furryr.file.terminal

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import com.termux.view.TerminalSelectionBridge
import com.termux.view.TerminalView
import io.furryr.file.R
import java.util.Locale

/**
 * Text selection actions for the system ActionMode.
 *
 * Termux's selection controller builds a floating ActionMode toolbar; the ASM
 * transform in `app/build.gradle.kts` replaces its items with the app's
 * browser-style actions when the mode is created and refreshes their state on
 * every selection change. Copy and Paste stay visible; the rest collapse into
 * the floating toolbar's overflow when they do not fit, and installed
 * `ACTION_PROCESS_TEXT` apps (translate, etc.) are offered in that overflow.
 *
 * The read-only transcript is never edited in place: an action either copies
 * the selection, hands it to another app, or writes it back to the session as
 * input.
 */
object TerminalSelectionMenu {
    private const val ID_COPY = 101
    private const val ID_PASTE = 102
    private const val ID_SELECT_ALL = 103
    private const val ID_WEB_SEARCH = 104
    private const val ID_SHARE = 105
    private const val ID_OPEN_LINK = 106
    private const val ID_SELECT_URL = 107

    /** Process-text candidates are numbered from here; they only live in the overflow. */
    private const val ID_PROCESS_TEXT = 1000

    private const val ACTION_BUTTON =
        MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT
    private const val IF_ROOM =
        MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT

    /**
     * Build the action list for a new selection ActionMode.
     *
     * Copy and Paste are the action buttons of the floating toolbar; Select all
     * and Web search are pinned to its overflow, so the toolbar always has an
     * overflow button (the floating toolbar only renders it while at least one
     * item lives there). Share and Open link use the remaining width.
     */
    @JvmStatic
    fun populate(view: TerminalView, menu: Menu) {
        val context = view.context
        menu.clear()
        add(menu, ID_COPY, 0, context.getString(R.string.copy_text), ACTION_BUTTON) {
            copy(view)
        }
        add(menu, ID_PASTE, 1, context.getString(R.string.paste_text), ACTION_BUTTON) {
            paste(view)
        }
        add(menu, ID_SELECT_ALL, 2, context.getString(R.string.select_all_text), MenuItem.SHOW_AS_ACTION_NEVER) {
            selectAll(view)
        }
        add(menu, ID_SELECT_URL, 3, context.getString(R.string.terminal_selection_select_url), MenuItem.SHOW_AS_ACTION_NEVER) {
            TerminalSelectionBridge.selectNearestUrl(view)
        }
        add(menu, ID_SHARE, 4, context.getString(R.string.share_text), IF_ROOM) {
            share(view)
        }
        add(menu, ID_WEB_SEARCH, 5, context.getString(R.string.web_search_text), MenuItem.SHOW_AS_ACTION_NEVER) {
            webSearch(view)
        }
        add(menu, ID_OPEN_LINK, 6, context.getString(R.string.terminal_selection_open_link), IF_ROOM) {
            openLink(view)
        }
        addProcessText(view, menu)
        refresh(view, menu)
    }

    /**
     * The selection or the session changed: only content-dependent items move.
     * Called from `onPrepareActionMode`, which Termux invalidates on every
     * handle drag, so "Open link" follows what is actually selected.
     */
    @JvmStatic
    fun refresh(view: TerminalView, menu: Menu) {
        val text = TerminalSelectionBridge.selectedText(view)
        val hasText = text.isNotEmpty()
        val canProcess = hasText && view.currentSession?.isRunning == true
        menu.findItem(ID_COPY)?.isEnabled = hasText
        menu.findItem(ID_PASTE)?.isEnabled = clipboardHasText(view.context)
        menu.findItem(ID_SHARE)?.isEnabled = hasText
        menu.findItem(ID_WEB_SEARCH)?.isEnabled = hasText
        menu.findItem(ID_OPEN_LINK)?.isVisible = linkIn(text) != null
        menu.findItem(ID_SELECT_URL)?.isVisible = TerminalSelectionBridge.hasUrl(view)
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index)
            if (item.itemId >= ID_PROCESS_TEXT) item.isEnabled = canProcess
        }
    }

    private fun add(
        menu: Menu,
        id: Int,
        order: Int,
        title: CharSequence,
        flags: Int,
        action: () -> Unit,
    ) {
        val item = menu.add(Menu.NONE, id, order, title)
        item.setShowAsAction(flags)
        item.setOnMenuItemClickListener {
            action()
            true
        }
    }

    /**
     * Offer the apps that can process the selected text, the same candidates
     * WebView puts in its selection menu. They stay in the overflow so the
     * toolbar row keeps a stable set of app actions, and the processed result
     * is not read back: the transcript is read-only.
     */
    private fun addProcessText(view: TerminalView, menu: Menu) {
        val context = view.context
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val handlers = runCatching { pm.queryIntentActivities(query, 0) }
            .getOrNull()
            .orEmpty()
        handlers.forEachIndexed { index, info ->
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) return@forEachIndexed
            // The floating toolbar draws an item icon only when the item belongs
            // to the system text-assist group (FloatingToolbarPopup.shouldShowIcon),
            // and it pulls the menu only when it is shown, so the icon must be
            // loaded here, not after the toolbar appears. The item id stays ours:
            // the framework reserves android.R.id.textAssist itself for ordering.
            val item = menu.add(
                android.R.id.textAssist,
                ID_PROCESS_TEXT + index,
                ID_PROCESS_TEXT + index,
                label,
            )
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            item.icon = info.loadIcon(pm)
            item.setOnMenuItemClickListener {
                processText(view, info)
                true
            }
        }
    }

    private fun processText(view: TerminalView, info: ResolveInfo) {
        val text = TerminalSelectionBridge.selectedText(view)
        if (text.isEmpty()) return
        view.stopTextSelectionMode()
        val activity = info.activityInfo ?: return
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            .setComponent(ComponentName(activity.packageName, activity.name))
        launch(view.context, intent)
    }

    private fun copy(view: TerminalView) {
        val text = TerminalSelectionBridge.selectedText(view)
        if (text.isNotEmpty()) view.currentSession?.onCopyTextToClipboard(text)
        view.stopTextSelectionMode()
    }

    private fun paste(view: TerminalView) {
        view.stopTextSelectionMode()
        view.currentSession?.onPasteTextFromClipboard()
    }

    private fun selectAll(view: TerminalView) {
        TerminalSelectionBridge.selectAll(view)
    }

    private fun share(view: TerminalView) {
        val text = TerminalSelectionBridge.selectedText(view)
        if (text.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        launch(view.context, Intent.createChooser(send, view.context.getString(R.string.share_text)))
        view.stopTextSelectionMode()
    }

    private fun webSearch(view: TerminalView) {
        val text = TerminalSelectionBridge.selectedText(view)
        if (text.isEmpty()) return
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, text)
        launch(view.context, intent)
        view.stopTextSelectionMode()
    }

    private fun openLink(view: TerminalView) {
        val url = linkIn(TerminalSelectionBridge.selectedText(view)) ?: return
        view.stopTextSelectionMode()
        launch(view.context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /**
     * The selected text as a single URL, or null. Terminal output wraps and
     * decorates links, so surrounding quotes/brackets and trailing punctuation
     * are stripped; an embedded space means it is not one link.
     */
    private fun linkIn(text: String): String? {
        val candidate = text.trim()
            .removeSurrounding("<", ">")
            .removeSurrounding("\"")
            .trimEnd('.', ',', ';', ':', ')', ']', '}', '\'', '"')
        if (candidate.isEmpty() || candidate.any { it.isWhitespace() }) return null
        val lower = candidate.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> candidate
            lower.startsWith("www.") -> "https://$candidate"
            else -> null
        }
    }

    private fun clipboardHasText(context: Context): Boolean =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.hasPrimaryClip() == true

    private fun launch(context: Context, intent: Intent) {
        runCatching {
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
