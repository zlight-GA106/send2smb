package com.zlight.sendtosmb.data

import com.hierynomus.mssmb2.SMBApiException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Actual SMB2 I/O, exclusively inside a random directory on the disposable loopback fixture. */
class ResumableSmbTest {
    @get:Rule val local = TemporaryFolder()
    private val payload = ByteArray(3 * 1024 * 1024 + 137) { (it * 31 + it / 257).toByte() }
    private fun connect() = SmbRepository().also {
        it.connect(SmbAddress.parse("smb://127.0.0.1:1445/TESTSHARE"), "android-test", "SendToSMB-test-only!")
    }
    private fun isolated(block: (SmbRepository, String) -> Unit) {
        assumeTrue(System.getenv("SENDTOSMB_RESUME_SMB_TEST") == "1")
        connect().use { control ->
            val folder = "resume-test-${UUID.randomUUID()}"
            control.mkdir(folder)
            try { block(control, folder) } finally { control.delete(folder) }
        }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun upload(smb: SmbRepository, path: String, bytes: ByteArray = payload) =
        smb.upload(path, ByteArrayInputStream(bytes), bytes.size.toLong())
    private fun bytes(smb: SmbRepository, path: String) = ByteArrayOutputStream().also { smb.download(path, it) }.toByteArray()
    private fun partial(folder: String, id: String) = "$folder/.sendtosmb-resume-$id.part"
    private fun pauseUpload(path: String, id: String, abrupt: Boolean = false) {
        connect().use { smb ->
            val stop = AtomicBoolean()
            assertThrows(CancellationException::class.java) {
                smb.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload),
                    cancelled = stop::get, onProgress = { done, _ ->
                        if (done >= 512 * 1024) {
                            if (abrupt) smb.disconnect() else stop.set(true)
                        }
                    })
            }
        }
    }

    @Test fun cancelledUploadResumesFromVerifiedPrefixUsingNewConnection() = isolated { control, folder ->
        val path = "$folder/upload.bin"
        val id = UUID.randomUUID().toString()
        pauseUpload(path, id)
        val part = bytes(control, partial(folder, id))
        assertEquals(512 * 1024, part.size)
        assertArrayEquals(payload.copyOf(part.size), part)
        assertFalse(control.list(folder).any { it.name == "upload.bin" })
        var first = -1L
        connect().use { smb ->
            smb.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload),
                onProgress = { done, _ -> if (first < 0) first = done })
        }
        assertEquals(part.size.toLong(), first)
        assertArrayEquals(payload, bytes(control, path))
        assertTrue(control.list(folder).none { it.name.endsWith(".part") })
    }

    @Test fun disconnectedSocketUploadKeepsPartialAndNewConnectionCanResume() = isolated { control, folder ->
        val path = "$folder/disconnected.bin"
        val id = UUID.randomUUID().toString()
        pauseUpload(path, id, abrupt = true)
        assertEquals(512 * 1024, bytes(control, partial(folder, id)).size)
        connect().use { it.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload)) }
        assertArrayEquals(payload, bytes(control, path))
    }

    @Test fun uploadRejectsTamperedServerPrefixWithoutChangingIt() = isolated { control, folder ->
        val path = "$folder/tampered.bin"
        val id = UUID.randomUUID().toString()
        pauseUpload(path, id)
        val tampered = bytes(control, partial(folder, id)).apply { this[12] = (this[12].toInt() xor 1).toByte() }
        control.upload(partial(folder, id), ByteArrayInputStream(tampered), tampered.size.toLong(), overwrite = true)
        connect().use { smb ->
            assertThrows(ResumeConflictException::class.java) {
                smb.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload))
            }
        }
        assertArrayEquals(tampered, bytes(control, partial(folder, id)))
        assertFalse(control.list(folder).any { it.name == "tampered.bin" })
    }

    @Test fun uploadRejectsChangedSourceEvenWhenExistingPrefixStillMatches() = isolated { control, folder ->
        val path = "$folder/changed-source.bin"
        val id = UUID.randomUUID().toString()
        pauseUpload(path, id)
        val changed = payload.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        connect().use { smb ->
            assertThrows(ResumeConflictException::class.java) {
                smb.uploadResumable(path, id, { ByteArrayInputStream(changed) }, payload.size.toLong(), hash(payload))
            }
        }
        assertArrayEquals(changed, bytes(control, partial(folder, id)))
        assertFalse(control.list(folder).any { it.name == "changed-source.bin" })
    }

    @Test fun targetCollisionBeforeRenameKeepsBothExistingTargetAndVerifiedPartial() = isolated { control, folder ->
        val path = "$folder/collision.bin"
        val id = UUID.randomUUID().toString()
        connect().use { smb ->
            assertThrows(Exception::class.java) {
                smb.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload),
                    beforeCommit = { upload(control, path, "unrelated file".toByteArray()) })
            }
        }
        assertEquals("unrelated file", bytes(control, path).toString(Charsets.UTF_8))
        assertArrayEquals(payload, bytes(control, partial(folder, id)))
        connect().use { smb ->
            assertThrows(ResumeConflictException::class.java) {
                smb.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload))
            }
        }
        assertEquals("unrelated file", bytes(control, path).toString(Charsets.UTF_8))
    }

    @Test fun completedRenameCanBeRecognizedAfterLostTaskCompletionRecord() = isolated { control, folder ->
        val path = "$folder/idempotent.bin"
        val id = UUID.randomUUID().toString()
        connect().use { it.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload)) }
        val modified = control.list(folder).single().lastModified
        connect().use {
            it.uploadResumable(path, id, { throw IOException("source intentionally unavailable after commit") }, payload.size.toLong(), hash(payload))
        }
        assertEquals(modified, control.list(folder).single().lastModified)
        assertArrayEquals(payload, bytes(control, path))
    }

    @Test fun failureBeforeCommitRetainsCompletePartialAndRetryCommitsIt() = isolated { control, folder ->
        val path = "$folder/precommit.bin"
        val id = UUID.randomUUID().toString()
        connect().use { smb ->
            assertThrows(IOException::class.java) {
                smb.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload),
                    beforeCommit = { throw IOException("simulated process interruption before rename") })
            }
        }
        assertArrayEquals(payload, bytes(control, partial(folder, id)))
        assertFalse(control.list(folder).any { it.name == "precommit.bin" })
        var first = -1L
        connect().use { it.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload),
            onProgress = { done, _ -> if (first < 0) first = done }) }
        assertEquals(payload.size.toLong(), first)
        assertArrayEquals(payload, bytes(control, path))
    }

    @Test fun cancelledDownloadResumesFromFsyncedPrefixUsingNewConnection() = isolated { control, folder ->
        val path = "$folder/download.bin"
        upload(control, path)
        val expected = control.resumeFingerprint(path)
        assertEquals(hash(payload), expected.sha256)
        val part = local.newFile()
        val stop = AtomicBoolean()
        connect().use { smb ->
            assertThrows(CancellationException::class.java) {
                smb.downloadResumable(path, part, expected, stop::get, onProgress = { done, _ -> if (done >= 512 * 1024) stop.set(true) })
            }
        }
        assertEquals(512 * 1024L, part.length())
        assertArrayEquals(payload.copyOf(part.length().toInt()), part.readBytes())
        var first = -1L
        connect().use { it.downloadResumable(path, part, expected, onProgress = { done, _ -> if (first < 0) first = done }) }
        assertEquals(512 * 1024L, first)
        assertArrayEquals(payload, part.readBytes())
    }

    @Test fun downloadRejectsTamperedLocalPrefixAndRetainsIt() = isolated { control, folder ->
        val path = "$folder/local-conflict.bin"
        upload(control, path)
        val expected = control.resumeFingerprint(path)
        val damaged = payload.copyOf(500000).apply { this[200] = (this[200].toInt() xor 1).toByte() }
        val part = local.newFile().apply { writeBytes(damaged) }
        connect().use { smb ->
            assertThrows(ResumeConflictException::class.java) { smb.downloadResumable(path, part, expected) }
        }
        assertArrayEquals(damaged, part.readBytes())
    }

    @Test fun disconnectedSocketDownloadKeepsDurablePartialForNewConnection() = isolated { control, folder ->
        val path = "$folder/download-disconnected.bin"
        upload(control, path)
        val expected = control.resumeFingerprint(path)
        val part = local.newFile()
        connect().use { smb ->
            assertThrows(CancellationException::class.java) {
                smb.downloadResumable(path, part, expected, onProgress = { done, _ -> if (done >= 512 * 1024) smb.disconnect() })
            }
        }
        assertEquals(512 * 1024L, part.length())
        connect().use { it.downloadResumable(path, part, expected) }
        assertArrayEquals(payload, part.readBytes())
    }

    @Test fun downloadRejectsChangedRemoteIdentityWithoutAppending() = isolated { control, folder ->
        val path = "$folder/remote-conflict.bin"
        upload(control, path)
        val expected = control.resumeFingerprint(path)
        val part = local.newFile().apply { writeBytes(payload.copyOf(500000)) }
        val before = part.readBytes()
        control.upload(path, ByteArrayInputStream("other file".toByteArray()), 10, overwrite = true)
        connect().use { smb ->
            assertThrows(ResumeConflictException::class.java) { smb.downloadResumable(path, part, expected) }
        }
        assertArrayEquals(before, part.readBytes())
    }

    @Test fun downloadCompleteHashRejectsChangeEvenIfMetadataMatches() = isolated { control, folder ->
        val path = "$folder/content-conflict.bin"
        upload(control, path)
        val expected = control.resumeFingerprint(path)
        val changed = payload.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        control.upload(path, ByteArrayInputStream(changed), changed.size.toLong(), overwrite = true)
        val actual = control.resumeFingerprint(path)
        val part = local.newFile().apply { writeBytes(payload.copyOf(500000)) }
        connect().use { smb ->
            // Deliberately make metadata indistinguishable; the original full hash must still win.
            assertThrows(ResumeConflictException::class.java) { smb.downloadResumable(path, part, expected.copy(lastModified = actual.lastModified)) }
        }
        assertArrayEquals(changed, part.readBytes())
    }

    @Test fun oversizedPartialIsNeverTruncatedAndDiscardIsExplicitAndScoped() = isolated { control, folder ->
        val path = "$folder/oversized.bin"
        val id = UUID.randomUUID().toString()
        upload(control, partial(folder, id), payload)
        upload(control, "$folder/unrelated.bin", "keep".toByteArray())
        connect().use { smb ->
            assertThrows(ResumeConflictException::class.java) {
                smb.uploadResumable(path, id, { ByteArrayInputStream(byteArrayOf(1)) }, 1, hash(byteArrayOf(1)))
            }
            assertThrows(IllegalArgumentException::class.java) { smb.discardUploadPartial(path, "../unrelated.bin") }
            smb.discardUploadPartial(path, id)
            smb.discardUploadPartial(path, id) // A repeated explicit discard is harmless.
        }
        assertEquals("keep", bytes(control, "$folder/unrelated.bin").toString(Charsets.UTF_8))
        assertTrue(control.list(folder).none { it.name.endsWith(".part") })
        val overlong = local.newFile().apply { writeText("must retain this longer partial") }
        val before = overlong.readBytes()
        assertThrows(ResumeConflictException::class.java) {
            control.downloadResumable("$folder/unrelated.bin", overlong, control.resumeFingerprint("$folder/unrelated.bin"))
        }
        assertArrayEquals(before, overlong.readBytes())
    }

    @Test fun emptyFilesCompleteAndRecognizeExistingCommit() = isolated { control, folder ->
        val path = "$folder/empty.bin"
        val id = UUID.randomUUID().toString()
        connect().use { it.uploadResumable(path, id, { ByteArrayInputStream(byteArrayOf()) }, 0, hash(byteArrayOf())) }
        val expected = control.resumeFingerprint(path)
        val part = local.newFile()
        connect().use { it.downloadResumable(path, part, expected) }
        assertEquals(0L, part.length())
        assertArrayEquals(byteArrayOf(), bytes(control, path))
    }

    @Test fun contentOnlyAclStillSupportsFingerprintsUploadDownloadResumeCommitAndDiscard() = isolated { control, folder ->
        val remoteSource = "$folder/compat-source.bin"
        upload(control, remoteSource)
        metadataRestriction(control, folder, ".resume-deny-attributes") {
            // The fixture genuinely denies the old metadata-dependent copy path. Ordinary
            // download's existing READ_DATA fallback and new resume paths must keep working.
            assertThrows(SMBApiException::class.java) { control.copy(remoteSource, "$folder/not-created.bin") }
            assertArrayEquals(payload, bytes(control, remoteSource))
            val expected = control.resumeFingerprint(remoteSource)
            assertEquals(hash(payload), expected.sha256)
            val cached = local.newFile().apply { writeBytes(payload.copyOf(500000)) }
            connect().use { it.downloadResumable(remoteSource, cached, expected) }
            assertArrayEquals(payload, cached.readBytes())
            val path = "$folder/compat-upload.bin"
            val id = UUID.randomUUID().toString()
            pauseUpload(path, id)
            assertEquals(512 * 1024, bytes(control, partial(folder, id)).size)
            connect().use { it.uploadResumable(path, id, { ByteArrayInputStream(payload) }, payload.size.toLong(), hash(payload)) }
            assertArrayEquals(payload, bytes(control, path))
            connect().use { it.uploadResumable(path, id, { throw IOException("idempotent committed file needs no source") }, payload.size.toLong(), hash(payload)) }
            val abandoned = UUID.randomUUID().toString()
            pauseUpload("$folder/compat-discard.bin", abandoned)
            control.discardUploadPartial("$folder/compat-discard.bin", abandoned)
            assertTrue(control.list(folder).none { it.name.endsWith(".part") })
        }
    }

    @Test fun deniedOrUnsupportedAllInfoUsesFreshDirectoryMetadataWithoutWeakeningHashes() = isolated { control, folder ->
        listOf(".resume-deny-all-info", ".resume-unsupported-all-info").forEachIndexed { index, marker ->
            val data = payload.copyOf(600000)
            val remoteSource = "$folder/compat-query-$index.bin"
            upload(control, remoteSource, data)
            metadataRestriction(control, folder, marker) {
                assertThrows(SMBApiException::class.java) { control.copy(remoteSource, "$folder/not-created-$index.bin") }
                val expected = control.resumeFingerprint(remoteSource)
                assertEquals(hash(data), expected.sha256)
                val cached = local.newFile().apply { writeBytes(data.copyOf(12345)) }
                connect().use { it.downloadResumable(remoteSource, cached, expected) }
                assertArrayEquals(data, cached.readBytes())
                val target = "$folder/compat-query-upload-$index.bin"
                val id = UUID.randomUUID().toString()
                connect().use { it.uploadResumable(target, id, { ByteArrayInputStream(data) }, data.size.toLong(), hash(data)) }
                assertArrayEquals(data, bytes(control, target))
                val damaged = data.copyOf(12345).apply { this[0] = (this[0].toInt() xor 1).toByte() }
                cached.writeBytes(damaged)
                assertThrows(ResumeConflictException::class.java) { control.downloadResumable(remoteSource, cached, expected) }
                assertArrayEquals(damaged, cached.readBytes())
            }
        }
    }

    private fun metadataRestriction(control: SmbRepository, folder: String, name: String, block: () -> Unit) {
        val marker = "$folder/$name"
        upload(control, marker, byteArrayOf())
        try { block() } finally { control.delete(marker) }
    }
}
