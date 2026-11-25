package io.furryr.file.ui.util

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Site icons are process-cached; a failed fetch keeps the caller's fallback. */
private object FaviconCache {
    private const val MAX_BYTES = 256 * 1024
    private val cache = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, ImageBitmap?>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ImageBitmap?>?,
            ): Boolean = size > 16
        },
    )

    fun load(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        val image = runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "image/*")
                val bytes = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val chunk = ByteArray(8192)
                    while (bytes.size() < MAX_BYTES) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        bytes.write(chunk, 0, count)
                    }
                }
                val data = bytes.toByteArray()
                BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        cache[url] = image
        return image
    }
}

@Composable
internal fun rememberFavicon(url: String?): ImageBitmap? {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) { FaviconCache.load(url) }
    }
    return image
}
