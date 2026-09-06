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
        compose.onNodeWithText("请连接局域网 Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
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

    @Test fun searchGridSelectionAndDeleteConfirmation() {
        val actions = mutableListOf<UiAction>()
        val profile = UiProfile("ui-test", "测试共享", "smb://127.0.0.1:1445/TESTSHARE")
        val files = listOf(UiFile("Documents", "Documents", true), UiFile("notes.txt", "notes.txt", false, 1024))
        compose.setContent { SendToSmbApp(UiState(profiles = listOf(profile), currentProfileId = profile.id,
            connected = true, files = files, capacity = UiCapacity(1_000_000, 500_000)), actions::add) }
        compose.onNodeWithText("搜索此文件夹").performTextInput("notes")
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
}
