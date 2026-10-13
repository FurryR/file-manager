package io.furryr.file.uds

import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Lifts the non-SDK-interface restriction so this module can reach
 * `android.system.Os.recvmsg`/`sendmsg` and `android.system.UnixSocketAddress`.
 *
 * No degraded mode: if the bypass fails we let it throw.
 */
internal object HiddenApi {
    private const val TAG = "UdsHiddenApi"

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
