package io.furryr.file.render

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.source.OpenMode
import java.io.ByteArrayOutputStream

internal data class ApkManifestInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val label: String?,
    val icon: ImageBitmap?,
)

/**
 * Reads APK facts through the registered archive view.
 *
 * Only `AndroidManifest.xml`, `resources.arsc` and the best icon entry are
 * read. The archive proxy owns the zip index, so a seekable remote APK is
 * never copied whole.
 */
internal object ApkManifest {
    private const val XML_TYPE = 0x0003
    private const val STRING_POOL_TYPE = 0x0001
    private const val START_ELEMENT_TYPE = 0x0102
    private const val TABLE_TYPE = 0x0002
    private const val PACKAGE_TYPE = 0x0200
    private const val TYPE_TYPE = 0x0201
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val FLAG_COMPLEX = 0x0001
    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02

    suspend fun read(folder: VFolder): ApkManifestInfo {
        val raw = parseManifest(readEntry(folder, "AndroidManifest.xml")
            ?: error("The APK has no AndroidManifest.xml"))
        val table = runCatching {
            parseResourceTable(readEntry(folder, "resources.arsc")
                ?: error("The APK has no resources.arsc"))
        }.getOrNull()
        val label = raw.label ?: raw.labelRes?.let { table?.resolveString(it) }
        val icon = raw.iconRes?.let { table?.resolveIcon(it, folder) }
        return ApkManifestInfo(
            packageName = raw.packageName,
            versionName = raw.versionName,
            versionCode = raw.versionCode,
            minSdk = raw.minSdk,
            targetSdk = if (raw.targetSdk > 0) raw.targetSdk else raw.minSdk,
            label = label,
            icon = icon,
        )
    }

    private data class RawManifest(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val label: String?,
        val labelRes: Int?,
        val iconRes: Int?,
    )

    private data class ResourceValue(val type: Int, val data: Int, val density: Int)
    private data class ResourceTable(
        val strings: StringPool,
        val values: Map<Int, List<ResourceValue>>,
    )

    private fun parseManifest(bytes: ByteArray): RawManifest {
        require(bytes.size >= 8 && u16(bytes, 0) == XML_TYPE) { "Not an Android binary manifest" }
        var offset = u16(bytes, 2)
        var pool: StringPool? = null
        var packageName = ""
        var versionName = ""
        var versionCode = 0L
        var minSdk = 0
        var targetSdk = 0
        var label: String? = null
        var labelRes: Int? = null
        var iconRes: Int? = null
        while (offset + 8 <= bytes.size) {
            val type = u16(bytes, offset)
            val headerSize = u16(bytes, offset + 2)
            val size = u32(bytes, offset + 4)
            require(size >= 8 && headerSize >= 8 && offset + size <= bytes.size) {
                "Malformed binary manifest chunk"
            }
            if (type == STRING_POOL_TYPE) {
                pool = StringPool(bytes, offset, headerSize)
            } else if (type == START_ELEMENT_TYPE) {
                val strings = pool ?: error("Manifest string pool is missing")
                val ext = offset + headerSize
                val name = strings.get(u32(bytes, ext + 4).toInt()) ?: ""
                val attrStart = u16(bytes, ext + 8)
                val attrSize = u16(bytes, ext + 10)
                val attrCount = u16(bytes, ext + 12)
                for (index in 0 until attrCount) {
                    val attr = ext + attrStart + index * attrSize
                    val attrName = strings.get(u32(bytes, attr + 4).toInt()) ?: continue
                    val rawIndex = u32(bytes, attr + 8)
                    val dataType = bytes[attr + 15].toInt() and 0xFF
                    val data = u32(bytes, attr + 16)
                    val raw = if (rawIndex != 0xFFFFFFFFL) strings.get(rawIndex.toInt()) else null
                    val text = if (dataType == TYPE_STRING) strings.get(data.toInt()) else raw
                    when (name) {
                        "manifest" -> when (attrName) {
                            "package" -> packageName = text.orEmpty()
                            "versionCode" -> versionCode = intValue(dataType, data, text).toLong()
                            "versionName" -> versionName = text.orEmpty()
                        }
                        "uses-sdk" -> when (attrName) {
                            "minSdkVersion" -> minSdk = intValue(dataType, data, text)
                            "targetSdkVersion" -> targetSdk = intValue(dataType, data, text)
                        }
                        "application" -> when (attrName) {
                            "label" -> if (dataType == TYPE_REFERENCE) {
                                labelRes = data.toInt()
                            } else {
                                label = text
                            }
                            "icon" -> if (dataType == TYPE_REFERENCE) {
                                iconRes = data.toInt()
                            }
                            // A round icon is often an adaptive-only XML that
                            // bitmap decoding cannot read; use it only when the
                            // manifest has no plain icon.
                            "roundIcon" -> if (dataType == TYPE_REFERENCE && iconRes == null) {
                                iconRes = data.toInt()
                            }
                        }
                    }
                }
            }
            offset += size.toInt()
        }
        require(packageName.isNotBlank()) { "The APK manifest has no package name" }
        return RawManifest(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            label = label,
            labelRes = labelRes,
            iconRes = iconRes,
        )
    }

    private fun parseResourceTable(bytes: ByteArray): ResourceTable {
        require(bytes.size >= 12 && u16(bytes, 0) == TABLE_TYPE) { "Not an Android resource table" }
        var offset = u16(bytes, 2)
        var strings: StringPool? = null
        val values = HashMap<Int, MutableList<ResourceValue>>()
        while (offset + 8 <= bytes.size) {
            val type = u16(bytes, offset)
            val headerSize = u16(bytes, offset + 2)
            val size = u32(bytes, offset + 4)
            require(size >= 8 && headerSize >= 8 && offset + size <= bytes.size) {
                "Malformed resource table chunk"
            }
            when (type) {
                STRING_POOL_TYPE -> strings = StringPool(bytes, offset, headerSize)
                PACKAGE_TYPE -> {
                    val packageId = u32(bytes, offset + 8).toInt()
                    val end = offset + size.toInt()
                    var child = offset + headerSize
                    while (child + 8 <= end) {
                        val childType = u16(bytes, child)
                        val childSize = u32(bytes, child + 4)
                        if (childSize < 8 || child + childSize > end) break
                        if (childType == TYPE_TYPE) {
                            parseType(bytes, child, packageId, values)
                        }
                        child += childSize.toInt()
                    }
                }
            }
            offset += size.toInt()
        }
        return ResourceTable(strings ?: error("Resource table string pool is missing"), values)
    }

    private fun parseType(
        bytes: ByteArray,
        offset: Int,
        packageId: Int,
        values: MutableMap<Int, MutableList<ResourceValue>>,
    ) {
        val headerSize = u16(bytes, offset + 2)
        val typeId = bytes[offset + 8].toInt() and 0xFF
        val flags = bytes[offset + 9].toInt() and 0xFF
        val entryCount = u32(bytes, offset + 12).toInt()
        val entriesStart = u32(bytes, offset + 16).toInt()
        val density = if (headerSize >= 36) u16(bytes, offset + 20 + 14) else 0
        val offsets = offset + headerSize
        var index = 0
        while (index < entryCount) {
            val entryIndex: Int
            val entryOffset: Int
            when {
                flags and FLAG_SPARSE != 0 -> {
                    val pair = offsets + index * 4
                    entryIndex = u16(bytes, pair)
                    val raw = u16(bytes, pair + 2)
                    entryOffset = if (raw == 0xFFFF) -1 else raw * 4
                }
                flags and FLAG_OFFSET16 != 0 -> {
                    val raw = u16(bytes, offsets + index * 2)
                    entryIndex = index
                    entryOffset = if (raw == 0xFFFF) -1 else raw * 4
                }
                else -> {
                    val raw = u32(bytes, offsets + index * 4)
                    entryIndex = index
                    entryOffset = if (raw == 0xFFFFFFFFL) -1 else raw.toInt()
                }
            }
            if (entryOffset >= 0) {
                val entry = offset + entriesStart + entryOffset
                if (entry + 8 <= bytes.size) {
                    val entrySize = u16(bytes, entry)
                    val entryFlags = u16(bytes, entry + 2)
                    if (entryFlags and FLAG_COMPLEX == 0 && entrySize >= 8) {
                        val value = entry + entrySize
                        if (value + 8 <= bytes.size) {
                            val dataType = bytes[value + 3].toInt() and 0xFF
                            val data = u32(bytes, value + 4).toInt()
                            val id = (packageId shl 24) or (typeId shl 16) or entryIndex
                            values.getOrPut(id) { mutableListOf() } +=
                                ResourceValue(dataType, data, density)
                        }
                    }
                }
            }
            index++
        }
    }

    private fun ResourceTable.resolve(id: Int, seen: MutableSet<Int> = mutableSetOf()): List<ResourceValue> {
        if (!seen.add(id)) return emptyList()
        return values[id].orEmpty().flatMap { value ->
            if (value.type == TYPE_REFERENCE) resolve(value.data, seen) else listOf(value)
        }
    }

    private fun ResourceTable.resolveString(id: Int): String? =
        resolve(id).firstNotNullOfOrNull { value ->
            if (value.type == TYPE_STRING) strings.get(value.data) else null
        }

    private suspend fun ResourceTable.resolveIcon(id: Int, folder: VFolder): ImageBitmap? {
        val candidates = resolve(id)
            .filter { it.type == TYPE_STRING }
            .sortedByDescending { densityRank(it.density) }
        for (value in candidates) {
            val path = strings.get(value.data) ?: continue
            val bytes = readEntry(folder, path) ?: continue
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            return bitmap.asImageBitmap()
        }
        return null
    }

    private fun densityRank(density: Int): Int = when (density) {
        0 -> 160
        0xFFFE -> 100_000
        0xFFFF -> -1
        else -> density
    }

    private suspend fun readEntry(folder: VFolder, path: String): ByteArray? {
        var current = folder
        val segments = path.trim('/').split('/')
        for ((index, segment) in segments.withIndex()) {
            if (index == segments.lastIndex) {
                val file = current.child(segment).getOrNull() as? VFile ?: return null
                val source = file.open(OpenMode.READ).getOrNull() ?: return null
                return source.use { readAll(it) }
            }
            current = current.child(segment).getOrNull() as? VFolder ?: return null
        }
        return null
    }

    private fun readAll(source: io.furryr.file.core.source.FileSource): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val chunk = source.readChunk(offset, 64 * 1024)
            if (chunk.isEmpty()) break
            output.write(chunk)
            offset += chunk.size
        }
        return output.toByteArray()
    }

    private fun intValue(dataType: Int, data: Long, raw: String?): Int = when (dataType) {
        TYPE_INT_DEC, TYPE_INT_HEX -> data.toInt()
        else -> raw?.toIntOrNull() ?: 0
    }

    private class StringPool(
        private val bytes: ByteArray,
        private val offset: Int,
        headerSize: Int,
    ) {
        private val count = u32(bytes, offset + 8).toInt()
        private val flags = u32(bytes, offset + 16).toInt()
        private val stringsStart = u32(bytes, offset + 20).toInt()
        private val offsets = offset + headerSize

        fun get(index: Int): String? {
            if (index < 0 || index >= count) return null
            val start = offset + stringsStart + u32(bytes, offsets + index * 4).toInt()
            return if (flags and 0x100 != 0) utf8(start) else utf16(start)
        }

        private fun utf8(start: Int): String {
            var position = start
            var length = bytes[position].toInt() and 0xFF
            if (length and 0x80 != 0) {
                length = ((length and 0x7F) shl 8) or (bytes[position + 1].toInt() and 0xFF)
                position += 2
            } else {
                position++
            }
            var byteLength = bytes[position].toInt() and 0xFF
            if (byteLength and 0x80 != 0) {
                byteLength = ((byteLength and 0x7F) shl 8) or (bytes[position + 1].toInt() and 0xFF)
                position += 2
            } else {
                position++
            }
            require(byteLength >= 0 && position + byteLength <= bytes.size) { "Malformed manifest string" }
            return String(bytes, position, byteLength, Charsets.UTF_8)
        }

        private fun utf16(start: Int): String {
            var position = start
            var length = u16(bytes, position)
            if (length and 0x8000 != 0) {
                length = ((length and 0x7FFF) shl 16) or u16(bytes, position + 2)
                position += 4
            } else {
                position += 2
            }
            require(length >= 0 && position + length * 2 <= bytes.size) { "Malformed manifest string" }
            val chars = CharArray(length)
            for (index in 0 until length) chars[index] = u16(bytes, position + index * 2).toChar()
            return String(chars)
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= bytes.size) { "Truncated resource data" }
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= bytes.size) { "Truncated resource data" }
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
