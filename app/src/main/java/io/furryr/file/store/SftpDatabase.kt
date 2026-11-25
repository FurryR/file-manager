package io.furryr.file.store

import android.content.Context
import io.furryr.file.provider.SftpServerSource
import io.furryr.file.provider.model.SftpServer

class SftpDatabase(context: Context) : ServerDatabase<SftpServer>(
    context = context,
    name = "sftp_servers.db",
    defaultPort = 22,
    mapper = { host, name, port, username, password, identityId, sortOrder ->
        SftpServer(
            host = host,
            name = name,
            port = port,
            username = username,
            password = password,
            sortOrder = sortOrder,
            identityId = identityId,
        )
    },
    nameOf = { it.name },
), SftpServerSource {

    override fun sftpServers(): List<SftpServer> = getAll()

    override fun sftpServer(host: String): SftpServer? = findByHost(host)

    /** Merge backup rows, keeping the host as identity and overwriting it. */
    fun restore(servers: List<SftpServer>) {
        for (server in servers) {
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO servers (host, name, port, username, password, identity_id, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    server.host, server.name, server.port,
                    server.username, server.password, server.identityId, server.sortOrder,
                ),
            )
        }
    }
}
