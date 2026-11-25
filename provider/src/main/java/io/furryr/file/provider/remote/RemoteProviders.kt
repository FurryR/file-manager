package io.furryr.file.provider.remote

import io.furryr.file.core.FileProvider
import io.furryr.file.provider.RemoteLocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

object RemoteProviders {
    fun providers(cacheDir: File, locations: RemoteLocationSource): List<FileProvider> =
        listOf(AlistProvider(cacheDir, locations), SshProvider(cacheDir, locations))

    suspend fun verify(location: RemoteLocation): Result<Unit> = runInterruptible(Dispatchers.IO) {
        runCatching {
            location.validate()
            val path = normalizeRemotePath(location.basePath)
            when (location) {
                is AlistLocation -> AlistBackend(location).list(path)
                is SshLocation -> SshExecBackend(location).list(path)
            }
            Unit
        }
    }

    /** Drop live SSH resources for a connection the app removed or edited. */
    fun forget(id: String) {
        SshControls.close(id)
        SshWorkers.close(id)
        SshConnections.disconnect(id)
    }
}
