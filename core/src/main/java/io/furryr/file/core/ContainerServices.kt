package io.furryr.file.core

import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.proto.Request
import io.furryr.file.proto.ServiceControlRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContainerServiceStatus(
    val name: String, val state: String, val enabled: Boolean, val pid: Int,
    val exitCode: Int, val detail: String, val description: String,
)
data class ContainerServiceResult(val services: List<ContainerServiceStatus>, val text: String, val exitCode: Int)

/** Public facade; callers do not depend on the wire protocol or daemon internals. */
class ContainerServices internal constructor(private val resolve: () -> DaemonConnection) {
    suspend fun control(action: String, units: List<String> = emptyList(), content: String = ""): Result<ContainerServiceResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val result = resolve().request(Request.newBuilder().setServiceControl(
                    ServiceControlRequest.newBuilder().setAction(action).addAllUnits(units).setContent(content),
                ).build()).response.serviceControl
                ContainerServiceResult(result.servicesList.map {
                    ContainerServiceStatus(it.name, it.state, it.enabled, it.pid, it.exitCode, it.detail, it.description)
                }, result.text, result.exitCode)
            }
        }
}
