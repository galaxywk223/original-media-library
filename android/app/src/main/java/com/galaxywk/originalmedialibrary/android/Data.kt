package com.galaxywk.originalmedialibrary.android

import androidx.room.*

@Entity(tableName = "media_collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val awemeId: String?, val sourceUrl: String?, val title: String, val author: String?,
    val mediaType: String, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
)

@Entity(tableName = "media_assets", foreignKeys = [ForeignKey(
    entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("collectionId"), Index(value = ["path"], unique = true)])
data class AssetEntity(
    @PrimaryKey val id: String, val collectionId: String, val path: String, val filename: String,
    val kind: String, val mimeType: String, val size: Long, val sequence: Int,
)

@Entity(tableName = "download_jobs")
data class JobEntity(
    @PrimaryKey val id: String, val sourceUrl: String, val title: String, val status: String = "queued",
    val progress: Int = 0, val error: String? = null, val workId: String? = null, val createdAt: Long = System.currentTimeMillis(),
)

data class CollectionWithAssets(@Embedded val collection: CollectionEntity, @Relation(parentColumn = "id", entityColumn = "collectionId") val assets: List<AssetEntity>)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_collections ORDER BY updatedAt DESC")
    fun observeCollections(): kotlinx.coroutines.flow.Flow<List<CollectionEntity>>
    @Query("SELECT * FROM media_collections WHERE id = :id")
    suspend fun collection(id: String): CollectionEntity?
    @Query("SELECT * FROM media_assets WHERE collectionId = :id ORDER BY sequence")
    suspend fun assets(id: String): List<AssetEntity>
    @Query("SELECT * FROM media_assets WHERE id = :id") suspend fun asset(id: String): AssetEntity?
    @Query("SELECT * FROM download_jobs WHERE id = :id") suspend fun job(id: String): JobEntity?
    @Query("UPDATE media_collections SET mediaType = :type, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMediaType(id: String, type: String, updatedAt: Long = System.currentTimeMillis())
    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC")
    fun observeJobs(): kotlinx.coroutines.flow.Flow<List<JobEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCollection(value: CollectionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAssets(values: List<AssetEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertJob(value: JobEntity)
    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC") suspend fun jobs(): List<JobEntity>
    @Query("UPDATE download_jobs SET status = :status, progress = :progress, error = :error WHERE id = :id") suspend fun updateJob(id: String, status: String, progress: Int, error: String? = null)
    @Query("UPDATE media_collections SET title = :title, updatedAt = :updatedAt WHERE id = :id") suspend fun rename(id: String, title: String, updatedAt: Long = System.currentTimeMillis())
    @Delete suspend fun deleteCollection(value: CollectionEntity)
}

@Database(entities = [CollectionEntity::class, AssetEntity::class, JobEntity::class], version = 1, exportSchema = false)
abstract class MediaDatabase : RoomDatabase() { abstract fun dao(): MediaDao }
