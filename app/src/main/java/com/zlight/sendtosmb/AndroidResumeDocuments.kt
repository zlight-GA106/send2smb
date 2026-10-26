package com.zlight.sendtosmb

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException

internal class AndroidResumeDocuments(context: Context) : ResumeDocuments {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private fun document(uri: String): Uri {
        val parsed = Uri.parse(uri)
        return if (DocumentsContract.isTreeUri(parsed) && runCatching { DocumentsContract.getDocumentId(parsed) }.isFailure)
            DocumentsContract.buildDocumentUriUsingTree(parsed, DocumentsContract.getTreeDocumentId(parsed)) else parsed
    }
    override fun stat(uri: String): ResumeDocument? = try {
        val parsed = document(uri)
        resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                ResumeDocument(parsed.toString(), if (name >= 0) cursor.getString(name) ?: "文件" else "文件",
                    resolver.getType(parsed) == DocumentsContract.Document.MIME_TYPE_DIR, if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else -1)
            } else null
        }
    } catch (_: java.io.FileNotFoundException) {
        null
    }
    override fun children(uri: String): List<ResumeDocument> {
        val parent = document(uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val result = arrayListOf<ResumeDocument>()
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, OpenableColumns.SIZE)
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) result.add(ResumeDocument(
                DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)).toString(),
                cursor.getString(1) ?: "文件", cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                if (cursor.isNull(3)) -1 else cursor.getLong(3)))
        } ?: error("无法读取保存目录，请重新授权后继续")
        return result
    }
    override fun persistentRead(uri: String) = resolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }
    override fun open(uri: String): InputStream = resolver.openInputStream(Uri.parse(uri)) ?: error("所选文件无法读取，请重新授权后继续")
    override fun create(parent: String, name: String, directory: Boolean): String {
        return DocumentsContract.createDocument(resolver, document(parent),
            if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name)?.toString()
            ?: error("无法创建保存目标，请检查目录权限")
    }
    override fun write(uri: String, source: File, cancelled: () -> Boolean) {
        resolver.openFileDescriptor(Uri.parse(uri), "wt")?.use { descriptor ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) throw CancellationException("传输已暂停")
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
                if (descriptor.statSize >= 0) output.fd.sync()
            }
        } ?: error("保存位置无法写入，下载缓存已保留")
    }
    override fun rename(uri: String, name: String): String {
        val renamed = DocumentsContract.renameDocument(resolver, document(uri), name)?.toString()
            ?: error("保存位置不支持重命名，已保留经过校验的临时文件")
        check(stat(renamed)?.name == name) { "保存位置未保留目标文件名，文件已保留，请检查目标目录" }
        return renamed
    }
    override fun delete(uri: String) { check(DocumentsContract.deleteDocument(resolver, document(uri))) { "临时文件未能清理，请检查保存位置权限" } }
}
