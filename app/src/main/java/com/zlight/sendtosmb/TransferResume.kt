package com.zlight.sendtosmb

import com.zlight.sendtosmb.data.SmbAddress
import com.zlight.sendtosmb.ui.UiFile
import com.zlight.sendtosmb.ui.UiTransfer
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.util.Properties
import java.util.UUID

/** Durable transfer identity. Credentials remain in ProfileStore, never in this descriptor. */
data class TransferResume(
    val serverUrl: String,
    val username: String,
    val domain: String,
    val remotePath: String,
    val targetName: String,
    val sourceUri: String? = null,
    val treeUri: String? = null,
    val targetUri: String? = null,
    val sha256: String? = null,
    val size: Long = -1,
    val modified: Long = -1,
    val uploadCacheName: String? = null,
    val uploadGrantOwned: Boolean = false,
    val planReady: Boolean = false,
    val entries: List<ResumeDownloadEntry> = emptyList(),
)

data class ResumeDownloadEntry(
    val id: String,
    val source: UiFile,
    val relativePath: String,
    val sha256: String? = null,
    val targetUri: String? = null,
    val completed: Boolean = false,
)

/** Pure codec, also exercised by host JVM tests without Android JSON/Keystore stubs. */
internal object TransferResumeCodec {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val digest = Regex("[0-9a-fA-F]{64}")
    private const val MAX_ENTRIES = 100_000

    fun validId(value: String): Boolean = uuid.matches(value) && runCatching { UUID.fromString(value) }.isSuccess

    fun validate(value: TransferResume) {
        SmbAddress.parse(value.serverUrl)
        SmbAddress.normalizeRelativePath(value.remotePath)
        if (value.targetName.isNotEmpty()) SmbAddress.validateName(value.targetName)
        value.sourceUri?.let(::contentUri)
        value.treeUri?.let(::contentUri)
        value.targetUri?.let(::contentUri)
        value.sha256?.let { require(digest.matches(it)) { "续传校验值无效" } }
        require(value.size >= -1 && value.modified >= -1) { "续传文件信息无效" }
        value.uploadCacheName?.let {
            require(it.endsWith(".upload") && validId(it.removeSuffix(".upload"))) { "续传缓存名称无效" }
        }
        require(value.entries.size <= MAX_ENTRIES) { "续传目录包含过多项目，请分批传输" }
        val ids = HashSet<String>()
        val paths = HashSet<String>()
        value.entries.forEach { entry ->
            require(validId(entry.id) && ids.add(entry.id.lowercase())) { "续传项目标识无效或重复" }
            path(entry.relativePath)
            require(paths.add(SmbAddress.normalizeRelativePath(entry.relativePath))) { "续传项目路径重复" }
            SmbAddress.validateName(entry.source.name)
            path(entry.source.path)
            require(entry.source.size >= 0 && entry.source.modifiedMillis >= -1) { "续传源文件信息无效" }
            entry.sha256?.let { require(digest.matches(it)) { "续传项目校验值无效" } }
            entry.targetUri?.let(::contentUri)
        }
    }

    fun encode(value: TransferResume): String {
        validate(value)
        val properties = Properties()
        fun put(key: String, item: Any?) { if (item != null) properties.setProperty(key, item.toString()) }
        put("version", 1)
        put("serverUrl", value.serverUrl)
        put("username", value.username)
        put("domain", value.domain)
        put("remotePath", value.remotePath)
        put("targetName", value.targetName)
        put("sourceUri", value.sourceUri)
        put("treeUri", value.treeUri)
        put("targetUri", value.targetUri)
        put("sha256", value.sha256)
        put("size", value.size)
        put("modified", value.modified)
        put("uploadCacheName", value.uploadCacheName)
        put("uploadGrantOwned", value.uploadGrantOwned)
        put("planReady", value.planReady)
        put("entries", value.entries.size)
        value.entries.forEachIndexed { index, entry ->
            val prefix = "entry.$index."
            put(prefix + "id", entry.id)
            put(prefix + "name", entry.source.name)
            put(prefix + "path", entry.source.path)
            put(prefix + "directory", entry.source.isDirectory)
            put(prefix + "size", entry.source.size)
            put(prefix + "modified", entry.source.modifiedMillis)
            put(prefix + "relativePath", entry.relativePath)
            put(prefix + "sha256", entry.sha256)
            put(prefix + "targetUri", entry.targetUri)
            put(prefix + "completed", entry.completed)
        }
        return StringWriter().also { properties.store(it, "SendToSMB transfer resume") }.toString()
    }

    fun decode(encoded: String): TransferResume {
        val properties = Properties().apply { load(StringReader(encoded)) }
        fun required(key: String): String = requireNotNull(properties.getProperty(key)) { "续传记录缺少 $key" }
        fun number(key: String): Long = requireNotNull(required(key).toLongOrNull()) { "续传记录数字无效" }
        fun boolean(key: String): Boolean = requireNotNull(required(key).toBooleanStrictOrNull()) { "续传记录状态无效" }
        require(required("version") == "1") { "续传记录版本不受支持" }
        val count = number("entries")
        require(count in 0..MAX_ENTRIES.toLong()) { "续传目录包含过多项目" }
        val result = TransferResume(
            serverUrl = required("serverUrl"), username = required("username"), domain = required("domain"),
            remotePath = required("remotePath"), targetName = required("targetName"),
            sourceUri = properties.getProperty("sourceUri"), treeUri = properties.getProperty("treeUri"),
            targetUri = properties.getProperty("targetUri"), sha256 = properties.getProperty("sha256"),
            size = number("size"), modified = number("modified"), uploadCacheName = properties.getProperty("uploadCacheName"),
            uploadGrantOwned = boolean("uploadGrantOwned"), planReady = boolean("planReady"),
            entries = (0 until count.toInt()).map { index ->
                val prefix = "entry.$index."
                ResumeDownloadEntry(
                    id = required(prefix + "id"),
                    source = UiFile(required(prefix + "name"), required(prefix + "path"),
                        boolean(prefix + "directory"), number(prefix + "size"), number(prefix + "modified")),
                    relativePath = required(prefix + "relativePath"), sha256 = properties.getProperty(prefix + "sha256"),
                    targetUri = properties.getProperty(prefix + "targetUri"), completed = boolean(prefix + "completed"),
                )
            },
        )
        validate(result)
        return result
    }

    private fun path(value: String) {
        require(SmbAddress.normalizeRelativePath(value).isNotEmpty()) { "续传路径不能为空" }
    }

    private fun contentUri(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() && uri.rawFragment == null) { "续传文件权限地址无效" }
    }
}

/** Resume state is never evicted with the bounded completed-transfer history. */
internal object TransferHistoryPolicy {
    private val active = setOf("queued", "running", "discarding")

    fun forPersistence(transfers: List<UiTransfer>, maxHistory: Int = 200): List<UiTransfer> {
        require(maxHistory >= 0)
        val durable = transfers.filter { it.resume != null }
        durable.forEach { require(TransferResumeCodec.validId(it.id)) { "续传任务标识无效" } }
        val history = transfers.filter { it.resume == null && it.status !in active }
            .sortedBy { it.createdMillis }.takeLast(maxHistory)
        return (durable + history).sortedBy { it.createdMillis }
    }

    fun restored(transfer: UiTransfer): UiTransfer = if (transfer.resume != null && transfer.status in active) {
        transfer.copy(status = "paused", bytesPerSecond = 0.0, error = "上次传输已中断，可继续")
    } else transfer.copy(bytesPerSecond = 0.0)
}
