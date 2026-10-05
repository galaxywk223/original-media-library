package com.galaxywk.originalmedialibrary.android

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media_collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val awemeId: String?, val sourceUrl: String?, val title: String, val author: String?,
    val mediaType: String, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "NULL") val trashedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val imported: Boolean = false,
)
@Entity(tableName = "media_assets", foreignKeys = [ForeignKey(
    entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("collectionId"), Index(value = ["path"], unique = true)])
data class AssetEntity(
    @PrimaryKey val id: String, val collectionId: String, val path: String, val filename: String,
    val kind: String, val mimeType: String, val size: Long, val sequence: Int,
    @ColumnInfo(defaultValue = "NULL") val exportedUri: String? = null,
    @ColumnInfo(defaultValue = "NULL") val thumbnail: String? = null,
    @ColumnInfo(defaultValue = "NULL") val width: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val height: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val durationMs: Long? = null,
)
@Entity(tableName = "download_jobs")
data class JobEntity(
    @PrimaryKey val id: String, val sourceUrl: String, val title: String, val status: String = "queued",
    val progress: Int = 0, val error: String? = null, val workId: String? = null, val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'download'") val kind: String = "download",
    @ColumnInfo(defaultValue = "''") val payload: String = "",
    @ColumnInfo(defaultValue = "0") val force: Boolean = false,
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val totalBytes: Long = 0,
)
data class CollectionWithAssets(@Embedded val collection: CollectionEntity,
    @Relation(parentColumn = "id", entityColumn = "collectionId") val assets: List<AssetEntity>)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_collections WHERE trashedAt IS NULL ORDER BY updatedAt DESC") fun observeCollections(): Flow<List<CollectionEntity>>
    @Query("SELECT * FROM media_collections WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC") fun observeTrash(): Flow<List<CollectionEntity>>
    @Transaction @Query("SELECT * FROM media_collections ORDER BY updatedAt DESC") fun observeLibrary(): Flow<List<CollectionWithAssets>>
    @Transaction @Query("SELECT * FROM media_collections") suspend fun library(): List<CollectionWithAssets>
    @Query("SELECT * FROM media_collections WHERE id = :id") suspend fun collection(id: String): CollectionEntity?
    @Query("SELECT * FROM media_collections WHERE awemeId = :id AND trashedAt IS NULL LIMIT 1") suspend fun duplicate(id: String): CollectionEntity?
    @Query("SELECT * FROM media_assets WHERE collectionId = :id ORDER BY sequence") suspend fun assets(id: String): List<AssetEntity>
    @Query("SELECT * FROM media_assets WHERE id = :id") suspend fun asset(id: String): AssetEntity?
    @Query("SELECT * FROM media_assets WHERE path = :path") suspend fun assetByPath(path: String): AssetEntity?
    @Query("SELECT * FROM download_jobs WHERE id = :id") suspend fun job(id: String): JobEntity?
    @Query("UPDATE media_collections SET mediaType = :type, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMediaType(id: String, type: String, updatedAt: Long = System.currentTimeMillis())
    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC") fun observeJobs(): Flow<List<JobEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCollection(value: CollectionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAssets(values: List<AssetEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertJob(value: JobEntity)
    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC") suspend fun jobs(): List<JobEntity>
    @Query("UPDATE download_jobs SET status = :status, progress = :progress, error = :error WHERE id = :id")
    suspend fun updateJob(id: String, status: String, progress: Int, error: String? = null)
    @Query("UPDATE download_jobs SET downloadedBytes = :current, totalBytes = :total, progress = :progress WHERE id = :id")
    suspend fun updateBytes(id: String, current: Long, total: Long, progress: Int)
    @Query("UPDATE media_collections SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long = System.currentTimeMillis())
    @Query("UPDATE media_collections SET trashedAt = :time WHERE id = :id") suspend fun trash(id: String, time: Long?)
    @Query("DELETE FROM media_assets WHERE id = :id") suspend fun deleteAsset(id: String)
    @Delete suspend fun deleteCollection(value: CollectionEntity)
}
@Database(entities = [CollectionEntity::class, AssetEntity::class, JobEntity::class], version = 2, exportSchema = false)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun dao(): MediaDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_collections ADD COLUMN trashedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE media_collections ADD COLUMN imported INTEGER NOT NULL DEFAULT 0")
                listOf("exportedUri TEXT", "thumbnail TEXT", "width INTEGER", "height INTEGER", "durationMs INTEGER").forEach {
                    db.execSQL("ALTER TABLE media_assets ADD COLUMN $it DEFAULT NULL")
                }
                listOf("kind TEXT NOT NULL DEFAULT 'download'", "payload TEXT NOT NULL DEFAULT ''", "force INTEGER NOT NULL DEFAULT 0",
                    "downloadedBytes INTEGER NOT NULL DEFAULT 0", "totalBytes INTEGER NOT NULL DEFAULT 0").forEach {
                    db.execSQL("ALTER TABLE download_jobs ADD COLUMN $it")
                }
            }
        }
    }
}