package com.zlight.sendtosmb.editor

import com.zlight.sendtosmb.data.SmbAddress
import com.zlight.sendtosmb.data.SmbRepository
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Opt-in host integration: permanently hard-bound to the disposable loopback server. */
class TextSmbFilesTest {
    @get:Rule val files = TemporaryFolder()
    private fun isolated(block: (SmbRepository, String) -> Unit) {
        assumeTrue(System.getenv("SENDTOSMB_EDITOR_SMB_TEST") == "1")
        SmbRepository().use { smb ->
            smb.connect(SmbAddress.parse("smb://127.0.0.1:1445/TESTSHARE"), "android-test", "SendToSMB-test-only!", "")
            val folder = "txt-editor-test-${UUID.randomUUID()}"
            smb.mkdir(folder)
            try { block(smb, folder) } finally { smb.delete(folder) }
        }
    }
    private fun bytes(smb: SmbRepository, path: String): ByteArray = ByteArrayOutputStream().also { smb.download(path, it) }.toByteArray()

    @Test fun cacheEditAndAtomicReplaceRetainBomAndCrLf() = isolated { smb, folder ->
        val path = "$folder/中文.txt"
        val original = TextEncoding.UTF8_BOM.bom + "第一行\r\nsecond\r\n".toByteArray()
        smb.upload(path, ByteArrayInputStream(original), original.size.toLong())
        val cache = files.newFile()
        val hash = TextSmbFiles.cache(smb, path, cache)
        assertArrayEquals(original, cache.readBytes())
        val doc = PagedTextDocument.create(files.newFolder(), cache)
        doc.change(0, doc.readPage(0).replace(0, 1, "新", doc.defaultEnding))
        val export = doc.export()
        TextSmbFiles.save(smb, TextFileSource("中文.txt", "fixture", path, hash), export)
        assertArrayEquals(TextEncoding.UTF8_BOM.bom + "新一行\r\nsecond\r\n".toByteArray(), bytes(smb, path))
        assertTrue(smb.list(folder).none { it.name.endsWith(".part") })
    }

    @Test fun changedRemoteFileIsNeverOverwritten() = isolated { smb, folder ->
        val path = "$folder/conflict.txt"
        smb.upload(path, ByteArrayInputStream("original".toByteArray()), 8)
        val cache = files.newFile()
        val hash = TextSmbFiles.cache(smb, path, cache)
        smb.upload(path, ByteArrayInputStream("other edit".toByteArray()), 10, overwrite = true)
        val draft = files.newFile().apply { writeText("my edit") }
        assertThrows(IllegalStateException::class.java) { TextSmbFiles.save(smb, TextFileSource("conflict.txt", "fixture", path, hash), draft) }
        assertEquals("other edit", bytes(smb, path).toString(Charsets.UTF_8))
        assertEquals("my edit", draft.readText())
    }

    @Test fun noOpSaveDoesNotRewriteRemoteTimestamp() = isolated { smb, folder ->
        val path = "$folder/noop.txt"
        smb.upload(path, ByteArrayInputStream("unchanged\r\n".toByteArray()), 11)
        val cached = files.newFile()
        val hash = TextSmbFiles.cache(smb, path, cached)
        val modified = smb.list(folder).single().lastModified
        Thread.sleep(1100)
        TextSmbFiles.save(smb, TextFileSource("noop.txt", "fixture", path, hash), cached)
        assertEquals(modified, smb.list(folder).single().lastModified)
        assertEquals("unchanged\r\n", bytes(smb, path).toString(Charsets.UTF_8))
    }

    @Test fun saveAsRefusesExistingFilesAndCanCreateNewTxt() = isolated { smb, folder ->
        val draft = files.newFile().apply { writeText("a\r\nb") }
        val source = TextFileSource("new.txt", "fixture", "$folder/new.txt")
        TextSmbFiles.save(smb, source, draft)
        assertArrayEquals(draft.readBytes(), bytes(smb, source.path))
        draft.writeText("replacement")
        assertThrows(IllegalArgumentException::class.java) { TextSmbFiles.save(smb, source, draft) }
        assertEquals("a\r\nb", bytes(smb, source.path).toString(Charsets.UTF_8))
    }

    @Test fun preCommitFailureOrCancellationKeepsOriginalFile() = isolated { smb, folder ->
        val path = "$folder/safe.txt"
        smb.upload(path, ByteArrayInputStream("safe".toByteArray()), 4)
        assertThrows(IllegalStateException::class.java) {
            smb.upload(path, ByteArrayInputStream("new content".toByteArray()), 11, overwrite = true, beforeCommit = { error("connection/conflict before rename") })
        }
        assertEquals("safe", bytes(smb, path).toString(Charsets.UTF_8))
        assertTrue(smb.list(folder).none { it.name.endsWith(".part") })
    }
}
