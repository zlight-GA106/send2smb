package com.zlight.sendtosmb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun TransfersPage(state: UiState, wide: Boolean, onAction: (UiAction) -> Unit) {
    val active = state.transfers.filter { it.status == "running" || it.status == "queued" }
    val history = state.transfers.filterNot { it.status == "running" || it.status == "queued" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            PageHeading("传输", if (active.isEmpty()) "传输记录默认保存在本机" else "${active.size} 个任务正在进行") {
                if (history.isNotEmpty()) ToolIcon(Icons.Outlined.DeleteSweep, "清空传输记录") { onAction(UiAction.ClearCompletedTransfers) }
            }
        }
        if (state.transfers.isEmpty()) item {
            FluentCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AccentIcon(Icons.Outlined.SwapVert, 68)
                    Spacer(Modifier.height(22.dp))
                    Text("一切准备就绪", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("上传或下载文件后，在这里查看进度和结果。", style = MaterialTheme.typography.bodyMedium, color = Fluent.Secondary)
                }
            }
        }
        if (active.isNotEmpty()) item { Text("正在传输 · ${active.size}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, color = Fluent.Secondary) }
        items(active, key = { it.id }) { transfer -> TransferCard(transfer, onCancel = { onAction(UiAction.CancelTransfer(transfer.id)) }) }
        if (history.isNotEmpty()) item { Text("传输记录 · ${history.size}", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = Fluent.Secondary) }
        items(history.sortedByDescending { it.createdMillis }, key = { it.id }) { transfer ->
            TransferCard(transfer, onRepeatDownload = {
                transfer.sourceFile?.let { onAction(UiAction.Download(listOf(it), transfer.profileId)) }
            })
        }
        if (active.isNotEmpty()) item {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, null, Modifier.size(17.dp), tint = Fluent.Muted)
                Spacer(Modifier.width(8.dp))
                Text("传输期间请保持应用在前台。离开应用会断开 SMB 连接，未完成的任务将停止。", style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
            }
        }
    }
}

@Composable
private fun TransferCard(transfer: UiTransfer, onCancel: () -> Unit = {}, onRepeatDownload: () -> Unit = {}) {
    val active = transfer.status == "running" || transfer.status == "queued"
    val complete = transfer.status == "completed"
    val failed = transfer.status == "failed"
    FluentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccentIcon(if (transfer.direction == "upload") Icons.Outlined.Upload else Icons.Outlined.Download, 42,
                    color = if (complete) Fluent.Green else if (failed) Fluent.Red else Fluent.Blue,
                    background = if (complete) Fluent.GreenTint else Fluent.BlueTint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(transfer.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text((if (transfer.direction == "upload") "上传" else "下载") + " · " + when (transfer.status) { "queued" -> "等待中"; "running" -> "传输中"; "completed" -> "已完成"; "failed" -> "失败"; "cancelled" -> "已取消"; else -> transfer.status }, style = MaterialTheme.typography.bodySmall, color = if (failed) Fluent.Red else if (complete) Fluent.Green else Fluent.Secondary)
                }
                if (active) ToolIcon(Icons.Outlined.Close, "取消传输 ${transfer.name}", onClick = onCancel)
                else Icon(if (complete) Icons.Outlined.CheckCircle else if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Cancel, null, Modifier.size(20.dp), tint = if (complete) Fluent.Green else if (failed) Fluent.Red else Fluent.Muted)
            }
            if (active) {
                Spacer(Modifier.height(14.dp))
                if (transfer.total > 0) LinearProgressIndicator(progress = { (transfer.done.toFloat() / transfer.total.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = Fluent.Blue, trackColor = Fluent.Background)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp), color = Fluent.Blue, trackColor = Fluent.Background)
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatSize(transfer.done)} / ${if (transfer.total > 0) formatSize(transfer.total) else "计算中"}", style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
                    if (transfer.total > 0) Text("${(transfer.done.toDouble() / transfer.total * 100).toInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.bodySmall, color = Fluent.Blue)
                }
            } else if (transfer.error != null) {
                Spacer(Modifier.height(9.dp))
                Text(transfer.error, style = MaterialTheme.typography.bodySmall, color = Fluent.Red)
            } else {
                Spacer(Modifier.height(8.dp))
                Text(formatSize(if (complete) transfer.total else transfer.done), style = MaterialTheme.typography.bodySmall, color = Fluent.Muted)
            }
            if (!active && transfer.direction == "download" && transfer.sourceFile != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onRepeatDownload, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("再次下载")
                }
            }
        }
    }
}
