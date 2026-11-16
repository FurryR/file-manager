package io.furryr.file

import io.furryr.file.core.FileProvider
import io.furryr.file.core.FileWorld
import io.furryr.file.core.Vfs
import io.furryr.file.provider.LocalProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * One independent file-manager world.
 *
 * It owns the route table, its structural views and its open-with table. The
 * file manager and a picker each build one; they share process services (the
 * daemon, the location stores, SSH connection caches) but never registrations.
 */
class AppWorld(
    val id: String,
    val files: FileWorld,
    val opener: Opener,
    val localProvider: LocalProvider,
    val providers: List<FileProvider>,
    val pickerSelection: io.furryr.file.picker.PickerSelection? = null,
) {
    val vfs: Vfs get() = files.vfs
    val nodeOps get() = files.nodeOps
}

/**
 * Process-wide index of live worlds, addressable by id.
 *
 * It is the only global registry left on purpose: forwarded `content://` URIs
 * name the world that issued them, so a URI handed to another app outlives the
 * activity that picked it. Worlds themselves are independent objects.
 */
object FileWorlds {
    private val worlds = ConcurrentHashMap<String, AppWorld>()

    @Volatile
    var defaultId: String = "app"
        private set

    /**
     * Builds a world that is not live in this process (for example a persisted
     * document URI after process death). Installed by [AppRuntime].
     */
    @Volatile
    var factory: ((String) -> AppWorld?)? = null

    @Synchronized
    fun install(world: AppWorld, default: Boolean = false) {
        worlds[world.id] = world
        if (default) defaultId = world.id
    }

    fun find(id: String): AppWorld? {
        worlds[id]?.let { return it }
        val built = factory?.invoke(id) ?: return worlds[defaultId]
        return synchronized(this) { worlds.getOrPut(id) { built } }
    }

    fun app(): AppWorld? = find(defaultId)
}
