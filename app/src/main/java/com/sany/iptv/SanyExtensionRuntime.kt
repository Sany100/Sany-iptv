package com.sany.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SANY's safe extension contract.
 *
 * Providers are trusted Kotlin modules compiled with the app. CloudStream repository
 * manifests are supported for discovery; arbitrary downloaded .cs3/Dex code is not
 * executed by this first runtime layer.
 */
data class SanyMediaItem(
    val id: String,
    val title: String,
    val url: String,
    val posterUrl: String? = null,
    val type: String = "Other"
)

data class SanyVideoSource(
    val url: String,
    val quality: String = "Auto",
    val language: String = "Unknown",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<String> = emptyList()
)

interface SanyExtensionProvider {
    val id: String
    val name: String
    val version: String
    fun search(query: String): List<SanyMediaItem>
    fun load(url: String): SanyMediaItem?
    fun loadLinks(item: SanyMediaItem): List<SanyVideoSource>
}

object SanyExtensionRegistry {
    private val providers = java.util.concurrent.ConcurrentHashMap<String, SanyExtensionProvider>()

    fun register(provider: SanyExtensionProvider) {
        require(provider.id.isNotBlank()) { "Extension id cannot be empty" }
        providers[provider.id] = provider
    }

    fun unregister(id: String) {
        providers.remove(id)
    }

    fun all(): List<SanyExtensionProvider> = providers.values.sortedBy { it.name.lowercase() }

    fun find(id: String): SanyExtensionProvider? = providers[id]
}

data class CloudStreamPluginInfo(
    val name: String,
    val internalName: String,
    val version: String,
    val description: String,
    val authors: String,
    val language: String,
    val downloadUrl: String
)

data class CloudStreamRepositoryInfo(
    val name: String,
    val description: String,
    val pluginLists: List<String>
)

object SanyExtensionRepositories {
    private const val PREFS = "sany_extension_repositories"
    private const val KEY_URLS = "urls"
    private const val MAX_RESPONSE_BYTES = 1_500_000

    fun all(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URLS, "[]")
            .let { raw -> runCatching { JSONArray(raw) }.getOrElse { JSONArray() } }
            .let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }

    fun add(context: Context, rawUrl: String): Boolean {
        val url = normalizeHttpsUrl(rawUrl) ?: return false
        val urls = all(context).toMutableList()
        if (url !in urls) urls.add(url)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URLS, JSONArray(urls).toString()).apply()
        return true
    }

    fun remove(context: Context, url: String) {
        val urls = all(context).filterNot { it == url }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URLS, JSONArray(urls).toString()).apply()
    }

    fun fetchCatalog(context: Context): List<Pair<String, List<CloudStreamPluginInfo>>> {
        val result = mutableListOf<Pair<String, List<CloudStreamPluginInfo>>>()
        for (repoUrl in all(context)) {
            val manifest = runCatching { JSONObject(readHttps(repoUrl)) }.getOrNull() ?: continue
            val repoName = manifest.optString("name").ifBlank { URL(repoUrl).host }
            val pluginLists = manifest.optJSONArray("pluginLists") ?: JSONArray()
            val plugins = mutableListOf<CloudStreamPluginInfo>()
            for (i in 0 until pluginLists.length()) {
                val listUrl = normalizeHttpsUrl(pluginLists.optString(i)) ?: continue
                val array = runCatching { JSONArray(readHttps(listUrl)) }.getOrNull() ?: continue
                for (j in 0 until array.length()) {
                    val item = array.optJSONObject(j) ?: continue
                    val download = normalizeHttpsUrl(item.optString("url")) ?: continue
                    plugins.add(
                        CloudStreamPluginInfo(
                            name = item.optString("name").ifBlank { item.optString("internalName") },
                            internalName = item.optString("internalName"),
                            version = item.optString("version"),
                            description = item.optString("description"),
                            authors = when (val a = item.opt("authors")) {
                                is JSONArray -> (0 until a.length()).map { a.optString(it) }.joinToString(", ")
                                is String -> a
                                else -> ""
                            },
                            language = item.optString("language"),
                            downloadUrl = download
                        )
                    )
                }
            }
            result.add(repoName to plugins.distinctBy { it.internalName.ifBlank { it.downloadUrl } })
        }
        return result
    }

    fun runtimeNotice(): String =
        "Repository manifest and plugin catalogue discovery are enabled. " +
        "CloudStream .cs3 binary execution is not enabled yet: those extensions target CloudStream's " +
        "specific Kotlin ABI and require a separately verified compatibility layer. " +
        "SANY's safe native provider API is available for built-in extensions."

    private fun normalizeHttpsUrl(raw: String): String? {
        val candidate = raw.trim()
        if (candidate.isBlank() || candidate.length > 2048) return null
        return runCatching {
            val parsed = URL(candidate)
            if (parsed.protocol != "https" || parsed.host.isBlank() ||
                parsed.userInfo != null || parsed.port !in listOf(-1, 443)) null
            else parsed.toString()
        }.getOrNull()
    }

    private fun readHttps(startUrl: String): String {
        var current = normalizeHttpsUrl(startUrl) ?: error("Only HTTPS repository URLs are allowed")
        repeat(4) { redirectCount ->
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SANY-IPTV-Extensions/1.0")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectCount == 3) error("Too many repository redirects")
                    val location = connection.getHeaderField("Location") ?: error("Invalid redirect")
                    current = normalizeHttpsUrl(URL(current, location).toString())
                        ?: error("Repository redirect must use HTTPS")
                    return@repeat
                }
                if (code !in 200..299) error("Repository HTTP $code")
                val input = connection.inputStream
                val bytes = input.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_RESPONSE_BYTES) error("Repository response is too large")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return bytes.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }
        error("Unable to load repository")
    }
}
