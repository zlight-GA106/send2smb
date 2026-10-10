package com.zlight.sendtosmb.editor

import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zlight.sendtosmb.ui.*

private class EditorTextView(context: Context) : EditText(context) {
    var changing = false
    var edit: (Int, Int, String) -> Unit = { _, _, _ -> }
    var select: ((Int, Int) -> Unit)? = null
    var find: () -> Unit = {}
    var undo: () -> Unit = {}
    var redo: () -> Unit = {}
    init {
        setBackgroundColor(android.graphics.Color.WHITE)
        gravity = Gravity.TOP or Gravity.START
        typeface = Typeface.MONOSPACE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        val padding = (12 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!changing && s != null) edit(start, before, s.subSequence(start, start + count).toString())
            }
        })
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean { menu?.add(0, 110, 0, "查找")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM); return true }
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = if (item?.itemId == 110) { find(); mode?.finish(); true } else false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
    }
    override fun onSelectionChanged(selStart: Int, selEnd: Int) { super.onSelectionChanged(selStart, selEnd); if (!changing) select?.invoke(selStart, selEnd) }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.isCtrlPressed == true && keyCode == KeyEvent.KEYCODE_Z) { if (event.isShiftPressed) redo() else undo(); return true }
        if (event?.isCtrlPressed == true && keyCode == KeyEvent.KEYCODE_Y) { redo(); return true }
        if (event?.isCtrlPressed == true && keyCode == KeyEvent.KEYCODE_F) { find(); return true }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
internal fun TextEditorScreen(state: EditorState, model: TextEditorViewModel,
    onExit: () -> Unit, onSaveLocal: (Boolean) -> Unit, onOpenLocal: () -> Unit, onRecover: (String) -> Unit, onShare: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var ignoreCase by remember { mutableStateOf(false) }
    var lineDialog by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    var appearance by remember { mutableStateOf(false) }
    var encoding by remember { mutableStateOf(false) }
    var autoSave by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var close by remember { mutableStateOf(false) }
    var recover by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorTextView?>(null) }
    var lastSelectionVersion by remember { mutableIntStateOf(-1) }
    var lastPage by remember { mutableIntStateOf(-1) }
    fun save(exit: Boolean = false) {
        if (model.hasSmbSource()) model.save(onSaved = { if (exit) onExit() }) else onSaveLocal(exit)
    }
    fun requestClose() {
        if (state.busy) { model.report("请等待当前操作完成，本机草稿会保留"); return }
        if (!state.dirty) onExit()
        else if (state.autoSave == TextAutoSave.LEAVE && model.hasSmbSource() && !state.readOnly) save(true)
        else close = true
    }
    fun showFind() {
        if (state.selection != state.selectionEnd) query = state.text.substring(minOf(state.selection, state.selectionEnd), maxOf(state.selection, state.selectionEnd)).take(4096)
        search = true
    }
    BackHandler { requestClose() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding().navigationBarsPadding()) {
        Surface(color = Color.White) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭编辑器") { requestClose() }
                Column(Modifier.weight(1f)) {
                    Text(state.name + if (state.dirty) " ●" else "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${state.encoding.label} · ${state.ending}${if (state.readOnly) " · 只读" else ""}", style = MaterialTheme.typography.labelSmall, color = Fluent.Secondary)
                }
                ToolIcon(Icons.Outlined.Save, if (model.hasSmbSource()) "保存到 SMB" else "另存到设备", enabled = state.ready && !state.busy && !state.readOnly) { save() }
                Box {
                    ToolIcon(Icons.Outlined.MoreVert, "编辑器菜单") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("从设备打开") }, onClick = { menu = false; onOpenLocal() }, enabled = !state.busy)
                        DropdownMenuItem(text = { Text("恢复草稿（${state.recoveries.size}）") }, onClick = { menu = false; recover = true }, enabled = state.recoveries.isNotEmpty())
                        if (model.hasSmbSource()) DropdownMenuItem(text = { Text("另存到 SMB 同一文件夹") }, onClick = { menu = false; saveAs = true }, enabled = state.ready && !state.busy && !state.readOnly)
                        DropdownMenuItem(text = { Text("另存到设备") }, onClick = { menu = false; onSaveLocal(false) }, enabled = state.ready && !state.busy)
                        DropdownMenuItem(text = { Text("分享文本文件") }, onClick = { menu = false; onShare() }, enabled = state.ready && !state.busy)
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("查找、替换") }, onClick = { menu = false; showFind() }, enabled = state.ready && !state.busy)
                        DropdownMenuItem(text = { Text("跳转到行") }, onClick = { menu = false; lineDialog = true }, enabled = state.ready && !state.busy)
                        DropdownMenuItem(text = { Text("字体与行距") }, onClick = { menu = false; appearance = true })
                        DropdownMenuItem(text = { Text(if (state.wrap) "自动换行 ✓" else "自动换行") }, onClick = { menu = false; model.wrap(!state.wrap) })
                        DropdownMenuItem(text = { Text(if (state.readOnly) "只读模式 ✓" else "只读模式") }, onClick = { menu = false; model.readOnly(!state.readOnly) }, enabled = !state.busy)
                        DropdownMenuItem(text = { Text("编码：${state.encoding.label}") }, onClick = { menu = false; encoding = true }, enabled = !state.busy)
                        DropdownMenuItem(text = { Text("自动保存：${state.autoSave.label}") }, onClick = { menu = false; autoSave = true })
                    }
                }
            }
        }
        HorizontalDivider(color = Fluent.Border)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.AutoMirrored.Outlined.Undo, "撤销", enabled = state.canUndo && !state.readOnly && !state.busy) { model.undo() }
            ToolIcon(Icons.AutoMirrored.Outlined.Redo, "重做", enabled = state.canRedo && !state.readOnly && !state.busy) { model.redo() }
            ToolIcon(Icons.Outlined.Search, "查找", enabled = state.ready && !state.busy) { showFind() }
            ToolIcon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "光标向左", enabled = state.ready && !state.busy) { model.cursor(-1) }
            ToolIcon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "光标向右", enabled = state.ready && !state.busy) { model.cursor(1) }
            ToolIcon(Icons.Outlined.KeyboardArrowUp, "光标向上", enabled = state.ready && !state.busy) { model.cursor(-2) }
            ToolIcon(Icons.Outlined.KeyboardArrowDown, "光标向下", enabled = state.ready && !state.busy) { model.cursor(2) }
        }
        if (state.selection != state.selectionEnd) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf("复制" to android.R.id.copy, "剪切" to android.R.id.cut, "粘贴" to android.R.id.paste, "全选" to android.R.id.selectAll).forEach { (name, id) ->
                TextButton(onClick = { editor?.onTextContextMenuItem(id) }, enabled = !state.busy && (!state.readOnly || id == android.R.id.copy || id == android.R.id.selectAll)) { Text(name) }
            }
            TextButton(onClick = { showFind() }) { Text("查找") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Fluent.Blue)
        if (state.ready) {
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                EditorTextView(context).also { view ->
                    editor = view
                    view.edit = model::edit; view.select = model::selection; view.find = { showFind() }; view.undo = model::undo; view.redo = model::redo
                    val originalKeyListener = view.keyListener
                    view.tag = originalKeyListener
                    view.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                        if (dest.length - (dend - dstart) + (end - start) > PagedTextDocument.MAX_EDIT_PAGE) {
                            model.report("单页最多 256K 字符，请分次粘贴或使用其他页面")
                            // Rejecting a replacement with "" would delete the selected original.
                            dest.subSequence(dstart, dend)
                        } else null
                    })
                    view.contentDescription = "文本正文"
                }
            }, update = { view ->
                view.changing = true
                view.find = { showFind() }
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, state.fontSize.toFloat())
                view.setLineSpacing(0f, state.lineSpacing)
                view.setHorizontallyScrolling(!state.wrap)
                view.keyListener = if (state.readOnly || state.busy) null else view.tag as android.text.method.KeyListener
                view.showSoftInputOnFocus = !state.readOnly && !state.busy
                if (view.text.toString() != state.text) {
                    val y = if (lastPage == state.page) view.scrollY else 0
                    view.setText(state.text)
                    view.setSelection(state.selection.coerceAtMost(view.length()), state.selectionEnd.coerceAtMost(view.length()))
                    view.scrollTo(0, y)
                }
                if (lastSelectionVersion != state.selectionVersion || lastPage != state.page) {
                    view.setSelection(state.selection.coerceAtMost(view.length()), state.selectionEnd.coerceAtMost(view.length()))
                    lastSelectionVersion = state.selectionVersion; lastPage = state.page
                }
                view.changing = false
            })
        } else Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(if (state.error) "原文件未修改。可从菜单选择正确编码重新打开。" else "正在准备本地文本…", color = Fluent.Secondary)
        }
        if (state.pages > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { model.page(state.page - 1) }, enabled = state.page > 0 && !state.busy) { Text("上一页") }
            Text("${state.page + 1} / ${state.pages} 页", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { model.page(state.page + 1) }, enabled = state.page + 1 < state.pages && !state.busy) { Text("下一页") }
        }
        Surface(color = Color.White) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("行 ${state.cursorLine}，列 ${state.cursorColumn} · ${state.words} 字/词 · ${state.characters} 字符 · ${state.lines} 行", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.status, style = MaterialTheme.typography.labelSmall, color = if (state.error) Fluent.Red else Fluent.Secondary, maxLines = if (state.error) 3 else 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (search) AlertDialog(onDismissRequest = { search = false }, title = { Text("查找、替换") }, text = {
        Column {
            OutlinedTextField(query, { query = it.take(4096) }, label = { Text("查找内容") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(replacement, { replacement = it }, label = { Text("替换为") }, modifier = Modifier.fillMaxWidth(), enabled = !state.readOnly)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ignoreCase, { ignoreCase = it }); Text("忽略大小写") }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { model.find(query, ignoreCase); search = false }, enabled = query.isNotEmpty() && !state.busy) { Text("查找下一个") }
                TextButton(onClick = { model.find(query, ignoreCase, replacement); search = false }, enabled = query.isNotEmpty() && !state.readOnly && !state.busy) { Text("替换下一个") }
            }
            TextButton(onClick = { model.replaceAll(query, replacement, ignoreCase); search = false }, enabled = query.isNotEmpty() && !state.readOnly && !state.busy) { Text("全部替换") }
        }
    }, confirmButton = { TextButton(onClick = { search = false }) { Text("关闭") } })
    if (lineDialog) AlertDialog(onDismissRequest = { lineDialog = false }, title = { Text("跳转到行") }, text = {
        OutlinedTextField(line, { line = it.filter(Char::isDigit).take(12) }, label = { Text("1 – ${state.lines}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    }, confirmButton = { TextButton(onClick = { line.toLongOrNull()?.let(model::goToLine); lineDialog = false }, enabled = line.toLongOrNull()?.let { it in 1..state.lines } == true) { Text("跳转") } }, dismissButton = { TextButton(onClick = { lineDialog = false }) { Text("取消") } })
    if (appearance) AlertDialog(onDismissRequest = { appearance = false }, title = { Text("字体与行距") }, text = {
        Column {
            Text("字体大小 ${state.fontSize} sp")
            Slider(state.fontSize.toFloat(), { model.font(it.toInt()) }, valueRange = 10f..32f, steps = 21)
            Text("行距 ${"%.1f".format(state.lineSpacing)} 倍")
            Slider(state.lineSpacing, model::spacing, valueRange = 1f..2f, steps = 9)
            Text("字数按中日韩逐字、其他文字按连续字母和数字计词；字符数按 Unicode 字符统计，包含换行。", style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
        }
    }, confirmButton = { TextButton(onClick = { appearance = false }) { Text("完成") } })
    if (encoding) AlertDialog(onDismissRequest = { encoding = false }, title = { Text(if (state.ready) "保存编码" else "重新识别编码") }, text = {
        Column { Text("仅在这里主动选择时转换编码。不能表示的字符会阻止保存。", style = MaterialTheme.typography.bodySmall)
            TextEncoding.entries.forEach { value -> TextButton(onClick = { model.encoding(value); encoding = false }, enabled = !state.readOnly) { Text(value.label + if (value == state.encoding) " ✓" else "") } }
        }
    }, confirmButton = { TextButton(onClick = { encoding = false }) { Text("取消") } })
    if (autoSave) AlertDialog(onDismissRequest = { autoSave = false }, title = { Text("自动保存") }, text = {
        Column {
            Text("自动写回仅用于 SMB 文档。本机恢复草稿始终保存；设备文档通过系统“另存为”创建副本。", style = MaterialTheme.typography.bodySmall)
            TextAutoSave.entries.forEach { value -> TextButton(onClick = { model.autoSave(value); autoSave = false }) { Text(value.label + if (value == state.autoSave) " ✓" else "") } }
        }
    }, confirmButton = { TextButton(onClick = { autoSave = false }) { Text("取消") } })
    if (saveAs) NameDialog("另存到 SMB", "文件名", state.name, "保存", onDismiss = { saveAs = false }) { model.save(it); saveAs = false }
    if (close) AlertDialog(onDismissRequest = { close = false }, title = { Text("${state.name} 尚未保存") }, text = { Text("可以写回文件，或保留本机恢复草稿后退出。") },
        confirmButton = { TextButton(onClick = { close = false; save(true) }, enabled = !state.readOnly) { Text("保存并退出") } },
        dismissButton = { Row { TextButton(onClick = { close = false; onExit() }) { Text("保留草稿退出") }; TextButton(onClick = { close = false }) { Text("继续编辑") } } })
    if (recover) AlertDialog(onDismissRequest = { recover = false }, title = { Text("恢复本机草稿") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            state.recoveries.forEach { draft -> TextButton(onClick = { recover = false; onRecover(draft.id) }) { Text(draft.name) } }
        }
    }, confirmButton = { TextButton(onClick = { recover = false }) { Text("关闭") } })
}
