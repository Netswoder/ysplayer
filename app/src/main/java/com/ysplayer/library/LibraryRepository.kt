package com.ysplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.ysplayer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

data class ScanState(val running: Boolean = false, val count: Int = 0, val message: String = "")
data class DocumentEntry(val uri: String, val name: String, val path: String)

class LibraryRepository(private val context: Context, private val database: LibraryDatabase) {
    val dao = database.library()
    val scanState = MutableStateFlow(ScanState())
    private val scanLock = Mutex()
    suspend fun addSource(uri: Uri, coversOnly: Boolean) {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val rootName = withContext(Dispatchers.IO) {
            context.contentResolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), Bundle.EMPTY, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } ?: error("无法打开目录")
        val source = Source(uri.toString(), rootName, coversOnly)
        dao.putSource(source)
        scan(source)
    }
    suspend fun scan(source: Source) = scanLock.withLock {
        scanState.value = ScanState(true, message = "正在扫描 ${source.name}")
        try {
            withContext(Dispatchers.IO) { repairPrimaryRj(); scanInternal(source) }
            scanState.value = scanState.value.copy(running = false, message = "扫描完成")
        } catch (e: CancellationException) {
            scanState.value = scanState.value.copy(running = false, message = "扫描已取消，保留原有索引")
            throw e
        } catch (e: Exception) {
            scanState.value = scanState.value.copy(running = false, message = "扫描失败：${e.message}。请检查目录或重新授权。")
            throw e
        }
    }
    /** Resolve old ambiguous edition pairs without changing IDs referenced by lists or progress. */
    suspend fun repairPrimaryRj() = database.withTransaction {
        val sources = dao.allSources().associateBy { it.uri }
        val tracks = dao.allTracks().groupBy { it.workId }
        dao.allWorks().filter { it.rj.isBlank() }.forEach { work ->
            val originals = tracks[work.id].orEmpty()
            val codes = originals.mapNotNull { track ->
                val parents = listOf(sources[track.sourceUri]?.name.orEmpty()) + track.path.split('/').dropLast(1)
                val first = parents.firstOrNull { LibraryRules.ids(it).isNotEmpty() }
                first?.let { LibraryRules.ids(it).takeIf { ids -> ids.size > 1 }?.first() }
            }
            if (codes.isNotEmpty() && codes.size == originals.size && codes.distinct().size == 1) dao.resolveRj(work.id, codes.first())
        }
    }
    private suspend fun scanInternal(savedSource: Source) {
        val tree = Uri.parse(savedSource.uri)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), Bundle.EMPTY, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        val source = savedSource.copy(name = name?.takeIf { it.isNotBlank() } ?: savedSource.name)
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(DocumentsContract.getTreeDocumentId(tree) to "")
        val seen = hashSetOf<String>()
        val files = mutableListOf<DocumentEntry>()
        val programDirs = hashSetOf<String>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (id, prefix) = pending.removeFirst()
            if (!seen.add(id)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val cursor = context.contentResolver.query(children, projection, Bundle.EMPTY, null)
                ?: error("目录暂不可读：$prefix")
            cursor.use {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val childId = it.getString(0)
                    val name = it.getString(1) ?: continue
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(childId to path)
                    else {
                        val ext = LibraryRules.extension(name)
                        if (ext == "exe") programDirs.add(prefix)
                        if (ext in LibraryRules.audioExtensions || ext in LibraryRules.subtitleExtensions || ext in LibraryRules.imageExtensions)
                            files.add(DocumentEntry(DocumentsContract.buildDocumentUriUsingTree(tree, childId).toString(), name, path))
                    }
                }
            }
            scanState.value = ScanState(true, files.size, "${source.name} · 已发现 ${files.size} 个媒体文件")
        }
        val oldWorks = dao.allWorks().associateBy { it.id }
        val idsByRj = oldWorks.values.filter { it.rj.isNotBlank() }.sortedBy { if (it.id.startsWith("rj:")) 0 else 1 }
            .groupBy { it.rj.uppercase() }.mapValues { it.value.first().id }
        val oldTracks = dao.allTracks().associateBy { it.id }
        val audio = if (source.coversOnly) emptyList() else files.filter { LibraryRules.extension(it.name) in LibraryRules.audioExtensions }.map { file ->
            val uri = Uri.parse(file.uri)
            ScannedAudio(file, stableId(uri.authority + ":" + DocumentsContract.getDocumentId(uri)), LibraryRules.boundary(source.name, file.path))
        }
        val reconciliation = ScanReconciliation(audio, oldTracks.values.filter { it.sourceUri == source.uri }, oldWorks)
        val audioByUri = audio.associateBy { it.file.uri }
        fun workId(boundary: LibraryRules.Boundary): String {
            val rj = boundary.key.takeIf { it.startsWith("rj:") }?.removePrefix("rj:").orEmpty()
            return if (rj.isNotEmpty()) idsByRj[rj] ?: reconciliation.reusableWorkIds[boundary.key] ?: boundary.key else stableId(source.uri + boundary.key)
        }
        val previousAssets = dao.allAssets().filter { it.sourceUri == source.uri }
        val filesByPath = files.groupBy { ScanReconciliation.renameKey(it.path) }
        val movedAssets = previousAssets.associate { old -> old.uri to (filesByPath[ScanReconciliation.renameKey(old.path)]?.singleOrNull()?.uri ?: old.uri) }
        val works = linkedMapOf<String, Work>()
        val tracks = mutableListOf<Track>()
        val assets = mutableListOf<Asset>()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val boundary = LibraryRules.boundary(source.name, file.path)
            val rj = boundary.key.takeIf { it.startsWith("rj:") }?.removePrefix("rj:").orEmpty()
            val id = workId(boundary)
            val ext = LibraryRules.extension(file.name)
            if (ext in LibraryRules.audioExtensions && !source.coversOnly) {
                val original = oldWorks[id]
                val raw = source.name + "/" + file.path
                val voice = Regex("(?i)CV[ :：]+([^()（）/]+)").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
                works[id] = original?.copy(rj = rj.ifEmpty { original.rj }, title = if (original.edited) original.title else boundary.title,
                    review = if (original.edited && original.review !in automaticReviews) original.review else boundary.review) ?: Work(id, boundary.title,
                    rj = rj,
                    voices = voice, review = boundary.review)
                val scanned = audioByUri.getValue(file.uri)
                val trackId = scanned.trackId
                val old = reconciliation.original(scanned)
                tracks.add(Track(trackId, id, source.uri, file.uri, file.name, file.path,
                    LibraryRules.variant(file.path), LibraryRules.section(file.path), resource = LibraryRules.resource(file.path, programDirs),
                    manualWorkId = old?.manualWorkId.orEmpty(), sortOrder = old?.sortOrder ?: -1,
                    subtitleUri = movedAssets[old?.subtitleUri] ?: old?.subtitleUri.orEmpty(), subtitleOffset = old?.subtitleOffset ?: 0,
                    subtitleEncoding = old?.subtitleEncoding ?: "自动"))
            } else if (ext in LibraryRules.subtitleExtensions || ext in LibraryRules.imageExtensions) {
                val imageIds = LibraryRules.ids(file.name)
                val assetWorkId = if (source.coversOnly && imageIds.isNotEmpty()) idsByRj[imageIds.first()] ?: "rj:${imageIds.first()}" else id
                assets.add(Asset(file.uri, source.uri, assetWorkId, file.name, file.path,
                    if (ext in LibraryRules.imageExtensions) "image" else "subtitle"))
            }
        }
        currentCoroutineContext().ensureActive()
        database.withTransaction {
            dao.putSource(source)
            if (!source.coversOnly) dao.markUnavailable(source.uri)
            dao.clearAssets(source.uri)
            // Re-read within the transaction: a slow SAF scan must not erase edits or fetched credits.
            dao.putWorks(works.values.map { indexed ->
                dao.work(indexed.id)?.let { fresh -> fresh.copy(rj = indexed.rj.ifEmpty { fresh.rj },
                    title = if (fresh.edited) fresh.title else indexed.title,
                    review = if (fresh.edited && fresh.review !in automaticReviews) fresh.review else indexed.review) } ?: indexed
            })
            dao.putTracks(tracks)
            dao.putAssets(assets)
            reconciliation.relocated.forEach { (old, renamed) ->
                dao.moveTrackEntries(old.id, renamed.trackId)
                dao.moveTrackProgress(old.id, renamed.trackId)
            }
            dao.deleteTracks(reconciliation.relocated.map { it.first.id })
            reconciliation.destinations.forEach { (oldId, keys) ->
                val key = keys.singleOrNull() ?: return@forEach
                val target = audio.firstOrNull { it.boundary.key == key }?.let { workId(it.boundary) } ?: return@forEach
                val old = dao.work(oldId)
                if (oldId != target && old?.rj?.isBlank() == true && key.startsWith("rj:") &&
                    dao.allTracks().none { it.available && (it.workId == oldId || it.effectiveWorkId == oldId) }) mergeRenamedWork(oldId, target)
            }
            if (!source.coversOnly) {
                val affected = (oldTracks.values.filter { it.sourceUri == source.uri }.flatMap { listOf(it.workId, it.effectiveWorkId) } +
                    previousAssets.map { it.workId }).toSet()
                pruneStaleWorks(affected)
            }
            val images = dao.allAssets().filter { it.type == "image" }.groupBy { it.workId }
            val refreshed = dao.allWorks().map { work ->
                val candidates = images[work.id].orEmpty().sortedWith(compareBy<Asset> {
                    if (Regex("(?i)^(cover|folder|jacket)[.]").containsMatchIn(it.name)) 0 else 1
                }.thenBy { it.path.length })
                val named = candidates.filter { Regex("(?i)^(cover|folder|jacket)[.]").containsMatchIn(it.name) }
                val chosen = when {
                    named.size == 1 -> named.single().uri
                    candidates.size == 1 -> candidates.single().uri
                    candidates.any { it.uri == work.autoCover } -> work.autoCover
                    else -> ""
                }
                work.copy(autoCover = chosen)
            }
            dao.putWorks(refreshed)
        }
    }
    private suspend fun mergeRenamedWork(oldId: String, targetId: String) {
        val old = dao.work(oldId) ?: return
        val target = dao.work(targetId) ?: return
        dao.putWorks(listOf(target.copy(title = if (old.edited && !target.edited) old.title else target.title,
            cover = target.cover.ifBlank { old.cover }, voices = target.voices.ifBlank { old.voices }, circles = target.circles.ifBlank { old.circles },
            favorite = target.favorite || old.favorite, edited = target.edited || old.edited,
            tags = if (target.tagsKnown) target.tags else old.tags, tagsKnown = target.tagsKnown || old.tagsKnown)))
        dao.moveWorkEntries(oldId, targetId)
        dao.moveWorkAssets(oldId, targetId)
        dao.moveManualWorkLinks(oldId, targetId)
        val latest = listOfNotNull(dao.progressFor(oldId), dao.progressFor(targetId)).maxByOrNull { it.updated }
        if (latest != null) dao.putProgress(latest.copy(workId = targetId))
        dao.deleteProgress(oldId)
        removeWorkIndex(oldId)
    }
    private suspend fun pruneStaleWorks(affected: Set<String>) {
        val tracks = dao.allTracks()
        val live = tracks.filter { it.available }.flatMap { listOf(it.workId, it.effectiveWorkId) }.toSet()
        val referenced = tracks.flatMap { listOf(it.workId, it.effectiveWorkId) }.toSet()
        dao.allWorks().filter { work -> work.id !in live && (work.id in affected ||
            work.id !in referenced && (!work.edited || work.autoCover.isNotBlank() || work.id.startsWith("rj:") || work.review in automaticReviews)) }
            .forEach { removeWorkIndex(it.id) }
    }
    /** Index operations never call SAF deleteDocument or remove a source file. */
    suspend fun removeWork(id: String): Set<String> = scanLock.withLock {
        database.withTransaction { removeWorkIndex(id) }
    }
    private suspend fun removeWorkIndex(id: String): Set<String> {
        val removed = dao.allTracks().filter { it.effectiveWorkId == id }.map { it.id }
        dao.deleteWorkEntries(id, removed)
        dao.deleteTracks(removed)
        dao.deleteWorkAssets(id)
        dao.deleteWork(id)
        return removed.toSet()
    }
    suspend fun saveCover(work: Work, uri: Uri) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取该图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法读取该图片")
        val folder = File(context.filesDir, "covers").apply { mkdirs() }
        val dest = File(folder, "${UUID.randomUUID()}.jpg")
        try {
            dest.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            dao.setCover(work.id, Uri.fromFile(dest).toString())
        } finally { bitmap.recycle() }
    }
    suspend fun removeSource(source: Source) = database.withTransaction {
        dao.markUnavailable(source.uri)
        dao.clearAssets(source.uri)
        dao.deleteSource(source.uri)
    }
    suspend fun deletePlaylist(id: String) = database.withTransaction { dao.clearEntries(id); dao.deletePlaylist(id) }
    suspend fun saveEntries(id: String, entries: List<PlaylistEntry>) = database.withTransaction {
        dao.clearEntries(id)
        dao.putEntries(entries.mapIndexed { i, item -> item.copy(position = i) })
    }
    companion object {
        private val automaticReviews = setOf("未找到 RJ，请确认作品归属", "多个 RJ 编号，需确认归属")
        fun stableId(text: String) = UUID.nameUUIDFromBytes(text.toByteArray(Charsets.UTF_8)).toString()
        fun sorted(tracks: List<Track>) = tracks.sortedWith { a, b ->
            if (a.sortOrder >= 0 || b.sortOrder >= 0) (a.sortOrder.takeIf { it >= 0 } ?: Int.MAX_VALUE)
                .compareTo(b.sortOrder.takeIf { it >= 0 } ?: Int.MAX_VALUE)
            else LibraryRules.naturalComparator.compare(a.path, b.path)
        }
    }
}
