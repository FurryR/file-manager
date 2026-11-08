package io.furryr.file.provider

import io.furryr.file.provider.model.FtpServer
import io.furryr.file.provider.model.SafLocation
import io.furryr.file.provider.model.SftpServer
import io.furryr.file.provider.remote.RemoteLocation

/**
 * Read-only location records supplied by the app.
 *
 * Providers never persist or query a store themselves: the app owns every
 * SQLite database and preference file and passes the current records in.
 * Each provider only receives the source it needs.
 */
interface SafLocationSource {
    fun safLocations(): List<SafLocation>
    fun safLocation(hexId: String): SafLocation?
}

interface FtpServerSource {
    fun ftpServers(): List<FtpServer>
    fun ftpServer(host: String): FtpServer?
}

interface SftpServerSource {
    fun sftpServers(): List<SftpServer>
    fun sftpServer(host: String): SftpServer?
}

interface RemoteLocationSource {
    fun remoteLocations(): List<RemoteLocation>
}

/** Persisted SAF tree URIs keyed by the short id used in `document://` paths. */
interface DocumentTreeSource {
    fun documentTree(id: String): String?
}
