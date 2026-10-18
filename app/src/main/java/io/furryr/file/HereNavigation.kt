package io.furryr.file

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import io.furryr.file.agent.ContainerBackend
import io.furryr.file.agent.ContainerManager
import io.furryr.file.agent.ProotRunner
import io.furryr.file.core.HubActions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.io.FileDescriptor

/** Converts a borrowed host descriptor into an app-owned navigation request. */
object HereNavigation {
    data class Request(val directory: String, val selectedPath: String?)

    // Retain requests across activity recreation; only the current UI consumes them.
    private val pending = Channel<Request>(Channel.CONFLATED)
    val requests = pending.receiveAsFlow()

    fun install(context: Context) {
        val appContext = context.applicationContext
        HubActions.onHere = { fd ->
            val request = resolve(appContext, fd)
            check(pending.trySend(request).isSuccess) { "file navigation is unavailable" }
        }
    }

    private fun resolve(context: Context, fd: FileDescriptor): Request {
        // Reading the link (not canonicalFile on the selected entry) preserves
        // O_NOFOLLOW's final symlink, even when that symlink is dangling.
        val hostPath = ParcelFileDescriptor.dup(fd).use {
            Os.readlink("/proc/self/fd/${it.fd}")
        }
        require(hostPath.startsWith('/')) { "the selected descriptor has no filesystem path" }
        val selectedStat = Os.fstat(fd)
        require(selectedStat.st_nlink > 0) { "the selected entry has been deleted" }

        val mappings = ContainerManager.listContainers(context).getOrThrow().flatMap { container ->
            val root = Mapping(
                File(container.rootfsPath).canonicalPath, container.name, "/",
                ProotRunner.overlaidGuestPaths(context, container),
            )
            val runtime = ProotRunner.privateRuntimeMounts(context, container)
                .filter { mount ->
                    // Native chroot uses private tmpfs at /tmp and /run, whose
                    // descriptors already resolve under the rootfs mount path.
                    (container.backend == ContainerBackend.PROOT ||
                        mount.dstContainerPath != "/tmp" && mount.dstContainerPath != "/run") &&
                        File(mount.srcAndroidPath).exists()
                }
                .map {
                    Mapping(
                        File(it.srcAndroidPath).canonicalPath, container.name, it.dstContainerPath,
                        container.bindMounts.map { mount -> mount.dstContainerPath },
                    )
                }
            listOf(root) + runtime
        }
        val candidate = mappings.filter { contains(it.host, hostPath) }.maxByOrNull { it.host.length }
        val mapping = candidate?.takeUnless { entry ->
            val inner = entry.guestPath(hostPath).ifEmpty { "/" }
            val covered = entry.overlaidPaths.any {
                val guestMount = File("/" + it.trimStart('/')).normalize().path
                contains(guestMount, inner)
            }
            if (!covered) false else {
                // A host-opened rootfs file may be hidden by a guest bind or
                // tmpfs. Keep it local when lstat proves it is that original
                // host inode. Private chroot mounts have the same pathname but
                // a different inode (or no visible host entry), and must retain
                // their container route. Never reopen or dereference the FD.
                val hostStat = runCatching { Os.lstat(hostPath) }
                    .onFailure { Log.d("HereNavigation", "Selected path is not visible in the host namespace", it) }
                    .getOrNull()
                hostStat != null && hostStat.st_dev == selectedStat.st_dev &&
                    hostStat.st_ino == selectedStat.st_ino
            }
        }
        val selected = if (mapping != null) {
            "container://${mapping.container}${mapping.guestPath(hostPath)}"
        } else {
            // Shared Android binds can belong to many containers. Their host
            // directory is unambiguous and remains usable outside any container.
            hostPath
        }
        if (selected == "/") return Request("/", null)
        if (mapping != null && selected == "container://${mapping.container}") {
            return Request("container:///", selected)
        }
        val parent = selected.substringBeforeLast('/').let {
            when {
                it.isEmpty() -> "/"
                mapping != null && it == "container://${mapping.container}" -> "$it/"
                else -> it
            }
        }
        return Request(parent, selected)
    }

    private fun contains(root: String, path: String): Boolean =
        path == root || path.startsWith(root.trimEnd('/') + "/")

    private data class Mapping(
        val host: String,
        val container: String,
        val guest: String,
        val overlaidPaths: List<String>,
    ) {
        fun guestPath(hostPath: String): String = guest.trimEnd('/') + hostPath.removePrefix(host)
    }
}
