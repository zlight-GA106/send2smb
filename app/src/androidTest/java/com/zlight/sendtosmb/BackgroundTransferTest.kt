package com.zlight.sendtosmb

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zlight.sendtosmb.data.SmbAddress
import com.zlight.sendtosmb.data.SmbRepository
import com.zlight.sendtosmb.ui.UiAction
import com.zlight.sendtosmb.ui.UiProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only the disposable, loopback TESTSHARE is modified. */
@RunWith(AndroidJUnit4::class)
class BackgroundTransferTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SendToSmbApplication

    @Test fun enabledUploadContinuesAfterActivityIsDestroyed() = withUpload(true) { scenario, model, source, remote, folder ->
        await { model.state.value.transfers.any { it.name == source.name && it.status == "running" && it.done > 0 } }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.close()
        await { !model.state.value.transferBusy }
        val completed = model.state.value.transfers.single { it.name == source.name }
        assertEquals("completed", completed.status)
        assertEquals(source.length(), completed.done)
        assertTrue(completed.averageBytesPerSecond > 0)
        assertTrue(model.state.value.averageBytesPerSecond > 0)
        assertFalse(model.state.value.connected) // idle sessions no longer need a background service
        val output = ByteArrayOutputStream()
        remote.download("$folder/${source.name}", output)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source.readBytes()),
            MessageDigest.getInstance("SHA-256").digest(output.toByteArray()))
    }

    @Test fun disabledBackgroundCancelsAnActiveUpload() = withUpload(false) { scenario, model, source, _, _ ->
        await { model.state.value.transfers.any { it.name == source.name && it.status == "running" && it.done > 0 } }
        scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(model.state.value.transferBusy)
        assertFalse(model.state.value.connected)
        assertEquals("cancelled", model.state.value.transfers.single { it.name == source.name }.status)
    }

    @Test fun notificationCancelStopsTheQueue() = withUpload(true) { _, model, source, _, _ ->
        await { model.state.value.transfers.any { it.name == source.name && it.status == "running" && it.done > 0 } }
        instrumentation.runOnMainSync {
            app.startService(Intent(app, BackgroundTransferService::class.java).setAction("com.zlight.sendtosmb.CANCEL_TRANSFERS"))
        }
        await { !model.state.value.transferBusy }
        assertEquals("cancelled", model.state.value.transfers.single { it.name == source.name }.status)
        assertFalse(model.state.value.connected)
    }

    private fun withUpload(enabled: Boolean,
        block: (ActivityScenario<MainActivity>, ExplorerViewModel, File, SmbRepository, String) -> Unit) {
        val profiles = ProfileStore(app)
        val previousProfiles = profiles.read()
        val settings = SettingsStore(app)
        val previousSetting = settings.read().backgroundTransfers
        val history = TransferStore(app).read()
        val folder = "background-test-${UUID.randomUUID()}"
        val source = File(app.cacheDir, "updates/$folder.bin")
        source.parentFile!!.mkdirs()
        source.outputStream().use { output ->
            val chunk = ByteArray(65536) { (it % 251).toByte() }
            repeat(512) { output.write(chunk) }
        }
        val remote = SmbRepository()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            remote.connect(SmbAddress.parse("smb://127.0.0.1:1445/TESTSHARE"), "android-test", "SendToSMB-test-only!", "")
            remote.mkdir(folder)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: ExplorerViewModel
            instrumentation.runOnMainSync {
                model = app.explorer
                model.dispatch(UiAction.SetBackgroundTransfers(enabled))
                model.dispatch(UiAction.SaveProfile(UiProfile(folder, "后台传输测试", "smb://127.0.0.1:1445/TESTSHARE",
                    "android-test", "SendToSMB-test-only!"), true))
            }
            await { model.state.value.connected && !model.state.value.loading }
            instrumentation.runOnMainSync {
                model.upload(listOf(FileProvider.getUriForFile(app, "${app.packageName}.files", source)), folder)
            }
            block(scenario, model, source, remote, folder)
        } finally {
            instrumentation.runOnMainSync { app.explorer.stopTransfers("测试结束") }
            scenario?.close()
            // Wait for cancelled socket cleanup before removing the disposable directory.
            Thread.sleep(500)
            runCatching { remote.delete(folder) }
            remote.disconnect()
            source.delete()
            profiles.write(previousProfiles.first, previousProfiles.second)
            settings.writeBackgroundTransfers(previousSetting)
            TransferStore(app).write(history)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 120_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("后台传输条件未在 120 秒内满足", condition())
    }
}
