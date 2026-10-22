package io.furryr.file.core

/** One user-visible location backed by a [provider] and a canonical [root]. */
data class LocationEntry(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val root: String,
    val provider: FileProvider,
    val kind: String,
    val actions: LocationActions? = null,
)

/** Library-level location actions; UI renders dialogs and invokes these. */
interface LocationActions {
    suspend fun rename(name: String): Result<Unit>
    suspend fun delete(): Result<Unit>
}
