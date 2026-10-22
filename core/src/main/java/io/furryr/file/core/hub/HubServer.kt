package io.furryr.file.core.hub

import android.util.Log
import com.google.protobuf.ByteString
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
import io.furryr.file.core.HubActions
import io.furryr.file.core.HubCallerRoot
import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.proto.HandleMode
import io.furryr.file.proto.hub.AiConfigResponse
import io.furryr.file.proto.hub.CallerRoot
import io.furryr.file.proto.hub.ExecResponse
import io.furryr.file.proto.hub.GlobRequest
import io.furryr.file.proto.hub.GlobResponse
import io.furryr.file.proto.hub.HubRequest
import io.furryr.file.proto.hub.HubResponse
import io.furryr.file.proto.hub.ListTargetsResponse
import io.furryr.file.proto.hub.ReadFileRequest
import io.furryr.file.proto.hub.ReadFileResponse
import io.furryr.file.proto.hub.Target
import io.furryr.file.proto.hub.WaitResponse
import io.furryr.file.proto.hub.WriteFileRequest
import io.furryr.file.proto.hub.WriteFileResponse
import io.furryr.file.uds.Transport
import io.furryr.file.uds.TransportEvent
import io.furryr.file.uds.UdsEndpoint
import io.furryr.file.uds.UdsServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap

/**
 * The communication hub: a local UDS server.
 *
 * The socket address is open; a connection proves which daemon spawned its
 * caller by presenting that daemon's key in Hello. Every operation requires
 * authentication first. The hub translates each operation onto the selected
 * daemon — spawning PTYs, forwarding signals, reaping children — and never
 * exposes the daemon's own file protocol. Each connection owns its PTY handles
 * and keeps its own authenticated caller root.
 */
class HubServer internal constructor(private val server: UdsServer) : Closeable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            server.connections.collect { client ->
                scope.launch { serve(client) }
            }
        }
    }

    private suspend fun serve(client: Transport) = coroutineScope {
        val state = ConnectionState()
        try {
            client.events.takeWhile { it !is TransportEvent.Closed }.collect { event ->
                when (event) {
                    is TransportEvent.Incoming -> {
                        try {
                            val request = try {
                                HubRequest.parseFrom(event.frame.payload)
                            } catch (_: Exception) {
                                null
                            }
                            val failure = when {
                                request == null -> failure(0, "malformed hub request")
                                event.frame.descriptorsTruncated -> failure(request.id,
                                    "hub descriptor transfer was truncated (MSG_CTRUNC); received=${event.frame.fds.size}")
                                event.frame.fds.isNotEmpty() -> failure(request.id, "unexpected hub descriptors")
                                request.opCase != HubRequest.OpCase.HELLO && !state.authenticated ->
                                    failure(request.id, "authentication required")
                                request.opCase == HubRequest.OpCase.HELLO && state.authenticated ->
                                    failure(request.id, "caller is already authenticated")
                                else -> null
                            }
                            if (failure != null) {
                                send(client, Reply(failure))
                            } else if (request!!.opCase == HubRequest.OpCase.WAIT_EXEC) {
                                val handle = request.waitExec.execId
                                val exec = state.execs[handle]
                                if (exec == null) {
                                    send(client, Reply(failure(request.id, "exec not owned by client")))
                                } else {
                                    launch {
                                        val reply = try {
                                            val code = runInterruptible {
                                                exec.connection.waitExec(exec.execId).getOrThrow()
                                            }
                                            state.execs.remove(handle, exec)
                                            Reply(
                                                HubResponse.newBuilder().setId(request.id).setOk(true)
                                                    .setWait(WaitResponse.newBuilder().setExitCode(code)).build(),
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            state.execs.remove(handle, exec)
                                            runCatching { exec.connection.closeExec(exec.execId) }
                                            Reply(failure(request.id, e.message ?: "hub request failed"))
                                        }
                                        try {
                                            send(client, reply)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w(TAG, "hub exec wait response delivery failed", e)
                                            client.close()
                                        }
                                    }
                                }
                            } else if (request!!.opCase == HubRequest.OpCase.WAIT) {
                                // Capture ownership in request order, before a later CLOSE.
                                val handle = request.wait.ptyId
                                val pty = state.ptys[handle]
                                if (pty == null) {
                                    send(client, Reply(failure(request.id, "PTY not owned by client")))
                                } else {
                                    launch {
                                        val reply = try {
                                            val code = runInterruptible {
                                                pty.connection.waitPty(pty.id).getOrThrow()
                                            }
                                            state.ptys.remove(handle, pty)
                                            pty.terminalTarget?.close()
                                            Reply(
                                                HubResponse.newBuilder().setId(request.id).setOk(true)
                                                    .setWait(WaitResponse.newBuilder().setExitCode(code)).build(),
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Reply(failure(request.id, e.message ?: "hub request failed"))
                                        }
                                        try {
                                            send(client, reply)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w(TAG, "hub wait response delivery failed", e)
                                            client.close()
                                        }
                                    }
                                }
                            } else {
                                send(client, dispatch(request!!, state))
                            }
                        } finally {
                            event.frame.close()
                        }
                    }
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "hub client ended", e)
        } finally {
            coroutineContext.cancelChildren()
            runCatching { client.close() }
            state.ptys.values.forEach { pty ->
                pty.terminalTarget?.close()
                pty.connection.closePty(pty.id).onFailure {
                    Log.w(TAG, "hub PTY cleanup failed", it)
                }
            }
            state.ptys.clear()
            state.execs.values.forEach { exec ->
                exec.connection.closeExec(exec.execId).onFailure {
                    Log.w(TAG, "hub exec cleanup failed", it)
                }
            }
            state.execs.clear()
        }
    }

    private suspend fun send(client: Transport, reply: Reply) {
        try {
            client.send(reply.response.toByteArray(), reply.fds)
        } finally {
            // SCM_RIGHTS duplicates each descriptor; drop our copies even on send failure.
            reply.fds.forEach { runCatching { android.system.Os.close(it) } }
        }
    }

    private fun dispatch(request: HubRequest, state: ConnectionState): Reply {
        val id = request.id
        return runCatching {
            when (request.opCase) {
                HubRequest.OpCase.HELLO -> {
                    require(request.hello.key.isNotBlank()) { "missing daemon key" }
                    val target = requireNotNull(DaemonManager.targetForKey(request.hello.key)) {
                        "unknown daemon key"
                    }
                    val containerId = DaemonManager.hubId(target)
                    val root = requireNotNull(HubActions.resolveRoot) {
                        "filesystem resolution is unavailable"
                    }.invoke(containerId)
                    state.authenticated = true
                    state.root = root
                    state.target = target
                    state.terminalId = request.hello.terminalId
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true).setCallerRoot(
                            CallerRoot.newBuilder().setContainerId(containerId),
                        ).build(),
                    )
                }

                HubRequest.OpCase.LIST_TARGETS ->
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setTargets(listTargets()).build(),
                    )

                HubRequest.OpCase.GET_AI_CONFIG -> {
                    val config = requireNotNull(HubActions.aiConfigProvider) {
                        "AI configuration is unavailable"
                    }.invoke()
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setAiConfig(
                                AiConfigResponse.newBuilder().setBaseUrl(config.baseUrl)
                                    .setApiKey(config.apiKey).setModel(config.model)
                                    .setEffort(config.effort).setProvider(config.provider),
                            ).build(),
                    )
                }

                HubRequest.OpCase.SET_AI_SELECTION -> {
                    val selection = request.setAiSelection
                    requireNotNull(HubActions.aiSelectionSaver) {
                        "AI selection persistence is unavailable"
                    }.invoke(selection.model, selection.effort)
                    Reply(HubResponse.newBuilder().setId(id).setOk(true).build())
                }

                HubRequest.OpCase.EXEC -> {
                    val exec = request.exec
                    val targetId = exec.containerId.ifBlank { "host" }
                    val target = target(targetId)
                    val root = requireNotNull(state.root) { "hello is required" }
                    val cwd = exec.cwd.ifBlank { null }?.let {
                        requireNotNull(HubActions.resolveCwd) { "filesystem resolution is unavailable" }
                            .invoke(root, targetId, it)
                    }
                    val connection = DaemonManager.connection(target)
                    val env = exec.envMap + Hub.environment(target) +
                        mapOf("HUB_TERMINAL" to state.terminalId)
                    if (exec.pipes) {
                        val stream = connection.spawnExec(
                            command = exec.command,
                            args = exec.argsList,
                            cwd = cwd,
                            env = env,
                        ).getOrThrow()
                        val handle = state.nextHandle++
                        state.execs[handle] = OwnedExec(connection, stream.execId)
                        return@runCatching Reply(
                            HubResponse.newBuilder().setId(id).setOk(true)
                                .setExec(
                                    ExecResponse.newBuilder()
                                        .setExecId(handle).setPid(stream.childPid).build(),
                                ).build(),
                            listOf(stream.stdout, stream.stderr),
                        )
                    }
                    val pty = connection.spawnPty(
                        command = exec.command,
                        args = exec.argsList,
                        env = env,
                        termType = exec.term,
                        rows = exec.rows,
                        cols = exec.cols,
                        cwd = cwd,
                        user = if (exec.hasUser()) exec.user else null,
                        nice = if (exec.hasNice()) exec.nice else null,
                    ).getOrThrow()
                    val handle = state.nextHandle++
                    state.ptys[handle] = OwnedPty(
                        connection, pty.ptyId,
                        HubActions.onTerminalTarget?.invoke(state.terminalId, targetId),
                    )
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setExec(
                                ExecResponse.newBuilder()
                                    .setPtyId(handle).setPid(pty.childPid).build(),
                            ).build(),
                        listOfNotNull(pty.fileDescriptor),
                    )
                }

                HubRequest.OpCase.READ_FILE ->
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setReadFile(readFile(request.readFile)).build(),
                    )

                HubRequest.OpCase.WRITE_FILE ->
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setWriteFile(writeFile(request.writeFile)).build(),
                    )

                HubRequest.OpCase.GLOB ->
                    Reply(
                        HubResponse.newBuilder().setId(id).setOk(true)
                            .setGlob(glob(request.glob)).build(),
                    )

                HubRequest.OpCase.RESIZE -> {
                    val r = request.resize
                    val pty = state.pty(r.ptyId)
                    pty.connection.resizePty(pty.id, r.rows, r.cols).getOrThrow()
                    Reply(ok(id))
                }

                HubRequest.OpCase.HERE -> {
                    val path = request.here.path
                    require(path.startsWith('/')) { "here requires an absolute path" }
                    val reveal = HubActions.onHere ?: error("file navigation is unavailable")
                    reveal(path, requireNotNull(state.root) { "hello is required" })
                    Reply(ok(id))
                }

                HubRequest.OpCase.SIGNAL -> {
                    val s = request.signal
                    val pty = state.pty(s.ptyId)
                    pty.connection.signalPty(pty.id, s.signal).getOrThrow()
                    Reply(ok(id))
                }

                HubRequest.OpCase.CLOSE -> {
                    val handle = request.close.ptyId
                    val pty = state.pty(handle)
                    pty.connection.closePty(pty.id).getOrThrow()
                    state.ptys.remove(handle, pty)
                    pty.terminalTarget?.close()
                    Reply(ok(id))
                }

                else -> Reply(failure(id, "missing hub op"))
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            Reply(failure(id, it.message ?: "hub request failed"))
        }
    }

    private fun listTargets(): ListTargetsResponse {
        val live = DaemonManager.liveEndpoints().associateBy { it.name }
        val names = LinkedHashSet<String>()
        names.add("host")
        names.add("app")
        names.addAll(live.keys)
        runCatching { HubActions.containerIds?.invoke().orEmpty() }
            .onFailure { Log.w(TAG, "container enumeration failed", it) }
            .getOrDefault(emptyList())
            .let(names::addAll)
        val ordered = names.filter { it == "host" || it == "app" } +
            names.filterNot { it == "host" || it == "app" }.sorted()
        return ListTargetsResponse.newBuilder().addAllTargets(
            ordered.map { name ->
                val endpoint = live[name]
                Target.newBuilder()
                    .setName(name)
                    .setElevated(endpoint?.elevated == true)
                    .setRunning(endpoint != null)
                    .build()
            },
        ).build()
    }

    private fun readFile(request: ReadFileRequest): ReadFileResponse {
        require(request.path.isNotBlank()) { "read requires a path" }
        val targetId = request.containerId.ifBlank { "host" }
        val connection = DaemonManager.connection(target(targetId))
        val handle = connection.openHandle(request.path, HandleMode.READ, wantFd = false)
            .getOrThrow()
        try {
            val size = handle.size
            val offset = request.offset.coerceAtMost(size)
            val limit = if (request.length <= 0) DEFAULT_READ_SIZE
            else request.length.toLong().coerceAtMost(MAX_READ_SIZE)
            val output = ByteArrayOutputStream()
            var position = offset
            var eof = position >= size
            while (!eof && output.size() < limit) {
                val remaining = (limit - output.size()).coerceAtMost(CHUNK_SIZE.toLong()).toInt()
                val chunk = connection.readHandle(handle.handleId, position, remaining).getOrThrow()
                val data = chunk.data.toByteArray()
                if (data.isEmpty()) {
                    eof = true
                    break
                }
                output.write(data)
                position += data.size
                eof = chunk.eof || position >= size
            }
            return ReadFileResponse.newBuilder()
                .setData(ByteString.copyFrom(output.toByteArray()))
                .setSize(size)
                .setEof(eof)
                .build()
        } finally {
            runCatching { connection.closeHandle(handle.handleId) }
        }
    }

    private fun writeFile(request: WriteFileRequest): WriteFileResponse {
        require(request.path.isNotBlank()) { "write requires a path" }
        val targetId = request.containerId.ifBlank { "host" }
        val connection = DaemonManager.connection(target(targetId))
        val data = request.data.toByteArray()
        val handle = connection.openHandle(request.path, HandleMode.WRITE, wantFd = false)
            .getOrThrow()
        try {
            if (data.isNotEmpty()) {
                connection.writeHandle(handle.handleId, 0, data).getOrThrow()
            }
            return WriteFileResponse.newBuilder().setBytesWritten(data.size.toLong()).build()
        } finally {
            runCatching { connection.closeHandle(handle.handleId) }
        }
    }

    private fun glob(request: GlobRequest): GlobResponse {
        val targetId = request.containerId.ifBlank { "host" }
        val connection = DaemonManager.connection(target(targetId))
        val base = request.path.ifBlank { "/" }
        require(base.startsWith('/')) { "glob path must be absolute" }
        val basePath = base.trimEnd('/').ifEmpty { "/" }
        val prefix = if (basePath == "/") "/" else "$basePath/"
        val maxResults = if (request.maxResults <= 0) DEFAULT_GLOB_RESULTS
        else request.maxResults.coerceAtMost(MAX_GLOB_RESULTS)
        val matcher = GlobMatcher(request.pattern.ifBlank { "*" })
        val pending = ArrayDeque<String>()
        pending.addLast(basePath)
        val paths = ArrayList<String>()
        var visited = 0
        var truncated = false
        while (pending.isNotEmpty()) {
            if (++visited > MAX_GLOB_VISITS) {
                truncated = true
                break
            }
            val directory = pending.removeLast()
            val entries = try {
                connection.listFiles(directory).getOrThrow()
            } catch (e: Exception) {
                if (directory == basePath) throw e else continue
            }
            for (entry in entries) {
                val relative = if (entry.path.startsWith(prefix)) {
                    entry.path.removePrefix(prefix)
                } else {
                    entry.path.trimStart('/')
                }
                if (relative.isNotEmpty() && matcher.matches(relative)) {
                    paths.add(entry.path)
                    if (paths.size >= maxResults) {
                        truncated = true
                        break
                    }
                }
                if (entry.isDirectory && !entry.isSymlink && relative.isNotEmpty()) {
                    pending.addLast(entry.path)
                }
            }
            if (truncated) break
        }
        paths.sort()
        return GlobResponse.newBuilder().addAllPaths(paths).setTruncated(truncated).build()
    }

    private class GlobMatcher(pattern: String) {
        private val regex = Regex(buildString {
            append('^')
            var index = 0
            while (index < pattern.length) {
                when (val c = pattern[index]) {
                    '*' -> {
                        if (index + 1 < pattern.length && pattern[index + 1] == '*') {
                            if (index + 2 < pattern.length && pattern[index + 2] == '/') {
                                append("(?:.*/)?")
                                index += 2
                            } else {
                                append(".*")
                                index += 1
                            }
                        } else {
                            append("[^/]*")
                        }
                    }
                    '?' -> append("[^/]")
                    '[' -> {
                        val end = pattern.indexOf(']', index + 1)
                        if (end < 0) {
                            append("\\[")
                        } else {
                            append('[')
                            val start = index + 1
                            val first = if (start < end && (pattern[start] == '!' || pattern[start] == '^')) start + 1 else start
                            if (first > start) append('^')
                            for (i in first until end) {
                                val item = pattern[i]
                                if (item == '\\') append("\\\\") else append(item)
                            }
                            append(']')
                            index = end
                        }
                    }
                    '\\' -> {
                        if (index + 1 < pattern.length) {
                            append(Regex.escape(pattern[index + 1].toString()))
                            index += 1
                        } else {
                            append("\\\\")
                        }
                    }
                    '.', '+', '(', ')', '^', '$', '{', '}', '|' -> append('\\').append(c)
                    else -> append(c)
                }
                index += 1
            }
            append('$')
        })
        fun matches(path: String): Boolean = regex.matches(path)
    }

    private fun target(containerId: String): DaemonTarget = when (containerId) {
        "host" -> DaemonTarget.Host
        "app" -> DaemonTarget.App
        else -> {
            require(containerId.startsWith(CONTAINER_PREFIX)) {
                "invalid container id '$containerId'"
            }
            DaemonTarget.Named(containerId.removePrefix(CONTAINER_PREFIX))
        }
    }

    private fun ok(id: Int): HubResponse =
        HubResponse.newBuilder().setId(id).setOk(true).build()

    private fun failure(id: Int, message: String): HubResponse =
        HubResponse.newBuilder().setId(id).setOk(false).setError(message).build()

    override fun close() {
        server.close()
        scope.cancel()
    }

    private class ConnectionState {
        var authenticated: Boolean = false
        var root: HubCallerRoot? = null
        var terminalId: String = ""
        var target: DaemonTarget = DaemonTarget.Host
        var nextHandle: Long = 1
        val ptys = ConcurrentHashMap<Long, OwnedPty>()
        val execs = ConcurrentHashMap<Long, OwnedExec>()

        fun pty(handle: Long): OwnedPty =
            ptys[handle] ?: error("PTY not owned by client")
    }

    private class OwnedPty(
        val connection: DaemonConnection,
        val id: Long,
        val terminalTarget: Closeable?,
    )

    private class OwnedExec(
        val connection: DaemonConnection,
        val execId: Long,
    )

    private class Reply(
        val response: HubResponse,
        val fds: List<FileDescriptor> = emptyList(),
    )

    companion object {
        private const val TAG = "HubServer"
        private const val CONTAINER_PREFIX = "container-"
        private const val DEFAULT_READ_SIZE = 64L * 1024
        private const val MAX_READ_SIZE = 1024L * 1024
        private const val CHUNK_SIZE = 64 * 1024
        private const val DEFAULT_GLOB_RESULTS = 200
        private const val MAX_GLOB_RESULTS = 1000
        private const val MAX_GLOB_VISITS = 20_000

        /** Bind [endpoint] and start serving. */
        fun listen(endpoint: UdsEndpoint): HubServer = HubServer(UdsServer.listen(endpoint))
    }
}
