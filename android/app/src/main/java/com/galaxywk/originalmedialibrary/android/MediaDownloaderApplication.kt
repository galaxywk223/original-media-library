package com.galaxywk.originalmedialibrary.android

import android.app.Application
import androidx.room.Room

class MediaDownloaderApplication : Application() {
    lateinit var database: MediaDatabase
    lateinit var session: DouyinSession
    lateinit var repository: MediaRepository

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, MediaDatabase::class.java, "library.db").fallbackToDestructiveMigration().build()
        session = DouyinSession(this)
        repository = MediaRepository(this, database, session)
    }
}
