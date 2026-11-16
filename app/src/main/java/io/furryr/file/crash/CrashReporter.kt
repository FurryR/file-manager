package io.furryr.file.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Last-resort handler for uncaught exceptions. It writes one self-contained
 * text report to `filesDir/crash/`, remembers it as pending, and (in the main
 * process only) opens [CrashActivity] before the process dies.
 *
 * The pending marker is what makes the screen reliable: if Android refuses the
 * activity start from a background crash, the next [MainActivity] launch finds
 * the marker and opens the report instead of the file manager. [CrashActivity]
 * clears the marker once it has the report.
 *
 * Reports are ordinary UTF-8 text so the export path in [CrashActivity] does
 * not need any format-specific code.
 */
object CrashReporter {

    /** Directory (under filesDir/cacheDir) that holds crash reports. */
    const val ReportDirName = "crash"

    private const val Tag = "FileManager"
    private const val PrefsName = "crash"
    private const val KeyPending = "pending_report"
    private const val KeyLastLaunch = "last_launch"
    private const val MaxReports = 10

    /**
     * A crash screen that crashes again would otherwise loop forever. One
     * launch per few seconds is enough for the first report; later ones stay
     * pending and are shown by [MainActivity].
     */
    private const val LaunchDebounceMs = 3_000L

    private const val LogcatTailLines = 300
    private const val LogcatMaxChars = 64 * 1024

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            handle(app, thread, error, previous)
        }
    }

    /** The report that has not been shown yet, if any. */
    fun pendingReport(context: Context): File? {
        val prefs = prefs(context)
        val path = prefs.getString(KeyPending, null) ?: return null
        val file = File(path)
        if (!file.isFile) {
            prefs.edit().remove(KeyPending).commit()
            return null
        }
        return file
    }

    /** The crash screen now owns the report; do not show it on next launch. */
    fun markShown(context: Context, report: File) {
        val prefs = prefs(context)
        if (prefs.getString(KeyPending, null) == report.absolutePath) {
            prefs.edit().remove(KeyPending).commit()
        }
    }

    fun suggestedFileName(): String =
        "crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    private fun handle(
        context: Context,
        thread: Thread,
        error: Throwable,
        previous: Thread.UncaughtExceptionHandler?,
    ) {
        runCatching {
            Log.e(Tag, "Uncaught exception in thread ${thread.name}", error)
            val text = buildReport(context, thread, error)
            val report = writeReport(context, text)
            if (report != null) {
                prefs(context).edit()
                    .putString(KeyPending, report.absolutePath)
                    .commit()
            }
            val mainProcess = isMainProcess(context)
            if (mainProcess && mayLaunch(context)) {
                val intent = CrashActivity.intent(
                    context,
                    report,
                    if (report == null) text else null,
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
            }
            if (!mainProcess) {
                // Keep the platform's own behaviour (log, process teardown)
                // for helper processes such as :pulse.
                previous?.uncaughtException(thread, error)
            }
        }.onFailure {
            Log.e(Tag, "Crash reporting failed", it)
        }
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    private fun mayLaunch(context: Context): Boolean {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KeyLastLaunch, 0L)
        if (now - last < LaunchDebounceMs) return false
        prefs.edit().putLong(KeyLastLaunch, now).commit()
        return true
    }

    private fun writeReport(context: Context, text: String): File? = runCatching {
        val dir = File(context.filesDir, ReportDirName)
        dir.mkdirs()
        val file = File(
            dir,
            "crash-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt",
        )
        file.writeText(text, Charsets.UTF_8)
        prune(dir)
        file
    }.getOrNull()

    private fun prune(dir: File) {
        val reports = dir.listFiles { file ->
            file.isFile && file.name.startsWith("crash-") && file.name.endsWith(".txt")
        } ?: return
        reports.sortedByDescending { it.lastModified() }
            .drop(MaxReports)
            .forEach { it.delete() }
    }

    private fun buildReport(context: Context, thread: Thread, error: Throwable): String {
        val stackTrace = StringWriter().also { writer ->
            error.printStackTrace(PrintWriter(writer))
        }.toString().trimEnd()

        return buildString {
            appendLine("File crash report")
            appendLine("=================")
            appendLine()
            appendLine("Time       : ${formatTime()}")
            appendLine("Process    : ${currentProcessName(context)}")
            appendLine("Thread     : ${thread.name} (id=${thread.id})")
            appendLine("Application: ${appVersion(context)}")
            appendLine("Package    : ${context.packageName}")
            appendLine("Device     : ${deviceName()}")
            appendLine("Android    : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI        : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Locale     : ${Locale.getDefault().toLanguageTag()}")
            appendLine("Memory     : ${memorySummary()}")
            appendLine()
            appendLine("Exception  : ${error.toString()}")
            appendLine()
            appendLine("Stack trace")
            appendLine("-----------")
            appendLine(stackTrace)

            val logcat = readLogcat()
            if (logcat.isNotEmpty()) {
                appendLine()
                appendLine("Recent logcat (last $LogcatTailLines lines)")
                appendLine("---------------------------------------")
                appendLine(logcat)
            }
        }
    }

    private fun formatTime(): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        return format.format(Date())
    }

    /**
     * Since Android 4.1 an app may read its own log entries without
     * READ_LOGS. Some ROMs still reject the command; any failure simply
     * removes the section instead of breaking the report.
     *
     * Reading happens on a bounded helper thread: a hung `logcat` must not
     * keep the crashing process alive.
     */
    private fun readLogcat(): String {
        val process = runCatching {
            ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "-t", LogcatTailLines.toString(),
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return ""
        val output = StringBuffer()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (output.length < LogcatMaxChars) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.append(buffer, 0, count)
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        runCatching {
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroy()
            reader.join(300)
        }
        return output.toString()
            .lineSequence()
            .filterNot { it.startsWith("--------- beginning of") }
            .filterNot { it.startsWith("logcat: ") }
            .joinToString("\n")
            .trim()
            .take(LogcatMaxChars)
            .takeIf { it.isNotEmpty() && !it.contains("Unable to open log device") }
            .orEmpty()
    }

    private fun memorySummary(): String {
        val runtime = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val used = (runtime.totalMemory() - runtime.freeMemory()) / mb
        return "$used/${runtime.maxMemory() / mb} MB"
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
            ?: return context.packageName
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        val label = runCatching { pm.getApplicationLabel(context.applicationInfo).toString() }
            .getOrDefault(context.packageName)
        val debug = if (
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) " debug" else ""
        return "$label ${info.versionName ?: "?"} ($code)$debug"
    }

    private fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        val fallback = context.applicationInfo.processName ?: context.packageName
        return runCatching {
            val bytes = File("/proc/self/cmdline").readBytes()
            val end = bytes.indexOf(0)
            String(bytes, 0, if (end >= 0) end else bytes.size, Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: fallback
    }

    private fun isMainProcess(context: Context): Boolean {
        val main = context.applicationInfo.processName ?: context.packageName
        return currentProcessName(context) == main
    }
}
