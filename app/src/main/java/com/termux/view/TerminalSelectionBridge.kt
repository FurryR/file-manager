package com.termux.view

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.WcWidth

/**
 * Access to Termux's selection controller from the app.
 *
 * The controller is package-private in `terminal-view`; this file lives in the
 * same package so it can read the live selection without reflection, then
 * exposes a small stable surface to the terminal UI.
 */
object TerminalSelectionBridge {
    /** Selected text, trimmed, or an empty string when nothing is selected. */
    fun selectedText(view: TerminalView): String {
        val controller = controller(view) ?: return ""
        val selectors = IntArray(4)
        controller.getSelectors(selectors)
        // getSelectors: [startRow, endRow, startColumn, endColumn]
        return view.currentSession?.emulator
            ?.getSelectedText(selectors[2], selectors[0], selectors[3], selectors[1])
            ?.trim()
            .orEmpty()
    }

    /** Select the whole visible screen without touching the transcript. */
    fun selectAll(view: TerminalView) {
        val controller = controller(view) ?: return
        val emulator = view.currentSession?.emulator ?: return
        setInt(controller, "mSelX1", 0)
        setInt(controller, "mSelY1", 0)
        setInt(controller, "mSelX2", emulator.mColumns - 1)
        setInt(controller, "mSelY2", emulator.mRows - 1)
        controller.render()
        view.invalidate()
    }

    /** True when the visible screen contains a link the cursor could reach. */
    fun hasUrl(view: TerminalView): Boolean = nearestUrl(view) != null

    /**
     * Select the link nearest the terminal cursor (screen rows, wrapped lines
     * joined), so Copy/Share/Open link act on it without dragging handles.
     * Selection mode stays active; the caller decides when to leave it.
     */
    fun selectNearestUrl(view: TerminalView): Boolean {
        val span = nearestUrl(view) ?: return false
        val controller = controller(view) ?: return false
        setInt(controller, "mSelX1", span.startColumn)
        setInt(controller, "mSelY1", span.startRow)
        setInt(controller, "mSelX2", span.endColumn)
        setInt(controller, "mSelY2", span.endRow)
        controller.render()
        view.invalidate()
        return true
    }

    /** One link on the screen; rows are screen coordinates, columns inclusive. */
    private data class UrlSpan(
        val startRow: Int,
        val startColumn: Int,
        val endRow: Int,
        val endColumn: Int,
    )

    private val URL_REGEX = Regex("(?i)(?:https?://|www\\.)[^\\s<>\"'`]+")

    private fun nearestUrl(view: TerminalView): UrlSpan? {
        val emulator = view.currentSession?.emulator ?: return null
        val cursorRow = emulator.getCursorRow()
        val cursorColumn = emulator.getCursorCol()
        val columnCount = emulator.mColumns
        var best: UrlSpan? = null
        var bestDistance = Int.MAX_VALUE
        for (span in visibleUrls(view, emulator)) {
            val rowDistance = when {
                cursorRow < span.startRow -> span.startRow - cursorRow
                cursorRow > span.endRow -> cursorRow - span.endRow
                else -> 0
            }
            val columnDistance = when {
                cursorColumn < span.startColumn -> span.startColumn - cursorColumn
                cursorColumn > span.endColumn -> cursorColumn - span.endColumn
                else -> 0
            }
            // A row is worth more than any column offset, so the nearest line
            // wins before the nearest link on that line.
            val distance = rowDistance * (columnCount + 1) + columnDistance
            if (distance < bestDistance) {
                bestDistance = distance
                best = span
            }
        }
        return best
    }

    /**
     * Scan the visible lines, joining rows the terminal wrapped. The screen is
     * addressed by cells, so a character offset is mapped through its display
     * width; a URL itself is single-width text.
     */
    private fun visibleUrls(view: TerminalView, emulator: TerminalEmulator): List<UrlSpan> {
        val screen = emulator.getScreen() ?: return emptyList()
        val columns = emulator.mColumns.coerceAtLeast(1)
        val top = topRow(view)
        val rowCount = emulator.mRows
        val spans = ArrayList<UrlSpan>()
        var offset = 0
        while (offset < rowCount) {
            val segments = ArrayList<Pair<Int, String>>()
            var row = top + offset
            while (true) {
                segments += row to emulator.getSelectedText(0, row, columns - 1, row)
                if (!screen.getLineWrap(row) || segments.size >= rowCount - offset) break
                row++
                offset++
            }
            offset++
            val text = segments.joinToString("") { it.second }
            if (text.isEmpty()) continue

            val rowOf = IntArray(text.length)
            val columnOf = IntArray(text.length)
            var base = 0
            for ((segmentRow, segment) in segments) {
                var cell = 0
                var index = 0
                while (index < segment.length) {
                    rowOf[base + index] = segmentRow
                    columnOf[base + index] = cell
                    val codePoint = Character.codePointAt(segment, index)
                    val next = index + Character.charCount(codePoint)
                    if (next > index + 1 && next < segment.length) {
                        // A surrogate pair spans two cells in the row buffer but
                        // one on screen; both map to the code point's first cell.
                        rowOf[base + index + 1] = segmentRow
                        columnOf[base + index + 1] = cell
                    }
                    cell += WcWidth.width(codePoint).coerceAtLeast(0)
                    index = next
                }
                base += segment.length
            }

            for (match in URL_REGEX.findAll(text)) {
                val url = trimUrl(match.value)
                if (url.length <= schemeLength(url)) continue
                val start = match.range.first
                val last = start + url.length - 1
                if (last < 0 || last >= rowOf.size) continue
                spans += UrlSpan(
                    startRow = rowOf[start],
                    startColumn = columnOf[start],
                    endRow = rowOf[last],
                    endColumn = columnOf[last] + cellWidth(text, last) - 1,
                )
            }
        }
        return spans
    }

    /** Display width of the character at [index], counting a surrogate pair once. */
    private fun cellWidth(text: String, index: Int): Int {
        val start = if (Character.isLowSurrogate(text[index]) && index > 0) index - 1 else index
        return WcWidth.width(Character.codePointAt(text, start)).coerceAtLeast(1)
    }

    private fun schemeLength(url: String): Int = when {
        url.startsWith("www.", ignoreCase = true) -> 4
        url.startsWith("https://", ignoreCase = true) -> 8
        else -> 7
    }

    /** Strip sentence punctuation and unmatched closing brackets from a link. */
    private fun trimUrl(raw: String): String {
        var end = raw.length
        while (end > 0 && raw[end - 1] in ".,;:!?'\"…。，、；：") end--
        while (end > 0) {
            val closing = raw[end - 1]
            val opening = when (closing) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                else -> break
            }
            val body = raw.substring(0, end)
            if (body.count { it == opening } >= body.count { it == closing }) break
            end--
        }
        return raw.substring(0, end)
    }

    private fun topRow(view: TerminalView): Int =
        runCatching {
            TerminalView::class.java
                .getDeclaredField("mTopRow")
                .apply { isAccessible = true }
                .getInt(view)
        }.getOrDefault(0)

    private fun controller(view: TerminalView): com.termux.view.textselection.TextSelectionCursorController? =
        runCatching {
            TerminalView::class.java
                .getDeclaredMethod("getTextSelectionCursorController")
                .apply { isAccessible = true }
                .invoke(view) as? com.termux.view.textselection.TextSelectionCursorController
        }.getOrNull()

    private fun setInt(target: Any, name: String, value: Int) {
        runCatching {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.setInt(target, value)
        }
    }
}
