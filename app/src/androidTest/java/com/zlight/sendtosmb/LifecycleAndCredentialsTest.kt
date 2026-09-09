package com.zlight.sendtosmb

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zlight.sendtosmb.ui.UiProfile
import com.zlight.sendtosmb.ui.UiFile
import com.zlight.sendtosmb.ui.UiTransfer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleAndCredentialsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun savedPasswordsAreEncryptedAndRememberOptOutIsRespected() {
        val store = ProfileStore(app)
        val previous = store.read()
        try {
            val profile = UiProfile("secret-test", "凭据测试", "smb://127.0.0.1:1445/TESTSHARE", "android-test", "unique-secret-786461")
            store.write(listOf(profile), profile.id)
            assertEquals(profile, store.read().first.single())
            val persisted = app.getSharedPreferences("connections", 0).all.values.joinToString()
            assertFalse(persisted.contains(profile.password))
            assertFalse(persisted.contains(profile.username))
            store.write(listOf(profile.copy(rememberPassword = false)), profile.id)
            assertEquals("", store.read().first.single().password)
        } finally { store.write(previous.first, previous.second) }
    }

    @Test fun backgroundDisconnectsAndForegroundReconnectsSavedProfile() {
        val store = ProfileStore(app)
        val previous = store.read()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var model: ExplorerViewModel
        try {
            val profile = UiProfile("lifecycle-test", "隔离测试共享", "smb://127.0.0.1:1445/TESTSHARE", "android-test", "SendToSMB-test-only!")
            store.write(listOf(profile), profile.id)
            instrumentation.runOnMainSync {
                model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[ExplorerViewModel::class.java]
                model.onForeground()
            }
            await { model.state.value.connected && !model.state.value.loading }
            instrumentation.runOnMainSync { model.onBackground() }
            assertFalse(model.state.value.connected)
            assertFalse(model.state.value.connecting)
            instrumentation.runOnMainSync { model.onForeground() }
            await { model.state.value.connected && !model.state.value.loading }
            assertNotNull(model.state.value.capacity)
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
            store.write(previous.first, previous.second)
        }
    }

    @Test fun completedDownloadHistoryIsEncryptedAndRestored() {
        val transferStore = TransferStore(app)
        val previous = transferStore.read()
        val previousDirectory = transferStore.readDownloadDirectory()
        val source = UiFile("private-report.pdf", "Documents/private-report.pdf", false, 4096)
        val transfer = UiTransfer("history-encryption-test", source.name, "download", 4096, 4096,
            "completed", sourceFile = source, profileId = "profile-for-repeat")
        val directory = SavedDownloadDirectory("content://test-provider/tree/private-downloads", "Private downloads")
        try {
            transferStore.write(listOf(transfer))
            transferStore.writeDownloadDirectory(directory)
            assertEquals(transfer, transferStore.read().single())
            assertEquals(directory, transferStore.readDownloadDirectory())
            val persisted = app.getSharedPreferences("transfer_history", 0).all.values.joinToString()
            assertFalse(persisted.contains(source.name))
            assertFalse(persisted.contains(source.path))
            assertFalse(persisted.contains(directory.uri))
            assertFalse(persisted.contains(directory.name))
        } finally {
            transferStore.write(previous)
            transferStore.writeDownloadDirectory(previousDirectory)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 35_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("连接未在 35 秒内完成", condition())
    }
}
