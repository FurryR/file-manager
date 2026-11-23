package io.furryr.file.core

/** One account a backend can resolve an owner or group id to. */
data class SystemAccount(val id: Int, val name: String)

/** Users and groups a backend published for its own filesystem namespace. */
data class SystemAccounts(
    val users: List<SystemAccount> = emptyList(),
    val groups: List<SystemAccount> = emptyList(),
)

/**
 * Optional POSIX metadata support.
 *
 * A provider that can change permissions, ownership and timestamps exposes
 * this interface; [io.furryr.file.core.model.FileEntry] carries the read-side
 * facts. The file manager asks the node's provider, so a backend that cannot
 * represent these facts simply does not implement it.
 */
interface MetadataProvider {
    suspend fun setMode(
        path: String,
        mode: Int,
        applyFiles: Boolean,
        applyDirs: Boolean,
    ): Result<Unit>

    suspend fun setOwner(
        path: String,
        uid: Int,
        gid: Int,
        applyFiles: Boolean,
        applyDirs: Boolean,
    ): Result<Unit>

    suspend fun setModifiedTime(
        path: String,
        modifiedAt: Long,
        applyFiles: Boolean,
        applyDirs: Boolean,
    ): Result<Unit>

    /** Users and groups this backend can address for the file at [path]. */
    suspend fun accounts(path: String): Result<SystemAccounts>
}
