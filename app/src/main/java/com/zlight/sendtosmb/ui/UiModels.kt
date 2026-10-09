package com.zlight.sendtosmb.ui

data class UiProfile(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val rememberPassword: Boolean = true,
)

data class UiFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modifiedMillis: Long = 0,
)

data class UiCapacity(val total: Long, val free: Long)

data class UiUpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val mandatory: Boolean,
    val releaseNotes: String,
    val size: Long,
    val downloadUrl: String,
    val sha256: String,
)

data class UiUpdateState(
    val serverUrl: String = "",
    val checking: Boolean = false,
    val info: UiUpdateInfo? = null,
    val status: String? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val verifiedPath: String? = null,
)

data class UiTransfer(
    val id: String,
    val name: String,
    val direction: String,
    val done: Long = 0,
    val total: Long = 0,
    val status: String = "queued",
    val error: String? = null,
    val createdMillis: Long = System.currentTimeMillis(),
    val sourceFile: UiFile? = null,
    val profileId: String? = null,
    val bytesPerSecond: Double = 0.0,
    val averageBytesPerSecond: Double = 0.0,
)

data class UiState(
    val profiles: List<UiProfile> = emptyList(),
    val currentProfileId: String? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val networkAvailable: Boolean = true,
    val path: String = "",
    val files: List<UiFile> = emptyList(),
    val capacity: UiCapacity? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val transfers: List<UiTransfer> = emptyList(),
    val clipboardCount: Int = 0,
    val downloadDirectoryUri: String? = null,
    val downloadDirectoryName: String? = null,
    val einkMode: Boolean = false,
    val backgroundTransfers: Boolean = false,
    val transferBusy: Boolean = false,
    val averageBytesPerSecond: Double = 0.0,
    val update: UiUpdateState = UiUpdateState(),
)

sealed interface UiAction {
    data class Connect(val profileId: String) : UiAction
    data object Disconnect : UiAction
    data class SaveProfile(val profile: UiProfile, val connectNow: Boolean) : UiAction
    data class DeleteProfile(val profileId: String) : UiAction
    data class Navigate(val path: String) : UiAction
    data object Refresh : UiAction
    data object Upload : UiAction
    data class Download(val files: List<UiFile>, val profileId: String? = null) : UiAction
    data class CreateFolder(val name: String) : UiAction
    data class Rename(val file: UiFile, val newName: String) : UiAction
    data class Delete(val files: List<UiFile>) : UiAction
    data class SetClipboard(val files: List<UiFile>, val move: Boolean) : UiAction
    data object Paste : UiAction
    data class CancelTransfer(val id: String) : UiAction
    data object ClearCompletedTransfers : UiAction
    data object SelectDownloadDirectory : UiAction
    data object ClearDownloadDirectory : UiAction
    data class SetEinkMode(val enabled: Boolean) : UiAction
    data class SetBackgroundTransfers(val enabled: Boolean) : UiAction
    data class CheckUpdate(val serverUrl: String) : UiAction
    data object DownloadUpdate : UiAction
    data object CancelUpdateDownload : UiAction
    data object InstallUpdate : UiAction
    data class ReportUpdateStatus(val message: String) : UiAction
    data object OpenWifiSettings : UiAction
    data object DismissMessage : UiAction
}
