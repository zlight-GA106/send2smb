package com.zlight.sendtosmb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class AppDestination(val title: String, val icon: ImageVector)
private val destinations = listOf(
    AppDestination("首页", Icons.Outlined.Home),
    AppDestination("文件管理", Icons.Outlined.FolderOpen),
    AppDestination("传输", Icons.Outlined.SwapVert),
    AppDestination("连接", Icons.Outlined.Storage),
    AppDestination("设置", Icons.Outlined.Settings),
)

@Composable
fun SendToSmbApp(state: UiState, onAction: (UiAction) -> Unit) {
    FluentTheme {
        var destination by rememberSaveable { mutableIntStateOf(0) }
        var editingProfile by remember { mutableStateOf<UiProfile?>(null) }
        var showProfileEditor by rememberSaveable { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val currentProfile = state.profiles.firstOrNull { it.id == state.currentProfileId }
        val scope = rememberCoroutineScope()

        LaunchedEffect(state.message) {
            state.message?.let { message ->
                scope.launch { snackbar.showSnackbar(message, duration = SnackbarDuration.Long) }
                onAction(UiAction.DismissMessage)
            }
        }
        LaunchedEffect(state.connected) { if (state.connected && showProfileEditor) showProfileEditor = false }
        BackHandler(destination != 0 && !showProfileEditor) { destination = 0 }

        BoxWithConstraints(Modifier.fillMaxSize().background(Fluent.Background).einkGrayscale(state.einkMode)) {
            val wide = maxWidth >= 840.dp
            Scaffold(
                containerColor = Fluent.Background,
                snackbarHost = { SnackbarHost(snackbar) },
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!wide) {
                        Surface(color = Color.White, shadowElevation = 0.dp) {
                            Column {
                                HorizontalDivider(color = Fluent.Border)
                                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    destinations.forEachIndexed { index, item ->
                                        val selected = destination == index
                                        Column(Modifier.weight(1f).fillMaxHeight().clickable { destination = index }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                            Box(Modifier.width(48.dp).height(30.dp).clip(Fluent.Control).background(if (selected) Fluent.BlueTint else Color.Transparent), contentAlignment = Alignment.Center) {
                                                BadgedBox(badge = { if (index == 2 && state.transfers.any { it.status == "running" || it.status == "queued" }) Badge(containerColor = Fluent.Blue) }) {
                                                    Icon(item.icon, item.title, Modifier.size(22.dp), tint = if (selected) Fluent.Blue else Fluent.Secondary)
                                                }
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(item.title, style = MaterialTheme.typography.labelSmall, color = if (selected) Fluent.Blue else Fluent.Secondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (wide) {
                        AppSidebar(state, destination, onDestination = { destination = it }, onAdd = { editingProfile = null; showProfileEditor = true }, onAction = onAction)
                        VerticalDivider(color = Fluent.Border)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (!wide) AppHeader(state)
                        if (!state.networkAvailable) {
                            Box(Modifier.padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 8.dp)) {
                                NetworkNotice { onAction(UiAction.OpenWifiSettings) }
                            }
                        }
                        when (destination) {
                            0 -> HomePage(state, wide, onAction,
                                onConnect = {
                                    if (state.profiles.isEmpty()) { editingProfile = null; showProfileEditor = true } else destination = 3
                                },
                                onOpenFiles = { destination = 1 })
                            1 -> ExplorerPage(state, wide, onAction, onConnect = {
                                if (state.profiles.isEmpty()) { editingProfile = null; showProfileEditor = true } else destination = 3
                            })
                            2 -> TransfersPage(state, wide, onAction)
                            3 -> ConnectionsPage(state, wide, onAction,
                                onAdd = { editingProfile = null; showProfileEditor = true },
                                onEdit = { editingProfile = it; showProfileEditor = true })
                            4 -> SettingsPage(state, wide, onAction)
                        }
                    }
                }
            }
        }

        if (showProfileEditor) ProfileEditor(
            profile = editingProfile,
            onDismiss = { showProfileEditor = false },
            onSave = { profile, connect ->
                onAction(UiAction.SaveProfile(profile, connect))
                showProfileEditor = false
                if (connect) destination = 1
            },
        )
    }
}

@Composable
private fun AppHeader(state: UiState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(31.dp).clip(Fluent.Control).background(Fluent.Blue), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.DriveFolderUpload, null, Modifier.size(20.dp), tint = Color.White)
        }
        Spacer(Modifier.width(9.dp))
        Text("SendToSMB", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        StatusBadge(state.connected, state.connecting)
    }
}

@Composable
private fun AppSidebar(state: UiState, selected: Int, onDestination: (Int) -> Unit, onAdd: () -> Unit, onAction: (UiAction) -> Unit) {
    Column(Modifier.width(240.dp).fillMaxHeight().background(Color(0xFFF8F9FB)).padding(horizontal = 16.dp, vertical = 24.dp)) {
        Row(Modifier.padding(start = 8.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(Fluent.Control).background(Fluent.Blue), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DriveFolderUpload, null, Modifier.size(22.dp), tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Text("SendToSMB", style = MaterialTheme.typography.titleMedium)
        }
        destinations.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(Fluent.Control).background(if (selected == index) Fluent.BlueTint else Color.Transparent).clickable { onDestination(index) }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(item.icon, null, Modifier.size(21.dp), tint = if (selected == index) Fluent.Blue else Fluent.Secondary)
                Spacer(Modifier.width(12.dp))
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = if (selected == index) Fluent.Blue else Fluent.Text)
                if (index == 2 && state.transfers.any { it.status == "running" }) {
                    Spacer(Modifier.weight(1f)); Badge(containerColor = Fluent.Blue)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("网络位置", style = MaterialTheme.typography.labelMedium, color = Fluent.Secondary, modifier = Modifier.weight(1f))
            ToolIcon(Icons.Outlined.Add, "添加连接", onClick = onAdd)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (state.profiles.isEmpty()) Text("添加你的第一台共享设备", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = Fluent.Muted)
            state.profiles.forEach { profile ->
                val active = profile.id == state.currentProfileId && state.connected
                Row(Modifier.fillMaxWidth().clip(Fluent.Control).clickable { onAction(UiAction.Connect(profile.id)); onDestination(1) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Computer, null, Modifier.size(20.dp), tint = if (active) Fluent.Blue else Fluent.Secondary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(if (active) "已连接" else "点击连接", style = MaterialTheme.typography.bodySmall, color = if (active) Fluent.Green else Fluent.Muted)
                    }
                }
            }
        }
        HorizontalDivider(color = Fluent.Border)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Wifi, null, Modifier.size(18.dp), tint = if (state.networkAvailable) Fluent.Green else Fluent.Orange)
            Spacer(Modifier.width(8.dp))
            Text(if (state.networkAvailable) "局域网访问" else "未检测到局域网", style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
        }
        Text("离开自动断开 · 返回自动连接", Modifier.padding(start = 10.dp, top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Fluent.Muted)
    }
}
