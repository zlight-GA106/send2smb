package com.zlight.sendtosmb.editor

import java.io.*
import java.util.Properties
import java.util.UUID

data class TextPageInfo(val file: String, val counts: TextCounts, val endings: Set<LineEnding> = emptySet())
data class DocumentSnapshot(val revision: Long, val encoding: TextEncoding, val pages: List<TextPageInfo>)
data class TextMatch(val page: Int, val start: Int, val end: Int)

/** Disk-backed pages keep both reading and editing bounded, including single-line files. */
class PagedTextDocument private constructor(val directory: File, initial: List<TextPageInfo>,
    initialEncoding: TextEncoding, initialEnding: LineEnding, initialRevision: Long, initialSaved: Long) {
    private val writeGate = Any()
    private var pages = initial.toMutableList()
    private val pending = mutableMapOf<Int, LineText>()
    @Volatile var encoding = initialEncoding; private set
    val defaultEnding = initialEnding
    @Volatile var revision = initialRevision; private set
    private var savedRevision = initialSaved
    val dirty get() = synchronized(this) { revision != savedRevision }
    val pageCount get() = synchronized(this) { pages.size }

    @Synchronized fun readPage(index: Int): LineText = pending[index] ?: LineText.parse(File(directory, pages[index].file).readText(Charsets.UTF_8))
    @Synchronized fun change(index: Int, text: LineText) {
        require(text.text.length <= MAX_EDIT_PAGE) { "单页最多 256K 字符，请分次插入或新建文档" }
        Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text.text))
        pending[index] = text
        pages[index] = pages[index].copy(counts = TextCounts.of(text.text), endings = text.endings.toSet())
        revision++
    }
    @Synchronized fun setEncoding(value: TextEncoding) { if (encoding != value) { encoding = value; revision++ } }
    @Synchronized fun counts(): TextCounts {
        var words = 0L; var chars = 0L; var breaks = 0L; var previousWord = false
        for (page in pages) {
            words += page.counts.words - if (previousWord && page.counts.startsWord) 1 else 0
            chars += page.counts.characters; breaks += page.counts.breaks
            if (page.counts.characters > 0) previousWord = page.counts.endsWord
        }
        return TextCounts(words, chars, breaks, pages.first().counts.startsWord, previousWord)
    }
    @Synchronized fun breaksBefore(page: Int): Long = pages.take(page).sumOf { it.counts.breaks }
    @Synchronized fun endingLabel(): String {
        val styles = pages.flatMap { it.endings }.distinct()
        return if (styles.size > 1) "混合换行（保留）" else defaultEnding.name
    }

    /** Page blobs are immutable. Commit the manifest only after every changed page is fsynced. */
    fun checkpoint(): DocumentSnapshot = synchronized(writeGate) {
        val changes: Map<Int, LineText>
        val snapshot: DocumentSnapshot
        val saved: Long
        synchronized(this) {
            changes = pending.toMap()
            snapshot = DocumentSnapshot(revision, encoding, pages.toList())
            saved = savedRevision
        }
        val committed = snapshot.pages.toMutableList()
        changes.forEach { (index, text) ->
            val name = "page-${UUID.randomUUID()}.txt"
            AtomicTextFiles.write(File(directory, name)) { it.write(text.raw().toByteArray(Charsets.UTF_8)) }
            committed[index] = committed[index].copy(file = name)
        }
        val result = snapshot.copy(pages = committed)
        writeManifest(result, saved)
        synchronized(this) {
            changes.forEach { (index, text) ->
                pages[index] = pages[index].copy(file = committed[index].file)
                if (pending[index] === text) pending.remove(index)
            }
        }
        val retained = committed.map { it.file }.toSet()
        directory.listFiles()?.filter { it.name.startsWith("page-") && it.name.endsWith(".txt") && it.name !in retained }?.forEach { it.delete() }
        result
    }

    fun prepareSave(): Pair<DocumentSnapshot, File> = synchronized(writeGate) {
        val snapshot = checkpoint()
        snapshot to export(snapshot)
    }

    fun export(snapshot: DocumentSnapshot = checkpoint()): File = synchronized(writeGate) {
        val result = File(directory, "export-${UUID.randomUUID()}.txt")
        AtomicTextFiles.write(result) { output ->
            snapshot.encoding.writer(output).use { writer ->
                for (page in snapshot.pages) File(directory, page.file).reader(Charsets.UTF_8).use { it.copyTo(writer) }
            }
        }
        result
    }
    fun markSaved(atRevision: Long) { synchronized(this) { savedRevision = atRevision }; checkpoint() }

    /** Searching streams bounded page strings, carrying enough overlap for cross-page matches. */
    fun find(query: String, startPage: Int, startOffset: Int, ignoreCase: Boolean = false, wrap: Boolean = true): TextMatch? {
        require(query.length <= 4096) { "查找内容最多 4096 个字符" }
        if (query.isEmpty()) return null
        for (pass in 0..if (wrap) 1 else 0) {
            val first = if (pass == 0) startPage else 0
            val last = if (pass == 0) pageCount - 1 else startPage
            for (index in first..last) {
                val text = readPage(index).text
                // Cross-page matches are reported on the first page; replacement joins the parts.
                val following = StringBuilder()
                var next = index + 1
                while (following.length < query.length - 1 && next < pageCount) following.append(readPage(next++).text.take(query.length - 1 - following.length))
                val from = if (pass == 0 && index == startPage) startOffset.coerceAtMost(text.length) else 0
                val found = (text + following).indexOf(query, from, ignoreCase)
                if (found >= 0 && found < text.length && !(pass == 1 && index == startPage && found >= startOffset)) return TextMatch(index, found, found + query.length)
            }
        }
        return null
    }

    fun replaceMatch(match: TextMatch, replacement: String) {
        val first = readPage(match.page)
        change(match.page, first.replace(match.start, minOf(match.end, first.text.length), replacement, defaultEnding))
        var remaining = (match.end - first.text.length).coerceAtLeast(0)
        var next = match.page + 1
        while (remaining > 0) {
            val part = readPage(next)
            val removed = minOf(remaining, part.text.length)
            change(next++, part.replace(0, removed, "", defaultEnding))
            remaining -= removed
        }
    }

    /** Disk restore points allow whole-document replacement undo without keeping a large copy in RAM. */
    fun backup(): File = synchronized(writeGate) {
        val snapshot = checkpoint()
        val target = File(directory, "undo-${UUID.randomUUID()}").apply { mkdirs() }
        for (page in snapshot.pages) File(directory, page.file).copyTo(File(target, page.file))
        File(directory, "document.properties").copyTo(File(target, "document.properties"))
        target
    }
    fun restoreBackup(target: File) = synchronized(writeGate) {
        val restored = restore(target)
        val copied = restored.pages.map { info ->
            val name = "page-${UUID.randomUUID()}.txt"
            AtomicTextFiles.write(File(directory, name)) { output -> File(target, info.file).inputStream().use { it.copyTo(output) } }
            info.copy(file = name)
        }
        synchronized(this) { pages = copied.toMutableList(); pending.clear(); encoding = restored.encoding; revision++ }
        checkpoint()
        Unit
    }

    fun linePosition(line: Long): Pair<Int, Int> {
        require(line >= 1) { "行号从 1 开始" }
        var remaining = line - 1
        for (index in 0 until pageCount) {
            val text = readPage(index).text
            val count = text.count { it == '\n' }.toLong()
            if (remaining <= count) {
                var position = 0
                repeat(remaining.toInt()) { position = text.indexOf('\n', position) + 1 }
                if (position == text.length && index + 1 < pageCount) {
                    var next = index + 1
                    while (next + 1 < pageCount && readPage(next).text.isEmpty()) next++
                    return next to 0
                }
                return index to position
            }
            remaining -= count
        }
        error("行号超出文档范围")
    }

    private fun writeManifest(snapshot: DocumentSnapshot, saved: Long) {
        val props = Properties().apply {
            setProperty("format", "1"); setProperty("encoding", snapshot.encoding.name)
            setProperty("ending", defaultEnding.name); setProperty("revision", snapshot.revision.toString())
            setProperty("saved", saved.toString()); setProperty("pages", snapshot.pages.size.toString())
            snapshot.pages.forEachIndexed { index, page ->
                setProperty("page.$index", page.file)
                setProperty("counts.$index", with(page.counts) { "$words,$characters,$breaks,$startsWord,$endsWord" })
                setProperty("endings.$index", page.endings.joinToString(",") { it.name })
            }
        }
        AtomicTextFiles.write(File(directory, "document.properties")) { props.store(it, "TXT recovery manifest") }
    }

    companion object {
        const val PAGE_CHARS = 65536
        const val MAX_EDIT_PAGE = 262144
        const val MAX_FILE_BYTES = 256L * 1024 * 1024

        fun create(directory: File, source: File, overrideEncoding: TextEncoding? = null): PagedTextDocument {
            require(source.length() <= MAX_FILE_BYTES) { "文档超过 256 MB，请拆分后编辑" }
            directory.mkdirs()
            val encoding = overrideEncoding ?: TextEncoding.detect(source)
            val pages = ArrayList<TextPageInfo>()
            val endingCounts = LongArray(LineEnding.entries.size)
            fun add(raw: String) {
                val normalized = LineText.parse(raw)
                normalized.endings.forEach { endingCounts[it.ordinal]++ }
                val name = "page-${UUID.randomUUID()}.txt"
                AtomicTextFiles.write(File(directory, name)) { it.write(raw.toByteArray(Charsets.UTF_8)) }
                pages.add(TextPageInfo(name, TextCounts.of(normalized.text), normalized.endings.toSet()))
            }
            encoding.reader(source).use { reader ->
                val buffer = CharArray(8192)
                val carry = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    carry.append(buffer, 0, count)
                    while (carry.length > PAGE_CHARS) {
                        var cut = carry.lastIndexOf("\n", PAGE_CHARS - 1) + 1
                        if (cut < PAGE_CHARS / 2) cut = PAGE_CHARS
                        if (carry[cut - 1] == '\r' || Character.isHighSurrogate(carry[cut - 1])) cut--
                        add(carry.substring(0, cut)); carry.delete(0, cut)
                    }
                }
                if (carry.isNotEmpty() || pages.isEmpty()) add(carry.toString())
            }
            val most = endingCounts.indices.maxByOrNull { endingCounts[it] } ?: LineEnding.LF.ordinal
            val ending = if (endingCounts.sum() == 0L) LineEnding.LF else LineEnding.entries[most]
            val document = PagedTextDocument(directory, pages, encoding, ending, 0, 0)
            // Some legacy byte sequences have multiple spellings: reject any non-round-trip decode.
            val roundTrip = document.export(DocumentSnapshot(0, encoding, pages))
            try { check(AtomicTextFiles.hash(source) == AtomicTextFiles.hash(roundTrip)) { "该编码不能无损往返，请选择正确编码重新打开" } }
            finally { roundTrip.delete() }
            document.checkpoint()
            return document
        }

        fun restore(directory: File): PagedTextDocument {
            val props = Properties().apply { File(directory, "document.properties").inputStream().use { load(it) } }
            check(props.getProperty("format") == "1") { "不支持此草稿格式" }
            val pages = (0 until props.getProperty("pages").toInt()).map { index ->
                val name = props.getProperty("page.$index")
                require(name.matches(Regex("page-[0-9a-f-]+\\.txt")))
                check(File(directory, name).isFile) { "草稿页面缺失" }
                val values = props.getProperty("counts.$index").split(',')
                val endings = props.getProperty("endings.$index", "").split(',').filter { it.isNotBlank() }.map { LineEnding.valueOf(it) }.toSet()
                TextPageInfo(name, TextCounts(values[0].toLong(), values[1].toLong(), values[2].toLong(), values[3].toBoolean(), values[4].toBoolean()), endings)
            }
            return PagedTextDocument(directory, pages, TextEncoding.valueOf(props.getProperty("encoding")),
                LineEnding.valueOf(props.getProperty("ending")), props.getProperty("revision").toLong(), props.getProperty("saved").toLong())
        }
    }
}
