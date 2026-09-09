package com.zlight.sendtosmb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID

@Composable
internal fun ConnectionsPage(state: UiState, wide: Boolean, onAction: (UiAction) -> Unit, onAdd: () -> Unit, onEdit: (UiProfile) -> Unit) {
    var deleting by remember { mutableStateOf<UiProfile?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PageHeading("连接", "你的电脑、NAS 与共享文件夹") {
                if (wide) PrimaryButton("添加连接", Icons.Outlined.Add, onClick = onAdd)
                else ToolIcon(Icons.Outlined.Add, "添加连接", tint = Fluent.Blue, onClick = onAdd)
            }
        }
        if (state.profiles.isEmpty()) {
            item {
                FluentCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AccentIcon(Icons.Outlined.AddToDrive, 64)
                        Spacer(Modifier.height(20.dp))
                        Text("让设备之间，更近一点", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("添加 SMB 网络位置，就能在手机、平板和电脑之间自由管理文件。", style = MaterialTheme.typography.bodyMedium, color = Fluent.Secondary)
                        Spacer(Modifier.height(24.dp))
                        PrimaryButton("添加连接", Icons.Outlined.Add, onClick = onAdd)
                    }
                }
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            val active = profile.id == state.currentProfileId
            FluentCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentIcon(Icons.Outlined.Computer, 46)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Text(profile.url, style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (active && (state.connected || state.connecting)) StatusBadge(state.connected, state.connecting)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(15.dp), tint = Fluent.Muted)
                        Spacer(Modifier.width(5.dp))
                        Text(profile.username.ifBlank { "访客访问" }, style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(if (profile.rememberPassword) Icons.Outlined.Lock else Icons.Outlined.KeyOff, null, Modifier.size(14.dp), tint = Fluent.Muted)
                        Spacer(Modifier.width(5.dp))
                        Text(if (profile.rememberPassword) "已保存凭据" else "不保存密码", style = MaterialTheme.typography.bodySmall, color = Fluent.Muted)
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Fluent.Border)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (active && state.connected) SecondaryButton("断开", Icons.Outlined.LinkOff) { onAction(UiAction.Disconnect) }
                        else PrimaryButton(if (active && state.connecting) "连接中…" else "连接", Icons.Outlined.Link, enabled = !state.connecting) { onAction(UiAction.Connect(profile.id)) }
                        Spacer(Modifier.weight(1f))
                        ToolIcon(Icons.Outlined.Edit, "编辑 ${profile.name}") { onEdit(profile) }
                        ToolIcon(Icons.Outlined.DeleteOutline, "删除 ${profile.name}") { deleting = profile }
                    }
                }
            }
        }
        if (state.profiles.isNotEmpty() && !wide) item { SecondaryButton("添加网络位置", Icons.Outlined.Add, modifier = Modifier.fillMaxWidth(), onClick = onAdd) }
        item {
            Column(Modifier.padding(4.dp)) {
                Text("连接方式", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                ConnectionTip(Icons.Outlined.Wifi, "在同一局域网", "手机与共享设备连接同一 Wi-Fi 或有线网络。")
                Spacer(Modifier.height(14.dp))
                ConnectionTip(Icons.Outlined.AddLink, "发起连接并保存凭据", "填写共享地址与账号；凭据仅加密保存在本机。")
                Spacer(Modifier.height(14.dp))
                ConnectionTip(Icons.Outlined.FolderOpen, "访问你的文件", "连接后从文件管理页上传、下载和整理共享文件。")
            }
        }
    }
    deleting?.let { profile ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除连接？") },
            text = { Text("将移除“${profile.name}”和它保存的凭据。共享文件夹里的文件不会被删除。") },
            confirmButton = { TextButton(onClick = { onAction(UiAction.DeleteProfile(profile.id)); deleting = null }) { Text("删除连接", color = Fluent.Red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun ConnectionTip(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(19.dp), tint = Fluent.Secondary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Fluent.Secondary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Fluent.Muted)
        }
    }
}

@Composable
internal fun ProfileEditor(profile: UiProfile?, onDismiss: () -> Unit, onSave: (UiProfile, Boolean) -> Unit) {
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var url by rememberSaveable(profile?.id) { mutableStateOf(profile?.url.orEmpty()) }
    var username by rememberSaveable(profile?.id) { mutableStateOf(profile?.username.orEmpty()) }
    var password by remember(profile?.id) { mutableStateOf(profile?.password.orEmpty()) }
    var domain by rememberSaveable(profile?.id) { mutableStateOf(profile?.domain.orEmpty()) }
    var rememberPassword by rememberSaveable(profile?.id) { mutableStateOf(profile?.rememberPassword ?: true) }
    var passwordVisible by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val validUrl = url.trim().startsWith("smb://", ignoreCase = true) || url.trim().startsWith("\\\\") || url.trim().startsWith('/')
    val valid = name.isNotBlank() && validUrl
    fun save(connect: Boolean) {
        attempted = true
        if (valid) onSave(UiProfile(profile?.id ?: UUID.randomUUID().toString(), name.trim(), url.trim(), username.trim(), password, domain.trim(), rememberPassword), connect)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 820.dp).imePadding(), shape = Fluent.Radius, color = Color.White) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 10.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (profile == null) "添加网络位置" else "编辑连接", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    ToolIcon(Icons.Outlined.Close, "关闭连接表单", onClick = onDismiss)
                }
                HorizontalDivider(color = Fluent.Border)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("连接你的电脑或 NAS 共享文件夹", style = MaterialTheme.typography.bodyMedium, color = Fluent.Secondary)
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("连接名称") }, placeholder = { Text("例如：家里的 NAS") }, singleLine = true, isError = attempted && name.isBlank(), shape = Fluent.Control)
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("SMB 地址") }, placeholder = { Text("smb://192.168.1.10/共享名称") }, supportingText = { Text(if (attempted && !validUrl) "请输入 smb://、UNC 或 /服务器/共享 地址" else "支持 smb://、\\\\服务器\\共享 或 /服务器/共享") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, isError = attempted && !validUrl, shape = Fluent.Control)
                    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名") }, placeholder = { Text("访客访问可留空") }, leadingIcon = { Icon(Icons.Outlined.PersonOutline, null, Modifier.size(20.dp)) }, singleLine = true, shape = Fluent.Control)
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, leadingIcon = { Icon(Icons.Outlined.Lock, null, Modifier.size(20.dp)) }, trailingIcon = { ToolIcon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (passwordVisible) "隐藏密码" else "显示密码") { passwordVisible = !passwordVisible } }, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, shape = Fluent.Control)
                    OutlinedTextField(domain, { domain = it }, Modifier.fillMaxWidth(), label = { Text("域（可选）") }, placeholder = { Text("WORKGROUP") }, singleLine = true, shape = Fluent.Control)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("记住凭据", style = MaterialTheme.typography.titleSmall)
                            Text("加密保存在这台设备上", style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
                        }
                        Switch(rememberPassword, { rememberPassword = it })
                    }
                }
                HorizontalDivider(color = Fluent.Border)
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    SecondaryButton("保存") { save(false) }
                    PrimaryButton("保存并连接", Icons.Outlined.Link) { save(true) }
                }
            }
        }
    }
}
