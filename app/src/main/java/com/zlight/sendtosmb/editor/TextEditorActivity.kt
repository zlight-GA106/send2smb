package com.zlight.sendtosmb.editor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import com.zlight.sendtosmb.SettingsStore
import com.zlight.sendtosmb.ui.FluentTheme
import com.zlight.sendtosmb.ui.einkGrayscale
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier

class TextEditorActivity : ComponentActivity() {
    private val model: TextEditorViewModel by viewModels()
    private var finishAfterSave = false
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) model.saveLocal(uri) { if (finishAfterSave) finish(); finishAfterSave = false }
        else finishAfterSave = false
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startActivity(Intent(this, TextEditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishAfterSave = savedInstanceState?.getBoolean("finishAfterSave") ?: false
        enableEdgeToEdge()
        val restoreId = savedInstanceState?.getString("textSession")
        model.open(if (restoreId == null) intent else Intent(this, TextEditorActivity::class.java).putExtra("recovery", restoreId))
        val eink = SettingsStore(this).read().einkMode
        setContent {
            val state by model.state.collectAsState()
            FluentTheme {
                Box(Modifier.einkGrayscale(eink)) {
                    TextEditorScreen(state, model, onExit = ::finish,
                        onSaveLocal = { exit -> finishAfterSave = exit; createDocument.launch(state.name) },
                        onOpenLocal = { openDocument.launch(arrayOf("text/*", "application/octet-stream")) },
                        onRecover = { id -> startActivity(Intent(this@TextEditorActivity, TextEditorActivity::class.java).putExtra("recovery", id)) },
                        onShare = { model.share { file ->
                            val uri = FileProvider.getUriForFile(this@TextEditorActivity, "$packageName.files", file)
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享文本文件"))
                        } })
                }
            }
        }
    }
    override fun onStart() { super.onStart(); model.onVisible(true) }
    override fun onStop() { model.onVisible(false); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("finishAfterSave", finishAfterSave)
        model.sessionId()?.let { outState.putString("textSession", it) }
        super.onSaveInstanceState(outState)
    }
}

/** Exported entry accepts document/text intents only; SMB paths and profile IDs stay private. */
class TextOpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, TextEditorActivity::class.java)
        val uri = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else -> null
        }
        if (uri != null && uri.scheme == "content") {
            target.data = uri
            target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            target.clipData = android.content.ClipData.newRawUri("TXT", uri)
        } else if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            target.putExtra("sharedText", text)
        } else { finish(); return }
        startActivity(target)
        finish()
    }
}
