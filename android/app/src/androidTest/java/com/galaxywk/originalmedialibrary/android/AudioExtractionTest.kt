package com.galaxywk.originalmedialibrary.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioExtractionTest {
    @Test fun workerProduces192kbpsMp3AndRegistersItOnce() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MediaDownloaderApplication
        val id = UUID.randomUUID().toString()
        val directory = File(app.getExternalFilesDir("media"), "test-$id 中文 #").apply { mkdirs() }
        val input = File(directory, "测试视频.mp4")
        val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=black:s=64x64:d=1", "-f", "lavfi", "-i", "sine=frequency=1000:duration=1",
            "-c:v", "mpeg4", "-c:a", "aac", "-shortest", input.absolutePath))
        assertTrue(session.allLogsAsString, ReturnCode.isSuccess(session.returnCode))
        val collection = CollectionEntity(id, null, null, "测试", null, "video")
        val asset = AssetEntity(UUID.randomUUID().toString(), id, input.absolutePath, input.name, "video", "video/mp4", input.length(), 1)
        val jobId = UUID.randomUUID().toString()
        val dao = app.database.dao()
        try {
            dao.insertCollection(collection)
            dao.insertAssets(listOf(asset))
            dao.insertJob(JobEntity(jobId, "", "音频测试"))
            val worker = TestListenableWorkerBuilder<AudioExtractionWorker>(app).setInputData(
                workDataOf("collectionId" to id, "assetId" to asset.id, "jobId" to jobId)).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals("completed", dao.job(jobId)?.status)
            val audio = dao.assets(id).single { it.kind == "audio" }
            assertEquals("audio/mpeg", audio.mimeType)
            assertEquals("mixed", dao.collection(id)?.mediaType)
            assertFalse(Mp3Arguments.temporary(File(audio.path)).exists())
            val probe = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_streams", "-of", "json", audio.path))
            assertTrue(ReturnCode.isSuccess(probe.returnCode))
            val stream = JSONObject(probe.output).getJSONArray("streams").getJSONObject(0)
            assertEquals("mp3", stream.getString("codec_name"))
            assertEquals(192000, stream.getInt("bit_rate"))
            assertEquals(audio.id, AudioExtractionService(app, app.database).extract(id, asset.id).id)
            assertEquals(2, dao.assets(id).size)
        } finally {
            dao.deleteCollection(collection)
            directory.deleteRecursively()
        }
    }

    @Test fun invalidVideoCleansPartialAndPersistsFailure() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MediaDownloaderApplication
        val id = UUID.randomUUID().toString()
        val dir = File(app.getExternalFilesDir("media"), "invalid-$id").apply { mkdirs() }
        val input = File(dir, "invalid.mp4").apply { writeText("invalid video") }
        val collection = CollectionEntity(id, null, null, "失败测试", null, "video")
        val source = AssetEntity(UUID.randomUUID().toString(), id, input.absolutePath, input.name, "video", "video/mp4", input.length(), 1)
        val jobId = UUID.randomUUID().toString()
        val dao = app.database.dao()
        try {
            dao.insertCollection(collection); dao.insertAssets(listOf(source)); dao.insertJob(JobEntity(jobId, "", "失败测试"))
            val worker = TestListenableWorkerBuilder<AudioExtractionWorker>(app).setInputData(
                workDataOf("collectionId" to id, "assetId" to source.id, "jobId" to jobId)).build()
            assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
            assertEquals("failed", dao.job(jobId)?.status)
            assertFalse(File(dir, "invalid.part.mp3").exists())
            assertFalse(File(dir, "invalid.mp3").exists())
            assertEquals(1, dao.assets(id).size)
        } finally { dao.deleteCollection(collection); dir.deleteRecursively() }
    }
}
