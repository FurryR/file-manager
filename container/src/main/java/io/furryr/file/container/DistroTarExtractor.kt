package io.furryr.file.container

import android.util.Log
import java.io.FileOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

internal object DistroTarExtractor {
    private const val TAG = "DistroTarExtractor"

    fun extractTarGzInto(tarball: File, destDir: File) {
        val destPath = destDir.canonicalPath
        val dataBuffer = ByteArray(8192)

        tarball.inputStream().use { raw ->
            GZIPInputStream(raw).use { gzip ->
                var header = ByteArray(512)

                while (true) {
                    // Read 512-byte header
                    var offset = 0
                    while (offset < 512) {
                        val bytesRead = gzip.read(header, offset, 512 - offset)
                        if (bytesRead == -1) {
                            if (offset == 0) break
                            throw IOException("Truncated tar header")
                        }
                        offset += bytesRead
                    }

                    // End marker: two zero blocks
                    if (offset == 512 && isAllZeros(header)) {
                        offset = 0
                        val secondBlock = ByteArray(512)
                        while (offset < 512) {
                            val bytesRead = gzip.read(secondBlock, offset, 512 - offset)
                            if (bytesRead == -1 && offset == 0) break
                            if (bytesRead == -1) break
                            offset += bytesRead
                        }
                        break
                    }
                    if (offset < 512) break

                    val name = parseTarString(header, 0, 100)
                    val prefix = parseTarString(header, 345, 155)
                    val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

                    val sanitised = fullName.trimStart('/')
                        .split('/')
                        .filter { it != ".." && it != "." && it.isNotEmpty() }
                        .joinToString("/")
                    if (sanitised.isEmpty()) {
                        skipTarData(gzip, header)
                        continue
                    }

                    val size = parseTarOctal(header, 124, 12)
                    val typeFlag = header[156].toInt().toChar()

                    val mode = parseTarOctal(header, 100, 8)

                    val entryFile = File(destDir, sanitised)
                    val entryCanonical = entryFile.canonicalPath

                    // Path-traversal guard
                    if (!entryCanonical.startsWith(destPath + File.separator) && entryCanonical != destPath) {
                        skipTarData(gzip, header)
                        continue
                    }

                    when (typeFlag) {
                        '0', '\u0000' -> {
                            entryFile.parentFile?.mkdirs()
                            FileOutputStream(entryFile).use { out ->
                                var remaining = size
                                while (remaining > 0) {
                                    val toRead = minOf(remaining, dataBuffer.size.toLong()).toInt()
                                    val read = gzip.read(dataBuffer, 0, toRead)
                                    if (read == -1) throw IOException("Unexpected EOF in tar data")
                                    out.write(dataBuffer, 0, read)
                                    remaining -= read
                                }
                            }
                            applyMode(entryFile, mode)
                            val padding = (512 - (size % 512)) % 512
                            if (padding > 0) skipBytes(gzip, padding)
                        }

                        '5' -> { entryFile.mkdirs(); applyMode(entryFile, mode) }

                        '2' -> {
                            entryFile.parentFile?.mkdirs()
                            val linkTarget = parseTarString(header, 157, 100)
                            entryFile.delete()
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    entryFile.toPath(),
                                    java.nio.file.Paths.get(linkTarget),
                                )
                            } catch (_: Exception) {
                                entryFile.createNewFile()
                                applyMode(entryFile, mode)
                            }
                        }

                        '1' -> {
                            val linkTarget = parseTarString(header, 157, 100)
                            val targetFile = File(destDir, linkTarget.trimStart('/'))
                            val targetCanonical = targetFile.canonicalPath

                            if (!targetCanonical.startsWith(destPath + File.separator) && targetCanonical != destPath) {
                                skipTarData(gzip, header)
                                continue
                            }

                            entryFile.parentFile?.mkdirs()
                            entryFile.delete()

                            try {
                                java.nio.file.Files.createLink(
                                    entryFile.toPath(),
                                    targetFile.toPath()
                                )
                            } catch (_: Exception) {
                                targetFile.copyTo(entryFile, overwrite = true)
                                applyMode(entryFile, mode)
                            }
                        }

                        else -> skipTarData(gzip, header)
                    }
                }
            }
        }
    }

    /**
     * Docker images often wrap the rootfs in a single top-level directory
     * (e.g. `alpine-minirootfs/...`). If [destDir] contains exactly one
     * subdirectory and no regular files, move its contents up and delete it.
     */
    fun maybeFlattenRootfs(destDir: File) {
        val children = destDir.listFiles() ?: return
        val dirs = children.filter { it.isDirectory && !it.name.startsWith(".") }
        val files = children.filter { it.isFile && !it.name.startsWith(".") }
        if (dirs.size == 1 && files.isEmpty()) {
            val wrapper = dirs[0]
            Log.i(TAG, "Flattening top-level directory: ${wrapper.name}")
            wrapper.listFiles()?.forEach { child ->
                child.renameTo(File(destDir, child.name))
            }
            wrapper.delete()
        }
    }

    // ── Permission restoration ─────────────────────────────────────────

    /**
     * Apply Unix permission bits from a tar header mode field.
     * Falls back to sensible defaults when mode is 0.
     */
    private fun applyMode(file: File, mode: Long) {
        if (mode == 0L) return
        val perms = mutableSetOf<PosixFilePermission>()
        if (mode and 0x100 != 0L) perms.add(PosixFilePermission.OWNER_READ)
        if (mode and 0x080 != 0L) perms.add(PosixFilePermission.OWNER_WRITE)
        if (mode and 0x040 != 0L) perms.add(PosixFilePermission.OWNER_EXECUTE)
        if (mode and 0x020 != 0L) perms.add(PosixFilePermission.GROUP_READ)
        if (mode and 0x010 != 0L) perms.add(PosixFilePermission.GROUP_WRITE)
        if (mode and 0x008 != 0L) perms.add(PosixFilePermission.GROUP_EXECUTE)
        if (mode and 0x004 != 0L) perms.add(PosixFilePermission.OTHERS_READ)
        if (mode and 0x002 != 0L) perms.add(PosixFilePermission.OTHERS_WRITE)
        if (mode and 0x001 != 0L) perms.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(file.toPath(), perms)
    }

    // ── tar helpers ─────────────────────────────────────────────────

    private fun skipBytes(input: InputStream, n: Long) {
        val buffer = ByteArray(512)
        var remaining = n
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            remaining -= read
        }
    }

    private fun skipTarData(input: InputStream, header: ByteArray) {
        val size = parseTarOctal(header, 124, 12)
        val blocks = (size + 511) / 512
        val toSkip = blocks * 512
        var skipped = 0L
        while (skipped < toSkip) {
            val n = input.skip(toSkip - skipped)
            if (n <= 0) break
            skipped += n
        }
    }

    private fun isAllZeros(bytes: ByteArray): Boolean {
        for (b in bytes) {
            if (b != 0.toByte()) return false
        }
        return true
    }

    private fun parseTarString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseTarOctal(header: ByteArray, offset: Int, length: Int): Long {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        val str = String(header, offset, end - offset, Charsets.UTF_8)
        if (str.isEmpty()) return 0L
        return str.toLong(8)
    }

    // ── HTTP helpers ────────────────────────────────────────────────

}
