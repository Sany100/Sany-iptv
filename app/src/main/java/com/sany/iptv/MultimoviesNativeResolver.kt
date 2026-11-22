package com.sany.iptv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MultimoviesHit(val title: String, val url: String, val type: String)
data class MultimoviesSource(val name: String, val url: String, val playable: Boolean)

object MultimoviesNativeResolver {
    private const val BASE = "https://multimovies.makeup"
    private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private val searchCache = mutableMapOf<String, Pair<Long, List<MultimoviesHit>>>()
    private val sourceCache = mutableMapOf<String, Pair<Long, List<MultimoviesSource>>>()

    private fun <T> cacheRead(cache: MutableMap<String, Pair<Long, T>>, key: String): T? =
        synchronized(cache) {
            val entry = cache[key] ?: return@synchronized null
            if (System.currentTimeMillis() - entry.first > CACHE_TTL_MS) {
                cache.remove(key)
                null
            } else entry.second
        }

    private fun <T> cacheWrite(cache: MutableMap<String, Pair<Long, T>>, key: String, value: T) {
        synchronized(cache) {
            if (cache.size >= 60) cache.clear()
            cache[key] = System.currentTimeMillis() to value
        }
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "http" || url.protocol == "https"
    }.getOrDefault(false)

    private fun get(url: String, referer: String? = null): String? {
        val c = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "text/html,application/json")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
        return try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    fun search(title: String, series: Boolean): List<MultimoviesHit> {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return emptyList()
        val expected = if (series) "tv" else "movie"
        val cacheKey = expected + ":" + cleanTitle.lowercase()
        cacheRead(searchCache, cacheKey)?.let { return it }
        val q = URLEncoder.encode(cleanTitle, "UTF-8")
        val jsonText = get("$BASE/api/search?q=$q&limit=12")
        val hits = mutableListOf<MultimoviesHit>()
        if (!jsonText.isNullOrBlank()) try {
            val arr = JSONObject(jsonText).optJSONArray("results")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val rawType = o.optString("type").ifBlank { if (o.has("name")) "tv" else "movie" }.lowercase()
                val type = if (rawType == "series" || rawType == "show") "tv" else rawType
                val name = o.optString("title").ifBlank { o.optString("name") }.trim()
                val rawLink = o.optString("url").ifBlank { o.optString("link") }.ifBlank { o.optString("permalink") }.trim()
                val link = if (rawLink.isBlank()) "" else absolute(rawLink)
                if (name.isNotBlank() && rawLink.isNotBlank() && isHttpUrl(link) &&
                    (type == expected || (series && type == "tv"))) {
                    hits += MultimoviesHit(name, link, expected)
                }
            }
        } catch (_: Exception) { }
        if (hits.isEmpty()) {
            // Public Dooplay search: consume only the nonce published in normal
            // homepage HTML. No challenge solving or access-control bypass.
            val homepage = get("$BASE/").orEmpty()
            val nonceBlock = Regex("""dtGonza\s*=\s*\{[^}]*\}""").find(homepage)?.value
            val nonce = nonceBlock?.let {
                Regex(""""nonce"\s*:\s*"([A-Za-z0-9]+)"""").find(it)?.groupValues?.get(1)
            }
            if (!nonce.isNullOrBlank()) {
                val dooplay = get(
                    "$BASE/wp-json/dooplay/search/?keyword=$q&nonce=$nonce",
                    "$BASE/"
                )
                if (!dooplay.isNullOrBlank()) try {
                    val root = JSONObject(dooplay)
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val o = root.optJSONObject(keys.next()) ?: continue
                        val name = o.optString("title").trim()
                        val rawLink = o.optString("url").trim()
                        if (name.isBlank() || rawLink.isBlank()) continue
                        val link = absolute(rawLink)
                        val hostMatches = runCatching {
                            URL(link).host.equals(URL(BASE).host, ignoreCase = true)
                        }.getOrDefault(false)
                        if (!hostMatches) continue
                        val kind = if (link.contains("/series/") || link.contains("/tvshows/") || link.contains("/tv/")) "tv" else "movie"
                        if (kind != expected) continue
                        hits += MultimoviesHit(name, link, expected)
                    }
                } catch (_: Exception) { }
            }
        }
        if (hits.isEmpty()) {
            val html = get("$BASE/search?q=$q").orEmpty()
            val article = Regex("(?is)<article\\b[^>]*>(.*?)</article>")
            val href = Regex("(?is)<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>")
            val img = Regex("(?is)<img[^>]+alt=[\"']([^\"']+)[\"']")
            for (m in article.findAll(html)) {
                val block = m.groupValues[1]
                val link = href.find(block)?.groupValues?.get(1)?.let { absolute(it) } ?: continue
                if (!link.startsWith(BASE)) continue
                val kind = if (link.contains("/series/") || link.contains("/tvshows/")) "tv" else "movie"
                if (kind != expected) continue
                val name = img.find(block)?.groupValues?.get(1)?.replace("&amp;", "&")?.trim().orEmpty()
                    .ifBlank { Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(block)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty() }
                if (name.isNotBlank()) hits += MultimoviesHit(name, link, kind)
            }
        }
        val result = hits.distinctBy { it.url }.take(8)
        cacheWrite(searchCache, cacheKey, result)
        return result
    }

    fun sources(detailUrl: String): List<MultimoviesSource> {
        val isProviderDetail = runCatching {
            URL(detailUrl).host.equals(URL(BASE).host, ignoreCase = true)
        }.getOrDefault(false)
        if (!isHttpUrl(detailUrl) || !isProviderDetail) return emptyList()
        cacheRead(sourceCache, detailUrl)?.let { return it }
        val html = get(detailUrl) ?: return emptyList()
        val candidates = linkedMapOf<String, String>()
        val config = extractWatchConfig(html)
        if (config != null) try {
            val servers = JSONObject(config).optJSONArray("initialServers")
            if (servers != null) for (i in 0 until servers.length()) {
                val o = servers.optJSONObject(i) ?: continue
                val link = absolute(o.optString("url").replace("\\/", "/").trim())
                if (isHttpUrl(link)) candidates[o.optString("name").ifBlank { "Server " + (i + 1) }] = link
            }
        } catch (_: Exception) { }
        val chips = Regex("(?is)<button[^>]*class=[\"'][^\"']*server-chip[^\"']*[\"'][^>]*>")
        for (m in chips.findAll(html)) {
            val tag = m.value
            val raw = Regex("(?is)data-url=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1) ?: continue
            val name = Regex("(?is)data-id=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1) ?: "Server"
            val link = absolute(raw.replace("&amp;", "&"))
            if (isHttpUrl(link)) candidates[name] = link
        }
        val result = candidates.map { (name, link) ->
            val direct = if (isMedia(link)) link else directMedia(link)
            MultimoviesSource(name, direct ?: link, direct != null)
        }
        cacheWrite(sourceCache, detailUrl, result)
        return result
    }

    private fun extractWatchConfig(html: String): String? {
        val marker = Regex("(?is)watchConfig\\s*=\\s*").find(html) ?: return null
        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until html.length) {
            val ch = html[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else {
                when (ch) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun directMedia(link: String): String? {
        if (isMedia(link)) return link
        // Only inspect openly exposed HTML media declarations; no challenge solving,
        // script execution, obfuscation decoding, iframe crawling, or DRM handling.
        val html = get(link) ?: return null
        val patterns = listOf(
            Regex("(?is)<source[^>]+src=[\"']([^\"']+)[\"']"),
            Regex("(?is)[\"']file[\"']\\s*:\\s*[\"']([^\"']+)[\"']"),
            Regex("(?is)[\"']src[\"']\\s*:\\s*[\"'](https?://[^\\\"']+)[\"']")
        )
        for (pattern in patterns) {
            val raw = pattern.find(html)?.groupValues?.get(1) ?: continue
            val resolved = absolute(raw.replace("\\/","/").replace("&amp;","&"))
            if (isMedia(resolved)) return resolved
        }
        return null
    }

    private fun isMedia(url: String): Boolean =
        Regex("(?i)\\.(m3u8|mp4)(?:$|[?#])").containsMatchIn(url)

    private fun absolute(path: String): String = when {
        path.startsWith("//") -> "https:$path"
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> BASE + path
        else -> BASE + "/" + path
    }
}
