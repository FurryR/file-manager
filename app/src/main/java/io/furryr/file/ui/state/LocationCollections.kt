package io.furryr.file.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.furryr.file.provider.LocationsRepository
import io.furryr.file.provider.model.FtpServer
import io.furryr.file.provider.model.LocalLocation
import io.furryr.file.provider.model.SafLocation
import io.furryr.file.provider.model.SftpServer
import io.furryr.file.provider.remote.RemoteLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Stable
internal class LocationCollections {
    var safLocations by mutableStateOf<List<SafLocation>>(emptyList())
        private set
    var safRefreshKey by mutableIntStateOf(0)
        private set

    var localLocations by mutableStateOf<List<LocalLocation>>(emptyList())
        private set
    var localRefreshKey by mutableIntStateOf(0)
        private set

    var ftpLocations by mutableStateOf<List<FtpServer>>(emptyList())
        private set
    var ftpRefreshKey by mutableIntStateOf(0)
        private set

    var sftpLocations by mutableStateOf<List<SftpServer>>(emptyList())
        private set
    var sftpRefreshKey by mutableIntStateOf(0)
        private set

    var remoteLocations by mutableStateOf<List<RemoteLocation>>(emptyList())
        private set
    var remoteRefreshKey by mutableIntStateOf(0)
        private set

    fun refreshSaf() {
        safRefreshKey++
    }

    fun refreshLocal() {
        localRefreshKey++
    }

    fun refreshFtp() {
        ftpRefreshKey++
    }

    fun refreshSftp() {
        sftpRefreshKey++
    }

    fun refreshRemote() {
        remoteRefreshKey++
    }

    suspend fun loadSaf() {
        safLocations = withContext(Dispatchers.IO) { LocationsRepository.safLocations() }
    }

    suspend fun loadLocal() {
        localLocations = withContext(Dispatchers.IO) {
            runCatching { LocationsRepository.localLocations() }.getOrDefault(emptyList())
        }
    }

    suspend fun loadFtp() {
        ftpLocations = withContext(Dispatchers.IO) { LocationsRepository.ftpServers() }
    }

    suspend fun loadSftp() {
        sftpLocations = withContext(Dispatchers.IO) { LocationsRepository.sftpServers() }
    }

    suspend fun loadRemote() {
        remoteLocations = withContext(Dispatchers.IO) {
            runCatching { LocationsRepository.remoteLocations() }.getOrDefault(emptyList())
        }
    }
}

@Composable
internal fun rememberLocationCollections(): LocationCollections {
    val state = remember { LocationCollections() }
    LaunchedEffect(state.safRefreshKey) { state.loadSaf() }
    LaunchedEffect(state.localRefreshKey) { state.loadLocal() }
    LaunchedEffect(state.ftpRefreshKey) { state.loadFtp() }
    LaunchedEffect(state.sftpRefreshKey) { state.loadSftp() }
    LaunchedEffect(state.remoteRefreshKey) { state.loadRemote() }
    return state
}
