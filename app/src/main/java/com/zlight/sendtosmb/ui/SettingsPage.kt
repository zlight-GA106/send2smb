package com.zlight.sendtosmb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zlight.sendtosmb.BuildConfig

@Composable
internal fun SettingsPage(state: UiState, wide: Boolean, onAction: (UiAction) -> Unit) {
    var serverUrl by remember(state.update.serverUrl) { mutableStateOf(state.update.serverUrl) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("设置") }
        item { EinkModeCard(state.einkMode, onAction) }
        item { UpdateCard(state, serverUrl, { serverUrl = it }, onAction) }
    }
}

@Composable
private fun EinkModeCard(enabled: Boolean, onAction: (UiAction) -> Unit) {
    FluentCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentIcon(Icons.Outlined.Brightness6, 42)
            Spacer(Modifier.width(12.dp))
            Text("E Ink 模式", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(enabled, onCheckedChange = { onAction(UiAction.SetEinkMode(it)) })
        }
    }
}

@Composable
private fun UpdateCard(state: UiState, serverUrl: String, onServerUrlChange: (String) -> Unit, onAction: (UiAction) -> Unit) {
    val update = state.update
    val busy = update.checking || update.downloading
    FluentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccentIcon(Icons.Outlined.SystemUpdate, 42)
                Spacer(Modifier.width(12.dp))
                Text("EasyUpdate 更新", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(serverUrl, onServerUrlChange, Modifier.fillMaxWidth(), label = { Text("服务地址") },
                placeholder = { Text("http://192.168.95.55:19910") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, enabled = !busy, shape = Fluent.Control)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(if (update.checking) "检查中…" else "检查更新", Icons.Outlined.Refresh, enabled = !busy) {
                    onAction(UiAction.CheckUpdate(serverUrl))
                }
                Spacer(Modifier.weight(1f))
                Text("当前 ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.labelSmall, color = Fluent.Muted)
            }
            update.info?.let { info ->
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Fluent.Border)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("新版本 ${info.versionName}（${info.versionCode}）", style = MaterialTheme.typography.titleSmall)
                    if (info.mandatory) {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = Fluent.OrangeTint, shape = Fluent.Control) {
                            Text("强制更新", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = Fluent.Orange)
                        }
                    }
                }
                if (info.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(info.releaseNotes, style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Text("安装包 ${formatSize(info.size)}", style = MaterialTheme.typography.labelSmall, color = Fluent.Muted)
                Spacer(Modifier.height(12.dp))
                if (update.downloading) {
                    LinearProgressIndicator(progress = { update.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = Fluent.Blue, trackColor = Fluent.Background)
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("正在下载 ${(update.progress * 100).toInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onAction(UiAction.CancelUpdateDownload) }) { Text("取消") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (update.verifiedPath != null) {
                            PrimaryButton("安装更新", Icons.Outlined.GetApp) { onAction(UiAction.InstallUpdate) }
                            SecondaryButton("重新下载", Icons.Outlined.Download) { onAction(UiAction.DownloadUpdate) }
                        } else {
                            PrimaryButton("下载并安装", Icons.Outlined.Download) { onAction(UiAction.DownloadUpdate) }
                        }
                    }
                }
            }
            update.status?.let { status ->
                Spacer(Modifier.height(12.dp))
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (status.contains("失败") || status.contains("无效") || status.contains("不一致") || status.contains("无法")) Fluent.Red else Fluent.Secondary)
            }
        }
    }
}
