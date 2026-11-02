package com.ysplayer

import android.Manifest
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysplayer.data.LibraryDatabase
import com.ysplayer.data.*
import kotlinx.coroutines.flow.first
import com.ysplayer.library.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Exercise the actual SAF query and scan transaction, not a copy of the grouping rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = android.app.Application::class)
class ScannerIntegrationTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var provider: FixtureDocumentsProvider
    private val tree = DocumentsContract.buildTreeDocumentUri("ysplayer.fixture", "root")
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        repository = LibraryRepository(context, db)
        provider = FixtureDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "ysplayer.fixture"; exported = true; grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        ShadowContentResolver.registerProviderInternal("ysplayer.fixture", provider)
    }
    @After fun close() { db.close() }
    @Test fun scanImportsMediaAndKeepsManualDataOnRescan() = runBlocking {
        repository.addSource(tree, false)
        val dao = db.library()
        val work = dao.allWorks().single()
        assertEquals("RJ000001", work.rj)
        assertEquals(2, dao.allTracks().size)
        assertEquals(1, dao.allTracks().count { it.resource })
        assertEquals(1, dao.allAssets().count { it.type == "subtitle" })
        assertTrue(work.autoCover.isNotEmpty())
        dao.putWorks(listOf(work.copy(title = "My title", voices = "Actor", circles = "Circle", review = "", edited = true)))
        val track = dao.allTracks().first { !it.resource }
        dao.putTracks(listOf(track.copy(sortOrder = 7, subtitleOffset = 500, manualWorkId = "manual-work")))
        repository.scan(dao.allSources().single())
        assertEquals(2, dao.allTracks().size)
        assertEquals("My title", dao.allWorks().single().title)
        val restored = dao.allTracks().first { it.id == track.id }
        assertEquals(7, restored.sortOrder)
        assertEquals(500L, restored.subtitleOffset)
        assertEquals("manual-work", restored.effectiveWorkId)
    }
    @Test fun unreadableSourceDoesNotMarkPreviousTracksMissing() = runBlocking {
        repository.addSource(tree, false)
        val before = db.library().allTracks()
        val works = db.library().allWorks()
        val assets = db.library().allAssets()
        provider.failQueries = true
        val failed = runCatching { repository.scan(db.library().allSources().single()) }
        assertTrue(failed.isFailure)
        assertEquals(before, db.library().allTracks())
        assertEquals(works, db.library().allWorks())
        assertEquals(assets, db.library().allAssets())
        assertFalse(repository.scanState.value.running)
    }
    @Test fun addingRjPrefixWithStableDocumentIdsPreservesTheWorkAndHistory() = runBlocking { renameWithRj(false) }
    @Test fun addingRjPrefixWithPathDocumentIdsMigratesTracksSubtitlesAndHistory() = runBlocking { renameWithRj(true) }
    private suspend fun renameWithRj(pathIds: Boolean) {
        provider.includeProgramResources = false
        provider.documentIdsFollowPath = pathIds
        provider.workFolderName = "Work"
        repository.addSource(tree, false)
        val dao = db.library()
        val original = dao.allWorks().single()
        val track = dao.allTracks().single()
        val subtitle = dao.allAssets().single { it.type == "subtitle" }
        dao.putWorks(listOf(original.copy(title = "My title", cover = "file:///manual.jpg", voices = "Alice、Beth", circles = "Circle",
            edited = true, favorite = true, tags = "低语", tagsKnown = true)))
        dao.putTracks(listOf(track.copy(sortOrder = 7, subtitleUri = subtitle.uri, subtitleOffset = 500, subtitleEncoding = "UTF-8")))
        dao.putPlaylist(Playlist("list", "List"))
        dao.putEntries(listOf(PlaylistEntry("whole", "list", original.id, position = 0), PlaylistEntry("track", "list", original.id, track.id, 1)))
        dao.putProgress(Progress(original.id, track.id, 12345, 1234))
        provider.workFolderName = "RJ000001 Work"
        repository.scan(dao.allSources().single())
        val work = dao.allWorks().single()
        val updated = dao.allTracks().single()
        assertEquals(original.id, work.id)
        assertEquals("RJ000001", work.rj)
        assertEquals("My title", work.title)
        assertEquals("file:///manual.jpg", work.cover)
        assertEquals("Alice、Beth", work.voices)
        assertEquals("Circle", work.circles)
        assertEquals("低语", work.tags)
        assertTrue(work.favorite && work.tagsKnown)
        assertTrue(work.review.isEmpty())
        assertEquals(pathIds, updated.id != track.id)
        assertTrue(updated.available)
        assertEquals("RJ000001 Work/01.mp3", updated.path)
        assertEquals(7, updated.sortOrder)
        assertEquals(500L, updated.subtitleOffset)
        assertEquals("UTF-8", updated.subtitleEncoding)
        assertEquals(dao.allAssets().single { it.type == "subtitle" }.uri, updated.subtitleUri)
        assertEquals(Progress(work.id, updated.id, 12345, 1234), dao.progressFor(work.id))
        assertEquals(updated.id, dao.entries().first().single { it.id == "track" }.trackId)
        assertTrue(dao.entries().first().all { it.workId == work.id })
        assertTrue(dao.allAssets().all { it.workId == work.id })
        assertEquals(0, provider.deleteCalls)
    }
    @Test fun staleUnnumberedWorkFromAnOlderScanIsRemoved() = runBlocking {
        provider.includeProgramResources = false
        provider.workFolderName = "Work"
        repository.addSource(tree, false)
        val dao = db.library()
        val stale = dao.allWorks().single()
        val canonical = Work("rj:RJ000001", "Work", "RJ000001")
        // Simulate the older scanner having already reassigned stable document IDs.
        dao.putWorks(listOf(stale.copy(review = "", edited = true), canonical))
        dao.putTracks(dao.allTracks().map { it.copy(workId = canonical.id, path = "RJ000001 Work/${it.name}") })
        provider.workFolderName = "RJ000001 Work"
        repository.scan(dao.allSources().single())
        assertNull(dao.work(stale.id))
        assertEquals(canonical.id, dao.allWorks().single().id)
    }
    @Test fun existingDuplicateFromAnOlderPathBasedScanMergesCreditsFavoriteAndLatestProgress() = runBlocking {
        provider.includeProgramResources = false
        provider.documentIdsFollowPath = true
        provider.workFolderName = "Work"
        repository.addSource(tree, false)
        val dao = db.library()
        val old = dao.allWorks().single()
        val oldTrack = dao.allTracks().single()
        dao.putWorks(listOf(old.copy(cover = "file:///manual.jpg", favorite = true, voices = "Alice、Beth", edited = true),
            Work("rj:RJ000001", "Work", "RJ000001")))
        provider.workFolderName = "RJ000001 Work"
        val newUri = DocumentsContract.buildDocumentUriUsingTree(tree, "RJ000001 Work:audio")
        val newId = LibraryRepository.stableId(newUri.authority + ":" + DocumentsContract.getDocumentId(newUri))
        dao.putTracks(listOf(oldTrack.copy(available = false), oldTrack.copy(id = newId, workId = "rj:RJ000001", uri = newUri.toString(), path = "RJ000001 Work/01.mp3")))
        dao.putPlaylist(Playlist("list", "List"))
        dao.putEntries(listOf(PlaylistEntry("entry", "list", old.id, oldTrack.id, 0)))
        dao.putProgress(Progress(old.id, oldTrack.id, 700, 200))
        dao.putProgress(Progress("rj:RJ000001", newId, 100, 100))
        repository.scan(dao.allSources().single())
        val work = dao.allWorks().single()
        assertEquals("rj:RJ000001", work.id)
        assertTrue(work.favorite)
        assertEquals("file:///manual.jpg", work.cover)
        assertEquals("Alice、Beth", work.voices)
        assertEquals(newId, dao.allTracks().single().id)
        assertEquals(Progress(work.id, newId, 700, 200), dao.progressFor(work.id))
        assertNull(dao.progressFor(old.id))
        assertEquals(PlaylistEntry("entry", "list", work.id, newId, 0), dao.entries().first().single())
    }
    @Test fun completeEmptyScanPrunesStaleWorkAndEntriesButKeepsSourceAndUnrelatedManualWork() = runBlocking {
        repository.addSource(tree, false)
        val dao = db.library()
        val work = dao.allWorks().single()
        dao.putWorks(listOf(Work("manual", "Manual", edited = true)))
        dao.putPlaylist(Playlist("list", "List"))
        dao.putEntries(listOf(PlaylistEntry("whole", "list", work.id, position = 0),
            PlaylistEntry("track", "list", work.id, dao.allTracks().first().id, 1)))
        provider.hasWork = false
        repository.scan(dao.allSources().single())
        assertNull(dao.work(work.id))
        assertTrue(dao.allTracks().isEmpty())
        assertTrue(dao.entries().first().isEmpty())
        assertEquals("manual", dao.allWorks().single().id)
        assertEquals(1, dao.allSources().size)
        assertEquals(1, dao.playlists().first().size)
    }
    @Test fun anotherAvailableSourceProtectsTheSameWorkFromPruning() = runBlocking {
        repository.addSource(tree, false)
        val dao = db.library()
        val work = dao.allWorks().single()
        dao.putTracks(listOf(Track("other-track", work.id, "other-source", "content://other/audio", "02.mp3", "02.mp3", "MP3", "本篇")))
        provider.hasWork = false
        repository.scan(dao.allSources().single())
        assertNotNull(dao.work(work.id))
        assertTrue(dao.track("other-track")!!.available)
    }
    @Test fun failureAfterPartialDirectoryTraversalLeavesTheEntireIndexUnchanged() = runBlocking {
        repository.addSource(tree, false)
        val dao = db.library()
        val works = dao.allWorks()
        val tracks = dao.allTracks()
        val assets = dao.allAssets()
        provider.workFolderName = "RJ000001 Renamed Work"
        provider.failOnDocument = "game"
        assertTrue(runCatching { repository.scan(dao.allSources().single()) }.isFailure)
        assertEquals(works, dao.allWorks())
        assertEquals(tracks, dao.allTracks())
        assertEquals(assets, dao.allAssets())
    }
    @Test fun manualRemovalDeletesOnlyTheIndexAndCanBeReimported() = runBlocking {
        repository.addSource(tree, false)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coverFile = java.io.File(context.filesDir, "kept-cover.jpg").apply { writeText("fixture") }
        val dao = db.library()
        val work = dao.allWorks().single()
        val track = dao.allTracks().first()
        val removedTrackIds = dao.allTracks().map { it.id }.toSet()
        dao.putWorks(listOf(work.copy(cover = android.net.Uri.fromFile(coverFile).toString()), Work("other", "Other", edited = true)))
        dao.putPlaylist(Playlist("list", "List"))
        dao.putEntries(listOf(PlaylistEntry("whole", "list", work.id, position = 0), PlaylistEntry("track", "list", work.id, track.id, 1),
            PlaylistEntry("keep", "list", "other", position = 2)))
        dao.putProgress(Progress(work.id, track.id, 300, 100))
        db.credits().putWork(CreditWork(work.rj, "https://www.dlsite.com/", 100, true))
        assertEquals(removedTrackIds, repository.removeWork(work.id))
        assertNull(dao.work(work.id))
        assertTrue(dao.allTracks().isEmpty())
        assertTrue(dao.allAssets().isEmpty())
        assertEquals("keep", dao.entries().first().single().id)
        assertEquals(1, dao.playlists().first().size)
        assertEquals(1, dao.allSources().size)
        assertNotNull(dao.progressFor(work.id))
        assertNotNull(db.credits().work(work.rj))
        assertTrue(coverFile.exists())
        assertEquals(0, provider.deleteCalls)
        repository.scan(dao.allSources().single())
        assertNotNull(dao.work(work.id))
        assertEquals(removedTrackIds, dao.allTracks().map { it.id }.toSet())
        assertTrue(coverFile.exists())
        assertEquals(0, provider.deleteCalls)
    }
    @Test fun repairingAndRescanningOldEditionPairKeepsIdentityCoverAndHistory() = runBlocking {
        provider.workFolderName = "RJ000001RJ000002 Work"
        val dao = db.library()
        val source = Source(tree.toString(), "Library")
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(tree, "audio")
        val trackId = LibraryRepository.stableId(audioUri.authority + ":audio")
        dao.putSource(source)
        dao.putWorks(listOf(Work("legacy-folder-id", "手改标题", cover = "file:///manual.jpg", voices = "手填声优", circles = "手填社团",
            review = "多个 RJ 编号，需确认归属", edited = true, favorite = true, tags = "ASMR", tagsKnown = true)))
        dao.putTracks(listOf(Track(trackId, "legacy-folder-id", source.uri, audioUri.toString(), "01.mp3", "RJ000001RJ000002 Work/01.mp3", "MP3", "本篇", sortOrder = 3)))
        dao.putPlaylist(Playlist("list", "List"))
        dao.putEntries(listOf(PlaylistEntry("entry", "list", "legacy-folder-id", trackId, 0)))
        dao.putProgress(Progress("legacy-folder-id", trackId, 12345, 1234))
        repository.repairPrimaryRj()
        assertEquals("RJ000001", dao.allWorks().single().rj)
        repository.scan(source)
        val work = dao.allWorks().single()
        assertEquals("legacy-folder-id", work.id)
        assertEquals("手改标题", work.title)
        assertEquals("file:///manual.jpg", work.cover)
        assertEquals("手填声优", work.voices)
        assertEquals("ASMR", work.tags)
        assertTrue(work.favorite && work.tagsKnown)
        assertTrue(work.review.isEmpty())
        assertEquals(12345L, dao.progressFor(work.id)!!.position)
        assertEquals(work.id, dao.entries().first().single().workId)
        assertTrue(dao.allTracks().all { it.workId == work.id })
        assertTrue(dao.allAssets().all { it.workId == work.id })
        assertEquals(3, dao.allTracks().first { it.id == trackId }.sortOrder)
    }
}

class FixtureDocumentsProvider : DocumentsProvider() {
    var failQueries = false
    var failOnDocument: String? = null
    var workFolderName = "[RJ000001] Work"
    var documentIdsFollowPath = false
    var includeProgramResources = true
    var hasWork = true
    var deleteCalls = 0
    private data class Doc(val id: String, val name: String, val mime: String, val parent: String?)
    private val dir = DocumentsContract.Document.MIME_TYPE_DIR
    private val docs = listOf(
        Doc("root", "Library", dir, null),
        Doc("work", "[RJ000001] Work", dir, "root"),
        Doc("audio", "01.mp3", "audio/mpeg", "work"),
        Doc("subtitle", "01.mp3.vtt", "text/vtt", "work"),
        Doc("cover", "cover.jpg", "image/jpeg", "work"),
        Doc("game", "game", dir, "work"),
        Doc("exe", "player.exe", "application/octet-stream", "game"),
        Doc("voices", "voices", dir, "game"),
        Doc("resource", "effect.ogg", "audio/ogg", "voices")
    )
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    private fun rawId(documentId: String) = documentId.substringAfterLast(':')
    private fun visible(doc: Doc) = (hasWork || doc.id == "root") &&
        (includeProgramResources || doc.id !in setOf("game", "exe", "voices", "resource"))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(docs.filter { it.id == rawId(documentId) && visible(it) }, projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = rawId(parentDocumentId)
        check(!failQueries && parent != failOnDocument) { "Storage disconnected" }
        return cursor(docs.filter { it.parent == parent && visible(it) }, projection)
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current: String? = rawId(documentId)
        while (current != null) {
            if (current == rawId(parentDocumentId)) return true
            current = docs.find { it.id == current }?.parent
        }
        return false
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = throw java.io.FileNotFoundException()
    override fun deleteDocument(documentId: String) { deleteCalls++ }
    private fun cursor(rows: List<Doc>, requested: Array<out String>?): Cursor {
        val columns = requested ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { rows.forEach { row ->
            addRow(columns.map { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> if (documentIdsFollowPath && row.id != "root") "$workFolderName:${row.id}" else row.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (row.id == "work") workFolderName else row.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.mime
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                else -> null
            } })
        } }
    }
}
