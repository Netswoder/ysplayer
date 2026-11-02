package com.ysplayer

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysplayer.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class DatabaseMigrationTest {
    @Test fun upgradesOriginalSchemaWithoutLosingCoversPlaylistsOrPlaybackProgress() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(javaClass.getResourceAsStream("/library-v1.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
        val path = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    old.execSQL(indices.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", entity.getString("tableName")))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO works VALUES ('one','手填标题','RJ123456','content://auto','file:///cover.jpg','声优甲、声优乙','社团','',1,1)")
            old.execSQL("INSERT INTO sources VALUES ('content://source','音声',0)")
            old.execSQL("INSERT INTO tracks VALUES ('track','one','content://source','content://audio','01.mp3','01.mp3','MP3','本篇',1,0,'',2,'content://subtitle',150,'UTF-8')")
            old.execSQL("INSERT INTO playlists VALUES ('list','我的列表')")
            old.execSQL("INSERT INTO playlist_entries VALUES ('entry','list','one','track',0)")
            old.execSQL("INSERT INTO progress VALUES ('one','track',120000,1234)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            val work = db.library().allWorks().single()
            assertEquals("file:///cover.jpg", work.cover)
            assertEquals("声优甲、声优乙", work.voices)
            assertEquals("", work.tags)
            assertFalse(work.tagsKnown)
            assertTrue(work.edited && work.favorite)
            assertEquals("我的列表", db.library().playlists().first().single().name)
            assertEquals("track", db.library().entries().first().single().trackId)
            assertEquals(120000L, db.library().progressFor("one")!!.position)
            assertEquals(150L, db.library().allTracks().single().subtitleOffset)
            assertEquals(CreditStats(), db.credits().stats().first())
            db.credits().putWork(CreditWork("RJ123456", "https://www.dlsite.com", 1, true))
            assertEquals(1, db.credits().stats().first().works)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun upgradesMetadataCacheWithoutPretendingOldRecordsAlreadyHaveChineseTags() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(javaClass.getResourceAsStream("/library-v2.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
        val path = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO works VALUES ('one','Title','RJ123456','','file:///cover.jpg','手填声优','手填社团','',1,1)")
            old.execSQL("INSERT INTO credit_works VALUES ('RJ123456','https://www.dlsite.com',1234,1)")
            old.execSQL("INSERT INTO credit_creators VALUES ('circle:RG1','circle','社团','https://www.dlsite.com')")
            old.execSQL("INSERT INTO credit_links VALUES ('RJ123456','circle:RG1',0)")
            old.execSQL("INSERT INTO credit_attempts VALUES ('RJ123457',1234,9999999999999,'HTTP 403')")
            old.version = 2
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            val work = db.library().allWorks().single()
            assertEquals("手填声优", work.voices)
            assertEquals("file:///cover.jpg", work.cover)
            assertFalse(work.tagsKnown)
            assertFalse(db.credits().work("RJ123456")!!.tagsFetched)
            assertEquals(1234L, db.credits().work("RJ123456")!!.fetchedAt)
            assertEquals("社团", db.credits().creators("RJ123456").single().name)
            assertEquals(0L, db.credits().attempt("RJ123457")!!.retryAfter)
            assertTrue(db.credits().attempt("RJ123457")!!.message.contains("HTTP 403"))
            db.credits().putTags(listOf(CreditTag("RJ123456", "497", "ASMR", 0)))
            assertEquals("ASMR", db.credits().tags("RJ123456").single().name)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
