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
        initialized = true
    }

    override fun safLocations(): List<SafLocation> = saf.getAll()

    override fun safLocation(hexId: String): SafLocation? = saf.findByHexId(hexId)

    override fun ftpServers(): List<FtpServer> = ftp.getAll()

    override fun ftpServer(host: String): FtpServer? = ftp.findByHost(host)

    override fun sftpServers(): List<SftpServer> = sftp.getAll()

    override fun sftpServer(host: String): SftpServer? = sftp.findByHost(host)

    override fun remoteLocations(): List<RemoteLocation> = RemoteLocationStore.all()

    override fun documentTree(id: String): String? = DocumentTreeStore.documentTree(id)
}
