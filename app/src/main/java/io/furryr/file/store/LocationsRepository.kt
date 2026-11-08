package io.furryr.file.store

import android.content.Context
import io.furryr.file.core.FileProvider
import io.furryr.file.core.LocationActions
import io.furryr.file.core.LocationEntry
import io.furryr.file.provider.FtpProvider
import io.furryr.file.provider.SAFProvider
import io.furryr.file.provider.SftpProvider
import io.furryr.file.provider.model.FtpServer
import io.furryr.file.provider.model.LocalLocation
import io.furryr.file.provider.model.SafLocation
import io.furryr.file.provider.model.SftpServer
import io.furryr.file.provider.model.WebLocation
import io.furryr.file.provider.remote.RemoteKind
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.provider.remote.RemoteProviders

/**
 * App-facing facade over the provider location stores (SAF trees, FTP/SFTP
 * servers, encrypted AList/SSH connections), so the UI layer never touches
 * provider singletons or their stores directly.
 *
 * Requires [LocationStores] and [Vfs][io.furryr.file.core.Vfs] providers to
 * have been installed by the app.
 */
object LocationsRepository {

    /** Unified gallery entries; actions are implemented by this library. */
    fun locations(context: Context, localProvider: FileProvider): List<LocationEntry> {
        val entries = mutableListOf<LocationEntry>()

        localLocations().forEach { location ->
            entries += LocationEntry(
                id = location.id,
                title = location.name.ifBlank { location.path },
                subtitle = location.path,
                root = location.path,
                provider = localProvider,
                kind = "local",
                actions = object : LocationActions {
                    override suspend fun rename(name: String): Result<Unit> =
                        runCatching { renameLocal(location.id, name) }

                    override suspend fun delete(): Result<Unit> =
                        runCatching { deleteLocal(location.id) }
                },
            )
        }

        safLocations().forEach { location ->
            entries += LocationEntry(
                id = location.hexId,
                title = location.name,
                subtitle = location.treeUri,
                root = "saf://${location.hexId}",
                provider = SAFProvider,
                kind = "saf",
                actions = object : LocationActions {
                    override suspend fun rename(name: String): Result<Unit> =
                        runCatching { renameSaf(location.hexId, name) }

                    override suspend fun delete(): Result<Unit> =
                        runCatching { deleteSaf(location.hexId) }
                },
            )
        }

        ftpServers().forEach { server ->
            entries += LocationEntry(
                id = server.host,
                title = server.name,
                subtitle = "ftp://${server.host}:${server.port}",
                root = "ftp://${server.host}",
                provider = FtpProvider,
                kind = "ftp",
                actions = object : LocationActions {
                    override suspend fun rename(name: String): Result<Unit> = runCatching {
                        updateFtp(server.host, server.host, name, server.port, server.username, server.password)
                    }

                    override suspend fun delete(): Result<Unit> =
                        runCatching { deleteFtp(server.host) }
                },
            )
        }

        sftpServers().forEach { server ->
            entries += LocationEntry(
                id = server.host,
                title = server.name,
                subtitle = "sftp://${server.host}:${server.port}",
                root = "sftp://${server.host}",
                provider = SftpProvider,
                kind = "sftp",
                actions = object : LocationActions {
                    override suspend fun rename(name: String): Result<Unit> = runCatching {
                        updateSftp(server.host, server.host, name, server.port, server.username, server.password)
                    }

                    override suspend fun delete(): Result<Unit> =
                        runCatching { deleteSftp(server.host) }
                },
            )
        }

        val remoteProviders = RemoteProviders.providers(context.cacheDir, LocationStores)
        remoteLocations().forEach { location ->
            val provider = remoteProviders.firstOrNull { it.scheme == location.kind.scheme }
                ?: return@forEach
            entries += LocationEntry(
                id = location.id,
                title = location.name,
                subtitle = if (location.kind == RemoteKind.SSH) "ssh://${location.pathAuthority}"
                else "${location.kind.scheme}://${location.endpoint}",
                root = location.entryUri,
                provider = provider,
                kind = location.kind.scheme,
                actions = object : LocationActions {
                    override suspend fun rename(name: String): Result<Unit> =
                        runCatching { updateRemote(location.copy(name = name)) }

                    override suspend fun delete(): Result<Unit> =
                        runCatching { deleteRemote(location.id) }
                },
            )
        }

        return entries
    }

    // ── SAF ─────────────────────────────────────────────────────────

    fun safLocations(): List<SafLocation> = LocationStores.safLocations()

    fun insertSaf(treeUri: String): String = LocationStores.saf.insert(treeUri)

    fun renameSaf(hexId: String, name: String) = LocationStores.saf.rename(hexId, name)

    fun deleteSaf(hexId: String) = LocationStores.saf.delete(hexId)

    fun reorderSaf(hexIds: List<String>) = LocationStores.saf.reorder(hexIds)

    // ── Local paths ─────────────────────────────────────────────────

    fun localLocations(): List<LocalLocation> = LocalLocationStore.all()

    fun insertLocal(path: String, name: String = ""): String = LocalLocationStore.add(path, name)

    fun renameLocal(id: String, name: String) = LocalLocationStore.rename(id, name)

    fun deleteLocal(id: String) = LocalLocationStore.delete(id)

    fun reorderLocal(ids: List<String>) = LocalLocationStore.reorder(ids)

    // ── Web links (drawer network group) ────────────────────────────

    fun webLocations(): List<WebLocation> = WebLocationStore.all()

    fun insertWeb(url: String, name: String = ""): String = WebLocationStore.add(url, name)

    fun renameWeb(id: String, name: String) = WebLocationStore.rename(id, name)

    fun deleteWeb(id: String) = WebLocationStore.delete(id)

    fun reorderWeb(ids: List<String>) = WebLocationStore.reorder(ids)

    // ── FTP ─────────────────────────────────────────────────────────

    fun ftpServers(): List<FtpServer> = LocationStores.ftpServers()

    fun insertFtp(host: String, name: String, port: Int, username: String, password: String) =
        LocationStores.ftp.insert(host, name, port, username, password)

    fun updateFtp(
        host: String, newHost: String, name: String, port: Int, username: String, password: String,
    ) = LocationStores.ftp.update(host, newHost, name, port, username, password)

    fun deleteFtp(host: String) = LocationStores.ftp.delete(host)

    fun reorderFtp(hosts: List<String>) = LocationStores.ftp.reorder(hosts)

    // ── SFTP ────────────────────────────────────────────────────────

    fun sftpServers(): List<SftpServer> = LocationStores.sftpServers()

    fun insertSftp(host: String, name: String, port: Int, username: String, password: String) =
        LocationStores.sftp.insert(host, name, port, username, password)

    fun updateSftp(
        host: String, newHost: String, name: String, port: Int, username: String, password: String,
    ) = LocationStores.sftp.update(host, newHost, name, port, username, password)

    fun deleteSftp(host: String) = LocationStores.sftp.delete(host)

    fun reorderSftp(hosts: List<String>) = LocationStores.sftp.reorder(hosts)

    // ── Encrypted remote connections (AList / SSH) ──────────────────

    fun remoteLocations(): List<RemoteLocation> = RemoteLocationStore.all()

    fun insertRemote(location: RemoteLocation) = RemoteLocationStore.put(location)

    fun updateRemote(location: RemoteLocation) {
        if (location.kind == RemoteKind.SSH) RemoteProviders.forget(location.id)
        RemoteLocationStore.put(location)
    }

    fun deleteRemote(id: String) {
        RemoteProviders.forget(id)
        RemoteLocationStore.remove(id)
    }

    fun reorderRemote(ids: List<String>) = RemoteLocationStore.reorder(ids)

    suspend fun verifyRemote(location: RemoteLocation): Result<Unit> = RemoteProviders.verify(location)
}
