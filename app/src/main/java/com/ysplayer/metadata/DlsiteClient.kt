package com.ysplayer.metadata

import android.content.SharedPreferences
import com.ysplayer.data.CreditCreator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

const val DLSITE_ORIGIN = "https://www.dlsite.com"
private val USER_AGENT = "YsPlayerMetadata/" + com.ysplayer.BuildConfig.VERSION_NAME
private const val DAY = 86_400_000L

data class DownloadedTag(val id: String, val name: String)
data class DownloadedCredits(val rj: String, val sourceUrl: String, val creators: List<CreditCreator>, val voiceFieldPresent: Boolean,
                             val tags: List<DownloadedTag> = emptyList(), val tagsFetched: Boolean = true)
fun interface CreditSource { suspend fun fetch(rj: String): DownloadedCredits }

object DlsiteParser {
    fun parse(html: String, rj: String, sourceUrl: String): DownloadedCredits {
        val doc = Jsoup.parse(html, sourceUrl)
        val pageError = doc.select(".error_box_work .title_text, .error_box_work .error_large_text").text()
        if (pageError.isNotBlank()) {
            val restricted = Regex("国家|國家|地域|区域|區域|country|region", RegexOption.IGNORE_CASE).containsMatchIn(pageError)
            throw IllegalArgumentException(if (restricted) "DLsite 返回地区限制页面（HTTP 200）；请检查当前网络出口是否能正常访问该作品" else "DLsite 页面提示：$pageError")
        }
        val circles = linkedMapOf<String, CreditCreator>()
        val voices = linkedMapOf<String, CreditCreator>()
        val tags = linkedMapOf<String, DownloadedTag>()
        var voiceField = false
        var chineseTagField = false
        doc.select("tr").forEach { row ->
            val label = row.selectFirst("th")?.text()?.trim() ?: return@forEach
            if (label in setOf("サークル名", "Circle", "社团名", "社團名")) {
                row.select("td a[href]").forEach { link ->
                    val url = link.absUrl("href")
                    val maker = Regex("/maker_id/(RG\\d+)(?:[./]|$)").find(url)?.groupValues?.get(1)
                    if (officialUrl(url) && maker != null && link.text().isNotBlank()) {
                        circles[maker] = CreditCreator("circle:$maker", "circle", link.text().trim(), url)
                    }
                }
            } else if (label in setOf("声優", "Voice Actor", "Voice actor", "声优", "聲優")) {
                voiceField = true
                row.select("td a[href]").forEach { link ->
                    val url = link.absUrl("href")
                    val name = link.text().trim()
                    if (officialUrl(url) && "/keyword_creater/" in url && name.isNotEmpty()) {
                        val id = "voice:" + UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8))
                        voices[id] = CreditCreator(id, "voice", name, url)
                    }
                }
            } else if (label == "分类" && doc.selectFirst("html")?.attr("lang")?.replace('_', '-')?.equals("zh-CN", true) == true) {
                chineseTagField = true
                row.select("td a[href]").forEach { link ->
                    val url = link.absUrl("href")
                    val id = Regex("/genre/(\\d+)(?:[/?.]|$)").find(url)?.groupValues?.get(1)
                    if (officialUrl(url) && id != null && link.text().isNotBlank()) tags[id] = DownloadedTag(id, link.text().trim())
                }
                require(tags.isNotEmpty() || row.selectFirst("td")?.text().orEmpty().trim() in setOf("", "无", "暂无", "-")) {
                    "中文分类栏解析失败，保留已有标签"
                }
            }
        }
        require(circles.isNotEmpty()) { "未找到社团署名，页面可能已下架、需要验证或结构发生变化" }
        require(!voiceField || voices.isNotEmpty()) { "声优栏解析失败，保留原有信息" }
        return DownloadedCredits(rj, sourceUrl, circles.values.toList() + voices.values, voiceField, tags.values.toList(), chineseTagField)
    }
}

internal fun officialUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host == "www.dlsite.com" && uri.userInfo == null && uri.port in setOf(-1, 443)
}.getOrDefault(false)

/** Follow only public product links. Translation editions explicitly retain the requested RJ. */
internal fun productRedirect(currentUrl: String, location: String, requestedRj: String): String? = runCatching {
    val next = URI(currentUrl).resolve(location).toString()
    if (!officialUrl(next)) return@runCatching null
    val uri = URI(next)
    val product = Regex("/(maniax|home)/work/=/product_id/(RJ\\d{6,10})\\.html/?").matchEntire(uri.path)
        ?: return@runCatching null
    val parameters = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.map {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }.orEmpty()
    if (parameters.any { it.first !in setOf("locale", "translation") } || parameters.map { it.first }.distinct().size != parameters.size) return@runCatching null
    val translation = parameters.firstOrNull { it.first == "translation" }?.second
    if (translation != null && translation != requestedRj) return@runCatching null
    if (product.groupValues[2] != requestedRj && translation != requestedRj) return@runCatching null
    val query = (translation?.let { "translation=$it&" } ?: "") + "locale=zh_CN"
    URI(uri.scheme, uri.authority, uri.path, query, null).toASCIIString()
}.getOrNull()

/** Longest matching rule wins; a specific user-agent group takes precedence over '*'. */
class RobotsPolicy(text: String, userAgent: String) {
    private data class Group(val agents: MutableList<String> = mutableListOf(),
                             val rules: MutableList<Pair<Boolean, String>> = mutableListOf(), var delay: Long = 10_000)
    private val rules: List<Pair<Boolean, String>>
    val delayMillis: Long
    init {
        val groups = mutableListOf<Group>()
        var group = Group()
        var directives = false
        text.lineSequence().forEach { line ->
            val clean = line.substringBefore('#')
            if (':' !in clean) return@forEach
            val key = clean.substringBefore(':').trim().lowercase(Locale.ROOT)
            val value = clean.substringAfter(':').trim()
            if (key == "user-agent") {
                if (directives) { groups += group; group = Group(); directives = false }
                group.agents += value.lowercase(Locale.ROOT)
            } else if (group.agents.isNotEmpty() && key in setOf("allow", "disallow", "crawl-delay")) {
                directives = true
                if (key == "crawl-delay") {
                    value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let {
                        group.delay = maxOf(10_000, (it * 1000).toLong())
                    }
                } else if (value.isNotEmpty()) group.rules += (key == "allow") to value
            }
        }
        groups += group
        val scored = groups.map { g -> g to (g.agents.filter { it == "*" || userAgent.lowercase(Locale.ROOT).contains(it) }
            .maxOfOrNull { if (it == "*") 0 else it.length } ?: -1) }
        val best = scored.maxOfOrNull { it.second } ?: -1
        val chosen = scored.filter { it.second == best && best >= 0 }.map { it.first }
        rules = chosen.flatMap { it.rules }
        delayMillis = chosen.maxOfOrNull { it.delay } ?: 10_000
    }
    fun allows(url: String): Boolean {
        val uri = URI(url)
        val target = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        return rules.filter { (_, pattern) ->
            val end = pattern.endsWith('$')
            val body = if (end) pattern.dropLast(1) else pattern
            Regex("^" + body.split('*').joinToString(".*") { Regex.escape(it) } + if (end) "$" else "").containsMatchIn(target)
        }.maxWithOrNull(compareBy<Pair<Boolean, String>> { it.second.trimEnd('$').replace("*", "").length }.thenBy { it.first })?.first ?: true
    }
}

class MetadataAccessException(message: String, val retryAt: Long, val status: Int = 0) : IOException(message)
data class HttpPage(val text: String, val status: Int = 200, val retryAfter: String? = null, val location: String? = null)
fun interface MetadataTransport { suspend fun get(url: String): HttpPage }

class DlsiteHttpTransport : MetadataTransport {
    override suspend fun get(url: String): HttpPage = withContext(Dispatchers.IO) {
        run {
            require(officialUrl(url)) { "资料来源地址异常" }
            currentCoroutineContext().ensureActive()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,ja;q=0.5")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    // Redirects are handled by the rate-limited client, never by the transport.
                    return@withContext HttpPage("", status, connection.getHeaderField("Retry-After"), connection.getHeaderField("Location"))
                } else {
                    if (status != 200) return@withContext HttpPage("", status, connection.getHeaderField("Retry-After"))
                    val data = java.io.ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(data.size() + count <= 2_000_000) { "作品页面过大" }
                            data.write(buffer, 0, count)
                        }
                    }
                    return@withContext HttpPage(data.toString("UTF-8"))
                }
            } finally { connection.disconnect() }
        }
        error("无法读取作品页面")
    }
}

/** A single app-wide client gates every request, including robots.txt. State survives restarts. */
class DlsiteClient(
    private val preferences: SharedPreferences,
    private val transport: MetadataTransport = DlsiteHttpTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) : CreditSource {
    private val mutex = Mutex()
    init { preferences.edit().remove("blockedUntil").apply() }
    override suspend fun fetch(rj: String): DownloadedCredits = mutex.withLock {
        require(Regex("RJ\\d{6,10}").matches(rj)) { "RJ 编号无效" }
        var robots = preferences.getString("robots", null)
        if (robots == null || now() - preferences.getLong("robotsAt", 0) >= DAY) {
            robots = request("$DLSITE_ORIGIN/robots.txt", 10_000).first
            preferences.edit().putString("robots", robots).putLong("robotsAt", now()).commit()
        }
        val policy = RobotsPolicy(robots, USER_AGENT)
        val url = "$DLSITE_ORIGIN/maniax/work/=/product_id/$rj.html?locale=zh_CN"
        val page = try { request(url, policy.delayMillis, policy) }
        catch (e: MetadataAccessException) {
            if (e.status != 404) throw e
            request("$DLSITE_ORIGIN/home/work/=/product_id/$rj.html?locale=zh_CN", policy.delayMillis, policy)
        }
        DlsiteParser.parse(page.first, rj, page.second).also {
            require(it.tagsFetched) { "未取得 DLsite 简体中文分类栏，未写入日文标签；可重新补全" }
        }
    }
    private suspend fun request(url: String, interval: Long, policy: RobotsPolicy? = null): Pair<String, String> {
        var target = url
        repeat(3) {
            if (policy != null && !policy.allows(target)) throw MetadataAccessException("站点不允许查询此作品页面", 0)
            val previous = preferences.getLong("lastRequest", 0)
            // Clock changes can only increase the wait up to one interval, never cause a request burst.
            if (previous > 0) (interval - (now() - previous).coerceAtLeast(0)).takeIf { it > 0 }?.let { sleep(it) }
            currentCoroutineContext().ensureActive()
            preferences.edit().putLong("lastRequest", now()).commit()
            val response = try { transport.get(target) } catch (e: CancellationException) { throw e }
            catch (e: IOException) {
                throw MetadataAccessException("${e.javaClass.simpleName}：${e.message ?: "网络暂不可用"}；可重新补全", 0)
            }
            if (response.status in setOf(301, 302, 303, 307, 308) && response.location != null && policy != null) {
                val rj = Regex("/product_id/(RJ\\d+)\\.html").find(url)?.groupValues?.get(1)
                val next = rj?.let { productRedirect(target, response.location, it) }
                if (next != null) {
                    target = next
                    return@repeat
                }
                val retry = parseRetryAfter(response.retryAfter)
                throw MetadataAccessException("DLsite HTTP ${response.status}：跳转到非关联作品、登录或验证页面；未继续访问", retry, response.status)
            }
            if (response.status != 200) {
                val retry = parseRetryAfter(response.retryAfter)
                val hint = response.retryAfter?.let { "；服务器 Retry-After: $it" }.orEmpty()
                throw MetadataAccessException("DLsite HTTP ${response.status}$hint；可重新补全", retry, response.status)
            }
            return response.text to target
        }
        throw MetadataAccessException("作品页面多次跳转，未继续访问此作品，可重新补全", 0)
    }
    private fun parseRetryAfter(value: String?): Long {
        if (value == null) return 0
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { return now() + it.coerceAtMost(604800) * 1000 }
        return runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(value)?.time ?: 0 }.getOrDefault(0)
    }
}
