package io.furryr.file.container

/**
 * Text form of a container address.
 *
 * Parsing lives in [ContainerUriResolver]; this object is the one place that
 * *builds* the `container://` spelling, so the app, the agent and the terminal
 * bridge never assemble it by hand.
 */
object ContainerUris {
    const val SCHEME = "container"
    const val PREFIX = "container://"

    /** The provider root that lists the app-local set of containers. */
    fun root(): String = "container:///"

    /** `container://<name>` for the root, `container://<name>/<path>` below it. */
    fun forContainer(name: String, path: String = "/"): String {
        val clean = path.trimStart('/')
        return if (clean.isEmpty()) "$PREFIX$name" else "$PREFIX$name/$clean"
    }

    /** Terminal command marker that selects this container's daemon. */
    fun command(name: String): String = "$PREFIX$name"

    fun isContainer(uri: String): Boolean = uri.startsWith(PREFIX)

    fun containerName(uri: String): String? = resolve(uri)?.containerName

    fun resolve(uri: String): ContainerUriResult? =
        ContainerUriResolver.resolveContainerUri(uri).getOrNull()

    /** The container named by a terminal marker, or null when [cmd] is not one. */
    fun commandName(cmd: String): String? {
        val parsed = resolve(cmd) ?: return null
        return parsed.containerName?.takeIf { parsed.containerPath == "/" }
    }
}
