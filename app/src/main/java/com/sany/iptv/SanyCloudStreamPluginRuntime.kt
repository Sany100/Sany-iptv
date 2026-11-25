package com.sany.iptv

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import java.util.zip.ZipFile

/**
 * Executes verified CloudStream .cs3 packages against the pinned CloudStream
 * library ABI embedded in SANY. This is a deliberately scoped host for standard
 * MainAPI providers such as AniLight, not a wholesale port of CloudStream's UI
 * PluginManager or VideoClickAction system.
 */
object SanyCloudStreamPluginRuntime {
    private const val TAG = "SanyCSPluginRuntime"
    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()
    @Volatile private var hostContext: Context? = null
    private fun providersContext(): Context? = hostContext

    data class LoadResult(
        val success: Boolean,
        val pluginName: String,
        val providerNames: List<String>,
        val error: String? = null
    )

    private data class LoadedPlugin(
        val file: File,
        @Suppress("unused") val loader: DexClassLoader,
        val instance: BasePlugin,
        val providerNames: List<String>
    )

    @Synchronized
    fun load(context: Context, packageFile: File): LoadResult {
        val pendingDir = File(context.filesDir, "cloudstream_extensions/pending").canonicalFile
        val file = packageFile.canonicalFile
        require(file.parentFile == pendingDir) { "Plugin must be installed in SANY private extension storage" }
        require(file.isFile && file.extension.equals("cs3", true)) { "Expected an installed .cs3 package" }
        loaded[file.absolutePath]?.let {
            return LoadResult(true, it.instance.javaClass.simpleName, it.providerNames)
        }

        hostContext = context.applicationContext
        SanyNetworkBypasses.configureCloudStream(context)
        val manifest = readManifest(file)
        val optimized = File(context.codeCacheDir, "cloudstream_dex_opt").apply { mkdirs() }
        require(optimized.isDirectory) { "Unable to create DEX optimization directory" }

        // Android 14+ rejects dynamically loaded DEX files that remain writable.
        // A .cs3 is a ZIP package, so extract its classes.dex into code_cache,
        // make the extracted DEX read-only, and load that immutable file.
        val dexDirectory = File(context.codeCacheDir, "cloudstream_dex").apply { mkdirs() }
        require(dexDirectory.isDirectory) { "Unable to create DEX staging directory" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val packageHash = digest.digest().joinToString("") { "%02x".format(it) }
        val dexFile = File(dexDirectory, "$packageHash.dex")
        if (!dexFile.exists() || dexFile.canWrite()) {
            val temporaryDex = File(dexDirectory, "$packageHash.dex.tmp")
            if (temporaryDex.exists()) temporaryDex.delete()
            ZipFile(file).use { zip ->
                val dexEntry = zip.getEntry("classes.dex") ?: error("Plugin archive has no classes.dex")
                require(!dexEntry.isDirectory && dexEntry.size in 1..80L * 1024L * 1024L) {
                    "Invalid classes.dex size"
                }
                zip.getInputStream(dexEntry).use { input ->
                    FileOutputStream(temporaryDex).use { output -> input.copyTo(output) }
                }
            }
            // Use POSIX mode bits as well as File.setWritable: Android's DEX
            // verifier checks the actual file mode and rejects any writable bit.
            android.system.Os.chmod(temporaryDex.absolutePath, 0x124) // 0444
            require((android.system.Os.stat(temporaryDex.absolutePath).st_mode.toInt() and 0x92) == 0) {
                "Unable to make staged DEX file read-only"
            }
            if (dexFile.exists()) dexFile.delete()
            check(temporaryDex.renameTo(dexFile)) { "Unable to stage read-only DEX file" }
            android.system.Os.chmod(dexFile.absolutePath, 0x124) // Re-assert after rename.
        }
        require(dexFile.isFile && !dexFile.canWrite() &&
            (android.system.Os.stat(dexFile.absolutePath).st_mode.toInt() and 0x92) == 0
        ) {
            "Android requires the staged DEX file to be read-only"
        }
        val loader = DexClassLoader(dexFile.absolutePath, optimized.absolutePath, null, context.classLoader)

        var instance: BasePlugin? = null
        try {
            val pluginClass = loader.loadClass(manifest.pluginClassName)
            require(BasePlugin::class.java.isAssignableFrom(pluginClass)) {
                "Manifest class does not extend CloudStream BasePlugin"
            }
            instance = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
            instance.filename = file.absolutePath

            if (manifest.requiresResources && instance is Plugin) {
                val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                    .invoke(assets, file.absolutePath)
                instance.resources = Resources(assets, context.resources.displayMetrics, context.resources.configuration)
            }

            if (instance is Plugin) instance.load(context) else instance.load()

            val providers = APIHolder.allProviders.filter { it.sourcePlugin == file.absolutePath }
            require(providers.isNotEmpty()) { "Plugin loaded but registered no CloudStream MainAPI providers" }
            val providerNames = providers.map { it.name }
            loaded[file.absolutePath] = LoadedPlugin(file, loader, instance, providerNames)
            Log.i(TAG, "Loaded ${manifest.name}: ${providerNames.joinToString()}")
            return LoadResult(true, manifest.name.ifBlank { pluginClass.simpleName }, providerNames)
        } catch (error: Throwable) {
            rollback(file.absolutePath, instance)
            Log.e(TAG, "Failed to load ${file.name}", error)
            return LoadResult(false, manifest.name, emptyList(), error.cause?.message ?: error.message ?: error.javaClass.simpleName)
        }
    }

    @Synchronized
    fun unload(packageFile: File): Boolean {
        val path = packageFile.canonicalPath
        val active = loaded.remove(path) ?: return false
        rollback(path, active.instance)
        return true
    }

    fun loadedPluginFiles(): List<File> = loaded.values.map { it.file }

    fun providers(): List<MainAPI> = APIHolder.allProviders
        .filter { provider ->
            loaded.values.any { loadedPlugin ->
                runCatching {
                    File(loadedPlugin.file.absolutePath).canonicalPath ==
                        File(provider.sourcePlugin).canonicalPath
                }.getOrDefault(false)
            }
        }

    private data class CachedSearch(val savedAt: Long, val items: List<SearchResponse>)
    private val searchCache = ConcurrentHashMap<String, CachedSearch>()
    private const val SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L

    // Short-lived cache for playback URLs; signed links may expire, so keep TTL small.
    private data class CachedPlayback(val savedAt: Long, val requests: List<SanyPlaybackRequest>)
    private val playbackCache = ConcurrentHashMap<String, CachedPlayback>()
    private val playbackLocks = ConcurrentHashMap<String, Mutex>()
    private const val PLAYBACK_CACHE_TTL_MS = 60 * 1000L

    suspend fun search(providerName: String, query: String, forceRefresh: Boolean = false): List<SearchResponse> {
        providersContext()?.let { SanyNetworkBypasses.configureCloudStream(it) }
        val provider = providers().firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: error("CloudStream provider is not loaded: $providerName")
        val normalizedQuery = query.trim()
        val isMovieProvider = provider.supportedTypes.any {
            it == com.lagradost.cloudstream3.TvType.Movie ||
                it == com.lagradost.cloudstream3.TvType.AnimeMovie
        }
        val startedAt = System.currentTimeMillis()
        val cacheKey = "${provider.name.lowercase()}:${normalizedQuery.lowercase()}"
        if (!forceRefresh) searchCache[cacheKey]?.takeIf {
            System.currentTimeMillis() - it.savedAt < SEARCH_CACHE_TTL_MS
        }?.let {
            if (isMovieProvider) Log.i("SANY-MOVIE", "Search cache hit: provider=${provider.name} results=${it.items.size}")
            return it.items
        }
        val result = provider.search(normalizedQuery).orEmpty()
        if (isMovieProvider) Log.i(
            "SANY-MOVIE",
            "Search completed: provider=${provider.name} elapsedMs=${System.currentTimeMillis() - startedAt} results=${result.size}"
        )
        // Never cache an empty response: transient provider/network failures must
        // be retried on the next Sports homepage visit instead of staying blank.
        if (result.isNotEmpty()) {
            searchCache[cacheKey] = CachedSearch(System.currentTimeMillis(), result)
        } else {
            searchCache.remove(cacheKey)
        }
        return result
    }

    private data class CachedHomePage(val savedAt: Long, val shelves: List<HomePageList>)
    private val homePageCache = ConcurrentHashMap<String, CachedHomePage>()
    private val homePageLocks = ConcurrentHashMap<String, Mutex>()
    private val HOME_PAGE_CACHE_TTL_MS = 10 * 60 * 1000L
    // One shared gate prevents multiple selected extensions from flooding provider servers.
    private val homePageRequestGate = Semaphore(4)

    /** Fetches and briefly caches the selected provider's real CloudStream homepage shelves. */
    suspend fun homePage(providerName: String, page: Int = 1, forceRefresh: Boolean = false): List<HomePageList> {
        providersContext()?.let { SanyNetworkBypasses.configureCloudStream(it) }
        val provider = providers().firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: error("CloudStream provider is not loaded: $providerName")
        require(provider.hasMainPage && provider.mainPage.isNotEmpty()) {
            "$providerName does not publish a homepage catalogue"
        }
        val isMovieProvider = provider.supportedTypes.any {
            it == com.lagradost.cloudstream3.TvType.Movie ||
                it == com.lagradost.cloudstream3.TvType.AnimeMovie
        }
        val startedAt = System.currentTimeMillis()
        val cacheKey = "${provider.name.lowercase()}:$page"
        fun cachedResult(): List<HomePageList>? = homePageCache[cacheKey]?.takeIf {
            System.currentTimeMillis() - it.savedAt < HOME_PAGE_CACHE_TTL_MS
        }?.shelves
        if (!forceRefresh) cachedResult()?.let {
            if (isMovieProvider) Log.i("SANY-MOVIE", "Homepage cache hit: provider=${provider.name} shelves=${it.size}")
            return it
        }

        // If the same screen/provider is requested twice, share one network fetch.
        val requestLock = homePageLocks.computeIfAbsent(cacheKey) { Mutex() }
        return requestLock.withLock {
            if (!forceRefresh) cachedResult()?.let {
                if (isMovieProvider) Log.i("SANY-MOVIE", "Homepage cache hit after lock: provider=${provider.name} shelves=${it.size}")
                return@withLock it
            }

            val failedShelves = java.util.Collections.synchronizedList(mutableListOf<String>())
            val shelves = coroutineScope {
                provider.mainPage
                    .sortedBy { it.name.trim().lowercase() }
                    .map { data ->
                        async(Dispatchers.IO) {
                            homePageRequestGate.withPermit {
                                try {
                                    val response = kotlinx.coroutines.withTimeout(9_000L) {
                                        provider.getMainPage(
                                            page,
                                            MainPageRequest(data.name, data.data, data.horizontalImages)
                                        )
                                    }
                                    val items = response?.items.orEmpty()
                                    if (items.isEmpty()) failedShelves.add(data.name)
                                    items
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Throwable) {
                                    failedShelves.add("${data.name}: ${error.cause?.message ?: error.message ?: error.javaClass.simpleName}")
                                    emptyList()
                                }
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()
                    .sortedBy { it.name.trim().lowercase() }
            }
            if (shelves.isEmpty()) {
                error("Homepage returned no items for ${provider.mainUrl}. Failed/empty categories: ${failedShelves.joinToString("; ")}")
            }
            homePageCache[cacheKey] = CachedHomePage(System.currentTimeMillis(), shelves)
            if (isMovieProvider) Log.i(
                "SANY-MOVIE",
                "Homepage loaded: provider=${provider.name} elapsedMs=${System.currentTimeMillis() - startedAt} shelves=${shelves.size} failedOrEmpty=${failedShelves.size}"
            )
            shelves
        }
    }

    /** Returns each declared homepage category separately, including categories with no events yet. */
    suspend fun homePageSections(
        providerName: String,
        page: Int = 1,
        shelfTimeoutMs: Long = 20_000L
    ): List<Pair<String, List<SearchResponse>>> {
        providersContext()?.let { SanyNetworkBypasses.configureCloudStream(it) }
        val provider = providers().firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: error("CloudStream provider is not loaded: $providerName")
        require(provider.hasMainPage && provider.mainPage.isNotEmpty()) {
            "$providerName does not publish homepage categories"
        }
        return coroutineScope {
            provider.mainPage
                .sortedBy { it.name.trim().lowercase() }
                .map { data ->
                    async(Dispatchers.IO) {
                        homePageRequestGate.withPermit {
                            val returned = runCatching {
                                kotlinx.coroutines.withTimeout(shelfTimeoutMs) {
                                    provider.getMainPage(
                                        page,
                                        MainPageRequest(data.name, data.data, data.horizontalImages)
                                    )
                                }?.items.orEmpty().map { shelf -> shelf.name to shelf.list }
                            }.onFailure { error ->
                                Log.w(TAG, "$providerName category failed: ${data.name}", error)
                            }.getOrDefault(emptyList())
                            if (returned.isEmpty()) listOf(data.name to emptyList())
                            else returned
                        }
                    }
                }
                .awaitAll()
                .flatten()
                .distinctBy { it.first.trim().lowercase() }
        }
    }

    suspend fun load(providerName: String, url: String): LoadResponse? {
        providersContext()?.let { SanyNetworkBypasses.configureCloudStream(it) }
        val provider = providers().firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: error("CloudStream provider is not loaded: $providerName")
        return provider.load(url)
    }

    suspend fun resolvePlayback(
        providerName: String,
        title: String,
        episodeData: String,
        onSource: ((SanyPlaybackRequest) -> Unit)? = null
    ): List<SanyPlaybackRequest> {
        providersContext()?.let { SanyNetworkBypasses.configureCloudStream(it) }
        val provider = providers().firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: error("CloudStream provider is not loaded: $providerName")
        val isMovieProvider = provider.supportedTypes.any {
            it == com.lagradost.cloudstream3.TvType.Movie ||
                it == com.lagradost.cloudstream3.TvType.AnimeMovie
        }
        val resolveStartedAt = System.currentTimeMillis()
        if (isMovieProvider) Log.i(
            "SANY-MOVIE",
            "Source resolve started: provider=${provider.name} title=$title dataLength=${episodeData.length}"
        )
        val cacheKey = provider.name.lowercase() + ":" + episodeData.hashCode() + ":" + episodeData.length
        fun cached(): List<SanyPlaybackRequest>? = playbackCache[cacheKey]?.takeIf {
            System.currentTimeMillis() - it.savedAt < PLAYBACK_CACHE_TTL_MS
        }?.requests
        cached()?.let {
            if (isMovieProvider) Log.i("SANY-MOVIE", "Source cache hit: provider=${provider.name} sources=${it.size}")
            if (isMovieProvider) it.forEach { request -> runCatching { onSource?.invoke(request) } }
            return it
        }
        val requestLock = playbackLocks.computeIfAbsent(cacheKey) { Mutex() }
        return requestLock.withLock {
            cached()?.let {
                if (isMovieProvider) Log.i("SANY-MOVIE", "Source cache hit after lock: provider=${provider.name} sources=${it.size}")
                if (isMovieProvider) it.forEach { request -> runCatching { onSource?.invoke(request) } }
                return@withLock it
            }
            val links = mutableListOf<ExtractorLink>()
            val subtitles = mutableListOf<SubtitleFile>()
            val isPlayZtv = providerName.contains("PlayZTV", ignoreCase = true) ||
                provider.name.contains("PlayZTV", ignoreCase = true)
            fun shortValueCodePoints(value: String): String {
                val encoded = StringBuilder()
                val limit = minOf(8, value.length)
                for (index in 0 until limit) {
                    if (index > 0) encoded.append('-')
                    encoded.append(value[index].toInt().toString(16))
                }
                return encoded.toString()
            }
            if (isPlayZtv) {
                Log.i(
                    "SANY-LIVE-SPORTS",
                    "PlayZTV resolver input: providerArg=$providerName loadedProvider=${provider.name} " +
                        "itemDataLength=${episodeData.length} itemDataHex=${shortValueCodePoints(episodeData)}"
                )
            }
            val extractorStartedAt = System.currentTimeMillis()
            val emittedMovieLinks = java.util.concurrent.atomic.AtomicInteger(0)
            provider.loadLinks(
                data = episodeData,
                isCasting = false,
                subtitleCallback = { subtitle ->
                    subtitles.add(subtitle)
                    if (isMovieProvider && subtitles.size == 1) {
                        Log.i(
                            "SANY-MOVIE",
                            "Extractor emitted first subtitle: provider=${provider.name} elapsedMs=${System.currentTimeMillis() - extractorStartedAt}"
                        )
                    }
                },
                callback = { link ->
                    links.add(link)
                    if (isMovieProvider) {
                        val sourceIndex = emittedMovieLinks.incrementAndGet()
                        if (sourceIndex <= 8) {
                            val scheme = link.url.trim().substringBefore(':', "").lowercase().ifBlank { "missing" }
                            Log.i(
                                "SANY-MOVIE",
                                "Extractor emitted source: provider=${provider.name} index=$sourceIndex elapsedMs=${System.currentTimeMillis() - extractorStartedAt} scheme=$scheme urlLength=${link.url.length} type=${link.type} quality=${link.quality}"
                            )
                        }
                        val scheme = link.url.trim().substringBefore(':', "").lowercase()
                        if (scheme in setOf("http", "https", "ftp")) {
                            runCatching {
                                onSource?.invoke(
                                    CloudStreamPlaybackBridge.toSanyRequest(title, link, subtitles.toList(), provider.name)
                                )
                            }.onFailure { error ->
                                Log.w("SANY-MOVIE", "Could not publish early source: provider=${provider.name}", error)
                            }
                        }
                    }
                }
            )
            if (isMovieProvider) {
                Log.i(
                    "SANY-MOVIE",
                    "Extractor completed: provider=${provider.name} elapsedMs=${System.currentTimeMillis() - extractorStartedAt} links=${links.size} subtitles=${subtitles.size}"
                )
                links.forEachIndexed { index, link ->
                    val scheme = link.url.trim().substringBefore(':', "").lowercase().ifBlank { "missing" }
                    Log.i(
                        "SANY-MOVIE",
                        "Source[$index]: provider=${provider.name} name=${link.name} scheme=$scheme urlLength=${link.url.length} type=${link.type} quality=${link.quality} headers=${link.getAllHeaders().keys.joinToString(",")}"
                    )
                }
            }
            if (isPlayZtv) {
                Log.i(
                    "SANY-LIVE-SPORTS",
                    "PlayZTV resolver result: extractorLinks=${links.size}"
                )
                links.forEachIndexed { index, link ->
                    val scheme = link.url.substringBefore(':', "").takeIf { it.isNotBlank() } ?: "missing"
                    Log.i(
                        "SANY-LIVE-SPORTS",
                        "PlayZTV link[$index]: source=${link.source} name=${link.name} " +
                            "scheme=$scheme length=${link.url.length} type=${link.type} " +
                            "quality=${link.quality} headerNames=${link.getAllHeaders().keys.joinToString(",")}"
                    )
                }
            }
            val playbackLinks = if (isPlayZtv) {
                val valid = links.filter { link ->
                    val scheme = link.url.trim().substringBefore(':', "").lowercase()
                    scheme in setOf("http", "https", "ftp")
                }
                val rejected = links.size - valid.size
                if (rejected > 0) {
                    Log.w(
                        "SANY-LIVE-SPORTS",
                        "PlayZTV filtered $rejected non-URL extractor result(s); playableLinks=${valid.size}"
                    )
                }
                valid
            } else {
                links
            }
            val requests = playbackLinks.map { link ->
                CloudStreamPlaybackBridge.toSanyRequest(title, link, subtitles, provider.name)
            }.distinctBy { it.url }
            if (isMovieProvider) {
                // Publish the completed list once more so early entries gain any subtitles
                // discovered later during extraction.
                requests.forEach { request -> runCatching { onSource?.invoke(request) } }
                Log.i(
                    "SANY-MOVIE",
                    "Source resolve finished: provider=${provider.name} elapsedMs=${System.currentTimeMillis() - resolveStartedAt} playableSources=${requests.size}"
                )
            }
            if (requests.isNotEmpty()) {
                if (playbackCache.size > 120) playbackCache.clear()
                playbackCache[cacheKey] = CachedPlayback(System.currentTimeMillis(), requests)
            }
            requests
        }
    }
    private fun rollback(path: String, instance: BasePlugin?) {
        if (instance != null) runCatching { instance.beforeUnload() }
        APIHolder.apis.filter { it.sourcePlugin == path }.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { provider -> provider.sourcePlugin == path }
        }
        extractorApis.withLock {
            extractorApis.removeAll { extractor -> extractor.sourcePlugin == path }
        }
    }

    private data class Manifest(
        val pluginClassName: String,
        val name: String,
        val requiresResources: Boolean
    )

    private fun readManifest(file: File): Manifest = ZipFile(file).use { zip ->
        val entry = zip.getEntry("manifest.json") ?: error("Plugin archive has no manifest.json")
        require(!entry.isDirectory && entry.size in 1..64_000) { "Invalid plugin manifest" }
        val json = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val obj = org.json.JSONObject(json)
        val className = obj.optString("pluginClassName")
        require(className.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) {
            "Invalid pluginClassName"
        }
        require(zip.getEntry("classes.dex") != null) { "Plugin archive has no classes.dex" }
        Manifest(className, obj.optString("name"), obj.optBoolean("requiresResources", false))
    }
}
