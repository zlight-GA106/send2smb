package com.zlight.sendtosmb

import android.content.Context
import java.util.UUID

data class AppSettings(
    val einkMode: Boolean,
    val updateServerUrl: String,
    val deviceId: String,
    val backgroundTransfers: Boolean = false,
)

/** Private, backup-excluded preferences for display mode and the EasyUpdate service. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun read(): AppSettings {
        val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
        return AppSettings(
            einkMode = prefs.getBoolean("eink_mode", false),
            updateServerUrl = prefs.getString("update_server_url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_UPDATE_URL,
            deviceId = deviceId,
            backgroundTransfers = prefs.getBoolean("background_transfers", false),
        )
    }

    fun writeEink(enabled: Boolean) {
        check(prefs.edit().putBoolean("eink_mode", enabled).commit()) { "显示设置保存失败" }
    }

    fun writeBackgroundTransfers(enabled: Boolean) {
        check(prefs.edit().putBoolean("background_transfers", enabled).commit()) { "后台传输设置保存失败" }
    }

    fun writeUpdateServerUrl(url: String) {
        check(prefs.edit().putString("update_server_url", url).commit()) { "更新服务地址保存失败" }
    }

    companion object {
        const val DEFAULT_UPDATE_URL = "http://192.168.95.55:19910"
    }
}
