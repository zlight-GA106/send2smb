package com.zlight.sendtosmb.editor

import java.io.*
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

enum class TextEncoding(val label: String, val charset: Charset, val bom: ByteArray) {
    UTF8("UTF-8", Charsets.UTF_8, byteArrayOf()),
    UTF8_BOM("UTF-8 BOM", Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    GBK("GBK", Charset.forName("GBK"), byteArrayOf()),
    UTF16_LE("UTF-16 LE BOM", Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    UTF16_BE("UTF-16 BE BOM", Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()));

    fun reader(file: File, skipBom: Boolean = true): Reader {
        val input = BufferedInputStream(FileInputStream(file))
        if (skipBom && bom.isNotEmpty()) {
            val prefix = ByteArray(bom.size)
            val count = input.read(prefix)
            if (count != bom.size || !prefix.contentEquals(bom)) { input.close(); error("文件 BOM 与所选编码不一致") }
        }
        return InputStreamReader(input, charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    }

    fun writer(output: OutputStream): Writer {
        output.write(bom)
        val borrowed = object : FilterOutputStream(output) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            override fun close() { flush() }
        }
        return OutputStreamWriter(borrowed, charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    }

    companion object {
        fun detect(file: File): TextEncoding {
            val prefix = file.inputStream().use { input -> ByteArray(3).also { input.read(it) } }
            entries.filter { it.bom.isNotEmpty() }.firstOrNull { prefix.take(it.bom.size).toByteArray().contentEquals(it.bom) }?.let { return it }
            if (valid(file, UTF8)) return UTF8
            if (valid(file, GBK)) return GBK
            error("无法无损识别编码，请选择编码重新打开；不会用替换字符覆盖原文")
        }
        private fun valid(file: File, encoding: TextEncoding): Boolean = runCatching {
            encoding.reader(file).use { reader -> val buffer = CharArray(8192); while (reader.read(buffer) >= 0) Unit }
        }.isSuccess
    }
}

enum class LineEnding(val value: String) { CRLF("\r\n"), LF("\n"), CR("\r") }

/** UI uses LF. Each existing newline carries its original spelling through edits and undo. */
data class LineText(val text: String, val endings: List<LineEnding>) {
    fun raw(): String {
        val output = StringBuilder(text.length + endings.count { it == LineEnding.CRLF })
        var index = 0
        for (char in text) if (char == '\n') output.append(endings[index++].value) else output.append(char)
        check(index == endings.size)
        return output.toString()
    }
    fun replace(start: Int, end: Int, insertion: String, default: LineEnding): LineText {
        require(start in 0..text.length && end in start..text.length)
        val first = text.take(start).count { it == '\n' }
        val removed = text.substring(start, end).count { it == '\n' }
        val inserted = parse(insertion)
        if (inserted.text == text.substring(start, end)) return this
        // Clipboard CRLF is explicit; Android's plain LF follows this document's existing style.
        val newEndings = inserted.endings.map { if (it == LineEnding.LF) default else it }
        return LineText(text.replaceRange(start, end, inserted.text), endings.take(first) + newEndings + endings.drop(first + removed))
    }
    companion object {
        fun parse(raw: String): LineText {
            val output = StringBuilder(raw.length)
            val endings = ArrayList<LineEnding>()
            var i = 0
            while (i < raw.length) {
                when (val char = raw[i++]) {
                    '\r' -> { if (i < raw.length && raw[i] == '\n') { i++; endings.add(LineEnding.CRLF) } else endings.add(LineEnding.CR); output.append('\n') }
                    '\n' -> { endings.add(LineEnding.LF); output.append('\n') }
                    else -> output.append(char)
                }
            }
            return LineText(output.toString(), endings)
        }
    }
}

data class TextCounts(val words: Long, val characters: Long, val breaks: Long, val startsWord: Boolean, val endsWord: Boolean) {
    companion object {
        fun of(text: String): TextCounts {
            var words = 0L; var characters = 0L; var breaks = 0L; var inWord = false; var first = false
            var index = 0
            while (index < text.length) {
                val cp = text.codePointAt(index)
                val cjk = when (Character.UnicodeScript.of(cp)) {
                    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
                    else -> false
                }
                val word = !cjk && (Character.isLetterOrDigit(cp) || cp == '_'.code)
                if (cjk || (word && !inWord)) words++
                if (index == 0) first = word
                inWord = word
                if (cp == '\n'.code) breaks++
                characters++
                index += Character.charCount(cp)
            }
            return TextCounts(words, characters, breaks, first, inWord)
        }
    }
}

object AtomicTextFiles {
    fun write(file: File, block: (FileOutputStream) -> Unit) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}-${java.util.UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output -> block(output); output.flush(); output.fd.sync() }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            // Persist the directory entry too where the filesystem supports directory fsync.
            runCatching { java.nio.channels.FileChannel.open(file.parentFile!!.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
        } finally { temporary.delete() }
    }
    fun hash(file: File): String = file.inputStream().use { hash(it) }
    fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
