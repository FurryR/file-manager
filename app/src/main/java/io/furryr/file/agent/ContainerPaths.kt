package io.furryr.file.agent

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import io.furryr.file.core.HubCallerRoot
import java.io.File

/** App-owned filesystem namespace mapping shared by here, run0 and terminal drops. */
object ContainerPaths {
    /** Resolve an authenticated hub container id to the caller's filesystem root. */
    fun callerRoot(context: Context, containerId: String): HubCallerRoot {
        if (containerId == "host" || containerId == "app") return HubCallerRoot(null, "/")
        val name = containerId.removePrefix(CONTAINER_PREFIX)
        require(name != containerId && name.isNotEmpty()) { "invalid container id '$containerId'" }
        val container = ContainerManager.getContainer(context, name)
            ?: error("container '$name' not found")
        return HubCallerRoot(container.name, File(container.rootfsPath).canonicalPath)
    }

    fun revealPath(context: Context, path: String, root: HubCallerRoot): String {
        require(path.startsWith('/')) { "here requires an absolute path" }
        val guest = normalize(path)
        val caller = root.containerName?.let {
            ContainerManager.getContainer(context, it) ?: error("container '$it' not found")
        }
        val host = if (caller != null) {
            val mappings = mappings(context, caller)
            val mapping = effectiveMapping(mappings, guest)
            // A private mount has no host pathname; keep the caller's view.
            if (mapping.host == null) return "container://${caller.name}$guest"
            hostPath(mappings, guest, followFinal = false)
        } else {
            guest
        }
        val stat = runCatching { Os.lstat(host) }.getOrElse {
            throw IllegalArgumentException("the selected entry does not exist")
        }
        val containers = ContainerManager.listContainers(context).getOrThrow()
        if (caller != null) {
            // A bind mount can expose another container's private mount.
            val other = containers.filter { it.name != caller.name }
                .sortedByDescending { File(it.rootfsPath).canonicalPath.length }
                .firstNotNullOfOrNull { container ->
                    guestPath(context, container, host, stat, privateOnly = true)
                        ?.let { "container://${container.name}$it" }
                }
            if (other != null) return other
            return "container://${caller.name}$guest"
        }
        for (container in containers.sortedByDescending { File(it.rootfsPath).canonicalPath.length }) {
            val containerPath = guestPath(context, container, host, stat, privateOnly = true)
                ?: continue
            return "container://${container.name}$containerPath"
        }
        return host
    }

    fun workingDirectory(context: Context, root: HubCallerRoot, target: String, path: String): String {
        if (path == "~" || path.startsWith("~/")) return path
        require(path.startsWith('/')) { "cwd is not an absolute path" }
        val guest = normalize(path)
        val caller = root.containerName?.let {
            ContainerManager.getContainer(context, it) ?: error("container '$it' not found")
        }
        val host = if (caller != null) {
            hostPath(mappings(context, caller), guest, followFinal = true)
        } else {
            guest
        }
        if (root.containerName != null && target == "$CONTAINER_PREFIX${root.containerName}") return guest
        if (target == "host" || target == "app") return host
        val container = containerForTarget(context, target)
        return guestPath(context, container, host)
            ?: error("cwd is not reachable in container '${container.name}'")
    }

    /** Convert only reachable filesystem paths, never remote/SAF URIs. */
    fun terminalPath(context: Context, path: String, target: String, isDirectory: Boolean): String {
        val targetContainer = containerNameForTarget(target)?.let {
            ContainerManager.getContainer(context, it)
                ?: error("container '$it' not found")
        }
        var sourceRoot: String? = null
        val host = if (path.startsWith("container://")) {
            val source = ContainerUriResolver.resolveContainerUri(path).getOrThrow()
            val name = requireNotNull(source.containerName) { "select a container entry" }
            if (name == targetContainer?.name) return source.containerPath
            val container = ContainerManager.getContainer(context, name) ?: error("container '$name' not found")
            // A bind mount that the target cannot see still exists in the source
            // rootfs, which every container reaches through its /data bind.
            sourceRoot = append(File(container.rootfsPath).canonicalPath, source.containerPath)
            hostPath(mappings(context, container), source.containerPath, followFinal = isDirectory)
        } else {
            require(path.startsWith('/')) { "only local and container paths can be inserted into a terminal" }
            // Retain file symlinks, but resolve directory aliases such as /sdcard.
            val file = File(path)
            if (isDirectory) file.canonicalPath
            else if (file.parentFile == null) "/" else File(file.parentFile!!.canonicalFile, file.name).path
        }
        if (targetContainer == null) return host
        return guestPath(context, targetContainer, host)
            ?: sourceRoot?.let { guestPath(context, targetContainer, it) }
            ?: error("path is not reachable in container '${targetContainer.name}'")
    }

    private fun containerNameForTarget(target: String): String? = when {
        target == "host" || target == "app" -> null
        target.startsWith(CONTAINER_PREFIX) -> target.removePrefix(CONTAINER_PREFIX)
        else -> target
    }

    private fun containerForTarget(context: Context, target: String): Container {
        val name = requireNotNull(containerNameForTarget(target)) { "invalid container id '$target'" }
        return ContainerManager.getContainer(context, name)
            ?: error("container '$name' not found")
    }

    private fun hostPath(mappings: List<Mapping>, guest: String, followFinal: Boolean): String {
        val remaining = ArrayDeque(guest.split('/').filter { it.isNotEmpty() })
        val resolved = mutableListOf<String>()
        var links = 0
        while (remaining.isNotEmpty()) {
            val segment = remaining.removeFirst()
            if (segment == ".") continue
            if (segment == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                continue
            }
            val path = "/" + (resolved + segment).joinToString("/")
            val mapping = effectiveMapping(mappings, path)
            val host = append(requireNotNull(mapping.host) { "path is private to its container" },
                path.removePrefix(mapping.guest.trimEnd('/')))
            // Absolute guest symlinks must restart at the guest root, not escape
            // into Android. Retain the final symlink unless inserting a directory.
            if (remaining.isEmpty() && !followFinal) return host
            if (OsConstants.S_ISLNK(Os.lstat(host).st_mode)) {
                check(++links <= 40) { "too many symbolic links in container path" }
                val link = Os.readlink(host)
                if (link.startsWith('/')) resolved.clear()
                link.split('/').filter { it.isNotEmpty() }.asReversed().forEach { remaining.addFirst(it) }
            } else resolved.add(segment)
        }
        val path = "/" + resolved.joinToString("/")
        val mapping = effectiveMapping(mappings, path)
        return append(requireNotNull(mapping.host) { "path is private to its container" },
            path.removePrefix(mapping.guest.trimEnd('/')))
    }

    private fun guestPath(
        context: Context,
        container: Container,
        host: String,
        stat: StructStat? = null,
        privateOnly: Boolean = false,
    ): String? {
        val mappings = mappings(context, container)
        return mappings.withIndex()
            .filter {
                val mapping = it.value
                mapping.host != null && contains(mapping.host, host) && (!privateOnly || mapping.privateToContainer)
            }
            .sortedWith(compareByDescending<IndexedValue<Mapping>> { it.value.host!!.length }.thenByDescending { it.index })
            .firstNotNullOfOrNull { (_, mapping) ->
                val guest = append(mapping.guest, host.removePrefix(mapping.host!!.trimEnd('/')))
                val effective = effectiveMapping(mappings, guest)
                when {
                    effective == mapping -> guest
                    // A private chroot mount has a rootfs pathname but a different
                    // inode from the host entry hidden underneath it.
                    effective.host == null && stat != null && !hostVisible(host, stat) -> guest
                    else -> null
                }
            }
    }

    private fun mappings(context: Context, container: Container): List<Mapping> = buildList {
        add(Mapping(File(container.rootfsPath).canonicalPath, "/", true))
        if (container.backend == ContainerBackend.CHROOT) {
            listOf("/proc", "/tmp", "/run").forEach { add(Mapping(null, it, true)) }
        }
        val privateMounts = ProotRunner.privateRuntimeMounts(context, container)
        ProotRunner.pathMounts(context, container).filter { File(it.srcAndroidPath).exists() }.forEach {
            val guest = normalize(it.dstContainerPath)
            if (container.backend == ContainerBackend.CHROOT) removeAll { existing -> contains(guest, existing.guest) }
            add(Mapping(File(it.srcAndroidPath).canonicalPath, guest, it in privateMounts))
        }
    }

    private fun effectiveMapping(mappings: List<Mapping>, guest: String): Mapping =
        mappings.withIndex().filter { contains(it.value.guest, guest) }
            .maxWith(compareBy<IndexedValue<Mapping>> { it.value.guest.length }.thenBy { it.index }).value

    private fun normalize(path: String): String = File("/" + path.trimStart('/')).normalize().path
    private fun append(root: String, suffix: String): String =
        (root.trimEnd('/') + "/" + suffix.trimStart('/')).trimEnd('/').ifEmpty { "/" }
    private fun contains(root: String, path: String): Boolean = path == root || path.startsWith(root.trimEnd('/') + "/")
    private fun sameEntry(a: StructStat, b: StructStat): Boolean = a.st_dev == b.st_dev && a.st_ino == b.st_ino
    private fun hostVisible(path: String, stat: StructStat): Boolean = try {
        sameEntry(stat, Os.lstat(path))
    } catch (_: android.system.ErrnoException) {
        false // An entry in a private mount need not exist in the host namespace.
    }

    private data class Mapping(val host: String?, val guest: String, val privateToContainer: Boolean)

    private const val CONTAINER_PREFIX = "container-"
}
