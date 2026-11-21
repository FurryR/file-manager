package io.furryr.file.adblock

/**
 * JNI bridge to `libadblock.so` (adblock-rust).
 *
 * The engine is process-wide and lives in the native library; this class only
 * moves rule text, cache bytes and request facts across the boundary. Library
 * load failures are not fatal: the browser simply keeps loading everything.
 */
internal object AdBlockNative {
    private val available: Boolean = runCatching {
        System.loadLibrary("adblock")
        true
    }.getOrDefault(false)

    /** Parse [rules] and install the result; returns the serialized cache. */
    fun buildEngine(rules: String): ByteArray? =
        if (available) runCatching { nativeBuildEngine(rules) }.getOrNull() else null

    /** Install a cache produced by [buildEngine]. */
    fun loadEngine(serialized: ByteArray): Boolean =
        if (available) runCatching { nativeLoadEngine(serialized) }.getOrDefault(false) else false

    fun clear() {
        if (available) runCatching { nativeClear() }
    }

    fun isLoaded(): Boolean =
        if (available) runCatching { nativeIsLoaded() }.getOrDefault(false) else false

    fun check(url: String, sourceUrl: String, requestType: String, method: String): Boolean =
        if (available) {
            runCatching { nativeCheck(url, sourceUrl, requestType, method) }.getOrDefault(false)
        } else {
            false
        }

    @JvmStatic
    private external fun nativeBuildEngine(rules: String): ByteArray?

    @JvmStatic
    private external fun nativeLoadEngine(serialized: ByteArray): Boolean

    @JvmStatic
    private external fun nativeClear()

    @JvmStatic
    private external fun nativeIsLoaded(): Boolean

    @JvmStatic
    private external fun nativeCheck(
        url: String,
        sourceUrl: String,
        requestType: String,
        method: String,
    ): Boolean
}
