package com.zlight.sendtosmb.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.EnumSet
import java.util.UUID
import java.security.MessageDigest
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

    /** Locks the source against other SMB writers while recording a complete content identity. */
    fun resumeFingerprint(path: String, cancelled: () -> Boolean = { false }): ResumeRemoteFile {
        val state = state()
        requireChild(path)
        return openResumeRead(state, path).use { file ->
            val before = resumeStat(state, path, file)
            val hash = hashRemote(state, file, before.first, cancelled)
            if (before != resumeStat(state, path, file)) throw ResumeConflictException("远程源文件已变化，请重新创建下载任务")
            ResumeRemoteFile(before.first, before.second, hash)
        }
    }

    /**
     * Retains a stable sibling partial across cancellation, socket failures and process death.
     * A retry re-reads and verifies every existing prefix byte before continuing. The final name
     * appears only after the original source hash, remote bytes and SMB flush have all succeeded.
     */
    fun uploadResumable(path: String, transferId: String, input: () -> InputStream,
                        expectedBytes: Long, expectedSha256: String,
                        cancelled: () -> Boolean = { false },
                        onProgress: (Long, Long) -> Unit = { _, _ -> }, beforeCommit: () -> Unit = {}) {
        requireChild(path)
        require(expectedBytes >= 0) { "断点续传需要确定的源文件大小" }
        val expectedHash = validateResumeHash(expectedSha256)
        val temporaryPath = resumeTemporaryPath(path, transferId)
        val state = state()
        checkActive(state, cancelled)
        val target = state.address.resolve(path)
        if (resumeExists(state, path)) {
            // Rename may have succeeded just before a client lost its connection or process.
            // Never overwrite or remove an existing destination, including an unrelated file.
            if (resumeFingerprint(path, cancelled).let { it.size == expectedBytes && it.sha256 == expectedHash }) {
                onProgress(expectedBytes, expectedBytes)
                return
            }
            throw ResumeConflictException("目标文件已存在且内容不同，请另选名称")
        }
        openResumeFile(state, temporaryPath,
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_WRITE_DATA, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.DELETE),
            SMB2CreateDisposition.FILE_OPEN_IF).use { file ->
            val partialBytes = resumeStat(state, temporaryPath, file).first
            if (partialBytes > expectedBytes) throw ResumeConflictException("服务器上的断点文件长度异常，已保留现场")
            val localBuffer = ByteArray(BUFFER_SIZE)
            val remoteBuffer = ByteArray(BUFFER_SIZE)
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                input().use { source ->
                    var done = 0L
                    while (done < partialBytes) {
                        checkActive(state, cancelled)
                        val count = minOf(BUFFER_SIZE.toLong(), partialBytes - done).toInt()
                        readSourceExactly(state, source, localBuffer, count, cancelled)
                        readRemoteExactly(state, file, remoteBuffer, done, count, cancelled)
                        if (!localBuffer.regionMatches(remoteBuffer, count))
                            throw ResumeConflictException("源文件与服务器断点内容不一致，已保留断点文件")
                        digest.update(localBuffer, 0, count)
                        done += count
                    }
                    onProgress(done, expectedBytes)
                    var synced = done
                    while (done < expectedBytes) {
                        checkActive(state, cancelled)
                        val count = minOf(BUFFER_SIZE.toLong(), expectedBytes - done).toInt()
                        readSourceExactly(state, source, localBuffer, count, cancelled)
                        digest.update(localBuffer, 0, count)
                        val written = file.write(localBuffer, done, 0, count)
                        if (written != count.toLong()) throw IOException("文件未完整写入服务器，断点已保留")
                        done += written
                        if (done - synced >= 1024 * 1024) { file.flush(); synced = done }
                        onProgress(done, expectedBytes)
                    }
                    checkActive(state, cancelled)
                    if (source.read() != -1 || digest.digest().hex() != expectedHash)
                        throw ResumeConflictException("源文件内容已变化，未提交目标文件；原断点已保留")
                    file.flush()
                    checkActive(state, cancelled)
                    if (resumeStat(state, temporaryPath, file).first != expectedBytes || hashRemote(state, file, expectedBytes, cancelled) != expectedHash)
                        throw ResumeConflictException("服务器断点文件校验失败，未提交目标文件")
                    beforeCommit()
                    checkActive(state, cancelled)
                    file.rename(target, false)
                }
            } finally { runCatching { file.flush() } }
        }
    }

    /** Explicit task discard only; never deletes the final destination or scans other partials. */
    fun discardUploadPartial(path: String, transferId: String) {
        requireChild(path)
        val temporary = resumeTemporaryPath(path, transferId)
        val state = state()
        if (resumeExists(state, temporary)) {
            openResumeFile(state, temporary, EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.DELETE),
                SMB2CreateDisposition.FILE_OPEN).use {
                resumeStat(state, temporary, it)
                it.deleteOnClose()
            }
        }
    }

    /**
     * Downloads to an app-private partial. The local prefix is compared with the locked remote
     * source before appending; a changed source or prefix never silently restarts/truncates it.
     * Local bytes are fsynced periodically and even when cancellation interrupts a checkpoint.
     */
    fun downloadResumable(path: String, localPart: File, expected: ResumeRemoteFile,
                          cancelled: () -> Boolean = { false },
                          onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        requireChild(path)
        require(expected.size >= 0) { "下载源大小无效" }
        val expectedHash = validateResumeHash(expected.sha256)
        require(!localPart.exists() || localPart.isFile) { "本地断点不是普通文件" }
        val state = state()
        openResumeRead(state, path).use { file ->
            val before = resumeStat(state, path, file)
            if (before.first != expected.size || before.second != expected.lastModified)
                throw ResumeConflictException("远程源文件已变化，原下载断点已保留")
            RandomAccessFile(localPart, "rw").use { local ->
                try {
                    val partialBytes = local.length()
                    if (partialBytes > expected.size) throw ResumeConflictException("本地断点长度异常，已保留现场")
                    val localBuffer = ByteArray(BUFFER_SIZE)
                    val remoteBuffer = ByteArray(BUFFER_SIZE)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    while (done < partialBytes) {
                        checkActive(state, cancelled)
                        val count = minOf(BUFFER_SIZE.toLong(), partialBytes - done).toInt()
                        local.readFully(localBuffer, 0, count)
                        readRemoteExactly(state, file, remoteBuffer, done, count, cancelled)
                        if (!localBuffer.regionMatches(remoteBuffer, count))
                            throw ResumeConflictException("本地断点内容与远程文件不一致，未覆盖本地断点")
                        digest.update(remoteBuffer, 0, count)
                        done += count
                    }
                    onProgress(done, expected.size)
                    var synced = done
                    while (done < expected.size) {
                        checkActive(state, cancelled)
                        val count = minOf(BUFFER_SIZE.toLong(), expected.size - done).toInt()
                        readRemoteExactly(state, file, remoteBuffer, done, count, cancelled)
                        digest.update(remoteBuffer, 0, count)
                        local.write(remoteBuffer, 0, count)
                        done += count
                        if (done - synced >= 1024 * 1024) { local.fd.sync(); synced = done }
                        onProgress(done, expected.size)
                    }
                    checkActive(state, cancelled)
                    local.fd.sync()
                    if (before != resumeStat(state, path, file) || digest.digest().hex() != expectedHash)
                        throw ResumeConflictException("远程源文件完整校验失败，下载断点已保留")
                    checkRemoteEnd(state, file, expected.size, cancelled)
                    if (local.length() != expected.size) throw ResumeConflictException("本地下载长度校验失败，断点已保留")
                    local.seek(0)
                    val savedDigest = MessageDigest.getInstance("SHA-256")
                    var verified = 0L
                    while (verified < expected.size) {
                        checkActive(state, cancelled)
                        val count = minOf(BUFFER_SIZE.toLong(), expected.size - verified).toInt()
                        local.readFully(localBuffer, 0, count)
                        savedDigest.update(localBuffer, 0, count)
                        verified += count
                    }
                    if (savedDigest.digest().hex() != expectedHash)
                        throw ResumeConflictException("本地下载完整校验失败，断点已保留")
                } finally { runCatching { local.fd.sync() } }
            }
        }
    }

    private fun openResumeRead(state: State, path: String): com.hierynomus.smbj.share.File =
        openResumeFile(state, path, EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES), SMB2CreateDisposition.FILE_OPEN)

    private fun openResumeFile(state: State, path: String, access: EnumSet<AccessMask>, disposition: SMB2CreateDisposition): com.hierynomus.smbj.share.File {
        fun open(requested: Set<AccessMask>) = share(state).openFile(state.address.resolve(path), requested, null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), disposition,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_OPEN_REPARSE_POINT))
        return try { open(access) } catch (error: SMBApiException) {
            if (error.statusCode != STATUS_ACCESS_DENIED || AccessMask.FILE_READ_ATTRIBUTES !in access) throw error
            // Some NAS ACLs permit file contents but deny READ_ATTRIBUTES. Keep the exact
            // same no-follow and deny-writer options; only reduce the optional access bit.
            open(EnumSet.copyOf(access).apply { remove(AccessMask.FILE_READ_ATTRIBUTES) })
        }
    }

    private fun resumeStat(state: State, path: String, file: com.hierynomus.smbj.share.File): Pair<Long, Long> {
        val info = try { file.getFileInformation(FileAllInformation::class.java) } catch (error: SMBApiException) {
            if (error.statusCode !in RESUME_METADATA_FALLBACK_STATUS) throw error
            null
        }
        if (info != null) {
            if (info.standardInformation.isDirectory ||
                info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L)
                throw ResumeConflictException("断点续传不支持文件夹或符号链接")
            return info.standardInformation.endOfFile to info.basicInformation.lastWriteTime.toEpochMillis()
        }
        // A fresh directory query supplies the same size/time/type fields without requesting
        // extra rights on this file. Prefix and complete content hashes remain mandatory.
        val entry = resumeDirectoryEntry(state, path) ?: throw ResumeConflictException("断点文件已被移动或删除")
        if (entry.isDirectory || entry.isReparsePoint) throw ResumeConflictException("断点续传不支持文件夹或符号链接")
        return entry.size to entry.lastModified
    }

    private fun resumeDirectoryEntry(state: State, path: String): SmbEntry? {
        val name = SmbAddress.normalizeRelativePath(path).substringAfterLast('\\')
        val entries = list(state, SmbAddress.parentPath(path))
        return entries.firstOrNull { it.name == name } ?: entries.filter { it.name.equals(name, true) }.let {
            if (it.size > 1) throw ResumeConflictException("服务器返回了含糊的断点文件名称")
            it.singleOrNull()
        }
    }

    private fun resumeExists(state: State, path: String): Boolean = try { exists(share(state), state.address.resolve(path)) }
    catch (error: SMBApiException) {
        if (error.statusCode !in RESUME_METADATA_FALLBACK_STATUS) throw error
        resumeDirectoryEntry(state, path) != null
    }

    private fun hashRemote(state: State, file: com.hierynomus.smbj.share.File, bytes: Long, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var offset = 0L
        while (offset < bytes) {
            val count = minOf(BUFFER_SIZE.toLong(), bytes - offset).toInt()
            readRemoteExactly(state, file, buffer, offset, count, cancelled)
            digest.update(buffer, 0, count)
            offset += count
        }
        checkRemoteEnd(state, file, bytes, cancelled)
        return digest.digest().hex()
    }

    private fun checkRemoteEnd(state: State, file: com.hierynomus.smbj.share.File, bytes: Long, cancelled: () -> Boolean) {
        checkActive(state, cancelled)
        // Directory metadata can lag on a NAS. Never interpret a matching prefix hash as
        // a complete file merely because the last listed size still describes that prefix.
        // SMBJ maps STATUS_END_OF_FILE to -1; some servers return a successful zero read.
        if (file.read(ByteArray(1), bytes, 0, 1) > 0)
            throw ResumeConflictException("远程文件包含超出记录长度的数据，断点已保留")
        checkActive(state, cancelled)
    }

    private fun readRemoteExactly(state: State, file: com.hierynomus.smbj.share.File, buffer: ByteArray,
                                  position: Long, count: Int, cancelled: () -> Boolean) {
        var done = 0
        while (done < count) {
            checkActive(state, cancelled)
            val read = file.read(buffer, position + done, done, count - done)
            if (read <= 0) throw ResumeConflictException("远程文件未完整返回数据，断点已保留")
            done += read
        }
    }

    private fun readSourceExactly(state: State, input: InputStream, buffer: ByteArray, count: Int, cancelled: () -> Boolean) {
        var done = 0
        while (done < count) {
            checkActive(state, cancelled)
            val read = input.read(buffer, done, count - done)
            if (read < 0) throw ResumeConflictException("源文件大小已变化，原断点已保留")
            if (read == 0) throw IOException("文件来源未返回数据，断点已保留，请重试")
            done += read
        }
    }

    private fun resumeTemporaryPath(path: String, transferId: String): String {
        require(runCatching { UUID.fromString(transferId).toString() == transferId.lowercase() }.getOrDefault(false)) { "断点任务 ID 无效" }
        val temporary = SmbAddress.childPath(SmbAddress.parentPath(path), ".sendtosmb-resume-${transferId.lowercase()}.part")
        require(!SmbAddress.normalizeRelativePath(path).equals(SmbAddress.normalizeRelativePath(temporary), ignoreCase = true)) {
            "目标名称不能与本任务的断点文件相同"
        }
        return temporary
    }

    private fun validateResumeHash(hash: String): String {
        require(hash.matches(Regex("[0-9a-fA-F]{64}"))) { "断点任务缺少有效的源文件校验值" }
        return hash.lowercase()
    }
    private fun ByteArray.regionMatches(other: ByteArray, length: Int): Boolean = (0 until length).all { this[it] == other[it] }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
        private val RESUME_METADATA_FALLBACK_STATUS = setOf(STATUS_ACCESS_DENIED, 0xC00000BBL, 0xC0000003L, 0xC000000DL)
    }
}
