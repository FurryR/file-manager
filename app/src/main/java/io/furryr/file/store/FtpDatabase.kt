package io.furryr.file.store

import android.content.Context
import io.furryr.file.provider.FtpServerSource
import io.furryr.file.provider.model.FtpServer

class FtpDatabase(context: Context) : ServerDatabase<FtpServer>(
    context = context,
    name = "ftp_servers.db",
    defaultPort = 21,
    mapper = { host, name, port, username, password, sortOrder ->
        FtpServer(host, name, port, username, password, sortOrder)
    },
    nameOf = { it.name },
), FtpServerSource {

    override fun ftpServers(): List<FtpServer> = getAll()

    override fun ftpServer(host: String): FtpServer? = findByHost(host)

    /** Merge backup rows, keeping the host as identity and overwriting it. */
    fun restore(servers: List<FtpServer>) {
        for (server in servers) {
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO servers (host, name, port, username, password, sort_order) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(
                    server.host, server.name, server.port,
                    server.username, server.password, server.sortOrder,
                ),
            )
        }
    }
}
