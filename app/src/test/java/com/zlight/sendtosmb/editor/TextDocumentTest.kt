package com.zlight.sendtosmb.editor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextDocumentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun document(text: String, encoding: TextEncoding = TextEncoding.UTF8): Pair<PagedTextDocument, File> {
        val input = temporary.newFile()
        input.writeBytes(encoding.bom + text.toByteArray(encoding.charset))
        return PagedTextDocument.create(temporary.newFolder(), input) to input
    }

    @Test fun utf8BomAndCrLfSurviveOneCharacterEdit() {
        val (doc, source) = document("第一行\r\nsecond\r\n", TextEncoding.UTF8_BOM)
        assertEquals(TextEncoding.UTF8_BOM, doc.encoding)
        val page = doc.readPage(0)
        doc.change(0, page.replace(0, 1, "第", doc.defaultEnding))
        val output = doc.export()
        assertArrayEquals(source.readBytes(), output.readBytes())
        doc.change(0, page.replace(0, 1, "新", doc.defaultEnding))
        assertArrayEquals(TextEncoding.UTF8_BOM.bom + "新一行\r\nsecond\r\n".toByteArray(), doc.export().readBytes())
    }

    @Test fun utf8WithoutBomDoesNotGainOne() {
        val (doc, _) = document("hello\nworld")
        doc.change(0, doc.readPage(0).replace(1, 2, "A", doc.defaultEnding))
        assertArrayEquals("hAllo\nworld".toByteArray(), doc.export().readBytes())
    }

    @Test fun mixedAndLoneCrNewlinesArePreservedExactly() {
        val (doc, source) = document("a\r\nb\nc\rd\r\n")
        assertEquals("混合换行（保留）", doc.endingLabel())
        assertArrayEquals(source.readBytes(), doc.export().readBytes())
        doc.change(0, doc.readPage(0).replace(2, 3, "B", doc.defaultEnding))
        assertArrayEquals("a\r\nB\nc\rd\r\n".toByteArray(), doc.export().readBytes())
    }

    @Test fun newlinesInsertedOnAndroidFollowOriginalStyle() {
        val (doc, _) = document("a\r\nb")
        doc.change(0, doc.readPage(0).replace(1, 1, "\nx", doc.defaultEnding))
        assertArrayEquals("a\r\nx\r\nb".toByteArray(), doc.export().readBytes())
    }

    @Test fun gbkIsDetectedAndRetainedOnSave() {
        val (doc, _) = document("中文简体\r\n", TextEncoding.GBK)
        assertEquals(TextEncoding.GBK, doc.encoding)
        doc.change(0, doc.readPage(0).replace(0, 1, "汉", doc.defaultEnding))
        assertArrayEquals("汉文简体\r\n".toByteArray(TextEncoding.GBK.charset), doc.export().readBytes())
    }

    @Test fun unrepresentableCharactersNeverBecomeQuestionMarks() {
        val (doc, source) = document("中文", TextEncoding.GBK)
        val before = source.readBytes()
        doc.change(0, doc.readPage(0).replace(2, 2, "😀", doc.defaultEnding))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { doc.export() }
        assertArrayEquals(before, source.readBytes())
        assertTrue(PagedTextDocument.restore(doc.directory).dirty)
        assertEquals("中文😀", PagedTextDocument.restore(doc.directory).readPage(0).text)
    }

    @Test fun malformedTrailingSurrogateIsRejectedAtEncoderClose() {
        val (doc, _) = document("a")
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { doc.change(0, LineText.parse("a\uD800")) }
        assertEquals("a", doc.export().readText())
    }

    @Test fun utf16BomFilesRoundTrip() {
        for (encoding in listOf(TextEncoding.UTF16_LE, TextEncoding.UTF16_BE)) {
            val (doc, input) = document("Hello 中文 😀\r\n", encoding)
            assertEquals(encoding, doc.encoding)
            assertArrayEquals(input.readBytes(), doc.export().readBytes())
        }
    }

    @Test fun emptyFilesAndMissingFinalNewlineRoundTrip() {
        for (text in listOf("", "x", "x\n", "\n\n")) {
            val (doc, input) = document(text)
            assertArrayEquals(input.readBytes(), doc.export().readBytes())
            assertEquals(text.count { it == '\n' }.toLong(), doc.counts().breaks)
        }
    }

    @Test fun pagesNeverSplitCrLfOrUnicodeSurrogatePairs() {
        val text = "x".repeat(PagedTextDocument.PAGE_CHARS - 1) + "😀\r\n中" + "y".repeat(PagedTextDocument.PAGE_CHARS)
        val (doc, input) = document(text)
        assertTrue(doc.pageCount > 1)
        for (index in 0 until doc.pageCount) {
            val raw = doc.readPage(index).raw()
            assertFalse(raw.endsWith("\r"))
            assertFalse(raw.lastOrNull()?.isHighSurrogate() == true)
        }
        assertArrayEquals(input.readBytes(), doc.export().readBytes())
    }

    @Test fun largePagedEditsAndRecoveryRetainUntouchedBytes() {
        val text = ("中文 abc 😀\r\n".repeat(40000))
        val (doc, _) = document(text, TextEncoding.UTF8_BOM)
        assertTrue(doc.pageCount > 5)
        val last = doc.pageCount - 1
        doc.change(last, doc.readPage(last).replace(0, 1, "汉", doc.defaultEnding))
        doc.checkpoint()
        val recovered = PagedTextDocument.restore(doc.directory)
        assertTrue(recovered.dirty)
        assertEquals(doc.counts(), recovered.counts())
        assertArrayEquals(doc.export().readBytes(), recovered.export().readBytes())
    }

    @Test fun searchAndReplacementCanCrossPageBoundaries() {
        val text = "a".repeat(PagedTextDocument.PAGE_CHARS - 2) + "HELLO" + "z".repeat(70000)
        val (doc, _) = document(text)
        val match = doc.find("hello", 0, 0, true)!!
        assertEquals(PagedTextDocument.PAGE_CHARS - 2, match.start)
        doc.replaceMatch(match, "你好")
        assertEquals(text.replace("HELLO", "你好"), doc.export().readText())
        assertNull(doc.find("hello", 0, 0, true))
    }

    @Test fun diskRestorePointsUndoAndRedoBulkReplacements() {
        val (doc, _) = document("one\r\ntwo\none")
        val before = doc.backup()
        doc.change(0, doc.readPage(0).replace(0, 3, "1", doc.defaultEnding))
        val after = doc.backup()
        doc.restoreBackup(before)
        assertEquals("one\r\ntwo\none", doc.export().readText())
        doc.restoreBackup(after)
        assertEquals("1\r\ntwo\none", doc.export().readText())
    }

    @Test fun lineJumpAndUnicodeCountsAreCorrect() {
        val (doc, _) = document("Hello world\r\n中文😀\n")
        assertEquals(4L, doc.counts().words)
        assertEquals(16L, doc.counts().characters)
        assertEquals(0 to 12, doc.linePosition(2))
        assertEquals(0 to 17, doc.linePosition(3))
        assertThrows(IllegalStateException::class.java) { doc.linePosition(4) }
    }

    @Test fun failedAtomicWriteLeavesPreviousRecoveryIntact() {
        val target = temporary.newFile()
        target.writeText("previous")
        assertThrows(IllegalStateException::class.java) {
            AtomicTextFiles.write(target) { it.write("partial".toByteArray()); error("simulated disk failure") }
        }
        assertEquals("previous", target.readText())
    }

    @Test fun lineJumpAtPageBoundaryShowsTheFollowingLine() {
        val (doc, _) = document("a".repeat(PagedTextDocument.PAGE_CHARS - 1) + "\nsecond line")
        assertEquals(1 to 0, doc.linePosition(2))
        assertEquals("second line", doc.readPage(1).text)
    }

    @Test fun replacingIdenticalNormalizedTextRetainsMixedLineEndings() {
        val original = LineText.parse("a\r\nb\nc\r")
        assertEquals(original, original.replace(0, original.text.length, original.text, LineEnding.CRLF))
    }

    @Test fun checkpointsPruneObsoletePageBlobs() {
        val (doc, _) = document("a\n")
        repeat(12) { doc.change(0, LineText.parse("$it\r\n")); doc.checkpoint() }
        assertEquals(1, doc.directory.listFiles()!!.count { it.name.startsWith("page-") })
        assertEquals("11\r\n", PagedTextDocument.restore(doc.directory).export().readText())
    }
}
