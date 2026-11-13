package io.furryr.file.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspend work: a cancelled node walk, listing or archive
 * open must abort the coroutine, never become a `Result.failure` that the UI
 * shows as an ordinary error.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
