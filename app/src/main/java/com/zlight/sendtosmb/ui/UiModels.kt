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
    data object OpenWifiSettings : UiAction
    data object DismissMessage : UiAction
}
