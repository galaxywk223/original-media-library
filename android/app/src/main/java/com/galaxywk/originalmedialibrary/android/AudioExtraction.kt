package com.galaxywk.originalmedialibrary.android

import android.content.Context
import android.media.MediaScannerConnection
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import com.google.gson.Gson
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Mp3Arguments {
    fun temporary(output: File) = File(output.parentFile, "${output.nameWithoutExtension}.part.mp3")
    fun command(input: File, temporary: File): Array<String> = arrayOf(
        "-hide_banner", "-loglevel", "error", "-y", "-i", input.absolutePath,
        "-map", "0:a:0", "-vn", "-codec:a", "libmp3lame", "-b:a", "192k", "-f", "mp3", temporary.absolutePath,
    )
}

object AndroidAudioEngine {
    fun version(): String? = runCatching { FFmpegKitConfig.getFFmpegVersion() }.getOrNull()

    suspend fun convert(input: File, temporary: File) = suspendCancellableCoroutine<Unit> { continuation ->
        val session = FFmpegKit.executeWithArgumentsAsync(Mp3Arguments.command(input, temporary)) { result ->
            if (!continuation.isActive) {
                temporary.delete()
            } else if (ReturnCode.isSuccess(result.returnCode)) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(IllegalStateException(
                    result.allLogsAsString?.takeLast(1200)?.ifBlank { "视频无音轨或音频提取失败" }
                        ?: "音频提取失败",
                ))
            }
        }
        continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }
}

class AudioExtractionService(private val context: Context, private val database: MediaDatabase) {
    companion object { private val lock get() = LibraryRepository.mutations }

    suspend fun extract(collectionId: String, assetId: String): AssetEntity = lock.withLock {
        withContext(Dispatchers.IO) {
            val dao = database.dao()
            val collection = dao.collection(collectionId) ?: error("作品不存在")
            check(collection.trashedAt == null) { "请先恢复回收站作品" }
            val source = dao.asset(assetId) ?: error("视频文件不存在")
            check(source.collectionId == collectionId && source.kind == "video") { "只能从当前作品的视频提取音频" }
            val root = (context.applicationContext as MediaDownloaderApplication).storage.root
            val input = File(source.path).canonicalFile
            check(input.toPath().startsWith(root.toPath()) && input.isFile) { "视频不在媒体目录内或已不存在" }
            val output = File(input.parentFile, "${input.nameWithoutExtension}.mp3")
            val existing = dao.assets(collectionId).firstOrNull { File(it.path).canonicalFile == output }
            if (existing != null && existing.kind == "audio" && output.isFile) return@withContext existing
            check(!output.exists()) { "音频文件已存在，未覆盖" }
            val temporary = Mp3Arguments.temporary(output)
            temporary.delete()
            var created = false
            try {
                AndroidAudioEngine.convert(input, temporary)
                check(temporary.isFile && temporary.length() > 0) { "音频输出为空" }
                check(temporary.renameTo(output)) { "音频文件写入失败" }
                created = true
                val assets = dao.assets(collectionId)
                val audio = AssetEntity(existing?.id ?: UUID.randomUUID().toString(), collectionId,
                    output.absolutePath, output.name, "audio", "audio/mpeg", output.length(),
                    existing?.sequence ?: ((assets.maxOfOrNull { it.sequence } ?: 0) + 1))
                database.withTransaction {
                    dao.insertAssets(listOf(audio))
                    dao.updateMediaType(collection.id, "mixed")
                }
                audio
            } catch (error: Throwable) {
                if (created) output.delete()
                throw error
            } finally {
                temporary.delete()
            }
        }
    }
}

class AudioExtractionRepository(private val context: Context, private val database: MediaDatabase) {
    suspend fun queue(collectionId: String, assetId: String) {
        val dao = database.dao()
        val source = dao.asset(assetId) ?: error("视频不存在")
        require(source.collectionId == collectionId && source.kind == "video") { "只能从当前作品的视频提取音频" }
        val collection = dao.collection(collectionId) ?: error("作品不存在")
        check(collection.trashedAt == null) { "请先恢复作品" }
        val jobId = UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<AudioExtractionWorker>().setInputData(workDataOf("jobId" to jobId))
            .addTag("job:$jobId").build()
        dao.insertJob(JobEntity(jobId, "oml-audio://$collectionId/$assetId", "提取音频：${collection.title}",
            workId = request.id.toString(), kind = "audio", payload = Gson().toJson(arrayOf(collectionId, assetId))))
        WorkManager.getInstance(context).enqueueUniqueWork("audio-extraction", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class AudioExtractionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MediaDownloaderApplication
        val dao = app.database.dao()
        val jobId = inputData.getString("jobId") ?: return Result.failure()
        val job = dao.job(jobId) ?: return Result.failure()
        val values = if (job.payload.isNotBlank()) Gson().fromJson(job.payload, Array<String>::class.java) else null
        val collectionId = values?.getOrNull(0) ?: inputData.getString("collectionId") ?: return Result.failure()
        val assetId = values?.getOrNull(1) ?: inputData.getString("assetId") ?: return Result.failure()
        try {
            setForeground(JobNotifications.foreground(app, job.title, id))
            dao.updateJob(jobId, "extracting", 1)
            AudioExtractionService(app, app.database).extract(collectionId, assetId)
            dao.updateJob(jobId, "completed", 100)
            runCatching { app.library.export(collectionId) }.onFailure {
                dao.updateJob(jobId, "completed", 100, "提取成功，导出失败：${it.message}")
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { dao.updateJob(jobId, "cancelled", 0) }
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: "音频提取失败"
            dao.updateJob(jobId, "failed", 0, message)
            return Result.failure(workDataOf("error" to message))
        }
    }
}
