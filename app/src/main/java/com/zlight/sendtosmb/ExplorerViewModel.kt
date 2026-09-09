package com.zlight.sendtosmb

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.zlight.sendtosmb.data.SmbAddress
import com.zlight.sendtosmb.data.SmbRepository
import com.zlight.sendtosmb.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ExplorerViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val store = ProfileStore(application)
    private val transferStore = TransferStore(application)
    private val gate = Mutex()
    private var repository: SmbRepository? = null
    private var foreground = false
    private var autoConnect = true
    private var generation = 0
    private val jobs = mutableSetOf<Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private var clipboard = emptyList<UiFile>()
    private var clipboardMove = false
    private val network = LanMonitor(application) { available ->
        viewModelScope.launch {
            val wasAvailable = mutable.value.networkAvailable
            mutable.update { it.copy(networkAvailable = available) }
            if (!available && wasAvailable) closeSession("局域网已断开，请连接 Wi-Fi 后重试")
            if (available && !wasAvailable && foreground && autoConnect) reconnect()
        }
    }

    init {
        runCatching { store.read() }.onSuccess { (profiles, current) ->
            mutable.update { it.copy(profiles = profiles, currentProfileId = current) }
        }.onFailure { mutable.update { it.copy(message = "保存的凭据无法解密，请重新添加连接") } }
        runCatching { transferStore.read() }.onSuccess { history ->
            mutable.update { it.copy(transfers = history) }
        }.onFailure { mutable.update { it.copy(message = "传输记录无法读取，新的记录仍会正常保存") } }
        runCatching { transferStore.readDownloadDirectory() }.onSuccess { directory ->
            mutable.update { it.copy(downloadDirectoryUri = directory?.uri, downloadDirectoryName = directory?.name) }
        }.onFailure { mutable.update { it.copy(message = "保存的下载目录已失效，请重新选择") } }
        mutable.update { it.copy(networkAvailable = network.available) }
        network.start()
    }

    fun onForeground() {
        foreground = true
        autoConnect = true
        mutable.update { it.copy(networkAvailable = network.available) }
        reconnect()
    }

    fun onBackground() {
        foreground = false
        closeSession()
    }

    private fun closeSession(message: String? = null) {
        generation++
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
        val old = repository
        repository = null
        if (old != null) CoroutineScope(Dispatchers.IO).launch { runCatching { old.disconnect() } }
        val hadActiveTransfers = mutable.value.transfers.any { it.status == "running" || it.status == "queued" }
        mutable.update { current -> current.copy(connected = false, connecting = false, loading = false,
            message = message ?: current.message,
            transfers = current.transfers.map { if (it.status in setOf("running", "queued")) it.copy(status = "cancelled", error = "连接关闭，传输已停止") else it }) }
        if (hadActiveTransfers) persistTransfers()
    }

    private fun reconnect() {
        if (!foreground || !autoConnect || !mutable.value.networkAvailable || mutable.value.currentProfileId == null || mutable.value.connected || mutable.value.connecting) return
        work { ensureConnection(); reload() }
    }

    private suspend fun ensureConnection(): SmbRepository {
        check(foreground) { "应用已退出前台" }
        check(network.available) { "请先连接 Wi-Fi 或有线局域网" }
        repository?.let { current ->
            if (mutable.value.connected && current.isConnected) return current
            repository = null
            mutable.update { it.copy(connected = false, connecting = false) }
            withContext(NonCancellable + Dispatchers.IO) { runCatching { current.disconnect() } }
        }
        val profile = mutable.value.profiles.firstOrNull { it.id == mutable.value.currentProfileId }
            ?: error("请先添加并选择 SMB 连接")
        val address = SmbAddress.parse(profile.url)
        val next = SmbRepository()
        repository = next
        mutable.update { it.copy(connecting = true) }
        try {
            withContext(Dispatchers.IO) { next.connect(address, profile.username, profile.password, profile.domain) }
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(connected = true, connecting = false) }
            return next
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { next.disconnect() } }
            if (repository === next) {
                repository = null
                mutable.update { it.copy(connected = false, connecting = false) }
            }
            throw e
        }
    }

    private fun work(block: suspend () -> Unit) {
        val expected = generation
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                gate.withLock {
                    if (expected != generation) return@withLock
                    mutable.update { it.copy(loading = true) }
                    try { block() } finally { if (expected == generation) mutable.update { it.copy(loading = false) } }
                }
            } catch (_: CancellationException) {
                // A background transition invalidates this operation and its connection.
            } catch (e: Exception) {
                Log.e(TAG, "SMB operation failed", e)
                if (expected == generation) mutable.update { it.copy(connecting = false, message = explain(e)) }
            }
        }
        jobs.add(job)
        job.invokeOnCompletion { viewModelScope.launch { jobs.remove(job) } }
        job.start()
    }

    private suspend fun reload(path: String = mutable.value.path) {
        var retried = false
        while (true) {
            val smb = ensureConnection()
            try {
                val (entries, capacity) = withContext(Dispatchers.IO) {
                    smb.list(path) to runCatching { smb.capacity() }.getOrNull()
                }
                mutable.update { it.copy(path = path, files = entries.map { f -> UiFile(f.name, f.path, f.isDirectory, f.size, f.lastModified) },
                    capacity = capacity?.let { c -> UiCapacity(c.totalBytes, c.freeBytes) }) }
                return
            } catch (error: Exception) {
                if (retried || !shouldRetryRead(error)) throw error
                retried = true
                resetConnectionForRetry()
            }
        }
    }

    private suspend fun resetConnectionForRetry() {
        val old = repository
        repository = null
        mutable.update { it.copy(connected = false, connecting = false) }
        if (old != null) withContext(NonCancellable + Dispatchers.IO) { runCatching { old.disconnect() } }
    }

    private fun persist() { store.write(mutable.value.profiles, mutable.value.currentProfileId) }

    fun dispatch(action: UiAction) {
        when (action) {
            is UiAction.SaveProfile -> {
                runCatching {
                    val p = action.profile.copy(url = SmbAddress.parse(action.profile.url).canonicalUrl,
                        name = action.profile.name.trim().ifBlank { "SMB 共享" }, username = action.profile.username.trim(), domain = action.profile.domain.trim())
                    val old = mutable.value
                    val profiles = old.profiles.filterNot { it.id == p.id } + p
                    store.write(profiles, if (action.connectNow) p.id else old.currentProfileId)
                    mutable.update { it.copy(profiles = profiles, message = "连接已保存") }
                    if (action.connectNow) dispatch(UiAction.Connect(p.id))
                }.onFailure { mutable.update { s -> s.copy(message = explain(it)) } }
            }
            is UiAction.Connect -> {
                closeSession()
                autoConnect = true
                clipboard = emptyList()
                mutable.update { it.copy(currentProfileId = action.profileId, path = "", files = emptyList(), capacity = null, clipboardCount = 0, message = null) }
                runCatching { persist() }.onFailure { mutable.update { s -> s.copy(message = explain(it)) } }
                if (!network.available) mutable.update { it.copy(message = "请连接与 SMB 服务器互通的 Wi-Fi 或有线局域网") }
                else reconnect()
            }
            UiAction.Disconnect -> { autoConnect = false; closeSession("已断开连接；下次进入应用时自动重连") }
            is UiAction.DeleteProfile -> {
                if (mutable.value.currentProfileId == action.profileId) { closeSession(); mutable.update { it.copy(currentProfileId = null, files = emptyList(), capacity = null) } }
                mutable.update { it.copy(profiles = it.profiles.filterNot { p -> p.id == action.profileId }) }
                runCatching { persist() }.onFailure { mutable.update { s -> s.copy(message = explain(it)) } }
            }
            is UiAction.Navigate -> work { reload(action.path) }
            UiAction.Refresh -> work { reload() }
            is UiAction.CreateFolder -> work { val smb = ensureConnection(); withContext(Dispatchers.IO) { smb.mkdir(join(mutable.value.path, validName(action.name))) }; reload() }
            is UiAction.Rename -> work { val smb = ensureConnection(); withContext(Dispatchers.IO) { smb.rename(action.file.path, validName(action.newName)) }; reload() }
            is UiAction.Delete -> work {
                val smb = ensureConnection()
                withContext(Dispatchers.IO) { action.files.forEach { ensureActive(); smb.delete(it.path) } }
                reload(); mutable.update { it.copy(message = "已删除 ${action.files.size} 个项目") }
            }
            is UiAction.SetClipboard -> {
                clipboard = action.files; clipboardMove = action.move
                mutable.update { it.copy(clipboardCount = clipboard.size, message = "已${if (clipboardMove) "剪切" else "复制"} ${clipboard.size} 个项目，请打开目标文件夹后粘贴") }
            }
            UiAction.Paste -> {
                val items = clipboard.toList(); val move = clipboardMove; val destination = mutable.value.path
                work {
                    val smb = ensureConnection()
                    withContext(Dispatchers.IO) { items.forEach { file ->
                        ensureActive()
                        val target = join(destination, file.name)
                        require(!target.equals(file.path, true) && !target.startsWith(file.path + "/", true)) { "请选择其他目标文件夹" }
                        if (move) smb.move(file.path, target) else smb.copy(file.path, target, cancelled = { !isActive })
                    } }
                    clipboard = emptyList(); mutable.update { it.copy(clipboardCount = 0, message = "已粘贴 ${items.size} 个项目") }; reload()
                }
            }
            is UiAction.CancelTransfer -> cancelled.add(action.id)
            UiAction.ClearCompletedTransfers -> {
                mutable.update { it.copy(transfers = it.transfers.filter { t -> t.status in setOf("running", "queued") }) }
                persistTransfers()
            }
            UiAction.ClearDownloadDirectory -> {
                runCatching { transferStore.writeDownloadDirectory(null) }
                    .onSuccess { mutable.update { it.copy(downloadDirectoryUri = null, downloadDirectoryName = null, message = "已恢复为每次下载时选择目录") } }
                    .onFailure { mutable.update { state -> state.copy(message = explain(it)) } }
            }
            UiAction.DismissMessage -> mutable.update { it.copy(message = null) }
            else -> Unit // Document and Wi-Fi pickers belong to the Activity.
        }
    }

    fun upload(uris: List<Uri>, destination: String) {
        if (uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        work {
            val metadata = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    var name = "上传文件"; var size = -1L
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (n >= 0) name = cursor.getString(n) ?: name
                            val s = cursor.getColumnIndex(OpenableColumns.SIZE); if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
                        }
                    }
                    Triple(uri, name, size)
                }
            }
            val items = metadata.map { (uri, name, size) -> Triple(uri, name, newTransfer(name, "upload", size, profileId = mutable.value.currentProfileId)) }
            val smb = connectionForTransfers(items.map { it.third })
            for ((uri, name, id) in items) transfer(id) { isCancelled, progress ->
                withContext(Dispatchers.IO) {
                    val existing = smb.list(destination).map { it.name.lowercase() }.toSet()
                    val targetName = uniqueName(validName(name), existing)
                    resolver.openInputStream(uri)?.use { input ->
                        smb.upload(join(destination, targetName), input, mutable.value.transfers.first { it.id == id }.total, isCancelled, progress)
                    } ?: throw IOException("无法读取所选文件")
                }
            }
            reload()
            reportTransfers(items.map { it.third }, "上传")
        }
    }

    fun download(files: List<UiFile>, treeUri: Uri, requestedProfileId: String? = null) {
        if (files.isEmpty()) return
        if (requestedProfileId != null && mutable.value.profiles.none { it.id == requestedProfileId }) {
            mutable.update { it.copy(message = "原连接已被删除，无法再次下载") }
            return
        }
        if (requestedProfileId != null && requestedProfileId != mutable.value.currentProfileId) {
            closeSession()
            autoConnect = true
            clipboard = emptyList()
            mutable.update { it.copy(currentProfileId = requestedProfileId, path = "", files = emptyList(), capacity = null, clipboardCount = 0) }
            runCatching { persist() }.onFailure { mutable.update { state -> state.copy(message = explain(it)) } }
        }
        val profileId = requestedProfileId ?: mutable.value.currentProfileId
        val items = files.map { file -> file to newTransfer(file.name, "download", if (file.isDirectory) -1 else file.size, file, profileId) }
        work {
            val smb = connectionForTransfers(items.map { it.second })
            for ((file, id) in items) transfer(id) { isCancelled, progress ->
                withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: error("无法打开保存位置")
                    var done = 0L
                    suspend fun save(entry: UiFile, parent: DocumentFile, depth: Int) {
                        check(depth < 128) { "文件夹层级过深" }
                        if (isCancelled()) throw CancellationException("已取消")
                        val name = uniqueName(validName(entry.name), parent.listFiles().mapNotNull { it.name?.lowercase() }.toSet())
                        if (entry.isDirectory) {
                            val folder = parent.createDirectory(name) ?: error("无法创建本地文件夹")
                            smb.list(entry.path).forEach { f -> save(UiFile(f.name, f.path, f.isDirectory, f.size, f.lastModified), folder, depth + 1) }
                        } else {
                            val target = parent.createFile("application/octet-stream", name) ?: error("无法创建本地文件")
                            try {
                                val start = done
                                getApplication<Application>().contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                                    smb.download(entry.path, output, isCancelled,
                                        { bytes, _ -> progress(start + bytes, if (file.isDirectory) -1 else file.size) },
                                        expectedBytes = entry.size)
                                } ?: error("无法写入保存位置")
                                done += entry.size
                            } catch (e: Exception) { runCatching { target.delete() }; throw e }
                        }
                    }
                    save(file, root, 0)
                }
            }
            reportTransfers(items.map { it.second }, "下载")
        }
    }

    fun setDownloadDirectory(uri: Uri, name: String) {
        runCatching {
            val directory = SavedDownloadDirectory(uri.toString(), name.ifBlank { "已选目录" })
            transferStore.writeDownloadDirectory(directory)
            mutable.update { it.copy(downloadDirectoryUri = directory.uri, downloadDirectoryName = directory.name,
                message = "已指定下载目录：${directory.name}") }
        }.onFailure { error -> mutable.update { it.copy(message = explain(error)) } }
    }

    fun reportDownloadDirectoryError(error: Throwable) {
        Log.e(TAG, "Download directory selection failed", error)
        mutable.update { it.copy(message = explain(error)) }
    }

    private fun reportTransfers(ids: List<String>, direction: String) {
        val items = mutable.value.transfers.filter { it.id in ids }
        val completed = items.count { it.status == "completed" }
        mutable.update { it.copy(message = "$direction 完成 $completed/${items.size} 个项目${if (completed < items.size) "，请在传输页查看详情" else ""}") }
    }

    private fun newTransfer(name: String, direction: String, total: Long, sourceFile: UiFile? = null, profileId: String? = null): String {
        val id = UUID.randomUUID().toString()
        mutable.update { it.copy(transfers = it.transfers + UiTransfer(id, name, direction, total = total, sourceFile = sourceFile, profileId = profileId)) }
        return id
    }

    private suspend fun connectionForTransfers(ids: List<String>): SmbRepository = try {
        ensureConnection()
    } catch (e: Exception) {
        if (e !is CancellationException) {
            ids.forEach { id -> updateTransfer(id) { it.copy(status = "failed", error = explain(e)) } }
            persistTransfers()
        }
        throw e
    }

    private suspend fun transfer(id: String, block: suspend (() -> Boolean, (Long, Long) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        val isCancelled = { !context.isActive || cancelled.contains(id) }
        var lastUpdate = 0L
        try {
            if (isCancelled()) throw CancellationException()
            updateTransfer(id) { it.copy(status = "running") }
            block(isCancelled) { done, total ->
                val now = System.nanoTime()
                if (now - lastUpdate > 100_000_000 || done == total) {
                    updateTransfer(id) { it.copy(done = done, total = total) }; lastUpdate = now
                }
            }
            if (isCancelled()) throw CancellationException()
            updateTransfer(id) { it.copy(status = "completed", done = if (it.total >= 0) it.total else it.done) }
            persistTransfers()
        } catch (e: Exception) {
            updateTransfer(id) { it.copy(status = if (isCancelled() || e is CancellationException) "cancelled" else "failed", error = if (isCancelled()) "传输已停止" else explain(e)) }
            persistTransfers()
            context.ensureActive()
        } finally { cancelled.remove(id) }
    }

    private fun updateTransfer(id: String, update: (UiTransfer) -> UiTransfer) = mutable.update { it.copy(transfers = it.transfers.map { t -> if (t.id == id) update(t) else t }) }

    private fun persistTransfers() {
        runCatching { transferStore.write(mutable.value.transfers) }
            .onFailure { mutable.update { state -> state.copy(message = "传输记录保存失败") } }
    }

    private fun shouldRetryRead(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            val message = cause.message.orEmpty()
            cause is SMBApiException || cause is SMBRuntimeException || cause is IOException ||
                listOf("ACCESS_DENIED", "USER_SESSION_DELETED", "NETWORK_SESSION_EXPIRED", "NETWORK_NAME_DELETED",
                    "CONNECTION_DISCONNECTED", "CONNECTION_RESET", "CONNECTION RESET", "BROKEN_PIPE", "BROKEN PIPE")
                    .any { message.contains(it, ignoreCase = true) }
        }

    private fun explain(error: Throwable): String {
        val message = error.message.orEmpty()
        val smb = generateSequence(error) { it.cause }.filterIsInstance<SMBApiException>().firstOrNull()
        return when {
            error is SecurityException -> "所选保存位置没有写入权限，请选择其他文件夹"
            smb?.statusCode == 0xC0000022L || message.contains("LOGON_FAILURE", true) || message.contains("ACCESS_DENIED", true) -> "访问被拒绝，请检查用户名、密码和共享权限"
            smb?.statusCode in setOf(0xC000000FL, 0xC0000034L, 0xC000003AL) -> "此文件或文件夹已不存在，请刷新后重试"
            message.contains("BAD_NETWORK_NAME", true) -> "找不到共享文件夹，请检查 URL 中的共享名称"
            message.contains("COLLISION", true) -> "目标已存在同名项目，请修改名称后重试"
            smb != null -> "服务器暂时无法打开此项目，已重新连接；请再试一次"
            message.contains("connect", true) || message.contains("timeout", true) || error is java.net.SocketException -> "无法连接服务器，请检查 Wi-Fi、服务器地址和 SMB 端口"
            message.isNotBlank() && message.any { it in '\u4e00'..'\u9fff' } -> message.take(200)
            else -> "操作失败，请检查连接和文件权限后重试"
        }
    }

    override fun onCleared() { network.stop(); closeSession(); super.onCleared() }

    private companion object { const val TAG = "SendToSMB" }
}

internal fun join(parent: String, name: String) = if (parent.isBlank()) name else "$parent/$name"
internal fun validName(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it in "\\/:*?\"<>|" || it.code < 32 } && !name.endsWith('.') && !name.endsWith(' ')) { "名称不能为空，也不能包含路径分隔符或 Windows 不支持的字符" }
    return name
}
internal fun uniqueName(name: String, existing: Set<String>): String {
    if (name.lowercase() !in existing) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot); val extension = name.substring(dot)
    return generateSequence(1) { it + 1 }.map { "$base ($it)$extension" }.first { it.lowercase() !in existing }
}
