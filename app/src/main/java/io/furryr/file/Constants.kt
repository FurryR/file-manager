package io.furryr.file

import android.os.Environment

/**
 * Home directory shown on first launch and used when a container's last tab
 * is closed. Resolved at runtime so it follows the primary shared storage of
 * the current user (`/storage/emulated/<userId>` on most devices) instead of
 * hardcoding user 0.
 */
val DefaultPath: String
    get() = Environment.getExternalStorageDirectory().absolutePath

const val ProviderAuthority = "io.furryr.file.provider"

/** Authority of [io.furryr.file.forward.ForwardFileProvider]. */
const val ForwardProviderAuthority = "io.furryr.file.forward"
