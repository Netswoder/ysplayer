package com.ysplayer.subtitle

import java.nio.charset.Charset
import org.mozilla.universalchardet.UniversalDetector

data class SubtitleCue(val start: Long, val end: Long, val text: String)

object SubtitleParser {
    fun decode(bytes: ByteArray, encoding: String = "自动"): String {
        if (encoding != "自动") return bytes.toString(Charset.forName(encoding)).removePrefix("\uFEFF")
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size); detector.dataEnd()
        val charset = when {
            bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) -> Charsets.UTF_8
            bytes.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> Charsets.UTF_16LE
            bytes.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> Charsets.UTF_16BE
            else -> runCatching { Charset.forName(detector.detectedCharset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        }
        return bytes.toString(charset).removePrefix("\uFEFF")
    }
    fun parse(text: String, extension: String): List<SubtitleCue> = when (extension.lowercase()) {
        "lrc" -> lrc(text)
        "ass", "ssa" -> ass(text)
        else -> timed(text)
    }.sortedBy { it.start }

    fun time(value: String): Long {
        val parts = value.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return -1
        return runCatching {
            val hours = if (parts.size == 3) parts[0].toLong() else 0
            val minutes = parts[parts.size - 2].toLong()
            val seconds = parts.last().toDouble()
            ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong()
        }.getOrDefault(-1)
    }
    private fun clean(text: String) = text.replace(Regex("<[^>]*>"), "").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").trim()
    private fun lrc(text: String): List<SubtitleCue> {
        val offset = Regex("(?i)\\[offset:([+-]?\\d+)\\]").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val stamp = Regex("\\[(\\d+:\\d{2}(?:[.,]\\d+)?)\\]")
        val lines = text.lines().flatMap { line ->
            val lyric = clean(line.replace(stamp, ""))
            stamp.findAll(line).map { (time(it.groupValues[1]) + offset).coerceAtLeast(0) to lyric }.toList()
        }.sortedBy { it.first }
        // Multiple lines at one timestamp are treated as bilingual text, not overwritten.
        val grouped = lines.groupBy({ it.first }, { it.second }).toSortedMap().entries.toList()
        return grouped.mapIndexed { i, entry -> SubtitleCue(entry.key, grouped.getOrNull(i + 1)?.key ?: Long.MAX_VALUE,
            entry.value.filter { it.isNotBlank() }.distinct().joinToString("\n")) }
    }
    private fun timed(text: String): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        val lines = text.replace("\r", "").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i++]
            if (!line.contains("-->")) continue
            val parts = line.split("-->", limit = 2)
            val start = time(parts[0]); val end = time(parts[1].trim().substringBefore(' '))
            val body = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) body.add(lines[i++])
            if (start >= 0 && end > start) result.add(SubtitleCue(start, end, clean(body.joinToString("\n"))))
        }
        return result
    }
    private fun ass(text: String): List<SubtitleCue> {
        var inEvents = false
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val cues = mutableListOf<SubtitleCue>()
        for (line in text.lines()) {
            if (line.trim().startsWith('[')) inEvents = line.trim().equals("[Events]", true)
            if (!inEvents) continue
            if (line.startsWith("Format:", true)) format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            if (!line.startsWith("Dialogue:", true)) continue
            val values = line.substringAfter(':').trim().split(',', limit = format.size)
            fun field(key: String) = values.getOrNull(format.indexOf(key)).orEmpty()
            val start = time(field("start")); val end = time(field("end"))
            val body = field("text").replace(Regex("\\{[^}]*}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            if (start >= 0 && end > start) cues.add(SubtitleCue(start, end, body.trim()))
        }
        return cues
    }
    fun active(cues: List<SubtitleCue>, position: Long): String {
        return cues.getOrNull(activeIndex(cues, position))?.text.orEmpty()
    }
    fun activeIndex(cues: List<SubtitleCue>, position: Long): Int {
        val index = indexAtOrBefore(cues, position)
        return index.takeIf { it >= 0 && position < cues[it].end } ?: -1
    }
    /** Also locates the nearest preceding row while playback is in a subtitle gap. */
    fun indexAtOrBefore(cues: List<SubtitleCue>, position: Long): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (cues[middle].start <= position) low = middle + 1 else high = middle
        }
        return low - 1
    }
    /** The parser has already applied an LRC file's offset; add only the user's track offset. */
    fun seekPosition(cue: SubtitleCue, offset: Long, duration: Long = 0): Long {
        val shifted = if (offset > 0 && cue.start > Long.MAX_VALUE - offset) Long.MAX_VALUE
            else if (offset < 0 && cue.start < Long.MIN_VALUE - offset) Long.MIN_VALUE
            else cue.start + offset
        val start = shifted.coerceAtLeast(0)
        return if (duration > 0) start.coerceAtMost(duration) else start
    }
}
