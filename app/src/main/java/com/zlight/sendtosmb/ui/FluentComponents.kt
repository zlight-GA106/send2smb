package com.zlight.sendtosmb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FluentCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = Fluent.Radius, color = Fluent.Surface, border = BorderStroke(1.dp, Fluent.Border)) {
        Column(content = content)
    }
}

@Composable
internal fun AccentIcon(icon: ImageVector, size: Int = 44, color: Color = Fluent.Blue, background: Color = Fluent.BlueTint) {
    Box(Modifier.size(size.dp).clip(Fluent.Control).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size((size * 0.53f).dp), tint = color)
    }
}

@Composable
internal fun PrimaryButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 44.dp), enabled = enabled, shape = Fluent.Control,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, maxLines = 1)
    }
}

@Composable
internal fun SecondaryButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 44.dp), enabled = enabled, shape = Fluent.Control,
        border = BorderStroke(1.dp, Fluent.Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = Fluent.Text),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, maxLines = 1)
    }
}

@Composable
internal fun ToolIcon(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = Fluent.Secondary, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, label, Modifier.size(21.dp), tint = if (enabled) tint else Fluent.Muted.copy(alpha = .45f))
    }
}

@Composable
internal fun StatusBadge(connected: Boolean, connecting: Boolean = false) {
    val color = if (connected) Fluent.Green else if (connecting) Fluent.Blue else Fluent.Secondary
    val background = if (connected) Fluent.GreenTint else if (connecting) Fluent.BlueTint else Fluent.Background
    Row(Modifier.clip(Fluent.Control).background(background).padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (connected) "已连接" else if (connecting) "连接中" else "未连接", color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun NetworkNotice(onWifi: () -> Unit) {
    Surface(color = Fluent.OrangeTint, shape = Fluent.Control, border = BorderStroke(1.dp, Color(0xFFF0DBBA))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WifiOff, null, Modifier.size(20.dp), tint = Fluent.Orange)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("未检测到局域网连接", style = MaterialTheme.typography.titleSmall, color = Fluent.Orange)
                Text("仍可直接尝试连接 SMB 服务器", style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
            }
            TextButton(onClick = onWifi) { Text("网络设置") }
        }
    }
}

@Composable
internal fun CapacityCard(capacity: UiCapacity?, name: String, connected: Boolean, modifier: Modifier = Modifier) {
    FluentCard(modifier) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentIcon(Icons.Outlined.Dns, 42)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name.ifBlank { "网络存储" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("SMB", color = Fluent.Muted, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
                val usage = if (capacity != null && capacity.total > 0) ((capacity.total - capacity.free).toFloat() / capacity.total.toFloat()).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { usage }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = if (usage > .9f) Fluent.Orange else Fluent.Blue, trackColor = Fluent.Background, drawStopIndicator = {})
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (capacity != null) "${formatSize(capacity.free)} 可用" else if (connected) "服务器未提供容量" else "连接后显示容量", color = Fluent.Secondary, style = MaterialTheme.typography.bodySmall)
                    if (capacity != null) Text("共 ${formatSize(capacity.total)}", color = Fluent.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun PageHeading(title: String, subtitle: String = "", modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Fluent.Secondary)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun FileTypeIcon(file: UiFile, size: Int = 40) {
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val (icon, color, background) = when {
        file.isDirectory -> Triple(Icons.Outlined.Folder, Color(0xFFC28B1F), Color(0xFFFFF6DF))
        ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "svg", "bmp") -> Triple(Icons.Outlined.Image, Color(0xFF8764B8), Color(0xFFF4EFFA))
        ext in setOf("mp4", "mkv", "avi", "mov", "webm") -> Triple(Icons.Outlined.Movie, Color(0xFFB44875), Color(0xFFFCEEF4))
        ext in setOf("mp3", "flac", "wav", "ogg", "m4a") -> Triple(Icons.Outlined.AudioFile, Color(0xFFB44875), Color(0xFFFCEEF4))
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> Triple(Icons.Outlined.FolderZip, Color(0xFF9A7621), Color(0xFFFFF6DF))
        ext == "pdf" -> Triple(Icons.Outlined.PictureAsPdf, Fluent.Red, Color(0xFFFCEFF0))
        ext in setOf("xls", "xlsx", "csv") -> Triple(Icons.Outlined.TableChart, Fluent.Green, Fluent.GreenTint)
        else -> Triple(Icons.Outlined.Description, Fluent.Blue, Fluent.BlueTint)
    }
    AccentIcon(icon, size, color, background)
}
