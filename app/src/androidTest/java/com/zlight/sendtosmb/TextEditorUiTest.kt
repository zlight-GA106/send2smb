package com.zlight.sendtosmb

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import com.zlight.sendtosmb.editor.TextEditorActivity
import org.junit.Rule
import org.junit.Test

class TextEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<TextEditorActivity>()

    @Test fun nativeEditingHasDirtyMarkerUndoRedoAndTouchTools() {
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("另存到设备").fetchSemanticsNodes().isNotEmpty() }
        onView(withContentDescription("文本正文")).perform(replaceText("first\n中文 second"))
        compose.onNodeWithText("未命名.txt ●").assertExists()
        compose.onNodeWithContentDescription("撤销").performClick()
        compose.onNodeWithContentDescription("重做").performClick()
        compose.onNodeWithText("未命名.txt ●").assertExists()
        compose.onNodeWithContentDescription("光标向左").performClick()
        compose.onNodeWithContentDescription("编辑器菜单").performClick()
        compose.onNodeWithText("查找、替换").performClick()
        compose.onNodeWithText("查找内容").performTextInput("first")
        compose.onNodeWithText("查找下一个").performClick()
        compose.onNodeWithContentDescription("编辑器菜单").performClick()
        compose.onNodeWithText("只读模式").performClick()
        compose.onNodeWithContentDescription("撤销").assertIsNotEnabled()
    }
}
