package com.ysplayer

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.ysplayer.playback.PlaybackSnapshot
import com.ysplayer.subtitle.SubtitleCue
import com.ysplayer.ui.FullSubtitleReader
import com.ysplayer.ui.PlaybackPages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class SubtitleReaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rightSwipeOpensFullSubtitlesAndLeftSwipeReturnsToCover() {
        compose.setContent {
            MaterialTheme {
                PlaybackPages(Modifier.fillMaxSize(), subtitleContent = { Text("全部字幕内容") }, coverContent = { Text("作品封面内容") })
            }
        }
        compose.onNodeWithTag("playback_page_1").assertIsSelected()
        compose.onNodeWithTag("playback_pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("playback_page_0").assertIsSelected()
        compose.onNodeWithText("全部字幕内容").assertIsDisplayed()
        compose.onNodeWithTag("playback_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("playback_page_1").assertIsSelected()
    }

    @Test fun currentLineIsHighlightedAndTapSeeksToAdjustedStart() {
        var sought = -1L
        val cues = listOf(SubtitleCue(0, 1000, "first"), SubtitleCue(1000, 2000, "second"), SubtitleCue(2000, 3000, "third"))
        compose.setContent {
            MaterialTheme {
                FullSubtitleReader(PlaybackSnapshot(trackId = "track", position = 1700, duration = 4000,
                    subtitleCues = cues, subtitleOffset = 500), true, { sought = it }, {}, Modifier.fillMaxWidth().height(700.dp))
            }
        }
        compose.onNodeWithTag("subtitle_cue_1").assertIsSelected()
        compose.onNodeWithTag("subtitle_cue_2").performClick()
        compose.runOnIdle { assertEquals(2500L, sought) }
    }

    @Test fun browsingDoesNotSnapBackAndReturnButtonRestoresFollowing() {
        var position by mutableLongStateOf(0L)
        val cues = List(100) { SubtitleCue(it * 1000L, (it + 1) * 1000L, "Line $it") }
        compose.setContent {
            MaterialTheme {
                FullSubtitleReader(PlaybackSnapshot(trackId = "track", position = position, duration = 100000,
                    subtitleCues = cues), true, {}, {}, Modifier.fillMaxWidth().height(700.dp))
            }
        }
        compose.onNodeWithTag("subtitle_list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("subtitle_follow").assertExists()
        compose.runOnIdle { position = 80000 }
        compose.onNodeWithTag("subtitle_cue_80").assertDoesNotExist()
        compose.onNodeWithTag("subtitle_follow").performClick()
        compose.onNodeWithTag("subtitle_cue_80").assertIsDisplayed().assertIsSelected()
    }

    @Test fun switchingTracksReplacesContentAndResetsManualBrowsing() {
        var state by mutableStateOf(PlaybackSnapshot(trackId = "old", subtitleCues = List(40) {
            SubtitleCue(it * 1000L, (it + 1) * 1000L, "Old $it")
        }))
        compose.setContent { MaterialTheme { FullSubtitleReader(state, true, {}, {}, Modifier.fillMaxWidth().height(700.dp)) } }
        compose.onNodeWithTag("subtitle_list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("subtitle_follow").assertExists()
        compose.runOnIdle { state = PlaybackSnapshot(trackId = "new", subtitleCues = listOf(SubtitleCue(0, 1000, "New track"))) }
        compose.onNodeWithText("New track").assertIsDisplayed()
        compose.onNodeWithTag("subtitle_follow").assertDoesNotExist()
    }

    @Test fun missingSubtitlesShowFilePickerInsteadOfPreviousText() {
        var picked = false
        compose.setContent {
            MaterialTheme { FullSubtitleReader(PlaybackSnapshot(trackId = "track", subtitleStatus = "未找到字幕"), true, {}, { picked = true }) }
        }
        compose.onNodeWithText("未找到字幕").assertIsDisplayed()
        compose.onNodeWithText("选择字幕文件").performClick()
        compose.runOnIdle { assertEquals(true, picked) }
    }
}
