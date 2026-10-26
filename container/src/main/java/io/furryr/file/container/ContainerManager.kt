package io.furryr.file.container

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.util.Log
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.daemon.process.CommandSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File


/**
 * Container lifecycle manager: create, delete, rename, list, exec,
 * bind-mount, and install packages inside proot-based Linux containers.
 *
 * All file I/O and process management runs on [Dispatchers.IO].
 *
 * Container processes are not tracked here — the app supplies
 * [ContainerRuntime.isRunning] from its terminal session manager.
 *
 * Directory layout under [Context.filesDir]:
 *   containers/<name>/             – extracted rootfs + distro.json
 */
object ContainerManager {
    private const val CONTAINERS_DIR = "containers"
    private const val DISTRO_FILE = "distro.json"
    private const val TAG = "ContainerManager"

    // ══════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════

    // ── createContainer ──────────────────────────────────────────────────

    suspend fun createContainer(
        context: Context,
        name: String,
        imageRef: String,
        backend: ContainerBackend = ContainerBackend.PROOT,
        progress: ((Long, Long) -> Unit)? = null,
    ): Result<Container> = withContext(Dispatchers.IO) {
        var didCreateDir = false
        try {
            validateContainerName(name).getOrElse { return@withContext Result.failure(it) }
            if (backend == ContainerBackend.CHROOT && !DaemonManager.isRootAvailable()) {
                return@withContext Result.failure(
                    IllegalStateException("The chroot backend requires root access")
                )
            }
            ProotInstaller.install(context).getOrThrow()

            val containersDir = getContainersDir(context)
            containersDir.mkdirs()

            val containerDir = File(containersDir, name)
            if (containerDir.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Container '$name' already exists")
                )
            }
            didCreateDir = containerDir.mkdirs()

            DistroFetcher.downloadRootfs(imageRef, containerDir, progress).getOrThrow()

            val distroId = parseImageId(imageRef)

            val container = Container(
                name = name,
                distroId = distroId,
                state = ContainerState.CREATED,
                createdAt = System.currentTimeMillis(),
                rootfsPath = containerDir.absolutePath,
                backend = backend,
            )

            saveDistro(containerDir, container, imageRef)

            if (backend == ContainerBackend.CHROOT) {
                // TODO(workaround): the rootfs is extracted as the app UID, but
                // a chroot container expects root-owned files. Ideally the
                // extraction itself would run as root; until then, ask the root
                // daemon for a one-shot recursive chown.
                chownRootfsToRoot(containerDir.absolutePath)
            }

            Log.i(TAG, "Container '$name' created (image=$imageRef, backend=$backend) at ${containerDir.absolutePath}")
            Result.success(container)
        } catch (e: Exception) {
            if (didCreateDir) {
                val partialDir = File(getContainersDir(context), name)
                if (partialDir.exists()) {
                    deleteRecursively(partialDir)
                    Log.w(TAG, "Cleaned up partial container directory for '$name' after failure")
                }
            }
            Result.failure(e)
        }
    }

    // ── deleteContainer ──────────────────────────────────────────────────

    suspend fun deleteContainer(
        context: Context,
        name: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            validateContainerName(name).getOrElse { return@withContext Result.failure(it) }

            val containerDir = getContainerDir(context, name)
            if (!containerDir.exists()) {
                Log.w(TAG, "Container '$name' directory missing during delete")
                return@withContext Result.success(Unit)
            }

            // A chroot rootfs is root-owned, so the app UID cannot unlink it;
            // delegate the recursive removal to the root daemon.
            val container = runCatching { loadContainer(containerDir) }.getOrNull()
            if (container?.backend == ContainerBackend.CHROOT && DaemonManager.isRootAvailable()) {
                val result = DaemonManager.execService(DaemonTarget.Host).run(
                    CommandSpec(
                        command = "/system/bin/toybox",
                        args = listOf("rm", "-rf", containerDir.absolutePath),
                    )
                ).getOrThrow()
                if (result.exitCode != 0) {
                    throw RuntimeException("Failed to delete container: ${result.stderrText.trim()}")
                }
            } else {
                deleteRecursively(containerDir)
            }
            Log.i(TAG, "Container '$name' deleted")

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── renameContainer ──────────────────────────────────────────────────

    suspend fun renameContainer(
        context: Context,
        oldName: String,
        newName: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            validateContainerName(oldName).getOrElse { return@withContext Result.failure(it) }
            validateContainerName(newName).getOrElse { return@withContext Result.failure(it) }
            val containerDir = getContainerDir(context, oldName)
            if (!containerDir.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Container '$oldName' not found at ${containerDir.absolutePath}")
                )
            }

            val newDir = getContainerDir(context, newName)
            if (newDir.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Container '$newName' already exists")
                )
            }

            if (!containerDir.renameTo(newDir)) {
                return@withContext Result.failure(
                    RuntimeException("Failed to rename container '$oldName' to '$newName'")
                )
            }

            // Update distro.json with new name
            val distroFile = File(newDir, DISTRO_FILE)
            if (distroFile.exists()) {
                val json = JSONObject(distroFile.readText())
                json.put("name", newName)
                distroFile.writeText(json.toString(2))
            }

            Log.i(TAG, "Container renamed from '$oldName' to '$newName'")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── listContainers ───────────────────────────────────────────────────

    fun listContainers(context: Context): Result<List<Container>> {
        return try {
            val containersDir = getContainersDir(context)
            if (!containersDir.exists() || !containersDir.isDirectory) {
                return Result.success(emptyList())
            }

            val list = containersDir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir ->
                    val distroFile = File(dir, DISTRO_FILE)
                    if (distroFile.exists()) {
                        try {
                            val container = loadContainer(dir)
                            val liveState = if (ContainerRuntime.isRunning(container.name)) {
                                ContainerState.RUNNING
                            } else {
                                container.state
                            }
                            val resolved = container.copy(state = liveState)
                            val rootfsDir = File(resolved.rootfsPath)
                            if (!rootfsDir.isDirectory) {
                                Log.w(TAG, "Skipping container '${dir.name}': rootfs missing at ${resolved.rootfsPath}")
                                null
                            } else {
                                resolved
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping corrupt container at ${dir.name}: ${e.message}")
                            null
                        }
                    } else {
                        null
                    }
                }
                ?: emptyList()

            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── getContainer ─────────────────────────────────────────────────────

    fun getContainer(context: Context, name: String): Container? {
        return try {
            if (!ContainerUriResolver.isValidContainerName(name)) return null
            val containerDir = getContainerDir(context, name)
            if (!containerDir.exists()) return null
            val distroFile = File(containerDir, DISTRO_FILE)
            if (!distroFile.exists()) return null
            val container = loadContainer(containerDir)
            val rootfsDir = File(container.rootfsPath)
            if (!rootfsDir.isDirectory) {
                Log.w(TAG, "Container '$name': rootfs missing at ${container.rootfsPath}, treating as not found")
                return null
            }
            container
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read container '$name': ${e.message}")
            null
        }
    }

    // ── execInContainer ──────────────────────────────────────────────────

    suspend fun execInContainer(
        context: Context,
        name: String,
        command: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            validateContainerName(name).getOrElse { return@withContext Result.failure(it) }
            // Validates that the container exists and has a manifest.
            loadContainer(getContainerDir(context, name))

            val output = ContainerProvider.execContainer(name, command)
            Log.d(TAG, "execInContainer '$name': '$command'")
            Result.success(output)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── addBindMount ─────────────────────────────────────────────────────

    suspend fun addBindMount(
        context: Context,
        name: String,
        srcAndroidPath: String,
        dstContainerPath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            validateContainerName(name).getOrElse { return@withContext Result.failure(it) }
            val containerDir = getContainerDir(context, name)
            if (!containerDir.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Container '$name' not found")
                )
            }

            val distroFile = File(containerDir, DISTRO_FILE)
            if (!distroFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("distro.json not found for container '$name'")
                )
            }

            val json = JSONObject(distroFile.readText())
            val mountsArray = json.optJSONArray("bindMounts") ?: JSONArray()

            var replaced = false
            for (i in 0 until mountsArray.length()) {
                val mount = mountsArray.getJSONObject(i)
                if (mount.optString("dstContainerPath") == dstContainerPath) {
                    mount.put("srcAndroidPath", srcAndroidPath)
                    mount.put("dstContainerPath", dstContainerPath)
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                mountsArray.put(JSONObject().apply {
                    put("srcAndroidPath", srcAndroidPath)
                    put("dstContainerPath", dstContainerPath)
                })
            }

            json.put("bindMounts", mountsArray)
            distroFile.writeText(json.toString(2))

            // Drop cached container daemons so the new mount takes effect.
            ContainerProvider.resetConnections()

            Log.i(TAG, "Bind mount added to '$name': $srcAndroidPath -> $dstContainerPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Internal helpers — directory layout
    // ══════════════════════════════════════════════════════════════════════

    private fun getContainersDir(context: Context): File =
        File(context.filesDir, CONTAINERS_DIR)

    private fun getContainerDir(context: Context, name: String): File =
        File(getContainersDir(context), name)

    /**
     * Keep the shared container resolv.conf in sync with the device's DNS.
     * Every container binds the same file, written **in place** (truncate +
     * write, never delete + recreate) so both backends pick up changes: the
     * proot bind is path translation and re-reads the file on every open,
     * and a chroot `mount --bind` pins the inode but sees in-place writes.
     * VPN switches surface as link-property changes on (new) networks.
     */
    private var resolverWatchRegistered = false

    fun startResolverWatch(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (resolverWatchRegistered) return
            resolverWatchRegistered = true
        }
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().build()
        runCatching {
            manager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    // Re-resolve through the *active* network rather than the
                    // callback's own link properties: a VPN going up fires this
                    // for both the VPN and the underlying network, and only
                    // the active one's DNS is what containers should use.
                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        ProotRunner.refreshResolverFromActive(appContext)
                    }

                    override fun onAvailable(network: Network) {
                        ProotRunner.refreshResolverFromActive(appContext)
                    }
                },
            )
        }.onFailure { error -> Log.w(TAG, "cannot watch network changes", error) }
    }

    /**
     * TODO(workaround): give a freshly extracted chroot rootfs root ownership by
     * delegating `chown -R 0:0` to the root daemon. Best-effort — root can read
     * app-owned files anyway, so a failure only affects ownership semantics.
     */
    private suspend fun chownRootfsToRoot(rootfs: String) {
        runCatching {
            val uid = android.os.Process.myUid()
            val gid = android.system.Os.getgid()
            val exec = DaemonManager.execService(DaemonTarget.Host)
            // Rootfs contents -> root:0 ...
            val chownAll = exec.run(
                CommandSpec(
                    command = "/system/bin/toybox",
                    args = listOf("chown", "-R", "0:0", rootfs),
                )
            ).getOrThrow()
            if (chownAll.exitCode != 0) {
                Log.w(TAG, "chown rootfs failed (${chownAll.exitCode}): ${chownAll.stderrText.trim()}")
                return@runCatching
            }
            // ... but keep the container directory and metadata app-owned so
            // distro.json stays writable and the tree stays manageable.
            val restore = exec.run(
                CommandSpec(
                    command = "/system/bin/toybox",
                    args = listOf("chown", "$uid:$gid", rootfs, "$rootfs/distro.json"),
                )
            ).getOrThrow()
            if (restore.exitCode != 0) {
                Log.w(TAG, "restore ownership failed (${restore.exitCode}): ${restore.stderrText.trim()}")
            }
        }.onFailure { Log.w(TAG, "chown rootfs request failed", it) }
    }

    private fun validateContainerName(name: String): Result<Unit> {
        return if (ContainerUriResolver.isValidContainerName(name)) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalArgumentException(
                    "Invalid container name, must be alphanumeric with optional hyphens: $name"
                )
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Internal helpers — distro.json persistence
    // ══════════════════════════════════════════════════════════════════════

    private fun parseImageId(imageRef: String): String {
        val name = imageRef.substringAfterLast('/').substringBefore(':')
        return name.ifBlank { "unknown" }
    }

    private fun saveDistro(
        containerDir: File,
        container: Container,
        imageRef: String,
    ) {
        val mountsArray = JSONArray()
        for (mount in container.bindMounts) {
            mountsArray.put(JSONObject().apply {
                put("srcAndroidPath", mount.srcAndroidPath)
                put("dstContainerPath", mount.dstContainerPath)
            })
        }

        val json = JSONObject().apply {
            put("name", container.name)
            put("distroId", container.distroId)
            put("image", imageRef)
            put("backend", container.backend.name.lowercase())
            put("bindMounts", mountsArray)
        }

        File(containerDir, DISTRO_FILE).writeText(json.toString(2))
    }

    private fun loadContainer(containerDir: File): Container {
        val json = JSONObject(File(containerDir, DISTRO_FILE).readText())

        val mountsArray = json.optJSONArray("bindMounts") ?: JSONArray()
        val bindMounts = (0 until mountsArray.length()).map { i ->
            val mount = mountsArray.getJSONObject(i)
            BindMount(
                srcAndroidPath = mount.getString("srcAndroidPath"),
                dstContainerPath = mount.getString("dstContainerPath"),
            )
        }

        val backend = when (json.optString("backend", "proot").lowercase()) {
            "chroot" -> ContainerBackend.CHROOT
            else -> ContainerBackend.PROOT
        }

        return Container(
            name = json.getString("name"),
            distroId = json.getString("distroId"),
            state = ContainerState.CREATED,
            createdAt = json.optLong("createdAt", 0L),
            rootfsPath = containerDir.absolutePath,
            backend = backend,
            bindMounts = bindMounts,
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Internal helpers — file utilities
    // ══════════════════════════════════════════════════════════════════════

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete ${file.absolutePath}")
        }
    }
}
