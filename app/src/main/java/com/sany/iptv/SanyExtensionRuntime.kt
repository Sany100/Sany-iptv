package com.sany.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * SANY native extension contract. Built-in providers can implement this API
 * without depending on CloudStream's Android application classes.
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
    private val providers = ConcurrentHashMap<String, SanyExtensionProvider>()

    fun register(provider: SanyExtensionProvider) {
        require(provider.id.isNotBlank()) { "Extension id cannot be empty" }
        providers[provider.id] = provider
    }

    fun unregister(id: String) { providers.remove(id) }
    fun all(): List<SanyExtensionProvider> = providers.values.sortedBy { it.name.lowercase() }
    fun find(id: String): SanyExtensionProvider? = providers[id]
}

data class CloudStreamPluginInfo(
    val name: String,
    val internalName: String,
    val version: Int,
    val apiVersion: Int,
    val status: Int,
    val description: String,
    val authors: String,
    val language: String,
    val tvTypes: List<String>,
    val fileSize: Long?,
    val fileHash: String?,
    val downloadUrl: String
)

data class CloudStreamPluginManifest(
    val pluginClassName: String,
    val name: String,
    val version: Int,
    val requiresResources: Boolean
)

data class CloudStreamInstalledPackage(
    val file: File,
    val manifest: CloudStreamPluginManifest,
    val sha256: String
)

object SanyExtensionRepositories {
    private const val PREFS = "sany_extension_repositories"
    private const val KEY_URLS = "urls"
    private const val MAX_JSON_BYTES = 1_500_000
    private const val MAX_PLUGIN_BYTES = 80L * 1024L * 1024L
    private const val SUPPORTED_REPOSITORY_MANIFEST = 1
    private const val SUPPORTED_PLUGIN_API = 1

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
            val manifest = runCatching { JSONObject(readHttps(repoUrl, MAX_JSON_BYTES)) }.getOrNull() ?: continue
            val manifestVersion = manifest.optInt("manifestVersion", 1)
            if (manifestVersion > SUPPORTED_REPOSITORY_MANIFEST) continue
            val repoName = manifest.optString("name").ifBlank { URL(repoUrl).host }
            val pluginLists = manifest.optJSONArray("pluginLists") ?: JSONArray()
            val plugins = mutableListOf<CloudStreamPluginInfo>()
            for (i in 0 until pluginLists.length()) {
                val listUrl = resolveHttps(repoUrl, pluginLists.optString(i)) ?: continue
                val array = runCatching { JSONArray(readHttps(listUrl, MAX_JSON_BYTES)) }.getOrNull() ?: continue
                for (j in 0 until array.length()) {
                    val item = array.optJSONObject(j) ?: continue
                    val download = resolveHttps(listUrl, item.optString("url")) ?: continue
                    val authors = item.optJSONArray("authors")
                    val tvTypes = item.optJSONArray("tvTypes")
                    plugins.add(
                        CloudStreamPluginInfo(
                            name = item.optString("name").ifBlank { item.optString("internalName") },
                            internalName = item.optString("internalName"),
                            version = item.optInt("version", 0),
                            apiVersion = item.optInt("apiVersion", 1),
                            status = item.optInt("status", 1),
                            description = item.optString("description"),
                            authors = authors?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }
                                ?: item.optString("authors"),
                            language = item.optString("language"),
                            tvTypes = tvTypes?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                            fileSize = if (item.has("fileSize") && !item.isNull("fileSize")) item.optLong("fileSize") else null,
                            fileHash = item.optString("fileHash").takeIf { it.isNotBlank() },
                            downloadUrl = download
                        )
                    )
                }
            }
            result.add(repoName to plugins.distinctBy { it.internalName.ifBlank { it.downloadUrl } })
        }
        return result
    }

    /**
     * Downloads and verifies a CloudStream .cs3 ZIP package. The package is kept
     * in app-private storage. This method deliberately does NOT execute its DEX:
     * CloudStream's MainAPI/BasePlugin ABI and app services are not yet embedded
     * in SANY, so attempting to load it would cause linkage errors or crashes.
     */
    fun downloadAndValidate(context: Context, plugin: CloudStreamPluginInfo): CloudStreamInstalledPackage {
        require(plugin.status != 0) { "This provider is marked offline by its repository" }
        require(plugin.apiVersion <= SUPPORTED_PLUGIN_API) {
            "Unsupported CloudStream plugin API version: ${plugin.apiVersion}"
        }
        require(plugin.internalName.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "Invalid plugin internalName"
        }
        require(plugin.fileSize == null || plugin.fileSize in 1..MAX_PLUGIN_BYTES) {
            "Plugin package size is outside the allowed limit"
        }

        val root = File(context.filesDir, "cloudstream_extensions/pending").apply { mkdirs() }
        val destination = File(root, "${plugin.internalName}.cs3")
        val temporary = File(root, "${plugin.internalName}.download")
        try {
            downloadHttps(plugin.downloadUrl, temporary)
            require(temporary.length() in 1..MAX_PLUGIN_BYTES) { "Invalid plugin file size" }
            if (plugin.fileSize != null) require(temporary.length() == plugin.fileSize) {
                "Plugin file size does not match repository metadata"
            }
            val hash = sha256(temporary)
            val expected = plugin.fileHash
            if (!expected.isNullOrBlank()) {
                val normalizedExpected = expected.removePrefix("sha256-").lowercase()
                require(hash.equals(normalizedExpected, ignoreCase = true)) {
                    "Plugin SHA-256 verification failed"
                }
            }

            val manifest = validatePackage(temporary)
            require(manifest.version == plugin.version || plugin.version <= 0) {
                "Plugin version does not match repository metadata"
            }
            require(manifest.name.isNotBlank()) { "Plugin manifest has no name" }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Unable to save verified plugin package" }
            return CloudStreamInstalledPackage(destination, manifest, hash)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun installedPackages(context: Context): List<File> =
        File(context.filesDir, "cloudstream_extensions/pending")
            .listFiles { file -> file.isFile && file.extension.equals("cs3", true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun removeInstalledPackage(context: Context, fileName: String): Boolean {
        if (!fileName.matches(Regex("[A-Za-z0-9_.-]{1,110}\\.cs3"))) return false
        val root = File(context.filesDir, "cloudstream_extensions/pending").canonicalFile
        val target = File(root, fileName).canonicalFile
        if (target.parentFile != root) return false
        return !target.exists() || target.delete()
    }

    fun runtimeNotice(): String =
        "Repository discovery, package download, SHA-256 verification and .cs3 manifest/DEX validation are supported. " +
        "DEX execution is intentionally disabled until SANY embeds a matching CloudStream MainAPI/BasePlugin ABI, " +
        "ExtractorApi services and lifecycle. CloudStream plugins are trusted executable code and must not be " +
        "loaded from unknown repositories."

    private fun validatePackage(file: File): CloudStreamPluginManifest {
        ZipFile(file).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json") ?: error("Missing manifest.json")
            val dexEntry = zip.getEntry("classes.dex") ?: error("Missing classes.dex")
            require(!manifestEntry.isDirectory && !dexEntry.isDirectory) { "Invalid plugin archive" }
            require(manifestEntry.size in 1..64_000) { "Invalid plugin manifest size" }
            val json = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val obj = JSONObject(json)
            val className = obj.optString("pluginClassName")
            require(className.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) {
                "Invalid pluginClassName in manifest"
            }
            return CloudStreamPluginManifest(
                pluginClassName = className,
                name = obj.optString("name"),
                version = obj.optInt("version", 0),
                requiresResources = obj.optBoolean("requiresResources", false)
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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

    private fun resolveHttps(base: String, raw: String): String? {
        if (raw.isBlank()) return null
        return normalizeHttpsUrl(runCatching { URL(URL(base), raw).toString() }.getOrDefault(raw))
    }

    private fun openHttps(startUrl: String, maxRedirects: Int = 4): HttpURLConnection {
        var current = normalizeHttpsUrl(startUrl) ?: error("Only HTTPS URLs are allowed")
        repeat(maxRedirects + 1) { index ->
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SANY-IPTV-Extensions/1.0")
                setRequestProperty("Accept", "application/json, application/zip, application/octet-stream")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (index == maxRedirects || location.isNullOrBlank()) error("Invalid repository redirect")
                current = normalizeHttpsUrl(URL(current, location).toString())
                    ?: error("Redirect must remain on HTTPS")
            } else {
                if (code !in 200..299) {
                    connection.disconnect()
                    error("Repository HTTP $code")
                }
                return connection
            }
        }
        error("Too many HTTPS redirects")
    }

    private fun readHttps(url: String, maxBytes: Int): String {
        val connection = openHttps(url)
        try {
            val declared = connection.contentLengthLong
            if (declared > maxBytes) error("Repository response is too large")
            return connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) error("Repository response is too large")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadHttps(url: String, destination: File) {
        val connection = openHttps(url)
        try {
            val declared = connection.contentLengthLong
            if (declared > MAX_PLUGIN_BYTES) error("Plugin package is too large")
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_PLUGIN_BYTES) error("Plugin package exceeded size limit")
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
