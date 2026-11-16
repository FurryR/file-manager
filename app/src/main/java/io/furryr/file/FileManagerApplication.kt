package io.furryr.file

import android.app.Application
import io.furryr.file.crash.CrashReporter
import io.furryr.file.picker.PickerTokens
import java.io.File

/**
 * Installs the crash reporter before the first activity exists, so failures
 * during startup still produce a report instead of a silent process death.
 *
 * The picker's document-id key is persisted here: a granted URI must keep
 * working after the picker activity and after a process restart.
 */
class FileManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { PickerTokens.install(File(filesDir, "picker-token.bin")) }
        CrashReporter.install(this)
    }
}
