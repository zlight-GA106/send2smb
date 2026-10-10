package com.zlight.sendtosmb.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zlight.sendtosmb.SendToSmbApplication
import com.zlight.sendtosmb.data.SmbAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

enum class TextAutoSave(val label: String) { OFF("关闭"), LEAVE("离开页面时保存"), TIMED("每 30 秒保存") }
data class TextRecovery(val id: String, val name: String, val modified: Long)
data class EditorState(
    val name: String = "未命名.txt", val sourceLabel: String = "本地文档", val text: String = "",
    val dirty: Boolean = false, val busy: Boolean = true, val ready: Boolean = false,
    val status: String = "正在打开…", val error: Boolean = false, val page: Int = 0, val pages: Int = 1,
    val selection: Int = 0, val selectionEnd: Int = 0, val selectionVersion: Int = 0,
    val encoding: TextEncoding = TextEncoding.UTF8, val ending: String = "LF", val words: Long = 0,
    val characters: Long = 0, val lines: Long = 1, val cursorLine: Long = 1, val cursorColumn: Int = 1,
    val canUndo: Boolean = false, val canRedo: Boolean = false, val readOnly: Boolean = false,
    val wrap: Boolean = true, val fontSize: Int = 16, val lineSpacing: Float = 1.3f,
    val autoSave: TextAutoSave = TextAutoSave.OFF, val recoveries: List<TextRecovery> = emptyList(),
)

class TextEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("text_editor", 0)
    private val mutable = MutableStateFlow(EditorState(wrap = prefs.getBoolean("wrap", true),
        fontSize = prefs.getInt("font", 16).coerceIn(10, 32), lineSpacing = prefs.getFloat("spacing", 1.3f).coerceIn(1f, 2f),
        autoSave = runCatching { TextAutoSave.valueOf(prefs.getString("autosave", "OFF")!!) }.getOrDefault(TextAutoSave.OFF)))
    val state = mutable.asStateFlow()
    private val root = File(application.filesDir, "text-editor")
    @Volatile private var document: PagedTextDocument? = null
    private var currentDirectory: File? = null
    private var source = TextFileSource("未命名.txt")
    private var page = LineText.parse("")
    private val operation = Mutex()
    private val persistence = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draftRequests = Channel<Unit>(Channel.CONFLATED)
    private val cancel = AtomicBoolean(false)
    private var initialized = false
    private var visible = false
    private data class Edit(val page: Int, val before: LineText, val after: LineText, val cursorBefore: Int, val cursorAfter: Int)
    private data class History(val edit: Edit? = null, val before: File? = null, val after: File? = null)
    private val undo = ArrayDeque<History>()
    private val redo = ArrayDeque<History>()
    private var historySize = 0L

    init {
        persistence.launch {
            for (ignored in draftRequests) {
                val doc = document ?: continue
                try {
                    val checkpoint = doc.checkpoint()
                    if (doc.revision == checkpoint.revision && doc.dirty) mutable.update {
                        if (it.busy || it.error) it else it.copy(status = "草稿已保存在本机，尚未写回文件")
                    }
                } catch (error: Exception) { report("本机草稿保存失败：${error.message}") }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (visible && state.value.autoSave == TextAutoSave.TIMED && source.profileId != null && state.value.dirty && !state.value.busy && !state.value.readOnly) save()
            }
        }
    }

    fun open(intent: Intent) {
        if (initialized) return
        initialized = true
        task("正在缓存到本机…") {
            val restore = intent.getStringExtra("recovery")
            if (restore != null) {
                val dir = TextFileSource.session(root, restore)
                currentDirectory = dir
                source = TextFileSource.restore(dir)
                document = PagedTextDocument.restore(dir)
            } else {
                val dir = TextFileSource.newSession(root)
                currentDirectory = dir
                val cached = File(dir, "original.bin")
                val profileId = intent.getStringExtra("profile")
                val path = intent.getStringExtra("path").orEmpty()
                val name = intent.getStringExtra("name") ?: "未命名.txt"
                val uri = intent.data
                source = TextFileSource(name, profileId, path, localUri = uri?.toString())
                source.store(dir)
                when {
                    profileId != null && !intent.getBooleanExtra("new", false) -> {
                        val hash = TextSmbFiles.connected(profile(profileId)) { TextSmbFiles.cache(it, path, cached) }
                        source = source.copy(originalHash = hash)
                    }
                    uri != null -> {
                        require(uri.scheme == "content") { "只接受 Android 文档提供方的 content URI" }
                        val resolver = getApplication<Application>().contentResolver
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) source = source.copy(name = it.getString(0) ?: name)
                        }
                        resolver.openInputStream(uri)?.use { input -> copyToCache(input, cached) } ?: error("无法读取所选文本")
                        source = source.copy(originalHash = AtomicTextFiles.hash(cached))
                    }
                    intent.hasExtra("sharedText") -> copyToCache(ByteArrayInputStream(intent.getStringExtra("sharedText").orEmpty().toByteArray(Charsets.UTF_8)), cached)
                    else -> AtomicTextFiles.write(cached) { }
                }
                source.store(dir)
                document = PagedTextDocument.create(dir, cached)
            }
            withContext(Dispatchers.Main) {
                loadPage(0); refreshRecoveries()
                mutable.update { it.copy(status = when {
                    restore != null && it.dirty -> "已恢复本机草稿，尚未写回文件"
                    it.dirty -> "新建文档，尚未保存"
                    else -> "已缓存到本机"
                }) }
            }
        }
    }

    private fun copyToCache(input: InputStream, target: File) {
        AtomicTextFiles.write(target) { output ->
            val buffer = ByteArray(65536); var total = 0L
            while (true) {
                if (cancel.get()) throw CancellationException()
                val count = input.read(buffer); if (count < 0) break
                total += count
                require(total <= PagedTextDocument.MAX_FILE_BYTES) { "文档超过 256 MB，请拆分后编辑" }
                check(target.parentFile!!.usableSpace > count + 1024 * 1024) { "本机空间不足，未覆盖原文件" }
                output.write(buffer, 0, count)
            }
        }
    }

    fun edit(start: Int, removed: Int, insertion: String) {
        val doc = document ?: return
        if (state.value.readOnly || state.value.busy) return
        val before = page
        runCatching {
            val after = before.replace(start, start + removed, insertion, doc.defaultEnding)
            if (after == before) return
            doc.change(state.value.page, after)
            val cursor = start + LineText.parse(insertion).text.length
            val edit = Edit(state.value.page, before, after, state.value.selection, cursor)
            undo.addLast(History(edit)); historySize += (before.text.length + after.text.length) * 2L
            redo.clear()
            historySize = undo.sumOf { it.edit?.let { e -> (e.before.text.length + e.after.text.length) * 2L } ?: 0L }
            while (undo.isNotEmpty() && (undo.size > 100 || historySize > 8 * 1024 * 1024)) {
                val oldest = undo.removeFirst(); oldest.edit?.let { historySize -= (it.before.text.length + it.after.text.length) * 2L }
            }
            page = after
            refresh(cursor, cursor)
            draftRequests.trySend(Unit)
        }.onFailure { report(it.message ?: "编辑失败，原文已保留"); refresh(state.value.selection, state.value.selectionEnd, forceSelection = true) }
    }

    fun undo() = replay(false)
    fun redo() = replay(true)
    private fun replay(forward: Boolean) {
        if (state.value.readOnly || state.value.busy) return
        val from = if (forward) redo else undo
        val to = if (forward) undo else redo
        val history = from.pollLast() ?: return
        val edit = history.edit
        if (edit == null) {
            task("正在恢复替换记录…") {
                document!!.restoreBackup((if (forward) history.after else history.before)!!)
                withContext(Dispatchers.Main) { to.addLast(history); loadPage(state.value.page.coerceAtMost(document!!.pageCount - 1)) }
            }
            return
        }
        val text = if (forward) edit.after else edit.before
        document!!.change(edit.page, text)
        to.addLast(history)
        page = text
        mutable.update { it.copy(page = edit.page) }
        val cursor = if (forward) edit.cursorAfter else edit.cursorBefore
        refresh(cursor, cursor, true)
        draftRequests.trySend(Unit)
    }

    fun selection(start: Int, end: Int) { if (state.value.ready) refresh(start.coerceIn(0, page.text.length), end.coerceIn(0, page.text.length)) }
    fun cursor(direction: Int) {
        val at = state.value.selectionEnd.coerceIn(0, page.text.length)
        val text = page.text
        val next = when (direction) {
            -1 -> if (at > 0) text.offsetByCodePoints(at, -1) else 0
            1 -> if (at < text.length) text.offsetByCodePoints(at, 1) else text.length
            else -> {
                val start = text.lastIndexOf('\n', (at - 1).coerceAtLeast(-1)) + 1
                val column = at - start
                if (direction == -2) {
                    val previousEnd = (start - 1).coerceAtLeast(0)
                    val previousStart = text.lastIndexOf('\n', (previousEnd - 1).coerceAtLeast(-1)) + 1
                    minOf(previousStart + column, previousEnd)
                } else {
                    val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
                    val following = (end + 1).coerceAtMost(text.length)
                    val followingEnd = text.indexOf('\n', following).let { if (it < 0) text.length else it }
                    minOf(following + column, followingEnd)
                }
            }
        }
        // Vertical movement uses UTF-16 offsets; never leave the caret inside an emoji pair.
        val safe = if (next in 1 until text.length && text[next].isLowSurrogate() && text[next - 1].isHighSurrogate()) next - 1 else next
        refresh(safe, safe, true)
    }

    fun page(index: Int) {
        if (index !in 0 until state.value.pages || state.value.busy) return
        task("正在读取页面…") { withContext(Dispatchers.Main) { loadPage(index); mutable.update { it.copy(status = "第 ${index + 1} 页") } } }
    }
    private fun loadPage(index: Int, selection: Int = 0, end: Int = selection) {
        val doc = document ?: return
        page = doc.readPage(index)
        mutable.update { it.copy(page = index, ready = true, pages = doc.pageCount) }
        refresh(selection, end.coerceAtMost(page.text.length), true)
    }
    private fun refresh(start: Int, end: Int, forceSelection: Boolean = false) {
        val doc = document ?: return
        val counts = doc.counts()
        val prefix = page.text.take(end)
        val priorLines = doc.breaksBefore(state.value.page)
        mutable.update { it.copy(name = source.name, sourceLabel = if (source.profileId != null) "SMB · ${source.path}" else "本地缓存 · 另存到设备",
            text = page.text, dirty = doc.dirty || (source.profileId != null && source.originalHash == null),
            encoding = doc.encoding, ending = doc.endingLabel(),
            words = counts.words, characters = counts.characters, lines = counts.breaks + 1,
            cursorLine = priorLines + prefix.count { it == '\n' } + 1,
            cursorColumn = prefix.substringAfterLast('\n').codePointCount(0, prefix.substringAfterLast('\n').length) + 1,
            selection = start.coerceIn(0, page.text.length), selectionEnd = end.coerceIn(0, page.text.length),
            selectionVersion = it.selectionVersion + if (forceSelection) 1 else 0, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
    }

    fun find(query: String, ignoreCase: Boolean, replace: String? = null) {
        if (!state.value.ready || state.value.busy) return
        task("正在查找…") {
            val doc = document!!
            val selectedStart = minOf(state.value.selection, state.value.selectionEnd)
            val selectedEnd = maxOf(state.value.selection, state.value.selectionEnd)
            val selected = page.text.substring(selectedStart, selectedEnd)
            val match = (if (replace != null && selected.equals(query, ignoreCase)) TextMatch(state.value.page, selectedStart, selectedEnd) else null)
                ?: doc.find(query, state.value.page, state.value.selectionEnd, ignoreCase)
                ?: error("没有找到“$query”")
            if (replace != null && match.end > doc.readPage(match.page).text.length) {
                if (state.value.readOnly) error("文档处于只读模式")
                val before = doc.backup()
                try {
                    doc.replaceMatch(match, replace)
                    val after = doc.backup()
                    withContext(Dispatchers.Main) {
                        undo.addLast(History(before = before, after = after)); redo.clear()
                        loadPage(match.page, match.start + LineText.parse(replace).text.length)
                        mutable.update { it.copy(status = "已替换跨页内容，可撤销", error = false) }
                    }
                } catch (error: Exception) { doc.restoreBackup(before); throw error }
                return@task
            }
            withContext(Dispatchers.Main) {
                loadPage(match.page, match.start, match.end)
                if (replace != null) {
                    if (state.value.readOnly) error("文档处于只读模式")
                    mutable.update { it.copy(busy = false) }
                    edit(match.start, match.end - match.start, replace)
                    mutable.update { it.copy(busy = true) }
                }
                mutable.update { it.copy(status = "已定位匹配内容", error = false) }
            }
        }
    }
    fun replaceAll(query: String, replacement: String, ignoreCase: Boolean) {
        if (state.value.readOnly || state.value.busy || query.isEmpty()) return
        task("正在替换…") {
            require(query.length <= 4096)
            val doc = document!!
            val before = doc.backup()
            var count = 0
            try {
                var currentPage = 0; var offset = 0; var previousPage = 0
                while (currentPage < doc.pageCount) {
                    val match = doc.find(query, currentPage, offset, ignoreCase, wrap = false) ?: break
                    if (match.page != previousPage) { doc.checkpoint(); previousPage = match.page }
                    doc.replaceMatch(match, replacement)
                    currentPage = match.page
                    offset = match.start + LineText.parse(replacement).text.length
                    count++
                }
                val after = doc.backup()
                withContext(Dispatchers.Main) {
                    undo.addLast(History(before = before, after = after)); redo.clear()
                    loadPage(state.value.page)
                    mutable.update { it.copy(status = "已替换 $count 处，可撤销", error = false) }
                }
            } catch (error: Exception) { doc.restoreBackup(before); throw error }
        }
    }
    fun goToLine(line: Long) = task("正在跳转…") {
        val target = document!!.linePosition(line)
        withContext(Dispatchers.Main) { loadPage(target.first, target.second); mutable.update { it.copy(status = "已跳转到第 $line 行") } }
    }

    fun encoding(value: TextEncoding) {
        val doc = document
        if (doc != null) {
            if (state.value.readOnly || state.value.busy) return
            doc.setEncoding(value); refresh(state.value.selection, state.value.selectionEnd); draftRequests.trySend(Unit)
        } else task("正在按所选编码重新打开…") {
            val dir = currentDirectory ?: error("没有可重新打开的缓存")
            source = TextFileSource.restore(dir)
            document = PagedTextDocument.create(dir, File(dir, "original.bin"), value)
            withContext(Dispatchers.Main) { loadPage(0) }
        }
    }

    fun save(asName: String? = null, onSaved: (() -> Unit)? = null) {
        if (!state.value.ready || state.value.busy || state.value.readOnly) return
        task("正在安全保存到 SMB…") {
            val doc = document!!
            val currentSource = source
            val id = currentSource.profileId ?: error("请使用另存到设备")
            val target = if (asName != null) {
                val name = if (asName.endsWith(".txt", true)) asName else "$asName.txt"
                SmbAddress.validateName(name)
                currentSource.copy(name = name, path = SmbAddress.childPath(SmbAddress.parentPath(currentSource.path), name), originalHash = null)
            } else currentSource
            val (snapshot, export) = doc.prepareSave()
            try {
                val hash = TextSmbFiles.connected(profile(id)) { TextSmbFiles.save(it, target, export) { cancel.get() } }
                source = target.copy(originalHash = hash)
                source.store(doc.directory)
                doc.markSaved(snapshot.revision)
                withContext(Dispatchers.Main) {
                    refresh(state.value.selection, state.value.selectionEnd)
                    mutable.update { it.copy(status = "已保存到 SMB", error = false) }
                    onSaved?.invoke()
                }
            } finally { export.delete() }
        }
    }

    fun saveLocal(uri: Uri, onSaved: (() -> Unit)? = null) = task("正在另存到设备…") {
        val doc = document ?: error("文档尚未打开")
        val resolver = getApplication<Application>().contentResolver
        val (snapshot, export) = doc.prepareSave()
        try {
            resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    export.inputStream().use { it.copyTo(output) }; output.flush()
                    // Some providers return pipes; they cannot fsync. Read-back below verifies bytes.
                    if (descriptor.statSize >= 0) output.fd.sync()
                }
            } ?: error("无法写入所选位置")
            val expected = AtomicTextFiles.hash(export)
            val actual = resolver.openInputStream(uri)?.use { AtomicTextFiles.hash(it) } ?: error("无法验证保存结果，草稿已保留")
            check(expected == actual) { "保存后的校验不一致，草稿已保留" }
            var name = source.name
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
            source = TextFileSource(name, originalHash = expected, localUri = uri.toString())
            source.store(doc.directory)
            doc.markSaved(snapshot.revision)
            withContext(Dispatchers.Main) {
                refresh(state.value.selection, state.value.selectionEnd)
                mutable.update { it.copy(status = "已另存到设备并校验", error = false) }
                onSaved?.invoke()
            }
        } finally { export.delete() }
    }

    fun readOnly(value: Boolean) { mutable.update { it.copy(readOnly = value) } }
    fun wrap(value: Boolean) { prefs.edit().putBoolean("wrap", value).apply(); mutable.update { it.copy(wrap = value) } }
    fun font(value: Int) { prefs.edit().putInt("font", value).apply(); mutable.update { it.copy(fontSize = value) } }
    fun spacing(value: Float) { prefs.edit().putFloat("spacing", value).apply(); mutable.update { it.copy(lineSpacing = value) } }
    fun autoSave(value: TextAutoSave) { prefs.edit().putString("autosave", value.name).apply(); mutable.update { it.copy(autoSave = value) } }
    fun onVisible(value: Boolean) {
        visible = value
        if (!value) {
            draftRequests.trySend(Unit)
            if (state.value.autoSave == TextAutoSave.LEAVE && source.profileId != null && state.value.dirty && !state.value.busy) save()
        }
    }
    fun share(onReady: (File) -> Unit) = task("正在准备分享副本…") {
        val doc = document ?: error("文档尚未打开")
        val (_, export) = doc.prepareSave()
        try {
            val safeName = source.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(180).ifBlank { "文本.txt" }
            val target = File(getApplication<Application>().cacheDir, "text-exports/${java.util.UUID.randomUUID()}/$safeName")
            AtomicTextFiles.write(target) { output -> export.inputStream().use { it.copyTo(output) } }
            withContext(Dispatchers.Main) { mutable.update { it.copy(status = "分享副本已准备好") }; onReady(target) }
        } finally { export.delete() }
    }
    fun hasSmbSource() = source.profileId != null
    fun sessionId() = document?.directory?.name
    fun report(message: String) { mutable.update { it.copy(status = message, error = true) } }
    private fun profile(id: String) = (getApplication<Application>() as SendToSmbApplication).explorer.state.value.profiles.firstOrNull { it.id == id }
        ?: error("原 SMB 连接已被删除，草稿已保留；请另存到设备")

    private fun refreshRecoveries() {
        val recoveries = root.listFiles()?.filter { it != currentDirectory }?.mapNotNull { dir -> runCatching {
            val d = PagedTextDocument.restore(dir); val s = TextFileSource.restore(dir)
            if (d.dirty || (s.profileId != null && s.originalHash == null)) TextRecovery(dir.name, s.name, File(dir, "document.properties").lastModified()) else null
        }.getOrNull() }?.sortedByDescending { it.modified }.orEmpty()
        mutable.update { it.copy(recoveries = recoveries) }
    }
    private fun task(label: String, block: suspend () -> Unit) {
        if (state.value.busy && initialized && document != null) return
        mutable.update { it.copy(busy = true, status = label, error = false) }
        cancel.set(false)
        viewModelScope.launch {
            try { operation.withLock { withContext(Dispatchers.IO) { block() } } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                report(error.message ?: "操作失败，草稿已保留")
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() {
        cancel.set(true)
        persistence.launch { runCatching { document?.checkpoint() }; persistence.cancel() }
        draftRequests.close()
        super.onCleared()
    }
}
