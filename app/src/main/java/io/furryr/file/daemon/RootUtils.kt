package io.furryr.file.daemon
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*

import java.io.File
import java.util.concurrent.TimeUnit

fun detectSuPath(): String {
    val candidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su"
    )
    return candidates.firstOrNull { File(it).exists() } ?: "su"
}

fun isSuAvailable(suPath: String): Boolean {
    if (suPath.isBlank()) return false
    return runCatching {
        val process = ProcessBuilder(suPath, "-c", "id").redirectErrorStream(true).start()
        process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
