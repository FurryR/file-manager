package io.furryr.file.store

import android.content.Context
import android.util.Log
import io.furryr.file.core.FileProvider
import io.furryr.file.core.LocationActions
import io.furryr.file.core.LocationEntry
import io.furryr.file.provider.FtpProvider
import io.furryr.file.provider.ProviderSchemes
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
 * App-facing facade over every persisted location (local paths, SAF trees,
 * FTP/SFTP servers, encrypted AList/SSH connections), so the UI never touches a
 * provider singleton or a store directly.
 *
 * One [LocationKind] table drives drawer entries and their rename/delete
 * actions: the built-in families and plugin-contributed families take the same
 * path. Typed accessors stay for the editors, which need the concrete model.
 *
 * Requires [LocationStores], [LocalLocationStore] and the `:core` providers to
 * have been installed by the app.
 */
object LocationsRepository {
    private const val TAG = "LocationsRepository"

    private val extraKinds = linkedMapOf<String, LocationKind>()

    /** Add a location family (a plugin) to the drawer. */
    @Synchronized
    fun registerKind(kind: LocationKind) {
        extraKinds[kind.id] = kind
    }

    @Synchronized
    fun unregisterKind(id: String) {
        extraKinds.remove(id)
    }

    private fun kinds(localProvider: FileProvider): List<LocationKind> {
        val plugins = synchronized(this) { extraKinds.values.toList() }
        return listOf(
            LocalKind(localProvider),
            SafKind,
            FtpKind,
            SftpKind,
            EncryptedRemoteKind(),
        ) + plugins
    }

    /** Unified gallery entries; actions are implemented by each [LocationKind]. */
    fun locations(context: Context, localProvider: FileProvider): List<LocationEntry> =
        kinds(localProvider).flatMap { kind ->
            runCatching { kind.records(context) }
                .onFailure { Log.w(TAG, "Location kind '${kind.id}' failed", it) }
                .getOrDefault(emptyList())
                .map { it.entry(kind) }
        }

    private fun LocationRecord.entry(kind: LocationKind): LocationEntry {
        val record = this
        return LocationEntry(
            id = record.id,
            title = record.title,
            subtitle = record.subtitle,
            root = record.root,
            provider = record.provider,
            kind = record.kind,
            actions = object : LocationActions {
                override suspend fun rename(name: String): Result<Unit> = kind.rename(record.id, name)

                override suspend fun delete(): Result<Unit> = kind.delete(record.id)
            },
        )
    }

    // ── Local paths ─────────────────────────────────────────────────

    fun localLocations(): List<LocalLocation> = LocalLocationStore.all()

    fun insertLocal(path: String, name: String = ""): String = LocalLocationStore.add(path, name)

    fun renameLocal(id: String, name: String) = LocalLocationStore.rename(id, name)

    fun deleteLocal(id: String) = LocalLocationStore.delete(id)

    fun reorderLocal(ids: List<String>) = LocalLocationStore.reorder(ids)

    // ── SAF ─────────────────────────────────────────────────────────

    fun safLocations(): List<SafLocation> = LocationStores.safLocations()

    fun insertSaf(treeUri: String): String = LocationStores.saf.insert(treeUri)

    fun renameSaf(hexId: String, name: String) = LocationStores.saf.rename(hexId, name)

    fun deleteSaf(hexId: String) = LocationStores.saf.delete(hexId)

    fun reorderSaf(hexIds: List<String>) = LocationStores.saf.reorder(hexIds)

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

    // ── Built-in kinds ──────────────────────────────────────────────

    private class LocalKind(private val provider: FileProvider) : LocationKind {
        override val id: String = "local"

        override fun records(context: Context): List<LocationRecord> =
            LocalLocationStore.all().map { location ->
                LocationRecord(
                    id = location.id,
                    title = location.name.ifBlank { location.path },
                    subtitle = location.path,
                    root = location.path,
                    kind = id,
                    provider = provider,
                )
            }

        override fun rename(id: String, name: String): Result<Unit> =
            runCatching { LocalLocationStore.rename(id, name) }

        override fun delete(id: String): Result<Unit> = runCatching { LocalLocationStore.delete(id) }

        override fun reorder(ids: List<String>): Result<Unit> =
            runCatching { LocalLocationStore.reorder(ids) }
    }

    private object SafKind : LocationKind {
        override val id: String = "saf"

        override fun records(context: Context): List<LocationRecord> =
            LocationStores.safLocations().map { location ->
                LocationRecord(
                    id = location.hexId,
                    title = location.name,
                    subtitle = location.treeUri,
                    root = "saf://${location.hexId}",
                    kind = id,
                    provider = SAFProvider,
                )
            }

        override fun rename(id: String, name: String): Result<Unit> =
            runCatching { LocationStores.saf.rename(id, name) }

        override fun delete(id: String): Result<Unit> = runCatching { LocationStores.saf.delete(id) }

        override fun reorder(ids: List<String>): Result<Unit> =
            runCatching { LocationStores.saf.reorder(ids) }
    }

    private object FtpKind : LocationKind {
        override val id: String = ProviderSchemes.FTP

        override fun records(context: Context): List<LocationRecord> =
            LocationStores.ftpServers().map { server ->
                LocationRecord(
                    id = server.host,
                    title = server.name,
                    subtitle = "ftp://${server.host}:${server.port}",
                    root = "ftp://${server.host}",
                    kind = id,
                    provider = FtpProvider,
                )
            }

        override fun rename(id: String, name: String): Result<Unit> =
            runCatching { LocationStores.ftp.rename(id, name) }

        override fun delete(id: String): Result<Unit> = runCatching { LocationStores.ftp.delete(id) }

        override fun reorder(ids: List<String>): Result<Unit> =
            runCatching { LocationStores.ftp.reorder(ids) }
    }

    private object SftpKind : LocationKind {
        override val id: String = ProviderSchemes.SFTP

        override fun records(context: Context): List<LocationRecord> =
            LocationStores.sftpServers().map { server ->
                LocationRecord(
                    id = server.host,
                    title = server.name,
                    subtitle = "sftp://${server.host}:${server.port}",
                    root = "sftp://${server.host}",
                    kind = id,
                    provider = SftpProvider,
                )
            }

        override fun rename(id: String, name: String): Result<Unit> =
            runCatching { LocationStores.sftp.rename(id, name) }

        override fun delete(id: String): Result<Unit> = runCatching { LocationStores.sftp.delete(id) }

        override fun reorder(ids: List<String>): Result<Unit> =
            runCatching { LocationStores.sftp.reorder(ids) }
    }

    private class EncryptedRemoteKind : LocationKind {
        override val id: String = "remote"

        override fun records(context: Context): List<LocationRecord> {
            val providers = RemoteProviders.providers(context.cacheDir, LocationStores)
            return RemoteLocationStore.all().mapNotNull { location ->
                val provider = providers.firstOrNull { it.scheme == location.kind.scheme }
                    ?: return@mapNotNull null
                LocationRecord(
                    id = location.id,
                    title = location.name,
                    subtitle = if (location.kind == io.furryr.file.provider.remote.RemoteKind.SSH) {
                        "ssh://${location.pathAuthority}"
                    } else {
                        "${location.kind.scheme}://${location.endpoint}"
                    },
                    root = location.entryUri,
                    kind = location.kind.scheme,
                    provider = provider,
                )
            }
        }

        override fun rename(id: String, name: String): Result<Unit> = runCatching {
            val location = RemoteLocationStore.find(id)
            updateRemote(location.copy(name = name))
        }

        override fun delete(id: String): Result<Unit> = runCatching { deleteRemote(id) }

        override fun reorder(ids: List<String>): Result<Unit> =
            runCatching { RemoteLocationStore.reorder(ids) }
    }
}
