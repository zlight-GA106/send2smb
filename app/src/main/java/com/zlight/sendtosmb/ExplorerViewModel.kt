package com.zlight.sendtosmb

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ExplorerViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val store = ProfileStore(application)
    private val transferStore = TransferStore(application)
    private val settings = SettingsStore(application)
    private val updates = EasyUpdateClient(application)
    private var deviceId = ""
    private val updateCancel = AtomicBoolean(false)
    private val gate = Mutex()
    private var repository: SmbRepository? = null
    private var foreground = false
    private var autoConnect = true
    private var generation = 0
    private val jobs = mutableSetOf<Job>()
    private val transferJobs = mutableSetOf<Job>()
    private val taskSpeeds = ConcurrentHashMap<String, TransferSpeedMeter>()
    @Volatile private var batchSpeed: TransferSpeedMeter? = null
    private var speedTicker: Job? = null
    private var backgroundServiceRequested = false
    private var backgroundServiceFailed = false
    private val ownedUploadGrants = ConcurrentHashMap.newKeySet<String>()
    private val scheduledTransfers = ConcurrentHashMap<String, String>()
    private val transferPersistence = Any()
    private val resumeDocuments by lazy { AndroidResumeDocuments(getApplication()) }
    private val resumeWorkspace by lazy { File(getApplication<Application>().filesDir, "transfer-resume") }
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private var clipboard = emptyList<UiFile>()
    private var clipboardMove = false
    private val network = LanMonitor(application) { available ->
        viewModelScope.launch {
            val wasAvailable = mutable.value.networkAvailable
            mutable.update { it.copy(networkAvailable = available) }
            // LAN detection is only a hint: VPN or other routes may still reach the server,
            // so never close a working session just because Wi-Fi/Ethernet was lost.
            if (available && !wasAvailable && foreground && autoConnect) reconnect()
        }
    }

    init {
        runCatching { store.read() }.onSuccess { (profiles, current) ->
            mutable.update { it.copy(profiles = profiles, currentProfileId = current) }
        }.onFailure { mutable.update { it.copy(message = "保存的凭据无法解密，请重新添加连接") } }
        runCatching { transferStore.read() }.onSuccess { history ->
            mutable.update { it.copy(transfers = history) }
            ownedUploadGrants.addAll(history.mapNotNull { it.resume?.takeIf { r -> r.uploadGrantOwned }?.sourceUri })
        }.onFailure { mutable.update { it.copy(message = "传输记录无法读取，新的记录仍会正常保存") } }
        runCatching { transferStore.readDownloadDirectory() }.onSuccess { directory ->
            mutable.update { it.copy(downloadDirectoryUri = directory?.uri, downloadDirectoryName = directory?.name) }
        }.onFailure { mutable.update { it.copy(message = "保存的下载目录已失效，请重新选择") } }
        runCatching { settings.read() }.onSuccess { saved ->
            deviceId = saved.deviceId
            mutable.update { it.copy(einkMode = saved.einkMode, backgroundTransfers = saved.backgroundTransfers,
                update = it.update.copy(serverUrl = saved.updateServerUrl)) }
        }
        mutable.update { it.copy(networkAvailable = network.available) }
        network.start()
    }

    fun onForeground() {
        foreground = true
        backgroundServiceFailed = false
        autoConnect = true
        mutable.update { it.copy(networkAvailable = network.available) }
        syncBackgroundService()
        reconnect()
    }

    fun onBackground() {
        foreground = false
        if (!canTransferInBackground()) closeSession()
    }

    private fun canTransferInBackground() = mutable.value.backgroundTransfers &&
        mutable.value.transferBusy && backgroundServiceRequested

    private fun syncBackgroundService() {
        if (mutable.value.backgroundTransfers && mutable.value.transferBusy) {
            if (!backgroundServiceRequested && !backgroundServiceFailed && foreground) {
                runCatching { BackgroundTransferService.start(getApplication()) }
                    .onSuccess { backgroundServiceRequested = true }
                    .onFailure(::onBackgroundServiceFailed)
            }
        } else if (backgroundServiceRequested) {
            backgroundServiceRequested = false
            BackgroundTransferService.stop(getApplication())
        }
    }

    fun onBackgroundServiceFailed(error: Throwable) {
        Log.e(TAG, "Background transfer service failed", error)
        backgroundServiceRequested = false
        backgroundServiceFailed = true
        mutable.update { it.copy(message = "后台传输服务无法启动，请保持应用在前台传输") }
        if (!foreground) closeSession()
    }

    fun onBackgroundServiceStopped() {
        backgroundServiceRequested = false
        if (!foreground) closeSession()
        else syncBackgroundService()
    }

    fun stopTransfers(message: String) { closeSession(message) }

    private fun closeSession(message: String? = null) {
        generation++
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
        transferJobs.clear()
        scheduledTransfers.clear()
        speedTicker?.cancel()
        speedTicker = null
        taskSpeeds.clear()
        batchSpeed = null
        BackgroundTransferService.stop(getApplication())
        backgroundServiceRequested = false
        val old = repository
        repository = null
        if (old != null) CoroutineScope(Dispatchers.IO).launch { runCatching { old.disconnect() } }
        val hadActiveTransfers = mutable.value.transfers.any { it.status in setOf("running", "queued", "discarding") }
        mutable.update { current -> current.copy(connected = false, connecting = false, loading = false, transferBusy = false,
            message = message ?: current.message,
            transfers = current.transfers.map { if (it.status in setOf("running", "queued", "discarding")) it.copy(
                status = if (it.resume != null) "paused" else "cancelled", bytesPerSecond = 0.0,
                error = if (it.resume != null) "连接关闭，断点已保留，可继续传输" else "连接关闭，传输已停止") else it }) }
        if (hadActiveTransfers) persistTransfers()
    }

    private fun reconnect() {
        if (!foreground || !autoConnect || mutable.value.currentProfileId == null || mutable.value.connected || mutable.value.connecting) return
        work { ensureConnection(); reload() }
    }

    private suspend fun ensureConnection(): SmbRepository {
        check(foreground || canTransferInBackground()) { "应用已退出前台" }
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

    private fun work(transferWork: Boolean = false, block: suspend () -> Unit): Job {
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
        if (transferWork) {
            if (transferJobs.isEmpty()) {
                batchSpeed = TransferSpeedMeter()
                mutable.update { it.copy(transferBusy = true, averageBytesPerSecond = 0.0) }
                speedTicker = viewModelScope.launch {
                    while (isActive) {
                        delay(500)
                        refreshSpeeds()
                    }
                }
            }
            transferJobs.add(job)
            syncBackgroundService()
        }
        job.invokeOnCompletion { viewModelScope.launch {
            jobs.remove(job)
            if (transferJobs.remove(job) && transferJobs.isEmpty()) {
                refreshSpeeds()
                speedTicker?.cancel()
                speedTicker = null
                batchSpeed = null
                mutable.update { it.copy(transferBusy = false) }
                syncBackgroundService()
                if (!foreground) closeSession()
            }
        } }
        job.start()
        return job
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
                    val previous = old.profiles.firstOrNull { it.id == p.id }
                    val connectionChanged = previous != null && old.currentProfileId == p.id &&
                        (previous.url != p.url || previous.username != p.username || previous.domain != p.domain || previous.password != p.password)
                    val profiles = old.profiles.filterNot { it.id == p.id } + p
                    store.write(profiles, if (action.connectNow) p.id else old.currentProfileId)
                    if (connectionChanged) closeSession("连接信息已更新，未完成任务的断点已保留")
                    mutable.update { it.copy(profiles = profiles, message = "连接已保存") }
                    if (action.connectNow) dispatch(UiAction.Connect(p.id))
                }.onFailure { mutable.update { s -> s.copy(message = explain(it)) } }
            }
            is UiAction.Connect -> {
                closeSession()
                autoConnect = true
                clipboard = emptyList()
                mutable.update { it.copy(currentProfileId = action.profileId, path = "", files = emptyList(), capacity = null, clipboardCount = 0,
                    message = if (network.available) null else "未检测到局域网，直接尝试连接服务器") }
                runCatching { persist() }.onFailure { mutable.update { s -> s.copy(message = explain(it)) } }
                reconnect()
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
            is UiAction.CancelTransfer -> {
                cancelled.add(action.id)
                updateTransfer(action.id) { if (it.status == "queued" && it.resume != null) it.copy(status = "paused", error = "已暂停，断点已保留") else it }
                persistTransfers()
            }
            is UiAction.ResumeTransfer -> resumeTransfer(action.id)
            is UiAction.DiscardTransfer -> discardTransfer(action.id)
            UiAction.ClearCompletedTransfers -> {
                mutable.update { it.copy(transfers = it.transfers.filter { t -> t.resume != null || t.status in setOf("running", "queued") }) }
                persistTransfers()
            }
            UiAction.ClearDownloadDirectory -> {
                runCatching { transferStore.writeDownloadDirectory(null) }
                    .onSuccess { mutable.update { it.copy(downloadDirectoryUri = null, downloadDirectoryName = null, message = "已恢复为每次下载时选择目录") } }
                    .onFailure { mutable.update { state -> state.copy(message = explain(it)) } }
            }
            is UiAction.SetEinkMode -> {
                runCatching { settings.writeEink(action.enabled) }
                    .onSuccess { mutable.update { it.copy(einkMode = action.enabled) } }
                    .onFailure { mutable.update { state -> state.copy(message = explain(it)) } }
            }
            is UiAction.SetBackgroundTransfers -> {
                runCatching { settings.writeBackgroundTransfers(action.enabled) }
                    .onSuccess {
                        mutable.update { it.copy(backgroundTransfers = action.enabled) }
                        syncBackgroundService()
                        if (!foreground && !canTransferInBackground()) closeSession()
                    }
                    .onFailure { mutable.update { state -> state.copy(message = explain(it)) } }
            }
            is UiAction.CheckUpdate -> checkForUpdate(action.serverUrl)
            UiAction.DownloadUpdate -> downloadUpdate()
            UiAction.CancelUpdateDownload -> updateCancel.set(true)
            is UiAction.ReportUpdateStatus -> setUpdateStatus(action.message)
            UiAction.DismissMessage -> mutable.update { it.copy(message = null) }
            else -> Unit // Document, Wi-Fi pickers and the APK installer belong to the Activity.
        }
    }

    private fun setUpdateStatus(status: String) = mutable.update { it.copy(update = it.update.copy(status = status)) }

    private fun checkForUpdate(rawUrl: String) {
        if (mutable.value.update.checking || mutable.value.update.downloading) return
        val base = runCatching { EasyUpdateClient.normalizeServer(rawUrl) }.getOrElse {
            setUpdateStatus("服务地址无效：请输入 http:// 或 https:// 开头的地址")
            return
        }
        runCatching { settings.writeUpdateServerUrl(base) }
        mutable.update { it.copy(update = it.update.copy(serverUrl = base, checking = true, info = null,
            status = "正在检查更新…", progress = 0f, verifiedPath = null)) }
        viewModelScope.launch {
            runCatching { updates.check(base) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is UpdateOutcome.UpToDate -> mutable.update { it.copy(update = it.update.copy(checking = false,
                            status = "已是最新版本（${outcome.latestName} · ${outcome.latestCode}）")) }
                        is UpdateOutcome.Available -> mutable.update { it.copy(update = it.update.copy(checking = false,
                            info = outcome.info, status = null)) }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(update = it.update.copy(checking = false,
                        status = "检查更新失败：${error.message ?: "请检查服务地址和网络"}")) }
                }
            viewModelScope.launch(Dispatchers.IO) { runCatching { updates.heartbeat(base, deviceId) } }
        }
    }

    private fun downloadUpdate() {
        val info = mutable.value.update.info ?: return
        if (mutable.value.update.downloading) return
        val base = mutable.value.update.serverUrl
        updateCancel.set(false)
        mutable.update { it.copy(update = it.update.copy(downloading = true, progress = 0f, verifiedPath = null,
            status = "正在下载 ${info.versionName}…")) }
        viewModelScope.launch {
            try {
                val file = updates.download(info, base, { updateCancel.get() }) { done, total ->
                    mutable.update { state -> state.copy(update = state.update.copy(progress = if (total > 0) done.toFloat() / total else 0f)) }
                }
                mutable.update { it.copy(update = it.update.copy(downloading = false, progress = 1f,
                    verifiedPath = file.absolutePath, status = "更新包已下载并通过校验，可以安装")) }
            } catch (_: CancellationException) {
                mutable.update { it.copy(update = it.update.copy(downloading = false, progress = 0f, status = "已取消下载")) }
            } catch (error: Exception) {
                Log.e(TAG, "Update download failed", error)
                mutable.update { it.copy(update = it.update.copy(downloading = false, progress = 0f,
                    status = "下载失败：${error.message ?: "请重试"}")) }
            }
        }
    }

    fun upload(uris: List<Uri>, destination: String) {
        if (uris.isEmpty()) return
        val profile = mutable.value.profiles.firstOrNull { it.id == mutable.value.currentProfileId } ?: return
        val resolver = getApplication<Application>().contentResolver
        uris.distinct().forEach { uri ->
            if (resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission } &&
                runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess)
                ownedUploadGrants.add(uri.toString())
        }
        // Record the selection before waiting behind any earlier transfer batch.
        val items = uris.distinct().mapIndexed { index, uri ->
            newTransfer("准备上传 ${index + 1}", "upload", -1, profileId = profile.id,
                resume = TransferResume(SmbAddress.parse(profile.url).canonicalUrl, profile.username, profile.domain,
                    destination, "", sourceUri = uri.toString(), uploadGrantOwned = uri.toString() in ownedUploadGrants))
        }
        enqueueResumable(items, refresh = true, direction = "上传")
    }

    fun download(files: List<UiFile>, treeUri: Uri, requestedProfileId: String? = null) {
        if (files.isEmpty()) return
        val profileId = requestedProfileId ?: mutable.value.currentProfileId
        val profile = mutable.value.profiles.firstOrNull { it.id == profileId }
        if (profile == null) { mutable.update { it.copy(message = "原连接已被删除，无法下载") }; return }
        if (!selectTransferProfile(profile.id)) return
        val items = files.map { file -> newTransfer(file.name, "download", if (file.isDirectory) -1 else file.size, file, profile.id,
            TransferResume(SmbAddress.parse(profile.url).canonicalUrl, profile.username, profile.domain,
                file.path, "", treeUri = treeUri.toString(), size = if (file.isDirectory) -1 else file.size, modified = file.modifiedMillis)) }
        enqueueResumable(items, direction = "下载")
    }

    private fun enqueueResumable(ids: List<String>, refresh: Boolean = false, direction: String? = null) {
        val owner = UUID.randomUUID().toString()
        ids.forEach { scheduledTransfers[it] = owner }
        // This runs immediately, outside the serialized SMB gate, even for a waiting batch.
        val recorded = viewModelScope.async(Dispatchers.IO) {
            persistTransfers(strict = true)
            // Resolve display metadata without holding the SMB gate or delaying the first journal.
            ids.forEach { id ->
                val item = mutable.value.transfers.firstOrNull { it.id == id }
                item?.resume?.takeIf { it.targetName.isEmpty() }?.sourceUri?.let { uri ->
                    runCatching { resumeDocuments.stat(uri) }.getOrNull()?.let { info ->
                        updateTransfer(id) { it.copy(name = info.name, total = info.size) }
                    }
                }
            }
            persistTransfers(strict = true)
        }
        val job = work(transferWork = true) {
            recorded.await()
            runResumableBatch(ids, owner)
            if (refresh && mutable.value.connected) runCatching { reload() }
            if (direction != null) reportTransfers(ids, direction)
        }
        job.invokeOnCompletion { viewModelScope.launch {
            ids.filter { scheduledTransfers.remove(it, owner) }.forEach { id ->
                updateTransfer(id) { if (it.status == "queued") it.copy(status = "paused", error = "任务已中断，断点记录已保留") else it }
            }
            persistTransfers()
            releaseUnusedUploadGrants()
        } }
    }

    private fun selectTransferProfile(profileId: String): Boolean {
        if (mutable.value.currentProfileId == profileId) return true
        if (mutable.value.transferBusy) { mutable.update { it.copy(message = "请先暂停当前传输，再继续其他连接的任务") }; return false }
        closeSession()
        autoConnect = true
        clipboard = emptyList()
        mutable.update { it.copy(currentProfileId = profileId, path = "", files = emptyList(), capacity = null, clipboardCount = 0) }
        runCatching { persist() }
        return true
    }

    private fun resumeTransfer(id: String) {
        val item = mutable.value.transfers.firstOrNull { it.id == id && it.resume != null && it.status !in setOf("queued", "running", "discarding") } ?: return
        val profile = matchingResumeProfile(item)
        if (profile == null) {
            mutable.update { it.copy(message = "请添加原服务器、原账号的连接后继续，或恢复原连接后清理断点") }; return
        }
        if (!selectTransferProfile(profile.id)) return
        cancelled.remove(id)
        updateTransfer(id) { it.copy(status = "queued", profileId = profile.id, error = null) }
        if (scheduledTransfers.containsKey(id)) { persistTransfers(); return }
        enqueueResumable(listOf(id))
    }

    private suspend fun runResumableBatch(ids: List<String>, owner: String) {
        try {
            for (id in ids) {
                if (scheduledTransfers[id] != owner) continue
                val queued = mutable.value.transfers.firstOrNull { it.id == id }
                if (queued?.resume == null || queued.status != "queued") {
                    cancelled.remove(id); scheduledTransfers.remove(id, owner); continue
                }
                transfer(id, owner) { isCancelled, progress ->
                    val item = mutable.value.transfers.first { it.id == id }
                    val saved = item.resume ?: error("续传记录不存在")
                    val profile = mutable.value.profiles.firstOrNull { it.id == item.profileId } ?: error("原 SMB 连接已删除")
                    require(SmbAddress.parse(profile.url).canonicalUrl == saved.serverUrl && profile.username == saved.username && profile.domain == saved.domain) {
                        "服务器或账号已改变，断点已保留；请恢复原连接"
                    }
                    val smb = ensureConnection()
                    withContext(Dispatchers.IO) { resumeEngine(id).run(item.direction, item.sourceFile, smb, isCancelled, progress) }
                }
            }
        } finally { ids.forEach { scheduledTransfers.remove(it, owner) } }
    }

    private fun resumeEngine(id: String, fixed: TransferResume? = null) = ResumeTransferEngine(id, resumeWorkspace, resumeDocuments,
        current = { fixed ?: mutable.value.transfers.first { it.id == id }.resume ?: error("续传记录不存在") },
        checkpoint = { record ->
            synchronized(transferPersistence) {
                updateTransfer(id) { it.copy(resume = record, name = record.targetName.ifBlank { it.name }, total = if (record.size >= 0 && it.direction == "upload") record.size else it.total) }
                persistTransfers(strict = true)
            }
        }, phase = { message -> updateTransfer(id) { it.copy(error = message) } })

    private fun discardTransfer(id: String) {
        val item = mutable.value.transfers.firstOrNull { it.id == id && it.resume != null && it.status !in setOf("running", "queued", "discarding") } ?: return
        val record = item.resume!!
        if (item.direction == "upload" && record.targetName.isNotEmpty()) {
            val profile = matchingResumeProfile(item)
            if (profile == null) {
                mutable.update { it.copy(message = "需恢复原连接才能安全清理服务器断点；现有数据已保留") }; return
            }
            if (!selectTransferProfile(profile.id)) return
            updateTransfer(id) { it.copy(profileId = profile.id) }
        }
        scheduledTransfers.remove(id)
        updateTransfer(id) { it.copy(status = "discarding", error = "正在清理此任务的断点…") }
        work {
            try {
                withContext(Dispatchers.IO) { persistTransfers(strict = true) }
                val smb = if (item.direction == "upload" && record.targetName.isNotEmpty()) ensureConnection() else null
                withContext(Dispatchers.IO) { resumeEngine(id, record).discard(smb) }
                updateTransfer(id) { it.copy(status = "cancelled", resume = null, error = "已放弃续传，已完成的文件保留") }
                withContext(Dispatchers.IO) { persistTransfers(strict = true) }
                releaseUnusedUploadGrants()
            } catch (error: Exception) {
                updateTransfer(id) { it.copy(status = "paused", resume = record, error = "断点清理未完成：${explain(error)}") }
                persistTransfers()
                throw error
            }
        }
    }

    private fun matchingResumeProfile(item: UiTransfer): UiProfile? {
        val record = item.resume ?: return null
        return mutable.value.profiles.sortedBy { if (it.id == item.profileId) 0 else 1 }.firstOrNull {
            SmbAddress.parse(it.url).canonicalUrl == record.serverUrl && it.username == record.username && it.domain == record.domain
        }
    }

    private fun releaseUnusedUploadGrants() {
        val inUse = mutable.value.transfers.mapNotNull { it.resume?.sourceUri }.toSet()
        ownedUploadGrants.filterNot { it in inUse }.toList().forEach { uri ->
            runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            ownedUploadGrants.remove(uri)
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

    private fun newTransfer(name: String, direction: String, total: Long, sourceFile: UiFile? = null, profileId: String? = null,
                            resume: TransferResume? = null): String {
        val id = UUID.randomUUID().toString()
        mutable.update { it.copy(transfers = it.transfers + UiTransfer(id, name, direction, total = total, sourceFile = sourceFile, profileId = profileId, resume = resume)) }
        return id
    }

    private suspend fun transfer(id: String, owner: String, block: suspend (() -> Boolean, (Long, Long) -> Unit) -> Unit) {
        val context = currentCoroutineContext()
        val isCancelled = { !context.isActive || cancelled.contains(id) }
        var lastUpdate = 0L
        var lastPersist = System.nanoTime()
        var lastBytes = mutable.value.transfers.first { it.id == id }.done
        val speed = TransferSpeedMeter()
        val batch = batchSpeed
        taskSpeeds[id] = speed
        try {
            if (isCancelled()) throw CancellationException()
            updateTransfer(id) { it.copy(status = "running", error = null) }
            withContext(Dispatchers.IO) { persistTransfers(strict = true) }
            block(isCancelled) { done, total ->
                val delta = speed.recordProgress(done)
                batch?.add(delta)
                lastBytes = done
                val now = System.nanoTime()
                if (now - lastUpdate > 100_000_000 || done == total) {
                    updateTransfer(id) { it.copy(done = done, total = total,
                        bytesPerSecond = speed.sample(), averageBytesPerSecond = speed.average()) }; lastUpdate = now
                }
                if (now - lastPersist >= 2_000_000_000L) { persistTransfers(strict = true); lastPersist = now }
            }
            val savedResume = mutable.value.transfers.first { it.id == id }.resume
            if (savedResume != null) withContext(Dispatchers.IO) { resumeEngine(id, savedResume).cleanupLocal() }
            updateTransfer(id) { it.copy(status = "completed", done = lastBytes,
                total = lastBytes, bytesPerSecond = 0.0, averageBytesPerSecond = speed.average(), error = null, resume = null) }
            try { withContext(Dispatchers.IO) { persistTransfers(strict = true) } }
            catch (error: Exception) { updateTransfer(id) { it.copy(resume = savedResume) }; throw error }
            releaseUnusedUploadGrants()
        } catch (e: Exception) {
            updateTransfer(id) { if (it.status == "cancelled") it else it.copy(
                status = if (isCancelled() || e is CancellationException) (if (it.resume != null) "paused" else "cancelled") else "failed", done = lastBytes,
                bytesPerSecond = 0.0, averageBytesPerSecond = speed.average(), error = if (isCancelled()) "传输已暂停，断点已保留" else explain(e)) }
            persistTransfers()
            context.ensureActive()
            if (e is IOException || shouldRetryRead(e)) resetConnectionForRetry()
        } finally { cancelled.remove(id); taskSpeeds.remove(id); scheduledTransfers.remove(id, owner) }
    }

    private fun refreshSpeeds() {
        val average = batchSpeed?.average() ?: mutable.value.averageBytesPerSecond
        mutable.update { state -> state.copy(averageBytesPerSecond = average, transfers = state.transfers.map { transfer ->
            val meter = taskSpeeds[transfer.id]
            if (transfer.status == "running" && meter != null) transfer.copy(bytesPerSecond = meter.sample(), averageBytesPerSecond = meter.average())
            else transfer
        }) }
    }

    private fun updateTransfer(id: String, update: (UiTransfer) -> UiTransfer) = mutable.update { it.copy(transfers = it.transfers.map { t -> if (t.id == id) update(t) else t }) }

    private fun persistTransfers(strict: Boolean = false) {
        synchronized(transferPersistence) {
            try { transferStore.write(mutable.value.transfers) }
            catch (error: Exception) {
                mutable.update { state -> state.copy(message = "传输记录保存失败，已停止新的传输写入") }
                if (strict) throw IOException("续传记录无法保存，未开始新的写入", error)
            }
        }
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
            message.contains("connect", true) || message.contains("timeout", true) || error is java.net.SocketException -> "无法连接服务器，请检查网络、服务器地址和 SMB 端口"
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
