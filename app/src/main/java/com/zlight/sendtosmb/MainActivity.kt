package com.zlight.sendtosmb

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import com.zlight.sendtosmb.ui.SendToSmbApp
import com.zlight.sendtosmb.ui.UiAction
import com.zlight.sendtosmb.ui.UiFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private val model get() = (application as SendToSmbApplication).explorer
    private var openTransfers by mutableStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var uploadPath = ""
    private var downloadFiles = emptyList<UiFile>()
    private var downloadProfileId: String? = null
    private val uploadPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch { lifecycle.withResumed { model.upload(uris, uploadPath) } }
    }
    private val downloadPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val files = downloadFiles.toList()
        val profileId = downloadProfileId
        if (uri != null && files.isNotEmpty()) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            lifecycleScope.launch { lifecycle.withResumed { model.download(files, uri, profileId) } }
        }
        downloadFiles = emptyList()
        downloadProfileId = null
    }
    private val downloadDirectoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val directory = DocumentFile.fromTreeUri(this, uri) ?: error("无法打开所选目录")
                check(directory.canWrite()) { "所选目录没有写入权限" }
                model.setDownloadDirectory(uri, directory.name ?: "已选目录")
            }.onFailure(model::reportDownloadDirectoryError)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("showTransfers", false)) openTransfers++
        uploadPath = savedInstanceState?.getString("uploadPath").orEmpty()
        downloadProfileId = savedInstanceState?.getString("downloadProfileId")
        savedInstanceState?.getString("downloadFiles")?.let { json ->
            runCatching {
                val array = JSONArray(json)
                downloadFiles = (0 until array.length()).map { i -> array.getJSONObject(i).let {
                    UiFile(it.getString("name"), it.getString("path"), it.getBoolean("directory"), it.getLong("size"), it.getLong("modified"))
                } }
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val state by model.state.collectAsState()
            SendToSmbApp(state, openTransfers) { action ->
                when (action) {
                    UiAction.OpenTextEditor -> startActivity(Intent(this, com.zlight.sendtosmb.editor.TextEditorActivity::class.java))
                    is UiAction.EditTextFile -> state.currentProfileId?.let { id ->
                        startActivity(Intent(this, com.zlight.sendtosmb.editor.TextEditorActivity::class.java)
                            .putExtra("profile", id).putExtra("path", action.file.path).putExtra("name", action.file.name))
                    }
                    is UiAction.CreateTextFile -> state.currentProfileId?.let { id ->
                        runCatching {
                            val name = if (action.name.endsWith(".txt", true)) action.name else "${action.name}.txt"
                            com.zlight.sendtosmb.data.SmbAddress.validateName(name)
                            startActivity(Intent(this, com.zlight.sendtosmb.editor.TextEditorActivity::class.java)
                                .putExtra("profile", id).putExtra("path", com.zlight.sendtosmb.data.SmbAddress.childPath(state.path, name))
                                .putExtra("name", name).putExtra("new", true))
                        }.onFailure { model.reportDownloadDirectoryError(it) }
                    }
                    UiAction.Upload -> {
                        uploadPath = state.path
                        uploadPicker.launch(arrayOf("*/*"))
                    }
                    is UiAction.Download -> {
                        val profileId = action.profileId ?: state.currentProfileId
                        state.downloadDirectoryUri?.let { model.download(action.files, android.net.Uri.parse(it), profileId) } ?: run {
                            downloadFiles = action.files
                            downloadProfileId = profileId
                            downloadPicker.launch(null)
                        }
                    }
                    UiAction.SelectDownloadDirectory -> downloadDirectoryPicker.launch(null)
                    UiAction.ClearDownloadDirectory -> {
                        state.downloadDirectoryUri?.let { uri ->
                            runCatching { contentResolver.releasePersistableUriPermission(android.net.Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                        }
                        model.dispatch(action)
                    }
                    UiAction.OpenWifiSettings -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    is UiAction.SetBackgroundTransfers -> {
                        model.dispatch(action)
                        if (action.enabled && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    UiAction.InstallUpdate -> installVerifiedUpdate(state.update.verifiedPath)
                    else -> model.dispatch(action)
                }
            }
        }
    }

    override fun onStart() { super.onStart(); model.onForeground() }
    override fun onStop() { if (!isChangingConfigurations) model.onBackground(); super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("showTransfers", false)) openTransfers++
    }

    private fun installVerifiedUpdate(path: String?) {
        if (path.isNullOrBlank()) {
            model.dispatch(UiAction.ReportUpdateStatus("更新包尚未下载"))
            return
        }
        val file = File(path)
        if (!file.exists()) {
            model.dispatch(UiAction.ReportUpdateStatus("更新包已不存在，请重新下载"))
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
            model.dispatch(UiAction.ReportUpdateStatus("请允许安装未知来源应用，然后再次点击“安装更新”"))
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure {
            model.dispatch(UiAction.ReportUpdateStatus("无法打开系统安装程序"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("uploadPath", uploadPath)
        outState.putString("downloadProfileId", downloadProfileId)
        val array = JSONArray()
        downloadFiles.forEach { array.put(JSONObject().put("name", it.name).put("path", it.path)
            .put("directory", it.isDirectory).put("size", it.size).put("modified", it.modifiedMillis)) }
        outState.putString("downloadFiles", array.toString())
        super.onSaveInstanceState(outState)
    }
}
