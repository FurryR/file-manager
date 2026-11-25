package io.furryr.file.store

/**
 * App-facing identity management.
 *
 * The encrypted store owns identity records; FTP and SFTP rows live in their
 * own databases, so only this facade can reject deleting an identity that any
 * remote connection still references.
 */
object IdentityRepository {
    fun identities(): List<RemoteLocationStore.Identity> = RemoteLocationStore.identities()

    fun passwordIdentities(): List<RemoteLocationStore.Identity> =
        RemoteLocationStore.identities().filter { !it.usesPrivateKey }

    fun save(identity: RemoteLocationStore.Identity) {
        if (identity.usesPrivateKey) {
            require(LocationStores.ftp.getAll().none { it.identityId == identity.id }) {
                "An FTP connection uses this identity as a password"
            }
            require(LocationStores.sftp.getAll().none { it.identityId == identity.id }) {
                "An SFTP connection uses this identity as a password"
            }
        }
        RemoteLocationStore.putIdentity(identity)
    }

    fun delete(id: String) {
        require(LocationStores.ftp.getAll().none { it.identityId == id }) {
            "This identity is used by an FTP connection; edit the connection first"
        }
        require(LocationStores.sftp.getAll().none { it.identityId == id }) {
            "This identity is used by an SFTP connection; edit the connection first"
        }
        RemoteLocationStore.removeIdentity(id)
    }

    fun ensure(
        name: String,
        password: String = "",
        privateKey: String = "",
        passphrase: String = "",
    ): RemoteLocationStore.Identity =
        RemoteLocationStore.ensureIdentity(name, password, privateKey, passphrase)
}
