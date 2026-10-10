package com.zlight.sendtosmb.editor

import com.zlight.sendtosmb.data.SmbAddress
import com.zlight.sendtosmb.data.SmbRepository
import com.zlight.sendtosmb.ui.UiProfile
import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

data class TextFileSource(val name: String, val profileId: String? = null, val path: String = "",
    val originalHash: String? = null, val localUri: String? = null) {
    fun store(directory: File) {
        val props = Properties().apply {
            setProperty("name", name); setProperty("path", path)
            profileId?.let { setProperty("profile", it) }; originalHash?.let { setProperty("hash", it) }
            localUri?.let { setProperty("uri", it) }
        }
        AtomicTextFiles.write(File(directory, "source.properties")) { props.store(it, "No credentials stored here") }
    }
    companion object {
        fun restore(directory: File): TextFileSource {
            val p = Properties().apply { File(directory, "source.properties").inputStream().use { load(it) } }
            return TextFileSource(p.getProperty("name", "未命名.txt"), p.getProperty("profile"), p.getProperty("path", ""), p.getProperty("hash"), p.getProperty("uri"))
        }
        fun newSession(root: File): File = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        fun session(root: File, id: String): File {
            require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "无效草稿" }
            return File(root, id)
        }
    }
}

/** Independent, short-lived SMB sessions: local editing does not hold the explorer's socket. */
object TextSmbFiles {
    fun <T> connected(profile: UiProfile, block: (SmbRepository) -> T): T = SmbRepository().use { smb ->
        smb.connect(SmbAddress.parse(profile.url), profile.username, profile.password, profile.domain)
        block(smb)
    }
    fun cache(smb: SmbRepository, path: String, target: File): String {
        val before = entry(smb, path)
        require(!before.isDirectory && !before.isReparsePoint) { "文本编辑不跟随链接或目录" }
        require(before.size <= PagedTextDocument.MAX_FILE_BYTES) { "文档超过 256 MB，请拆分后编辑" }
        AtomicTextFiles.write(target) { output ->
            val bounded = object : FilterOutputStream(output) {
                var bytes = 0L
                override fun write(b: ByteArray, off: Int, len: Int) {
                    bytes += len
                    require(bytes <= PagedTextDocument.MAX_FILE_BYTES) { "文档超过 256 MB" }
                    out.write(b, off, len)
                }
            }
            smb.download(path, bounded, expectedBytes = before.size)
        }
        val after = entry(smb, path)
        check(before.size == after.size && before.lastModified == after.lastModified) { "文件在缓存期间发生变化，请重新打开" }
        return AtomicTextFiles.hash(target)
    }
    fun save(smb: SmbRepository, source: TextFileSource, file: File, cancelled: () -> Boolean = { false }): String {
        val hash = AtomicTextFiles.hash(file)
        fun verify() {
            val current = entry(smb, source.path)
            require(!current.isDirectory && !current.isReparsePoint) { "原文件已变成目录或链接，拒绝覆盖" }
            val digest = MessageDigest.getInstance("SHA-256")
            val sink = object : OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }
            DigestOutputStream(sink, digest).use { output -> smb.download(source.path, output, cancelled, expectedBytes = current.size) }
            val remoteHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(remoteHash == source.originalHash) { "远程文件已被修改，未覆盖原件；草稿已保留，请使用另存为" }
        }
        if (source.originalHash != null) {
            verify()
            if (hash == source.originalHash) return hash // No-op saves never rewrite timestamps/BOM/newlines.
        }
        file.inputStream().use { input -> smb.upload(source.path, input, file.length(), cancelled,
            overwrite = source.originalHash != null, beforeCommit = { if (source.originalHash != null) verify() }) }
        return hash
    }
    private fun entry(smb: SmbRepository, path: String) = smb.list(SmbAddress.parentPath(path)).firstOrNull { it.path == path }
        ?: error("原文件已不存在，草稿已保留，请使用另存为")
}
