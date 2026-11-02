package com.ysplayer

import com.ysplayer.metadata.*
import org.junit.Assert.*
import org.junit.Test

class DlsiteParserTest {
    private val url = "$DLSITE_ORIGIN/maniax/work/=/product_id/RJ99000001.html"
    private fun fixture() = javaClass.getResourceAsStream("/dlsite/example-credits.html")!!.bufferedReader().use { it.readText() }
    @Test fun syntheticCreditRowsKeepAllVoicesAndExcludeAuthorAndIllustrator() {
        val result = DlsiteParser.parse(fixture(), "RJ99000001", url)
        assertEquals(listOf("Example Circle"), result.creators.filter { it.kind == "circle" }.map { it.name })
        assertEquals("circle:RG99001", result.creators.first().id)
        assertEquals(listOf("Actor A", "Actor B", "Actor C"), result.creators.filter { it.kind == "voice" }.map { it.name })
        assertTrue(result.voiceFieldPresent)
    }
    @Test fun officialSimplifiedChinesePageSuppliesChineseTagsWithStableIds() {
        val html = javaClass.getResourceAsStream("/dlsite/example-zh-credits.html")!!.bufferedReader().use { it.readText() }
        val result = DlsiteParser.parse(html, "RJ99000001", "$url?locale=zh_CN")
        assertEquals(3, result.creators.count { it.kind == "voice" })
        assertEquals("Example Circle", result.creators.first().name)
        assertTrue(result.tagsFetched)
        assertEquals(8, result.tags.size)
        assertEquals(DownloadedTag("496", "双声道立体声/人头麦"), result.tags[1])
        assertTrue(DownloadedTag("503", "低语") in result.tags)
        assertFalse(DlsiteParser.parse(html.replace("lang=\"zh-CN\"", "lang=\"ja\""), "RJ99000001", url).tagsFetched)
    }
    @Test fun uncreditedVoiceIsAValidCompletedLookupButBrokenVoiceRowIsNot() {
        val circle = "<tr><th>サークル名</th><td><a href='/maniax/circle/profile/=/maker_id/RG1.html'>Circle</a></td></tr>"
        val result = DlsiteParser.parse("<table>$circle</table>", "RJ99000001", url)
        assertFalse(result.voiceFieldPresent)
        assertThrows(IllegalArgumentException::class.java) {
            DlsiteParser.parse("<table>$circle<tr><th>声優</th><td>captcha</td></tr></table>", "RJ99000001", url)
        }
        assertThrows(IllegalArgumentException::class.java) { DlsiteParser.parse("<html>verification required</html>", "RJ99000001", url) }
    }
    @Test fun robotsMatchesWildcardLongestRuleAndSpecificAgent() {
        val policy = RobotsPolicy("User-agent: *\nDisallow: /*/search/\nDisallow: /private/\nAllow: /private/public$\nCrawl-delay: 12", "YsPlayerMetadata/0.3")
        assertFalse(policy.allows("$DLSITE_ORIGIN/maniax/search/result/"))
        assertFalse(policy.allows("$DLSITE_ORIGIN/private/other"))
        assertTrue(policy.allows("$DLSITE_ORIGIN/private/public"))
        assertFalse(policy.allows("$DLSITE_ORIGIN/private/public/other"))
        assertTrue(policy.allows(url))
        assertEquals(12_000L, policy.delayMillis)
        assertFalse(RobotsPolicy("User-agent: *\nAllow: /\nUser-agent: YsPlayerMetadata\nDisallow: /", "YsPlayerMetadata/0.3").allows(url))
    }
    @Test fun countryRestrictionPageReportsTheActualReasonInsteadOfMissingCredits() {
        val html = javaClass.getResourceAsStream("/dlsite/example-region-limited.html")!!.bufferedReader().use { it.readText() }
        val error = assertThrows(IllegalArgumentException::class.java) { DlsiteParser.parse(html, "RJ99000002", url) }
        assertTrue(error.message!!.contains("地区限制"))
        assertTrue(error.message!!.contains("HTTP 200"))
    }
    @Test fun translationRedirectRequiresExplicitAssociationAndAcceptsTrailingSlash() {
        val requested = "$DLSITE_ORIGIN/maniax/work/=/product_id/RJ99000002.html?locale=zh_CN"
        val canonical = "$DLSITE_ORIGIN/maniax/work/=/product_id/RJ99000003.html/"
        assertEquals("$canonical?translation=RJ99000002&locale=zh_CN",
            productRedirect(requested, "$canonical?translation=RJ99000002", "RJ99000002"))
        assertNull(productRedirect(requested, canonical, "RJ99000002"))
        assertNull(productRedirect(requested, "$canonical?translation=RJ99999999", "RJ99000002"))
        assertNull(productRedirect(requested, "$canonical?translation=RJ99000002&translation=RJ99000002", "RJ99000002"))
        assertNull(productRedirect(requested, "$canonical?translation=RJ99000002&redirect=other", "RJ99000002"))
        assertNull(productRedirect(requested, "https://other.example/maniax/work/=/product_id/RJ99000002.html", "RJ99000002"))
        assertNull(productRedirect(requested, "$DLSITE_ORIGIN/maniax/agecheck/", "RJ99000002"))
    }
}
