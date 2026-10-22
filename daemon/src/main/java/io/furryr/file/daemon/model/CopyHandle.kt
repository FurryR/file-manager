package io.furryr.file.daemon.model

import io.furryr.file.core.model.CopyProgress
import kotlinx.coroutines.flow.Flow

/**
 * A running daemon streaming operation (copy/move) identified by its request id.
 * Collect [progress]; call [cancel] to ask the daemon to abort it.
 *
 * Cancellation is connection-scoped: the daemon only aborts a request issued on
 * the same connection with this id. [cancel] is best-effort — a request that has
 * already finished reports a daemon-side "not cancellable".
 */
class CopyHandle internal constructor(
    val id: Long,
    val progress: Flow<CopyProgress>,
    private val onCancel: suspend () -> Result<Unit>,
) {
    suspend fun cancel(): Result<Unit> = onCancel()
}
