package io.furryr.file.shell

import android.content.Context
import android.widget.Toast
import io.furryr.file.R
import io.furryr.file.clipboard.ClipboardAction
import io.furryr.file.clipboard.ClipboardStore
import io.furryr.file.clipboard.ClipboardTransfer
import io.furryr.file.core.model.FileEntry
import io.furryr.file.model.OperationProgress
import io.furryr.file.model.Page
import io.furryr.file.render.ListingHandle
import io.furryr.file.render.RendererBase
import io.furryr.file.ui.util.OperationNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Selection and clipboard operations for the active tab. The shell keeps the
 * progress state; this controller only runs the transfer and reports back.
 */
internal class ClipboardController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentRenderer: () -> RendererBase<*>?,
    private val currentJob: () -> Job?,
    private val allPages: () -> Sequence<Page>,
    private val onHidden: (Boolean) -> Unit,
    private val onJob: (Job?) -> Unit,
    private val onProgress: (OperationProgress?) -> Unit,
    private val onTaskIds: (List<Long>?) -> Unit,
    private val onRefresh: () -> Unit,
) {
    fun listing(): ListingHandle? = currentRenderer()?.listingHandle

    fun selectedEntries(): List<FileEntry> = listing()?.selectedEntries.orEmpty()

    fun clearSelection() {
        listing()?.clearSelection()
    }

    /** Clear the pending payload and any plain selection left behind. */
    fun clear() {
        ClipboardStore.clear()
        allPages().forEach { page ->
            page.renderer?.listingHandle?.clearDisplayedSelection()
        }
    }

    /** Run the standard clipboard list into the current renderer's sink. */
    fun paste() {
        val state = ClipboardStore.state
        if (!state.inTransfer) return
        val sink = currentRenderer()?.clipboardSink ?: return
        if (!sink.accepts(state)) return
        val folder = sink.destinationFolder() ?: return
        val isCopy = state.action == ClipboardAction.COPY
        onHidden(false)
        currentJob()?.cancel()
        val job = scope.launch {
            onProgress(
                OperationProgress(
                    0, 0, state.entries.firstOrNull()?.name.orEmpty(), isCopy,
                )
            )
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            var speed = 0L
            var failure: Throwable? = null
            try {
                ClipboardTransfer.run(state, folder) { progress ->
                    if (!progress.finished) {
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastTime
                        if (elapsed >= SPEED_SAMPLE_MS) {
                            val delta = progress.copiedBytes - lastBytes
                            if (delta >= 0) speed = delta * 1000 / elapsed
                            lastBytes = progress.copiedBytes
                            lastTime = now
                        }
                        onProgress(
                            OperationProgress(
                                progress.totalBytes,
                                progress.copiedBytes,
                                progress.currentName.ifBlank { state.entries.first().name },
                                isCopy,
                                speed,
                            )
                        )
                    }
                }.onFailure { failure = it }
            } catch (e: CancellationException) {
                OperationNotification.cancel(context)
                throw e
            } catch (e: Exception) {
                failure = e
            }
            OperationNotification.cancel(context)
            onTaskIds(null)
            Toast.makeText(
                context,
                if (failure == null) {
                    context.getString(
                        if (isCopy) R.string.toast_copied else R.string.toast_moved,
                        state.entries.firstOrNull()?.name.orEmpty(),
                    )
                } else {
                    failure?.message ?: context.getString(R.string.toast_failed)
                },
                Toast.LENGTH_SHORT
            ).show()
            if (failure == null) {
                clear()
            }
            onProgress(null)
            onJob(null)
            onRefresh()
        }
        onJob(job)
    }

    private companion object {
        /** Minimum window between two transfer-rate samples shown in the copy dialog. */
        const val SPEED_SAMPLE_MS = 250L
    }
}
