package com.ysplayer

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.ysplayer.data.*
import com.ysplayer.library.LibraryRepository
import com.ysplayer.playback.PlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    val repository = (app as YsApplication).repository
    private val ysApp = app as YsApplication
    val metadata = ysApp.metadata
    private val dao = repository.dao
    val works = dao.works().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tracks = dao.tracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sources = dao.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = dao.entries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val progress = dao.progress().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val creditStats = ysApp.database.credits().stats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CreditStats())
    val creditCatalog = ysApp.database.credits().catalog().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val creditAttempts = ysApp.database.credits().attempts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notice = MutableStateFlow("")
    val connected = MutableStateFlow(false)
    private val preferences = app.getSharedPreferences("settings", 0)
    val gridLibrary = MutableStateFlow(preferences.getBoolean("gridLibrary", false))
    val automaticMetadata = MutableStateFlow(ysApp.metadataAutomatic())
    private var scanJob: Job? = null
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null
    init {
        ysApp.observeMetadata()
        future.addListener({ runCatching { future.get() }.onSuccess { controller = it; connected.value = true }
            .onFailure { notice.value = "播放服务连接失败：${it.message}" } }, ContextCompat.getMainExecutor(app))
    }
    fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice.value = e.message ?: "操作失败" }
    } }
    fun scan(source: Source? = null, uri: Uri? = null, coversOnly: Boolean = false) {
        if (scanJob?.isActive == true) { notice.value = "已有扫描正在进行"; return }
        scanJob = viewModelScope.launch {
            try {
                when {
                    uri != null -> repository.addSource(uri, coversOnly)
                    source != null -> repository.scan(source)
                    else -> dao.allSources().forEach { repository.scan(it) }
                }
                reconcileQueue(dao.allTracks().filter { it.available }.map { it.id }.toSet())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice.value = e.message ?: "扫描失败" }
        }
    }
    fun cancelScan() { scanJob?.cancel() }
    fun saveWork(work: Work) = action { dao.editWork(work.id, work.title, work.voices, work.circles) }
    fun favorite(work: Work) = action { dao.setFavorite(work.id, !work.favorite) }
    fun resetCover(work: Work) = action { dao.setCover(work.id, "") }
    fun confirm(work: Work) = action { dao.confirmWork(work.id) }
    fun setGridLibrary(enabled: Boolean) {
        gridLibrary.value = enabled; preferences.edit().putBoolean("gridLibrary", enabled).apply()
    }
    fun setAutomaticMetadata(enabled: Boolean) { automaticMetadata.value = enabled; ysApp.setMetadataAutomatic(enabled) }
    fun syncMetadata() = ysApp.requestMetadata()
    fun cancelMetadata() = ysApp.cancelMetadata()
    fun removeWork(work: Work, onRemoved: () -> Unit) = action {
        val removed = repository.removeWork(work.id)
        controller?.let { current ->
            for (i in current.mediaItemCount - 1 downTo 0) if (current.getMediaItemAt(i).mediaId in removed) current.removeMediaItem(i)
        }
        notice.value = "已从作品库移除，源文件保留"
        onRemoved()
    }
    private fun reconcileQueue(available: Set<String>) {
        controller?.let { current ->
            for (i in current.mediaItemCount - 1 downTo 0) if (current.getMediaItemAt(i).mediaId !in available) current.removeMediaItem(i)
        }
    }
    fun cover(work: Work, uri: Uri) = action { repository.saveCover(work, uri) }
    fun saveTrack(track: Track) = action { dao.putTracks(listOf(track)); refreshPlayback() }
    fun createPlaylist(name: String) = action { dao.putPlaylist(Playlist(UUID.randomUUID().toString(), name)) }
    fun renamePlaylist(playlist: Playlist) = action { dao.putPlaylist(playlist) }
    fun addToPlaylist(playlist: Playlist, workId: String, trackId: String = "") = action {
        val current = entries.value.filter { it.playlistId == playlist.id }
        if (current.any { it.workId == workId && it.trackId == trackId }) { notice.value = "该内容已经在列表中"; return@action }
        dao.putEntries(listOf(PlaylistEntry(UUID.randomUUID().toString(), playlist.id, workId, trackId,
            (current.maxOfOrNull { it.position } ?: -1) + 1)))
        notice.value = "已加入 ${playlist.name}"
    }
    fun moveEntry(playlistId: String, id: String, direction: Int) = action {
        val list = entries.value.filter { it.playlistId == playlistId }.toMutableList()
        val index = list.indexOfFirst { it.id == id }; val dest = index + direction
        if (index >= 0 && dest in list.indices) { java.util.Collections.swap(list, index, dest); repository.saveEntries(playlistId, list) }
    }
    fun removeEntry(entry: PlaylistEntry) = action {
        repository.saveEntries(entry.playlistId, entries.value.filter { it.playlistId == entry.playlistId && it.id != entry.id })
    }
    fun moveTrack(track: Track, direction: Int) = action {
        val list = LibraryRepository.sorted(tracks.value.filter { it.effectiveWorkId == track.effectiveWorkId && it.variant == track.variant }).toMutableList()
        val index = list.indexOfFirst { it.id == track.id }; val dest = index + direction
        if (index >= 0 && dest in list.indices) {
            java.util.Collections.swap(list, index, dest)
            dao.putTracks(list.mapIndexed { i, t -> t.copy(sortOrder = i) })
        }
    }
    fun variants(workId: String) = tracks.value.filter { it.effectiveWorkId == workId && it.available && !it.resource }
        .map { it.variant }.distinct().sorted()
    fun selectedVariant(workId: String): String {
        val variants = variants(workId)
        return preferences.getString("variant:$workId", null)?.takeIf { it in variants }
            ?: variants.firstOrNull { it == "MP3" } ?: variants.firstOrNull().orEmpty()
    }
    fun selectVariant(workId: String, variant: String) { preferences.edit().putString("variant:$workId", variant).apply() }
    fun workTracks(workId: String, variant: String = selectedVariant(workId), bonus: Boolean = false, resources: Boolean = false): List<Track> =
        LibraryRepository.sorted(tracks.value.filter { it.effectiveWorkId == workId && it.available &&
            (it.variant == variant || variant.isEmpty()) && (resources || !it.resource) && (bonus || it.section != "特典") && it.section != "试听" })
    fun playWork(work: Work, variant: String = selectedVariant(work.id), bonus: Boolean = false,
                 resume: Boolean = true, trackId: String = "", resources: Boolean = false) = action {
        val regular = workTracks(work.id, variant, bonus, resources)
        val explicitlySelected = tracks.value.find { it.id == trackId && it.available }
        val queue = if (explicitlySelected != null && regular.none { it.id == trackId }) listOf(explicitlySelected) else regular
        val saved = if (resume) dao.progressFor(work.id) else null
        val target = trackId.ifEmpty { saved?.trackId.orEmpty() }
        val index = queue.indexOfFirst { it.id == target }.coerceAtLeast(0)
        play(queue, index, if (trackId.isEmpty() && saved != null && saved.trackId == queue.getOrNull(index)?.id) saved.position else 0)
    }
    fun playPlaylist(id: String) {
        val queue = entries.value.filter { it.playlistId == id }.flatMap { entry ->
            if (entry.trackId.isEmpty()) workTracks(entry.workId)
            else tracks.value.filter { it.id == entry.trackId && it.available }
        }
        play(queue)
    }
    private fun play(queue: List<Track>, index: Int = 0, position: Long = 0) {
        if (queue.isEmpty()) { notice.value = "没有可播放的轨道，请检查版本、资源筛选或目录授权"; return }
        control {
            val items = queue.map { track ->
                val work = works.value.find { it.id == track.effectiveWorkId }
                MediaItem.Builder().setMediaId(track.id).setUri(track.uri).setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(track.name.substringBeforeLast('.')).setAlbumTitle(work?.title ?: "作品")
                    .setArtist(work?.voices.orEmpty()).setArtworkUri((work?.cover?.ifEmpty { work.autoCover })?.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    .setExtras(Bundle().apply { putString("workId", track.effectiveWorkId) }).build()).build()
            }
            setMediaItems(items, index, position); prepare(); play()
        }
    }
    fun control(command: MediaController.() -> Unit) {
        val current = controller
        if (current == null) notice.value = "播放服务正在连接，请稍后重试" else runCatching { current.command() }.onFailure { notice.value = it.message.orEmpty() }
    }
    fun seekToSubtitle(trackId: String, position: Long) = control {
        // Ignore an old page's tap if playback changed tracks during the gesture.
        if (currentMediaItem?.mediaId == trackId) seekTo(position.coerceAtLeast(0))
    }
    fun settings(block: android.content.SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(block).apply(); refreshPlayback()
    }
    fun refreshPlayback(args: Bundle = Bundle()) = control { sendCustomCommand(SessionCommand("ys.settings", Bundle.EMPTY), args) }
    override fun onCleared() { MediaController.releaseFuture(future); super.onCleared() }
}
