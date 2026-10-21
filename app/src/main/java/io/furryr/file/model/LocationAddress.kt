package io.furryr.file.model

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
) {
    val isRemote: Boolean get() = scheme in RemoteSchemes

    fun toUrl(): String {
        if (!isRemote) return path
        val userInfo = when {
            username.isBlank() -> ""
            password.isBlank() -> "$username@"
            else -> "$username:$password@"
        }
        val portPart = port?.takeIf { it > 0 }?.let { ":$it" } ?: ""
        val pathPart = path.trimStart('/').let { if (it.isBlank()) "/" else "/$it" }
        return "$scheme://$userInfo$host$portPart$pathPart"
    }
}

private val RemoteSchemes = setOf("ftp", "sftp", "ssh", "alist")

object LocationAddressParser {
    fun parse(raw: String): LocationAddress? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (RemoteSchemes.none { value.startsWith("$it://") }) {
            return LocationAddress(path = value)
        }
        val schemeEnd = value.indexOf("://")
        val scheme = value.substring(0, schemeEnd).lowercase()
        val remainder = value.substring(schemeEnd + 3)
        val slash = remainder.indexOf('/')
        val authority = if (slash >= 0) remainder.substring(0, slash) else remainder
        val path = if (slash >= 0) remainder.substring(slash).ifBlank { "/" } else "/"
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val userSeparator = userInfo.indexOf(':')
        val host: String
        val port: Int?
        if (hostPort.startsWith("[")) {
            val closing = hostPort.indexOf(']')
            host = if (closing >= 0) hostPort.substring(1, closing) else hostPort
            port = hostPort.substringAfter(']', "").removePrefix(":").toIntOrNull()
        } else {
            val portSeparator = hostPort.lastIndexOf(':')
            val possiblePort = if (portSeparator >= 0) hostPort.substring(portSeparator + 1) else ""
            if (portSeparator >= 0 && possiblePort.all(Char::isDigit) && possiblePort.isNotEmpty()) {
                host = hostPort.substring(0, portSeparator)
                port = possiblePort.toIntOrNull()
            } else {
                host = hostPort
                port = null
            }
        }
        return LocationAddress(
            scheme = scheme,
            name = host,
            host = host,
            port = port,
            username = if (userSeparator >= 0) userInfo.substring(0, userSeparator) else userInfo,
            password = if (userSeparator >= 0) userInfo.substring(userSeparator + 1) else "",
            path = path,
        )
    }
}
