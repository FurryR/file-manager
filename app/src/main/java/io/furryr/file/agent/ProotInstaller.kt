package io.furryr.file.agent

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Locates the proot binary in nativeLibraryDir (extracted by Android when
 * extractNativeLibs=true) and ensures it is executable.
 *
 * The binary path is obtained via [expectedPath]; call [install] to make it
 * executable before first use.
 */
object ProotInstaller {
    private const val SO_NAME = "libproot.so"
    private const val TAG = "ProotInstaller"

    fun install(context: Context): Result<File> = runCatching {
        val appContext = context.applicationContext
        val binary = File(appContext.applicationInfo.nativeLibraryDir, SO_NAME)
        if (!binary.exists()) {
            throw IOException("proot binary not found at ${binary.absolutePath}")
        }
        if (!binary.canExecute()) {
            if (!binary.setExecutable(true, true)) {
                android.util.Log.w(TAG, "setExecutable returned false for ${binary.absolutePath}")
            }
        }
        android.util.Log.d(TAG, "proot ready at ${binary.absolutePath}")
        binary
    }

    fun expectedPath(context: Context): File =
        File(context.applicationContext.applicationInfo.nativeLibraryDir, SO_NAME)
}
