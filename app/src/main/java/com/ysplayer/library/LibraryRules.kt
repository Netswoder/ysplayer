package com.ysplayer.library

import java.text.Normalizer
import java.util.Locale

/** Pure rules: paths are display-relative paths, never assumed to be filesystem paths. */
object LibraryRules {
    val audioExtensions = setOf("mp3", "wav", "flac", "ogg", "opus", "m4a", "aac")
    val subtitleExtensions = setOf("lrc", "srt", "vtt", "ass", "ssa")
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val rj = Regex("(?i)(?<![A-Z])RJ[0-9]+(?![0-9])")
    fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    fun ids(text: String): List<String> = rj.findAll(Normalizer.normalize(text, Normalizer.Form.NFKC))
        .map { it.value.uppercase(Locale.ROOT) }.distinct().toList()
    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun stem(name: String) = name.substringBeforeLast('.', name)
    fun subtitleKey(name: String): String {
        var value = stem(name)
        if (extension(value) in audioExtensions) value = stem(value)
        value = value.replace(Regex("(?i)[._ -](ja|jp|zh|cn|en|chs|cht)$"), "")
        return normalized(value)
    }
    fun trackKey(name: String) = normalized(stem(name))
    fun title(folder: String): String = folder.replace(Regex("(?i)[\\[【(（]?RJ[0-9]+[\\]】)）]?"), "")
        .trim(' ', '-', '_').ifBlank { folder }

    data class Boundary(val key: String, val title: String, val review: String = "")
    fun boundary(rootName: String, relativePath: String): Boundary {
        val parents = listOf(rootName) + relativePath.split('/').dropLast(1)
        val numbered = parents.withIndex().filter { ids(it.value).isNotEmpty() }
        // A Japanese/Chinese edition pair shares one library entry, keyed by the leading code.
        numbered.firstOrNull()?.let { first ->
            val codes = ids(first.value)
            if (codes.size > 1) return Boundary("rj:${codes.first()}", title(first.value))
        }
        val distinct = numbered.flatMap { ids(it.value) }.distinct()
        if (distinct.size == 1) {
            val label = numbered.first().value
            return Boundary("rj:${distinct.single()}", title(label))
        }
        if (distinct.size > 1) {
            val nearest = numbered.last()
            val local = ids(nearest.value)
            // An explicit child identifier can resolve an ancestor collection.
            if (local.size == 1 && numbered.dropLast(1).all { local.single() in ids(it.value) }) {
                return Boundary("rj:${local.single()}", title(nearest.value))
            }
            return Boundary("folder:" + parents.take(nearest.index + 1).joinToString("/"), title(nearest.value), "多个 RJ 编号，需确认归属")
        }
        val fileIds = ids(relativePath.substringAfterLast('/'))
        if (fileIds.isNotEmpty()) return Boundary("rj:${fileIds.first()}", fileIds.first())
        // Keep an unnamed subtree separate; a flat root remains explicitly unresolved.
        val candidate = parents.drop(1).lastOrNull { !isGroupingFolder(it) }
        return Boundary("folder:${candidate ?: rootName}", candidate ?: rootName, "未找到 RJ，请确认作品归属")
    }
    fun isGroupingFolder(name: String): Boolean = Regex(
        "(?i)^[\\d ._-]*(mp3|wav|flac|ogg|字幕|lrc|srt|vtt|ass|disc\\s*\\d+|cd\\s*\\d+|本篇|本編|特典|音声|音频)[ ._-]*$"
    ).matches(name)
    fun variant(path: String): String {
        val dirs = path.split('/').dropLast(1)
        val content = dirs.lastOrNull { Regex("無.*BGM|无.*BGM|BGM.*なし|効果音なし|SEなし|効果音あり|SEあり", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val format = extension(path).uppercase(Locale.ROOT)
        return if (content == null) format else "$format · $content"
    }
    fun section(path: String): String {
        val dirs = path.split('/').dropLast(1)
        return when {
            dirs.any { Regex("特典|おまけ|bonus", RegexOption.IGNORE_CASE).containsMatchIn(it) } -> "特典"
            dirs.any { Regex("体験|试听|sample|trial", RegexOption.IGNORE_CASE).containsMatchIn(it) } -> "试听"
            else -> dirs.lastOrNull { Regex("(?i)(disc|cd)[ ._-]*\\d+").containsMatchIn(it) } ?: "本篇"
        }
    }
    fun resource(path: String, programAncestors: Set<String>): Boolean {
        val lower = normalized(path)
        return programAncestors.any { it.isEmpty() || lower.startsWith(normalized(it).trimEnd('/') + "/") } &&
            (Regex("(^|/)(www/audio|assets|resources|streamingassets|voices)(/|$)").containsMatchIn(lower) ||
                lower.contains("_data/"))
    }
    val naturalComparator = Comparator<String> { a, b ->
        val aa = Regex("[0-9]+|[^0-9]+").findAll(normalized(a)).map { it.value }.toList()
        val bb = Regex("[0-9]+|[^0-9]+").findAll(normalized(b)).map { it.value }.toList()
        var result = 0
        for (i in 0 until minOf(aa.size, bb.size)) {
            val x = aa[i]; val y = bb[i]
            result = if (x.first().isDigit() && y.first().isDigit()) {
                val xn = x.trimStart('0').ifEmpty { "0" }; val yn = y.trimStart('0').ifEmpty { "0" }
                xn.length.compareTo(yn.length).takeIf { it != 0 } ?: xn.compareTo(yn)
            } else x.compareTo(y)
            if (result != 0) break
        }
        if (result == 0) aa.size.compareTo(bb.size) else result
    }
}
