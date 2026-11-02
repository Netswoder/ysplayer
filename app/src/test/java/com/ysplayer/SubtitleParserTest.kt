package com.ysplayer

import com.ysplayer.subtitle.SubtitleParser as Parser
import org.junit.Assert.*
import org.junit.Test

class SubtitleParserTest {
    @Test fun subtitleSeekAppliesTrackOffsetOnceAndRespectsDuration() {
        val cue = Parser.parse("[offset:100]\n[00:01.00]hello", "lrc").single()
        assertEquals(1600L, Parser.seekPosition(cue, 500))
        assertEquals(0L, Parser.seekPosition(cue, -2000))
        assertEquals(1200L, Parser.seekPosition(cue, 500, 1200))
        assertEquals(1600L, Parser.seekPosition(cue, 500, 0))
    }
    @Test fun subtitleSelectionRespectsGapsAndReturnsPreviousRowForFollowing() {
        val cues = Parser.parse("1\n00:00:01,000 --> 00:00:02,000\nfirst\n\n2\n00:00:04,000 --> 00:00:05,000\nsecond", "srt")
        assertEquals(-1, Parser.activeIndex(cues, 500))
        assertEquals(0, Parser.activeIndex(cues, 1000))
        assertEquals(-1, Parser.activeIndex(cues, 2000))
        assertEquals(0, Parser.indexAtOrBefore(cues, 3000))
        assertEquals(1, Parser.activeIndex(cues, 4000))
        assertEquals(-1, Parser.activeIndex(emptyList(), 0))
    }
    @Test fun lrcSupportsMultipleStampsBilingualAndOffset() {
        val cues = Parser.parse("[offset:100]\n[00:01.00][00:03.00]hello\n[00:01.00]你好", "lrc")
        assertEquals(2, cues.size)
        assertEquals(1100L, cues[0].start)
        assertEquals("hello\n你好", Parser.active(cues, 2000))
        assertEquals("hello", Parser.active(cues, 3100))
        assertEquals("", Parser.active(cues, 0))
    }
    @Test fun srtRespectsGapsAndMultiline() {
        val cues = Parser.parse("1\n00:00:01,000 --> 00:00:02,000\n日文\n中文\n\n2\n00:00:04,000 --> 00:00:05,000\nnext", "srt")
        assertEquals("日文\n中文", Parser.active(cues, 1000))
        assertEquals("", Parser.active(cues, 2000))
        assertEquals("next", Parser.active(cues, 4500))
    }
    @Test fun vttSupportsHeaderCueSettingsAndMarkup() {
        val cues = Parser.parse("WEBVTT\n\nintro\n00:01.500 --> 00:03.000 align:start\n<c.ja>Hello &amp; welcome</c>\n", "vtt")
        assertEquals("Hello & welcome", Parser.active(cues, 2000))
    }
    @Test fun assUsesDeclaredFieldsAndPreservesCommas() {
        val cues = Parser.parse("[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:01.00,0:00:02.50,Default,{\\b1}Hello,world\\N你好", "ass")
        assertEquals("Hello,world\n你好", Parser.active(cues, 1500))
    }
    @Test fun malformedTimesAreSkipped() {
        assertTrue(Parser.parse("nonsense --> later\ntext", "srt").isEmpty())
    }
    @Test fun decodesUtf8BomAndJapaneseEncoding() {
        assertEquals("こんにちは", Parser.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "こんにちは".toByteArray()))
        assertEquals("こんにちは", Parser.decode("こんにちは".toByteArray(charset("Shift_JIS")), "Shift_JIS"))
    }
}
