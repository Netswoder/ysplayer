package com.ysplayer

import android.app.Application
import androidx.room.Room
import com.ysplayer.data.LibraryDatabase
import com.ysplayer.library.LibraryRepository
import com.ysplayer.metadata.DlsiteClient
import com.ysplayer.metadata.MetadataUpdater
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import coil.disk.DiskCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import java.io.File

class YsApplication : Application(), ImageLoaderFactory {
    val database by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").build() }
    val repository by lazy { LibraryRepository(this, database) }
    val metadata by lazy { MetadataUpdater(database, DlsiteClient(getSharedPreferences("dlsite-network", 0))) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var metadataJob: Job? = null
    private var observing = false
    @Volatile private var paused = false
    private val requests = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch {
            for (request in requests) {
                val job = synchronized(this@YsApplication) {
                    if (paused) null else launch(start = CoroutineStart.LAZY) { metadata.syncMissing() }.also { metadataJob = it }
                }
                job?.start()
                job?.join()
            }
        }
    }
    @OptIn(FlowPreview::class)
    fun observeMetadata() {
        if (observing) return
        observing = true
        scope.launch {
            repository.repairPrimaryRj()
            var previous = emptySet<String>()
            database.library().works().map { works -> works.filter { it.circles.isBlank() || it.voices.isBlank() || !it.tagsKnown }
                .map { it.rj }.filter { it.isNotBlank() }.distinct().sorted() }.distinctUntilChanged().debounce(600).collect {
                val added = it.toSet() - previous
                previous = it.toSet()
                if (added.isNotEmpty() && !paused && metadataAutomatic()) requestMetadata()
            }
        }
    }
    fun metadataAutomatic() = getSharedPreferences("settings", 0).getBoolean("metadataAutomatic", true)
    fun setMetadataAutomatic(enabled: Boolean) {
        getSharedPreferences("settings", 0).edit().putBoolean("metadataAutomatic", enabled).apply()
        if (enabled) requestMetadata() else cancelMetadata()
    }
    @Synchronized fun requestMetadata() {
        paused = false
        requests.trySend(Unit)
    }
    @Synchronized fun cancelMetadata() { paused = true; metadataJob?.cancel() }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.18).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "image_cache")).maxSizeBytes(120L * 1024 * 1024).build() }
        .crossfade(false).build()
}
