package io.furryr.file.agent

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import io.furryr.file.core.HubCallerRoot
import java.io.File
import java.io.FileDescriptor

/** App-owned filesystem namespace mapping shared by here, run0 and terminal drops. */
object ContainerPaths {
    fun callerRoot(context: Context, fd: FileDescriptor): HubCallerRoot {
        val stat = Os.fstat(fd)
        require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_nlink > 0) { "invalid caller root" }
        if (sameEntry(stat, Os.stat("/"))) return HubCallerRoot(null, "/")
        val container = ContainerManager.listContainers(context).getOrThrow().firstOrNull {
            File(it.rootfsPath).isDirectory && sameEntry(stat, Os.stat(it.rootfsPath))
        } ?: error("caller root is not a registered container")
        return HubCallerRoot(container.name, File(container.rootfsPath).canonicalPath)
    }

    fun revealPath(context: Context, fd: FileDescriptor, root: HubCallerRoot): String {
        val host = descriptorPath(fd)
        val stat = Os.fstat(fd)
        val containers = ContainerManager.listContainers(context).getOrThrow()
        // The caller root disambiguates shared binds and private chroot mounts.
        val caller = containers.firstOrNull { it.name == root.containerName }
        if (caller != null) {
            guestPath(context, caller, host, stat)?.let { return "container://${caller.name}$it" }
        }
        val candidates = containers.filter { it.name != root.containerName }
            .sortedByDescending { File(it.rootfsPath).canonicalPath.length }
        for (container in candidates) {
            val guest = guestPath(context, container, host, stat, privateOnly = true) ?: continue
            return "container://${container.name}$guest"
        }
        return host
    }

    fun workingDirectory(context: Context, fd: FileDescriptor, root: HubCallerRoot, target: String): String {
        val stat = Os.fstat(fd)
        require(OsConstants.S_ISDIR(stat.st_mode)) { "cwd descriptor is not a directory" }
        val host = descriptorPath(fd)
        root.containerName?.let { name ->
            val caller = ContainerManager.getContainer(context, name) ?: error("container '$name' not found")
            val guest = requireNotNull(guestPath(context, caller, host, stat)) { "cwd is outside the caller root" }
            if (target == name) return guest
            require(effectiveMapping(mappings(context, caller), guest).host != null) {
                "cwd is private to container '$name'"
            }
        }
        // A descriptor already has a physical host pathname. Do not walk it
        // again as the app UID: run0 may be elevating from a protected directory.
        if (target == "primary" || target == "app") return host
        val container = ContainerManager.getContainer(context, target) ?: error("container '$target' not found")
        return guestPath(context, container, host) ?: error("cwd is not reachable in container '$target'")
    }

    /** Convert only reachable filesystem paths, never remote/SAF URIs. */
    fun terminalPath(context: Context, path: String, target: String, isDirectory: Boolean): String {
        val targetContainer = if (target == "primary" || target == "app") null else
            ContainerManager.getContainer(context, target) ?: error("container '$target' not found")
        val host = if (path.startsWith("container://")) {
            val source = ContainerUriResolver.resolveContainerUri(path).getOrThrow()
            val name = requireNotNull(source.containerName) { "select a container entry" }
            if (name == targetContainer?.name) return source.containerPath
            val container = ContainerManager.getContainer(context, name) ?: error("container '$name' not found")
            val mappings = mappings(context, container)
            hostPath(mappings, source.containerPath, followFinal = isDirectory)
        } else {
            require(path.startsWith('/')) { "only local and container paths can be inserted into a terminal" }
            // Retain file symlinks, but resolve directory aliases such as /sdcard.
            val file = File(path)
            if (isDirectory) file.canonicalPath
            else if (file.parentFile == null) "/" else File(file.parentFile!!.canonicalFile, file.name).path
        }
        if (targetContainer == null) return host
        return guestPath(context, targetContainer, host)
            ?: error("path is not reachable in container '${targetContainer.name}'")
    }

    private fun descriptorPath(fd: FileDescriptor): String {
        require(Os.fstat(fd).st_nlink > 0) { "the selected entry has been deleted" }
        val path = ParcelFileDescriptor.dup(fd).use { Os.readlink("/proc/self/fd/${it.fd}") }
        require(path.startsWith('/')) { "descriptor has no filesystem path" }
        return path
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
}
