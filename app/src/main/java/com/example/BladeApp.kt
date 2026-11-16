package com.example

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.data.local.AppPreferences
import com.example.data.remote.SupabaseClient
import com.example.data.repository.BladeRepository
import java.io.File

class BladeApp : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var repository: BladeRepository
        private set

    @OptIn(UnstableApi::class)
    lateinit var mediaCache: SimpleCache
        private set

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(applicationContext)
        val client = SupabaseClient(preferences)
        repository = BladeRepository(preferences, client)

        val cacheDir = File(cacheDir, "blade_media_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(150L * 1024L * 1024L)
        mediaCache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(this))
    }
}
