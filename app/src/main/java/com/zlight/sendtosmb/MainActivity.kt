package com.zlight.sendtosmb

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import com.zlight.sendtosmb.ui.SendToSmbApp
import com.zlight.sendtosmb.ui.UiAction
import com.zlight.sendtosmb.ui.UiFile
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val model: ExplorerViewModel by viewModels()
    private var uploadPath = ""
    private var downloadFiles = emptyList<UiFile>()
    private val uploadPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch { lifecycle.withResumed { model.upload(uris, uploadPath) } }
    }
    private val downloadPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val files = downloadFiles.toList()
        if (uri != null && files.isNotEmpty()) lifecycleScope.launch { lifecycle.withResumed { model.download(files, uri) } }
        downloadFiles = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uploadPath = savedInstanceState?.getString("uploadPath").orEmpty()
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
            SendToSmbApp(state) { action ->
                when (action) {
                    UiAction.Upload -> {
                        uploadPath = state.path
                        uploadPicker.launch(arrayOf("*/*"))
                    }
                    is UiAction.Download -> {
                        downloadFiles = action.files
                        downloadPicker.launch(null)
                    }
                    UiAction.OpenWifiSettings -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    else -> model.dispatch(action)
                }
            }
        }
    }

    override fun onStart() { super.onStart(); model.onForeground() }
    override fun onStop() { if (!isChangingConfigurations) model.onBackground(); super.onStop() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("uploadPath", uploadPath)
        val array = JSONArray()
        downloadFiles.forEach { array.put(JSONObject().put("name", it.name).put("path", it.path)
            .put("directory", it.isDirectory).put("size", it.size).put("modified", it.modifiedMillis)) }
        outState.putString("downloadFiles", array.toString())
        super.onSaveInstanceState(outState)
    }
}
