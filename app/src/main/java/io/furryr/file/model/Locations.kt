package io.furryr.file.model

/** A persisted SAF document tree registered by the user. */
data class SafLocation(
    val hexId: String,
    val name: String,
    val treeUri: String,
    val sortOrder: Int,
)

/** A persisted FTP server entry. */
data class FtpServer(
    val host: String,
    val name: String,
    val port: Int,
    val username: String,
    val password: String,
    val sortOrder: Int,
)

/** A persisted SFTP server entry. */
data class SftpServer(
    val host: String,
    val name: String,
    val port: Int,
    val username: String,
    val password: String,
    val sortOrder: Int,
)
