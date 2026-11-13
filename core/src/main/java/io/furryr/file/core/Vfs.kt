package io.furryr.file.core

import io.furryr.file.core.model.ProviderStat
import java.util.Locale

/**
 * Virtual filesystem router.
 *
 * Maps a canonical path or URI to a [FileProvider] by scheme, then dispatches
 * every filesystem operation through the provider contract. Bare paths use the
 * registered default provider. This layer is platform- and UI-independent:
 * callers (UI, hub, CLI bridges) only deal in paths and opaque providers.
 *
 * [node] is the single address→node boundary; everything above it takes nodes
 * (see [NodeOps] for copy/move).
 */
object Vfs {
    private val providers = mutableMapOf<String, FileProvider>()
    private var default: FileProvider? = null

    @Synchronized
    fun register(scheme: String, provider: FileProvider) {
        val key = scheme.lowercase(Locale.ROOT)
        check(providers.putIfAbsent(key, provider) == null) {
            "A provider is already registered for scheme '$scheme'"
        }
    }

    @Synchronized
    fun register(provider: FileProvider) {
        for (scheme in provider.schemes) register(scheme, provider)
    }

    /** The provider that owns [scheme], or null when nothing is registered. */
    @Synchronized
    fun provider(scheme: String): FileProvider? =
        providers[scheme.lowercase(Locale.ROOT)]

    @Synchronized
    fun unregister(scheme: String) {
        val removed = providers.remove(scheme.lowercase(Locale.ROOT))
        if (removed != null && default === removed) default = null
    }

    @Synchronized
    fun setDefault(provider: FileProvider) {
        default = provider
    }

    /** Route a parsed [Path] to its provider. View levels never reach a provider. */
    fun resolve(path: Path): Result<ResolvedProvider> {
        if (path.isViewed) {
            return Result.failure(
                IllegalArgumentException("a view address has no provider: $path"),
            )
        }
        val scheme = path.scheme
        val provider = if (scheme == null) {
            synchronized(this) { default }
                ?: return Result.failure(IllegalStateException("No default provider"))
        } else {
            synchronized(this) { providers[scheme.lowercase(Locale.ROOT)] }
                ?: return Result.failure(IllegalStateException("No provider for scheme '$scheme'"))
        }
        return Result.success(ResolvedProvider(provider, path, path.toString()))
    }

    /** Address-text boundary: parse once, then stay on [Path]. */
    fun resolve(uri: String): Result<ResolvedProvider> = parsed(uri) { resolve(it) }

    suspend fun folder(path: Path): Result<VFolder> = node(path).mapCatching { node ->
        node as? VFolder ?: error("not a folder: $path")
    }

    suspend fun folder(path: String): Result<VFolder> = parsed(path) { folder(it) }

    suspend fun file(path: Path): Result<VFile> = node(path).mapCatching { node ->
        node as? VFile ?: error("not a file: $path")
    }

    suspend fun file(path: String): Result<VFile> = parsed(path) { file(it) }

    /**
     * The node at [path]. The provider mints the provider part: a storage
     * provider answers with a [Folder]/[File], a live one with its own [VNode]
     * implementation, and the caller never has to know which. Each `!` view
     * level after it is entered through [ViewOpeners], so a file that is also a
     * container is just a longer chain. This is the only place an address
     * becomes a node.
     */
    suspend fun node(path: Path): Result<VNode> = runCatchingCancellable {
        var node = root(path.providerPath()).getOrThrow()
        for (view in path.views) {
            val file = node as? VFile ?: error("view requires a file: ${node.path}")
            val content = file.describe().getOrThrow()
            val opener = ViewOpeners.resolve(file, content)
                ?: error("no opener supports ${file.name}")
            node = ViewOpeners.enter(file, opener).getOrThrow()
        }
        node
    }

    suspend fun node(path: String): Result<VNode> = parsed(path) { node(it) }

    /**
     * The live node for [address], carrying [referer] as its request context.
     * Callers ask the route table, not a provider, so a link or share never
     * rebuilds an address.
     */
    fun liveFile(address: String, referer: String? = null): Result<VFile> = parsed(address) { path ->
        val scheme = path.scheme
            ?: return@parsed Result.failure(IllegalArgumentException("Not a live address: $address"))
        val resolved = resolve(path).getOrElse { return@parsed Result.failure(it) }
        runCatching {
            resolved.provider.liveFile(address, referer)
                ?: error("Scheme '$scheme' has no live resources")
        }
    }

    // ── Simple operations ──────────────────────────────────────────

    suspend fun stat(path: Path): Result<ProviderStat> = node(path).mapCatching { node ->
        when (node) {
            is VFile -> node.stat().getOrThrow()
            is VFolder -> node.stat().getOrThrow()
            else -> error("unsupported node: ${node.path}")
        }
    }

    suspend fun stat(path: String): Result<ProviderStat> = parsed(path) { stat(it) }

    // ── Internal ────────────────────────────────────────────────────

    /** The provider-visible address: one lookup, whatever the scheme is. */
    private suspend fun root(path: Path): Result<VNode> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        return resolved.provider.node(resolved.path, resolved.innerPath)
    }

    /** Parse an address into a [Path] without throwing out of a `Result` API. */
    private inline fun <T> parsed(uri: String, block: (Path) -> Result<T>): Result<T> {
        val path = try {
            Path.parse(uri)
        } catch (error: Exception) {
            return Result.failure(IllegalArgumentException("Cannot parse path: $uri", error))
        }
        return block(path)
    }

}
