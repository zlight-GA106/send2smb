package com.zlight.sendtosmb.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object Fluent {
    val Blue = Color(0xFF0F6CBD)
    val BlueTint = Color(0xFFEBF3FC)
    val Background = Color(0xFFF3F5F8)
    val Surface = Color.White
    val Border = Color(0xFFE1E5EA)
    val Text = Color(0xFF242424)
    val Secondary = Color(0xFF616870)
    val Muted = Color(0xFF9299A2)
    val Green = Color(0xFF107C10)
    val GreenTint = Color(0xFFEAF5EA)
    val Orange = Color(0xFFBC6B08)
    val OrangeTint = Color(0xFFFFF5E6)
    val Red = Color(0xFFB10E1E)
    val Radius = RoundedCornerShape(12.dp)
    val Control = RoundedCornerShape(8.dp)
}

@Composable
internal fun FluentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Fluent.Blue,
            onPrimary = Color.White,
            primaryContainer = Fluent.BlueTint,
            onPrimaryContainer = Fluent.Blue,
            secondary = Fluent.Secondary,
            background = Fluent.Background,
            onBackground = Fluent.Text,
            surface = Fluent.Surface,
            onSurface = Fluent.Text,
            onSurfaceVariant = Fluent.Secondary,
            surfaceVariant = Fluent.Background,
            outline = Color(0xFFAEB5BD),
            outlineVariant = Fluent.Border,
            error = Fluent.Red,
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 40.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 36.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
            titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
            labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
        ),
        shapes = Shapes(extraSmall = Fluent.Control, small = Fluent.Control, medium = Fluent.Radius, large = Fluent.Radius, extraLarge = Fluent.Radius),
        content = content,
    )
}

internal fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) { value /= 1024.0; index++ }
    return String.format(Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}

internal fun formatDate(millis: Long): String = if (millis <= 0) "—" else SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(millis))

internal fun fileKind(file: UiFile): String {
    if (file.isDirectory) return "文件夹"
    val ext = file.name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    return if (ext.isBlank()) "文件" else "$ext 文件"
}

internal fun parentPath(path: String): String = path.trim('/').substringBeforeLast('/', "")
