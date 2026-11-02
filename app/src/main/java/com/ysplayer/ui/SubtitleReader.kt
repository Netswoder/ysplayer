package com.ysplayer.ui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ysplayer.playback.PlaybackSnapshot
import com.ysplayer.subtitle.SubtitleParser
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Lyrics are to the left of the default cover: a rightward swipe opens them. */
@Composable
internal fun PlaybackPages(
    modifier: Modifier = Modifier,
    subtitleContent: @Composable (visible: Boolean) -> Unit,
    coverContent: @Composable () -> Unit
) {
    val pager = rememberPagerState(initialPage = 1, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center) {
            listOf("字幕", "封面").forEachIndexed { index, title ->
                TextButton(
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    modifier = Modifier.testTag("playback_page_$index").semantics { selected = pager.currentPage == index }
                ) {
                    Text(title, color = if (pager.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (pager.currentPage == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("playback_pager"), beyondViewportPageCount = 1) { page ->
            Box(Modifier.fillMaxSize()) {
                if (page == 0) subtitleContent(pager.settledPage == 0) else coverContent()
            }
        }
    }
}

@Composable
internal fun FullSubtitleReader(
    state: PlaybackSnapshot,
    visible: Boolean,
    onSeek: (Long) -> Unit,
    onChooseSubtitle: () -> Unit,
    modifier: Modifier = Modifier
) {
    key(state.trackId) {
        val cues = state.subtitleCues
        val list = rememberLazyListState()
        val dragged by list.interactionSource.collectIsDraggedAsState()
        var followPlayback by rememberSaveable { mutableStateOf(true) }
        var positioned by remember(cues) { mutableStateOf(false) }
        val position = state.position - state.subtitleOffset
        val activeIndex = SubtitleParser.activeIndex(cues, position)
        val focusIndex = SubtitleParser.indexAtOrBefore(cues, position).coerceAtLeast(0)

        LaunchedEffect(dragged) { if (dragged) followPlayback = false }
        LaunchedEffect(visible, followPlayback, focusIndex, cues) {
            if (visible && followPlayback && cues.isNotEmpty()) {
                if (!positioned || abs(focusIndex - list.firstVisibleItemIndex) > 30) list.scrollToItem(focusIndex)
                else list.animateScrollToItem(focusIndex)
                positioned = true
            }
        }

        Column(modifier.fillMaxSize().testTag("subtitle_reader")) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (cues.isEmpty()) "当前轨道字幕" else "${cues.size} 句 · 点击字幕跳转", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!followPlayback && cues.isNotEmpty()) {
                        TextButton(onClick = { followPlayback = true }, modifier = Modifier.testTag("subtitle_follow")) { Text("回到当前句") }
                    }
                }
            }
            if (cues.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.subtitleStatus.ifEmpty { "选择已有字幕，查看完整内容" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onChooseSubtitle, enabled = state.trackId.isNotEmpty()) { Text("选择字幕文件") }
                }
            } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = list, modifier = Modifier.fillMaxSize().testTag("subtitle_list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = maxHeight / 4, bottom = maxHeight / 2),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(cues, key = { index, cue -> "$index:${cue.start}" }) { index, cue ->
                        val active = index == activeIndex
                        val seekPosition = SubtitleParser.seekPosition(cue, state.subtitleOffset, state.duration)
                        Card(
                            onClick = { onSeek(seekPosition) },
                            modifier = Modifier.fillMaxWidth().testTag("subtitle_cue_$index").semantics { selected = active },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(subtitleTimestamp(seekPosition), style = MaterialTheme.typography.labelSmall,
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(cue.text.ifBlank { "♪" }, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun subtitleTimestamp(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
