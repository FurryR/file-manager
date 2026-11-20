package io.furryr.file.lsp

import android.content.Context
import android.net.Uri
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.source.OpenMode
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.daemon.process.CommandSpec
import io.furryr.file.daemon.process.PipeChannel
import io.furryr.file.provider.terminal.ExecProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/** A server the LSP engine can talk to. */
internal interface LspProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream

    suspend fun waitFor(): Int
    suspend fun terminate()
}

/** Where a server runs and how VFS nodes map to server URIs. */
internal interface LspHost {
    val key: String
    val rootUri: String?

    fun uri(node: VNode): String?

    suspend fun start(server: LspServerSpec): Result<LspProcess>
    suspend fun close()
}

internal object LspHosts {
    fun create(context: Context, root: VFolder?, server: LspServerSpec): LspHost? =
        when (server.workspace) {
            LspWorkspaceMode.EXEC -> {
                val exec = root?.provider as? ExecProvider
                if (root == null || exec == null) null else ProviderLspHost(exec, root)
            }
            LspWorkspaceMode.MIRROR -> root?.let { MirrorLspHost(context, it, server.id) }
            LspWorkspaceMode.VIRTUAL -> VirtualLspHost(context, server.id)
        }
}

/** Server sees the provider's own execution namespace (host, container, SSH). */
private class ProviderLspHost(
    private val exec: ExecProvider,
    private val root: VFolder,
) : LspHost {
    private val rootPath: String? = exec.terminalPath(root, root)
    override val key: String = "exec:" + root.path.toString() + ':' + (rootPath ?: "")
    override val rootUri: String? = rootPath?.let(::fileUri)

    override fun uri(node: VNode): String? =
        exec.terminalPath(root, node)?.let(::fileUri)

    override suspend fun start(server: LspServerSpec): Result<LspProcess> {
        val result = runCatching {
            val cwd = rootPath ?: error("workspace is not visible to its execution host")
            val argv = server.argv
            require(argv.isNotEmpty()) { "server command is empty" }
            val channel = exec.spawnPipes(
                root,
                CommandSpec(
                    command = argv.first(),
                    args = argv.drop(1),
                    env = server.env,
                    cwd = cwd,
                    stdin = true,
                ),
            ).getOrThrow()
            PipeLspProcess(channel)
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result
    }

    override suspend fun close() = Unit
}

/** Explicit opt-in: copy the workspace into an app cache and run on the host daemon. */
private class MirrorLspHost(
    private val context: Context,
    private val root: VFolder,
    serverId: String,
) : LspHost {
    private val mirror = File(
        context.cacheDir,
        "lsp/mirror/" + hash(root.path.toString() + '\u0000' + serverId),
    )
    override val key: String = "mirror:" + root.path.toString() + '\u0000' + serverId
    override val rootUri: String = Uri.fromFile(mirror).toString()

    override fun uri(node: VNode): String? {
        val relative = relative(node) ?: return null
        return Uri.fromFile(if (relative.isEmpty()) mirror else File(mirror, relative)).toString()
    }

    override suspend fun start(server: LspServerSpec): Result<LspProcess> = withContext(Dispatchers.IO) {
        val result = runCatching {
            stage()
            val argv = server.argv
            require(argv.isNotEmpty()) { "server command is empty" }
            val channel = DaemonManager.execService(DaemonTarget.Host).spawnPipes(
                CommandSpec(
                    command = argv.first(),
                    args = argv.drop(1),
                    env = server.env,
                    cwd = mirror.path,
                    stdin = true,
                ),
            ).getOrThrow()
            PipeLspProcess(channel)
        }
        result.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            mirror.deleteRecursively()
        }
        result
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { mirror.deleteRecursively() }
    }

    private suspend fun stage() {
        mirror.deleteRecursively()
        check(mirror.mkdirs() || mirror.isDirectory) { "cannot create the LSP mirror" }
        var bytes = 0L
        var files = 0

        suspend fun copy(folder: VFolder, target: File) {
            val children = folder.listNodes().getOrThrow()
            for (node in children) {
                if (node.name == ".git") continue
                // A provider must never hand back a traversal segment; reject
                // one here so a mirror cannot escape its cache directory.
                require(node.name.isNotEmpty() && node.name != "." && node.name != ".." && '/' !in node.name) {
                    "invalid workspace entry: ${node.name}"
                }
                val destination = File(target, node.name)
                when (node) {
                    is VFolder -> {
                        check(destination.mkdirs() || destination.isDirectory) { "cannot stage ${node.path}" }
                        copy(node, destination)
                    }
                    is VFile -> {
                        files++
                        if (files > MAX_FILES) error("workspace has too many files to mirror")
                        val source = node.open(OpenMode.READ).getOrThrow()
                        source.use { input ->
                            FileOutputStream(destination).use { output ->
                                var offset = 0L
                                while (true) {
                                    val chunk = input.readAt(offset, CHUNK)
                                    if (chunk.isEmpty()) break
                                    bytes += chunk.size
                                    if (bytes > MAX_BYTES) error("workspace is too large to mirror")
                                    output.write(chunk)
                                    offset += chunk.size
                                }
                            }
                        }
                    }
                }
            }
        }

        copy(root, mirror)
    }

    private fun relative(node: VNode): String? {
        val rootText = root.path.toString()
        val nodeText = node.path.toString()
        if (nodeText == rootText) return ""
        val prefix = rootText.trimEnd('/') + "/"
        if (!nodeText.startsWith(prefix)) return null
        return nodeText.removePrefix(prefix)
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val MAX_BYTES = 128L * 1024 * 1024
        const val MAX_FILES = 20_000
    }
}

/** Explicit opt-in: no workspace on disk; only open documents are synchronized. */
private class VirtualLspHost(
    private val context: Context,
    serverId: String,
) : LspHost {
    override val key: String = "virtual:$serverId"
    override val rootUri: String? = null

    override fun uri(node: VNode): String {
        val path = "/file-manager-virtual/" + hash(node.path.toString()) + "/" + node.name
        return Uri.fromFile(File(path)).toString()
    }

    override suspend fun start(server: LspServerSpec): Result<LspProcess> {
        val result = runCatching {
            val argv = server.argv
            require(argv.isNotEmpty()) { "server command is empty" }
            val channel = DaemonManager.execService(DaemonTarget.Host).spawnPipes(
                CommandSpec(
                    command = argv.first(),
                    args = argv.drop(1),
                    env = server.env,
                    cwd = context.cacheDir.path,
                    stdin = true,
                ),
            ).getOrThrow()
            PipeLspProcess(channel)
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result
    }

    override suspend fun close() = Unit
}

private class PipeLspProcess(private val channel: PipeChannel) : LspProcess {
    override val stdin: OutputStream get() = channel.output
    override val stdout: InputStream get() = channel.stdout
    override val stderr: InputStream get() = channel.stderr

    override suspend fun waitFor(): Int = channel.waitFor().getOrDefault(-1)

    override suspend fun terminate() {
        runCatching { channel.close() }
        withContext(Dispatchers.IO) { runCatching { channel.closeProcess() } }
    }
}

internal fun fileUri(path: String): String = Uri.fromFile(File(path)).toString()

private fun hash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
