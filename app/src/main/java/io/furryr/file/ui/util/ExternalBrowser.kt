package io.furryr.file.ui.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import io.furryr.file.R

/**
 * Open a web address outside this app.
 *
 * The app can be the default browser, so a plain [Intent.ACTION_VIEW] would
 * come right back to our own WebView. Prefer the system's default browser when
 * it is external; otherwise pick an installed browser and leave our activity
 * out of the resolution. Only when no external browser exists does the normal
 * in-app VIEW handler take over.
 */
fun openExternalBrowser(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val manager = context.packageManager
    val default = manager.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)
    if (default != null && default.activityInfo.packageName != context.packageName) {
        context.startActivity(view)
        return
    }
    val external = manager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
        .firstOrNull { it.activityInfo.packageName != context.packageName }
    if (external != null) {
        val explicit = Intent(view).setClassName(
            external.activityInfo.packageName,
            external.activityInfo.name,
        )
        runCatching { context.startActivity(explicit) }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.toast_no_app, url),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return
    }
    runCatching { context.startActivity(view) }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.toast_no_app, url),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
