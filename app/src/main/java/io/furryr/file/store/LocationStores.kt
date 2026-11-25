package io.furryr.file.store

import android.content.Context
import io.furryr.file.provider.DocumentTreeSource
import io.furryr.file.provider.FtpServerSource
import io.furryr.file.provider.RemoteLocationSource
import io.furryr.file.provider.SafLocationSource
import io.furryr.file.provider.SftpServerSource
import io.furryr.file.provider.model.FtpServer
import io.furryr.file.provider.model.SafLocation
import io.furryr.file.provider.model.SftpServer
import io.furryr.file.provider.remote.RemoteLocation

/**
 * App-owned persistence for every provider location.
 *
 * Libraries receive this object through their narrow source interfaces; only
 * the app opens the SQLite databases and encrypted files behind them.
 *
 * FTP/SFTP rows store only an identity reference. This facade resolves the
 * password for the provider and folds legacy inline passwords into identities
 * once, so a provider never reads the identity store.
 */
object LocationStores :
    SafLocationSource,
    FtpServerSource,
    SftpServerSource,
    RemoteLocationSource,
    DocumentTreeSource {

    private var initialized = false

    lateinit var saf: SafDatabase
        private set
    lateinit var ftp: FtpDatabase
        private set
    lateinit var sftp: SftpDatabase
        private set

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        saf = SafDatabase(context.applicationContext)
        ftp = FtpDatabase(context.applicationContext)
        sftp = SftpDatabase(context.applicationContext)
        RemoteLocationStore.init(context.applicationContext)
        DocumentTreeStore.init(context.applicationContext)
        migrateServerIdentities()
        initialized = true
    }

    /** Fold legacy inline FTP/SFTP passwords into shared password identities. */
    @Synchronized
    fun migrateServerIdentities() {
        ftp.getAll().forEach { server ->
            if (server.identityId.isBlank() && server.password.isNotBlank()) {
                val identity = IdentityRepository.ensure(
                    name = server.name.ifBlank { server.host },
                    password = server.password,
                )
                ftp.setIdentity(server.host, identity.id)
            }
        }
        sftp.getAll().forEach { server ->
            if (server.identityId.isBlank() && server.password.isNotBlank()) {
                val identity = IdentityRepository.ensure(
                    name = server.name.ifBlank { server.host },
                    password = server.password,
                )
                sftp.setIdentity(server.host, identity.id)
            }
        }
    }

    override fun safLocations(): List<SafLocation> = saf.getAll()

    override fun safLocation(hexId: String): SafLocation? = saf.findByHexId(hexId)

    override fun ftpServers(): List<FtpServer> = ftp.getAll().map { it.withResolvedPassword() }

    override fun ftpServer(host: String): FtpServer? = ftp.findByHost(host)?.withResolvedPassword()

    override fun sftpServers(): List<SftpServer> = sftp.getAll().map { it.withResolvedPassword() }

    override fun sftpServer(host: String): SftpServer? = sftp.findByHost(host)?.withResolvedPassword()

    override fun remoteLocations(): List<RemoteLocation> = RemoteLocationStore.remoteLocations()

    override fun documentTree(id: String): String? = DocumentTreeStore.documentTree(id)

    private fun FtpServer.withResolvedPassword(): FtpServer = copy(
        password = if (identityId.isBlank()) password
        else RemoteLocationStore.identityPassword(identityId),
    )

    private fun SftpServer.withResolvedPassword(): SftpServer = copy(
        password = if (identityId.isBlank()) password
        else RemoteLocationStore.identityPassword(identityId),
    )
}
