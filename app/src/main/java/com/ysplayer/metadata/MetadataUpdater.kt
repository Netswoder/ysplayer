package com.ysplayer.metadata

import com.ysplayer.data.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

data class MetadataState(val running: Boolean = false, val message: String = "按 RJ 补全缺失的社团与声优", val done: Int = 0, val total: Int = 0)

class MetadataUpdater(private val database: LibraryDatabase, private val source: CreditSource,
                      private val now: () -> Long = System::currentTimeMillis) {
    val state = MutableStateFlow(MetadataState())
    private val mutex = Mutex()
    /** Only incomplete library works are candidates. Successful lookups never expire automatically. */
    suspend fun syncMissing() = mutex.withLock {
        var downloaded = 0; var cached = 0; var failed = 0
        try {
            val pending = database.library().allWorks().filter { it.circles.isBlank() || it.voices.isBlank() || !it.tagsKnown }
                .map { it.rj.trim().uppercase(Locale.ROOT) }.filter { Regex("RJ\\d{6,10}").matches(it) }.distinct()
            state.value = MetadataState(true, "正在检查本地资料库", total = pending.size)
            for ((index, rj) in pending.withIndex()) {
                currentCoroutineContext().ensureActive()
                state.value = MetadataState(true, "$rj · ${index + 1}/${pending.size}，联网请求至少间隔 10 秒", index, pending.size)
                val stored = database.credits().work(rj)
                if (stored != null) {
                    fill(rj)
                    if (stored.tagsFetched) { cached++; continue }
                }
                // A scan or a manual edit may have completed while the previous request was waiting.
                if (database.library().allWorks().none { it.rj.equals(rj, true) && (it.voices.isBlank() || it.circles.isBlank() || !it.tagsKnown) }) continue
                try {
                    val result = source.fetch(rj)
                    currentCoroutineContext().ensureActive()
                    require(result.rj == rj && result.creators.any { it.kind == "circle" } && result.tagsFetched) { "作品资料或中文标签不完整" }
                    database.withTransaction {
                        database.credits().putWork(CreditWork(rj, result.sourceUrl, now(), result.voiceFieldPresent, result.tagsFetched))
                        database.credits().putCreators(result.creators)
                        database.credits().putLinks(result.creators.mapIndexed { position, c -> CreditLink(rj, c.id, position) })
                        database.credits().clearTags(rj)
                        database.credits().putTags(result.tags.mapIndexed { position, tag -> CreditTag(rj, tag.id, tag.name, position) })
                        database.credits().clearAttempt(rj)
                        fill(rj)
                    }
                    downloaded++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failed++
                    // Failed attempts are diagnostic records; they never prevent a later manual retry.
                    val retry = (e as? MetadataAccessException)?.retryAt ?: 0
                    database.credits().putAttempt(CreditAttempt(rj, now(), retry, e.message ?: "网络暂不可用"))
                    // A failed work must not prevent other pending works from being completed.
                }
            }
            val lastFailure = if (failed > 0) "；具体错误见下方，可重新补全" else ""
            state.value = MetadataState(message = "已补全 $downloaded 部 · 命中缓存 $cached 部 · 失败 $failed 部$lastFailure")
        } catch (e: CancellationException) {
            state.value = state.value.copy(running = false, message = "补全已暂停，已缓存的资料会保留")
            throw e
        } catch (e: Exception) {
            state.value = state.value.copy(running = false, message = "补全失败：${e.message}")
        }
    }
    private suspend fun fill(rj: String) {
        val creators = database.credits().creators(rj)
        database.library().fillMissingCredits(rj, creators.filter { it.kind == "circle" }.joinToString("、") { it.name },
            creators.filter { it.kind == "voice" }.joinToString("、") { it.name })
        if (database.credits().work(rj)?.tagsFetched == true) {
            database.library().fillMissingTags(rj, database.credits().tags(rj).joinToString("\n") { it.name })
        }
    }
}
