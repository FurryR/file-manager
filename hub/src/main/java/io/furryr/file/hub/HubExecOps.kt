package io.furryr.file.hub

import io.furryr.file.agent.AgentEnvironment
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.HubActions
import io.furryr.file.daemon.process.CommandSpec
import io.furryr.file.proto.hub.AiConfigResponse
import io.furryr.file.proto.hub.CallerRoot
import io.furryr.file.proto.hub.ExecResponse
import io.furryr.file.proto.hub.HubRequest
import io.furryr.file.proto.hub.HubResponse
import io.furryr.file.uds.Transport
import io.furryr.file.hub.HubServer.ConnectionState
import io.furryr.file.hub.HubServer.OwnedExec
import io.furryr.file.hub.HubServer.OwnedPty
import io.furryr.file.hub.HubServer.Reply

/** Handshake, exec/PTY and AI-selection requests. Returns null for another category. */
internal suspend fun HubServer.dispatchExecOps(
    request: HubRequest,
    state: ConnectionState,
    client: Transport,
): Reply? {
    val id = request.id
    return when (request.opCase) {
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

        HubRequest.OpCase.LIST_TARGETS -> Reply(
            HubResponse.newBuilder().setId(id).setOk(true)
                .setTargets(HubTargets.list()).build(),
        )

        HubRequest.OpCase.GET_AI_CONFIG -> {
            val config = requireNotNull(AgentEnvironment.aiConfigProvider) {
                "AI configuration is unavailable"
            }.invoke()
            Reply(
                HubResponse.newBuilder().setId(id).setOk(true)
                    .setAiConfig(
                        AiConfigResponse.newBuilder().setBaseUrl(config.baseUrl)
                            .setApiKey("").setModel(config.model)
                            .setEffort(config.effort).setProvider(config.provider),
                    ).build(),
            )
        }

        HubRequest.OpCase.SET_AI_SELECTION -> {
            val selection = request.setAiSelection
            requireNotNull(AgentEnvironment.aiSelectionSaver) {
                "AI selection persistence is unavailable"
            }.invoke(selection.model, selection.effort)
            Reply(HubResponse.newBuilder().setId(id).setOk(true).build())
        }

        HubRequest.OpCase.EXEC -> {
            val exec = request.exec
            val targetId = exec.containerId.ifBlank { DaemonManager.HOST_ID }
            val target = DaemonManager.targetForId(targetId)
            val root = requireNotNull(state.root) { "hello is required" }
            val cwd = exec.cwd.ifBlank { null }?.let {
                requireNotNull(HubActions.resolveCwd) { "filesystem resolution is unavailable" }
                    .invoke(root, targetId, it)
            }
            val env = exec.envMap + Hub.environment(target) +
                mapOf("HUB_TERMINAL" to state.terminalId)
            val service = DaemonManager.execService(target)
            if (exec.pipes) {
                val stream = service.spawnPipes(
                    CommandSpec(
                        command = exec.command,
                        args = exec.argsList,
                        env = env,
                        cwd = cwd,
                    ),
                ).getOrThrow()
                val handle = state.nextHandle++
                state.execs[handle] = OwnedExec(stream)
                Reply(
                    HubResponse.newBuilder().setId(id).setOk(true)
                        .setExec(ExecResponse.newBuilder().setExecId(handle).setPid(stream.pid).build())
                        .build(),
                    listOf(stream.stdoutFd, stream.stderrFd),
                )
            } else {
                val pty = service.spawn(
                    CommandSpec(
                        command = exec.command,
                        args = exec.argsList,
                        env = env,
                        cwd = cwd,
                        termType = exec.term,
                        rows = exec.rows,
                        cols = exec.cols,
                        user = if (exec.hasUser()) exec.user else null,
                        nice = if (exec.hasNice()) exec.nice else null,
                    ),
                ).getOrThrow()
                val handle = state.nextHandle++
                state.ptys[handle] = OwnedPty(
                    pty,
                    HubActions.onTerminalTarget?.invoke(state.terminalId, targetId),
                )
                Reply(
                    HubResponse.newBuilder().setId(id).setOk(true)
                        .setExec(ExecResponse.newBuilder().setPtyId(handle).setPid(pty.pid).build())
                        .build(),
                    listOfNotNull(pty.fileDescriptor),
                )
            }
        }

        HubRequest.OpCase.RESIZE -> {
            val r = request.resize
            val pty = state.pty(r.ptyId)
            pty.channel.resize(r.rows, r.cols).getOrThrow()
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
            pty.channel.signal(s.signal).getOrThrow()
            Reply(ok(id))
        }

        HubRequest.OpCase.CLOSE -> {
            val handle = request.close.ptyId
            val pty = state.pty(handle)
            pty.channel.close()
            state.ptys.remove(handle, pty)
            pty.terminalTarget?.close()
            Reply(ok(id))
        }

        HubRequest.OpCase.CLOSE_EXEC -> {
            val handle = request.closeExec.execId
            val exec = state.execs.remove(handle) ?: error("exec not owned by client")
            exec.channel.closeProcess()
            exec.channel.close()
            Reply(ok(id))
        }

        else -> null
    }
}
