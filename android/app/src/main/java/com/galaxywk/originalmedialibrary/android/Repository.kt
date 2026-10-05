package com.galaxywk.originalmedialibrary.android

import android.net.Uri
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

object JobNotifications {
    fun foreground(context: Context, title: String, jobId: UUID): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tasks", "下载与音频任务", NotificationManager.IMPORTANCE_LOW))
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(jobId)
        val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "tasks").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("素材下载器").setContentText(title).setOngoing(true).setContentIntent(launch)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancel).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(jobId.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(jobId.hashCode(), notification)
    }
    fun progress(context: Context, title: String, id: UUID, current: Long, total: Long, percent: Int) {
        val notification = NotificationCompat.Builder(context, "tasks").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText("${current / 1024} KB / ${if (total > 0) (total / 1024).toString() else "未知"} KB")
            .setOngoing(true).setProgress(100, percent, total <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", WorkManager.getInstance(context).createCancelPendingIntent(id)).build()
        context.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
    }
}
class DownloadRepository(private val context: Context, private val database: MediaDatabase, private val resolver: DouyinResolver) {
    private val dao = database.dao()
    private val workManager = WorkManager.getInstance(context)
    private val gson = Gson()
    val jobs: Flow<List<JobEntity>> = dao.observeJobs()
    suspend fun parse(text: String): List<ParsedSource> = coroutineScope {
        val urls = LinkParser.extract(text)
        require(urls.isNotEmpty()) { "未识别到抖音链接" }
        urls.map { url ->
            val id = LinkParser.id(url)
            ParsedSource(url, id, id != null && dao.duplicate(id) != null)
        }
    }
    suspend fun queue(sources: List<ParsedSource>, force: Boolean): Int {
        var count = 0
        for (source in sources) {
            if (source.awemeId != null && dao.duplicate(source.awemeId) != null && !force) continue
            val jobId = UUID.randomUUID().toString()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("jobId" to jobId)).addTag("job:$jobId").build()
            dao.insertJob(JobEntity(jobId, source.url, source.awemeId ?: source.url, workId = request.id.toString(), force = force))
            workManager.enqueueUniqueWork("media-downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            count++
        }
        return count
    }
    suspend fun cancel(job: JobEntity) {
        withContext(Dispatchers.IO) {
            job.workId?.let { workManager.cancelWorkById(UUID.fromString(it)).result.get() }
            workManager.cancelAllWorkByTag("job:${job.id}").result.get()
            workManager.cancelAllWorkByTag("audio:${job.id}").result.get()
            dao.updateJob(job.id, "cancelled", job.progress)
        }
    }
    suspend fun retry(job: JobEntity) {
        require(job.status in listOf("failed","cancelled","interrupted")) { "当前任务不可重试" }
        if (job.kind == "audio" || job.sourceUrl.startsWith("oml-audio://")) {
            val ids = if(job.payload.isNotBlank()) gson.fromJson(job.payload, Array<String>::class.java) else job.sourceUrl.removePrefix("oml-audio://").split('/').toTypedArray()
            (context.applicationContext as MediaDownloaderApplication).audio.queue(ids[0], ids[1])
        } else queue(listOf(ParsedSource(job.sourceUrl, LinkParser.id(job.sourceUrl))), job.force)
    }
    suspend fun recover() {
        for (job in dao.jobs()) {
            if (job.status !in listOf("queued","resolving","downloading","extracting")) continue
            val info = job.workId?.let { workManager.getWorkInfoById(UUID.fromString(it)).awaitWork() }
            when {
                info == null || info.state == WorkInfo.State.FAILED -> dao.updateJob(job.id, "interrupted", job.progress, "上次任务中断，可重试")
                info.state == WorkInfo.State.CANCELLED -> dao.updateJob(job.id, "cancelled", job.progress)
                info.state == WorkInfo.State.SUCCEEDED -> dao.updateJob(job.id, "completed", 100)
            }
        }
    }
}
private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitWork(): T = withContext(Dispatchers.IO) { get() }

class DownloadWorker(private val appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = appContext.applicationContext as MediaDownloaderApplication
        val jobId = inputData.getString("jobId") ?: return Result.failure()
        val dao = app.database.dao()
        val job = dao.job(jobId) ?: return Result.failure()
        val files = mutableListOf<File>()
        val directory = File(app.storage.root, jobId).apply { mkdirs() }
        var registered = false
        try {
            setForeground(JobNotifications.foreground(app, "解析作品", id))
            dao.updateJob(jobId, "resolving", 0)
            val detail = app.resolver.resolve(job.sourceUrl)
            if (!job.force && detail.awemeId != null && dao.duplicate(detail.awemeId) != null) {
                dao.updateJob(jobId, "completed", 100); return Result.success(workDataOf("duplicate" to true))
            }
            dao.insertJob(job.copy(title = detail.title, status = "downloading", payload = Gson().toJson(detail)))
            val client = OkHttpClient.Builder().followRedirects(true).callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
            var completedBytes = 0L
            for ((index, candidate) in detail.assets.withIndex()) {
                currentCoroutineContext().ensureActive()
                val extension = if(candidate.kind == "video") "mp4" else "jpg"
                val target = File(directory, "${MediaNames.safe(detail.title)}_${(index+1).toString().padStart(2,'0')}.$extension")
                check(!target.exists()) { "媒体文件已存在，未覆盖" }
                val partial = File(target.path + ".part")
                try {
                    val request = Request.Builder().url(candidate.url).header("User-Agent", DouyinSession.USER_AGENT)
                        .header("Referer", DouyinSession.HOME).apply {
                            val cookie = app.session.cookies(candidate.url)
                            if (cookie.isNotEmpty()) header("Cookie", cookie)
                        }.build()
                    val call = client.newCall(request)
                    withContext(Dispatchers.IO) {
                        val parent = currentCoroutineContext().job
                        val completion = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            while (parent.isActive && !isStopped) delay(100)
                            call.cancel()
                        }
                        try {
                            call.execute().use { response ->
                                check(response.isSuccessful) { "媒体请求失败（${response.code}）" }
                                val body = response.body ?: error("媒体响应为空")
                                val size = body.contentLength()
                                var read = 0L
                                var lastUpdate = 0L
                                body.byteStream().use { input ->
                                    partial.outputStream().use { output ->
                                        val buffer = ByteArray(64*1024)
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val length = input.read(buffer); if (length < 0) break
                                            output.write(buffer, 0, length); read += length
                                            if (System.currentTimeMillis() - lastUpdate > 200) {
                                                val percent = ((index + if(size > 0) read.toDouble()/size else 0.0) * 100 / detail.assets.size).toInt().coerceIn(0,99)
                                                dao.updateBytes(jobId, completedBytes + read, if(size>0) completedBytes+size else 0, percent)
                                                JobNotifications.progress(app, detail.title, id, completedBytes+read, if(size>0) completedBytes+size else 0, percent)
                                                lastUpdate = System.currentTimeMillis()
                                            }
                                        }
                                    }
                                }
                                check(read > 0 && (size < 0 || read == size)) { "下载文件不完整" }
                                completedBytes += read
                            }
                        } finally { completion.cancel() }
                    }
                    check(partial.renameTo(target)) { "文件保存失败" }
                    files += target
                } finally { partial.delete() }
            }
            LibraryRepository.mutations.lock()
            try {
                currentCoroutineContext().ensureActive()
                val collection = app.library.register(detail, files, jobId)
                registered = true
                dao.updateBytes(jobId, completedBytes, completedBytes, 100)
                dao.updateJob(jobId, "completed", 100)
                runCatching { app.library.export(collection.id) }.onFailure {
                    dao.updateJob(jobId, "completed", 100, "下载成功，导出失败：${it.message}")
                }
            } finally { LibraryRepository.mutations.unlock() }
            return Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { dao.updateJob(jobId, "cancelled", 0) }
            throw cancelled
        } catch (error: Exception) {
            dao.updateJob(jobId, "failed", 0, if(error is TimeoutCancellationException) "页面解析超时，请确认登录并重试" else error.message ?: "下载失败")
            return Result.failure()
        } finally {
            if (!registered) withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
        }
    }
}
class MediaRepository(val downloads: DownloadRepository, val library: LibraryRepository, val audio: AudioExtractionRepository) {
    fun collections() = library.items.map { it.filter { item -> item.collection.trashedAt == null }.map { item -> item.collection } }
    fun observeJobs() = downloads.jobs
    suspend fun selectExport(uri: Uri) = library.storage.settings.select(uri)
    suspend fun resolveAndQueue(text: String): Int {
        val parsed = downloads.parse(text)
        return downloads.queue(parsed, false)
    }
    suspend fun extractAudio(collection: CollectionEntity, asset: AssetEntity): Result<Unit> = runCatching {
        audio.queue(collection.id, asset.id)
        Unit
    }
    suspend fun extractFirstVideoAudio(collection: CollectionEntity): Result<Unit> = runCatching {
        val video = library.detail(collection.id).assets.first { it.kind == "video" }
        audio.queue(collection.id, video.id)
        Unit
    }
    suspend fun cancelAudio(job: JobEntity) = downloads.cancel(job)
    suspend fun retryAudio(job: JobEntity) = downloads.retry(job)
    suspend fun currentJobs(): List<JobEntity> = downloads.jobs.first()
    val jobs get() = downloads.jobs
}
