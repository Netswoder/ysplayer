package com.ysplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Completed lookups remain usable offline, including a page with no voice credit. */
@Entity(tableName = "credit_works")
data class CreditWork(@PrimaryKey val rj: String, val sourceUrl: String, val fetchedAt: Long,
                      val voiceFieldPresent: Boolean, @ColumnInfo(defaultValue = "0") val tagsFetched: Boolean = false)

@Entity(tableName = "credit_tags", primaryKeys = ["rj", "tagId"], indices = [Index("tagId")],
    foreignKeys = [ForeignKey(entity = CreditWork::class, parentColumns = ["rj"], childColumns = ["rj"], onDelete = ForeignKey.CASCADE)])
data class CreditTag(val rj: String, val tagId: String, val name: String, val position: Int)

/** A voice entry identifies an exact credited name, not a person's real identity or aliases. */
@Entity(tableName = "credit_creators", indices = [Index("kind")])
data class CreditCreator(@PrimaryKey val id: String, val kind: String, val name: String, val sourceUrl: String)

@Entity(tableName = "credit_links", primaryKeys = ["rj", "creatorId"], indices = [Index("creatorId")],
    foreignKeys = [ForeignKey(entity = CreditWork::class, parentColumns = ["rj"], childColumns = ["rj"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CreditCreator::class, parentColumns = ["id"], childColumns = ["creatorId"], onDelete = ForeignKey.CASCADE)])
data class CreditLink(val rj: String, val creatorId: String, val position: Int)

@Entity(tableName = "credit_attempts")
data class CreditAttempt(@PrimaryKey val rj: String, val attemptedAt: Long, val retryAfter: Long, val message: String)

data class CreditStats(val works: Int = 0, val circles: Int = 0, val voices: Int = 0)
data class CatalogEntry(val id: String, val kind: String, val name: String, val workCount: Int)

@Dao
interface CreditDao {
    @Query("SELECT * FROM credit_works WHERE rj = :rj") suspend fun work(rj: String): CreditWork?
    @Query("SELECT * FROM credit_tags WHERE rj = :rj ORDER BY position") suspend fun tags(rj: String): List<CreditTag>
    @Query("DELETE FROM credit_tags WHERE rj = :rj") suspend fun clearTags(rj: String)
    @Upsert suspend fun putTags(tags: List<CreditTag>)
    @Query("SELECT c.* FROM credit_creators c INNER JOIN credit_links l ON c.id = l.creatorId WHERE l.rj = :rj ORDER BY l.position")
    suspend fun creators(rj: String): List<CreditCreator>
    @Query("SELECT * FROM credit_attempts WHERE rj = :rj") suspend fun attempt(rj: String): CreditAttempt?
    @Query("SELECT * FROM credit_attempts ORDER BY attemptedAt DESC") fun attempts(): Flow<List<CreditAttempt>>
    @Query("SELECT (SELECT COUNT(*) FROM credit_works) AS works, (SELECT COUNT(*) FROM credit_creators WHERE kind = 'circle') AS circles, (SELECT COUNT(*) FROM credit_creators WHERE kind = 'voice') AS voices")
    fun stats(): Flow<CreditStats>
    @Query("SELECT c.id, c.kind, c.name, COUNT(l.rj) AS workCount FROM credit_creators c LEFT JOIN credit_links l ON l.creatorId = c.id GROUP BY c.id ORDER BY c.name")
    fun catalog(): Flow<List<CatalogEntry>>
    @Upsert suspend fun putWork(work: CreditWork)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putCreators(creators: List<CreditCreator>)
    @Upsert suspend fun putLinks(links: List<CreditLink>)
    @Upsert suspend fun putAttempt(attempt: CreditAttempt)
    @Query("DELETE FROM credit_attempts WHERE rj = :rj") suspend fun clearAttempt(rj: String)
}
