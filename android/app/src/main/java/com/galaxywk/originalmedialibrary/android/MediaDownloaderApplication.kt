package com.galaxywk.originalmedialibrary.android

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*

class MediaDownloaderApplication : Application() {
    lateinit var database: MediaDatabase
    lateinit var session: DouyinSession
    lateinit var repository: MediaRepository
    lateinit var resolver: DouyinResolver
    lateinit var settings: SettingsRepository
    lateinit var storage: StorageRepository
    lateinit var downloads: DownloadRepository
    lateinit var library: LibraryRepository
    lateinit var audio: AudioExtractionRepository

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, MediaDatabase::class.java, "library.db").addMigrations(MediaDatabase.MIGRATION_1_2).build()
        session = DouyinSession(this)
        settings = SettingsRepository(this)
        storage = StorageRepository(this, settings)
        resolver = DouyinResolver(session)
        downloads = DownloadRepository(this, database, resolver)
        library = LibraryRepository(this, database, storage)
        audio = AudioExtractionRepository(this, database)
        repository = MediaRepository(downloads, library, audio)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            downloads.recover()
            library.scan()
        }
    }
}
