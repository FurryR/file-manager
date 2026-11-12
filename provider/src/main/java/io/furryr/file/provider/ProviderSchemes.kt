package io.furryr.file.provider

import io.furryr.file.provider.remote.RemoteKind

/**
 * The schemes the app treats as remote storage or web locations.
 *
 * The built-in schemes are seeded here, but the sets are open: a plugin
 * provider registers its own scheme instead of editing this file. Every
 * consumer reads the live set, so a late registration is visible everywhere.
 */
object ProviderSchemes {
    const val FTP = "ftp"
    const val SFTP = "sftp"
    const val HTTP = "http"
    const val HTTPS = "https"

    private val remoteSchemes = linkedSetOf(FTP, SFTP, RemoteKind.ALIST.scheme, RemoteKind.SSH.scheme)
    private val webSchemes = linkedSetOf(HTTP, HTTPS)

    /** Locations that persist credentials and appear in the network group. */
    val remote: Set<String> get() = synchronized(remoteSchemes) { remoteSchemes.toSet() }

    /** Live pages pinned as links; these schemes reach the same provider. */
    val web: Set<String> get() = synchronized(webSchemes) { webSchemes.toSet() }

    /** Declare [scheme] as a credential-holding remote location. */
    fun registerRemote(scheme: String) {
        synchronized(remoteSchemes) { remoteSchemes += scheme }
    }

    /** Declare [scheme] as a live web location. */
    fun registerWeb(scheme: String) {
        synchronized(webSchemes) { webSchemes += scheme }
    }

    /** Remove [scheme] from both location groups (the provider itself stays registered). */
    fun unregister(scheme: String) {
        synchronized(remoteSchemes) { remoteSchemes -= scheme }
        synchronized(webSchemes) { webSchemes -= scheme }
    }
}
