package io.furryr.file.provider

import android.content.Context
import io.furryr.file.core.FileProvider
import io.furryr.file.core.LocationActions
import io.furryr.file.core.LocationEntry
import io.furryr.file.core.Vfs

import io.furryr.file.provider.FtpProvider
import io.furryr.file.provider.model.FtpServer
import io.furryr.file.provider.SAFProvider
import io.furryr.file.provider.model.SafLocation
import io.furryr.file.provider.SftpProvider
import io.furryr.file.provider.model.SftpServer
import io.furryr.file.provider.remote.RemoteKind
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.provider.remote.RemoteLocationStore
import io.furryr.file.provider.remote.RemoteProviders
import io.furryr.file.provider.remote.SshConnections
import io.furryr.file.provider.model.LocalLocation

/**
 * App-facing facade over the provider location stores (SAF trees, FTP/SFTP
 * servers, encrypted AList/SSH connections), so the UI layer never touches
 * provider singletons or their stores directly.
 *
 * Requires [Vfs] providers to have been installed by the app.
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

        val remoteProviders = RemoteProviders.providers(context)
        remoteLocations().forEach { location ->
            val provider = remoteProviders.firstOrNull { it.scheme == location.kind.scheme }
                ?: return@forEach
            entries += LocationEntry(
                id = location.id,
                title = location.name,
                subtitle = "${location.kind.scheme}://${location.endpoint.ifBlank { location.host }}",
                root = "${location.kind.scheme}://${location.id}${location.basePath}",
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

    fun updateRemote(location: RemoteLocation) {
        if (location.kind == RemoteKind.SSH) SshConnections.disconnect(location.id)
        RemoteLocationStore.put(location)
    }

    fun deleteRemote(id: String) {
        SshConnections.disconnect(id)
        RemoteLocationStore.remove(id)
    }

    fun reorderRemote(ids: List<String>) = RemoteLocationStore.reorder(ids)

    suspend fun verifyRemote(location: RemoteLocation): Result<Unit> = RemoteProviders.verify(location)
}
