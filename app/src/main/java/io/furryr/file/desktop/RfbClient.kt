package io.furryr.file.desktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** RFC 6143 RFB 3.8/3.3, using a fixed little-endian 32-bit true-colour format. */
internal class RfbClient(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val expectedName: String? = null,
    val frame: RfbFrame = RfbFrame(),
    private val onConnected: (String) -> Unit,
    private val onFrame: () -> Unit,
    private val onError: (Exception) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val messages = ArrayBlockingQueue<ByteArray>(512)
    @Volatile private var socket: Socket? = null
    @Volatile private var ready = false
    private var sender: Thread? = null

    fun start() {
        Thread({
            try {
                val connection = Socket()
                socket = connection
                if (closed.get()) { connection.close(); return@Thread }
                connection.connect(InetSocketAddress(host, port), 10_000)
                connection.tcpNoDelay = true
                connection.soTimeout = 15_000
                val input = DataInputStream(BufferedInputStream(connection.getInputStream(), 64 * 1024))
                val output = DataOutputStream(BufferedOutputStream(connection.getOutputStream()))
                val name = handshake(input, output, connection.inetAddress.isLoopbackAddress)
                connection.soTimeout = 0 // an idle desktop may send no updates
                sender = Thread({
                    try { while (!closed.get()) { output.write(messages.take()); output.flush() } }
                    catch (failure: Exception) { fail(failure) }
                }, "rfb-input").apply { isDaemon = true; start() }
                ready = true
                configure()
                onConnected(name)
                requestUpdate(false)
                while (!closed.get()) {
                    when (input.readUnsignedByte()) {
                        0 -> {
                            input.readUnsignedByte()
                            val count = input.readUnsignedShort()
                            var resized = false
                            for (index in 0 until count) {
                                val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                                val width = input.readUnsignedShort(); val height = input.readUnsignedShort()
                                when (val encoding = input.readInt()) {
                                    0 -> {
                                        frame.bounds(x, y, width, height)
                                        val bytes = ByteArray(width * 4)
                                        val pixels = IntArray(width)
                                        for (row in 0 until height) {
                                            input.readFully(bytes)
                                            for (column in 0 until width) {
                                                val offset = column * 4
                                                pixels[column] = (0xff000000L.toInt() or ((bytes[offset + 2].toInt() and 255) shl 16)
                                                    or ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset].toInt() and 255))
                                            }
                                            if (width > 0) frame.row(x, y + row, pixels)
                                        }
                                    }
                                    1 -> frame.copy(x, y, width, height, input.readUnsignedShort(), input.readUnsignedShort())
                                    -223 -> { frame.resize(width, height); resized = true }
                                    -224 -> break // LastRect
                                    else -> throw IOException("Unsupported RFB encoding: $encoding")
                                }
                            }
                            onFrame()
                            requestUpdate(!resized)
                        }
                        1 -> { input.readUnsignedByte(); input.readUnsignedShort(); skip(input, input.readUnsignedShort() * 6) }
                        2 -> Unit // Bell
                        3 -> { skip(input, 3); val length = input.readInt(); require(length in 0..1_048_576) { "Invalid RFB clipboard length" }; skip(input, length) }
                        else -> throw IOException("Unsupported RFB server message")
                    }
                }
            } catch (failure: Exception) { fail(failure) }
        }, "rfb-framebuffer").apply { isDaemon = true; start() }
    }
    private fun handshake(input: DataInputStream, output: DataOutputStream, loopback: Boolean): String {
        val banner = ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII)
        require(Regex("RFB 003\\.[0-9]{3}\\n").matches(banner)) { "Server is not a supported RFB server" }
        val version38 = banner == "RFB 003.008\n"
        output.write((if (version38) "RFB 003.008\n" else "RFB 003.003\n").toByteArray(Charsets.US_ASCII)); output.flush()
        val security = if (version38) {
            val count = input.readUnsignedByte()
            if (count == 0) throw IOException(readString(input, 4096))
            val offered = ByteArray(count).also(input::readFully).map { it.toInt() and 255 }
            val selected = when {
                password.isNotEmpty() && 2 in offered -> 2
                password.isNotEmpty() -> throw IOException("Server does not offer VNC password authentication")
                loopback && 1 in offered -> 1
                2 in offered -> throw IOException("This VNC server requires a password")
                else -> throw IOException("The server offers no supported secure connection; None authentication is restricted to loopback")
            }
            output.writeByte(selected); output.flush(); selected
        } else input.readInt()
        when (security) {
            0 -> throw IOException(readString(input, 4096))
            1 -> require(loopback && password.isEmpty()) { "Unauthenticated VNC is allowed only on loopback without a configured password" }
            2 -> {
                require(password.isNotEmpty() && password.all { it.code <= 255 }) { "VNC authentication needs an ISO-8859-1 password" }
                val challenge = ByteArray(16).also(input::readFully)
                val key = ByteArray(8)
                password.toByteArray(Charsets.ISO_8859_1).take(8).forEachIndexed { index, value ->
                    key[index] = (Integer.reverse(value.toInt() and 255) ushr 24).toByte()
                }
                val cipher = Cipher.getInstance("DES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
                output.write(cipher.doFinal(challenge)); output.flush(); key.fill(0)
            }
            else -> throw IOException("Unsupported RFB security type: $security")
        }
        if (version38 || security == 2) {
            if (input.readInt() != 0) throw IOException(if (version38) readString(input, 4096) else "VNC authentication failed")
        }
        output.writeByte(1); output.flush() // shared session
        val width = input.readUnsignedShort(); val height = input.readUnsignedShort()
        skip(input, 16)
        val name = readString(input, 65536)
        if (expectedName != null && name != expectedName) throw IOException("The port belongs to another desktop; choose a free port")
        frame.resize(width, height)
        return name
    }
    private fun configure() {
        send { out ->
            out.writeByte(0); out.write(byteArrayOf(0, 0, 0))
            out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
            repeat(3) { out.writeShort(255) }
            out.writeByte(16); out.writeByte(8); out.writeByte(0); out.write(byteArrayOf(0, 0, 0))
        }
        send { out -> out.writeByte(2); out.writeByte(0); out.writeShort(4); listOf(0, 1, -223, -224).forEach(out::writeInt) }
    }
    private fun requestUpdate(incremental: Boolean) { send { out ->
        out.writeByte(3); out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0); out.writeShort(0); out.writeShort(frame.width); out.writeShort(frame.height)
    } }
    fun pointer(x: Int, y: Int, mask: Int) { send { out ->
        out.writeByte(5); out.writeByte(mask and 255)
        out.writeShort(x.coerceIn(0, (frame.width - 1).coerceAtLeast(0)))
        out.writeShort(y.coerceIn(0, (frame.height - 1).coerceAtLeast(0)))
    } }
    fun key(keysym: Int, down: Boolean) { send { out -> out.writeByte(4); out.writeByte(if (down) 1 else 0); out.writeShort(0); out.writeInt(keysym) } }
    fun text(text: String) { send { out -> text.codePoints().toArray().forEach { cp ->
        val sym = when (cp) { 10, 13 -> 0xff0d; 9 -> 0xff09; else -> if (cp <= 255) cp else 0x01000000 or cp }
        for (down in listOf(true, false)) { out.writeByte(4); out.writeByte(if (down) 1 else 0); out.writeShort(0); out.writeInt(sym) }
    } } }
    private fun send(write: (DataOutputStream) -> Unit) {
        if (!ready || closed.get()) return
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        if (!messages.offer(bytes.toByteArray())) fail(IOException("VNC input queue is full; reconnect the session"))
    }
    private fun fail(failure: Exception) {
        if (closed.compareAndSet(false, true)) { release(); onError(failure) }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
    private fun release() {
        ready = false
        try { socket?.close() } catch (failure: IOException) { android.util.Log.d("RFB", "Socket close failed", failure) }
        sender?.interrupt(); messages.clear()
    }
    private fun readString(input: DataInputStream, maximum: Int): String {
        val length = input.readInt(); require(length in 0..maximum) { "Invalid RFB string length" }
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
    private fun skip(input: DataInputStream, length: Int) {
        var remaining = length
        val bytes = ByteArray(minOf(length, 8192))
        while (remaining > 0) { val count = minOf(remaining, bytes.size); input.readFully(bytes, 0, count); remaining -= count }
    }
}

/** One bounded framebuffer; rendering and decoder writes share the same monitor. */
internal class RfbFrame : Closeable {
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    private var bitmap: Bitmap? = null
    @Synchronized fun resize(width: Int, height: Int) {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "VNC framebuffer exceeds supported dimensions" }
        val replacement = try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            catch (failure: OutOfMemoryError) { throw IOException("Insufficient memory for the VNC framebuffer", failure) }
        bitmap?.recycle(); bitmap = replacement; this.width = width; this.height = height
    }
    fun bounds(x: Int, y: Int, width: Int, height: Int) {
        require(x >= 0 && y >= 0 && width >= 0 && height >= 0 && x.toLong() + width <= this.width && y.toLong() + height <= this.height) {
            "RFB rectangle is outside the framebuffer"
        }
    }
    @Synchronized fun row(x: Int, y: Int, pixels: IntArray) { bitmap?.setPixels(pixels, 0, pixels.size, x, y, pixels.size, 1) }
    @Synchronized fun copy(x: Int, y: Int, width: Int, height: Int, sourceX: Int, sourceY: Int) {
        bounds(x, y, width, height); bounds(sourceX, sourceY, width, height)
        val image = bitmap ?: return
        if (width == 0 || height == 0) return
        val row = IntArray(width)
        val rows = if (y > sourceY) (height - 1 downTo 0) else (0 until height)
        for (index in rows) { image.getPixels(row, 0, width, sourceX, sourceY + index, width, 1); image.setPixels(row, 0, width, x, y + index, width, 1) }
    }
    @Synchronized fun draw(canvas: Canvas, paint: Paint) { bitmap?.let { canvas.drawBitmap(it, 0f, 0f, paint) } }
    @Synchronized override fun close() { bitmap?.recycle(); bitmap = null; width = 0; height = 0 }
}
