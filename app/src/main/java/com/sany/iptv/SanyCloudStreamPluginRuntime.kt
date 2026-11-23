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
        .filter { provider -> loaded.values.any { it.file.absolutePath == provider.sourcePlugin } }

    suspend fun search(providerName: String, query: String): List<SearchResponse> {
        val provider = providers().firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: error("CloudStream provider is not loaded: $providerName")
        return provider.search(query).orEmpty()
    }

    /** Fetches the selected provider's real CloudStream homepage shelves. */
    suspend fun homePage(providerName: String, page: Int = 1): List<HomePageList> {
        val provider = providers().firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: error("CloudStream provider is not loaded: $providerName")
        require(provider.hasMainPage && provider.mainPage.isNotEmpty()) {
            "$providerName does not publish a homepage catalogue"
        }
        return provider.mainPage.flatMap { data ->
            provider.getMainPage(
                page,
                MainPageRequest(data.name, data.data, data.horizontalImages)
            )?.items.orEmpty()
        }.filter { it.list.isNotEmpty() }
    }

    suspend fun load(providerName: String, url: String): LoadResponse? {
        val provider = providers().firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: error("CloudStream provider is not loaded: $providerName")
        return provider.load(url)
    }

    suspend fun resolvePlayback(
        providerName: String,
        title: String,
        episodeData: String
    ): List<SanyPlaybackRequest> {
        val provider = providers().firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: error("CloudStream provider is not loaded: $providerName")
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        provider.loadLinks(
            data = episodeData,
            isCasting = false,
            subtitleCallback = { subtitles.add(it) },
            callback = { links.add(it) }
        )
        return links.map { link ->
            CloudStreamPlaybackBridge.toSanyRequest(title, link, subtitles, provider.name)
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
