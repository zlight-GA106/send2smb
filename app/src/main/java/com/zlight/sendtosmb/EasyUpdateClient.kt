package com.zlight.sendtosmb

import android.content.Context
import com.zlight.sendtosmb.ui.UiUpdateInfo
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface UpdateOutcome {
    data class UpToDate(val latestName: String, val latestCode: Long) : UpdateOutcome
    data class Available(val info: UiUpdateInfo) : UpdateOutcome
}

/**
 * Minimal client for a self-hosted EasyUpdate service
 * (https://github.com/zlight-GA106/Easyupdate). The public API needs no login; the
 * admin account only protects the web console.
 */
class EasyUpdateClient(private val context: Context) {

    suspend fun check(baseUrl: String): UpdateOutcome = withContext(Dispatchers.IO) {
        val base = normalizeServer(baseUrl)
        val connection = connection("$base/api/v1/apps/${context.packageName}/latest?version_code=${BuildConfig.VERSION_CODE}")
        try {
            val result = readJson(connection)
            check(result.optString("package_name") == context.packageName) { "服务返回的应用与本应用不匹配" }
            if (!result.optBoolean("update_available")) {
                return@withContext UpdateOutcome.UpToDate(
                    result.optString("latest_version_name").ifBlank { BuildConfig.VERSION_NAME },
                    result.optLong("latest_version_code", BuildConfig.VERSION_CODE.toLong()),
                )
            }
            val versionCode = result.getLong("version_code")
            val versionName = result.getString("version_name")
            val size = result.getLong("size")
            val sha256 = result.getString("sha256")
            val downloadUrl = result.getString("download_url")
            check(versionCode > BuildConfig.VERSION_CODE) { "服务返回的版本不高于当前版本" }
            check(size in 1..MAX_APK_BYTES) { "更新包体积异常" }
            check(sha256.matches(SHA256_PATTERN)) { "更新包 SHA-256 格式错误" }
            val info = UiUpdateInfo(versionName, versionCode, result.optBoolean("mandatory"),
                result.optString("release_notes"), size, validateDownloadUrl(base, downloadUrl), sha256)
            UpdateOutcome.Available(info)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        info: UiUpdateInfo,
        baseUrl: String,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存目录" }
        val partial = File(directory, "incoming-${info.versionCode}.apk")
        val target = File(directory, "verified-${info.versionCode}.apk")
        partial.delete()
        val connection = connection(validateDownloadUrl(baseUrl, info.downloadUrl))
        try {
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "下载失败：服务器返回 HTTP ${connection.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (isCancelled()) throw CancellationException("已取消下载")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= info.size) { "更新包超过服务声明的体积" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(total, info.size)
                    }
                }
            }
            check(total == info.size) { "更新包大小与声明不符" }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(hash.equals(info.sha256, ignoreCase = true)) { "更新包 SHA-256 校验失败" }
            val archive = context.packageManager.getPackageArchiveInfo(partial.absolutePath, 0)
            check(archive != null && archive.packageName == context.packageName && PackageInfoCompat.getLongVersionCode(archive) == info.versionCode) { "更新包的应用包名或版本不匹配" }
            if (target.exists()) check(target.delete()) { "无法替换旧的更新包" }
            check(partial.renameTo(target)) { "无法保存更新包" }
            target
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    suspend fun heartbeat(baseUrl: String, deviceId: String) {
        withContext(Dispatchers.IO) {
            val base = normalizeServer(baseUrl)
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("package_name", context.packageName)
                .put("version_name", BuildConfig.VERSION_NAME)
                .put("version_code", BuildConfig.VERSION_CODE.toLong())
                .toString().toByteArray(Charsets.UTF_8)
            val connection = connection("$base/api/v1/heartbeat")
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                readJson(connection)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/json")
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        check(connection.responseCode in 200..299) { "服务器返回 HTTP ${connection.responseCode}" }
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_RESPONSE_BYTES) { "服务响应过大" }
                output.write(buffer, 0, count)
            }
        }
        return JSONObject(output.toString("UTF-8"))
    }

    private fun validateDownloadUrl(baseUrl: String, downloadUrl: String): String {
        val base = URL(normalizeServer(baseUrl))
        val download = runCatching { URL(downloadUrl) }.getOrElse { throw IllegalArgumentException("更新包下载地址无效") }
        check(download.protocol == base.protocol && download.host == base.host && download.port == base.port && download.userInfo == null) {
            "更新包下载地址与服务地址不一致"
        }
        return download.toString()
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 65_536
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")

        /** Accepts `http(s)://host[:port][/path]` without credentials, query or fragment. */
        fun normalizeServer(value: String): String {
            val base = value.trim().trimEnd('/')
            val url = runCatching { URL(base) }.getOrElse {
                throw IllegalArgumentException("请输入有效的服务地址，例如 http://192.168.95.55:19910")
            }
            require(url.protocol == "http" || url.protocol == "https") { "服务地址需以 http:// 或 https:// 开头" }
            require(url.host.isNotEmpty() && url.userInfo == null && url.query == null && url.ref == null) { "服务地址无效" }
            return base
        }
    }
}
