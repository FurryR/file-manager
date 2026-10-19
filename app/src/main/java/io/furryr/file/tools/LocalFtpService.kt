package io.furryr.file.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.furryr.file.FileRepository
import io.furryr.file.R
import io.furryr.file.core.source.OpenMode
import io.furryr.file.provider.installFileProviders
import io.furryr.file.util.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class FtpState(val running: Boolean = false, val root: String = "", val port: Int = 0,
    val username: String = "", val loopback: Boolean = false, val error: String? = null)

/** User-started foreground server; it never starts on app launch or device boot. */
class LocalFtpService : Service() {
    private var server: LocalFtpServer? = null
    companion object {
        private val mutableState = MutableStateFlow(FtpState())
        @Volatile private var sessionPassword: String? = null
        val state = mutableState.asStateFlow()
        fun runningPassword(): String? = sessionPassword.takeIf { state.value.running }
        fun start(context: Context, root: String, port: Int, user: String, password: String, loopback: Boolean) {
            context.startForegroundService(Intent(context, LocalFtpService::class.java)
                .putExtra("root", root).putExtra("port", port).putExtra("user", user)
                .putExtra("password", password).putExtra("loopback", loopback))
        }
        fun stop(context: Context) { context.stopService(Intent(context, LocalFtpService::class.java)) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); installFileProviders(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("local_ftp", getString(R.string.tools_ftp), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = PendingIntent.getService(this, 4201,
            Intent(this, LocalFtpService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(4201, NotificationCompat.Builder(this, "local_ftp")
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(getString(R.string.tools_ftp))
            .setContentText(intent?.getStringExtra("root").orEmpty()).setOngoing(true)
            .addAction(0, getString(R.string.tools_stop), stop).build())
        if (intent == null || intent.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (server != null) return START_NOT_STICKY
        val config = FtpState(true, intent.getStringExtra("root").orEmpty(), intent.getIntExtra("port", 2121),
            intent.getStringExtra("user").orEmpty(), intent.getBooleanExtra("loopback", false))
        val password = intent.getStringExtra("password").orEmpty()
        try {
            require(config.root.isNotBlank() && config.username.isNotBlank() && password.isNotBlank())
            require(config.port in 1024..65535)
            server = LocalFtpServer(cacheDir, config, password) { error ->
                mutableState.value = config.copy(running = false, error = error)
                stopSelf()
            }
            sessionPassword = password
            mutableState.value = config
            server!!.start()
        } catch (e: Exception) {
            mutableState.value = config.copy(running = false, error = e.message ?: "FTP failed")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        server?.close(); server = null
        sessionPassword = null
        mutableState.value = mutableState.value.copy(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}

/** Passive FTP with UTF-8, MLSD and restartable downloads. No shell commands. */
private class LocalFtpServer(private val cache: File, private val config: FtpState,
    password: String, private val failed: (String) -> Unit) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closing = AtomicBoolean(false)
    private val passwordHash = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val dataSockets = ConcurrentHashMap.newKeySet<Closeable>()
    private val bind = InetAddress.getByName(if (config.loopback) "127.0.0.1" else "0.0.0.0")
    private val listener = ServerSocket(config.port, 16, bind)
    private enum class TransferPhase { WAITING, TRANSFERRING, COMMITTING, FINISHED }
    private class ActiveTransfer(val listener: ServerSocket) {
        var phase = TransferPhase.WAITING
        var cancelled = false
        var data: Socket? = null
        var job: Job? = null
    }
    private class CommandFailure(val code: Int, message: String) : IOException(message)
    fun start() {
        scope.launch {
            try {
                require(FileRepository.stat(config.root).getOrThrow().isDirectory) { "Shared root is not a directory" }
                while (!closing.get()) {
                    val socket = listener.accept()
                    if (clients.size >= 16) {
                        try { socket.getOutputStream().write("421 Too many clients\r\n".toByteArray()) }
                        catch (error: IOException) { android.util.Log.d("LocalFtp", "Rejected client disconnected", error) }
                        finally { closeSocket(socket) }
                        continue
                    }
                    clients.add(socket)
                    launch {
                        try { socket.use { handle(it) } }
                        catch (error: Exception) { android.util.Log.d("LocalFtp", "Client socket ended", error) }
                    }.invokeOnCompletion {
                        clients.remove(socket)
                        closeSocket(socket)
                    }
                }
            } catch (e: Exception) { if (!closing.get()) failed(e.message ?: "FTP listener failed") }
        }
    }
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        closeSocket(listener)
        clients.forEach { closeSocket(it) }; clients.clear()
        dataSockets.forEach { closeSocket(it) }; dataSockets.clear()
        passwordHash.fill(0); scope.cancel()
    }
    private fun closeSocket(socket: Closeable) {
        dataSockets.remove(socket)
        try { socket.close() }
        catch (error: IOException) { android.util.Log.d("LocalFtp", "Socket cleanup", error) }
    }
    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 1_000
        // RFC 959 clients may prefix ABOR with Telnet IP/DM (urgent data).
        socket.oobInline = true
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        val replyLock = Any()
        val transferLock = Any()
        var activeTransfer: ActiveTransfer? = null
        var controlClosed = false
        fun reply(code: Int, message: String) {
            synchronized(replyLock) {
                output.write("$code ${message.replace('\r', ' ').replace('\n', ' ')}\r\n"); output.flush()
            }
        }
        var authenticated = false; var suppliedUser = ""; var cwd = emptyList<String>()
        var passive: ServerSocket? = null; var restart = 0L; var renameFrom: String? = null
        fun passiveClose() { passive?.let(::closeSocket); passive = null }
        fun relative(raw: String): List<String> {
            val parts = if (raw.startsWith('/')) mutableListOf() else cwd.toMutableList()
            for (part in raw.split('/')) when (part) {
                "", "." -> Unit
                ".." -> { require(parts.isNotEmpty()) { "Path is outside the shared root" }; parts.removeAt(parts.lastIndex) }
                else -> { require('\u0000' !in part && '\r' !in part && '\n' !in part); parts.add(part) }
            }
            return parts
        }
        fun resolve(parts: List<String>, allowMissing: Boolean = false): String {
            var uri = Path.parse(config.root)
            parts.forEachIndexed { index, part ->
                val entry = FileRepository.list(uri.toString()).getOrThrow().find { it.name == part }
                if (entry == null) require(allowMissing && index == parts.lastIndex) { "Path does not exist" }
                else require(!entry.isSymlink) { "Symbolic links are outside the FTP share" }
                uri = uri.child(part)
            }
            return uri.toString()
        }
        fun checkTransfer(transfer: ActiveTransfer) {
            synchronized(transferLock) {
                if (transfer.cancelled || controlClosed || closing.get()) throw CancellationException("FTP transfer aborted")
            }
        }
        fun beginPublication(transfer: ActiveTransfer) {
            synchronized(transferLock) {
                checkTransfer(transfer)
                transfer.phase = TransferPhase.COMMITTING
            }
        }
        fun transfer(operation: suspend (ActiveTransfer, Socket) -> Unit) {
            val waiting = passive ?: throw CommandFailure(425, "Use EPSV or PASV first")
            val transfer = ActiveTransfer(waiting)
            synchronized(transferLock) {
                check(activeTransfer == null) { "A transfer is already active" }
                activeTransfer = transfer
                passive = null // This listener now belongs only to this transfer.
                restart = 0L
                reply(150, "Opening data connection")
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var failure: Exception? = null
                try {
                    checkTransfer(transfer)
                    waiting.soTimeout = 30_000
                    val data = waiting.accept()
                    synchronized(transferLock) {
                        if (transfer.cancelled || controlClosed || closing.get()) {
                            closeSocket(data)
                            throw CancellationException("FTP transfer aborted")
                        }
                        transfer.data = data
                        dataSockets.add(data)
                        transfer.phase = TransferPhase.TRANSFERRING
                    }
                    closeSocket(waiting)
                    require(data.inetAddress == socket.inetAddress) { "Data peer does not match the control connection" }
                    data.soTimeout = 60_000
                    operation(transfer, data)
                    // EOF caused by an ABOR/QUIT socket close is never success.
                    if (synchronized(transferLock) { transfer.phase != TransferPhase.COMMITTING }) checkTransfer(transfer)
                } catch (error: Exception) { failure = error }
                finally {
                    closeSocket(waiting)
                    transfer.data?.let(::closeSocket)
                    synchronized(transferLock) {
                        transfer.phase = TransferPhase.FINISHED
                        if (activeTransfer === transfer) {
                            activeTransfer = null
                            if (!controlClosed && !closing.get()) {
                                try {
                                    if (failure == null) reply(226, "Transfer complete")
                                    else reply(426, failure!!.message ?: "Transfer failed")
                                } catch (error: IOException) {
                                    android.util.Log.d("LocalFtp", "Transfer reply could not be delivered", error)
                                    controlClosed = true
                                    closeSocket(socket)
                                }
                            }
                        }
                    }
                }
            }
            synchronized(transferLock) { transfer.job = job }
            job.start()
        }
        fun abortTransfer(replyToClient: Boolean): Boolean {
            synchronized(transferLock) {
                val transfer = activeTransfer
                if (transfer?.phase == TransferPhase.COMMITTING) {
                    if (replyToClient) reply(450, "Upload publication has begun; it can no longer be aborted")
                    return false
                }
                if (transfer == null) {
                    passiveClose()
                    if (replyToClient) reply(225, "No transfer in progress")
                    return true
                }
                transfer.cancelled = true
                // Detach before closing sockets: the worker must not emit a
                // second terminal reply when close wakes accept/read/write.
                activeTransfer = null
                closeSocket(transfer.listener)
                transfer.data?.let(::closeSocket)
                transfer.job?.cancel()
                if (replyToClient) {
                    synchronized(replyLock) {
                        reply(426, "Transfer aborted")
                        reply(226, "Abort completed")
                    }
                }
                return true
            }
        }
        try {
            reply(220, "File Manager FTP ready")
            while (!closing.get()) {
                val line = readLine(input) { synchronized(transferLock) { activeTransfer != null } } ?: break
                val command = line.substringBefore(' ').uppercase(Locale.ROOT)
                val argument = line.substringAfter(' ', "")
                try {
                    if (synchronized(transferLock) { activeTransfer != null } && command !in setOf("ABOR", "QUIT", "NOOP")) {
                        reply(450, "Transfer in progress; use ABOR to cancel it")
                        continue
                    }
                    when (command) {
                        "QUIT" -> {
                            synchronized(transferLock) {
                                controlClosed = true
                                val stopped = abortTransfer(false)
                                reply(221, if (stopped) "Goodbye" else "Goodbye; upload publication is already completing")
                            }
                            break
                        }
                        "USER" -> { suppliedUser = argument; authenticated = false; reply(331, "Password required") }
                        "PASS" -> {
                            val candidate = MessageDigest.getInstance("SHA-256").digest(argument.toByteArray())
                            authenticated = suppliedUser == config.username && MessageDigest.isEqual(candidate, passwordHash)
                            candidate.fill(0); reply(if (authenticated) 230 else 530, if (authenticated) "Logged in" else "Authentication failed")
                        }
                        "FEAT" -> synchronized(replyLock) { output.write("211-Features\r\n UTF8\r\n EPSV\r\n PASV\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n MLST type*;size*;modify*;\r\n211 End\r\n"); output.flush() }
                        "SYST" -> reply(215, "UNIX Type: L8")
                        "NOOP" -> reply(200, "OK")
                        "OPTS" -> reply(if (argument.uppercase().startsWith("UTF8")) 200 else 501, "UTF-8 is enabled")
                        else -> {
                            if (!authenticated) { reply(530, "Log in first"); continue }
                            when (command) {
                                "TYPE" -> reply(if (argument in listOf("A", "I", "L 8")) 200 else 504, "Transfer type")
                                "PWD", "XPWD" -> reply(257, "\"/${cwd.joinToString("/").replace("\"", "\"\"")}\"")
                                "CWD", "XCWD", "CDUP" -> {
                                    val next = relative(if (command == "CDUP") ".." else argument)
                                    require(FileRepository.stat(resolve(next)).getOrThrow().isDirectory) { "Not a directory" }
                                    cwd = next; reply(250, "Directory changed")
                                }
                                "PASV", "EPSV" -> {
                                    passiveClose(); passive = ServerSocket(0, 1, bind).also { dataSockets.add(it) }
                                    val port = passive!!.localPort
                                    if (command == "EPSV") reply(229, "Entering Extended Passive Mode (|||$port|)")
                                    else {
                                        val address = socket.localAddress.address
                                        require(address.size == 4) { "Use EPSV for IPv6" }
                                        reply(227, "Entering Passive Mode (${address.joinToString(",") { (it.toInt() and 255).toString() }},${port / 256},${port % 256})")
                                    }
                                }
                                "LIST", "NLST", "MLSD" -> {
                                    val parts = if (argument.isEmpty() || argument.startsWith('-')) cwd.toList() else relative(argument)
                                    transfer { task, data ->
                                        val entries = FileRepository.list(resolve(parts)).getOrThrow()
                                        checkTransfer(task)
                                        data.getOutputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                                            entries.filter { !it.name.contains('\r') && !it.name.contains('\n') }.forEach { entry ->
                                                checkTransfer(task)
                                                val row = when (command) {
                                                    "NLST" -> entry.name
                                                    "MLSD" -> "type=${if (entry.isDirectory) "dir" else "file"};size=${entry.size};modify=${timestamp(entry.modifiedAt)}; ${entry.name}"
                                                    else -> "${if (entry.isDirectory) 'd' else '-'}rw-r--r-- 1 owner group ${entry.size} Jan 01 00:00 ${entry.name}"
                                                }
                                                writer.write(row + "\r\n")
                                            }
                                        }
                                    }
                                }
                                "SIZE", "MDTM" -> {
                                    val parts = relative(argument); require(parts.isNotEmpty())
                                    val parent = resolve(parts.dropLast(1))
                                    val entry = FileRepository.list(parent).getOrThrow().firstOrNull { it.name == parts.last() }
                                        ?: throw FileNotFoundException(argument)
                                    require(!entry.isDirectory && !entry.isSymlink)
                                    reply(213, if (command == "SIZE") entry.size.toString() else timestamp(entry.modifiedAt))
                                }
                                "REST" -> { restart = argument.toLong().also { require(it >= 0) }; reply(350, "Restart position accepted") }
                                "RETR" -> {
                                    val parts = relative(argument)
                                    val restartAt = restart
                                    transfer { task, data ->
                                        val resolved = FileRepository.resolve(resolve(parts)).getOrThrow()
                                        checkTransfer(task)
                                        resolved.provider.open(resolved.innerPath, OpenMode.READ).getOrThrow().use { source ->
                                            require(restartAt <= source.size || source.size < 0) { "Restart beyond end of file" }
                                            if (!source.seekable) {
                                                var skipped = 0L
                                                while (skipped < restartAt) {
                                                    checkTransfer(task)
                                                    val bytes = source.readAt(skipped, minOf(256 * 1024L, restartAt - skipped).toInt())
                                                    require(bytes.isNotEmpty()) { "Restart beyond end of file" }
                                                    skipped += bytes.size
                                                }
                                            }
                                            BufferedOutputStream(data.getOutputStream()).use { stream ->
                                                var offset = restartAt
                                                while (true) {
                                                    checkTransfer(task)
                                                    val bytes = source.readChunk(offset, 256 * 1024)
                                                    if (bytes.isEmpty()) break
                                                    stream.write(bytes)
                                                    offset += bytes.size
                                                }
                                            }
                                        }
                                    }
                                }
                                "STOR" -> {
                                    require(restart == 0L) { "Resumed uploads are not supported" }
                                    val parts = relative(argument)
                                    require(parts.isNotEmpty()) { "Cannot upload to the shared root" }
                                    transfer { task, data ->
                                        val uri = resolve(parts, true)
                                        checkTransfer(task)
                                        val temp = File.createTempFile("ftp-upload-", ".bin", cache)
                                        try {
                                            temp.outputStream().use { destination ->
                                                val incoming = data.getInputStream()
                                                val bytes = ByteArray(256 * 1024)
                                                while (true) {
                                                    checkTransfer(task)
                                                    val count = incoming.read(bytes)
                                                    if (count < 0) break
                                                    destination.write(bytes, 0, count)
                                                }
                                            }
                                            checkTransfer(task)
                                            temp.inputStream().use { staged ->
                                                FileRepository.writeStream(uri, staged, temp.length(),
                                                    beforePublish = { beginPublication(task) }).getOrThrow()
                                            }
                                        } finally {
                                            if (!temp.delete() && temp.exists()) android.util.Log.w("LocalFtp", "Could not remove upload staging file")
                                        }
                                    }
                                }
                                "MKD", "XMKD" -> {
                                    val parts = relative(argument); require(parts.isNotEmpty())
                                    FileRepository.createDirectory(resolve(parts.dropLast(1)), parts.last()).getOrThrow(); reply(257, "Directory created")
                                }
                                "DELE", "RMD", "XRMD" -> {
                                    val parts = relative(argument); require(parts.isNotEmpty()) { "Cannot delete the shared root" }
                                    val uri = resolve(parts)
                                    val isDirectory = FileRepository.stat(uri).getOrThrow().isDirectory
                                    require(isDirectory == (command != "DELE")) { "Wrong entry type" }
                                    if (isDirectory) require(FileRepository.list(uri).getOrThrow().isEmpty()) { "Directory is not empty" }
                                    FileRepository.delete(uri).getOrThrow(); reply(250, "Deleted")
                                }
                                "RNFR" -> { val parts = relative(argument); require(parts.isNotEmpty()); renameFrom = resolve(parts); reply(350, "Send RNTO") }
                                "RNTO" -> {
                                    val from = renameFrom ?: error("RNFR is required"); renameFrom = null
                                    val parts = relative(argument); require(parts.isNotEmpty())
                                    val parent = resolve(parts.dropLast(1))
                                    require(Path.parse(from).parent() == Path.parse(parent)) { "Cross-directory rename is not supported" }
                                    val target = Path.parse(parent).child(parts.last()).toString()
                                    require(!FileRepository.stat(target).getOrThrow().exists) { "Destination exists" }
                                    FileRepository.rename(from, parts.last()).getOrThrow(); reply(250, "Renamed")
                                }
                                "ABOR" -> abortTransfer(true)
                                else -> reply(502, "Command not implemented")
                            }
                        }
                    }
                } catch (e: Exception) { reply((e as? CommandFailure)?.code ?: 550, e.message ?: "Operation failed") }
            }
        } catch (e: Exception) {
            android.util.Log.d("LocalFtp", "Client disconnected: ${e.javaClass.simpleName}")
        } finally {
            synchronized(transferLock) { controlClosed = true }
            abortTransfer(false)
            passiveClose()
        }
    }
    private fun timestamp(value: Long) = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(value))
    private fun readLine(input: InputStream, transferActive: () -> Boolean): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var lastActivity = System.nanoTime()
        fun readByte(): Int {
            while (true) {
                try {
                    val byte = input.read()
                    lastActivity = System.nanoTime()
                    return byte
                }
                catch (timeout: SocketTimeoutException) {
                    // A long transfer is activity even when its control stream
                    // is idle. Preserve partially read commands across this wait.
                    val now = System.nanoTime()
                    if (closing.get()) throw timeout
                    if (transferActive()) lastActivity = now
                    else if (now - lastActivity >= 120_000_000_000L) throw timeout
                }
            }
        }
        while (true) {
            val b = readByte(); if (b < 0) return null
            if (b == 255) {
                val command = readByte()
                if (command < 0) return null
                if (command in 251..254 && readByte() < 0) return null
                if (command == 244 || command == 242) bytes.reset()
                continue
            }
            if (b == 10) break
            require(bytes.size() < 8192) { "FTP command too long" }; if (b != 13) bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
