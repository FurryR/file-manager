package io.furryr.file.daemon

import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Lifts the non-SDK-interface restriction so the app can call
 * `android.system.Os.recvmsg`/`sendmsg` and `android.system.UnixSocketAddress`
 * reflectively.
 *
 * The app targets SDK 28, where the hidden-API blocklist is enforced. There is
 * no degraded mode: if the bypass fails we let it throw.
 */
object HiddenApi {
    private const val TAG = "HiddenApi"

    @Volatile private var applied = false

    @Synchronized
    fun ensure() {
        if (applied) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            applied = true
            return
        }
        val ok = HiddenApiBypass.addHiddenApiExemptions("Landroid/system/")
        check(ok) { "hidden-API exemption could not be applied" }
        Log.i(TAG, "hidden-API exemption applied (SDK_INT=${Build.VERSION.SDK_INT})")
        applied = true
    }
}
