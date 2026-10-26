package com.zlight.sendtosmb

import com.zlight.sendtosmb.data.ResumeConflictException
import com.zlight.sendtosmb.data.ResumeRemoteFile
import com.zlight.sendtosmb.data.SmbRepository
import com.zlight.sendtosmb.editor.AtomicTextFiles
import com.zlight.sendtosmb.ui.UiFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

data class ResumeDocument(val uri: String, val name: String, val directory: Boolean, val size: Long = -1)

/** SAF is an adapter: the transfer plan and commit decisions can be tested on the host. */
interface ResumeDocuments {
    fun stat(uri: String): ResumeDocument?
    fun children(uri: String): List<ResumeDocument>
    fun persistentRead(uri: String): Boolean
    fun open(uri: String): InputStream
    fun create(parent: String, name: String, directory: Boolean): String
    fun write(uri: String, source: File, cancelled: () -> Boolean)
    fun rename(uri: String, name: String): String
    fun delete(uri: String)
}

/** Each descriptor change must be durably saved before the corresponding external operation. */
class ResumeTransferEngine(private val id: String, private val workspace: File,
    private val documents: ResumeDocuments, private val current: () -> TransferResume,
    private val checkpoint: (TransferResume) -> Unit, private val phase: (String) -> Unit = {}) {

    fun run(direction: String, source: UiFile?, smb: SmbRepository, cancelled: () -> Boolean,
            progress: (Long, Long) -> Unit) {
        workspace.mkdirs()
        active(cancelled)
        if (direction == "upload") upload(smb, cancelled, progress)
        else download(source ?: error("下载任务缺少源文件"), smb, cancelled, progress)
    }

    private fun upload(smb: SmbRepository, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        var record = current()
        if (record.targetName.isEmpty()) {
            val sourceInfo = documents.stat(record.sourceUri ?: error("上传来源已失效")) ?: error("上传来源无法读取，请重新授权后继续")
            val name = uniqueName(validName(sourceInfo.name),
                smb.list(record.remotePath).map { it.name.lowercase() }.toSet())
            record = record.copy(remotePath = join(record.remotePath, name), targetName = name)
            checkpoint(record)
        }
        if (record.sha256 != null && smb.list(com.zlight.sendtosmb.data.SmbAddress.parentPath(record.remotePath)).any { it.path == record.remotePath }) {
            // A previous rename can be committed even when the completion journal write was lost.
            smb.uploadResumable(record.remotePath, id, { error("已提交任务不应再次读取源文件") }, record.size, record.sha256, cancelled, progress)
            smb.discardUploadPartial(record.remotePath, id)
            return
        }
        val uri = record.sourceUri ?: error("上传来源已失效")
        if (record.uploadCacheName == null && !documents.persistentRead(uri)) {
            record = record.copy(uploadCacheName = "$id.upload")
            checkpoint(record)
        }
        val cache = record.uploadCacheName?.let { localFile(it.removeSuffix(".upload"), ".upload") }
        if (cache != null && !cache.exists()) {
            phase("正在缓存文件来源，以便应用重启后继续…")
            AtomicTextFiles.write(cache) { output ->
                documents.open(uri).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        active(cancelled)
                        val count = input.read(buffer); if (count < 0) break
                        check(count > 0) { "文件来源未返回数据，缓存未完成" }
                        check(workspace.usableSpace > count + 1024 * 1024) { "本机空间不足，无法缓存该文件来源" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        val open = { cache?.inputStream() ?: documents.open(uri) }
        phase("正在校验源文件与续传片段…")
        val sourceIdentity = open().use { digest(it, cancelled) }
        if (record.sha256 != null && (record.sha256 != sourceIdentity.second || record.size != sourceIdentity.first))
            throw ResumeConflictException("上传源文件已经变化，原断点已保留；请重新选择源文件创建任务")
        if (record.sha256 == null) {
            record = record.copy(size = sourceIdentity.first, sha256 = sourceIdentity.second)
            checkpoint(record)
        }
        phase("正在上传；完成后校验并提交文件…")
        smb.uploadResumable(record.remotePath, id, open, record.size, record.sha256!!, cancelled, progress)
        smb.discardUploadPartial(record.remotePath, id)
    }

    private fun download(source: UiFile, smb: SmbRepository, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        var record = current()
        val tree = record.treeUri ?: error("下载保存位置已失效，请重新选择目录创建任务")
        if (record.targetName.isEmpty()) {
            record = record.copy(targetName = uniqueName(validName(source.name), documents.children(tree).map { it.name.lowercase() }.toSet()))
            checkpoint(record)
        }
        if (!record.planReady) {
            phase("正在记录文件夹清单…")
            if (source.isDirectory) {
                val original = smb.list(com.zlight.sendtosmb.data.SmbAddress.parentPath(source.path)).firstOrNull { it.path == source.path }
                    ?: error("源文件夹已不存在")
                require(original.isDirectory && !original.isReparsePoint) { "文件夹下载不跟随符号链接" }
            }
            val entries = arrayListOf<ResumeDownloadEntry>()
            fun collect(file: UiFile, relative: String, depth: Int) {
                active(cancelled)
                require(depth < 128) { "文件夹层级过深" }
                require(entries.size < 100_000) { "单个文件夹任务超过 100000 个项目，请分批下载" }
                if (relative.isNotEmpty()) entries.add(ResumeDownloadEntry(UUID.randomUUID().toString(), file, relative))
                if (file.isDirectory) smb.list(file.path).forEach { child ->
                    require(!child.isReparsePoint) { "文件夹下载不跟随符号链接" }
                    collect(UiFile(child.name, child.path, child.isDirectory, child.size, child.lastModified),
                        join(relative, validName(child.name)), depth + 1)
                }
            }
            if (source.isDirectory) collect(source, "", 0) else collect(source, record.targetName, 0)
            record = record.copy(planReady = true, entries = entries)
            checkpoint(record)
        }
        val root = if (source.isDirectory) ensureRootFolder(tree, cancelled) else tree
        verifyCompleted(root, current(), cancelled)
        val total = current().entries.filterNot { it.source.isDirectory }.sumOf { it.source.size }
        var done = current().entries.filter { it.completed && !it.source.isDirectory }.sumOf { it.source.size }
        val restoredBytes = current().entries.filter { !it.completed && !it.source.isDirectory }.sumOf {
            localFile(it.id, ".part").takeIf { file -> file.isFile }?.length()?.coerceAtMost(it.source.size) ?: 0
        }
        progress(done + restoredBytes, total)
        for (entrySnapshot in current().entries) {
            active(cancelled)
            var entry = current().entries.first { it.id == entrySnapshot.id }
            if (entry.completed) continue
            val parentRelative = entry.relativePath.substringBeforeLast('/', "")
            val parent = if (parentRelative.isEmpty()) root else current().entries.firstOrNull {
                it.relativePath == parentRelative && it.source.isDirectory && it.completed
            }?.targetUri ?: error("下载文件夹计划损坏，断点已保留")
            if (entry.source.isDirectory) {
                val name = entry.relativePath.substringAfterLast('/')
                val directory = entry.targetUri?.takeIf { documents.stat(it)?.directory == true }
                    ?: documents.children(parent).firstOrNull { it.name == name && it.directory }?.uri
                    ?: documents.create(parent, name, true)
                updateEntry(entry.copy(targetUri = directory, completed = true))
                continue
            }
            phase("正在校验 ${entry.source.name} 的源文件…")
            if (entry.sha256 == null) {
                val fingerprint = smb.resumeFingerprint(entry.source.path, cancelled)
                if (fingerprint.size != entry.source.size || fingerprint.lastModified != entry.source.modifiedMillis)
                    throw ResumeConflictException("远程源文件已变化，请重新创建下载任务")
                entry = entry.copy(sha256 = fingerprint.sha256)
                updateEntry(entry)
            }
            val part = localFile(entry.id, ".part")
            phase("正在下载 ${entry.source.name}，已有片段将先校验…")
            smb.downloadResumable(entry.source.path, part, ResumeRemoteFile(entry.source.size, entry.source.modifiedMillis, entry.sha256!!),
                cancelled) { bytes, _ -> progress(done + bytes, total) }
            phase("正在校验并保存 ${entry.source.name} 到设备…")
            val published = publish(entry, parent, part, cancelled)
            updateEntry(entry.copy(targetUri = published, completed = true))
            part.delete()
            done += entry.source.size
            progress(done, total)
        }
        if (source.isDirectory) {
            val name = current().targetName
            val info = documents.stat(root) ?: error("下载目标文件夹已不存在")
            if (info.name != name) {
                check(documents.children(tree).none { it.name.equals(name, true) && it.uri != root }) { "目标文件夹名称已被占用，已完成文件保留在临时文件夹中" }
                val renamed = documents.rename(root, name)
                checkpoint(current().copy(targetUri = renamed))
            }
        }
        progress(total, total)
    }

    private fun ensureRootFolder(tree: String, cancelled: () -> Boolean): String {
        val record = current()
        record.targetUri?.let { if (documents.stat(it)?.directory == true) return it }
        val marker = ".sendtosmb-dir-$id"
        val children = documents.children(tree)
        val root = children.firstOrNull { it.name == marker && it.directory }?.uri
            ?: if (record.entries.all { it.completed }) {
                children.firstOrNull { it.name == record.targetName && it.directory && verifyFolder(it.uri, record, cancelled) }?.uri
            } else null
        if (root == null && record.targetUri != null) throw ResumeConflictException("原下载文件夹已被移动或删除，已完成文件记录保留；请重新创建任务")
        val uri = root ?: documents.create(tree, marker, true)
        checkpoint(record.copy(targetUri = uri))
        return uri
    }

    private fun verifyFolder(root: String, record: TransferResume, cancelled: () -> Boolean): Boolean {
        try { verifyCompleted(root, record, cancelled); return true }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { return false }
    }

    private fun verifyCompleted(root: String, record: TransferResume, cancelled: () -> Boolean) {
        val folders = mutableMapOf("" to root)
        record.entries.filter { it.completed }.forEach { entry ->
            active(cancelled)
            val parent = folders[entry.relativePath.substringBeforeLast('/', "")] ?: throw ResumeConflictException("已完成下载的父文件夹已不存在，断点记录保留")
            val child = documents.children(parent).firstOrNull { it.name == entry.relativePath.substringAfterLast('/') }
                ?: throw ResumeConflictException("已完成下载的文件已被移动或删除，断点记录保留")
            if (entry.source.isDirectory) {
                if (!child.directory) throw ResumeConflictException("已完成的文件夹已变化")
                folders[entry.relativePath] = child.uri
            } else if (child.directory || !matches(child.uri, entry.source.size, entry.sha256!!, cancelled))
                throw ResumeConflictException("已完成的下载文件被修改，未覆盖设备上的内容")
        }
    }

    private fun publish(entry: ResumeDownloadEntry, parent: String, part: File, cancelled: () -> Boolean): String {
        val name = entry.relativePath.substringAfterLast('/')
        val marker = ".sendtosmb-file-${entry.id}.part"
        val existing = documents.children(parent)
        existing.firstOrNull { it.name.equals(name, true) }?.let {
            if (!it.directory && matches(it.uri, entry.source.size, entry.sha256!!, cancelled)) return it.uri
            throw ResumeConflictException("设备目标文件已存在且内容不同，下载缓存已保留")
        }
        val uri = entry.targetUri?.takeIf { documents.stat(it)?.name == marker }
            ?: existing.firstOrNull { it.name == marker && !it.directory }?.uri
            ?: documents.create(parent, marker, false)
        updateEntry(entry.copy(targetUri = uri))
        documents.write(uri, part, cancelled)
        check(matches(uri, entry.source.size, entry.sha256!!, cancelled)) { "设备写入校验失败，完整下载缓存已保留" }
        active(cancelled)
        check(documents.children(parent).none { it.name.equals(name, true) && it.uri != uri }) { "设备目标名称已被占用，下载缓存已保留" }
        return documents.rename(uri, name).also { check(documents.stat(it)?.name == name) { "保存位置未保留目标文件名，文件已保留" } }
    }

    /** Removes only this task's own partials; published files and completed folder children remain. */
    fun discard(smb: SmbRepository?) {
        val record = current()
        if (record.sourceUri != null && record.targetName.isNotEmpty())
            (smb ?: error("请连接原服务器后再清理上传断点")).discardUploadPartial(record.remotePath, id)
        record.entries.filterNot { it.completed }.forEach { entry ->
            entry.targetUri?.let { uri ->
                if (documents.stat(uri)?.name == ".sendtosmb-file-${entry.id}.part") documents.delete(uri)
            }
        }
        if (record.treeUri != null && record.targetUri != null) {
            val root = documents.stat(record.targetUri)
            if (root?.directory == true && root.name == ".sendtosmb-dir-$id") {
                if (documents.children(root.uri).isEmpty()) documents.delete(root.uri)
                else if (documents.children(record.treeUri).none { it.name.equals(record.targetName, true) })
                    documents.rename(root.uri, record.targetName)
            }
        }
        cleanupLocal()
    }

    fun cleanupLocal() {
        fun remove(file: File) { check(!file.exists() || file.delete()) { "本机断点缓存清理失败，请重试" } }
        current().entries.forEach { remove(localFile(it.id, ".part")) }
        current().uploadCacheName?.let { remove(localFile(it.removeSuffix(".upload"), ".upload")) }
        val cacheTemporary = Regex("\\.${Regex.escape(id)}\\.upload-[0-9a-f-]{36}\\.tmp")
        workspace.listFiles()?.filter { it.isFile && cacheTemporary.matches(it.name) }?.forEach(::remove)
    }
    private fun updateEntry(entry: ResumeDownloadEntry) = checkpoint(current().copy(entries = current().entries.map { if (it.id == entry.id) entry else it }))
    private fun localFile(key: String, suffix: String): File {
        require(UUID.fromString(key).toString() == key) { "无效的本机断点 ID" }
        return File(workspace, key + suffix).also { require(it.canonicalFile.parentFile == workspace.canonicalFile) }
    }
    private fun matches(uri: String, size: Long, hash: String, cancelled: () -> Boolean) =
        documents.open(uri).use { digest(it, cancelled) }.let { it.first == size && it.second == hash }
    private fun digest(input: InputStream, cancelled: () -> Boolean): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(256 * 1024); var size = 0L
        while (true) { active(cancelled); val count = input.read(buffer); if (count < 0) break; check(count > 0) { "文件来源未返回数据" }; digest.update(buffer, 0, count); size += count }
        return size to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun active(cancelled: () -> Boolean) { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("传输已暂停，断点已保留") }
}
