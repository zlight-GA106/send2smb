package com.zlight.sendtosmb

import com.zlight.sendtosmb.ui.UiFile
import com.zlight.sendtosmb.ui.UiTransfer
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import java.util.UUID

class TransferResumeTest {
    private fun id(): String = UUID.randomUUID().toString()
    private fun descriptor() = TransferResume(
        serverUrl = "smb://127.0.0.1:1445/TESTSHARE/备份", username = "用户", domain = "宿舍",
        remotePath = "照片/假期.txt", targetName = "假期.txt", sourceUri = "content://photos/document/abc%2Fdef",
        treeUri = "content://documents/tree/primary%3ADocuments", targetUri = "content://documents/document/new",
        sha256 = "ab".repeat(32), size = 1_234_567, modified = 1_700_000_000_000,
        uploadCacheName = id() + ".upload", uploadGrantOwned = true, planReady = true,
        entries = listOf(
            ResumeDownloadEntry(id(), UiFile("子目录", "照片/子目录", true), "假期/子目录"),
            ResumeDownloadEntry(id(), UiFile("中文 + = #.txt", "照片/子目录/中文 + = #.txt", false, 192, 3_000),
                "假期/子目录/中文 + = #.txt", "cd".repeat(32), "content://documents/document/7", true),
        ),
    )

    @Test fun descriptorRoundTripPreservesUnicodeUrisIdentityAndCompletedChildren() {
        val value = descriptor()
        assertEquals(value, TransferResumeCodec.decode(TransferResumeCodec.encode(value)))
    }

    @Test fun unplannedRootUploadAndEmptyFilesRoundTrip() {
        val value = TransferResume("smb://server/share", "", "", "", "", size = 0)
        assertEquals(value, TransferResumeCodec.decode(TransferResumeCodec.encode(value)))
        assertNull(value.sourceUri)
        assertFalse(value.planReady)
    }

    @Test fun interruptedTasksRestorePausedWithoutLosingProgressOrPlan() {
        val value = descriptor()
        listOf("running", "queued", "discarding").forEach { status ->
            val transfer = UiTransfer(id(), "假期", "download", done = 192, total = 500, status = status,
                bytesPerSecond = 9_000.0, averageBytesPerSecond = 8_000.0, resume = value)
            val restored = TransferHistoryPolicy.restored(transfer)
            assertEquals("paused", restored.status)
            assertEquals("上次传输已中断，可继续", restored.error)
            assertEquals(192, restored.done)
            assertEquals(value, restored.resume)
            assertEquals(0.0, restored.bytesPerSecond, 0.0)
            assertEquals(8_000.0, restored.averageBytesPerSecond, 0.0)
        }
    }

    @Test fun alreadyPausedOrFailedTasksKeepTheirExplanation() {
        listOf("paused", "failed", "cancelled").forEach { status ->
            val transfer = UiTransfer(id(), "file", "upload", status = status, error = "源文件已改变", resume = descriptor())
            assertEquals(transfer, TransferHistoryPolicy.restored(transfer))
        }
    }

    @Test fun resumeTasksNeverEvictedByBoundedTerminalHistory() {
        val descriptor = descriptor()
        val durable = (0 until 230).map { index ->
            UiTransfer(id(), "$index", "upload", status = if (index % 2 == 0) "running" else "paused",
                createdMillis = index.toLong(), resume = descriptor)
        }
        val history = (0 until 300).map { index ->
            UiTransfer(id(), "$index", "download", status = "completed", createdMillis = (1_000 + index).toLong())
        }
        val selected = TransferHistoryPolicy.forPersistence(durable + history)
        assertEquals(430, selected.size)
        assertTrue(selected.containsAll(durable))
        assertEquals(history.takeLast(200), selected.filter { it.resume == null })
    }

    @Test fun legacyHistoryRemainsCompatibleAndTransientLegacyWorkIsNotRestored() {
        val completed = UiTransfer("legacy-not-uuid", "old", "download", status = "completed")
        val active = UiTransfer("old-active", "old", "upload", status = "running")
        assertEquals(listOf(completed), TransferHistoryPolicy.forPersistence(listOf(active, completed)))
        assertEquals(completed, TransferHistoryPolicy.restored(completed))
        assertTrue(TransferHistoryPolicy.forPersistence(listOf(completed), 0).isEmpty())
    }

    @Test fun rejectsInvalidIdsTraversalAndUnsafeCacheBasenames() {
        val value = descriptor()
        listOf("1-1-1-1-1", "../" + id(), "", id() + "/file").forEach { bad ->
            assertFalse(TransferResumeCodec.validId(bad))
            assertThrows(IllegalArgumentException::class.java) {
                TransferResumeCodec.encode(value.copy(entries = listOf(value.entries.first().copy(id = bad))))
            }
        }
        listOf("../backup", "/absolute", "dir/../other", "C:\\backup").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { TransferResumeCodec.encode(value.copy(remotePath = bad)) }
            assertThrows(IllegalArgumentException::class.java) {
                TransferResumeCodec.encode(value.copy(entries = listOf(value.entries.first().copy(relativePath = bad))))
            }
        }
        listOf("../cache.upload", "cache.upload", id() + ".part", ".", "..").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { TransferResumeCodec.encode(value.copy(uploadCacheName = bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferHistoryPolicy.forPersistence(listOf(UiTransfer("invalid", "f", "upload", resume = value)))
        }
    }

    @Test fun rejectsAmbiguousPlansCorruptDigestsAndCredentialsInAddress() {
        val value = descriptor()
        val first = value.entries.first()
        listOf(
            value.copy(entries = listOf(first, first.copy(relativePath = "other"))),
            value.copy(entries = listOf(first, first.copy(id = id()))),
            value.copy(sha256 = "not-a-hash"),
            value.copy(size = -2),
            value.copy(modified = -2),
            value.copy(sourceUri = "file:///private/secret"),
            value.copy(targetUri = "content://"),
            value.copy(serverUrl = "smb://user:secret@server/share"),
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { TransferResumeCodec.encode(bad) } }
    }

    @Test fun rejectsUnsupportedTruncatedOrMalformedSerializedDescriptors() {
        val properties = Properties().apply { load(StringReader(TransferResumeCodec.encode(descriptor()))) }
        fun serialized(): String = StringWriter().also { properties.store(it, null) }.toString()
        properties.setProperty("version", "2")
        assertThrows(IllegalArgumentException::class.java) { TransferResumeCodec.decode(serialized()) }
        properties.setProperty("version", "1")
        properties.setProperty("entries", "100001")
        assertThrows(IllegalArgumentException::class.java) { TransferResumeCodec.decode(serialized()) }
        properties.setProperty("entries", "2")
        properties.setProperty("planReady", "maybe")
        assertThrows(IllegalArgumentException::class.java) { TransferResumeCodec.decode(serialized()) }
        properties.setProperty("planReady", "true")
        properties.remove("entry.1.path")
        assertThrows(IllegalArgumentException::class.java) { TransferResumeCodec.decode(serialized()) }
    }
}
