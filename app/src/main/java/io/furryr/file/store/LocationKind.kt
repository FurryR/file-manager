package io.furryr.file.store

import android.content.Context
import io.furryr.file.core.FileProvider

/**
 * One drawer location, independent of the model its store persists.
 *
 * The store keeps typed records; this projection is what
 * [LocationsRepository] turns into a [io.furryr.file.core.LocationEntry]. A new
 * provider kind contributes records and actions, never UI code.
 */
data class LocationRecord(
    val id: String,
    val title: String,
    val subtitle: String,
    val root: String,
    /** Drawer group key: `local`, `saf`, `ftp`, `sftp`, `alist`, `ssh`, ...
     *  A kind may report several groups (the remote store reports one per
     *  connection protocol).
     */
    val kind: String,
    val provider: FileProvider,
)

/**
 * A family of persisted locations. [LocationsRepository] owns entry and action
 * assembly, so every kind is presented and mutated the same way.
 *
 * Actions are `suspend`-free on purpose: the repository adapts them into
 * [io.furryr.file.core.LocationActions] and the caller supplies the dispatcher.
 */
interface LocationKind {
    /** Registry key; one kind may emit records in several `kind` groups. */
    val id: String

    fun records(context: Context): List<LocationRecord>

    fun rename(id: String, name: String): Result<Unit>

    fun delete(id: String): Result<Unit>

    fun reorder(ids: List<String>): Result<Unit> = Result.success(Unit)
}
