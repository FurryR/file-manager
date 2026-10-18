package io.furryr.file.core

import java.io.File
import java.util.concurrent.TimeUnit

/** Best-effort detection of a `su` binary on the device. */
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

/** Run `su -c 'id -u'` to check whether [suPath] actually grants root. */
fun isSuAvailable(suPath: String): Boolean {
    if (suPath.isBlank()) return false
    return runCatching {
        val process = ProcessBuilder(suPath, "-c", "id -u").redirectErrorStream(true).start()
        try {
            process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                process.inputStream.bufferedReader().use { it.readText().trim() } == "0"
        } finally {
            process.destroyForcibly()
        }
    }.getOrDefault(false)
}

/** Single-quote [value] for safe interpolation into a `su -c` shell command. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
