package com.zlight.sendtosmb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeading(
                "文件",
                if (state.connected) profile?.url?.removePrefix("smb:") ?: "共享文件夹" else "你的文件，在每一台设备之间",
            ) {
                if (state.connected) ToolIcon(Icons.Outlined.Refresh, "刷新共享信息", enabled = !state.loading) { onAction(UiAction.Refresh) }
            }
        }
        if (!state.connected) {
            item {
                FluentCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (wide) 36.dp else 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ConnectionIllustration()
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
            item { CapacityCard(null, "网络存储空间", false, Modifier.fillMaxWidth()) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    IntroFeature(Icons.Outlined.Lock, "本机加密凭据")
                    IntroFeature(Icons.Outlined.SwapVert, "双向文件传输")
                    IntroFeature(Icons.Outlined.Devices, "手机与平板")
                }
            }
        } else {
            item { CapacityCard(state.capacity, profile?.name ?: "网络存储", true, Modifier.fillMaxWidth()) }
            item {
                Breadcrumbs(state.path, "共享根目录") { action ->
                    onAction(action)
                    onOpenFiles()
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = if (wide) 48.dp else 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ConnectionIllustration()
                    Spacer(Modifier.height(22.dp))
                    Text("连接，让文件触手可及", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                }
            }
        }
        item {
            TextButton(onClick = { onAction(UiAction.OpenTextEditor) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.EditNote, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("文本编辑器")
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun ConnectionIllustration() {
    Box(Modifier.width(154.dp).height(96.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(90.dp).clip(Fluent.Radius).background(Fluent.BlueTint), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(52.dp), tint = Fluent.Blue)
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).size(36.dp),
            shape = Fluent.Control, color = Color.White, border = BorderStroke(1.dp, Fluent.Border), shadowElevation = 2.dp) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Wifi, null, Modifier.size(20.dp), tint = Fluent.Blue) }
        }
    }
}

@Composable
private fun IntroFeature(icon: ImageVector, title: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(20.dp), tint = Fluent.Secondary)
        Spacer(Modifier.height(7.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = Fluent.Secondary)
    }
}
