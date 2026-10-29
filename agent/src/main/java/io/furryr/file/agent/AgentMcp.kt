package io.furryr.file.agent

import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.proto.hub.RemoteMcpCall
import io.furryr.file.proto.hub.RemoteMcpCallEvent

/** MCP catalog and HTTP proxy facades used by the transport layer. */
object AgentMcp {
    suspend fun servers(target: DaemonTarget, cwd: String): List<AgentMcpServer> =
        McpConfig.servers(target, cwd)

    suspend fun request(
        request: RemoteMcpCall,
        target: DaemonTarget,
        emit: suspend (RemoteMcpCallEvent) -> Unit,
    ) = McpProxy.request(request, target, emit)
}
