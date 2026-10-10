package com.zlight.sendtosmb.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean = false,
    val isReparsePoint: Boolean = false,
)

data class SmbCapacity(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

/**
 * Blocking SMB2/3 client. Run network calls on Dispatchers.IO. Caller owns the supplied streams.
 * Paths are relative to the configured URL, including its optional subdirectory.
 * disconnect() closes raw sockets before SMB cleanup, so a blocked transfer cannot hold it hostage.
 */
class SmbRepository : Closeable {
    private class State(val address: SmbAddress) {
        val sockets = AbortableSocketFactory()
        val client = SMBClient(SmbConfig.builder()
            .withSocketFactory(sockets)
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withBufferSize(BUFFER_SIZE)
            .withDfsEnabled(false)
            .build())
        @Volatile var connection: Connection? = null
        @Volatile var share: DiskShare? = null
        fun abort() {
            sockets.abort()
            runCatching { connection?.close(true) }
        }
    }

    private val active = AtomicReference<State?>(null)
    val isConnected: Boolean get() = active.get()?.let { it.share?.isConnected == true && !it.sockets.aborted.get() } == true

    fun connect(address: SmbAddress, username: String, password: String, domain: String = "") {
        val state = State(address)
        active.getAndSet(state)?.abort()
        try {
            val connection = state.client.connect(address.host, address.port)
            state.connection = connection
            checkActive(state)
            val chars = password.toCharArray()
            val session = try {
                // A guest NTLM context carries a session key. SMBJ 0.14's anonymous
                // context can crash during SMB3 key derivation on guest-mapped servers (#872).
                connection.authenticate(if (username.isBlank() && password.isEmpty()) AuthenticationContext.guest()
                    else AuthenticationContext(username, chars, domain))
            } finally { chars.fill('\u0000') }
            checkActive(state)
            val connectedShare = session.connectShare(address.share)
            require(connectedShare is DiskShare) { "此共享不是文件磁盘共享" }
            state.share = connectedShare
            require(connectedShare.folderExists(address.basePath)) { "SMB 地址中的文件夹不存在" }
            checkActive(state)
        } catch (error: Throwable) {
            active.compareAndSet(state, null)
            state.abort()
            throw error
        }
    }

    fun disconnect() { active.getAndSet(null)?.abort() }
    override fun close() = disconnect()

    fun list(path: String = ""): List<SmbEntry> = list(state(), path)

    fun capacity(): SmbCapacity {
        val info = share(state()).shareInformation
        return SmbCapacity(info.totalSpace, info.callerFreeSpace)
    }

    fun mkdir(path: String) {
        val state = state()
        requireChild(path)
        share(state).mkdir(state.address.resolve(path))
    }

    fun rename(path: String, newName: String) {
        SmbAddress.validateName(newName)
        move(path, SmbAddress.childPath(SmbAddress.parentPath(path), newName))
    }

    /** Moves within this share using a server rename; existing destinations are never overwritten. */
    fun move(source: String, destination: String) {
        val state = state()
        validateDestination(source, destination)
        val disk = share(state)
        disk.open(state.address.resolve(source), EnumSet.of(AccessMask.DELETE), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use {
            it.rename(state.address.resolve(destination), false)
        }
    }

    fun delete(path: String, cancelled: () -> Boolean = { false }) {
        requireChild(path)
        delete(state(), path, cancelled, 0)
    }

    /** Copies a file or tree to an exact destination path. Refuses merges and overwrites. */
    fun copy(source: String, destination: String, cancelled: () -> Boolean = { false },
             onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val state = state()
        validateDestination(source, destination)
        val disk = share(state)
        val target = state.address.resolve(destination)
        require(!exists(disk, target)) { "目标已存在，请选择其他名称" }
        val total = treeSize(state, source, cancelled, 0)
        var completed = 0L
        onProgress(0, total)
        copyTree(state, source, destination, cancelled, 0) { count ->
            completed += count
            onProgress(completed, total)
        }
        onProgress(completed, total)
    }

    /** Uploads through a sibling temporary file, committing only after all bytes are flushed. */
    fun upload(path: String, input: InputStream, totalBytes: Long = -1,
               cancelled: () -> Boolean = { false }, onProgress: (Long, Long) -> Unit = { _, _ -> },
               overwrite: Boolean = false, beforeCommit: () -> Unit = {}) {
        upload(state(), path, input, totalBytes, cancelled, onProgress, overwrite, beforeCommit)
    }

    fun download(path: String, output: OutputStream, cancelled: () -> Boolean = { false },
                 onProgress: (Long, Long) -> Unit = { _, _ -> }, expectedBytes: Long = -1) {
        val state = state()
        requireChild(path)
        val disk = share(state)
        val resolved = state.address.resolve(path)
        val file = try {
            disk.openFile(resolved, EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        } catch (error: SMBApiException) {
            // Some shares grant file contents without FILE_READ_ATTRIBUTES. The directory entry
            // already supplied the size, so retry with the smallest permission needed to download.
            if (error.statusCode != STATUS_ACCESS_DENIED) throw error
            disk.openFile(resolved, EnumSet.of(AccessMask.FILE_READ_DATA),
                null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        }
        file.use {
            val total = runCatching { it.getFileInformation(FileStandardInformation::class.java).endOfFile }
                .getOrDefault(expectedBytes)
            val buffer = ByteArray(BUFFER_SIZE)
            var done = 0L
            onProgress(0, total)
            while (total < 0 || done < total) {
                checkActive(state, cancelled)
                val length = if (total >= 0) minOf(buffer.size.toLong(), total - done).toInt() else buffer.size
                val count = it.read(buffer, done, 0, length)
                if (count < 0) break
                if (count == 0) throw IOException("服务器未返回文件数据")
                output.write(buffer, 0, count)
                done += count
                onProgress(done, total)
            }
            checkActive(state, cancelled)
            if (total >= 0 && done != total) throw IOException("远程文件在下载期间发生变化，请重试")
            output.flush()
            onProgress(done, total)
        }
    }

    private fun upload(state: State, path: String, input: InputStream, totalBytes: Long,
                       cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit, overwrite: Boolean,
                       beforeCommit: () -> Unit = {}) {
        requireChild(path)
        checkActive(state, cancelled)
        val disk = share(state)
        val target = state.address.resolve(path)
        if (!overwrite) require(!exists(disk, target)) { "目标已存在，请选择其他名称" }
        val temporary = state.address.resolve(SmbAddress.childPath(SmbAddress.parentPath(path), ".sendtosmb-${UUID.randomUUID()}.part"))
        var committed = false
        var created = false
        try {
            disk.openFile(temporary, EnumSet.of(AccessMask.FILE_WRITE_DATA, AccessMask.DELETE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, null).use { file ->
                created = true
                val buffer = ByteArray(BUFFER_SIZE)
                var done = 0L
                onProgress(0, totalBytes)
                while (true) {
                    checkActive(state, cancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    checkActive(state, cancelled)
                    val written = file.write(buffer, done, 0, count)
                    if (written != count.toLong()) throw IOException("文件未完整写入服务器")
                    done += written
                    onProgress(done, totalBytes)
                }
                checkActive(state, cancelled)
                if (totalBytes >= 0 && done != totalBytes) throw IOException("源文件大小发生变化，请重新上传")
                file.flush()
                checkActive(state, cancelled)
                beforeCommit()
                checkActive(state, cancelled)
                file.rename(target, overwrite)
                committed = true
                onProgress(done, if (totalBytes >= 0) totalBytes else done)
            }
        } finally {
            if (created && !committed) runCatching { disk.rm(temporary) }
        }
    }

    private fun list(state: State, path: String): List<SmbEntry> {
        checkActive(state)
        return share(state).list(state.address.resolve(path)).filter { it.fileName != "." && it.fileName != ".." }.map { file ->
            SmbEntry(file.fileName, SmbAddress.childPath(path, file.fileName),
                file.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                file.endOfFile, file.lastWriteTime.toEpochMillis(),
                file.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L || file.fileName.startsWith('.'),
                file.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L)
        }
    }

    private fun delete(state: State, path: String, cancelled: () -> Boolean, depth: Int) {
        checkDepth(depth)
        checkActive(state, cancelled)
        val disk = share(state)
        val resolved = state.address.resolve(path)
        val info = disk.getFileInformation(resolved)
        if (info.standardInformation.isDirectory) {
            require(info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value == 0L) {
                "为避免误删链接目标，请在服务器管理此链接文件夹"
            }
            list(state, path).forEach { delete(state, it.path, cancelled, depth + 1) }
            checkActive(state, cancelled)
            disk.rmdir(resolved, false)
        } else disk.rm(resolved)
    }

    private fun treeSize(state: State, path: String, cancelled: () -> Boolean, depth: Int): Long {
        checkDepth(depth)
        checkActive(state, cancelled)
        val info = share(state).getFileInformation(state.address.resolve(path))
        require(info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value == 0L) { "暂不复制符号链接" }
        return if (info.standardInformation.isDirectory) list(state, path).sumOf { treeSize(state, it.path, cancelled, depth + 1) }
        else info.standardInformation.endOfFile
    }

    private fun copyTree(state: State, source: String, destination: String, cancelled: () -> Boolean,
                         depth: Int, progress: (Long) -> Unit) {
        checkDepth(depth)
        checkActive(state, cancelled)
        val disk = share(state)
        val info = disk.getFileInformation(state.address.resolve(source))
        require(info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value == 0L) { "暂不复制符号链接" }
        if (info.standardInformation.isDirectory) {
            disk.mkdir(state.address.resolve(destination))
            list(state, source).forEach { copyTree(state, it.path, SmbAddress.childPath(destination, it.name), cancelled, depth + 1, progress) }
        } else {
            disk.openFile(state.address.resolve(source), EnumSet.of(AccessMask.FILE_READ_DATA), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { file ->
                file.inputStream.use { input ->
                    var previous = 0L
                    upload(state, destination, input, info.standardInformation.endOfFile, cancelled, { done, _ ->
                        progress(done - previous)
                        previous = done
                    }, false)
                }
            }
        }
    }

    private fun state(): State = active.get()?.also { checkActive(it) } ?: throw IOException("尚未连接 SMB 共享")
    private fun exists(disk: DiskShare, path: String): Boolean = try {
        disk.open(path, EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null).use { true }
    } catch (error: SMBApiException) {
        // Servers may use NO_SUCH_FILE, NAME_NOT_FOUND, or PATH_NOT_FOUND for a missing entry.
        if (error.statusCode in setOf(0xC000000FL, 0xC0000034L, 0xC000003AL)) false else throw error
    }
    private fun share(state: State): DiskShare = state.share ?: throw IOException("SMB 正在连接，请稍候")
    private fun checkActive(state: State, cancelled: () -> Boolean = { false }) {
        if (active.get() !== state || state.sockets.aborted.get() || Thread.currentThread().isInterrupted || cancelled())
            throw CancellationException("传输已取消或连接已断开")
    }
    private fun requireChild(path: String) {
        require(SmbAddress.normalizeRelativePath(path).isNotEmpty()) { "不能修改共享根目录" }
    }
    private fun validateDestination(source: String, destination: String) {
        requireChild(source)
        requireChild(destination)
        val from = SmbAddress.normalizeRelativePath(source)
        val to = SmbAddress.normalizeRelativePath(destination)
        require(!from.equals(to, ignoreCase = true)) { "目标与源文件相同" }
        require(!to.startsWith("$from\\", ignoreCase = true)) { "不能将文件夹复制或移动到其自身内部" }
    }
    private fun checkDepth(depth: Int) { require(depth <= 128) { "目录嵌套超过 128 层，请分批操作" } }

    private class AbortableSocketFactory : SocketFactory() {
        val aborted = AtomicBoolean(false)
        private val sockets = CopyOnWriteArraySet<Socket>()
        fun abort() {
            aborted.set(true)
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
        override fun createSocket(): Socket {
            if (aborted.get()) throw IOException("连接已取消")
            val socket = Socket()
            sockets.add(socket)
            if (aborted.get()) { socket.close(); throw IOException("连接已取消") }
            return socket
        }
        private fun open(host: InetSocketAddress, local: InetSocketAddress? = null): Socket {
            val socket = createSocket()
            try {
                if (local != null) socket.bind(local)
                socket.connect(host, 10_000)
                return socket
            } catch (error: Throwable) { sockets.remove(socket); runCatching { socket.close() }; throw error }
        }
        override fun createSocket(host: String, port: Int): Socket = open(InetSocketAddress(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            open(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket = open(InetSocketAddress(host, port))
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
            open(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val STATUS_ACCESS_DENIED = 0xC0000022L
    }
}
