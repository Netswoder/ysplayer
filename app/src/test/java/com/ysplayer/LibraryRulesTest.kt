package com.ysplayer

import com.ysplayer.library.LibraryRules as Rules
import org.junit.Assert.*
import org.junit.Test

class LibraryRulesTest {
    @Test fun preservesNumberAndNormalizesFullWidth() {
        assertEquals(listOf("RJ00123456"), Rules.ids("[ＲＪ００１２３４５６] test"))
        assertTrue(Rules.ids("notARJ123456").isEmpty())
    }
    @Test fun repeatedPackagingIsOneWork() {
        val a = Rules.boundary("RJ", "[circle]/[RJ00123456] title/inner_RJ00123456/mp3/01.mp3")
        val b = Rules.boundary("RJ", "[circle]/[RJ00123456] title/wav/01.wav")
        assertEquals(a.key, b.key)
        assertEquals("rj:RJ00123456", a.key)
    }
    @Test fun dualEditionFolderAlwaysUsesTheLeadingRj() {
        assertEquals("rj:RJ000001", Rules.boundary("library", "RJ000001 RJ000002/RJ000001/mp3/01.mp3").key)
        val a = Rules.boundary("library", "RJ000001 RJ000002/mp3/01.mp3")
        val b = Rules.boundary("library", "RJ000001 RJ000002/subtitles/01.lrc")
        assertEquals(a.key, b.key)
        assertEquals("rj:RJ000001", a.key)
        assertTrue(a.review.isEmpty())
        assertEquals("rj:RJ000001", Rules.boundary("library", "RJ000001RJ000002/RJ000002/01.wav").key)
        assertEquals(listOf("RJ000001", "RJ000002"), Rules.ids("RJ000001RJ000002"))
        assertEquals("rj:RJ00123456", Rules.boundary("RJ", "ＲＪ００１２３４５６ＲＪ００６５４３２１/MP3/01.mp3").key)
    }
    @Test fun conflictingIdentifiersDoNotMerge() {
        assertTrue(Rules.boundary("RJ000001", "RJ000002/01.mp3").review.isNotEmpty())
    }
    @Test fun unknownFoldersRemainSeparate() {
        assertNotEquals(Rules.boundary("library", "one/mp3/01.mp3").key, Rules.boundary("library", "two/wav/01.wav").key)
    }
    @Test fun matchesDoubleExtensionsAndLanguageSuffixes() {
        assertEquals(Rules.trackKey("トラック01.wav"), Rules.subtitleKey("トラック01.wav.vtt"))
        assertEquals(Rules.trackKey("01_intro.mp3"), Rules.subtitleKey("01_intro.zh.lrc"))
    }
    @Test fun naturalOrderHandlesDiscsAndLargeNumbers() {
        val actual = listOf("Disc2/1.mp3", "Disc1/10.mp3", "Disc1/2.mp3", "Disc1/01.mp3").sortedWith(Rules.naturalComparator)
        assertEquals(listOf("Disc1/01.mp3", "Disc1/2.mp3", "Disc1/10.mp3", "Disc2/1.mp3"), actual)
        assertTrue(Rules.naturalComparator.compare("99999999999999999999", "100000000000000000000") < 0)
    }
    @Test fun doesNotExcludeOggWithoutProgramEvidence() {
        assertFalse(Rules.resource("voices/01.ogg", emptySet()))
        assertTrue(Rules.resource("game/voices/01.ogg", setOf("game")))
        assertTrue(Rules.resource("voices/01.ogg", setOf("")))
        assertFalse(Rules.resource("game/mp3/01.mp3", setOf("game")))
    }
}
