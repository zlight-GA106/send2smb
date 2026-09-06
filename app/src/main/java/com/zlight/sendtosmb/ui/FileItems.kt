@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.zlight.sendtosmb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun FileListItem(file: UiFile, wide: Boolean, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
    onDetails: () -> Unit, onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onClipboard: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().background(if (selected) Fluent.BlueTint else Color.Transparent).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(selected, { onLongClick() }, Modifier.size(30.dp).padding(end = 6.dp))
            }
            FileTypeIcon(file, if (wide) 34 else 40)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!wide) {
                    Spacer(Modifier.height(3.dp))
                    Text((if (file.isDirectory) "文件夹" else formatSize(file.size)) + " · " + formatDate(file.modifiedMillis), style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (wide) {
                Text(formatDate(file.modifiedMillis), Modifier.width(155.dp), style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
                Text(fileKind(file), Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (file.isDirectory) "—" else formatSize(file.size), Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary, textAlign = TextAlign.End)
            }
            FileMenu(file, onDetails, onDownload, onRename, onDelete, onClipboard, onSelect = onLongClick)
        }
        HorizontalDivider(Modifier.padding(start = if (selectionMode) 95.dp else 65.dp), color = Fluent.Border.copy(alpha = .65f))
    }
}

@Composable
internal fun FileGridItem(file: UiFile, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onDetails: () -> Unit,
    onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onClipboard: (Boolean) -> Unit) {
    Surface(shape = Fluent.Control, color = if (selected) Fluent.BlueTint else Color.White, border = BorderStroke(1.dp, if (selected) Fluent.Blue.copy(alpha = .5f) else Fluent.Border)) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 12.dp, end = 8.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected) Icon(Icons.Outlined.CheckCircle, "已选择", Modifier.size(18.dp), tint = Fluent.Blue)
                Spacer(Modifier.weight(1f))
                FileMenu(file, onDetails, onDownload, onRename, onDelete, onClipboard, onSelect = onLongClick)
            }
            FileTypeIcon(file, 52)
            Spacer(Modifier.height(12.dp))
            Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(if (file.isDirectory) "文件夹" else formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
        }
    }
}

@Composable
private fun FileMenu(file: UiFile, onDetails: () -> Unit, onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onClipboard: (Boolean) -> Unit, onSelect: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolIcon(Icons.Outlined.MoreVert, "${file.name}的更多操作") { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            FileMenuAction("下载", Icons.Outlined.Download) { expanded = false; onDownload() }
            FileMenuAction("选择", Icons.Outlined.CheckCircleOutline) { expanded = false; onSelect() }
            HorizontalDivider(color = Fluent.Border)
            FileMenuAction("复制", Icons.Outlined.ContentCopy) { expanded = false; onClipboard(false) }
            FileMenuAction("剪切", Icons.Outlined.ContentCut) { expanded = false; onClipboard(true) }
            FileMenuAction("重命名", Icons.Outlined.DriveFileRenameOutline) { expanded = false; onRename() }
            FileMenuAction("详细信息", Icons.Outlined.Info) { expanded = false; onDetails() }
            HorizontalDivider(color = Fluent.Border)
            DropdownMenuItem(text = { Text("删除", color = Fluent.Red) }, onClick = { expanded = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp), tint = Fluent.Red) })
        }
    }
}

@Composable
private fun FileMenuAction(label: String, icon: ImageVector, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = action, leadingIcon = { Icon(icon, null, Modifier.size(20.dp), tint = Fluent.Secondary) })
}

@Composable
internal fun SelectionToolbar(files: List<UiFile>, visibleCount: Int, onClear: () -> Unit, onAll: () -> Unit, onDownload: () -> Unit,
    onCopy: (Boolean) -> Unit, onDelete: () -> Unit, onRename: () -> Unit) {
    Surface(shape = Fluent.Control, color = Fluent.BlueTint) {
        Column {
            Row(Modifier.fillMaxWidth().height(42.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolIcon(Icons.Outlined.Close, "清除选择", tint = Fluent.Blue, onClick = onClear)
                Text("已选择 ${files.size} 个项目", style = MaterialTheme.typography.titleSmall, color = Fluent.Blue, modifier = Modifier.weight(1f))
                TextButton(onClick = onAll, enabled = files.size != visibleCount) { Text("全选") }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                SelectionAction("下载", Icons.Outlined.Download, onClick = onDownload)
                SelectionAction("复制", Icons.Outlined.ContentCopy) { onCopy(false) }
                SelectionAction("剪切", Icons.Outlined.ContentCut) { onCopy(true) }
                SelectionAction("重命名", Icons.Outlined.DriveFileRenameOutline, files.size == 1, onRename)
                SelectionAction("删除", Icons.Outlined.DeleteOutline, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun SelectionAction(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.width(54.dp).clip(Fluent.Control).clickable(enabled = enabled, onClick = onClick).padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, Modifier.size(21.dp), tint = if (enabled) Fluent.Blue else Fluent.Muted)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) Fluent.Blue else Fluent.Muted)
    }
}

@Composable
internal fun NameDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    var attempted by remember { mutableStateOf(false) }
    val valid = value.isNotBlank() && value != "." && value != ".." && value.none { it in "\\/:*?\"<>|" || it.code < 32 } && !value.endsWith('.') && !value.endsWith(' ')
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(value, { value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = Fluent.Control,
                isError = attempted && !valid, supportingText = { if (attempted && !valid) Text("请输入有效名称，不能包含路径符号或末尾空格/句点") })
        },
        confirmButton = { TextButton(onClick = { attempted = true; if (valid) onConfirm(value) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun FileDetails(file: UiFile, onDismiss: () -> Unit, onDownload: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { FileTypeIcon(file, 52) },
        title = { Text(file.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DetailRow("类型", fileKind(file))
                if (!file.isDirectory) DetailRow("大小", "${formatSize(file.size)}（${file.size} 字节）")
                DetailRow("修改时间", formatDate(file.modifiedMillis))
                Column {
                    Text("位置", style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer { Text("/${file.path}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = { PrimaryButton(if (file.isDirectory) "下载文件夹" else "下载到设备", Icons.Outlined.Download, onClick = onDownload) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
