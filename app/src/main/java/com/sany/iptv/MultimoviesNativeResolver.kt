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

    private fun get(url: String): String? {
        val c = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "text/html,application/json")
        return try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    fun search(title: String, series: Boolean): List<MultimoviesHit> {
        val q = URLEncoder.encode(title.trim(), "UTF-8")
        val expected = if (series) "tv" else "movie"
        val jsonText = get("$BASE/api/search?q=$q&limit=12")
        val hits = mutableListOf<MultimoviesHit>()
        if (!jsonText.isNullOrBlank()) try {
            val arr = JSONObject(jsonText).optJSONArray("results")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type").ifBlank { if (o.has("name")) "tv" else "movie" }
                val name = o.optString("title").ifBlank { o.optString("name") }.trim()
                val link = o.optString("url").trim()
                if (name.isNotBlank() && link.startsWith("http") &&
                    (type == expected || (series && type == "series"))) {
                    hits += MultimoviesHit(name, link, expected)
                }
            }
        } catch (_: Exception) { }
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
        return hits.distinctBy { it.url }.take(8)
    }

    fun sources(detailUrl: String): List<MultimoviesSource> {
        if (!detailUrl.startsWith(BASE)) return emptyList()
        val html = get(detailUrl) ?: return emptyList()
        val candidates = linkedMapOf<String, String>()
        val config = extractWatchConfig(html)
        if (config != null) try {
            val servers = JSONObject(config).optJSONArray("initialServers")
            if (servers != null) for (i in 0 until servers.length()) {
                val o = servers.optJSONObject(i) ?: continue
                val link = absolute(o.optString("url").replace("\\/", "/").trim())
                if (link.startsWith("http")) candidates[o.optString("name").ifBlank { "Server ${i + 1}" }] = link
            }
        } catch (_: Exception) { }
        val chips = Regex("(?is)<button[^>]*class=[\"'][^\"']*server-chip[^\"']*[\"'][^>]*>")
        for (m in chips.findAll(html)) {
            val tag = m.value
            val raw = Regex("(?is)data-url=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1) ?: continue
            val name = Regex("(?is)data-id=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1) ?: "Server"
            candidates[name] = absolute(raw.replace("&amp;", "&"))
        }
        return candidates.map { (name, link) ->
            val direct = directMedia(link)
            MultimoviesSource(name, direct ?: link, direct != null)
        }
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
