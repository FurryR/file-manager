package io.furryr.file.ui.components

import io.furryr.file.R
import io.furryr.file.model.LocationAddress
import io.furryr.file.provider.ProviderSchemes
import io.furryr.file.provider.remote.RemoteKind
import io.furryr.file.provider.remote.RemoteLocation

/**
 * One remote/web location protocol: which fields the add dialog shows and how
 * a typed address becomes a stored location. Plugins register their own
 * descriptor instead of the UI hardcoding a scheme switch.
 */
data class RemoteProtocol(
    val schemes: Set<String>,
    val defaultPort: Int?,
    val endpointLabel: Int,
    val requiresUsername: Boolean = false,
    val showsToken: Boolean = false,
    val showsPrivateKey: Boolean = false,
    val add: (LocationAddress, RemoteProtocolTarget) -> Unit,
) {
    fun matches(scheme: String?): Boolean = scheme != null && scheme.lowercase() in schemes
}

/** Drawer callbacks a protocol needs; the drawer implements it once. */
interface RemoteProtocolTarget {
    fun ftp(host: String, name: String, port: Int, username: String, password: String)
    fun sftp(host: String, name: String, port: Int, username: String, password: String)
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
                    address.port ?: 21, address.username, address.password,
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
                    address.port ?: 22, address.username, address.password,
                )
            },
        )
        register(
            RemoteProtocol(
                schemes = setOf(RemoteKind.ALIST.scheme),
                defaultPort = null,
                endpointLabel = R.string.remote_endpoint,
                showsToken = true,
            ) { address, target ->
                target.remote(remoteLocationFrom(address, RemoteKind.ALIST))
            },
        )
        register(
            RemoteProtocol(
                schemes = setOf(RemoteKind.SSH.scheme),
                defaultPort = 22,
                endpointLabel = R.string.ftp_host_label,
                requiresUsername = true,
                showsPrivateKey = true,
            ) { address, target ->
                target.remote(remoteLocationFrom(address, RemoteKind.SSH))
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
