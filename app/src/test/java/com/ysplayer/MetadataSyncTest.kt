package com.ysplayer

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysplayer.data.*
import com.ysplayer.metadata.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class MetadataSyncTest {
    private lateinit var db: LibraryDatabase
    private val circle = CreditCreator("circle:RG1", "circle", "社团", "$DLSITE_ORIGIN/circle")
    private val voices = listOf(CreditCreator("voice:1", "voice", "声优甲", "$DLSITE_ORIGIN/voice1"),
        CreditCreator("voice:2", "voice", "声优乙", "$DLSITE_ORIGIN/voice2"))
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build() }
    @After fun close() { db.close() }
    @Test fun missingMappingsFetchOnceCacheRestoresOfflineAndKnownCreatorsAreReused() = runBlocking {
        db.library().putWorks(listOf(Work("one", "One", "RJ123456", circles = "手填社团"), Work("duplicate", "Duplicate", "RJ123456"),
            Work("complete", "Complete", "RJ123458", voices = "手填", circles = "手填", tagsKnown = true)))
        var calls = 0
        val updater = MetadataUpdater(db, CreditSource { rj -> calls++; DownloadedCredits(rj, "$DLSITE_ORIGIN/$rj", listOf(circle) + voices, true) })
        updater.syncMissing()
        assertEquals(1, calls)
        assertEquals("手填社团", db.library().work("one")!!.circles)
        assertEquals("声优甲、声优乙", db.library().work("one")!!.voices)
        assertEquals(CreditStats(1, 1, 2), db.credits().stats().first())
        db.library().editWork("one", "Changed", "", "")
        MetadataUpdater(db, CreditSource { error("Offline: cache must avoid the network") }).syncMissing()
        assertEquals("声优甲、声优乙", db.library().work("one")!!.voices)
        db.library().putWorks(listOf(Work("two", "Two", "RJ123457")))
        updater.syncMissing()
        assertEquals(2, calls)
        assertEquals(CreditStats(2, 1, 2), db.credits().stats().first())
    }
    @Test fun failedAttemptsAreSkippedAndNeverBlockManualRetry() = runBlocking {
        val now = 10_000L
        db.library().putWorks(listOf(Work("one", "One", "RJ123456"), Work("two", "Two", "RJ123457")))
        var calls = 0
        val source = CreditSource { calls++; throw MetadataAccessException("HTTP 429", now + 60_000, 429) }
        MetadataUpdater(db, source) { now }.syncMissing()
        assertEquals(2, calls)
        assertEquals(0, db.credits().stats().first().works)
        assertTrue(db.credits().attempts().first().all { it.retryAfter == now + 60_000 })
        assertEquals(2, db.credits().attempts().first().size)
        MetadataUpdater(db, source) { now + 1000 }.syncMissing()
        assertEquals(4, calls)
        assertTrue(db.library().work("one")!!.voices.isBlank() || db.library().work("two")!!.voices.isBlank())
    }
    @Test fun absentOfficialVoiceIsCachedAndManualEditDuringFetchWins() = runBlocking {
        db.library().putWorks(listOf(Work("one", "One", "RJ123456")))
        var calls = 0
        val updater = MetadataUpdater(db, CreditSource { rj ->
            calls++; db.library().editWork("one", "My title", "My voice", "")
            DownloadedCredits(rj, "$DLSITE_ORIGIN/$rj", listOf(circle), false)
        })
        updater.syncMissing()
        assertEquals("My voice", db.library().work("one")!!.voices)
        assertEquals("My title", db.library().work("one")!!.title)
        db.library().editWork("one", "My title", "", "社团")
        updater.syncMissing()
        assertEquals(1, calls)
        assertFalse(db.credits().work("RJ123456")!!.voiceFieldPresent)
    }
    @Test fun cancelledRequestDoesNotCommitPartialCatalogOrFailure() = runBlocking {
        db.library().putWorks(listOf(Work("one", "One", "RJ123456")))
        val updater = MetadataUpdater(db, CreditSource { throw CancellationException("Cancelled") })
        try { updater.syncMissing(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(CreditStats(), db.credits().stats().first())
        assertNull(db.credits().attempt("RJ123456"))
        assertFalse(updater.state.value.running)
    }
    @Test fun tenSecondGateSurvivesRestartButServerHintNeverBecomesALocalCooldown() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("gate-test", 0).also { it.edit().clear().putLong("blockedUntil", Long.MAX_VALUE).commit() }
        var clock = 1_000_000L
        val starts = mutableListOf<Long>()
        val html = javaClass.getResourceAsStream("/dlsite/example-zh-credits.html")!!.bufferedReader().use { it.readText() }
        var throttle = false
        val transport = MetadataTransport { url ->
            starts += clock
            if (url.endsWith("robots.txt")) HttpPage("User-agent: *\nCrawl-delay: 10")
            else {
                assertTrue(url.endsWith("?locale=zh_CN"))
                if (throttle) HttpPage("", 429, "7200") else HttpPage(html)
            }
        }
        val client = DlsiteClient(prefs, transport, { clock }, { clock += it })
        coroutineScope { listOf("RJ123456", "RJ123457").map { rj -> async { client.fetch(rj) } }.awaitAll() }
        assertEquals(listOf(1_000_000L, 1_010_000L, 1_020_000L), starts)
        assertFalse(prefs.contains("blockedUntil"))
        // A new client/process still respects the last start and cached robots policy.
        throttle = true
        val restarted = DlsiteClient(prefs, transport, { clock }, { clock += it })
        try { restarted.fetch("RJ123458"); fail("Expected throttle") } catch (e: MetadataAccessException) {
            assertEquals(429, e.status); assertEquals(clock + 7_200_000, e.retryAt)
        }
        assertEquals(4, starts.size)
        throttle = false
        assertTrue(restarted.fetch("RJ123459").tagsFetched)
        assertEquals(5, starts.size)
        assertEquals(1_040_000L, starts.last())
    }
    @Test fun publicSectionRedirectAndFallbackAreRateLimitedAndVerificationIsNotFollowed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("redirect-test", 0)
        val html = javaClass.getResourceAsStream("/dlsite/example-zh-credits.html")!!.bufferedReader().use { it.readText() }
        val home = "$DLSITE_ORIGIN/home/work/=/product_id/RJ99000001.html"
        for (status in listOf(302, 404)) {
            prefs.edit().clear().commit()
            var clock = 1_000_000L
            val starts = mutableListOf<Long>()
            val client = DlsiteClient(prefs, MetadataTransport { url ->
                starts += clock
                when {
                    url.endsWith("robots.txt") -> HttpPage("User-agent: *\nCrawl-delay: 10")
                    "/maniax/" in url -> HttpPage("", status, location = home)
                    else -> HttpPage(html)
                }
            }, { clock }, { clock += it })
            assertEquals("$home?locale=zh_CN", client.fetch("RJ99000001").sourceUrl)
            assertEquals(listOf(1_000_000L, 1_010_000L, 1_020_000L), starts)
        }
        prefs.edit().clear().commit()
        var clock = 1_000_000L
        var calls = 0
        val restricted = DlsiteClient(prefs, MetadataTransport { url ->
            calls++
            if (url.endsWith("robots.txt")) HttpPage("User-agent: *")
            else HttpPage("", 302, location = "$DLSITE_ORIGIN/maniax/agecheck/=/adultcheck/")
        }, { clock }, { clock += it })
        try { restricted.fetch("RJ99000001"); fail("Verification must not be followed") }
        catch (e: MetadataAccessException) { assertEquals(302, e.status) }
        assertEquals(2, calls)
    }
    @Test fun oldCreditCacheFetchesChineseTagsOnceAndThenWorksOffline() = runBlocking {
        val rj = "RJ123456"
        db.library().putWorks(listOf(Work("one", "One", rj, voices = "手填声优", circles = "手填社团")))
        db.credits().putWork(CreditWork(rj, "$DLSITE_ORIGIN/$rj", 1, true))
        db.credits().putCreators(listOf(circle) + voices)
        db.credits().putLinks((listOf(circle) + voices).mapIndexed { i, c -> CreditLink(rj, c.id, i) })
        var calls = 0
        val source = CreditSource { calls++; DownloadedCredits(rj, "$DLSITE_ORIGIN/$rj?locale=zh_CN", listOf(circle) + voices,
            true, listOf(DownloadedTag("497", "ASMR"), DownloadedTag("503", "低语"))) }
        MetadataUpdater(db, source).syncMissing()
        val work = db.library().work("one")!!
        assertEquals(1, calls)
        assertTrue(work.tagsKnown)
        assertEquals("ASMR\n低语", work.tags)
        assertEquals("手填声优", work.voices)
        assertEquals("手填社团", work.circles)
        assertEquals(listOf("497", "503"), db.credits().tags(rj).map { it.tagId })
        MetadataUpdater(db, CreditSource { error("Cached tags must not use the network") }).syncMissing()
        // A newly imported copy with blank display fields must also use the existing cache.
        db.library().putWorks(listOf(Work("copy", "Copy", rj)))
        MetadataUpdater(db, CreditSource { error("No repeat request") }).syncMissing()
        assertEquals("ASMR\n低语", db.library().work("copy")!!.tags)
        assertEquals("声优甲、声优乙", db.library().work("copy")!!.voices)
    }
    @Test fun failedChineseTagUpgradeKeepsExistingCreditsAndCanBeRetried() = runBlocking {
        val rj = "RJ123456"
        db.library().putWorks(listOf(Work("one", "One", rj)))
        db.credits().putWork(CreditWork(rj, "$DLSITE_ORIGIN/$rj", 1, true))
        db.credits().putCreators(listOf(circle) + voices)
        db.credits().putLinks((listOf(circle) + voices).mapIndexed { i, c -> CreditLink(rj, c.id, i) })
        var calls = 0
        val updater = MetadataUpdater(db, CreditSource { calls++; throw IllegalArgumentException("Not a Chinese tag page") })
        updater.syncMissing(); updater.syncMissing()
        assertEquals(2, calls)
        assertEquals(1L, db.credits().work(rj)!!.fetchedAt)
        assertFalse(db.credits().work(rj)!!.tagsFetched)
        assertFalse(db.library().work("one")!!.tagsKnown)
        assertEquals("声优甲、声优乙", db.library().work("one")!!.voices)
        assertEquals(0L, db.credits().attempt(rj)!!.retryAfter)
    }
    @Test fun dnsFailureCanBeRetriedAfterOnlyTheTenSecondInterval() = runBlocking {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("dns-test", 0).also { it.edit().clear().commit() }
        var clock = 1_000_000L
        var failNetwork = true
        val starts = mutableListOf<Long>()
        val html = javaClass.getResourceAsStream("/dlsite/example-zh-credits.html")!!.bufferedReader().use { it.readText() }
        val transport = MetadataTransport { url ->
            starts += clock
            if (url.endsWith("robots.txt")) HttpPage("User-agent: *\nCrawl-delay: 10")
            else if (failNetwork) throw java.net.UnknownHostException("fixture-host") else HttpPage(html)
        }
        try { DlsiteClient(prefs, transport, { clock }, { clock += it }).fetch("RJ99000001"); fail("Expected DNS failure") }
        catch (e: MetadataAccessException) { assertTrue(e.message!!.contains("UnknownHostException")); assertEquals(0L, e.retryAt) }
        failNetwork = false
        DlsiteClient(prefs, transport, { clock }, { clock += it }).fetch("RJ99000001")
        assertEquals(listOf(1_000_000L, 1_010_000L, 1_020_000L), starts)
    }
    @Test fun forbiddenWorkDoesNotStopNextWorkAndOnlyFailureIsRetriedNextTime() = runBlocking {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("skip-test", 0).also { it.edit().clear().commit() }
        db.library().putWorks(listOf(Work("one", "One", "RJ123456"), Work("two", "Two", "RJ123457")))
        val html = javaClass.getResourceAsStream("/dlsite/example-zh-credits.html")!!.bufferedReader().use { it.readText() }
        var clock = 1_000_000L
        var forbidden = true
        val urls = mutableListOf<String>()
        val starts = mutableListOf<Long>()
        val client = DlsiteClient(prefs, MetadataTransport { url ->
            urls += url; starts += clock
            if (url.endsWith("robots.txt")) HttpPage("User-agent: *\nCrawl-delay: 10")
            else if ("RJ123456" in url && forbidden) HttpPage("", 403) else HttpPage(html)
        }, { clock }, { clock += it })
        val updater = MetadataUpdater(db, client) { clock }
        updater.syncMissing()
        assertEquals(listOf(1_000_000L, 1_010_000L, 1_020_000L), starts)
        assertFalse(db.library().work("one")!!.tagsKnown)
        assertTrue(db.library().work("two")!!.tagsKnown)
        assertEquals(1, db.credits().stats().first().works)
        assertTrue(db.credits().attempt("RJ123456")!!.message.contains("HTTP 403"))
        assertTrue(updater.state.value.message.contains("失败 1"))
        forbidden = false
        updater.syncMissing()
        assertEquals(4, urls.size)
        assertTrue(urls.last().contains("RJ123456"))
        assertTrue(db.library().work("one")!!.tagsKnown)
        assertNull(db.credits().attempt("RJ123456"))
        assertEquals(2, db.credits().stats().first().works)
    }
    @Test fun officialTranslationRedirectKeepsLibraryRjAndRateLimitsEveryHop() = runBlocking {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("translation-test", 0).also { it.edit().clear().commit() }
        val html = javaClass.getResourceAsStream("/dlsite/example-zh-credits.html")!!.bufferedReader().use { it.readText() }
        var clock = 1_000_000L
        val urls = mutableListOf<String>()
        val starts = mutableListOf<Long>()
        val destination = "$DLSITE_ORIGIN/maniax/work/=/product_id/RJ99000003.html/?translation=RJ99000002"
        val client = DlsiteClient(prefs, MetadataTransport { url ->
            urls += url; starts += clock
            when {
                url.endsWith("robots.txt") -> HttpPage("User-agent: *\nCrawl-delay: 10")
                "/RJ99000002.html" in url -> HttpPage("", 302, location = destination)
                else -> HttpPage(html)
            }
        }, { clock }, { clock += it })
        val result = client.fetch("RJ99000002")
        assertEquals("RJ99000002", result.rj)
        assertEquals("$destination&locale=zh_CN", result.sourceUrl)
        assertEquals(result.sourceUrl, urls.last())
        assertEquals(listOf(1_000_000L, 1_010_000L, 1_020_000L), starts)
        assertTrue(result.tagsFetched)
        assertEquals(3, result.creators.count { it.kind == "voice" })
    }
}
