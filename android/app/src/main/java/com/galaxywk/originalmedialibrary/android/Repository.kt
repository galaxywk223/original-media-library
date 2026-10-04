package com.galaxywk.originalmedialibrary.android

import android.content.Context
import android.media.MediaScannerConnection
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

class MediaRepository(private val context: Context, private val database: MediaDatabase, private val session: DouyinSession) {
    private val dao = database.dao()
    private val resolver = DouyinResolver(session)
    private val workManager = WorkManager.getInstance(context)
    private val client = OkHttpClient()

    fun collections(): Flow<List<CollectionEntity>> = dao.observeCollections()
    fun observeJobs(): Flow<List<JobEntity>> = dao.observeJobs()
    suspend fun jobs(): List<JobEntity> = dao.jobs()
    suspend fun resolveAndQueue(text: String): Int {
        val works = resolver.resolve(text)
        works.forEach { work ->
            val jobId = UUID.randomUUID().toString()
            dao.insertJob(JobEntity(jobId, work.sourceUrl, work.title))
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("jobId" to jobId, "sourceUrl" to work.sourceUrl, "awemeId" to work.awemeId, "title" to work.title, "author" to work.author, "assets" to work.assets.joinToString("\n") { "${it.kind}|${it.sequence}|${it.url}" }))
                .addTag("download:$jobId")
                .build()
            dao.updateJob(jobId, "queued", 0)
            workManager.enqueue(request)
        }
        return works.size
    }
    suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) {
        workManager.cancelAllWorkByTag("download:$jobId").result.get()
        workManager.cancelAllWorkByTag("audio:$jobId").result.get()
        dao.updateJob(jobId, "cancelled", 0)
    }
    suspend fun retry(jobId: String) {
        val job = dao.job(jobId) ?: error("任务不存在")
        require(job.sourceUrl.startsWith("oml-audio://")) { "该任务不是音频提取任务" }
        val ids = job.sourceUrl.removePrefix("oml-audio://").split('/')
        require(ids.size == 2) { "音频任务参数缺失" }
        val collection = dao.collection(ids[0]) ?: error("作品不存在")
        val asset = dao.asset(ids[1]) ?: error("视频不存在")
        extractAudio(collection, asset).getOrThrow()
    }
    suspend fun rename(collection: CollectionEntity, title: String) = dao.rename(collection.id, title.trim().ifBlank { collection.title })
    suspend fun extractFirstVideoAudio(collection: CollectionEntity): Result<Unit> {
        val asset = dao.assets(collection.id).firstOrNull { it.kind == "video" } ?: return Result.failure(IllegalStateException("作品中没有视频"))
        return extractAudio(collection, asset)
    }
    suspend fun extractAudio(collection: CollectionEntity, asset: AssetEntity): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(asset.kind == "video") { "只能从视频提取音频" }
            require(asset.collectionId == collection.id) { "文件不属于当前作品" }
            val jobId = UUID.randomUUID().toString()
            val request = OneTimeWorkRequestBuilder<AudioExtractionWorker>()
                .setInputData(workDataOf("collectionId" to collection.id, "assetId" to asset.id, "jobId" to jobId))
                .addTag("audio:$jobId")
                .build()
            dao.insertJob(JobEntity(jobId, "oml-audio://${collection.id}/${asset.id}", "提取音频：${collection.title}", workId = request.id.toString()))
            workManager.enqueueUniqueWork("audio-extraction", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            Unit
        }.let { result -> result.map { Unit } }
    }
}

class DownloadWorker(private val appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = appContext.applicationContext as MediaDownloaderApplication
        val dao = app.database.dao()
        val jobId = inputData.getString("jobId") ?: return@withContext Result.failure()
        runCatching {
            dao.updateJob(jobId, "downloading", 1)
            val title = inputData.getString("title") ?: "douyin"
            val collectionId = UUID.randomUUID().toString()
            val dir = File(appContext.getExternalFilesDir("media"), safeName(title)).apply { mkdirs() }
            val rows = inputData.getString("assets").orEmpty().lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
                val parts = line.split('|', limit = 3)
                val kind = parts[0]
                val sequence = parts[1].toInt()
                val url = parts[2]
                val extension = if (kind == "image") ".jpg" else ".mp4"
                val destination = File(dir, "${safeName(title)}_${String.format("%02d", index + 1)}$extension")
                val temporary = File(destination.absolutePath + ".part")
                val request = Request.Builder().url(url).header("Cookie", app.session.cookies()).build()
                app.repositoryDownload(request, temporary)
                check(temporary.renameTo(destination)) { "媒体文件写入失败" }
                MediaScannerConnection.scanFile(appContext, arrayOf(destination.absolutePath), arrayOf(if (kind == "image") "image/jpeg" else "video/mp4"), null)
                AssetEntity(UUID.randomUUID().toString(), collectionId, destination.absolutePath, destination.name, kind, if (kind == "image") "image/jpeg" else "video/mp4", destination.length(), sequence)
            }
            val type = rows.map { it.kind }.distinct().let { if (it.size == 1) it.first() else "mixed" }
            dao.insertCollection(CollectionEntity(collectionId, inputData.getString("awemeId"), inputData.getString("sourceUrl"), title, inputData.getString("author"), type))
            dao.insertAssets(rows)
            dao.updateJob(jobId, "completed", 100)
            Result.success()
        }.getOrElse { error ->
            dao.updateJob(jobId, "failed", 0, error.message ?: "下载失败")
            Result.failure(workDataOf("error" to (error.message ?: "下载失败")))
        }
    }

    private fun safeName(value: String): String = value.replace(Regex("[<>:\"/\\\\|?*\\r\\n]+"), "_").trim().ifBlank { "douyin" }.take(100)
}

private suspend fun MediaDownloaderApplication.repositoryDownload(request: Request, output: File) {
    val client = OkHttpClient()
    client.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "媒体请求失败 (${response.code})" }
        val body = response.body ?: error("媒体响应为空")
        body.byteStream().use { input -> output.outputStream().use { input.copyTo(it) } }
    }
}
