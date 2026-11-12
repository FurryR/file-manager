package io.furryr.file.store

import android.content.Context
import io.furryr.file.provider.SftpServerSource
import io.furryr.file.provider.model.SftpServer

class SftpDatabase(context: Context) : ServerDatabase<SftpServer>(
    context = context,
    name = "sftp_servers.db",
    defaultPort = 22,
    mapper = { host, name, port, username, password, sortOrder ->
        SftpServer(host, name, port, username, password, sortOrder)
    },
    nameOf = { it.name },
), SftpServerSource {

    override fun sftpServers(): List<SftpServer> = getAll()

    override fun sftpServer(host: String): SftpServer? = findByHost(host)
}
