package com.zlight.sendtosmb

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.zlight.sendtosmb.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TransfersResumeUiTest {
    @get:Rule val compose = createComposeRule()

    private val source = UiFile("video.mp4", "Backup/video.mp4", false, size = 4096)
    private val checkpoint = TransferResume(
        serverUrl = "smb://127.0.0.1:1445/TESTSHARE",
        username = "test", domain = "", remotePath = source.path, targetName = source.name,
        size = source.size,
    )

    @Test fun pausedTransferKeepsProgressAndResumesInsteadOfStartingAnotherDownload() {
        val actions = mutableListOf<UiAction>()
        val task = UiTransfer("resume-download", source.name, "download", done = 1024, total = 4096,
            status = "paused", error = "连接已断开，可以继续传输", sourceFile = source, resume = checkpoint)
        compose.setContent { SendToSmbApp(UiState(transfers = listOf(task)), actions::add) }
        compose.onNodeWithText("传输").performClick()
        compose.onNodeWithText("1 个任务等待继续").assertIsDisplayed()
        compose.onNodeWithText("下载 · 已暂停").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("25%").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("再次下载").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("清空已结束记录").assertCountEquals(0)
        compose.onNodeWithText("继续传输").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(UiAction.ResumeTransfer(task.id)), actions)
        }
    }

    @Test fun discardingRequiresConfirmationAndKeepingResumeDoesNothing() {
        val actions = mutableListOf<UiAction>()
        val task = UiTransfer("discard-download", source.name, "download", done = 1024, total = 4096,
            status = "paused", sourceFile = source, resume = checkpoint)
        compose.setContent { SendToSmbApp(UiState(transfers = listOf(task)), actions::add) }
        compose.onNodeWithText("传输").performClick()
        compose.onNodeWithText("放弃续传").performScrollTo().performClick()
        compose.onNodeWithText("放弃续传？").assertIsDisplayed()
        compose.onNodeWithText("将删除“video.mp4”的续传临时数据。已完成的文件和原有文件会保留。").assertIsDisplayed()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithText("保留续传").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithText("放弃续传").performScrollTo().performClick()
        compose.onNodeWithText("放弃并清理").performClick()
        compose.runOnIdle {
            assertEquals(listOf(UiAction.DiscardTransfer(task.id)), actions)
        }
    }

    @Test fun resumableActiveTransferOffersPauseAndNonResumableTaskKeepsCancel() {
        val actions = mutableListOf<UiAction>()
        val resumable = UiTransfer("active-upload", "video.mp4", "upload", done = 1024, total = 4096,
            status = "running", resume = checkpoint, bytesPerSecond = 1024.0, error = "正在校验源文件…")
        val ordinary = UiTransfer("ordinary-upload", "legacy.bin", "upload", total = 2048)
        compose.setContent { SendToSmbApp(UiState(transferBusy = true, transfers = listOf(resumable, ordinary)), actions::add) }
        compose.onNodeWithText("传输").performClick()
        compose.onNodeWithText("正在校验源文件…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停传输 video.mp4").performScrollTo().performClick()
        compose.onNodeWithContentDescription("取消传输 legacy.bin").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(UiAction.CancelTransfer(resumable.id), UiAction.CancelTransfer(ordinary.id)), actions)
        }
    }

    @Test fun clearingFinishedHistoryLeavesResumeControlsAvailable() {
        val actions = mutableListOf<UiAction>()
        val paused = UiTransfer("paused", source.name, "download", done = 1024, total = 4096,
            status = "failed", error = "网络暂时不可用", sourceFile = source, resume = checkpoint)
        val completed = UiTransfer("completed", "photo.jpg", "upload", done = 2048, total = 2048, status = "completed")
        compose.setContent { SendToSmbApp(UiState(transfers = listOf(paused, completed)), actions::add) }
        compose.onNodeWithText("传输").performClick()
        compose.onNodeWithContentDescription("清空已结束记录").performClick()
        compose.runOnIdle { assertEquals(listOf(UiAction.ClearCompletedTransfers), actions) }
        compose.onNodeWithText("下载 · 传输中断").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("继续传输").performScrollTo().assertIsDisplayed()
    }
}
