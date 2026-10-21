package io.furryr.file

import io.furryr.file.provider.FtpProvider
import io.furryr.file.model.FtpServer
import io.furryr.file.provider.SAFProvider
import io.furryr.file.model.SafLocation
import io.furryr.file.provider.SftpProvider
import io.furryr.file.model.SftpServer
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.provider.remote.RemoteLocationStore
import io.furryr.file.provider.remote.RemoteProviders
import io.furryr.file.model.LocalLocation

/**
 * App-facing facade over the provider location stores (SAF trees, FTP/SFTP
 * servers, encrypted AList/SSH connections), so the UI layer never touches
 * provider singletons or their stores directly.
 *
 * Requires [io.furryr.file.provider.installFileProviders] to have run.
 */
object LocationsRepository {

    // ── SAF ─────────────────────────────────────────────────────────

    fun safLocations(): List<SafLocation> = SAFProvider.getDatabase().getAll()

    fun insertSaf(treeUri: String): String = SAFProvider.getDatabase().insert(treeUri)

    fun renameSaf(hexId: String, name: String) = SAFProvider.getDatabase().rename(hexId, name)

    fun deleteSaf(hexId: String) = SAFProvider.getDatabase().delete(hexId)

    fun reorderSaf(hexIds: List<String>) = SAFProvider.getDatabase().reorder(hexIds)

    // ── Local paths ─────────────────────────────────────────────────

    fun localLocations(): List<LocalLocation> = LocalLocationStore.all()

    fun insertLocal(path: String, name: String = ""): String = LocalLocationStore.add(path, name)

    fun renameLocal(id: String, name: String) = LocalLocationStore.rename(id, name)

    fun deleteLocal(id: String) = LocalLocationStore.delete(id)

    fun reorderLocal(ids: List<String>) = LocalLocationStore.reorder(ids)

    // ── FTP ─────────────────────────────────────────────────────────

    fun ftpServers(): List<FtpServer> = FtpProvider.getDatabase().getAll()

    fun insertFtp(host: String, name: String, port: Int, username: String, password: String) =
        FtpProvider.getDatabase().insert(host, name, port, username, password)

    fun updateFtp(
        host: String, newHost: String, name: String, port: Int, username: String, password: String,
    ) = FtpProvider.getDatabase().update(host, newHost, name, port, username, password)

    fun deleteFtp(host: String) = FtpProvider.getDatabase().delete(host)

    fun reorderFtp(hosts: List<String>) = FtpProvider.getDatabase().reorder(hosts)

    // ── SFTP ────────────────────────────────────────────────────────

    fun sftpServers(): List<SftpServer> = SftpProvider.getDatabase().getAll()

    fun insertSftp(host: String, name: String, port: Int, username: String, password: String) =
        SftpProvider.getDatabase().insert(host, name, port, username, password)

    fun updateSftp(
        host: String, newHost: String, name: String, port: Int, username: String, password: String,
    ) = SftpProvider.getDatabase().update(host, newHost, name, port, username, password)

    fun deleteSftp(host: String) = SftpProvider.getDatabase().delete(host)

    fun reorderSftp(hosts: List<String>) = SftpProvider.getDatabase().reorder(hosts)

    // ── Encrypted remote connections (AList / SSH) ──────────────────

    fun remoteLocations(): List<RemoteLocation> = RemoteLocationStore.all()

    fun insertRemote(location: RemoteLocation) = RemoteLocationStore.put(location)

    fun updateRemote(location: RemoteLocation) = RemoteLocationStore.put(location)

    fun deleteRemote(id: String) = RemoteLocationStore.remove(id)

    fun reorderRemote(ids: List<String>) = RemoteLocationStore.reorder(ids)

    suspend fun verifyRemote(location: RemoteLocation): Result<Unit> = RemoteProviders.verify(location)
}
