package com.ysplayer.library

import com.ysplayer.data.Track
import com.ysplayer.data.Work

internal data class ScannedAudio(val file: DocumentEntry, val trackId: String, val boundary: LibraryRules.Boundary)

/** Only reconcile unique paths whose difference is RJ decoration, within the same SAF source. */
internal class ScanReconciliation(current: List<ScannedAudio>, previous: List<Track>, works: Map<String, Work>) {
    private val seenIds = current.map { it.trackId }.toSet()
    private val oldById = previous.associateBy { it.id }
    private val oldByPath = previous.filter { it.id !in seenIds }.groupBy { renameKey(it.path) }
    private val newByPath = current.groupBy { renameKey(it.file.path) }
    val relocated = current.mapNotNull { audio ->
        val key = renameKey(audio.file.path)
        if (newByPath[key]?.size != 1) null else oldByPath[key]?.singleOrNull()?.let { it to audio }
    }
    private val movedByNewId = relocated.associate { it.second.trackId to it.first }
    fun original(audio: ScannedAudio): Track? = oldById[audio.trackId] ?: movedByNewId[audio.trackId]

    private val observations = current.flatMap { audio ->
        listOfNotNull(oldById[audio.trackId], movedByNewId[audio.trackId]).distinctBy { it.id }.map { it.workId to audio.boundary.key }
    }
    val destinations = observations.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    val reusableWorkIds = observations.groupBy({ it.second }, { it.first }).mapNotNull { (key, ids) ->
        val id = ids.distinct().singleOrNull() ?: return@mapNotNull null
        if (key.startsWith("rj:") && works[id]?.rj?.isBlank() == true && destinations[id]?.size == 1) key to id else null
    }.toMap()

    companion object {
        fun renameKey(path: String) = path.split('/').joinToString("/") { LibraryRules.normalized(LibraryRules.title(it)) }
    }
}
