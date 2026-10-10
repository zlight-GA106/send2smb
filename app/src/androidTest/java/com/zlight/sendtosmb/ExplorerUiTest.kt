package com.zlight.sendtosmb

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.zlight.sendtosmb.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExplorerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disconnectedScreenOffersSetupAndWifiSettings() {
        val actions = mutableListOf<UiAction>()
        compose.setContent { SendToSmbApp(UiState(networkAvailable = false), actions::add) }
        compose.onNodeWithText("未检测到局域网连接").assertIsDisplayed()
        compose.onNodeWithText("网络设置").performClick()
        compose.runOnIdle { assertTrue(actions.contains(UiAction.OpenWifiSettings)) }
        compose.onNodeWithText("添加网络位置").performClick()
        compose.onNodeWithText("SMB 地址").assertIsDisplayed()
        compose.onNodeWithText("连接名称").performTextInput("测试连接")
        compose.onNodeWithText("SMB 地址").performTextInput("/127.0.0.1:1445/TESTSHARE")
        compose.onNodeWithText("保存并连接").performClick()
        compose.runOnIdle {
            val saved = actions.filterIsInstance<UiAction.SaveProfile>().single()
            assertEquals("测试连接", saved.profile.name)
            assertTrue(saved.connectNow)
        }
    }

    @Test fun txtFilesOpenEditorAndFolderCanCreateTextDocument() {
        val actions = mutableListOf<UiAction>()
        val file = UiFile("notes.txt", "notes.txt", false, 20)
        compose.setContent { SendToSmbApp(UiState(connected = true, files = listOf(file)), actions::add) }
        compose.onNodeWithText("文件管理").performClick()
        compose.onNodeWithText("notes.txt").performClick()
        compose.runOnIdle { assertTrue(actions.contains(UiAction.EditTextFile(file))) }
        compose.onNodeWithContentDescription("文件操作").performClick()
        compose.onNodeWithText("新建文本文档").performClick()
        compose.onNodeWithText("文件名").performTextClearance()
        compose.onNodeWithText("文件名").performTextInput("diary.txt")
        compose.onNodeWithText("新建").performClick()
        compose.runOnIdle { assertTrue(actions.contains(UiAction.CreateTextFile("diary.txt"))) }
    }

    @Test fun settingsPageTogglesEinkAndChecksUpdates() {
        val actions = mutableListOf<UiAction>()
        compose.setContent { SendToSmbApp(UiState(update = UiUpdateState(serverUrl = "http://192.168.95.55:19910")), actions::add) }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("E Ink 模式").assertIsDisplayed()
        compose.onNodeWithText("EasyUpdate 更新").assertIsDisplayed()
        compose.onNodeWithText("服务地址").assertIsDisplayed()
        compose.onNodeWithContentDescription("E Ink 模式").performClick()
        compose.onNodeWithContentDescription("在后台继续运行").assertIsOff().performClick()
        compose.onNodeWithText("检查更新").performClick()
        compose.runOnIdle {
            assertEquals(true, actions.filterIsInstance<UiAction.SetEinkMode>().single().enabled)
            assertEquals(true, actions.filterIsInstance<UiAction.SetBackgroundTransfers>().single().enabled)
            assertEquals("http://192.168.95.55:19910", actions.filterIsInstance<UiAction.CheckUpdate>().single().serverUrl)
        }
    }

    @Test fun searchGridSelectionAndDeleteConfirmation() {
        val actions = mutableListOf<UiAction>()
        val profile = UiProfile("ui-test", "测试共享", "smb://127.0.0.1:1445/TESTSHARE")
        val files = listOf(UiFile("Documents", "Documents", true), UiFile("notes.txt", "notes.txt", false, 1024))
        compose.setContent { SendToSmbApp(UiState(profiles = listOf(profile), currentProfileId = profile.id,
            connected = true, files = files, capacity = UiCapacity(1_000_000, 500_000)), actions::add) }
        compose.onNodeWithText("文件管理").performClick()
        compose.onNodeWithText("搜索文件或文件夹").performTextInput("notes")
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        compose.onAllNodesWithText("Documents").assertCountEquals(0)
        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.onNodeWithContentDescription("切换到网格").performClick()
        compose.onNodeWithText("Documents").assertIsDisplayed()
        compose.onNodeWithContentDescription("notes.txt的更多操作").performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(actions.none { it is UiAction.Delete }) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(actions.none { it is UiAction.Delete }) }
        compose.onNodeWithText("Documents").performClick()
        compose.runOnIdle { assertTrue(actions.contains(UiAction.Navigate("Documents"))) }
    }

    @Test fun homeAndFileManagerAreSeparateAndDownloadHistoryCanRepeat() {
        val actions = mutableListOf<UiAction>()
        val profile = UiProfile("history-profile", "测试共享", "smb://127.0.0.1:1445/TESTSHARE")
        val source = UiFile("report.pdf", "Documents/report.pdf", false, 2048)
        val history = UiTransfer("history-download", source.name, "download", 2048, 2048, "completed",
            sourceFile = source, profileId = profile.id)
        compose.setContent { SendToSmbApp(UiState(profiles = listOf(profile), currentProfileId = profile.id,
            connected = true, transfers = listOf(history)), actions::add) }

        compose.onNodeWithText("文件").assertIsDisplayed()
        compose.onNodeWithText("//127.0.0.1:1445/TESTSHARE").assertIsDisplayed()
        compose.onNodeWithText("连接，让文件触手可及").assertIsDisplayed()
        compose.onNodeWithText("文件管理").performClick()
        compose.onNodeWithText("搜索文件或文件夹").assertIsDisplayed()
        compose.onNodeWithText("传输").performClick()
        compose.onNodeWithText("传输记录默认保存在本机").assertIsDisplayed()
        compose.onNodeWithText("指定保存目录").assertIsDisplayed()
        compose.onNodeWithText("选择目录").performClick()
        compose.runOnIdle { assertTrue(actions.contains(UiAction.SelectDownloadDirectory)) }
        compose.onNodeWithText("再次下载").performClick()
        compose.runOnIdle {
            val repeated = actions.filterIsInstance<UiAction.Download>().single()
            assertEquals(listOf(source), repeated.files)
            assertEquals(profile.id, repeated.profileId)
        }
    }

    @Test fun transferHeaderAndTasksShowSpeeds() {
        compose.setContent { SendToSmbApp(UiState(backgroundTransfers = true, transferBusy = true,
            averageBytesPerSecond = 2.0 * 1024 * 1024, transfers = listOf(
                UiTransfer("running", "video.mp4", "upload", done = 1024, total = 4096, status = "running", bytesPerSecond = 1024.0),
                UiTransfer("queued", "photo.jpg", "upload", total = 4096))), onAction = {}) }
        compose.onNodeWithText("传输").performClick()
        compose.onNodeWithText("平均速度").assertIsDisplayed()
        compose.onNodeWithText("2.0 MB/s").assertIsDisplayed()
        compose.onNodeWithText("速度 1.0 KB/s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("速度 —").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已允许后台传输，锁屏或切换应用后任务会继续运行。").performScrollTo().assertIsDisplayed()
    }
}
