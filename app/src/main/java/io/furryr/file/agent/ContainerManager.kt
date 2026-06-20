package io.furryr.file.agent

import android.content.Context
import android.util.Log
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
 * Container processes are not tracked here — [SessionManager] manages
 * terminal sessions with proot directly via [ProotRunner]. Use
 * [SessionManager.hasContainerSession] to check whether a container has
 * an active terminal.
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
    ): Result<Container> = withContext(Dispatchers.IO) {
        var didCreateDir = false
        try {
            validateContainerName(name).getOrElse { return@withContext Result.failure(it) }
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

            DistroFetcher.downloadRootfs(imageRef, containerDir, null).getOrThrow()

            val distroId = parseImageId(imageRef)

            val container = Container(
                name = name,
                distroId = distroId,
                state = ContainerState.CREATED,
                createdAt = System.currentTimeMillis(),
                rootfsPath = containerDir.absolutePath,
            )

            saveDistro(containerDir, container, imageRef)

            Log.i(TAG, "Container '$name' created (image=$imageRef) at ${containerDir.absolutePath}")
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

            deleteRecursively(containerDir)
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
                            val liveState = if (SessionManager.hasContainerSession(container.name)) {
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
            val containerDir = getContainerDir(context, name)
            val container = loadContainer(containerDir)

            val cmd = ProotRunner.buildProotCommand(
                context,
                container,
                listOf(command),
            )

            val pb = ProcessBuilder(cmd)
            pb.directory(File(container.rootfsPath))
            pb.redirectErrorStream(true)

            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val trimmed = output.trim()
                return@withContext Result.failure(
                    RuntimeException(
                        if (trimmed.isNotEmpty()) trimmed
                        else "Command exited with code $exitCode"
                    )
                )
            }

            Log.d(TAG, "execInContainer '$name': '$command' → exit $exitCode")
            Result.success(output.trim())
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

        return Container(
            name = json.getString("name"),
            distroId = json.getString("distroId"),
            state = ContainerState.CREATED,
            createdAt = json.optLong("createdAt", 0L),
            rootfsPath = containerDir.absolutePath,
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
