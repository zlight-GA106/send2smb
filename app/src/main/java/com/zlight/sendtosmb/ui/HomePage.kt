package com.zlight.sendtosmb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun HomePage(
    state: UiState,
    wide: Boolean,
    onAction: (UiAction) -> Unit,
    onConnect: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val profile = state.profiles.firstOrNull { it.id == state.currentProfileId }
    val recent = state.transfers.filterNot { it.status == "running" || it.status == "queued" }
        .sortedByDescending { it.createdMillis }.take(3)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageHeading("首页", if (state.connected) profile?.name ?: "共享文件夹" else "你的文件，在每一台设备之间") }
        if (!state.connected) {
            item {
                FluentCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (wide) 36.dp else 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.width(154.dp).height(96.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(90.dp).clip(Fluent.Radius).background(Fluent.BlueTint), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(52.dp), tint = Fluent.Blue)
                            }
                            Surface(
                                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).size(36.dp),
                                shape = Fluent.Control,
                                color = Color.White,
                                border = BorderStroke(1.dp, Fluent.Border),
                                shadowElevation = 2.dp,
                            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Wifi, null, Modifier.size(20.dp), tint = Fluent.Blue) } }
                        }
                        Spacer(Modifier.height(22.dp))
                        Text(if (state.connecting) "正在连接你的文件" else "连接，让文件触手可及", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        if (state.connecting) {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                            TextButton(onClick = { onAction(UiAction.Disconnect) }) { Text("取消连接") }
                        } else {
                            PrimaryButton(if (state.profiles.isEmpty()) "添加网络位置" else "连接共享文件夹", Icons.Outlined.AddLink, onClick = onConnect)
                        }
                    }
                }
            }
            if (profile != null && !state.connecting) {
                item {
                    FluentCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            AccentIcon(Icons.Outlined.Computer, 40)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("上次连接", style = MaterialTheme.typography.bodySmall, color = Fluent.Muted)
                                Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { onAction(UiAction.Connect(profile.id)) }) { Text("重新连接") }
                        }
                    }
                }
            }
        } else {
            item { CapacityCard(state.capacity, profile?.name ?: "网络存储", true, Modifier.fillMaxWidth()) }
            item {
                FluentCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AccentIcon(Icons.Outlined.FolderOpen, 44)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("访问你的文件", style = MaterialTheme.typography.titleMedium)
                                Text(profile?.url.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("打开文件管理", Icons.Outlined.FolderOpen, onClick = onOpenFiles)
                            SecondaryButton("断开", Icons.Outlined.LinkOff) { onAction(UiAction.Disconnect) }
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { Text("最近传输", style = MaterialTheme.typography.titleSmall, color = Fluent.Secondary) }
            items(recent, key = { it.id }) { transfer ->
                FluentCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (transfer.direction == "upload") Icons.Outlined.Upload else Icons.Outlined.Download, null, Modifier.size(21.dp), tint = if (transfer.status == "completed") Fluent.Green else Fluent.Secondary)
                        Spacer(Modifier.width(11.dp))
                        Text(transfer.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (transfer.status == "completed") "已完成" else if (transfer.status == "failed") "失败" else "已取消", style = MaterialTheme.typography.bodySmall, color = Fluent.Muted)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}
