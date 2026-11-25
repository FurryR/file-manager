package io.furryr.file.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import io.furryr.file.R
import io.furryr.file.model.LocationAddress
import io.furryr.file.provider.ProviderSchemes
import io.furryr.file.provider.remote.AlistLocation
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.provider.remote.RemoteSchemes
import io.furryr.file.provider.remote.SshLocation
import io.furryr.file.ui.icons.Icons

/**
 * One remote/web location protocol: which fields the add dialog shows and how
 * a typed address becomes a stored location. Plugins register their own
 * descriptor instead of the UI hardcoding a scheme switch.
 *
 * Each protocol also owns its fallback drawer icon; a connection's own site
 * icon comes from its concrete location type.
 */
data class RemoteProtocol(
    val schemes: Set<String>,
    val defaultPort: Int?,
    val endpointLabel: Int,
    val requiresUsername: Boolean = false,
    val showsToken: Boolean = false,
    val showsPrivateKey: Boolean = false,
    val usesEndpoint: Boolean = false,
    val requiresHostKey: Boolean = false,
    val helpText: Int = R.string.remote_ssh_help,
    val icon: ImageVector = Icons.Default.Cloud,
    val add: (LocationAddress, RemoteProtocolTarget) -> Unit,
) {
    fun matches(scheme: String?): Boolean = scheme != null && scheme.lowercase() in schemes
}

/** Drawer callbacks a protocol needs; the drawer implements it once. */
interface RemoteProtocolTarget {
    fun ftp(host: String, name: String, port: Int, username: String, identityId: String)
    fun sftp(host: String, name: String, port: Int, username: String, identityId: String)
    fun remote(location: RemoteLocation)
    fun web(url: String, name: String)
    fun local(path: String)
}

/** Process-wide protocol table; built-ins plus plugin registrations. */
object RemoteProtocols {
    private val entries = linkedMapOf<String, RemoteProtocol>()

    @Synchronized
    fun register(protocol: RemoteProtocol) {
        for (scheme in protocol.schemes) entries[key(scheme)] = protocol
    }

    @Synchronized
    fun of(scheme: String?): RemoteProtocol? = scheme?.let(entries::get)

    @Synchronized
    private fun key(scheme: String): String = scheme.lowercase(java.util.Locale.ROOT)

    /** True when a descriptor handled [address]; false is a UI error, never local. */
    fun add(address: LocationAddress, target: RemoteProtocolTarget): Boolean {
        if (address.scheme == null) {
            target.local(address.path)
            return true
        }
        val protocol = of(address.scheme) ?: return false
        protocol.add(address, target)
        return true
    }

    init {
        register(
            RemoteProtocol(
                schemes = setOf(ProviderSchemes.FTP),
                defaultPort = 21,
                endpointLabel = R.string.ftp_host_label,
            ) { address, target ->
                target.ftp(
                    address.host, address.name.ifBlank { address.host },
                    address.port ?: 21, address.username, address.identityId,
                )
            },
        )
        register(
            RemoteProtocol(
                schemes = setOf(ProviderSchemes.SFTP),
                defaultPort = 22,
                endpointLabel = R.string.ftp_host_label,
                requiresUsername = true,
            ) { address, target ->
                target.sftp(
                    address.host, address.name.ifBlank { address.host },
                    address.port ?: 22, address.username, address.identityId,
                )
            },
        )
        register(
            RemoteProtocol(
                schemes = setOf(RemoteSchemes.ALIST),
                defaultPort = null,
                endpointLabel = R.string.remote_endpoint,
                showsToken = true,
                usesEndpoint = true,
                helpText = R.string.remote_alist_help,
                icon = Icons.Default.Language,
            ) { address, target ->
                val host = address.host.trim().trimEnd('/')
                val endpoint = if (host.startsWith("http://") || host.startsWith("https://")) {
                    host
                } else {
                    val port = address.port?.takeIf { it > 0 && it != 443 }
                    "https://$host" + if (port == null) "" else ":$port"
                }
                target.remote(
                    AlistLocation(
                        name = address.name.ifBlank { host },
                        basePath = address.path,
                        endpoint = endpoint,
                        username = address.username,
                        token = address.token,
                        directoryPassword = address.directoryPassword,
                        identityId = address.identityId,
                    ),
                )
            },
        )
        register(
            RemoteProtocol(
                schemes = setOf(RemoteSchemes.SSH),
                defaultPort = 22,
                endpointLabel = R.string.ftp_host_label,
                requiresUsername = true,
                showsPrivateKey = true,
                requiresHostKey = true,
            ) { address, target ->
                val host = address.host.trim().trimEnd('/')
                target.remote(
                    SshLocation(
                        name = address.name.ifBlank { host },
                        basePath = address.path,
                        host = host,
                        port = address.port ?: 22,
                        username = address.username,
                        identityId = address.identityId,
                    ),
                )
            },
        )
        register(
            RemoteProtocol(
                schemes = ProviderSchemes.web,
                defaultPort = null,
                endpointLabel = R.string.ftp_host_label,
            ) { address, target ->
                target.web(address.toUrl(), address.name)
            },
        )
    }
}
