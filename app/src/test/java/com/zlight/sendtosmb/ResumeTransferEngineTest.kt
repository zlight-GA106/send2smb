package com.zlight.sendtosmb

import com.zlight.sendtosmb.data.ResumeConflictException
import com.zlight.sendtosmb.data.SmbAddress
import com.zlight.sendtosmb.data.SmbRepository
import com.zlight.sendtosmb.editor.AtomicTextFiles
import com.zlight.sendtosmb.ui.UiFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Real loopback SMB + a filesystem SAF adapter; every retry reconstructs state from the codec. */
class ResumeTransferEngineTest {
    @get:Rule val local = TemporaryFolder()
    private val payload = ByteArray(2 * 1024 * 1024 + 73) { (it * 37 + it / 383).toByte() }
    private val server = "smb://127.0.0.1:1445/TESTSHARE"
    private fun connect() = SmbRepository().also { it.connect(SmbAddress.parse(server), "android-test", "SendToSMB-test-only!") }
    private fun isolated(block: (SmbRepository, String) -> Unit) {
        assumeTrue(System.getenv("SENDTOSMB_RESUME_SMB_TEST") == "1")
        connect().use { control ->
            val folder = "resume-engine-${UUID.randomUUID()}"
            control.mkdir(folder)
            try { block(control, folder) } finally { control.delete(folder) }
        }
    }
    private fun upload(smb: SmbRepository, path: String, bytes: ByteArray = payload) =
        smb.upload(path, ByteArrayInputStream(bytes), bytes.size.toLong())
    private fun remoteBytes(smb: SmbRepository, path: String) = java.io.ByteArrayOutputStream().also { smb.download(path, it) }.toByteArray()
    private fun source(smb: SmbRepository, folder: String, name: String): UiFile = smb.list(folder).single { it.name == name }.let {
        UiFile(it.name, it.path, it.isDirectory, it.size, it.lastModified)
    }
    private fun record(path: String, source: String? = null, tree: String? = null) =
        TransferResume(server, "android-test", "", path, "", sourceUri = source, treeUri = tree)
    private fun engine(id: String, workspace: File, docs: FakeResumeDocuments, store: EnginePlan) =
        ResumeTransferEngine(id, workspace, docs, store::read, store::save)

    @Test fun nonpersistentUploadCachesBeforeFirstByteAndResumesWithoutSourcePermission() = isolated { control, folder ->
        val docs = FakeResumeDocuments(local.newFolder("provider"))
        val uri = docs.addSource("phone-video.mp4", payload, persistent = false)
        val id = UUID.randomUUID().toString()
        val workspace = local.newFolder("workspace")
        val store = EnginePlan(File(workspace, "plan.properties"), record(folder, source = uri))
        val stop = AtomicBoolean()
        connect().use { smb ->
            assertThrows(CancellationException::class.java) {
                engine(id, workspace, docs, store).run("upload", null, smb, stop::get) { bytes, _ ->
                    if (bytes >= 512 * 1024) {
                        assertArrayEquals(payload, File(workspace, "$id.upload").readBytes())
                        stop.set(true)
                    }
                }
            }
        }
        assertEquals(1, docs.sourceOpens[uri])
        assertEquals("$id.upload", store.read().uploadCacheName)
        assertNotNull(store.read().sha256)
        docs.denied.add(uri)
        docs.file(uri).delete()
        val reconstructed = EnginePlan(store.file)
        connect().use { smb -> engine(id, workspace, docs, reconstructed).run("upload", null, smb, { false }) { _, _ -> } }
        assertArrayEquals(payload, remoteBytes(control, "$folder/phone-video.mp4"))
        assertEquals(1, docs.sourceOpens[uri])
        assertTrue(control.list(folder).none { it.name.endsWith(".part") })
        engine(id, workspace, docs, reconstructed).cleanupLocal()
        assertFalse(File(workspace, "$id.upload").exists())
    }

    @Test fun downloadReconstructsFromStoredCodecAndCommitsExactlyOneFinalDocument() = isolated { control, folder ->
        upload(control, "$folder/notes.txt")
        val source = source(control, folder, "notes.txt")
        val docs = FakeResumeDocuments(local.newFolder("provider"))
        val id = UUID.randomUUID().toString()
        val workspace = local.newFolder("workspace")
        val store = EnginePlan(File(workspace, "plan.properties"), record(source.path, tree = docs.tree))
        val stop = AtomicBoolean()
        connect().use { smb ->
            assertThrows(CancellationException::class.java) {
                engine(id, workspace, docs, store).run("download", source, smb, stop::get) { bytes, _ -> if (bytes >= 512 * 1024) stop.set(true) }
            }
        }
        val entry = store.read().entries.single()
        assertEquals(512 * 1024L, File(workspace, "${entry.id}.part").length())
        assertTrue(docs.children(docs.tree).isEmpty())
        var firstPositive = -1L
        val reconstructed = EnginePlan(store.file)
        connect().use { smb -> engine(id, workspace, docs, reconstructed).run("download", source, smb, { false }) { bytes, _ ->
            if (bytes > 0 && firstPositive < 0) firstPositive = bytes
        } }
        assertEquals(512 * 1024L, firstPositive)
        assertTrue(reconstructed.read().entries.single().completed)
        val final = docs.children(docs.tree).single()
        assertEquals("notes.txt", final.name)
        assertArrayEquals(payload, docs.file(final.uri).readBytes())
        assertEquals(1, docs.fileCreates)
        assertFalse(File(workspace, "${entry.id}.part").exists())
        connect().use { smb -> engine(id, workspace, docs, EnginePlan(store.file)).run("download", source, smb, { false }) { _, _ -> } }
        assertEquals(1, docs.fileCreates)
        assertEquals(1, docs.writes)
        assertEquals(1, docs.children(docs.tree).size)
    }

    @Test fun providerWriteFailureKeepsFullDownloadAndRetryOnlyValidatesExistingLocalPrefix() = isolated { control, folder ->
        upload(control, "$folder/backup.bin")
        val source = source(control, folder, "backup.bin")
        val docs = FakeResumeDocuments(local.newFolder("provider"))
        val id = UUID.randomUUID().toString()
        val workspace = local.newFolder("workspace")
        val store = EnginePlan(File(workspace, "plan.properties"), record(source.path, tree = docs.tree))
        docs.failNextWrite = true
        connect().use { smb -> assertThrows(IOException::class.java) {
            engine(id, workspace, docs, store).run("download", source, smb, { false }) { _, _ -> }
        } }
        val entry = store.read().entries.single()
        val cached = File(workspace, "${entry.id}.part")
        assertArrayEquals(payload, cached.readBytes())
        assertFalse(entry.completed)
        assertNotNull(entry.targetUri)
        assertEquals(128L, docs.file(entry.targetUri!!).length())
        assertTrue(cached.setLastModified(1234567890000L))
        docs.beforeWrite = { _, file ->
            // Re-downloading/appending bytes would change this timestamp. A complete local partial
            // must instead be read/verified, then reused as the provider publication source.
            assertEquals(1234567890000L, file.lastModified())
            assertArrayEquals(payload, file.readBytes())
        }
        val positive = arrayListOf<Long>()
        connect().use { smb -> engine(id, workspace, docs, EnginePlan(store.file)).run("download", source, smb, { false }) { bytes, _ ->
            if (bytes > 0) positive.add(bytes)
        } }
        assertTrue(positive.isNotEmpty())
        assertTrue(positive.all { it == payload.size.toLong() })
        assertEquals(1, docs.fileCreates)
        assertEquals(2, docs.writes)
        assertArrayEquals(payload, docs.file(docs.children(docs.tree).single().uri).readBytes())
    }

    @Test fun lostFileRenameAcknowledgmentFindsIdenticalFinalFileWithoutPublishingDuplicate() = isolated { control, folder ->
        upload(control, "$folder/lost-ack.bin")
        val source = source(control, folder, "lost-ack.bin")
        val docs = FakeResumeDocuments(local.newFolder("provider"))
        val id = UUID.randomUUID().toString()
        val workspace = local.newFolder("workspace")
        val store = EnginePlan(File(workspace, "plan.properties"), record(source.path, tree = docs.tree))
        docs.failRenameAfter = { name -> name == "lost-ack.bin" }
        connect().use { smb -> assertThrows(IOException::class.java) {
            engine(id, workspace, docs, store).run("download", source, smb, { false }) { _, _ -> }
        } }
        assertEquals("lost-ack.bin", docs.children(docs.tree).single().name)
        assertFalse(store.read().entries.single().completed)
        assertNull(docs.stat(store.read().entries.single().targetUri!!))
        connect().use { smb -> engine(id, workspace, docs, EnginePlan(store.file)).run("download", source, smb, { false }) { _, _ -> } }
        assertTrue(store.read().entries.single().completed)
        assertEquals(1, docs.fileCreates)
        assertEquals(1, docs.writes)
        assertEquals(1, docs.renames)
        assertArrayEquals(payload, docs.file(docs.children(docs.tree).single().uri).readBytes())
    }

    @Test fun targetCreatedDuringProviderRenameIsNeverOverwrittenOrDeleted() = isolated { control, folder ->
        upload(control, "$folder/collision.bin")
        val source = source(control, folder, "collision.bin")
        val docs = FakeResumeDocuments(local.newFolder("provider"))
        val id = UUID.randomUUID().toString()
        val workspace = local.newFolder("workspace")
        val store = EnginePlan(File(workspace, "plan.properties"), record(source.path, tree = docs.tree))
        docs.beforeRename = { _, name ->
            docs.beforeRename = null
            docs.file(docs.create(docs.tree, name, false)).writeText("unrelated document")
        }
        connect().use { smb -> assertThrows(IOException::class.java) {
            engine(id, workspace, docs, store).run("download", source, smb, { false }) { _, _ -> }
        } }
        val entry = store.read().entries.single()
        assertArrayEquals(payload, File(workspace, "${entry.id}.part").readBytes())
        val unrelated = docs.children(docs.tree).single { it.name == "collision.bin" }
        assertEquals("unrelated document", docs.file(unrelated.uri).readText())
        connect().use { smb -> assertThrows(ResumeConflictException::class.java) {
            engine(id, workspace, docs, EnginePlan(store.file)).run("download", source, smb, { false }) { _, _ -> }
        } }
        assertFalse(store.read().entries.single().completed)
        assertEquals("unrelated document", docs.file(unrelated.uri).readText())
        assertEquals(1, docs.writes)
        assertTrue(docs.stat(entry.targetUri!!) != null)
    }

    @Test fun folderManifestResumesWithoutDuplicatingCompletedChildrenAndKeepsEmptyDirectories() = isolated { control, folder ->
        val plan = pausedFolder(control, folder)
        val completed = plan.store.read().entries.single { it.completed && !it.source.isDirectory }
        assertEquals(1, plan.docs.writes)
        upload(control, "${plan.source.path}/created-after-plan.bin", "later".toByteArray())
        connect().use { smb -> engine(plan.id, plan.workspace, plan.docs, EnginePlan(plan.store.file)).run("download", plan.source, smb, { false }) { _, _ -> } }
        val finalRoot = plan.docs.children(plan.docs.tree).single()
        assertEquals("album", finalRoot.name)
        assertEquals(2, plan.docs.writes)
        assertEquals(2, plan.docs.fileCreates)
        assertTrue(plan.store.read().entries.all { it.completed })
        var completedUri = finalRoot.uri
        completed.relativePath.split('/').forEach { segment -> completedUri = plan.docs.children(completedUri).single { it.name == segment }.uri }
        assertArrayEquals(remoteBytes(control, completed.source.path), plan.docs.file(completedUri).readBytes())
        val children = plan.docs.children(finalRoot.uri)
        assertTrue(children.none { it.name == "created-after-plan.bin" })
        val empty = children.single { it.name == "empty" }
        assertTrue(empty.directory)
        assertTrue(plan.docs.children(empty.uri).isEmpty())
        val nested = children.single { it.name == "nested" }
        assertArrayEquals("second".toByteArray(), plan.docs.file(plan.docs.children(nested.uri).single { it.name == "b.txt" }.uri).readBytes())
    }

    @Test fun lostFolderRenameAcknowledgmentVerifiesFinalTreeAndAvoidsDuplicateRoot() = isolated { control, folder ->
        val plan = pausedFolder(control, folder)
        plan.docs.failRenameAfter = { it == "album" }
        connect().use { smb -> assertThrows(IOException::class.java) {
            engine(plan.id, plan.workspace, plan.docs, EnginePlan(plan.store.file)).run("download", plan.source, smb, { false }) { _, _ -> }
        } }
        assertTrue(plan.store.read().entries.all { it.completed })
        assertNull(plan.docs.stat(plan.store.read().targetUri!!))
        assertEquals("album", plan.docs.children(plan.docs.tree).single().name)
        val creates = plan.docs.folderCreates
        val writes = plan.docs.writes
        connect().use { smb -> engine(plan.id, plan.workspace, plan.docs, EnginePlan(plan.store.file)).run("download", plan.source, smb, { false }) { _, _ -> } }
        assertEquals(creates, plan.docs.folderCreates)
        assertEquals(writes, plan.docs.writes)
        assertEquals(1, plan.docs.children(plan.docs.tree).size)
        assertEquals("album", plan.docs.stat(plan.store.read().targetUri!!)!!.name)
    }

    @Test fun missingRecordedFolderRootFailsWithoutRecreatingAndLosingCompletedChildren() = isolated { control, folder ->
        val plan = pausedFolder(control, folder)
        val root = plan.store.read().targetUri!!
        plan.docs.delete(root)
        val creates = plan.docs.folderCreates
        connect().use { smb -> assertThrows(ResumeConflictException::class.java) {
            engine(plan.id, plan.workspace, plan.docs, EnginePlan(plan.store.file)).run("download", plan.source, smb, { false }) { _, _ -> }
        } }
        assertEquals(creates, plan.docs.folderCreates)
        assertTrue(plan.docs.children(plan.docs.tree).isEmpty())
        assertEquals(1, plan.store.read().entries.count { it.completed && !it.source.isDirectory })
    }

    @Test fun deletedCompletedChildFailsBeforeAnyFurtherProviderPublication() = isolated { control, folder ->
        val plan = pausedFolder(control, folder)
        val completed = plan.store.read().entries.single { it.completed && !it.source.isDirectory }
        plan.docs.delete(completed.targetUri!!)
        assertDamagedCompletedChildFails(plan)
        assertNull(plan.docs.stat(completed.targetUri))
    }

    @Test fun tamperedCompletedChildFailsAndPreservesChangedDocument() = isolated { control, folder ->
        val plan = pausedFolder(control, folder)
        val completed = plan.store.read().entries.single { it.completed && !it.source.isDirectory }
        plan.docs.file(completed.targetUri!!).writeText("user changed completed document")
        assertDamagedCompletedChildFails(plan)
        assertEquals("user changed completed document", plan.docs.file(completed.targetUri).readText())
    }

    private fun assertDamagedCompletedChildFails(plan: PausedFolder) {
        val writes = plan.docs.writes
        val creates = plan.docs.fileCreates
        connect().use { smb -> assertThrows(ResumeConflictException::class.java) {
            engine(plan.id, plan.workspace, plan.docs, EnginePlan(plan.store.file)).run("download", plan.source, smb, { false }) { _, _ -> }
        } }
        assertEquals(writes, plan.docs.writes)
        assertEquals(creates, plan.docs.fileCreates)
    }

    private data class PausedFolder(val id: String, val workspace: File, val docs: FakeResumeDocuments, val store: EnginePlan, val source: UiFile)
    private fun pausedFolder(control: SmbRepository, folder: String): PausedFolder {
        control.mkdir("$folder/album")
        control.mkdir("$folder/album/empty")
        upload(control, "$folder/album/a.bin")
        control.mkdir("$folder/album/nested")
        upload(control, "$folder/album/nested/b.txt", "second".toByteArray())
        val source = source(control, folder, "album")
        val docs = FakeResumeDocuments(local.newFolder("provider"))
        val id = UUID.randomUUID().toString()
        val workspace = local.newFolder("workspace")
        val store = EnginePlan(File(workspace, "plan.properties"), record(source.path, tree = docs.tree))
        val stop = AtomicBoolean()
        store.afterSave = { state -> if (state.entries.any { it.completed && !it.source.isDirectory }) stop.set(true) }
        connect().use { smb -> assertThrows(CancellationException::class.java) {
            engine(id, workspace, docs, store).run("download", source, smb, stop::get) { _, _ -> }
        } }
        store.afterSave = null
        assertEquals(1, store.read().entries.count { it.completed && !it.source.isDirectory })
        return PausedFolder(id, workspace, docs, store, source)
    }
}

/** Descriptor updates are atomic disk writes, so a new engine never receives stale in-memory state. */
private class EnginePlan(val file: File, initial: TransferResume? = null) {
    var afterSave: ((TransferResume) -> Unit)? = null
    init { if (!file.exists()) save(requireNotNull(initial)) }
    fun read(): TransferResume = TransferResumeCodec.decode(file.readText())
    fun save(value: TransferResume) {
        AtomicTextFiles.write(file) { it.write(TransferResumeCodec.encode(value).toByteArray(Charsets.UTF_8)) }
        afterSave?.invoke(value)
    }
}

/** Models path-based document IDs: renaming a folder also invalidates all descendant URIs. */
private class FakeResumeDocuments(private val root: File) : ResumeDocuments {
    private data class Node(var file: File, var parent: String?, val persistent: Boolean = true)
    private val nodes = linkedMapOf<String, Node>()
    val denied = hashSetOf<String>()
    val sourceOpens = hashMapOf<String, Int>()
    val tree = add(File(root, "Documents").apply { mkdirs() }, null)
    var fileCreates = 0
    var folderCreates = 0
    var writes = 0
    var renames = 0
    var failNextWrite = false
    var failRenameAfter: ((String) -> Boolean)? = null
    var beforeWrite: ((String, File) -> Unit)? = null
    var beforeRename: ((String, String) -> Unit)? = null
    private fun add(file: File, parent: String?, persistent: Boolean = true): String =
        "content://resume-test/${UUID.randomUUID()}".also { nodes[it] = Node(file, parent, persistent) }
    fun addSource(name: String, bytes: ByteArray, persistent: Boolean): String {
        val file = File(root, "sources/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return add(file, null, persistent).also { sourceOpens[it] = 0 }
    }
    fun file(uri: String): File = nodes[uri]?.file ?: throw IOException("Document URI missing")
    override fun stat(uri: String): ResumeDocument? = nodes[uri]?.file?.takeIf { it.exists() }?.let {
        ResumeDocument(uri, it.name, it.isDirectory, if (it.isDirectory) 0 else it.length())
    }
    override fun children(uri: String): List<ResumeDocument> {
        check(stat(uri)?.directory == true) { "Document folder missing" }
        return nodes.filterValues { it.parent == uri }.keys.mapNotNull(::stat)
    }
    override fun persistentRead(uri: String) = nodes[uri]?.persistent == true
    override fun open(uri: String): InputStream {
        if (uri in denied) throw IOException("Read permission was revoked")
        if (sourceOpens.containsKey(uri)) sourceOpens[uri] = sourceOpens.getValue(uri) + 1
        return file(uri).inputStream()
    }
    override fun create(parent: String, name: String, directory: Boolean): String {
        SmbAddress.validateName(name)
        if (children(parent).any { it.name.equals(name, true) }) throw IOException("Document name collision")
        val file = File(file(parent), name)
        if (directory) { check(file.mkdir()); folderCreates++ } else { check(file.createNewFile()); fileCreates++ }
        return add(file, parent)
    }
    override fun write(uri: String, source: File, cancelled: () -> Boolean) {
        writes++
        beforeWrite?.invoke(uri, source)
        if (failNextWrite) {
            failNextWrite = false
            file(uri).outputStream().use { output -> source.inputStream().use { input ->
                val buffer = ByteArray(128)
                val count = input.read(buffer)
                if (count > 0) output.write(buffer, 0, count)
            } }
            throw IOException("Simulated provider failure after a partial write")
        }
        source.inputStream().use { input -> file(uri).outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancelled()) throw CancellationException("Provider write interrupted")
                val count = input.read(buffer); if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        } }
    }
    override fun rename(uri: String, name: String): String {
        SmbAddress.validateName(name)
        beforeRename?.invoke(uri, name)
        val node = nodes[uri] ?: throw IOException("Old document ID missing")
        val parent = requireNotNull(node.parent)
        if (children(parent).any { it.uri != uri && it.name.equals(name, true) }) throw IOException("Document name collision")
        val old = node.file
        val next = File(old.parentFile, name)
        if (!old.renameTo(next)) throw IOException("Provider rename failed")
        renames++
        val oldPath = old.toPath()
        val affected = nodes.filterValues { it.file.toPath().startsWith(oldPath) }
        val ids = affected.keys.associateWith { "content://resume-test/${UUID.randomUUID()}" }
        affected.values.forEach {
            it.file = next.toPath().resolve(oldPath.relativize(it.file.toPath())).toFile()
        }
        nodes.values.forEach { value -> value.parent?.let { oldParent -> ids[oldParent]?.let { value.parent = it } } }
        val updated = nodes.entries.map { (oldId, value) -> (ids[oldId] ?: oldId) to value }
        nodes.clear()
        nodes.putAll(updated)
        val newUri = ids.getValue(uri)
        if (failRenameAfter?.invoke(name) == true) {
            failRenameAfter = null
            throw IOException("Rename succeeded, but its acknowledgment was lost")
        }
        return newUri
    }
    override fun delete(uri: String) {
        val file = file(uri)
        check(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) && file.canonicalFile != root.canonicalFile)
        val path = file.toPath()
        val ids = nodes.filterValues { it.file.toPath().startsWith(path) }.keys.toList()
        check(file.deleteRecursively())
        ids.forEach(nodes::remove)
    }
}
