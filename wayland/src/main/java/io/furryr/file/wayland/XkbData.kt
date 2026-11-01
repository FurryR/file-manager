package io.furryr.file.wayland

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Copies the bundled xkeyboard-config tree into app storage.
 *
 * libxkbcommon (statically linked into `libwayland_compositor.so`) reads keymap
 * definitions from `XKB_CONFIG_ROOT`, which must be a real filesystem path — the
 * APK asset namespace cannot be used directly. Assets under `assets/xkb` are
 * installed by `scripts/fetch-xkbdata.sh`.
 */
internal object XkbData {
    private const val TAG = "XkbData"
    private const val ASSET_DIR = "xkb"
    private const val MARKER = ".extracted"

    /** Extract once; returns the directory to hand to the compositor. */
    fun ensure(context: Context): File {
        val target = WaylandManager.xkbDir(context)
        val marker = File(target, MARKER)
        if (marker.exists()) return target

        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        val assets = context.assets
        val pending = ArrayDeque(listOf(ASSET_DIR))
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) continue
            for (child in children) {
                val childPath = "$path/$child"
                val grandChildren = assets.list(childPath).orEmpty()
                if (grandChildren.isNotEmpty()) {
                    pending.addLast(childPath)
                } else {
                    val out = File(target, childPath.removePrefix("$ASSET_DIR/"))
                    out.parentFile?.mkdirs()
                    runCatching {
                        assets.open(childPath).use { input -> out.outputStream().use(input::copyTo) }
                    }.onFailure { Log.w(TAG, "cannot extract $childPath", it) }
                }
            }
        }
        marker.createNewFile()
        return target
    }
}
