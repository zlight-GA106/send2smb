package com.zlight.sendtosmb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class FileSort(val label: String) { Name("名称"), Modified("修改日期"), Size("大小") }

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ExplorerPage(state: UiState, wide: Boolean, onAction: (UiAction) -> Unit, onConnect: () -> Unit) {
    val keyboardVisible = WindowInsets.isImeVisible
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(FileSort.Name) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var sortMenu by remember { mutableStateOf(false) }
    var toolbarMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renameFile by remember { mutableStateOf<UiFile?>(null) }
    var detailsFile by remember { mutableStateOf<UiFile?>(null) }
    var deleteFiles by remember { mutableStateOf<List<UiFile>>(emptyList()) }
    val currentProfile = state.profiles.firstOrNull { it.id == state.currentProfileId }
    val selectedFiles = state.files.filter { it.path in selected }
    val visibleFiles = remember(state.files, search, sort, ascending) {
        val comparator = when (sort) {
            FileSort.Name -> compareBy<UiFile> { it.name.lowercase() }
            FileSort.Modified -> compareBy<UiFile> { it.modifiedMillis }
            FileSort.Size -> compareBy<UiFile> { it.size }
        }.let { if (ascending) it else it.reversed() }
        state.files.filter { it.name.contains(search, ignoreCase = true) }.sortedWith(compareBy<UiFile> { !it.isDirectory }.then(comparator))
    }
    LaunchedEffect(state.path, state.currentProfileId) { selected = emptySet(); search = "" }
    LaunchedEffect(state.files) { selected = selected.intersect(state.files.map { it.path }.toSet()) }
    BackHandler(selected.isNotEmpty() || (state.connected && state.path.trim('/').isNotBlank())) {
        if (selected.isNotEmpty()) selected = emptySet() else onAction(UiAction.Navigate(parentPath(state.path)))
    }
    fun toggle(file: UiFile) { selected = if (file.path in selected) selected - file.path else selected + file.path }
    fun clickFile(file: UiFile) {
        if (selected.isNotEmpty()) toggle(file)
        else if (file.isDirectory) onAction(UiAction.Navigate(file.path))
        else detailsFile = file
    }

    Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 28.dp else 20.dp)) {
        if (!state.connected && !keyboardVisible) {
            Spacer(Modifier.height(16.dp))
            PageHeading("文件管理", "连接后管理共享文件")
            Spacer(Modifier.height(18.dp))
        }
        if (!state.connected) {
            FileManagerDisconnected(state, onAction, onConnect)
        } else {
            if (!keyboardVisible) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Breadcrumbs(state.path, currentProfile?.name ?: "共享根目录", onAction) }
                    Spacer(Modifier.width(6.dp))
                    ToolIcon(Icons.Outlined.Refresh, "刷新文件夹", enabled = !state.loading) { onAction(UiAction.Refresh) }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                placeholder = { Text("搜索文件或文件夹", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                trailingIcon = { if (search.isNotEmpty()) ToolIcon(Icons.Outlined.Close, "清除搜索") { search = "" } },
                singleLine = true, shape = Fluent.Control,
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White, unfocusedBorderColor = Fluent.Border))
            Spacer(Modifier.height(10.dp))
            if (selected.isNotEmpty()) {
                SelectionToolbar(selectedFiles, visibleFiles.size, onClear = { selected = emptySet() }, onAll = { selected = visibleFiles.map { it.path }.toSet() },
                    onDownload = { onAction(UiAction.Download(selectedFiles)) },
                    onCopy = { move -> onAction(UiAction.SetClipboard(selectedFiles, move)); selected = emptySet() },
                    onDelete = { deleteFiles = selectedFiles }, onRename = { selectedFiles.singleOrNull()?.let { renameFile = it } })
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("上传", Icons.Outlined.Upload, enabled = !state.loading) { onAction(UiAction.Upload) }
                    Spacer(Modifier.width(6.dp))
                    if (wide) SecondaryButton("新建文件夹", Icons.Outlined.CreateNewFolder, enabled = !state.loading) { newFolder = true }
                    else ToolIcon(Icons.Outlined.CreateNewFolder, "新建文件夹", enabled = !state.loading) { newFolder = true }
                    Spacer(Modifier.weight(1f))
                    Box {
                        ToolIcon(Icons.AutoMirrored.Outlined.Sort, "排序：${sort.label}") { sortMenu = true }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            FileSort.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; sortMenu = false }, trailingIcon = { if (sort == option) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = Fluent.Blue) })
                            }
                            HorizontalDivider(color = Fluent.Border)
                            DropdownMenuItem(text = { Text(if (ascending) "升序排列" else "降序排列") }, onClick = { ascending = !ascending; sortMenu = false }, leadingIcon = { Icon(if (ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward, null, Modifier.size(18.dp)) })
                        }
                    }
                    ToolIcon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, if (grid) "切换到列表" else "切换到网格") { grid = !grid }
                    Box {
                        ToolIcon(Icons.Outlined.MoreHoriz, "文件操作") { toolbarMenu = true }
                        DropdownMenu(toolbarMenu, { toolbarMenu = false }) {
                            DropdownMenuItem(text = { Text("全选") }, onClick = { selected = visibleFiles.map { it.path }.toSet(); toolbarMenu = false }, leadingIcon = { Icon(Icons.Outlined.SelectAll, null) }, enabled = visibleFiles.isNotEmpty())
                            DropdownMenuItem(text = { Text("粘贴${if (state.clipboardCount > 0) "（${state.clipboardCount}）" else ""}") }, onClick = { onAction(UiAction.Paste); toolbarMenu = false }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) }, enabled = state.clipboardCount > 0)
                            DropdownMenuItem(text = { Text("断开连接") }, onClick = { onAction(UiAction.Disconnect); toolbarMenu = false }, leadingIcon = { Icon(Icons.Outlined.LinkOff, null) })
                        }
                    }
                }
            }
            if (state.clipboardCount > 0) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(Fluent.Control).background(Fluent.BlueTint).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ContentPaste, null, Modifier.size(17.dp), tint = Fluent.Blue)
                    Spacer(Modifier.width(8.dp))
                    Text("${state.clipboardCount} 个项目待粘贴", style = MaterialTheme.typography.bodySmall, color = Fluent.Blue, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAction(UiAction.Paste) }, enabled = !state.loading) { Text("粘贴到这里") }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Fluent.Blue, trackColor = Fluent.Border)
            Surface(Modifier.weight(1f).fillMaxWidth(), shape = Fluent.Radius, color = Color.White, border = BorderStroke(1.dp, Fluent.Border)) {
                if (visibleFiles.isEmpty()) EmptyFolder(search.isNotBlank(), state.loading)
                else if (grid) LazyVerticalGrid(columns = GridCells.Adaptive(if (wide) 150.dp else 128.dp), contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleFiles, key = { it.path }) { file ->
                        FileGridItem(file, file.path in selected, onClick = { clickFile(file) }, onLongClick = { toggle(file) }, onDetails = { detailsFile = file }, onDownload = { onAction(UiAction.Download(listOf(file))) }, onRename = { renameFile = file }, onDelete = { deleteFiles = listOf(file) }, onClipboard = { move -> onAction(UiAction.SetClipboard(listOf(file), move)) })
                    }
                } else Column {
                    if (wide) Row(Modifier.fillMaxWidth().background(Color(0xFFFAFBFC)).padding(start = 20.dp, end = 52.dp, top = 11.dp, bottom = 11.dp)) {
                        Text("名称", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Fluent.Secondary)
                        Text("修改日期", Modifier.width(155.dp), style = MaterialTheme.typography.labelMedium, color = Fluent.Secondary)
                        Text("类型", Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium, color = Fluent.Secondary)
                        Text("大小", Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium, color = Fluent.Secondary, textAlign = TextAlign.End)
                    }
                    LazyColumn {
                        items(visibleFiles, key = { it.path }) { file ->
                            FileListItem(file, wide, file.path in selected, selected.isNotEmpty(), onClick = { clickFile(file) }, onLongClick = { toggle(file) }, onDetails = { detailsFile = file }, onDownload = { onAction(UiAction.Download(listOf(file))) }, onRename = { renameFile = file }, onDelete = { deleteFiles = listOf(file) }, onClipboard = { move -> onAction(UiAction.SetClipboard(listOf(file), move)) })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (search.isBlank()) "${state.files.size} 个项目" else "找到 ${visibleFiles.size} 个项目", style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
                Text(if (selected.isNotEmpty()) "已选 ${selected.size} 项" else "长按选择多个文件", style = MaterialTheme.typography.bodySmall, color = Fluent.Muted)
            }
        }
    }
    if (newFolder) NameDialog("新建文件夹", "文件夹名称", "", "新建", onDismiss = { newFolder = false }) { onAction(UiAction.CreateFolder(it)); newFolder = false }
    renameFile?.let { file -> NameDialog("重命名", "新名称", file.name, "保存", onDismiss = { renameFile = null }) { onAction(UiAction.Rename(file, it)); renameFile = null } }
    detailsFile?.let { file -> FileDetails(file, onDismiss = { detailsFile = null }, onDownload = { onAction(UiAction.Download(listOf(file))); detailsFile = null }) }
    if (deleteFiles.isNotEmpty()) AlertDialog(onDismissRequest = { deleteFiles = emptyList() }, icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Fluent.Red) }, title = { Text("删除${if (deleteFiles.size == 1) "此项目" else " ${deleteFiles.size} 个项目"}？") },
        text = { Text(if (deleteFiles.size == 1) "“${deleteFiles.first().name}”将从共享文件夹中永久删除${if (deleteFiles.first().isDirectory) "，包括其中的所有内容" else ""}。此操作无法撤销。" else "所选项目及文件夹内容将从共享位置永久删除。此操作无法撤销。") },
        confirmButton = { TextButton(onClick = { onAction(UiAction.Delete(deleteFiles)); deleteFiles = emptyList(); selected = emptySet() }) { Text("永久删除", color = Fluent.Red) } },
        dismissButton = { TextButton(onClick = { deleteFiles = emptyList() }) { Text("取消") } })
}

@Composable
private fun FileManagerDisconnected(state: UiState, onAction: (UiAction) -> Unit, onConnect: () -> Unit) {
    FluentCard(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AccentIcon(Icons.Outlined.FolderOff, 68)
            Spacer(Modifier.height(20.dp))
            Text(if (state.connecting) "正在连接共享文件夹" else "连接后管理共享文件", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            if (state.connecting) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                TextButton(onClick = { onAction(UiAction.Disconnect) }) { Text("取消连接") }
            } else {
                PrimaryButton(if (state.profiles.isEmpty()) "添加网络位置" else "前往连接", Icons.Outlined.AddLink, onClick = onConnect)
            }
        }
    }
}

@Composable
internal fun Breadcrumbs(path: String, rootName: String, onAction: (UiAction) -> Unit) {
    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    Surface(shape = Fluent.Control, color = Color.White, border = BorderStroke(1.dp, Fluent.Border)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.Outlined.ArrowUpward, "上一级文件夹", enabled = segments.isNotEmpty()) { onAction(UiAction.Navigate(parentPath(path))) }
            Box(Modifier.width(1.dp).height(18.dp).background(Fluent.Border))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, null, Modifier.size(17.dp), tint = Fluent.Secondary)
                Text(rootName, Modifier.clip(Fluent.Control).clickable { onAction(UiAction.Navigate("")) }.padding(horizontal = 7.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = if (segments.isEmpty()) Fluent.Text else Fluent.Secondary, maxLines = 1)
                segments.forEachIndexed { index, segment ->
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = Fluent.Muted)
                    Text(segment, Modifier.clip(Fluent.Control).clickable { onAction(UiAction.Navigate(segments.take(index + 1).joinToString("/"))) }.padding(horizontal = 7.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = if (index == segments.lastIndex) Fluent.Blue else Fluent.Secondary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EmptyFolder(searching: Boolean, loading: Boolean) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(if (searching) Icons.Outlined.SearchOff else Icons.Outlined.FolderOpen, null, Modifier.size(46.dp), tint = Fluent.Muted)
        Spacer(Modifier.height(12.dp))
        Text(if (loading) "正在读取文件…" else if (searching) "没有找到匹配的文件" else "这个文件夹还是空的", style = MaterialTheme.typography.titleSmall, color = Fluent.Secondary)
        if (!loading) { Spacer(Modifier.height(5.dp)); Text(if (searching) "试试其他名称或关键词" else "上传文件，或新建一个文件夹", style = MaterialTheme.typography.bodySmall, color = Fluent.Muted) }
    }
}
