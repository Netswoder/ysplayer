package com.ysplayer.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources")
data class Source(@PrimaryKey val uri: String, val name: String, val coversOnly: Boolean = false)

@Entity(tableName = "works")
data class Work(
    @PrimaryKey val id: String, val title: String, val rj: String = "",
    val autoCover: String = "", val cover: String = "", val voices: String = "",
    val circles: String = "", val review: String = "", val edited: Boolean = false,
    val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    @ColumnInfo(defaultValue = "0") val tagsKnown: Boolean = false
)
@Entity(tableName = "tracks", indices = [Index("workId"), Index("sourceUri")])
data class Track(
    @PrimaryKey val id: String, val workId: String, val sourceUri: String,
    val uri: String, val name: String, val path: String, val variant: String,
    val section: String, val available: Boolean = true, val resource: Boolean = false,
    val manualWorkId: String = "", val sortOrder: Int = -1,
    val subtitleUri: String = "", val subtitleOffset: Long = 0,
    val subtitleEncoding: String = "自动"
) { val effectiveWorkId get() = manualWorkId.ifEmpty { workId } }

@Entity(tableName = "assets", indices = [Index("workId"), Index("sourceUri")])
data class Asset(@PrimaryKey val uri: String, val sourceUri: String, val workId: String,
                 val name: String, val path: String, val type: String)
@Entity(tableName = "playlists")
data class Playlist(@PrimaryKey val id: String, val name: String)
@Entity(tableName = "playlist_entries", indices = [Index("playlistId")])
data class PlaylistEntry(@PrimaryKey val id: String, val playlistId: String,
                         val workId: String, val trackId: String = "", val position: Int)
@Entity(tableName = "progress")
data class Progress(@PrimaryKey val workId: String, val trackId: String, val position: Long, val updated: Long)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM works ORDER BY title") fun works(): Flow<List<Work>>
    @Query("SELECT * FROM tracks") fun tracks(): Flow<List<Track>>
    @Query("SELECT * FROM sources") fun sources(): Flow<List<Source>>
    @Query("SELECT * FROM playlists") fun playlists(): Flow<List<Playlist>>
    @Query("SELECT * FROM playlist_entries ORDER BY position") fun entries(): Flow<List<PlaylistEntry>>
    @Query("SELECT * FROM progress ORDER BY updated DESC") fun progress(): Flow<List<Progress>>
    @Query("SELECT * FROM works") suspend fun allWorks(): List<Work>
    @Query("SELECT * FROM works WHERE id = :id") suspend fun work(id: String): Work?
    @Query("UPDATE works SET circles = CASE WHEN TRIM(circles) = '' THEN :circles ELSE circles END, voices = CASE WHEN TRIM(voices) = '' THEN :voices ELSE voices END WHERE UPPER(rj) = :rj")
    suspend fun fillMissingCredits(rj: String, circles: String, voices: String)
    @Query("UPDATE works SET tags = :tags, tagsKnown = 1 WHERE UPPER(rj) = :rj AND tagsKnown = 0")
    suspend fun fillMissingTags(rj: String, tags: String)
    @Query("UPDATE works SET rj = :rj, review = CASE WHEN review = '多个 RJ 编号，需确认归属' THEN '' ELSE review END WHERE id = :id AND rj = ''")
    suspend fun resolveRj(id: String, rj: String)
    @Query("UPDATE works SET cover = :cover WHERE id = :id") suspend fun setCover(id: String, cover: String)
    @Query("UPDATE works SET favorite = :favorite WHERE id = :id") suspend fun setFavorite(id: String, favorite: Boolean)
    @Query("UPDATE works SET review = '' WHERE id = :id") suspend fun confirmWork(id: String)
    @Query("UPDATE works SET title = :title, voices = :voices, circles = :circles, edited = 1 WHERE id = :id")
    suspend fun editWork(id: String, title: String, voices: String, circles: String)
    @Query("SELECT * FROM tracks") suspend fun allTracks(): List<Track>
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: String): Track?
    @Query("SELECT * FROM assets") suspend fun allAssets(): List<Asset>
    @Query("SELECT * FROM sources") suspend fun allSources(): List<Source>
    @Query("SELECT * FROM progress WHERE workId = :id") suspend fun progressFor(id: String): Progress?
    @Upsert suspend fun putSource(source: Source)
    @Upsert suspend fun putWorks(works: List<Work>)
    @Upsert suspend fun putTracks(tracks: List<Track>)
    @Upsert suspend fun putAssets(assets: List<Asset>)
    @Upsert suspend fun putPlaylist(playlist: Playlist)
    @Upsert suspend fun putEntries(entries: List<PlaylistEntry>)
    @Upsert suspend fun putProgress(progress: Progress)
    @Query("UPDATE tracks SET available = 0 WHERE sourceUri = :source") suspend fun markUnavailable(source: String)
    @Query("DELETE FROM assets WHERE sourceUri = :source") suspend fun clearAssets(source: String)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :id") suspend fun clearEntries(id: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Query("DELETE FROM sources WHERE uri = :uri") suspend fun deleteSource(uri: String)
    @Query("DELETE FROM works WHERE id = :id") suspend fun deleteWork(id: String)
    @Query("DELETE FROM assets WHERE workId = :id") suspend fun deleteWorkAssets(id: String)
    @Query("DELETE FROM tracks WHERE id IN (:ids)") suspend fun deleteTracks(ids: List<String>)
    @Query("DELETE FROM playlist_entries WHERE workId = :workId OR trackId IN (:trackIds)")
    suspend fun deleteWorkEntries(workId: String, trackIds: List<String>)
    @Query("UPDATE playlist_entries SET trackId = :newId WHERE trackId = :oldId") suspend fun moveTrackEntries(oldId: String, newId: String)
    @Query("UPDATE progress SET trackId = :newId WHERE trackId = :oldId") suspend fun moveTrackProgress(oldId: String, newId: String)
    @Query("UPDATE playlist_entries SET workId = :newId WHERE workId = :oldId") suspend fun moveWorkEntries(oldId: String, newId: String)
    @Query("UPDATE assets SET workId = :newId WHERE workId = :oldId") suspend fun moveWorkAssets(oldId: String, newId: String)
    @Query("UPDATE tracks SET manualWorkId = :newId WHERE manualWorkId = :oldId") suspend fun moveManualWorkLinks(oldId: String, newId: String)
    @Query("DELETE FROM progress WHERE workId = :id") suspend fun deleteProgress(id: String)
}

@Database(entities = [Source::class, Work::class, Track::class, Asset::class, Playlist::class,
    PlaylistEntry::class, Progress::class, CreditWork::class, CreditCreator::class, CreditLink::class,
    CreditAttempt::class, CreditTag::class], version = 3, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3, spec = TagMigration::class)])
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun credits(): CreditDao
}

class TagMigration : androidx.room.migration.AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE credit_attempts SET retryAfter = 0, message = message || '（旧版记录，本地冷却已取消）'")
    }
}
