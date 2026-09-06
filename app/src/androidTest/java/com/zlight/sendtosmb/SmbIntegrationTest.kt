package com.zlight.sendtosmb

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hierynomus.mssmb2.SMBApiException
import com.zlight.sendtosmb.data.SmbAddress
import com.zlight.sendtosmb.data.SmbRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * All mutation tests are hard-bound to the adb-reversed disposable TESTSHARE server.
 * Each writes solely below its own test-run UUID. realSmbUrl is used only by the read-only test.
 */
@RunWith(AndroidJUnit4::class)
class SmbIntegrationTest {
    @Test fun isolatedFileAndDirectoryRoundTrip() = withIsolatedFolder { repository, root ->
        val bytes = ByteArray(1_048_593) { index -> ((index * 31 + 7) % 251).toByte() }
        val input = "$root/上传 空间.bin"
        val uploadProgress = mutableListOf<Long>()
        repository.upload(input, ByteArrayInputStream(bytes), bytes.size.toLong(),
            onProgress = { done, _ -> uploadProgress += done })
        assertEquals(bytes.size.toLong(), uploadProgress.last())
        assertTrue(uploadProgress.zipWithNext().all { (previous, next) -> next >= previous })
        val uploaded = repository.list(root).single { it.name == "上传 空间.bin" }
        assertFalse(uploaded.isDirectory)
        assertEquals(bytes.size.toLong(), uploaded.size)
        val output = ByteArrayOutputStream()
        repository.download(input, output)
        assertArrayEquals(sha256(bytes), sha256(output.toByteArray()))

        // An accidental overwrite must leave the existing content intact.
        assertThrows(IllegalArgumentException::class.java) {
            repository.upload(input, ByteArrayInputStream(byteArrayOf(9)), 1)
        }
        repository.rename(input, "renamed.bin")
        repository.mkdir("$root/collection")
        repository.mkdir("$root/collection/nested")
        repository.move("$root/renamed.bin", "$root/collection/nested/payload.bin")
        repository.upload("$root/collection/empty.txt", ByteArrayInputStream(byteArrayOf()), 0)
        repository.copy("$root/collection", "$root/copied")
        val copied = ByteArrayOutputStream()
        repository.download("$root/copied/nested/payload.bin", copied)
        assertArrayEquals(sha256(bytes), sha256(copied.toByteArray()))
        val empty = ByteArrayOutputStream()
        repository.download("$root/copied/empty.txt", empty)
        assertEquals(0, empty.size())
        repository.move("$root/copied", "$root/moved")
        assertTrue(repository.list(root).any { it.name == "moved" && it.isDirectory })
        repository.delete("$root/moved")
        repository.delete("$root/collection")
        assertTrue(repository.list(root).isEmpty())
    }

    @Test fun isolatedCancelledUploadDoesNotPublishPartialFile() = withIsolatedFolder { repository, root ->
        var cancel = false
        assertThrows(CancellationException::class.java) {
            repository.upload("$root/cancelled.bin", ByteArrayInputStream(ByteArray(1_048_576)), 1_048_576,
                cancelled = { cancel }, onProgress = { done, _ -> if (done > 0) cancel = true })
        }
        assertTrue(repository.list(root).isEmpty())
    }

    @Test fun isolatedRejectsWrongPasswordAndReconnectsAfterDisconnect() {
        val repository = SmbRepository()
        try {
            assertThrows(Exception::class.java) {
                repository.connect(isolatedAddress(), USERNAME, "deliberately-incorrect-password")
            }
            assertFalse(repository.isConnected)
            repository.connect(isolatedAddress(), USERNAME, PASSWORD)
            assertTrue(repository.isConnected)
            repository.disconnect()
            assertFalse(repository.isConnected)
            assertThrows(Exception::class.java) { repository.list() }
            repository.connect(isolatedAddress(), USERNAME, PASSWORD)
            assertNotNull(repository.list())
        } finally { repository.disconnect() }
    }

    @Test fun isolatedReportsShareCapacity() {
        SmbRepository().use { repository ->
            repository.connect(isolatedAddress(), USERNAME, PASSWORD)
            val capacity = repository.capacity()
            assertTrue("Total capacity must be positive", capacity.totalBytes > 0)
            assertTrue(capacity.freeBytes in 0..capacity.totalBytes)
            Log.i(TAG, "Isolated fixture capacity (simulated): total=${capacity.totalBytes}, available=${capacity.freeBytes}")
        }
    }

    @Test fun disconnectInterruptsAnUnresponsiveLoopbackServer() {
        val repository = SmbRepository()
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            try {
                executor.submit {
                    server.accept().use {
                        accepted.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
                val connection = executor.submit<Throwable?> {
                    try {
                        repository.connect(SmbAddress.parse("smb://127.0.0.1:${server.localPort}/TESTSHARE"), USERNAME, PASSWORD)
                        null
                    } catch (error: Throwable) { error }
                }
                assertTrue("Loopback peer was not contacted", accepted.await(5, TimeUnit.SECONDS))
                val start = System.nanoTime()
                repository.disconnect()
                assertTrue("disconnect waited for network timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
                assertNotNull("Negotiation should be interrupted", connection.get(3, TimeUnit.SECONDS))
                assertFalse(repository.isConnected)
            } finally {
                repository.disconnect()
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    /** This method intentionally has no mutation or cleanup calls, including on failure. */
    @Test fun optionalRealShareReadOnlyListingAndCapacity() {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("realSmbUrl").orEmpty()
        assumeTrue("No realSmbUrl supplied; real-share read-only verification skipped", url.isNotBlank())
        SmbRepository().use { repository ->
            repository.connect(SmbAddress.parse(url), arguments.getString("realSmbUsername").orEmpty(),
                arguments.getString("realSmbPassword").orEmpty(), arguments.getString("realSmbDomain").orEmpty())
            val entries = repository.list()
            Log.i(TAG, "Real share read-only list succeeded: ${entries.size} entries")
            try {
                val capacity = repository.capacity()
                assertTrue(capacity.totalBytes > 0)
                assertTrue(capacity.freeBytes in 0..capacity.totalBytes)
                Log.i(TAG, "Real share read-only capacity: total=${capacity.totalBytes}, available=${capacity.freeBytes}")
            } catch (error: SMBApiException) {
                // Unsupported capacity is a server capability result; all other SMB errors fail the test.
                if (error.statusCode != 0xC00000BBL && error.statusCode != 0xC0000003L) throw error
                Log.i(TAG, "Real server does not support capacity query: ${error.status}")
            }
        }
    }

    private fun withIsolatedFolder(action: (SmbRepository, String) -> Unit) {
        val repository = SmbRepository()
        val root = "test-run-${UUID.randomUUID()}"
        var created = false
        try {
            repository.connect(isolatedAddress(), USERNAME, PASSWORD)
            repository.mkdir(root)
            created = true
            action(repository, root)
        } finally {
            try {
                if (created) {
                    if (!repository.isConnected) repository.connect(isolatedAddress(), USERNAME, PASSWORD)
                    repository.delete(root)
                }
            } finally { repository.disconnect() }
        }
    }

    private fun isolatedAddress(): SmbAddress = SmbAddress.parse("smb://127.0.0.1:1445/TESTSHARE").also {
        check(it.host == "127.0.0.1" && it.port == 1445 && it.share == "TESTSHARE" && it.basePath.isEmpty())
    }
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        private const val TAG = "SendToSmbIntegration"
        private const val USERNAME = "android-test"
        private const val PASSWORD = "SendToSMB-test-only!"
    }
}
