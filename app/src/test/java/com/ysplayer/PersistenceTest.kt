package com.ysplayer

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.ysplayer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Run the real generated Room schema against Android 10 and Android 15 runtimes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = android.app.Application::class)
class PersistenceTest {
    private lateinit var db: LibraryDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
    }
    @After fun close() { db.close() }
    @Test fun failedScanTransactionKeepsExistingAvailabilityAndManualData() = runBlocking {
        val dao = db.library()
        val work = Work("work", "Manual title", cover = "file:///cover.jpg", voices = "Actor", circles = "Circle", edited = true)
        val track = Track("track", "work", "source", "content://track", "01.mp3", "01.mp3", "MP3", "本篇", manualWorkId = "manual")
        dao.putWorks(listOf(work)); dao.putTracks(listOf(track))
        runCatching { db.withTransaction { dao.markUnavailable("source"); throw IllegalStateException("provider disconnected") } }
        assertTrue(dao.allTracks().single().available)
        assertEquals("manual", dao.allTracks().single().effectiveWorkId)
        assertEquals(work, dao.allWorks().single())
    }
    @Test fun idempotentUpsertDoesNotDuplicateAndMissingTrackKeepsHistory() = runBlocking {
        val dao = db.library()
        val track = Track("track", "work", "source", "content://track", "01.mp3", "01.mp3", "MP3", "本篇")
        dao.putTracks(listOf(track)); dao.putTracks(listOf(track))
        dao.putProgress(Progress("work", "track", 12500, 1))
        dao.markUnavailable("source")
        assertEquals(1, dao.allTracks().size)
        assertFalse(dao.allTracks().single().available)
        assertEquals(12500L, dao.progressFor("work")?.position)
    }
}
