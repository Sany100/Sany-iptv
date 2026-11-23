package com.sany.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Movie-only extension catalogue and installer.
 *
 * This manager deliberately uses its own preference namespace and repository
 * index; it never reads or changes the Anime extension allowlist.
 *
 * Repository index format:
 * {
 *   "schema": 1,
 *   "extensions": [{
 *     "id": "provider-id",
 *     "name": "Provider name",
 *     "version": "1.0.0",
 *     "description": "...",
 *     "packageUrl": "https://.../provider.cs3",
 *     "sha256": "<64 lowercase hex chars>"
 *   }]
 * }
 */
object SanyMovieExtensionManager {
    private const val PREFS = "sany_movie_extensions"
    private const val KEY_REPOSITORIES = "repositories"
    private const val KEY_INSTALLED = "installed"
    private const val KEY_DEFAULT_REPOSITORY_SEEDED = "default_repository_seeded"
    private const val DEFAULT_MOVIE_REPOSITORY = "https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
    private const val MAX_PACKAGE_BYTES = 80 * 1024 * 1024

    data class Repository(val url: String, val name: String = url)
    data class Extension(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val packageUrl: String,
        val sha256: String,
        val repositoryUrl: String
    )
    data class Installed(
        val id: String,
        val name: String,
        val version: String,
        val packagePath: String,
        val sha256: String
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun repositories(context: Context): List<Repository> {
        val store = prefs(context)
        val array = JSONArray(store.getString(KEY_REPOSITORIES, "[]") ?: "[]")
        if (array.length() == 0 && !store.getBoolean(KEY_DEFAULT_REPOSITORY_SEEDED, false)) {
            array.put(JSONObject().put("url", DEFAULT_MOVIE_REPOSITORY).put("name", "Indflix"))
            store.edit().putString(KEY_REPOSITORIES, array.toString())
                .putBoolean(KEY_DEFAULT_REPOSITORY_SEEDED, true).commit()
        }
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("url").trim()
            if (!isHttps(url)) null else Repository(url, item.optString("name", url))
        }
    }

    @Synchronized
    fun addRepository(context: Context, indexUrl: String, displayName: String = indexUrl) {
        val normalizedUrl = normalizeRepositoryUrl(indexUrl)
        require(isHttps(normalizedUrl)) { "Repository index must use HTTPS" }
        val current = repositories(context).filterNot { it.url.equals(normalizedUrl, true) }
        val updated = current + Repository(normalizedUrl, displayName.takeIf { it != indexUrl } ?: normalizedUrl)
        val array = JSONArray()
        updated.forEach { array.put(JSONObject().put("url", it.url).put("name", it.name)) }
        check(prefs(context).edit().putString(KEY_REPOSITORIES, array.toString()).commit()) {
            "Could not save Movie repository"
        }
    }

    @Synchronized
    fun removeRepository(context: Context, indexUrl: String) {
        val array = JSONArray()
        repositories(context).filterNot { it.url == indexUrl }.forEach {
            array.put(JSONObject().put("url", it.url).put("name", it.name))
        }
        check(prefs(context).edit().putString(KEY_REPOSITORIES, array.toString()).commit()) {
            "Could not update Movie repositories"
        }
    }

    fun installed(context: Context): List<Installed> {
        val array = JSONArray(prefs(context).getString(KEY_INSTALLED, "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val path = item.optString("packagePath")
            if (path.isBlank() || !File(path).isFile) null else Installed(
                item.optString("id"), item.optString("name"), item.optString("version"),
                path, item.optString("sha256")
            )
        }
    }

    /** Reloads previously installed Movie packages after an app restart. */
    fun loadInstalled(context: Context): List<SanyCloudStreamPluginRuntime.LoadResult> =
        installed(context).mapNotNull { item ->
            val packageFile = File(item.packagePath).canonicalFile
            val expected = File(context.filesDir, "cloudstream_extensions/pending").canonicalFile
            if (packageFile.parentFile != expected || !packageFile.isFile) null
            else SanyCloudStreamPluginRuntime.load(context, packageFile)
        }

    /** Reads SANY schema-1 indexes and native CloudStream manifest-v1 repositories. */
    fun catalogue(context: Context): List<Extension> {
        val result = LinkedHashMap<String, Extension>()
        repositories(context).forEach { repository ->
            val document = downloadText(repository.url, MAX_INDEX_BYTES).trim()
            if (document.startsWith("[")) {
                parseCloudStreamPluginList(JSONArray(document), repository.url, result)
                return@forEach
            }
            val root = JSONObject(document)
            if (root.optInt("schema") == 1) {
                val entries = root.optJSONArray("extensions") ?: JSONArray()
                for (i in 0 until entries.length()) {
                    val item = entries.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    val version = item.optString("version").trim()
                    val packageUrl = item.optString("packageUrl").trim()
                    val hash = item.optString("sha256").trim().lowercase()
                    if (!id.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}"))) continue
                    if (name.isBlank() || version.isBlank() || !isHttps(packageUrl)) continue
                    if (!hash.matches(Regex("[a-f0-9]{64}"))) continue
                    result[id] = Extension(id, name, version, item.optString("description"), packageUrl, hash, repository.url)
                }
            } else if (root.optInt("manifestVersion") == 1) {
                val pluginLists = root.optJSONArray("pluginLists") ?: JSONArray()
                for (i in 0 until pluginLists.length()) {
                    val listUrl = pluginLists.optString(i).trim()
                    require(isHttps(listUrl)) { "CloudStream plugin list must use HTTPS: $listUrl" }
                    parseCloudStreamPluginList(JSONArray(downloadText(listUrl, MAX_INDEX_BYTES)), repository.url, result)
                }
            } else {
                error("Unsupported Movie repository format: ${repository.url}")
            }
        }
        return result.values.toList()
    }

    private fun parseCloudStreamPluginList(
        entries: JSONArray,
        repositoryUrl: String,
        result: LinkedHashMap<String, Extension>
    ) {
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optInt("status", 1) == 0) continue
            val types = item.optJSONArray("tvTypes") ?: JSONArray()
            if ((0 until types.length()).none { types.optString(it).equals("Movie", true) }) continue
            val name = item.optString("name").trim()
            val internalName = item.optString("internalName").trim().ifBlank { name }
            val id = internalName.lowercase().replace(Regex("[^a-z0-9._-]"), "-")
                .trim('-', '.', '_').take(64)
            val packageUrl = item.optString("url").trim()
            val version = item.optString("version").trim()
            val rawHash = item.optString("fileHash").trim().lowercase()
            val hash = rawHash.removePrefix("sha256-")
            if (id.length < 2 || name.isBlank() || version.isBlank()) continue
            if (!isHttps(packageUrl) || !hash.matches(Regex("[a-f0-9]{64}"))) continue
            result[id] = Extension(
                id, name, version, item.optString("description"),
                packageUrl, hash, repositoryUrl
            )
        }
    }

    private fun normalizeRepositoryUrl(value: String): String {
        val trimmed = value.trim()
        val normalized = if (trimmed.startsWith("cloudstreamrepo://", true)) {
            "https://" + trimmed.substringAfter("://")
        } else trimmed
        return normalized
    }

    /**
     * Downloads to the exact private directory required by SanyCloudStreamPluginRuntime,
     * verifies SHA-256 before loading, and atomically records the installed package.
     */
    @Synchronized
    fun installOrUpdate(context: Context, extension: Extension): SanyCloudStreamPluginRuntime.LoadResult {
        require(isHttps(extension.packageUrl)) { "Movie package URL must use HTTPS" }
        require(extension.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid package checksum" }

        val directory = File(context.filesDir, "cloudstream_extensions/pending").canonicalFile
        check(directory.exists() || directory.mkdirs()) { "Could not create extension storage" }
        val safeId = extension.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val target = File(directory, "movie_$safeId.cs3").canonicalFile
        require(target.parentFile == directory) { "Invalid extension destination" }
        val temporary = File(directory, "movie_$safeId.cs3.download")
        if (temporary.exists()) temporary.delete()

        try {
            downloadToFile(extension.packageUrl, temporary, MAX_PACKAGE_BYTES)
            val actualHash = sha256(temporary)
            require(actualHash == extension.sha256) { "Movie extension checksum mismatch" }
            val old = installed(context).firstOrNull { it.id == extension.id }
            val backup = File(directory, "movie_$safeId.cs3.backup")
            if (backup.exists()) backup.delete()
            if (old != null) SanyCloudStreamPluginRuntime.unload(File(old.packagePath))
            if (target.exists()) check(target.renameTo(backup)) { "Could not preserve previous Movie extension" }
            check(temporary.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                "Could not finalize Movie extension package"
            }

            val loaded = SanyCloudStreamPluginRuntime.load(context, target)
            if (!loaded.success) {
                target.delete()
                if (backup.exists()) {
                    check(backup.renameTo(target)) { "Could not restore previous Movie extension" }
                    SanyCloudStreamPluginRuntime.load(context, target)
                }
                return loaded
            }
            if (backup.exists()) backup.delete()
            val remaining = installed(context).filterNot { it.id == extension.id }
            val array = JSONArray()
            (remaining + Installed(
                extension.id, extension.name, extension.version, target.absolutePath, actualHash
            )).forEach { item ->
                array.put(JSONObject()
                    .put("id", item.id).put("name", item.name).put("version", item.version)
                    .put("packagePath", item.packagePath).put("sha256", item.sha256))
            }
            check(prefs(context).edit().putString(KEY_INSTALLED, array.toString()).commit()) {
                "Extension loaded but install record could not be saved"
            }
            return loaded
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    @Synchronized
    fun uninstall(context: Context, extensionId: String): Boolean {
        val item = installed(context).firstOrNull { it.id == extensionId } ?: return false
        val packageFile = File(item.packagePath).canonicalFile
        val expected = File(context.filesDir, "cloudstream_extensions/pending").canonicalFile
        require(packageFile.parentFile == expected) { "Refusing to remove a file outside Movie extension storage" }
        SanyCloudStreamPluginRuntime.unload(packageFile)
        val deleted = !packageFile.exists() || packageFile.delete()
        if (!deleted) return false
        val array = JSONArray()
        installed(context).filterNot { it.id == extensionId }.forEach {
            array.put(JSONObject()
                .put("id", it.id).put("name", it.name).put("version", it.version)
                .put("packagePath", it.packagePath).put("sha256", it.sha256))
        }
        prefs(context).edit().putString(KEY_INSTALLED, array.toString()).apply()
        return true
    }

    private fun isHttps(value: String): Boolean =
        runCatching { URL(value).protocol.equals("https", true) }.getOrDefault(false)

    private fun downloadText(url: String, maxBytes: Int): String {
        val connection = openHttpsConnection(url).apply {
            setRequestProperty("Accept", "application/json")
        }
        try {
            require(connection.responseCode in 200..299) { "Repository HTTP ${connection.responseCode}: $url" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Repository index is too large" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToFile(url: String, destination: File, maxBytes: Int) {
        val connection = openHttpsConnection(url)
        try {
            require(connection.responseCode in 200..299) { "Package HTTP ${connection.responseCode}" }
            var total = 0L
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "Movie extension package is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(total > 0) { "Empty Movie extension package" }
        } finally {
            connection.disconnect()
        }
    }

    /** Follow a small number of HTTPS-only redirects (e.g. GitHub release assets). */
    private fun openHttpsConnection(address: String): HttpURLConnection {
        var current = URL(address)
        repeat(5) {
            require(current.protocol.equals("https", true)) { "Only HTTPS redirects are allowed" }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "Repository redirect has no Location header" }
                current = URL(current, location)
            } else {
                require(status in 200..299) { "Movie repository HTTP $status: ${current.toString()}" }
                return connection
            }
        }
        error("Too many Movie repository redirects")
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
}
