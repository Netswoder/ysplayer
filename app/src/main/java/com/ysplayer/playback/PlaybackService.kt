package com.ysplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ysplayer.MainActivity
import com.ysplayer.YsApplication
import com.ysplayer.data.Progress
import com.ysplayer.library.LibraryRules
import com.ysplayer.subtitle.SubtitleCue
import com.ysplayer.subtitle.SubtitleParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PlaybackSnapshot(
    val trackId: String = "", val workId: String = "", val title: String = "尚未播放",
    val workTitle: String = "选择一部作品，开始收听", val position: Long = 0, val duration: Long = 0,
    val playing: Boolean = false, val subtitle: String = "", val subtitleStatus: String = "",
    val error: String = "", val queue: List<String> = emptyList(), val index: Int = 0,
    val speed: Float = 1f, val repeat: Int = Player.REPEAT_MODE_OFF, val sleepMinutes: Int = 0,
    val subtitleCues: List<SubtitleCue> = emptyList(), val subtitleOffset: Long = 0
)
object PlaybackBus { val state = MutableStateFlow(PlaybackSnapshot()) }

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as YsApplication).repository
    private val prefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private lateinit var overlay: LyricsOverlay
    private var cues = emptyList<SubtitleCue>()
    private var offset = 0L
    private var subtitleJob: Job? = null
    private var subtitleStatus = ""
    private var errorText = ""
    private var sleepAt = 0L
    private var stopAtTrackEnd = false
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                stopAtTrackEnd = false
                player.pauseAtEndOfMediaItems = false
            }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) { stopAtTrackEnd = false; player.pauseAtEndOfMediaItems = false }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (stopAtTrackEnd && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                player.pause(); stopAtTrackEnd = false
            }
            loadSubtitle()
            saveQueue()
            publish()
        }
        override fun onPlayerError(error: PlaybackException) { errorText = "播放失败：${error.errorCodeName}。请检查文件或目录授权。"; publish() }
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_POSITION_DISCONTINUITY)) saveQueue()
            publish()
        }
    }
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        overlay = LyricsOverlay(this)
        session = MediaSession.Builder(this, player).setSessionActivity(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (!controller.isTrusted && controller.packageName != packageName) return MediaSession.ConnectionResult.reject()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand("ys.settings", Bundle.EMPTY)).build()).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (controller.packageName == packageName && customCommand.customAction == "ys.settings") {
                        val minutes = args.getInt("sleep", -1)
                        if (minutes >= 0) { sleepAt = if (minutes == 0) 0 else System.currentTimeMillis() + minutes * 60_000L; stopAtTrackEnd = false; player.pauseAtEndOfMediaItems = false }
                        if (args.containsKey("endTrack")) { stopAtTrackEnd = args.getBoolean("endTrack"); player.pauseAtEndOfMediaItems = stopAtTrackEnd; sleepAt = 0 }
                        loadSubtitle(); overlay.refreshSettings(); publish()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
            }).build()
        restoreQueue()
        player.addListener(listener)
        loadSubtitle()
        scope.launch {
            var lastSaved = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { player.pause(); sleepAt = 0 }
                publish()
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastSaved >= 5000 && player.currentMediaItem != null) { saveQueue(); lastSaved = now }
                delay(if (getSystemService(PowerManager::class.java).isInteractive) 250 else 1000)
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    private fun loadSubtitle() {
        subtitleJob?.cancel(); cues = emptyList(); offset = 0; errorText = ""; subtitleStatus = ""
        val id = player.currentMediaItem?.mediaId ?: return
        subtitleJob = scope.launch {
            subtitleStatus = "正在查找字幕"
            try {
                val result = withContext(Dispatchers.IO) {
                    val track = repository.dao.allTracks().find { it.id == id } ?: return@withContext Triple(emptyList<SubtitleCue>(), 0L, "未找到轨道")
                    val candidates = repository.dao.allAssets().filter {
                        it.type == "subtitle" && it.workId == track.effectiveWorkId &&
                            LibraryRules.subtitleKey(it.name) == LibraryRules.trackKey(track.name) &&
                            LibraryRules.extension(LibraryRules.stem(it.name)).let { hint ->
                                hint !in LibraryRules.audioExtensions || hint == LibraryRules.extension(track.name)
                            }
                    }
                    val exact = candidates.filter { it.path.substringBeforeLast('/', "") == track.path.substringBeforeLast('/', "") }
                    val chosen = track.subtitleUri.ifEmpty {
                        val pool = exact.ifEmpty { candidates }
                        // Different formats of the same stem are safe; differing names need manual selection.
                        if (pool.map { it.path.substringBeforeLast('/', "") }.distinct().size == 1)
                            pool.sortedBy { listOf("lrc", "srt", "vtt", "ass").indexOf(LibraryRules.extension(it.name)).let { rank -> if (rank < 0) 99 else rank } }.firstOrNull()?.uri.orEmpty()
                        else ""
                    }
                    if (chosen.isEmpty()) return@withContext Triple(emptyList(), track.subtitleOffset, "未找到明确匹配的字幕，可手动选择")
                    val bytes = contentResolver.openInputStream(android.net.Uri.parse(chosen))?.use { it.readBytesLimited(8 * 1024 * 1024) }
                        ?: error("无法读取字幕")
                    val name = candidates.find { it.uri == chosen }?.name
                        ?: androidx.documentfile.provider.DocumentFile.fromSingleUri(this@PlaybackService, android.net.Uri.parse(chosen))?.name.orEmpty()
                    Triple(SubtitleParser.parse(SubtitleParser.decode(bytes, track.subtitleEncoding), LibraryRules.extension(name)), track.subtitleOffset, "已加载 $name")
                }
                cues = result.first; offset = result.second; subtitleStatus = result.third
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { subtitleStatus = "字幕读取失败：${e.message}" }
            publish()
        }
    }
    private fun publish() {
        val item = player.currentMediaItem
        val snapshot = PlaybackSnapshot(item?.mediaId.orEmpty(), item?.mediaMetadata?.extras?.getString("workId").orEmpty(),
            item?.mediaMetadata?.title?.toString() ?: "尚未播放", item?.mediaMetadata?.albumTitle?.toString() ?: "选择一部作品，开始收听",
            player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it > 0 } ?: 0,
            player.isPlaying, SubtitleParser.active(cues, player.currentPosition - offset), subtitleStatus,
            errorText, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.title.toString() },
            player.currentMediaItemIndex.coerceAtLeast(0), player.playbackParameters.speed, player.repeatMode,
            if (stopAtTrackEnd) -1 else ((sleepAt - System.currentTimeMillis()).coerceAtLeast(0) / 60_000).toInt(),
            subtitleCues = cues, subtitleOffset = offset)
        PlaybackBus.state.value = snapshot
        overlay.update(snapshot.subtitle, player.currentMediaItem != null)
    }
    private fun saveQueue() {
        val items = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            items.put(JSONObject().put("id", item.mediaId).put("uri", item.localConfiguration?.uri.toString())
                .put("title", item.mediaMetadata.title).put("work", item.mediaMetadata.albumTitle)
                .put("workId", item.mediaMetadata.extras?.getString("workId")).put("cover", item.mediaMetadata.artworkUri?.toString().orEmpty()))
        }
        prefs.edit().putString("queue", items.toString()).putInt("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("position", player.currentPosition.coerceAtLeast(0)).apply()
        val item = player.currentMediaItem ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        scope.launch(Dispatchers.IO) {
            val track = repository.dao.track(item.mediaId) ?: return@launch
            if (repository.dao.work(track.effectiveWorkId) != null) {
                repository.dao.putProgress(Progress(track.effectiveWorkId, track.id, position, System.currentTimeMillis()))
            }
        }
    }
    private fun restoreQueue() {
        runCatching {
            val data = JSONArray(prefs.getString("queue", "[]"))
            val items = (0 until data.length()).map { i ->
                val entry = data.getJSONObject(i)
                MediaItem.Builder().setMediaId(entry.getString("id")).setUri(entry.getString("uri"))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.optString("title")).setAlbumTitle(entry.optString("work"))
                        .setArtworkUri(entry.optString("cover").takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse))
                        .setExtras(Bundle().apply { putString("workId", entry.optString("workId")) }).build()).build()
            }
            if (items.isNotEmpty()) player.setMediaItems(items, prefs.getInt("index", 0).coerceIn(items.indices), prefs.getLong("position", 0))
        }.onFailure { prefs.edit().remove("queue").apply() }
    }
    override fun onDestroy() {
        saveQueue(); player.removeListener(listener); overlay.close(); session.release(); player.release(); scope.cancel()
        PlaybackBus.state.value = PlaybackSnapshot()
        super.onDestroy()
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer); if (count < 0) break
        require(out.size() + count <= limit) { "字幕文件过大" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
