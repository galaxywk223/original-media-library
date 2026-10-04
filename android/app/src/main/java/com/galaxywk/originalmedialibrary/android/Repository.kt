package com.galaxywk.originalmedialibrary.android

import android.content.Context
import android.media.MediaScannerConnection
import androidx.work.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
    fun cancel(jobId: String) { workManager.cancelAllWorkByTag("download:$jobId") }
    fun retry(jobId: String) { workManager.cancelAllWorkByTag("download:$jobId") }
    suspend fun rename(collection: CollectionEntity, title: String) = dao.rename(collection.id, title.trim().ifBlank { collection.title })
    suspend fun extractFirstVideoAudio(collection: CollectionEntity): Result<Unit> {
        val asset = dao.assets(collection.id).firstOrNull { it.kind == "video" } ?: return Result.failure(IllegalStateException("作品中没有视频"))
        return extractAudio(collection, asset)
    }
    suspend fun extractAudio(collection: CollectionEntity, asset: AssetEntity): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(asset.kind == "video") { "只能从视频提取音频" }
            val input = File(asset.path)
            check(input.exists()) { "视频文件不存在" }
            val output = File(input.parentFile, input.nameWithoutExtension + ".m4a")
            if (output.exists()) return@runCatching
            val temporary = File(input.parentFile, input.nameWithoutExtension + ".part.m4a")
            temporary.delete()
            extractAudioTrack(input, temporary)
            check(temporary.exists()) { "音频输出文件不存在" }
            check(temporary.renameTo(output)) { "音频文件写入失败" }
            MediaScannerConnection.scanFile(context, arrayOf(output.absolutePath), arrayOf("audio/mp4"), null)
            val audio = AssetEntity(UUID.randomUUID().toString(), collection.id, output.absolutePath, output.name, "audio", "audio/mp4", output.length(), asset.sequence + 1)
            dao.insertAssets(listOf(audio))
        }
    }

    private fun extractAudioTrack(input: File, output: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("视频中没有音频轨道")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTrack = muxer.addTrack(format)
        muxer.start()
        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        try {
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
                buffer.clear()
            }
        } finally {
            muxer.stop(); muxer.release(); extractor.release()
        }
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
