package io.furryr.file.model

import io.furryr.file.core.Path
import io.furryr.file.provider.ProviderSchemes

data class LocationAddress(
    val scheme: String? = null,
    val name: String = "",
    val host: String = "",
    val port: Int? = null,
    val username: String = "",
    val password: String = "",
    val path: String = "/",
    val token: String = "",
    val directoryPassword: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    /** Full URL for a web location; [path] stays the filesystem form. */
    val url: String = "",
) {
    val isRemote: Boolean get() = scheme in RemoteSchemes
    val isWeb: Boolean get() = scheme in WebSchemes

    fun toUrl(): String {
        if (isWeb) return url.ifBlank { path }
        if (!isRemote) return path
        val userInfo = when {
            username.isBlank() -> null
            password.isBlank() -> username
            else -> "$username:$password"
        }
        return Path.of(
            scheme = scheme,
            userInfo = userInfo,
            host = host,
            port = port?.takeIf { it > 0 },
            segments = Path.parse(path).segmentList,
        ).toString()
    }
}

private val RemoteSchemes: Set<String> get() = ProviderSchemes.remote
private val WebSchemes: Set<String> get() = ProviderSchemes.web

object LocationAddressParser {
    fun parse(raw: String): LocationAddress? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val webScheme = WebSchemes.firstOrNull { value.startsWith("$it://", ignoreCase = true) }
        if (webScheme != null) {
            val uri = runCatching { android.net.Uri.parse(value) }.getOrNull()
                ?: return LocationAddress(scheme = webScheme, path = value, url = value)
            val host = uri.host.orEmpty()
            return LocationAddress(
                scheme = webScheme,
                name = host,
                host = host,
                path = value,
                url = value,
            )
        }
        if (RemoteSchemes.none { value.startsWith("$it://", ignoreCase = true) }) {
            return LocationAddress(path = value)
        }
        val parsed = Path.parse(value)
        val scheme = parsed.scheme ?: return LocationAddress(path = value)
        val userInfo = parsed.userInfo.orEmpty()
        val userSeparator = userInfo.indexOf(':')
        return LocationAddress(
            scheme = scheme,
            name = parsed.host.orEmpty(),
            host = parsed.host.orEmpty(),
            port = parsed.port,
            username = if (userSeparator >= 0) userInfo.substring(0, userSeparator) else userInfo,
            password = if (userSeparator >= 0) userInfo.substring(userSeparator + 1) else "",
            path = if (parsed.segmentList.isEmpty()) "/" else "/" + parsed.segmentList.joinToString("/"),
        )
    }
}
