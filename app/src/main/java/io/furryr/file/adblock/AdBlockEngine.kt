package io.furryr.file.adblock

import android.content.Context
import java.io.File

/**
 * Process-wide ad-blocking engine facade for the browser.
 *
 * A WebView checks requests on Chromium threads, so the first use loads the
 * serialized cache in the background instead of blocking a request. Updates
 * are built by the settings screen on `Dispatchers.IO`; request checks keep
 * using the previous engine until the new one is installed.
 */
object AdBlockEngine {
    @Volatile
    private var ready = false

    @Volatile
    private var loading = false

    @Volatile
    private var attempted = false

    private val loadLock = Any()

    /** Start loading the cached engine when the app process starts. */
    fun install(context: Context) {
        if (!AdBlockSettings.isEnabled(context)) return
        ensureLoaded(context.applicationContext, force = true)
    }

    /** True once a compiled rule set is installed in the native library. */
    fun isReady(): Boolean = ready

    /**
     * Load the cache file, or compile the current lists when no usable cache
     * exists. Runs on a daemon thread; at most one load is in flight.
     */
    fun ensureLoaded(context: Context, force: Boolean = false) {
        if (ready) return
        if (!force && attempted) return
        synchronized(loadLock) {
            if (ready || loading) return
            if (!force && attempted) return
            attempted = true
            loading = true
        }
        val app = context.applicationContext
        Thread({
            try {
                reloadNow(app)
            } finally {
                loading = false
            }
        }, "adblock-load").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Load the cache or rebuild immediately. Call from `Dispatchers.IO`; the
     * settings screen uses it after edits and imports.
     */
    fun reloadNow(context: Context): Result<Unit> {
        val app = context.applicationContext
        val cache = AdBlockSettings.engineCache(app)
        if (cache.isFile) {
            val loaded = runCatching { AdBlockNative.loadEngine(cache.readBytes()) }
                .getOrDefault(false)
            if (loaded) {
                ready = true
                return Result.success(Unit)
            }
        }
        return rebuildNow(app)
    }

    /** Compile every enabled list and replace the engine. */
    @Synchronized
    fun rebuildNow(context: Context): Result<Unit> = runCatching {
        val rules = AdBlockSettings.combinedRules(context)
        if (rules.isBlank()) {
            AdBlockNative.clear()
            error("No filter rules")
        }
        val serialized = AdBlockNative.buildEngine(rules) ?: error("Could not build the engine")
        val cache = AdBlockSettings.engineCache(context)
        cache.parentFile?.mkdirs()
        val temp = File(cache.parentFile, "${cache.name}.tmp")
        temp.writeBytes(serialized)
        if (!temp.renameTo(cache)) {
            cache.writeBytes(serialized)
            temp.delete()
        }
        ready = true
    }.onFailure {
        // A failed rebuild keeps the previous engine when the native side
        // still holds one, so a broken update does not silently disable
        // blocking. An empty configuration cleared it above.
        ready = AdBlockNative.isLoaded()
    }

    /** Rebuild in the background, then report the result on the worker thread. */
    fun rebuildAsync(context: Context, onDone: (Result<Unit>) -> Unit = {}) {
        val app = context.applicationContext
        Thread({
            onDone(rebuildNow(app))
        }, "adblock-rebuild").apply {
            isDaemon = true
            start()
        }
    }

    /** Drop the native engine (the user turned blocking off). */
    fun clear() {
        AdBlockNative.clear()
        ready = false
        attempted = false
    }

    /** Forget the "load once" guard after the list configuration changed. */
    fun resetAttempt() {
        attempted = false
    }

    /**
     * True when the request must be blocked. A missing engine is an allow; the
     * first request starts the background load and later requests use it.
     */
    fun shouldBlock(
        context: Context,
        url: String,
        sourceUrl: String,
        requestType: String,
        method: String,
    ): Boolean {
        if (!AdBlockSettings.isEnabled(context)) return false
        if (!ready) {
            ensureLoaded(context)
            return false
        }
        return AdBlockNative.check(url, sourceUrl, requestType, method)
    }
}
