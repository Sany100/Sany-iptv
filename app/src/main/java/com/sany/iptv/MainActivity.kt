package com.sany.iptv

import com.lagradost.cloudstream3.CommonActivity

import com.lagradost.cloudstream3.newEpisode

import android.app.Activity
import android.content.Context
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.Icon
import android.graphics.BitmapFactory
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ImageView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Rational
import android.content.res.Configuration
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.webkit.WebView
import android.webkit.CookieManager
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.media3.ui.AspectRatioFrameLayout
import java.net.URL
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.io.PrintWriter
import java.io.StringWriter

private const val ACTION_PIP_TOGGLE_PLAYBACK = "com.sany.iptv.action.PIP_TOGGLE_PLAYBACK"
private const val ACTION_PIP_SEEK_BACK = "com.sany.iptv.action.PIP_SEEK_BACK"
private const val ACTION_PIP_SEEK_FORWARD = "com.sany.iptv.action.PIP_SEEK_FORWARD"

class PipPlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_PIP_TOGGLE_PLAYBACK -> MainActivity.dispatchPipPlaybackAction("toggle")
            ACTION_PIP_SEEK_BACK -> MainActivity.dispatchPipPlaybackAction("back")
            ACTION_PIP_SEEK_FORWARD -> MainActivity.dispatchPipPlaybackAction("forward")
        }
    }
}

class MainActivity : AppCompatActivity() {
    /** Higher-velocity fling without changing normal finger tracking. */
    private class FastSportsScrollView(context: Context) : ScrollView(context) {
        override fun fling(velocityY: Int) { super.fling((velocityY * 1.45f).toInt()) }
    }
    private class FastSportsHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
        override fun fling(velocityX: Int) { super.fling((velocityX * 1.45f).toInt()) }
    }
    private val isTvDevice: Boolean
        get() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    private val isLight: Boolean
        get() = when (prefs.getString("app_theme", "dark")) {
            "light" -> true
            "system" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
    // Day mode uses a clean pure-white canvas and white surfaces throughout.
    private val bg get() = if (isLight) Color.rgb(242, 246, 252) else Color.BLACK
    private val panel get() = if (isLight) Color.WHITE else Color.rgb(10, 10, 10)
    private val panel2 get() = if (isLight) Color.rgb(232, 239, 249) else Color.rgb(18, 18, 18)
    private val accent: Int
        get() = when (prefs.getString("accent_color", "blue")) {
            "blue" -> Color.rgb(55, 145, 255)
            "purple" -> Color.rgb(165, 105, 255)
            "orange" -> Color.rgb(255, 145, 55)
            else -> Color.rgb(0, 190, 150)
        }
    private val muted get() = if (isLight) Color.rgb(90, 101, 118) else Color.rgb(148, 160, 180)
    private val white get() = if (isLight) Color.rgb(20, 27, 38) else Color.WHITE
    private val prefs by lazy { getSharedPreferences("sany_iptv", MODE_PRIVATE) }
    // API 23–33 (Android 6–13): favor low-data, fast-start defaults for older devices.
    // API 34+ (Android 14+): allow unrestricted adaptive quality on faster devices.
    private val defaultLowInternetMode: Boolean
        get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU
    private val defaultNetworkBufferMode: String
        get() = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) "low" else "balanced"
    private val pickFile = 41
    private val pickAnimeSubtitleFile = 42
    private var channels = listOf<Channel>()
    private val streamChecks = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    @Volatile private var checkerRunning = false
    @Volatile private var checkerPaused = false
    @Volatile private var checkerTargetCount = 0
    private val checkerProgressCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var liveTvPublishedCount = 0
    private var checkerFilter = "All"
    // Copy/export controls remain hidden until the session is unlocked with the owner key.
    private var checkerCopyUnlocked = false
    private var checkerDisplayedLimit = 250
    private var checkerSummaryView: TextView? = null
    private var checkerRunButton: TextView? = null
    private var checkerListAdapter: android.widget.BaseAdapter? = null
    private val checkerUiHandler = Handler(Looper.getMainLooper())
    private val checkerUiRefreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val permanentPlaylistUrl = "https://raw.githubusercontent.com/Sany100/Sany-iptv/migration/cloudstream-runtime-toolchain/playlist.m3u"
    private val playlistUrls = mutableListOf<String>()
    private var selectedGroup = "All"
    private var liveTvSelectedGroup = "Select category"
    private var section = "Live TV"
    private var searchQuery = ""
    private var liveTvSearchQuery = ""
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    // Provider-supplied track preferences remain active when the player settings are reapplied.
    private var activeProviderAudioLanguages: List<String>? = null
    private var activeProviderQualityHint: String? = null
    private val playbackRetryHandler = Handler(Looper.getMainLooper())
    private var playbackRetryCount = 0
    // Ordered CloudStream sources for automatic fallback after a real Media3 playback error.
    private var animeFallbackRequests: List<SanyPlaybackRequest> = emptyList()
    private var animeFallbackIndex = -1
    private var animeAutoNextInProgress = false
    private var liveSportsFirstFrameWatchdog: Runnable? = null
    private var playerView: PlayerView? = null
    private var moviesWebView: WebView? = null
    private var moviesSiteLoaded = false
    private var aniLightWebView: WebView? = null
    private var aniLightSiteLoaded = false
    private var aniLightNativeScreen: AniLightNativeScreen? = null
    private var aniLightNativeView: View? = null
    // Keep Movies catalogue and Live Sports provider catalogue views alive while
    // their details/player overlays are opened, preserving filters and scroll.
    private var moviesNativeView: View? = null
    @Volatile private var movieProviderSyncStarted = false
    private var movieDetailsParentView: View? = null
    private var liveSportsCatalogueView: View? = null
    @Volatile private var liveSportsProviderSyncRunning = false
    @Volatile private var liveSportsProviderSyncAttempted = false
    private var cloudStreamProviderScreen: CloudStreamProviderHomeScreen? = null
    private var cloudStreamProviderView: View? = null
    // Keep the exact Anime parent page attached/detached across details and playback.
    private var animeExternalPageActive = false
    private var animeExternalPageReturnToProvider = false
    private var currentChannel: Channel? = null
    private var playbackReturnScrollY = 0
    // Scroll position to restore after Android recreates the Activity (for example, fullscreen rotation).
    private var pendingListScrollRestoreY = 0
    private var playbackReturnSection = "Live TV"
    private var playbackReturnGroup = "All"
    private var playbackReturnSearch = ""
    private var resumeChannelOnForeground: Channel? = null
    private var previousChannel: Channel? = null
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var contentArea: LinearLayout
    private lateinit var search: EditText
    private lateinit var countLabel: TextView
    private lateinit var categoryRow: LinearLayout
    private lateinit var categoryHeaderContainer: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var listContainer: LinearLayout
    private var playerScreen: FrameLayout? = null
    private var animeEpisodePanel: View? = null
    private var animeCenterPlayButton: TextView? = null
    private var animePlayerTitleOverlay: TextView? = null
    // Scale UI work to the device's available memory instead of assuming a flagship phone.
    private val isLowMemoryDevice: Boolean by lazy {
        val manager = getSystemService(android.app.ActivityManager::class.java)
        manager?.isLowRamDevice == true || (manager?.memoryClass ?: 256) <= 128
    }
    private val isLowRamTvDevice: Boolean get() = isTvDevice && isLowMemoryDevice
    private val initialDisplayedLimit: Int get() = when {
        isLowRamTvDevice -> 6
        isTvDevice -> 12
        isLowMemoryDevice -> 24
        else -> 60
    }
    private val displayedLimitIncrement: Int get() = when {
        isLowRamTvDevice -> 12
        isTvDevice -> 24
        isLowMemoryDevice -> 48
        else -> 120
    }
    private var displayedLimit = 60
    private var playerHeader: LinearLayout? = null
    private var isPlayerFullscreen = false
    private var playerFullscreenButton: TextView? = null
    private var animeFullscreenActionButton: TextView? = null
    private var playerMinimizeButton: TextView? = null
    private var playerSettingsButton: TextView? = null
    private var playerTopActions: View? = null
    private var playerChannelScroll: ScrollView? = null
    private var playerChannelRows: LinearLayout? = null
    private var playerControlsPanel: View? = null
    private var headerTitleView: TextView? = null
    @Volatile private var liveSportsProviderLastAttemptAt = 0L
    @Volatile private var liveSportsProviderLastError: String? = null
    private var homeHeaderContainer: View? = null
    private var playlistSelectorContainer: View? = null
    private var liveTvNavLabel: TextView? = null
    private var maintenanceOverlay: View? = null
    private val maintenanceHandler = Handler(Looper.getMainLooper())
    private val maintenanceConfigUrl = "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/maintenance.json"
    // A small bounded pool prevents one thread per channel logo on low-memory phones.
    private val logoExecutor by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(
            if (isLowMemoryDevice) 1 else 2
        )
    }

    // Persistent disk cache makes channel logos appear immediately on later visits.
    private fun loadCachedChannelLogo(image: ImageView, fallback: View?, logoUrl: String) {
        image.tag = logoUrl
        logoExecutor.execute {
            try {
                val cacheDir = java.io.File(cacheDir, "channel_logos").apply { mkdirs() }
                val cachedFile = java.io.File(cacheDir, logoUrl.hashCode().toString() + ".img")
                if (!cachedFile.exists() || cachedFile.length() == 0L) {
                    val tempFile = java.io.File(cacheDir, cachedFile.name + ".tmp")
                    try {
                        val connection = URL(logoUrl).openConnection().apply {
                            connectTimeout = 4000
                            readTimeout = 5000
                            useCaches = true
                        }
                        connection.getInputStream().use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (tempFile.length() > 0L) {
                            if (!tempFile.renameTo(cachedFile)) {
                                tempFile.copyTo(cachedFile, overwrite = true)
                                tempFile.delete()
                            }
                        }
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
                if (cachedFile.exists() && cachedFile.length() > 0L) {
                    // Channel marks and poster tiles are small on screen; decode only
                    // enough pixels for their target bounds to avoid full-size bitmap RAM spikes.
                    val targetPixels = when {
                        isLowRamTvDevice -> dp(32)
                        isTvDevice -> dp(48)
                        image.scaleType == ImageView.ScaleType.CENTER_CROP -> dp(240)
                        else -> dp(96)
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(cachedFile.absolutePath, bounds)
                    var sampleSize = 1
                    while (bounds.outWidth / (sampleSize * 2) >= targetPixels &&
                        bounds.outHeight / (sampleSize * 2) >= targetPixels
                    ) sampleSize *= 2
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        inSampleSize = sampleSize
                    }
                    val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath, options)
                    if (bitmap != null) runOnUiThread {
                        if (!isFinishing && !isDestroyed && image.tag == logoUrl) {
                            image.setImageBitmap(bitmap)
                            fallback?.visibility = View.GONE
                        } else bitmap.recycle()
                    }
                }
            } catch (_: Exception) {
                // Keep the channel-name fallback if the logo is unavailable.
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Resolve device RAM capabilities only after the Activity has an attached Context.
        displayedLimit = initialDisplayedLimit
        // Install crash capture before any app/CloudStream initialization.
        installSanyCrashLogger()
        CommonActivity.setActivityInstance(this)
        pipPlaybackActionHandler = { action ->
            runOnUiThread {
                player?.let { activePlayer ->
                    when (action) {
                        "back" -> activePlayer.seekTo((activePlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                        "forward" -> {
                            val target = activePlayer.currentPosition + 10_000L
                            val duration = activePlayer.duration
                            activePlayer.seekTo(if (duration != C.TIME_UNSET && duration > 0L) target.coerceAtMost(duration) else target)
                        }
                        else -> {
                            if (activePlayer.playWhenReady) activePlayer.pause()
                            else if (activePlayer.playbackState == Player.STATE_ENDED) {
                                activePlayer.seekToDefaultPosition()
                                activePlayer.play()
                            } else activePlayer.play()
                        }
                    }
                    refreshPictureInPictureParams()
                }
            }
        }

        // Global host policy: suppress third-party donation/support dialogs from
        // dynamically loaded CloudStream plugins, regardless of the plugin's own
        // bundled DonationManager implementation or package/classloader.
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                private fun dismissPluginDonation(fragment: androidx.fragment.app.Fragment) {
                    val className = fragment.javaClass.name.lowercase(java.util.Locale.ROOT)
                    val isDonationUi = className.contains("donat") ||
                        className.contains("raghav") ||
                        className.contains("supportmywork")
                    if (isDonationUi && fragment is androidx.fragment.app.DialogFragment) {
                        fragment.dismissAllowingStateLoss()
                        android.util.Log.i("SANY-PluginPolicy", "Suppressed third-party donation dialog: $className")
                    }
                }

                override fun onFragmentStarted(
                    fm: androidx.fragment.app.FragmentManager,
                    fragment: androidx.fragment.app.Fragment
                ) {
                    dismissPluginDonation(fragment)
                }

                override fun onFragmentResumed(
                    fm: androidx.fragment.app.FragmentManager,
                    fragment: androidx.fragment.app.Fragment
                ) {
                    dismissPluginDonation(fragment)
                }
            },
            true
        )
        // WorkManager initialization must not be able to terminate the whole app at launch.
        try {
            EpgRepository.scheduleDaily(this)
        } catch (error: Throwable) {
            android.util.Log.e("SANY-STARTUP", "Daily EPG scheduling unavailable; continuing startup", error)
        }
        // Refresh EPG on every fresh app launch, without blocking the first screen.
        // The existing daily worker remains as a background safety-net.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) refreshEpgSources(false)
            }, 3_000L)
        } else {
            refreshEpgSources(false)
        }
        // Keep automatic update checks enabled for every installation; there is no user-facing switch.
        prefs.edit().putBoolean("auto_update_check", true).apply()
        // Apply the refreshed Electric Blue identity once, without overriding later user choices.
        if (!prefs.getBoolean("blue_ui_refresh_v1", false)) {
            prefs.edit().putString("accent_color", "blue").putBoolean("blue_ui_refresh_v1", true).apply()
        }
        // Remove assistant-added demo playlists while preserving all other user-saved playlists.
        val rejectedSources = setOf(
            "https://iptv-org.github.io/iptv/categories/animation.m3u",
            "https://iptv-org.github.io/iptv/categories/series.m3u",
            "https://raw.githubusercontent.com/wizakorhd/iptv/main/playlist-anime.m3u",
            "https://biskyt.github.io/legalFreeIPTVplaylist/vod.m3u"
        )
        playlistUrls.add(permanentPlaylistUrl)
        playlistUrls.addAll(readPlaylistUrls().filterNot { it in rejectedSources || it == permanentPlaylistUrl })
        // The owner playlist is compiled into the app and is always the startup playlist.
        val savedPlaylist = permanentPlaylistUrl
        prefs.edit().putString("playlist_url", permanentPlaylistUrl).apply()
        val restoringBackground = savedInstanceState != null
        if (restoringBackground) {
            // Restore the current screen only for Android's in-process Activity recreation
            // (for example, rotation or process recreation with a saved instance state).
            section = savedInstanceState?.getString("ui_section") ?: "Live TV"
            if (isTvDevice && (section == "Series" || section == "Checker")) section = "Live TV"
            liveTvSelectedGroup = savedInstanceState?.getString("live_tv_group") ?: "Select category"
            liveTvSearchQuery = savedInstanceState?.getString("live_tv_search") ?: ""
            selectedGroup = savedInstanceState?.getString("ui_group") ?: "All"
            searchQuery = savedInstanceState?.getString("ui_search") ?: ""
            if (section == "Live TV") {
                selectedGroup = liveTvSelectedGroup
                searchQuery = liveTvSearchQuery
            }
            playbackReturnSection = savedInstanceState?.getString("playback_return_section") ?: section
            if (isTvDevice && (playbackReturnSection == "Series" || playbackReturnSection == "Checker")) playbackReturnSection = "Live TV"
            playbackReturnGroup = savedInstanceState?.getString("playback_return_group") ?: selectedGroup
            playbackReturnSearch = savedInstanceState?.getString("playback_return_search") ?: searchQuery
            playbackReturnScrollY = savedInstanceState?.getInt("playback_return_scroll_y", savedInstanceState?.getInt("list_scroll_y", 0) ?: 0) ?: 0
            pendingListScrollRestoreY = savedInstanceState?.getInt("list_scroll_y", playbackReturnScrollY) ?: playbackReturnScrollY
        } else {
            // A fresh app launch always opens the Live TV home/category directory.
            // Do not carry the last visited category or section across a full app close.
            section = "Live TV"
            liveTvSelectedGroup = "Select category"
            liveTvSearchQuery = ""
            selectedGroup = "Select category"
            searchQuery = ""
            prefs.edit()
                .putString("ui_section", "Live TV")
                .putString("ui_group", "Select category")
                .putString("ui_search", "")
                .putString("live_tv_group", "Select category")
                .putString("live_tv_search", "")
                .putBoolean("resume_without_splash", false)
                .apply()
        }
        // Keep the Live TV channel list empty until the user opens a category.
        // Returning from background/PiP must not replay a stream or show the startup animation.
        val returningFromBackground = prefs.getBoolean("resume_without_splash", false)
        prefs.edit().putBoolean("resume_without_splash", false).remove("resume_stream").apply()
        val cachedPlaylist = savedPlaylist?.let { playlistCacheFile(it) }
        if (cachedPlaylist?.exists() == true) {
            try { channels = M3uParser.parse(cachedPlaylist.readText()) } catch (_: Exception) { channels = emptyList() }
        }
        // Android 15 (targetSdk 35) draws edge-to-edge by default. Keep the app
        // inside the safe system-bar area where possible and paint all exposed
        // system/decor regions with the currently selected theme color.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        applySystemBars()
        buildShell(showSplash = !restoringBackground && !returningFromBackground)
        // Show a report saved by the Application-level crash handler before optional startup work.
        showPreviousCrashReport()
        // Automatically sync and load every allowlisted Anime provider from the
        // SANY builds index. Each package is hash-verified and failures are isolated.
        if (!isLowRamTvDevice) {
        thread(name = "SANY-Anime-Plugins-AutoSync") {
            try {
                val report = SanyExtensionRepositories.syncOfficialAnimePlugins(applicationContext)
                report.packages.forEach { packageFile ->
                    val loadResult = SanyCloudStreamPluginRuntime.load(applicationContext, packageFile)
                    if (loadResult.success) {
                        android.util.Log.i("SANY-CS", "Anime plugin loaded: ${packageFile.name} -> ${loadResult.providerNames.joinToString()}")
                    } else {
                        android.util.Log.e("SANY-CS", "Anime plugin DEX load failed: ${packageFile.name}: ${loadResult.error}")
                    }
                }
                report.failures.forEach { android.util.Log.e("SANY-CS", "Anime plugin sync failed: $it") }
                val removedVegaMovies = SanyMovieExtensionManager.removeVegaMovies(applicationContext)
                if (removedVegaMovies > 0) {
                    android.util.Log.i("SANY-MOVIE", "Removed $removedVegaMovies retired VegaMovies extension(s)")
                }
                val multiMoviesAutoSync = runCatching {
                    SanyMovieExtensionManager.syncBuiltInMultiMovies(applicationContext)
                }.onFailure { error ->
                    android.util.Log.e("SANY-MOVIE", "Built-in MultiMovies auto-install/update failed", error)
                }.getOrNull()
                if (multiMoviesAutoSync != null) {
                    if (multiMoviesAutoSync.success) {
                        android.util.Log.i("SANY-MOVIE", "Built-in MultiMovies v51+ auto-installed/updated, selected and loaded")
                    } else {
                        android.util.Log.e("SANY-MOVIE", "Built-in MultiMovies auto-load failed: ${multiMoviesAutoSync.error}")
                    }
                }
                SanyMovieExtensionManager.loadInstalled(
                    applicationContext,
                    if (multiMoviesAutoSync?.success == true) setOf("multimovies") else emptySet()
                ).forEach { result ->
                    if (result.success) {
                        android.util.Log.i("SANY-MOVIE", "Movie extension restored: ${result.pluginName}")
                    } else {
                        android.util.Log.e("SANY-MOVIE", "Movie extension restore failed: ${result.error}")
                    }
                }
            } catch (error: Throwable) {
                android.util.Log.e("SANY-CS", "Anime plugin auto-sync failed", error)
            } finally {
                // Rebuild only Anime/Movies after their own providers finish syncing.
                // Live Sports has an independent startup sync and must not delay these headers.
                runOnUiThread {
                    if (!isFinishing && !isDestroyed &&
                        (section == "Series" || section == "Movies")
                    ) renderSection()
                }
            }
        }
        // Sports provider installation is independent from Anime/Movie downloads.
        // This also restores the provider automatically after a completely fresh reinstall.
        syncLiveSportsProviderInBackground()
        } else {
            // On 1 GB TVs, defer provider DEX loading and catalogue downloads until
            // the user opens Anime, Movies or Live Sports. The features remain available.
            android.util.Log.i("SANY-LOW-RAM", "Deferring Anime/Movie/Sports provider sync until their section is opened")
        }
        registerForPushUpdates()
        checkForMandatoryUpdate()
        checkMaintenanceMode()
        if (!restoringBackground) {
            // Avoid costly first-frame transforms on Android 6 devices.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M || prefs.getBoolean("reduce_ui_motion", true)) {
                home.alpha = 1f
                home.translationY = 0f
                home.scaleX = 1f
                home.scaleY = 1f
            } else {
                home.alpha = 0f
                home.translationY = dp(14).toFloat()
                home.scaleX = 0.985f
                home.scaleY = 0.985f
                home.post {
                    home.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setDuration(300).start()
                }
            }
        }
        restoreStreamCheckResults()
        if (!restoringBackground && !savedPlaylist.isNullOrBlank()) {
            // Refresh the owner playlist on every fresh launch; loadFromUrl falls back to cache offline.
            loadFromUrl(savedPlaylist, false)
        } else if (channels.isNotEmpty()) {
            startPlaylistCheck(false)
        }
    }

    // Register this installation for owner-wide update announcements through Firebase Cloud Messaging.
    private fun registerForPushUpdates() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "sany_updates", "SANY IPTV Updates", android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Important SANY IPTV update announcements" }
                getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 904)
            }
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .subscribeToTopic("sany_iptv_all")
        } catch (e: LinkageError) {
            // Firebase is optional in migration/CI builds. A missing runtime
            // Firebase class must never prevent the main IPTV screen from opening.
            android.util.Log.w("SANY-FCM", "Firebase runtime unavailable; skipping push registration", e)
        } catch (e: Exception) {
            android.util.Log.w("SANY-FCM", "Push registration unavailable", e)
        }
    }

    // Mandatory update gate: the remote policy is controlled by update.json in the repository.
    private val updateConfigUrl = "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/update.json"
    private val updateReminderHandler = Handler(Looper.getMainLooper())
    private var updateDialog: AlertDialog? = null
    private var updateRequired = false
    private val updateReminderRunnable = object : Runnable {
        override fun run() {
            if (updateRequired && !isFinishing && !isDestroyed) {
                showMandatoryUpdateDialog()
                updateReminderHandler.postDelayed(this, 3_600_000L)
            }
        }
    }

    // Recheck available app releases every hour while the app process is active.
    private val appUpdateReminderHandler = Handler(Looper.getMainLooper())
    private val appUpdateReminderRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (hasWindowFocus() && playerScreen == null) checkForAppUpdate(false)
            appUpdateReminderHandler.postDelayed(this, 3_600_000L)
        }
    }

    private fun checkForMandatoryUpdate() {
        thread(name = "sany-update-check") {
            try {
                val connection = (URL(updateConfigUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val config = JSONObject(body)
                if (!config.optBoolean("enabled", true)) {
                    runOnUiThread {
                        updateRequired = false
                        prefs.edit().putBoolean("mandatory_update_required", false).apply()
                        updateReminderHandler.removeCallbacks(updateReminderRunnable)
                        updateDialog?.dismiss()
                        updateDialog = null
                    }
                    return@thread
                }
                val minimumCode = config.optInt("minimum_version_code", 0)
                val currentCode = packageManager.getPackageInfo(packageName, 0).versionCode
                val required = currentCode < minimumCode
                val version = config.optString("latest_version", "নতুন সংস্করণ")
                val apkUrl = config.optString("apk_url", "")
                runOnUiThread {
                    updateRequired = required
                    if (required) {
                        prefs.edit().putBoolean("mandatory_update_required", true).apply()
                        showMandatoryUpdateDialog(version, apkUrl)
                        updateReminderHandler.removeCallbacks(updateReminderRunnable)
                        updateReminderHandler.postDelayed(updateReminderRunnable, 3_600_000L)
                    } else {
                        prefs.edit().putBoolean("mandatory_update_required", false).apply()
                        updateReminderHandler.removeCallbacks(updateReminderRunnable)
                        updateDialog?.dismiss()
                        updateDialog = null
                    }
                }
            } catch (_: Exception) {
                // Do not brick existing installations when the policy endpoint is temporarily unavailable.
            }
        }
    }

    private fun showMandatoryUpdateDialog(
        version: String = "নতুন সংস্করণ",
        apkUrl: String = "https://github.com/Sany100/Sany-iptv/releases/latest"
    ) {
        if (!updateRequired || isFinishing || isDestroyed) return
        if (updateDialog?.isShowing == true) return
        val safeUrl = if (apkUrl.startsWith("https://")) apkUrl else "https://github.com/Sany100/Sany-iptv/releases/latest"
        updateDialog = AlertDialog.Builder(this)
            .setTitle("SANY IPTV আপডেট প্রয়োজন")
            .setMessage("নতুন সংস্করণ $version ইনস্টল না করা পর্যন্ত অ্যাপ ব্যবহার করা যাবে না। UPDATE NOW চাপুন।")
            .setCancelable(false)
            .setPositiveButton("UPDATE NOW") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
                } catch (_: Exception) {
                    toast("আপডেট লিংক খোলা যাচ্ছে না")
                }
            }
            .create()
            .also { dialog ->
                dialog.setCanceledOnTouchOutside(false)
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(35, 145, 255))
                }
                dialog.show()
            }
    }

    private fun checkMaintenanceMode() {
        thread(name = "sany-maintenance-check") {
            try {
                val connection = (URL(maintenanceConfigUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val config = JSONObject(body)
                val enabled = config.optBoolean("enabled", false)
                val message = config.optString(
                    "message",
                    "আমাদের অ্যাপে উন্নয়নমূলক কাজ চলছে। অনুগ্রহ করে কিছুক্ষণ পরে আবার চেষ্টা করুন।"
                )
                val allowed = config.optJSONArray("allowed_devices") ?: JSONArray()
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: ""
                var isAdminDevice = false
                for (i in 0 until allowed.length()) {
                    if (allowed.optString(i) == deviceId && deviceId.isNotBlank()) {
                        isAdminDevice = true
                        break
                    }
                }
                runOnUiThread {
                    if (enabled && !isAdminDevice) showMaintenanceScreen(message, deviceId)
                    else hideMaintenanceScreen()
                }
            } catch (_: Exception) {
                // If the remote config cannot be reached, keep the app usable.
            }
        }
        maintenanceHandler.removeCallbacksAndMessages(null)
        maintenanceHandler.postDelayed({ checkMaintenanceMode() }, 60_000L)
    }

    private fun showMaintenanceScreen(message: String, deviceId: String) {
        if (maintenanceOverlay != null || !::root.isInitialized) return

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(5, 10, 24))
            isClickable = true
            isFocusable = true
        }
        val glow = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(12, 36, 74), Color.rgb(5, 10, 24))
            )
        }
        overlay.addView(glow, FrameLayout.LayoutParams(-1, -1))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(20, 35, 62), Color.rgb(11, 20, 39))
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(55, 105, 165))
            }
            elevation = dp(12).toFloat()
        }
        val cardParams = FrameLayout.LayoutParams(
            -1, -2, Gravity.CENTER
        ).apply {
            leftMargin = dp(22)
            rightMargin = dp(22)
            topMargin = dp(24)
            bottomMargin = dp(24)
        }

        val icon = TextView(this).apply {
            text = "🛠️"
            textSize = 58f
            gravity = Gravity.CENTER
        }
        card.addView(icon, LinearLayout.LayoutParams(-1, dp(82)))

        val brand = TextView(this).apply {
            text = "SANY IPTV"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
        }
        card.addView(brand, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val status = TextView(this).apply {
            text = "●  MAINTENANCE MODE ACTIVE"
            textSize = 11f
            setTextColor(Color.rgb(255, 199, 92))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.argb(35, 255, 190, 70))
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.argb(120, 255, 190, 70))
            }
        }
        card.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })

        val heading = TextView(this).apply {
            text = "We’ll be back soon!"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        val detail = TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(Color.rgb(215, 226, 243))
            gravity = Gravity.CENTER
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        card.addView(detail, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(18)
        })

        val divider = View(this).apply { setBackgroundColor(Color.argb(70, 150, 185, 230)) }
        card.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))

        val eta = TextView(this).apply {
            text = "EXPECTED RETURN\nশীঘ্রই আবার চালু হবে"
            textSize = 13f
            setTextColor(Color.rgb(255, 207, 115))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        card.addView(eta, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })

        val thanks = TextView(this).apply {
            text = "আপনার ধৈর্য ও সহযোগিতার জন্য ধন্যবাদ।\n— SANY IPTV Team"
            textSize = 13f
            setTextColor(Color.rgb(165, 185, 211))
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        card.addView(thanks, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(20)
        })

        val copy = TextView(this).apply {
            text = "COPY DEVICE ID (ADMIN)"
            textSize = 11f
            setTextColor(Color.rgb(125, 195, 255))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(4))
            setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SANY IPTV device ID", deviceId))
                toast("Device ID copied")
            }
        }
        card.addView(copy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        overlay.addView(card, cardParams)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        maintenanceOverlay = overlay
    }

    private fun hideMaintenanceScreen() {
        maintenanceOverlay?.let { root.removeView(it) }
        maintenanceOverlay = null
    }

    private fun installSanyCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter()
                error.printStackTrace(PrintWriter(trace))
                java.io.File(filesDir, "sany_last_crash.txt").writeText(
                    "SANY IPTV crash report\nThread: " + thread.name +
                        "\nAndroid: " + Build.VERSION.RELEASE +
                        "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL +
                        "\nTime: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                        "\n\n" + trace.toString()
                )
            } catch (_: Exception) { }
            previousHandler?.uncaughtException(thread, error)
        }
    }

    private fun showPreviousCrashReport() {
        val reportFile = java.io.File(filesDir, "sany_last_crash.txt")
        if (!reportFile.exists()) return
        val report = try { reportFile.readText().take(12000) } catch (_: Exception) { return }
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            AlertDialog.Builder(this)
                .setTitle("SANY IPTV — Crash report found")
                .setMessage("আগেরবার অ্যাপ বন্ধ হওয়ার কারণের রিপোর্ট পাওয়া গেছে। COPY REPORT চাপুন, তারপর রিপোর্টটি এখানে পাঠান।")
                .setPositiveButton("COPY REPORT") { _, _ ->
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SANY IPTV crash report", report))
                    toast("Crash report copied — এখানে paste করুন")
                }
                .setNeutralButton("DELETE REPORT") { _, _ -> reportFile.delete() }
                .setNegativeButton("LATER", null)
                .show()
        }, 900L)
    }
    private fun applySystemBars() {
        // Keep Android's status bar visible so the time, battery, and network icons show.
        // Fit app content below the status bar instead of drawing over system indicators.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        window.decorView.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = if (isLight) Color.WHITE else Color.rgb(6, 18, 38)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.navigationBarDividerColor = bg
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
    }

    private fun buildShell(showSplash: Boolean = true) {
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        home = LinearLayout(this).apply {
            orientation = if (isTvDevice) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        renderHome()
        if (showSplash) showStartupAnimation() else scheduleAutoUpdateCheck()
    }

    private fun showStartupAnimation() {
        val splash = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (isLight) intArrayOf(Color.WHITE, Color.rgb(245, 249, 255), Color.WHITE)
                else intArrayOf(Color.rgb(5, 15, 38), Color.rgb(13, 48, 91), Color.rgb(42, 22, 82))
            )
            elevation = dp(30).toFloat()
        }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            translationY = dp(18).toFloat()
        }
        val logo = ImageView(this).apply {
            setImageResource(com.sany.iptv.R.drawable.sany_logo_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "SANY IPTV logo"
            alpha = 0f
            scaleX = 0.72f
            scaleY = 0.72f
            rotationY = -78f
            rotationX = 24f
            cameraDistance = 9000f * resources.displayMetrics.density
            translationZ = dp(18).toFloat()
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        center.addView(logo, LinearLayout.LayoutParams(dp(260), dp(260)).apply {
            // Lift the splash logo higher while keeping the welcome text and
            // loading progress grouped comfortably below it.
            topMargin = dp(5)
            bottomMargin = dp(0)
        })

        val welcomeText = android.text.SpannableString("Welcome to my SANY IPTV app").apply {
            val nameStart = indexOf("SANY IPTV")
            if (nameStart >= 0) setSpan(
                android.text.style.StyleSpan(Typeface.BOLD),
                nameStart, nameStart + "SANY IPTV".length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val welcome = text(welcomeText.toString(), 20, Color.WHITE, false).apply {
            this.text = welcomeText
            gravity = Gravity.CENTER
            letterSpacing = 0.01f
            setPadding(dp(8), 0, dp(8), 0)
            alpha = 0f
            translationY = dp(-40).toFloat()
        }
        center.addView(welcome, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(4)
        })

        val thankYou = text("Thank you for choosing SANY IPTV", 12, Color.rgb(126, 220, 255), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.025f
            setPadding(0, dp(7), 0, dp(14))
            alpha = 0f
            // Keep this line in its own layout row beneath the welcome message.
            translationY = 0f
        }
        center.addView(thankYou, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })

        // Static welcome text colors: color/lighting animation intentionally disabled.
        welcome.setTextColor(if (isLight) Color.rgb(25, 55, 100) else Color.WHITE)
        thankYou.setTextColor(if (isLight) Color.rgb(0, 130, 190) else Color.rgb(126, 220, 255))

        val loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(64, 170, 255))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(85, 210, 230, 255))
            translationY = 0f
        }
        center.addView(loadingBar, LinearLayout.LayoutParams(dp(230), dp(7)))
        val percentLabel = text("0%", 13, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
            translationY = 0f
        }
        center.addView(percentLabel)
        splash.addView(center, FrameLayout.LayoutParams(-1, -1))
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))

        val logoGlow = ValueAnimator.ofFloat(0.985f, 1.02f).apply {
            duration = 1200L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                logo.scaleX = scale
                logo.scaleY = scale
            }
            start()
        }
        logo.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .rotationY(360f).rotationX(0f)
            .setDuration(1150)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
        welcome.animate().alpha(1f).translationY(0f)
            .setStartDelay(350).setDuration(650)
            .setInterpolator(DecelerateInterpolator()).start()
        center.getChildAt(2).animate().alpha(1f)
            .setStartDelay(650).setDuration(500)
            .setInterpolator(DecelerateInterpolator()).start()

        // Keep the welcome screen visible for five seconds and show live percentage progress.
        val progressAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration = 3_000L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Int
                loadingBar.progress = progress
                percentLabel.text = "$progress%"
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    logoGlow.cancel()
                    splash.animate().alpha(0f).setDuration(350).withEndAction {
                        root.removeView(splash)
                        scheduleAutoUpdateCheck()
                    }.start()
                }
            })
            start()
        }
    }

    private fun scheduleAutoUpdateCheck() {
        if (!prefs.getBoolean("auto_update_check", false)) return
        appUpdateReminderHandler.removeCallbacks(appUpdateReminderRunnable)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed && playerScreen == null) checkForAppUpdate(false)
            appUpdateReminderHandler.postDelayed(appUpdateReminderRunnable, 3_600_000L)
        }, 700L)
    }

    private fun updateHomeHeaderVisibility() {
        // Keep the brand header hidden on every tab while the compact player is active.
        // Closing the player restores the header in the normal home layout.
        homeHeaderContainer?.visibility = if (playerView != null) View.GONE else View.VISIBLE
    }

    private fun renderHome() {
        val currentNavigation = currentNavigationSnapshot()
        val previousNavigation = lastRenderedNavigation
        if (!restoringNavigation && previousNavigation != null &&
            (previousNavigation.section != currentNavigation.section ||
             previousNavigation.selectedGroup != currentNavigation.selectedGroup ||
             previousNavigation.liveTvSelectedGroup != currentNavigation.liveTvSelectedGroup)) {
            // Avoid duplicate adjacent entries when several UI refreshes target the same destination.
            if (navigationHistory.isEmpty() || navigationHistory.peekLast() != previousNavigation) {
                navigationHistory.addLast(previousNavigation)
            }
        }
        lastRenderedNavigation = currentNavigation
        restoringNavigation = false
        home.removeAllViews()
        // No reserved blank welcome slot: keep the selected page content directly below the player.
        val tvContentColumn = if (isTvDevice) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        } else null
        val settingsHeader = section == "Settings"
        val settingsHeaderText = if (isLight) Color.rgb(13, 28, 54) else Color.WHITE
        val settingsHeaderMuted = if (isLight) Color.rgb(76, 96, 126) else Color.rgb(166, 183, 207)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(if (isTvDevice) 18 else if (settingsHeader) 24 else 16),
                dp(if (isTvDevice) 4 else 6),
                dp(if (isTvDevice) 18 else if (settingsHeader) 24 else 16),
                dp(if (isTvDevice) 14 else 12)
            )
            // Settings uses the approved cool blue-grey header; other sections retain glass.
            background = if (settingsHeader && isLight) GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(224, 232, 244), Color.rgb(184, 199, 220), Color.rgb(207, 218, 235))
            ) else if (settingsHeader) GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8, 20, 39), Color.rgb(13, 31, 56), Color.rgb(7, 17, 34))
            ) else glassWithoutTopStroke(0)
            cameraDistance = 11000f * resources.displayMetrics.density
            rotationX = 0f
            translationZ = dp(10).toFloat()
            elevation = dp(14).toFloat()
        }
        // Keep the original stacked brand header. Move connection status below
        // SANY IPTV instead of showing it as a pill on the right side.
        val brandLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = text("SANY IPTV", if (isTvDevice) 23 else 29,
            if (settingsHeader && isLight) Color.rgb(38, 119, 245) else accent, true).apply {
            letterSpacing = 0.06f
            if (!settingsHeader) setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.argb(150, 55, 145, 255))
            setPadding(dp(8), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            translationZ = dp(10).toFloat()
        }
        brandLine.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val connectionStatus = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = if (settingsHeader && isLight) rounded(Color.WHITE, 28) else if (settingsHeader) rounded(Color.rgb(20, 39, 64), 28) else glass(18)
        }
        if (channels.isEmpty()) {
            connectionStatus.addView(text("NOT CONNECTED", 9, muted, true))
        } else {
            if (!isTvDevice) {
                val connectedDot = text("●", 9, Color.rgb(45, 210, 115), true).apply {
                    gravity = Gravity.CENTER
                }
                connectionStatus.addView(connectedDot)
                android.animation.ObjectAnimator.ofFloat(connectedDot, View.ALPHA, 1f, 0.18f).apply {
                    duration = 650L
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
            }
            connectionStatus.addView(text(" CONNECTED", 9,
                if (settingsHeader && isLight) Color.rgb(38, 119, 245) else accent, true))
        }
        brandLine.addView(connectionStatus, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = dp(8)
        })
        top.addView(brandLine)
        top.addView(text("PREMIUM IPTV EXPERIENCE", 10,
            if (settingsHeader) settingsHeaderText else white, true).apply {
            letterSpacing = 0.02f
            setPadding(dp(8), 0, 0, dp(3))
        })
        top.addView(text("✦  FAST  •  SMOOTH  •  UNLIMITED", 9,
            if (settingsHeader) settingsHeaderMuted else muted, true).apply {
            letterSpacing = 0.075f
            setPadding(0, 0, 0, dp(7))
        })
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerTitleView = text(if (section == "Series") "Anime Series" else section,
            19, if (settingsHeader) settingsHeaderText else white, true)
        titleRow.addView(headerTitleView, LinearLayout.LayoutParams(0, -2, 1f))
        // Playlist selector is shared by Live TV and Checker only.
        // Keep it out of Movies, Development, and Settings.
        if (section == "Live TV" || section == "Checker") {
            val playlistButton = text("Sany iptv Channel  ▾", 10, accent, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(9), 0, dp(9), 0)
                background = glass(10)
                isFocusable = true
                setOnClickListener { showPlaylistDialog() }
            }
            playlistSelectorContainer = playlistButton
            titleRow.addView(playlistButton, LinearLayout.LayoutParams(dp(142), dp(40)))
        }
        top.addView(titleRow)
        homeHeaderContainer = top
        // Full-width, edge-to-edge square header; remove the extra top gap.
        val headerLayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(if (isTvDevice) 4 else 10)
            leftMargin = 0
            rightMargin = 0
        }
        if (isTvDevice) tvContentColumn?.addView(top, headerLayoutParams)
        else home.addView(top, headerLayoutParams)
        updateHomeHeaderVisibility()
        // Keep Android 6 rendering simple; newer devices retain the header transition.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M || prefs.getBoolean("reduce_ui_motion", true)) {
            top.alpha = 1f
            top.translationY = 0f
            top.rotationX = 0f
        } else {
            top.alpha = 0f
            top.translationY = dp(-8).toFloat()
            top.post {
                top.animate().alpha(1f).translationY(0f).rotationX(0f)
                    .setDuration(320).setInterpolator(DecelerateInterpolator()).start()
            }
        }

        contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Anime, Movies, and Live Sports use full-width layouts without black side gutters.
            val edgeToEdgeSection = section == "Series" || section == "Movies" || section == "Live Sports"
            setPadding(if (edgeToEdgeSection) 0 else dp(18), 0,
                if (edgeToEdgeSection) 0 else dp(18), 0)
            setBackgroundColor(if (edgeToEdgeSection && isLight) Color.WHITE else bg)
        }
        if (isTvDevice) tvContentColumn?.addView(contentArea, LinearLayout.LayoutParams(-1, 0, 1f))
        else home.addView(contentArea, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomNav = LinearLayout(this).apply {
            orientation = if (isTvDevice) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(if (isTvDevice) 3 else 5), dp(if (isTvDevice) 6 else 2),
                dp(if (isTvDevice) 3 else 5), dp(if (isTvDevice) 6 else 2))
            // TV uses a narrow left-side rail; phones keep the bottom Liquid Glass dock.
            background = glass(0)
            elevation = dp(18).toFloat()
            translationZ = dp(8).toFloat()
        }
        if (isTvDevice) {
            home.addView(bottomNav, LinearLayout.LayoutParams(dp(104), -1))
            home.addView(tvContentColumn, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            home.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(74)))
        }
        renderSection()
        renderBottomNav()
        if (isTvDevice) root.post { focusInitialTvControl() }
    }

    private fun focusInitialTvControl() {
        val target = when {
            section == "Live TV" || section == "Movies" -> {
                if (::listContainer.isInitialized && listContainer.childCount > 0) {
                    val first = listContainer.getChildAt(0)
                    if (isTvDevice && first is ViewGroup && first.childCount > 0) first.getChildAt(0) else first
                } else bottomNav.getChildAt(0)
            }
            else -> bottomNav.getChildAt(
                (if (isTvDevice) listOf("Live TV", "Live Sports", "Movies", "Settings") else listOf("Live TV", "Live Sports", "Checker", "Movies", "Series", "Settings")).indexOf(section).coerceAtLeast(0)
            )
        }
        target?.requestFocus()
    }

    private fun applyTvFocusStyle(view: View) {
        if (!isTvDevice) return
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        // A single state-list foreground gives every focused remote item a clear,
        // high-contrast outline without allocating a new drawable on each focus event.
        val focusedOverlay = GradientDrawable().apply {
            setColor(Color.argb(if (isLight) 52 else 72, 55, 145, 255))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(3), accent)
        }
        val idleOverlay = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(12).toFloat()
        }
        view.foreground = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedOverlay)
            addState(intArrayOf(), idleOverlay)
        }
        if (Build.VERSION.SDK_INT >= 21) view.stateListAnimator = null
        if (Build.VERSION.SDK_INT >= 26) view.defaultFocusHighlightEnabled = false
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            focusedView.elevation = 0f
            // Let the parent scroll container reveal focus immediately; avoid
            // animated auto-scroll which can feel delayed on 1 GB TV boxes.
            if (hasFocus) focusedView.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, focusedView.width, focusedView.height), false
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isTvDevice) {
            // A TV remote Back key always stops the active stream immediately,
            // regardless of which section/menu currently sits behind the player.
            if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) &&
                playerScreen != null) {
                closePlayer()
                lastBackPressedAt = 0L
                return true
            }
            if (playerScreen != null) {
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        player?.let { active ->
                            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || !active.isPlaying) {
                                if (active.playbackState == Player.STATE_ENDED) active.seekToDefaultPosition()
                                active.play()
                            }
                            active.playWhenReady = true
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        player?.pause()
                        player?.playWhenReady = false
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        closePlayer()
                        return true
                    }
                    // OK/Enter on the fullscreen video toggles play/pause.
                    // In menus, OK remains the normal focused-item click action.
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isPlayerFullscreen) {
                            player?.let { active ->
                                if (active.isPlaying) {
                                    saveAnimeResumePosition(active, currentChannel)
                                    active.pause()
                                    active.playWhenReady = false
                                } else {
                                    if (active.playbackState == Player.STATE_ENDED) active.seekToDefaultPosition()
                                    active.playWhenReady = true
                                    active.play()
                                }
                            }
                            return true
                        }
                    }
                }
            }
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (player != null) { playAdjacentChannel(1); return true }
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (player != null) { playAdjacentChannel(-1); return true }
                }
                // DPAD remains ordinary menu navigation while the player is inline.
                // Only a fullscreen player consumes Up/Down as channel switching.
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (player != null && isPlayerFullscreen) { playAdjacentChannel(1); return true }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (player != null && isPlayerFullscreen) { playAdjacentChannel(-1); return true }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun renderSection() {
        contentArea.removeAllViews()
        if (section != "Movies") moviesWebView?.stopLoading()
        if (section != "Series") aniLightWebView?.stopLoading()
        if (section == "Settings") {
            renderSettings()
            return
        }
        if (section == "Series") {
            renderAniLightNative()
            return
        }
        if (section == "Movies") {
            renderNativeMovies(false)
            return
        }
        if (section == "Live Sports") {
            // A fresh visit always rebuilds the provider directory and fetches live homepage data.
            liveSportsCatalogueView = null
            renderLiveSportsExtensions()
            return
        }
        if (section == "Checker") {
            renderCheckerSection()
            return
        }
        if (channels.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(35), dp(24), dp(35))
            }
            empty.addView(text("▶", 46, accent, true).apply { gravity = Gravity.CENTER })
            empty.addView(text("Your screen is ready", 21, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(8)) })
            empty.addView(text("Add an M3U playlist to browse live channels, movies and series.", 14, muted).apply { gravity = Gravity.CENTER })
            empty.addView(actionButton("ADD PLAYLIST") { showPlaylistDialog() }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(22) })
            contentArea.addView(empty, LinearLayout.LayoutParams(-1, -1))
            return
        }

        // Keep a reference to the header selector so section changes can
        // reliably show it only on Live TV and Checker.
        playlistSelectorContainer?.visibility =
            if (section == "Live TV" || section == "Checker") View.VISIBLE else View.GONE

        // Keep the selected-category title/search bar outside every ScrollView.
        // This makes it stationary while channel rows scroll underneath it.
        categoryHeaderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }
        contentArea.addView(categoryHeaderContainer, LinearLayout.LayoutParams(-1, dp(42)).apply {
            topMargin = dp(18)
        })
        val categoriesScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(bg)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, dp(4))
        }
        categoriesScroll.addView(categoryRow)
        contentArea.addView(categoriesScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(5)
        })
        // Build the category directory once, after list/count views are initialized.
        countLabel = text("", 12, muted).apply { setPadding(dp(2), dp(4), 0, dp(8)) }
        contentArea.addView(countLabel)
        val listScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(listContainer)
        contentArea.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderCategories()
        renderChannelRows(listContainer)
        // Restore after rows have been measured; this also survives fullscreen rotation/recreation.
        val restoreY = pendingListScrollRestoreY
        listScroll.post { listScroll.scrollTo(0, restoreY.coerceAtLeast(0)) }
    }

    // Movies is temporarily disabled while the feature is being developed.
    private fun renderMoviesDevelopmentSection() {
        contentArea.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        page.addView(
            text("MOVIES", 22, white, true).apply {
                gravity = Gravity.CENTER
            }
        )
        page.addView(
            text("UNDER DEVELOPMENT", 16, accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(10))
            }
        )
        page.addView(
            text("এই বিভাগে কাজ চলছে। খুব শিগগিরই চালু হবে।", 14, muted).apply {
                gravity = Gravity.CENTER
            }
        )
        contentArea.addView(page, LinearLayout.LayoutParams(-1, -1))
    }

    private fun showMovieExtensionManager() {
        val actions = arrayOf("Browse Movie extensions", "Add Movie repository", "Manage installed extensions")
        AlertDialog.Builder(this)
            .setTitle("Movie Extension Manager")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> loadMovieExtensionCatalogue()
                    1 -> {
                        val field = EditText(this).apply {
                            hint = "CloudStream repo URL (HTTPS or cloudstreamrepo://)"
                            setSingleLine(true)
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                        }
                        AlertDialog.Builder(this).setTitle("Add Movie repository").setView(field)
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("ADD") { _, _ ->
                                val url = field.text.toString().trim()
                                runCatching { SanyMovieExtensionManager.addRepository(this, url) }
                                    .onSuccess { toast("Movie repository added"); loadMovieExtensionCatalogue() }
                                    .onFailure { toast("Repository error: ${it.message}") }
                            }.show()
                    }
                    2 -> showInstalledMovieExtensions()
                }
            }.setNegativeButton("CLOSE", null).show()
    }

    private fun loadMovieExtensionCatalogue() {
        toast("Movie repositories loading…")
        thread(name = "SANY-Movie-Extension-Catalogue") {
            val result = runCatching { SanyMovieExtensionManager.catalogue(applicationContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { entries ->
                    if (entries.isEmpty()) {
                        AlertDialog.Builder(this).setTitle("Movie Extensions")
                            .setMessage("কোনো Movie extension পাওয়া যায়নি। আগে একটি HTTPS Movie repository যোগ করো।")
                            .setPositiveButton("ADD REPOSITORY") { _, _ -> showMovieExtensionManager() }
                            .setNegativeButton("CLOSE", null).show()
                        return@onSuccess
                    }
                    val installed = SanyMovieExtensionManager.installed(this).associateBy { it.id }
                    val labels = entries.map { item ->
                        val current = installed[item.id]
                        if (current == null) "${item.name}  •  ${item.version}  •  INSTALL"
                        else "${item.name}  •  ${item.version}  •  INSTALLED ${current.version}"
                    }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Movie Extension Catalogue")
                        .setItems(labels) { _, index ->
                            val item = entries[index]
                            AlertDialog.Builder(this).setTitle(item.name)
                                .setMessage(item.description.ifBlank { "Version ${item.version}" } + "\\n\\nVersion: ${item.version}")
                                .setPositiveButton(if (installed.containsKey(item.id)) "UPDATE / REPAIR" else "INSTALL") { _, _ ->
                                    installMovieExtension(item)
                                }.setNegativeButton("CANCEL", null).show()
                        }.setNegativeButton("CLOSE", null).show()
                }.onFailure {
                    AlertDialog.Builder(this).setTitle("Movie repository error")
                        .setMessage(it.message ?: it.javaClass.simpleName)
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun installMovieExtension(extension: SanyMovieExtensionManager.Extension) {
        toast("Installing ${extension.name}…")
        thread(name = "SANY-Movie-Extension-Install") {
            val result = runCatching {
                SanyMovieExtensionManager.installOrUpdate(applicationContext, extension)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { loaded ->
                    if (loaded.success) {
                        // Refresh the active Movies page so the newly registered provider and
                        // its homepage catalogue appear immediately without restarting the app.
                        if (section == "Movies") renderSection()
                        AlertDialog.Builder(this).setTitle("Movie extension installed")
                            .setMessage("${loaded.pluginName}\\nProviders: ${loaded.providerNames.joinToString()}")
                            .setPositiveButton("SEARCH MOVIES") { _, _ ->
                                loaded.providerNames.firstOrNull()?.let { showMovieExtensionSearch(it) }
                            }.setNegativeButton("CLOSE", null).show()
                    } else {
                        toast("Install failed: ${loaded.error ?: "Unknown error"}")
                    }
                }.onFailure { toast("Install failed: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    private fun showInstalledMovieExtensions() {
        val installed = SanyMovieExtensionManager.installed(this)
        if (installed.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Installed Movie Extensions")
                .setMessage("এখনো কোনো Movie extension install করা হয়নি।")
                .setPositiveButton("BROWSE") { _, _ -> loadMovieExtensionCatalogue() }
                .setNegativeButton("CLOSE", null).show()
            return
        }
        val selected = SanyMovieExtensionManager.selectedIds(this).toMutableSet()
        val labels = installed.map { "${it.name}  •  ${it.version}" }.toTypedArray()
        val checked = BooleanArray(installed.size) { installed[it].id in selected }
        AlertDialog.Builder(this).setTitle("Select Movie Extensions")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                if (isChecked) selected.add(installed[index].id)
                else selected.remove(installed[index].id)
            }
            .setPositiveButton("SAVE SELECTION") { _, _ ->
                SanyMovieExtensionManager.setSelectedIds(this, selected)
                if (section == "Movies") renderSection()
                toast("Movie extension selection saved")
            }
            .setNeutralButton("MANAGE / REMOVE") { _, _ ->
                val actions = installed.map { "${it.name}  •  ${it.version}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Manage installed extensions")
                    .setItems(actions) { _, index ->
                        val item = installed[index]
                        AlertDialog.Builder(this).setTitle(item.name)
                            .setMessage("Version: ${item.version}")
                            .setPositiveButton("SEARCH") {
                                _, _ ->
                                val provider = SanyCloudStreamPluginRuntime.providers().firstOrNull {
                                    runCatching {
                                        java.io.File(it.sourcePlugin).canonicalPath ==
                                            java.io.File(item.packagePath).canonicalPath
                                    }.getOrDefault(false) &&
                                        it.supportedTypes.any { type ->
                                            type == com.lagradost.cloudstream3.TvType.Movie ||
                                                type == com.lagradost.cloudstream3.TvType.AnimeMovie
                                        }
                                }
                                if (provider == null) toast("এই extension-এর active Movie provider নেই")
                                else showMovieExtensionSearch(provider.name)
                            }
                            .setNeutralButton("UNINSTALL") { _, _ ->
                                runCatching { SanyMovieExtensionManager.uninstall(this, item.id) }
                                    .onSuccess {
                                        selected.remove(item.id)
                                        SanyMovieExtensionManager.setSelectedIds(this, selected)
                                        if (section == "Movies") renderSection()
                                        toast(if (it) "Movie extension removed" else "Remove failed")
                                    }
                                    .onFailure { toast("Remove failed: ${it.message}") }
                            }.setNegativeButton("CLOSE", null).show()
                    }.setNegativeButton("CLOSE", null).show()
            }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun showLiveSportsExtensionManager() {
        val actions = arrayOf("Browse Live Sports extensions", "Add Live Sports repository", "Manage installed extensions")
        AlertDialog.Builder(this).setTitle("Live Sports Extension Manager").setItems(actions) { _, which ->
            when (which) {
                0 -> loadLiveSportsExtensionCatalogue()
                1 -> {
                    val field = EditText(this).apply { hint = "CloudStream repo URL (HTTPS or cloudstreamrepo://)"; setSingleLine(true) }
                    AlertDialog.Builder(this).setTitle("Add Live Sports repository").setView(field)
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("ADD") { _, _ ->
                            runCatching { SanyLiveSportsExtensionManager.addRepository(this, field.text.toString().trim()) }
                                .onSuccess { toast("Repository added"); loadLiveSportsExtensionCatalogue() }
                                .onFailure { toast("Repository error: " + it.message) }
                        }.show()
                }
                2 -> showInstalledLiveSportsExtensions()
            }
        }.setNegativeButton("CLOSE", null).show()
    }

    private fun loadLiveSportsExtensionCatalogue() {
        thread(name = "SANY-LiveSports-Catalogue") {
            val result = runCatching { SanyLiveSportsExtensionManager.catalogue(applicationContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { entries ->
                    val installed = SanyLiveSportsExtensionManager.installed(this).associateBy { it.id }
                    if (entries.isEmpty()) toast("কোনো Live provider পাওয়া যায়নি")
                    else AlertDialog.Builder(this).setTitle("Live Sports Extension Catalogue")
                        .setItems(entries.map { it.name + " • " + it.version + if (installed.containsKey(it.id)) " • UPDATE" else " • INSTALL" }.toTypedArray()) { _, index ->
                            val item = entries[index]
                            AlertDialog.Builder(this).setTitle(item.name).setMessage(item.description)
                                .setPositiveButton(if (installed.containsKey(item.id)) "UPDATE" else "INSTALL") { _, _ ->
                                    thread(name = "SANY-LiveSports-Install") {
                                        val result2 = runCatching { SanyLiveSportsExtensionManager.installOrUpdate(this@MainActivity, item) }
                                        runOnUiThread { result2.onSuccess { loaded -> if (loaded.success) { if (section == "Live Sports") renderSection(); toast("Installed: " + loaded.providerNames.joinToString()) } else toast("Install failed: " + loaded.error) }.onFailure { toast("Install failed: " + it.message) } }
                                    }
                                }.setNegativeButton("CANCEL", null).show()
                        }.setNegativeButton("CLOSE", null).show()
                }.onFailure { toast("Repository error: " + it.message) }
            }
        }
    }

    private fun showInstalledLiveSportsExtensions() {
        val installed = SanyLiveSportsExtensionManager.installed(this)
        if (installed.isEmpty()) { toast("No Live Sports extensions installed"); loadLiveSportsExtensionCatalogue(); return }
        AlertDialog.Builder(this).setTitle("Manage Live Sports Extensions")
            .setItems(installed.map { it.name + " • " + it.version }.toTypedArray()) { _, index ->
                val item = installed[index]
                AlertDialog.Builder(this).setTitle(item.name).setMessage("Version " + item.version)
                    .setPositiveButton("UNINSTALL") { _, _ ->
                        val removed = runCatching { SanyLiveSportsExtensionManager.uninstall(this, item.id) }.getOrDefault(false)
                        if (section == "Live Sports") renderSection()
                        toast(if (removed) "Extension removed" else "Remove failed")
                    }.setNegativeButton("CLOSE", null).show()
            }.setNegativeButton("CLOSE", null).show()
    }

    private fun installBuiltInPlayZTV() {
        val progress = AlertDialog.Builder(this)
            .setTitle("PlayZTVProvider")
            .setMessage("Sports extension ডাউনলোড, যাচাই ও ইনস্টল হচ্ছে…")
            .setCancelable(false)
            .create()
        progress.show()
        thread(name = "SANY-PlayZTV-Install") {
            val result = runCatching {
                SanyLiveSportsExtensionManager.syncBuiltInPlayZTV(this@MainActivity)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                result.onSuccess { loaded ->
                    if (loaded.success) {
                        toast("PlayZTVProvider ইনস্টল ও Load হয়েছে")
                        if (section == "Live Sports") renderSection()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("PlayZTV Install Failed")
                            .setMessage(loaded.error ?: "Provider load failed")
                            .setPositiveButton("OK", null).show()
                    }
                }.onFailure { error ->
                    AlertDialog.Builder(this)
                        .setTitle("PlayZTV Install Failed")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun syncLiveSportsProviderInBackground(forceRetry: Boolean = false) {
        if (liveSportsProviderSyncRunning || isFinishing || isDestroyed) return
        if (liveSportsProviderSyncAttempted && !forceRetry) return
        liveSportsProviderSyncAttempted = true
        liveSportsProviderLastAttemptAt = System.currentTimeMillis()
        liveSportsProviderSyncRunning = true
        thread(name = "SANY-LiveSports-Provider-AutoSync") {
            var loaded: SanyCloudStreamPluginRuntime.LoadResult? = null
            var syncError: String? = null
            try {
                val outcome = runCatching {
                    SanyLiveSportsExtensionManager.syncBuiltInPlayZTV(this@MainActivity)
                }
                loaded = outcome.getOrNull()
                if (loaded?.success == true) {
                    android.util.Log.i("SANY-LIVE-SPORTS", "PlayZTVProvider automatically installed/updated and loaded")
                } else {
                    android.util.Log.e(
                        "SANY-LIVE-SPORTS",
                        "PlayZTVProvider auto-sync failed: ${loaded?.error ?: outcome.exceptionOrNull()?.message}",
                        outcome.exceptionOrNull()
                    )
                }
                runCatching {
                    SanyLiveSportsExtensionManager.loadInstalled(
                        this@MainActivity,
                        if (loaded?.success == true) setOf("playztvprovider") else emptySet()
                    )
                }.onSuccess { restored ->
                    restored.forEach { result ->
                        if (result.success) android.util.Log.i(
                            "SANY-LIVE-SPORTS", "Restored Live Sports provider: ${result.pluginName}"
                        ) else android.util.Log.e(
                            "SANY-LIVE-SPORTS", "Live Sports provider restore failed: ${result.error}"
                        )
                    }
                }.onFailure { error ->
                    android.util.Log.e("SANY-LIVE-SPORTS", "Installed Live Sports provider restore crashed", error)
                }
            } catch (error: Throwable) {
                syncError = error.message ?: error.javaClass.simpleName
                android.util.Log.e("SANY-LIVE-SPORTS", "Live Sports provider sync crashed", error)
            } finally {
                if (loaded?.success == true) {
                    liveSportsProviderLastError = null
                } else {
                    liveSportsProviderLastError =
                        loaded?.error ?: syncError ?: "Provider sync failed"
                    // Permit a later visit to retry after the short retry window.
                    liveSportsProviderSyncAttempted = false
                }
                runOnUiThread {
                    liveSportsProviderSyncRunning = false
                    if (!isFinishing && !isDestroyed && section == "Live Sports") {
                        liveSportsCatalogueView = null
                        renderSection()
                    }
                }
            }
        }
    }

    private fun renderLiveSportsExtensions() {
        liveSportsCatalogueView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            contentArea.addView(existing, LinearLayout.LayoutParams(-1, -1))
            return
        }
        contentArea.removeAllViews()
        val scroll = FastSportsScrollView(this).apply {
            isFillViewport = true
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(if (isLight) Color.WHITE else bg)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(28))
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(if (isLight) Color.WHITE else bg)
        }
        page.addView(text("LIVE SPORTS", 22, white, true))

        val installed = SanyLiveSportsExtensionManager.installed(this)
        // The curated package is the source of truth. Do not hide its provider just
        // because an extension build omits or mislabels TvType.Live in its metadata.
        val installedPaths = installed.mapNotNull { item ->
            runCatching { java.io.File(item.packagePath).canonicalPath }.getOrNull()
        }.toSet()
        val providers = SanyCloudStreamPluginRuntime.providers().filter { provider ->
            runCatching { java.io.File(provider.sourcePlugin).canonicalPath in installedPaths }
                .getOrDefault(false)
        }.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }

        if (providers.isEmpty()) {
            // Keep the built-in provider visible even while its runtime is restoring,
            // or when the extension is installed but has not registered its provider yet.
            val builtIn = installed.firstOrNull { it.id.equals("playztvprovider", true) }
            val retryAge = System.currentTimeMillis() - liveSportsProviderLastAttemptAt
            if (!liveSportsProviderSyncRunning &&
                (!liveSportsProviderSyncAttempted || retryAge >= 30_000L)
            ) {
                // A failed first-launch download must not leave Sports empty forever.
                // Re-entering this tab retries the pinned provider after a short backoff.
                syncLiveSportsProviderInBackground(forceRetry = true)
            }
            val providerCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(12))
                background = glass(16)
                elevation = dp(5).toFloat()
            }
            providerCard.addView(text("PlayZTVProvider", 18, white, true))
            providerCard.addView(text(
                when {
                    liveSportsProviderSyncRunning -> "PlayZTVProvider is installing/restoring…"
                    !liveSportsProviderLastError.isNullOrBlank() -> "Provider load failed: ${liveSportsProviderLastError}"
                    builtIn != null -> "Installed • Version ${builtIn.version} • Provider is not registered yet"
                    else -> "Built-in Live Sports provider • Waiting to install…"
                },
                12, muted
            ).apply { setPadding(0, dp(5), 0, dp(10)) })
            providerCard.addView(actionButton("RETRY / REPAIR PLAYZTV") {
                syncLiveSportsProviderInBackground(forceRetry = true)
            }, LinearLayout.LayoutParams(-1, dp(44)))
            page.addView(providerCard, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12)
                leftMargin = dp(12)
                rightMargin = dp(12)
            })
            page.addView(text(
                "Provider প্রস্তুত হলে এর Live Sports categories এখানে স্বয়ংক্রিয়ভাবে দেখাবে।",
                12, muted
            ).apply { setPadding(dp(12), dp(10), dp(12), dp(8)) })
        } else {
            providers.forEach { provider ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    // Keep the provider panel full width; only its controls get inner spacing.
                    setPadding(0, dp(10), 0, dp(10))
                    setBackgroundColor(if (isLight) Color.WHITE else 0xFF101820.toInt())
                }
                card.addView(text(provider.name, 17, white, true).apply {
                    setPadding(dp(12), dp(3), dp(12), 0)
                })
                card.addView(text("Live Sports provider", 12, muted).apply {
                    setPadding(dp(12), dp(3), dp(12), dp(10))
                })
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), 0, dp(12), 0)
                }
                actions.addView(actionButton("LOAD HOME") { loadLiveSportsHome(provider.name, card) },
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
                actions.addView(actionButton("SEARCH") { showLiveSportsSearch(provider.name, card) },
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
                card.addView(actions)
                page.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(10)
                })
                // Load automatically so opening Live Sports never shows an empty shell.
                loadLiveSportsHome(provider.name, card)
            }
        }
        scroll.addView(page)
        liveSportsCatalogueView = scroll
        contentArea.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun sportsCategory(title: String): String {
        val value = title.lowercase(java.util.Locale.ROOT)
        return when {
            listOf("cricket", "ipl", "t20", "test match", "odi", "ashes", "bbl", "psl").any { it in value } -> "Cricket"
            listOf("football", "soccer", "premier league", "epl", "ucl", "uefa", "fifa", "la liga", "serie a").any { it in value } -> "Football"
            listOf("basketball", "nba", "wnba", "euroleague").any { it in value } -> "Basketball"
            listOf("tennis", "atp", "wta", "grand slam", "wimbledon", "us open").any { it in value } -> "Tennis"
            listOf("wwe", "wwf", "wrestling", "aew", "wrestle").any { it in value } -> "WWE / Wrestling"
            listOf("boxing", "mma", "ufc", "bellator", "one championship", "fight night").any { it in value } -> "Boxing / MMA"
            listOf("badminton", "bwf").any { it in value } -> "Badminton"
            listOf("volleyball", "volley").any { it in value } -> "Volleyball"
            listOf("rugby", "six nations").any { it in value } -> "Rugby"
            listOf("nfl", "american football", "super bowl").any { it in value } -> "American Football"
            listOf("baseball", "mlb").any { it in value } -> "Baseball"
            listOf("hockey", "nhl", "ice hockey").any { it in value } -> "Hockey"
            listOf("formula 1", "formula one", "f1", "motogp", "nascar", "racing", "motorsport").any { it in value } -> "Motorsports"
            listOf("golf", "pga", "lpga", "masters tournament").any { it in value } -> "Golf"
            else -> "Other Sports"
        }
    }

    private fun loadLiveSportsHome(providerName: String, host: LinearLayout) {
        val old = host.findViewWithTag<View>("sports-results-$providerName")
        if (old != null) host.removeView(old)
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "sports-results-$providerName"
        }
        host.addView(results, LinearLayout.LayoutParams(-1, -2))
        val declaredCategories = if (providerName.equals("PlayZTVProvider", true)) {
            SanyCloudStreamPluginRuntime.providers()
                .firstOrNull { it.name.equals(providerName, true) }
                ?.mainPage.orEmpty().map { it.name }.filter { it.isNotBlank() }.distinct()
        } else emptyList()
        if (declaredCategories.isNotEmpty()) {
            declaredCategories.forEach { category ->
                results.addView(text("$category  •  লোড হচ্ছে…", 16, white, true)
                    .apply { setPadding(dp(8), dp(14), dp(8), dp(7)) })
            }
        } else {
            results.addView(text("Sports হোমপেজ লোড হচ্ছে…", 13, muted)
                .apply { setPadding(dp(8), dp(12), dp(8), dp(8)) })
        }
        thread(name = "SANY-LiveSports-Home") {
            val outcome = runCatching {
                kotlinx.coroutines.runBlocking {
                    val provider = SanyCloudStreamPluginRuntime.providers()
                        .firstOrNull { it.name.equals(providerName, true) }
                        ?: error("Provider is not loaded: $providerName")
                    val realShelves = if (provider.hasMainPage && provider.mainPage.isNotEmpty()) {
                        runCatching {
                            kotlinx.coroutines.withTimeout(45_000L) {
                                if (providerName.equals("PlayZTVProvider", true)) {
                                    // Keep every provider-declared category visible, even when
                                    // its event list is temporarily empty or slow to respond.
                                    SanyCloudStreamPluginRuntime.homePageSections(
                                        providerName, 1, shelfTimeoutMs = 20_000L
                                    )
                                } else {
                                    SanyCloudStreamPluginRuntime.homePage(providerName, 1, forceRefresh = false)
                                        .map { shelf -> shelf.name to shelf.list }
                                }
                            }
                        }.onFailure { error ->
                            android.util.Log.w("SANY-LIVE-SPORTS", "$providerName homepage request failed; trying parallel search fallback", error)
                        }.getOrDefault(emptyList())
                    } else emptyList()
                    if (realShelves.isNotEmpty()) {
                        realShelves
                    } else {
                        // Search fallback runs concurrently; the old sequential loop could
                        // leave the Sports page blank for up to 40 seconds.
                        val queries = listOf("sports", "football", "cricket", "live", "wwe")
                        val queryResults = kotlinx.coroutines.coroutineScope {
                            queries.map { query ->
                                async(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        kotlinx.coroutines.withTimeout(8_000L) {
                                            SanyCloudStreamPluginRuntime.search(providerName, query, forceRefresh = false)
                                        }
                                    }.onFailure { error ->
                                        android.util.Log.w("SANY-LIVE-SPORTS", "$providerName search failed for $query", error)
                                    }.getOrDefault(emptyList())
                                }
                            }.let { jobs -> kotlinx.coroutines.withTimeout(9_000L) { jobs.awaitAll() } }
                        }
                        queryResults.flatten()
                            .distinctBy { it.url.trim().lowercase() }
                            .filter { it.name.isNotBlank() && it.url.isNotBlank() }
                            .groupBy { sportsCategory(it.name) }
                            .toList()
                            .sortedBy { it.first.lowercase() }
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || section != "Live Sports") return@runOnUiThread
                results.removeAllViews()
                if (outcome.isFailure) {
                    val message = outcome.exceptionOrNull()?.message ?: "Unknown provider error"
                    android.util.Log.e("SANY-LIVE-SPORTS", "$providerName catalogue failed: $message", outcome.exceptionOrNull())
                    results.addView(text("Catalogue load failed: $message", 13, muted)
                        .apply { setPadding(dp(8), dp(12), dp(8), dp(8)) })
                    results.addView(actionButton("RETRY LOAD SPORTS HOME") {
                        loadLiveSportsHome(providerName, host)
                    }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
                } else {
                    val shelves = outcome.getOrDefault(emptyList())
                    android.util.Log.i("SANY-LIVE-SPORTS", "$providerName returned ${shelves.size} category shelves")
                    val visibleShelves = shelves.filter { it.first.isNotBlank() }
                    if (visibleShelves.isEmpty()) {
                        results.addView(text("এই extension-এ এখন কোনো Sports category পাওয়া যায়নি। SEARCH দিয়ে চেষ্টা করুন।", 13, muted)
                            .apply { setPadding(dp(8), dp(12), dp(8), dp(8)) })
                        results.addView(actionButton("RELOAD SPORTS HOME") {
                            loadLiveSportsHome(providerName, host)
                        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
                    } else {
                        visibleShelves.forEach { (categoryName, rawItems) ->
                            val categoryItems = rawItems.distinctBy { it.url.trim().lowercase() }
                                .filter { it.name.isNotBlank() && it.url.isNotBlank() }
                            val heading = text("$categoryName  •  ${categoryItems.size}", 16, white, true)
                                .apply { setPadding(dp(8), dp(14), dp(8), dp(7)) }
                            results.addView(heading, LinearLayout.LayoutParams(-1, -2))
                            if (categoryItems.isEmpty()) {
                                results.addView(text("এই ক্যাটাগরিতে এখন কোনো ইভেন্ট নেই", 12, muted)
                                    .apply { setPadding(dp(8), 0, dp(8), dp(8)) })
                            } else {
                                val screenWidthDp = resources.displayMetrics.widthPixels /
                                    resources.displayMetrics.density
                                // Compact banners: keep them smaller than the viewport while
                                // preserving a wide landscape shape and horizontal browsing.
                                val cardWidth = (screenWidthDp * 0.82f).toInt().coerceAtLeast(140)
                                val bannerHeight = (cardWidth * 0.48f).toInt().coerceIn(112, 168)
                                val rail = LinearLayout(this).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    clipChildren = false
                                    clipToPadding = false
                                }
                                val categoryScroller = android.widget.HorizontalScrollView(this).apply {
                                    isHorizontalScrollBarEnabled = false
                                    isFillViewport = false
                                    overScrollMode = View.OVER_SCROLL_NEVER
                                    clipToPadding = false
                                    setPadding(0, 0, 0, dp(8))
                                    addView(rail, ViewGroup.LayoutParams(-2, -2))
                                }
                                categoryItems.forEach { item ->
                                    val isNowPlaying =
                                        prefs.getString("sany_last_played_section", "") == "Live Sports" &&
                                        prefs.getString("sany_last_played_provider", "").equals(providerName, true) &&
                                        (prefs.getString("sany_last_played_item_url", "") == item.url ||
                                            prefs.getString("sany_last_played_title", "").equals(item.name, true))
                                    val card = FrameLayout(this).apply {
                                        background = GradientDrawable().apply {
                                            setColor(if (isNowPlaying) 0xFF123E70.toInt() else if (isLight) Color.WHITE else 0xFF202D43.toInt())
                                            cornerRadius = dp(8).toFloat()
                                            if (isNowPlaying) setStroke(dp(3), 0xFF35B8FF.toInt())
                                        }
                                        clipToOutline = true
                                        isClickable = true
                                        isFocusable = true
                                        if (isNowPlaying) elevation = dp(9).toFloat()
                                        setOnClickListener { playLiveSportsItem(providerName, item.name, item.url) }
                                    }
                                    val image = ImageView(this).apply {
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        setBackgroundColor(if (isLight) Color.WHITE else 0xFF202D43.toInt())
                                    }
                                    val placeholder = TextView(this).apply {
                                        text = "▶  LIVE SPORTS"
                                        textSize = 11f
                                        gravity = Gravity.CENTER
                                        setTextColor(white)
                                        setBackgroundColor(if (isLight) Color.WHITE else 0xFF202D43.toInt())
                                    }
                                    card.addView(image, FrameLayout.LayoutParams(-1, -1))
                                    card.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
                                    item.posterUrl?.takeIf { it.isNotBlank() }?.let {
                                        loadCachedChannelLogo(image, placeholder, it)
                                    }
                                    val titleOverlay = TextView(this).apply {
                                        text = item.name
                                        textSize = 11f
                                        maxLines = 2
                                        ellipsize = android.text.TextUtils.TruncateAt.END
                                        gravity = Gravity.BOTTOM or Gravity.START
                                        setTextColor(Color.WHITE)
                                        setPadding(dp(7), dp(18), dp(7), dp(6))
                                        background = GradientDrawable(
                                            GradientDrawable.Orientation.TOP_BOTTOM,
                                            intArrayOf(0x00000000, 0xD9000000.toInt())
                                        )
                                    }
                                    card.addView(titleOverlay, FrameLayout.LayoutParams(-1, dp(42), Gravity.BOTTOM))
                                    if (isNowPlaying) {
                                        card.addView(text("▶ PLAYING", 9, Color.WHITE, true).apply {
                                            setPadding(dp(7), dp(4), dp(7), dp(4))
                                            background = rounded(0xFF0875F5.toInt(), 14)
                                        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                                            setMargins(dp(6), dp(6), 0, 0)
                                        })
                                    }
                                    rail.addView(card, LinearLayout.LayoutParams(dp(cardWidth), dp(bannerHeight)).apply {
                                        // Small gutters separate compact banner cards.
                                        marginEnd = dp(8)
                                        bottomMargin = dp(2)
                                    })
                                }
                                results.addView(categoryScroller, LinearLayout.LayoutParams(-1, -2))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showLiveSportsSearch(providerName: String, host: LinearLayout) {
        val field = EditText(this).apply {
            hint = "Football, Cricket, WWE…"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Search • $providerName")
            .setView(field)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SEARCH") { _, _ ->
                val query = field.text?.toString()?.trim().orEmpty()
                if (query.isBlank()) return@setPositiveButton
                thread(name = "SANY-LiveSports-Search") {
                    val result = runCatching {
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeout(18_000L) {
                                SanyCloudStreamPluginRuntime.search(providerName, query)
                            }
                        }
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || section != "Live Sports") return@runOnUiThread
                        val resultHost = host.findViewWithTag<LinearLayout>("sports-results-$providerName")
                        if (resultHost != null) {
                            resultHost.removeAllViews()
                            result.onSuccess { items ->
                                if (items.isEmpty()) {
                                    resultHost.addView(text("কোনো ফলাফল পাওয়া যায়নি: $query", 13, muted))
                                } else {
                                    items.distinctBy { it.url.trim().lowercase() }.forEach { item ->
                                        resultHost.addView(actionButton("▶  ${item.name}") {
                                            playLiveSportsItem(providerName, item.name, item.url)
                                        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
                                    }
                                }
                            }.onFailure { resultHost.addView(text("Search failed: ${it.message}", 13, muted)) }
                        }
                    }
                }
            }.show()
    }

    private fun playLiveSportsItem(providerName: String, title: String, data: String) {
        toast("Loading $title…")
        thread(name = "SANY-LiveSports-Resolve") {
            val result = runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(25_000L) {
                        SanyCloudStreamPluginRuntime.resolvePlayback(providerName, title, data)
                    }
                }
            }
            runOnUiThread {
                animeEpisodeSourceResolving = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { sources ->
                    if (sources.isEmpty()) {
                        toast("এই Sports item-এর কোনো playable source পাওয়া যায়নি")
                    } else {
                        // Remember the catalogue identity (not the resolved stream URL),
                        // so the same Sports poster can be highlighted after playback starts.
                        prefs.edit()
                            .putString("sany_last_played_section", "Live Sports")
                            .putString("sany_last_played_provider", providerName)
                            .putString("sany_last_played_title", title)
                            .putString("sany_last_played_item_url", data)
                            .apply()
                        // Keep every returned Sports stream as a fallback. Some hosts
                        // resolve successfully but return a stream that never renders video.
                        animeFallbackRequests = sources
                        animeFallbackIndex = 0
                        // Keep the exact Live Sports catalogue attached underneath
                        // the player. Rebuilding it here used to reset category/scroll state.
                        playSanyPlaybackRequest(sources.first(), preserveFallbackQueue = true)
                    }
                }.onFailure {
                    android.util.Log.e("SANY-LIVE-SPORTS", "Resolve failed for $title", it)
                    toast("Sports playback failed: ${it.message ?: it.javaClass.simpleName}")
                }
            }
        }
    }
    private fun showMovieExtensionSearch(providerName: String) {
        val field = EditText(this).apply { hint = "Search movie title"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Search • $providerName").setView(field)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SEARCH") { _, _ ->
                val query = field.text.toString().trim()
                if (query.isBlank()) return@setPositiveButton
                thread(name = "SANY-Movie-Search") {
                    val result = runCatching { kotlinx.coroutines.runBlocking { SanyCloudStreamPluginRuntime.search(providerName, query) } }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        result.onSuccess { items ->
                            if (items.isEmpty()) {
                                toast("কোনো Movie পাওয়া যায়নি")
                            } else {
                                AlertDialog.Builder(this).setTitle("Movie results • ${items.size}")
                                    .setItems(items.map { it.name }.toTypedArray()) { _, index ->
                                        openMovieExtensionTitle(providerName, items[index].url, items[index].name)
                                    }.setNegativeButton("CLOSE", null).show()
                            }
                        }.onFailure { toast("Movie search failed: ${it.message}") }
                    }
                }
            }.show()
    }

    private fun openMovieExtensionTitle(providerName: String, url: String, fallbackTitle: String) {
        toast("Loading details…")
        thread(name = "SANY-Movie-Details") {
            val result = runCatching {
                kotlinx.coroutines.runBlocking { SanyCloudStreamPluginRuntime.load(providerName, url) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { response ->
                    if (response == null) {
                        toast("Extension থেকে Movie details পাওয়া যায়নি")
                    } else {
                        showMovieExtensionDetails(providerName, response, fallbackTitle)
                    }
                }.onFailure { toast("Movie details failed: ${it.message}") }
            }
        }
    }

    private fun restoreMovieDetailsParent() {
        val parent = movieDetailsParentView ?: moviesNativeView
        contentArea.removeAllViews()
        if (parent != null) {
            (parent.parent as? ViewGroup)?.removeView(parent)
            contentArea.addView(parent, LinearLayout.LayoutParams(-1, -1))
        } else {
            renderNativeMovies(false)
        }
        // Consume this details-page return point exactly once so the next Back
        // continues through the Movies category/page history instead of reopening it.
        movieDetailsParentView = null
    }

    /** Anime-style details page: select an episode first, then choose its source before playback. */
    private fun showMovieExtensionDetails(
        providerName: String,
        response: com.lagradost.cloudstream3.LoadResponse,
        fallbackTitle: String
    ) {
        val title = response.name.ifBlank { fallbackTitle }
        val episodes = (response as? com.lagradost.cloudstream3.TvSeriesLoadResponse)
            ?.episodes.orEmpty()
        val movieData = (response as? com.lagradost.cloudstream3.MovieLoadResponse)?.dataUrl
        val posterUrl = response.posterUrl.orEmpty()
        val plot = response.plot.orEmpty()

        // Retain the exact Movies catalogue/provider page so Back restores its
        // category page, active search/filter and scroll position.
        movieDetailsParentView = contentArea.getChildAt(0)
        contentArea.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(page)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹  BACK"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFF242832.toInt(), 12)
            setOnClickListener { restoreMovieDetailsParent() }
        }
        header.addView(back)
        header.addView(text("MOVIE DETAILS", 14, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(12)
        })
        page.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val hero = FrameLayout(this).apply {
            background = rounded(0xFF20242C.toInt(), 18)
            clipToOutline = true
        }
        val heroImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF20242C.toInt())
            tag = posterUrl
        }
        hero.addView(heroImage, FrameLayout.LayoutParams(-1, -1))
        if (posterUrl.startsWith("http", true)) loadMovieDetailImage(posterUrl, heroImage)
        val shade = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xCC000000.toInt(), 0xF5000000.toInt())
            )
        }
        hero.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val heroTitle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        heroTitle.addView(text(if (episodes.isNotEmpty()) "SERIES" else "MOVIE", 11, 0xFFB9D7FF.toInt(), true))
        heroTitle.addView(text(title, 23, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        hero.addView(heroTitle, FrameLayout.LayoutParams(-1, -1))
        page.addView(hero, LinearLayout.LayoutParams(-1, dp(224)).apply { bottomMargin = dp(14) })

        if (plot.isNotBlank()) {
            page.addView(text(plot, 13, 0xFFD0D0D0.toInt()).apply {
                setPadding(dp(4), dp(2), dp(4), dp(12))
            })
        }

        if (episodes.isNotEmpty()) {
            page.addView(text("EPISODES  •  ${episodes.size}", 17, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(10) })
            episodes.forEachIndexed { index, episode ->
                val episodeName = episode.name?.takeIf { it.isNotBlank() } ?: "Episode ${index + 1}"
                val episodeLabel = buildString {
                    episode.season?.let { append("S").append(it).append("  ") }
                    episode.episode?.let { append("E").append(it).append("  ") }
                    append(episodeName)
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    background = rounded(0xFF1D222B.toInt(), 13)
                    isFocusable = true
                    setOnClickListener {
                        resolveMovieExtensionPlayback(providerName, "$title • $episodeLabel", episode.data)
                    }
                }
                val playIcon = TextView(this).apply {
                    text = "▶"
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(0xFF315E9E.toInt(), 30)
                }
                row.addView(playIcon, LinearLayout.LayoutParams(dp(42), dp(42)))
                row.addView(text(episodeLabel, 14, Color.WHITE, true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(12), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("›", 25, 0xFFB9D7FF.toInt(), true))
                page.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        } else if (!movieData.isNullOrBlank()) {
            page.addView(text("PLAYBACK", 17, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(10) })
            val chooseSource = TextView(this).apply {
                text = "▶   SELECT SOURCE"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(accent, 14)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setOnClickListener { resolveMovieExtensionPlayback(providerName, title, movieData) }
            }
            page.addView(chooseSource, LinearLayout.LayoutParams(-1, dp(54)))
        } else {
            page.addView(text("এই কনটেন্টে কোনো Episode বা playable Movie data পাওয়া যায়নি।", 13, 0xFFBDBDBD.toInt()))
        }
        contentArea.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun loadMovieDetailImage(imageUrl: String, target: ImageView) {
        target.tag = imageUrl
        thread(name = "SANY-Movie-Detail-Image") {
            val bitmap = runCatching {
                val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 4500
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 SANY-IPTV")
                }
                try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            if (bitmap != null) runOnUiThread {
                if (target.tag == imageUrl && !isFinishing && !isDestroyed) target.setImageBitmap(bitmap)
                else if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun resolveMovieExtensionPlayback(providerName: String, title: String, data: String) {
        val resolveStartedAt = System.currentTimeMillis()
        val requestId = ++movieStreamingRequestId
        movieStreamingSourceDialog?.dismiss()
        movieStreamingSourceDialog = null
        movieStreamingSourceAdapter = null
        movieStreamingSourceRequests = mutableListOf()
        movieStreamingPlaybackStarted = false
        movieStreamingPickerDismissed = false
        android.util.Log.i("SANY-MOVIE", "Movie source request: provider=$providerName title=$title dataLength=${data.length}")
        toast("Resolving $title…")
        thread(name = "SANY-Movie-Resolve") {
            val links = runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(25_000L) {
                        SanyCloudStreamPluginRuntime.resolvePlayback(providerName, title, data) { source ->
                            runOnUiThread {
                                publishMovieStreamingSources(requestId, title, listOf(source), finished = false)
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || requestId != movieStreamingRequestId) return@runOnUiThread
                links.onSuccess { sources ->
                    android.util.Log.i(
                        "SANY-MOVIE",
                        "Movie source request finished: provider=$providerName elapsedMs=${System.currentTimeMillis() - resolveStartedAt} sources=${sources.size}"
                    )
                    publishMovieStreamingSources(requestId, title, sources, finished = true)
                    if (sources.isEmpty()) toast("এই Movie/Episode-এর কোনো playable source পাওয়া যায়নি")
                }.onFailure { error ->
                    android.util.Log.e(
                        "SANY-MOVIE",
                        "Movie source request failed: provider=$providerName elapsedMs=${System.currentTimeMillis() - resolveStartedAt}",
                        error
                    )
                    if (movieStreamingSourceRequests.isEmpty()) toast("Source resolve failed: ${error.message}")
                    else movieStreamingSourceDialog?.setTitle("$title • source search stopped")
                }
            }
        }
    }

    private fun publishMovieStreamingSources(
        requestId: Int,
        title: String,
        incoming: List<SanyPlaybackRequest>,
        finished: Boolean
    ) {
        if (isFinishing || isDestroyed || requestId != movieStreamingRequestId) return
        incoming.filter { it.url.isNotBlank() }.forEach { source ->
            val existingIndex = movieStreamingSourceRequests.indexOfFirst { it.url == source.url }
            if (existingIndex >= 0) movieStreamingSourceRequests[existingIndex] = source
            else movieStreamingSourceRequests.add(source)
        }
        if (movieStreamingPlaybackStarted) {
            // Keep later-arriving links available to the existing playback fallback queue.
            animeFallbackRequests = movieStreamingSourceRequests.toList()
            return
        }
        if (movieStreamingPickerDismissed || movieStreamingSourceRequests.isEmpty()) return

        val labels = movieStreamingSourceRequests.mapIndexed { index, request ->
            val provider = request.provider.ifBlank { "Source ${index + 1}" }
            val quality = request.quality?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
            val type = request.streamMimeType?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?.let { " • ${it.uppercase()}" }.orEmpty()
            "$provider$quality$type"
        }
        var adapter = movieStreamingSourceAdapter
        if (adapter == null) {
            adapter = android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                mutableListOf<String>()
            )
            movieStreamingSourceAdapter = adapter
            val created = AlertDialog.Builder(this)
                .setTitle(if (finished) title else "$title • আরও source খোঁজা হচ্ছে…")
                .setAdapter(adapter) { _, selectedIndex ->
                    val available = movieStreamingSourceRequests.toList()
                    movieStreamingPlaybackStarted = true
                    movieStreamingSourceDialog = null
                    startAnimeSourceFallback(available, selectedIndex)
                }
                .setNegativeButton("বন্ধ", null)
                .create()
            movieStreamingSourceDialog = created
            created.setOnDismissListener {
                if (movieStreamingSourceDialog === created) {
                    movieStreamingSourceDialog = null
                    movieStreamingSourceAdapter = null
                    movieStreamingPickerDismissed = true
                }
            }
            created.show()
        }
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
        movieStreamingSourceDialog?.setTitle(
            if (finished) title else "$title • আরও source খোঁজা হচ্ছে…"
        )
    }

    private fun renderNativeMovies(showSeries: Boolean) {
        if (showSeries) {
            renderDevelopmentSection()
            return
        }
        if (isLowRamTvDevice && !movieProviderSyncStarted) {
            movieProviderSyncStarted = true
            thread(name = "SANY-Movie-Provider-LazySync") {
                val result = runCatching {
                    SanyMovieExtensionManager.removeVegaMovies(applicationContext)
                    SanyMovieExtensionManager.syncBuiltInMultiMovies(applicationContext)
                }
                val loaded = result.getOrNull()
                if (loaded?.success == true) {
                    runCatching {
                        SanyMovieExtensionManager.loadInstalled(applicationContext, setOf("multimovies"))
                    }.onFailure { android.util.Log.e("SANY-MOVIE", "Lazy provider restore failed", it) }
                } else {
                    movieProviderSyncStarted = false
                    android.util.Log.e("SANY-MOVIE", "Lazy MultiMovies sync failed: ${loaded?.error ?: result.exceptionOrNull()?.message}")
                }
                if (loaded?.success == true) runOnUiThread {
                    if (!isFinishing && !isDestroyed && section == "Movies") {
                        contentArea.removeAllViews()
                        moviesNativeView = null
                        renderNativeMovies(false)
                    }
                }
            }
        }
        moviesNativeView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            contentArea.addView(existing, LinearLayout.LayoutParams(-1, -1))
            return
        }
        val screen = NativeMoviesScreen(
            context = this,
            seriesMode = false,
            light = isLight,
            accent = accent,
            onPlay = { title, url -> play(Channel(title, url, "Movies")) },
            onManageExtensions = { showMovieExtensionManager() },
            onOpenExtensionProvider = { providerName -> showMovieExtensionSearch(providerName) },
            onOpenExtensionTitle = { providerName, url, title ->
                openMovieExtensionTitle(providerName, url, title)
            }
        )
        moviesNativeView = screen.create()
        contentArea.addView(moviesNativeView, LinearLayout.LayoutParams(-1, -1))
    }

    // Series is intentionally disabled. Keep this page empty except for its label.
    private fun renderDevelopmentSection() {
        contentArea.removeAllViews()
        contentArea.addView(
            text("Development", 16, muted, true).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -1)
        )
    }

    // Anime Universe UI/template removed. Keep the Series page free of placeholder text.
    /**
     * Single SANY player entry point for provider-neutral playback data.
     * CloudStream ExtractorLink/SubtitleFile values must be mapped to this DTO
     * by a host bridge; CloudStream classes never enter MainActivity.
     */
    private var animeLoadingOverlay: View? = null
    private var animePlaybackLoadingRequested = false
    // Every playback type is held silent until its first decoded frame is rendered.
    private var animeAutoPlayWhenReady = false
    private var animeFirstFrameRendered = false
    private var animeFullscreenBufferingOverlay: View? = null

    // Anime loading is drawn only inside the fullscreen video surface.
    private fun showAnimePlaybackLoading(title: String, show: Boolean) {
        animePlaybackLoadingRequested = show
        if (show) animeFirstFrameRendered = false
        runOnUiThread { updateAnimeFullscreenBuffering() }
    }

    private fun updateAnimeFullscreenBuffering() {
        // Keep all Anime loading feedback attached to the actual video surface.
        // PlayerView has the same bounds in FIT and fullscreen, so the spinner
        // never covers the page or appears outside the video.
        // This overlay is only for the three requested playback sections.
        val isSupportedPlayback = section == "Live Sports" ||
            section == "Movies" || section == "Series"
        val activePlayer = player
        // isLoading can remain true while Media3 is downloading ahead of the
        // playhead; it is NOT a reason to cover video that is already playing.
        // Show rebuffering only when playback is actually stalled and the user
        // still intends to play. Initial loading remains controlled by the
        // explicit request / first-frame gate.
        val isGenuineRebuffer = activePlayer?.playbackState == Player.STATE_BUFFERING &&
            activePlayer.playWhenReady && !activePlayer.isPlaying
        val shouldShow = isSupportedPlayback && playerView != null &&
            (animePlaybackLoadingRequested ||
                (!animeFirstFrameRendered && activePlayer?.playbackState != Player.STATE_IDLE) ||
                isGenuineRebuffer)
        animeFullscreenBufferingOverlay?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private var movieSourcePickerDialog: AlertDialog? = null
    private var moviePlaybackStartedAtMs = 0L
    // Movies only: show each valid source as soon as the provider emits it.
    private var movieStreamingRequestId = 0
    private var movieStreamingSourceRequests = mutableListOf<SanyPlaybackRequest>()
    private var movieStreamingSourceDialog: AlertDialog? = null
    private var movieStreamingSourceAdapter: android.widget.ArrayAdapter<String>? = null
    private var movieStreamingPlaybackStarted = false
    private var movieStreamingPickerDismissed = false
    private var animeEpisodeSourceResolving = false
    private var animeSourcePickerDialog: AlertDialog? = null
    // Anime Series only: publish each source as its extractor emits it.
    private var animeStreamingRequestId = 0
    private var animeStreamingSourceRequests = mutableListOf<SanyPlaybackRequest>()
    private var animeStreamingSourceAdapter: android.widget.ArrayAdapter<String>? = null
    private var animeStreamingPlaybackStarted = false
    private var animeStreamingPickerDismissed = false
    private var subtitleTrackDialog: android.app.Dialog? = null
    // The catalogue item that opened the current CloudStream Anime details page.
    private var pendingCloudStreamAnimeItem: Triple<String, String, String>? = null

    private fun publishAnimeStreamingSources(
        requestId: Int,
        title: String,
        incoming: List<SanyPlaybackRequest>,
        finished: Boolean
    ) {
        if (isFinishing || isDestroyed || requestId != animeStreamingRequestId) return
        incoming.filter { it.url.isNotBlank() }.forEach { source ->
            val existingIndex = animeStreamingSourceRequests.indexOfFirst { it.url == source.url }
            if (existingIndex >= 0) animeStreamingSourceRequests[existingIndex] = source
            else animeStreamingSourceRequests.add(source)
        }
        if (animeStreamingPlaybackStarted) {
            animeFallbackRequests = animeStreamingSourceRequests.toList()
            return
        }
        if (animeStreamingPickerDismissed || animeStreamingSourceRequests.isEmpty()) return
        val labels = animeStreamingSourceRequests.mapIndexed { index, request ->
            val provider = request.provider.ifBlank { "Source ${index + 1}" }
            val quality = request.quality?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
            val type = request.streamMimeType?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?.let { " • ${it.uppercase()}" }.orEmpty()
            "$provider$quality$type"
        }
        var adapter = animeStreamingSourceAdapter
        if (adapter == null) {
            adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
            animeStreamingSourceAdapter = adapter
            val created = AlertDialog.Builder(this)
                .setTitle(if (finished) title else "$title • আরও source খোঁজা হচ্ছে…")
                .setAdapter(adapter) { _, selectedIndex ->
                    val available = animeStreamingSourceRequests.toList()
                    animeStreamingPlaybackStarted = true
                    animeSourcePickerDialog = null
                    animeStreamingSourceAdapter = null
                    startAnimeSourceFallback(available, selectedIndex)
                }
                .setNegativeButton("বন্ধ", null)
                .create()
            animeSourcePickerDialog = created
            created.setOnDismissListener {
                if (animeSourcePickerDialog === created) {
                    animeSourcePickerDialog = null
                    animeStreamingSourceAdapter = null
                    animeStreamingPickerDismissed = true
                }
            }
            created.show()
        }
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
        animeSourcePickerDialog?.setTitle(if (finished) title else "$title • আরও source খোঁজা হচ্ছে…")
    }

    private fun showAnimeSourcePicker(title: String, requests: List<SanyPlaybackRequest>) {
        if (requests.isEmpty() || isFinishing || isDestroyed || animeSourcePickerDialog?.isShowing == true) return
        val ordered = requests.distinctBy { it.url }.filter { it.url.isNotBlank() }
        if (ordered.isEmpty()) {
            toast("কোনো valid video source পাওয়া যায়নি")
            return
        }

        // Let the user choose a source before playback in both Movies and Anime.
        val labels = ordered.mapIndexed { index, request ->
            val provider = request.provider.ifBlank { "Source ${index + 1}" }
            val quality = request.quality?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
            val type = request.streamMimeType?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?.let { " • ${it.uppercase()}" }.orEmpty()
            "$provider$quality$type"
        }.toTypedArray()

        animeSourcePickerDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, selectedIndex ->
                startAnimeSourceFallback(ordered, selectedIndex)
            }
            .setNegativeButton("বন্ধ", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (animeSourcePickerDialog === dialog) animeSourcePickerDialog = null
                }
                dialog.show()
            }
    }

    private fun playSanyPlaybackRequest(request: SanyPlaybackRequest, preserveFallbackQueue: Boolean = false) {
        if (!preserveFallbackQueue) {
            animeFallbackRequests = emptyList()
            animeFallbackIndex = -1
        }
        val title = request.title.ifBlank { "AniLight" }
        val provider = request.provider.ifBlank { "Extension" }
        if (section == "Movies") {
            moviePlaybackStartedAtMs = System.currentTimeMillis()
            android.util.Log.i(
                "SANY-MOVIE",
                "Movie playback requested: provider=$provider mime=${request.streamMimeType ?: "auto"} quality=${request.quality ?: "unknown"}"
            )
        }
        showAnimePlaybackLoading(title, true)
        if (section == "Series") {
            val pending = pendingCloudStreamAnimeItem?.takeIf {
                title.startsWith(it.third, ignoreCase = true) || it.third.startsWith(title.substringBefore(" • "), ignoreCase = true)
            }
            val playedTitle = pending?.third ?: title.substringBefore(" • ").trim()
            val playedProvider = pending?.first ?: provider
            val playedItemUrl = pending?.second ?: request.url.trim()
            prefs.edit()
                .putString("sany_last_played_section", "Series")
                .putString("sany_last_played_provider", playedProvider)
                .putString("sany_last_played_title", playedTitle)
                .putString("sany_last_played_item_url", playedItemUrl)
                .apply()
            pendingCloudStreamAnimeItem = null
        }
        val streamUrl = request.url.trim()
        val parsedUrl = runCatching { Uri.parse(streamUrl) }.getOrNull()
        // The shared CloudStream playback bridge serves Anime, Movies and Live Sports.
        // SANY's player also supports FTP, so accept the same schemes here.
        if (parsedUrl?.scheme?.lowercase() !in setOf("http", "https", "ftp")) {
            showAnimePlaybackLoading("", false)
            val sectionLabel = when (section) {
                "Live Sports" -> "Live Sports"
                "Movies" -> "Movies"
                "Series" -> "AniLight"
                else -> provider
            }
            toast("${sectionLabel}-এর stream link সঠিক নয়")
            android.util.Log.e(
                "SANY-PLAYER",
                "Rejected provider URL: section=${section} provider=${provider} scheme=${parsedUrl?.scheme ?: "missing"} length=${streamUrl.length}"
            )
            return
        }
        try {
            android.util.Log.i(
                "SANY-PLAYER",
                "Starting provider playback: provider=$provider mime=${request.streamMimeType ?: "auto"} " +
                    "quality=${request.quality ?: "unknown"} subtitles=${request.subtitles.size}"
            )
            play(
                channel = Channel(title, request.url.trim(), provider),
                startPlayback = true,
                requestHeaders = request.headers,
                streamMimeTypeHint = request.streamMimeType,
                subtitleTracks = request.subtitles,
                preferredAudioLanguages = request.audioLanguage?.split(',')?.map(String::trim),
                qualityHint = request.quality,
                externalAudioTracks = request.audioTracks
            )
            if (section == "Live Sports" && animeFallbackIndex >= 0) {
                liveSportsFirstFrameWatchdog?.let(playbackRetryHandler::removeCallbacks)
                val expectedUrl = request.url.trim()
                val watchdog = Runnable {
                    if (section == "Live Sports" &&
                        playerView != null &&
                        currentChannel?.url == expectedUrl &&
                        player?.playWhenReady == true &&
                        player?.playbackState != Player.STATE_IDLE &&
                        animeFallbackIndex >= 0
                    ) {
                        android.util.Log.w("SANY-LIVE-SPORTS", "No video frame rendered for $provider; trying next source")
                        toast("ভিডিও দেখা যাচ্ছে না। অন্য Sports source চেষ্টা হচ্ছে…")
                        tryNextAnimeFallback()
                    }
                }
                liveSportsFirstFrameWatchdog = watchdog
                // Give slow live hosts time to start, then move on if no frame arrived.
                playbackRetryHandler.postDelayed(watchdog, 20_000L)
            }
        } catch (error: Exception) {
            showAnimePlaybackLoading("", false)
            android.util.Log.e("SANY-PLAYER", "Could not initialize provider player", error)
            toast("Player চালু করা যায়নি: ${error.javaClass.simpleName}")
        }
    }

    private fun attachAniLightNativeView() {
        val nativeView = aniLightNativeView ?: return
        (nativeView.parent as? android.view.ViewGroup)?.removeView(nativeView)
        val animePage = LinearLayout(this).apply {
            setBackgroundColor(if (isLight) Color.WHITE else bg)
        }
        animePage.addView(nativeView, LinearLayout.LayoutParams(-1, -1))
        contentArea.addView(animePage, LinearLayout.LayoutParams(-1, -1))
    }

    private fun attachCloudStreamProviderView() {
        val providerView = cloudStreamProviderView ?: return
        (providerView.parent as? android.view.ViewGroup)?.removeView(providerView)
        contentArea.addView(providerView, LinearLayout.LayoutParams(-1, -1))
    }

    private fun restoreAnimeExternalPage() {
        if (!animeExternalPageActive) return
        animeExternalPageActive = false
        contentArea.removeAllViews()
        if (animeExternalPageReturnToProvider &&
            cloudStreamProviderScreen != null && cloudStreamProviderView != null
        ) {
            attachCloudStreamProviderView()
        } else {
            attachAniLightNativeView()
        }
    }

    private fun renderAniLightNative() {
        // Reattach the existing screen instead of constructing a fresh one.
        // This preserves its page stack, filters and exact scroll position.
        if (aniLightNativeScreen != null && aniLightNativeView != null) {
            attachAniLightNativeView()
            return
        }
        // Keep CloudStream DEX provider management internal. The verified providers
        // are synchronized and loaded automatically at app startup; no manual
        // provider/search entry is shown in the Anime Series UI.
        lateinit var screen: AniLightNativeScreen
        screen = AniLightNativeScreen(
            context = this,
            light = isLight,
            accent = accent,
            onPlay = { playbackRequest ->
                // Show the same source confirmation step used by extension playback.
                showAnimeSourcePicker(playbackRequest.title.ifBlank { "Anime Sources" }, listOf(playbackRequest))
            },
            onPlaybackLoading = { title, show -> showAnimePlaybackLoading(title, show) },
            onBrowseCloudStreamProviders = { showCloudStreamProviderBrowser() },
            onCloudStreamResult = { providerName, url, title ->
                showCloudStreamDetails(providerName, url, title)
            },
            onSearchProvider = { providerName -> showCloudStreamSearch(providerName) },
            onLoadProviderShelves = { onLoaded ->
                thread(name = "SANY-Provider-Home-Shelves") {
                    val shelves = runCatching {
                        kotlinx.coroutines.runBlocking {
                            // Plugins are synchronized and loaded once during app startup.
                            // Never repeat repository downloads/hash checks when the user merely
                            // opens this screen; use the already registered curated providers.
                            // The provider display name is not guaranteed to match the
                            // extension's internalName (some plugins register a differently
                            // cased or branded MainAPI name). These are the only Anime packages
                            // loaded by the curated Anime sync path, so select by actual
                            // CloudStream media capabilities rather than brittle name equality.
                            // Inspect every installed extension. Some mixed-content providers
                            // expose Anime entries even when their declared supportedTypes are broad.
                            // The strict per-result type filter below decides what may enter Anime.
                            var animeProviders = SanyCloudStreamPluginRuntime.providers()
                            // Recover if the user opens Anime before the startup sync has
                            // registered the curated extensions, or if Android cleared process state.
                            if (animeProviders.isEmpty()) {
                                val retryReport = SanyExtensionRepositories.syncOfficialAnimePlugins(applicationContext)
                                retryReport.packages.forEach { packageFile ->
                                    val loaded = SanyCloudStreamPluginRuntime.load(applicationContext, packageFile)
                                    if (!loaded.success) {
                                        android.util.Log.e("SANY-Anime-Home", "Retry load failed: ${packageFile.name}: ${loaded.error}")
                                    }
                                }
                                retryReport.failures.forEach {
                                    android.util.Log.e("SANY-Anime-Home", "Retry sync: $it")
                                }
                                animeProviders = SanyCloudStreamPluginRuntime.providers()
                            }
                            android.util.Log.i(
                                "SANY-Anime-Home",
                                "Loaded Anime homepage providers: " +
                                    animeProviders.joinToString { "${it.name}[${it.supportedTypes.joinToString()}]" }
                            )
                            // Load every provider independently. A provider without a
                            // homepage must not prevent the other providers or the fallback
                            // search catalogue from appearing.
                            val fallbackQueries = listOf("anime", "popular anime", "one piece", "naruto", "demon slayer", "jujutsu kaisen")
                            val providerResults = kotlinx.coroutines.coroutineScope {
                                animeProviders.map { provider ->
                                    async(kotlinx.coroutines.Dispatchers.IO) {
                                        fun mapItems(
                                            items: List<com.lagradost.cloudstream3.SearchResponse>,
                                            categoryName: String,
                                            curatedAnimeProvider: Boolean = false
                                        ): List<Pair<String, SanyProviderItem>> =
                                            items.mapNotNull { item ->
                                                val title = item.name.trim()
                                                val url = item.url.trim()
                                                val isAnimeMovie = item.type == com.lagradost.cloudstream3.TvType.AnimeMovie
                                                val isAnimeSeries = item.type == com.lagradost.cloudstream3.TvType.Anime ||
                                                    item.type == com.lagradost.cloudstream3.TvType.OVA
                                                // These three packages are explicitly curated as Anime sources.
                                                // Some CloudStream plugins report generic/incorrect TvType values;
                                                // do not hide their valid homepage/search entries because of that metadata.
                                                if (title.isBlank() || url.isBlank() ||
                                                    (!curatedAnimeProvider && !isAnimeMovie && !isAnimeSeries)
                                                ) null
                                                else {
                                                    val category = categoryName.trim()
                                                        .ifBlank { if (isAnimeMovie) "Anime Movies" else "Anime" }
                                                        .replace(Regex("\\s+"), " ")
                                                    category to SanyProviderItem(
                                                        title = title,
                                                        url = url,
                                                        posterUrl = item.posterUrl.orEmpty(),
                                                        sourceProvider = provider.name
                                                    )
                                                }
                                            }

                                        val homepageItems: List<Pair<String, SanyProviderItem>> =
                                            runCatching<List<Pair<String, SanyProviderItem>>> {
                                                kotlinx.coroutines.withTimeout(18_000L) {
                                                    val pages = SanyCloudStreamPluginRuntime.homePage(provider.name, 1)
                                                    pages.flatMap { page ->
                                                        mapItems(page.list, page.name, curatedAnimeProvider = true)
                                                    }
                                                }
                                            }.onFailure { error ->
                                                android.util.Log.w("SANY-Anime-Home", "${provider.name} homepage failed: ${error.message}", error)
                                            }.getOrDefault(emptyList())

                                        if (homepageItems.isNotEmpty()) {
                                            homepageItems
                                        } else {
                                            val fallback = mutableListOf<Pair<String, SanyProviderItem>>()
                                            for (query in fallbackQueries) {
                                                val matches: List<Pair<String, SanyProviderItem>> =
                                                    runCatching<List<Pair<String, SanyProviderItem>>> {
                                                        val responses: List<com.lagradost.cloudstream3.SearchResponse> =
                                                            kotlinx.coroutines.withTimeout(6_000L) {
                                                                SanyCloudStreamPluginRuntime.search(provider.name, query)
                                                            }
                                                        mapItems(responses, "Anime", curatedAnimeProvider = true)
                                                    }.getOrDefault(emptyList())
                                                fallback += matches
                                                if (fallback.size >= 12) break
                                            }
                                            android.util.Log.i(
                                                "SANY-Anime-Home",
                                                "${provider.name}: homepage empty; fallback search returned ${fallback.size} titles"
                                            )
                                            fallback.distinctBy { it.second.title.trim().lowercase() }
                                        }
                                    }
                                }.awaitAll()
                            }
                            val allResults = providerResults.flatten()
                            // Merge identically named homepage categories across extensions.
                            // Each entry keeps its own source provider for playback.
                            allResults.groupBy { it.first.trim().lowercase() }
                                .toSortedMap()
                                .mapNotNull { (_, entries) ->
                                    val titles = entries.map { it.second }
                                        .distinctBy { it.title.trim().lowercase() }
                                        .sortedBy { it.title.lowercase() }
                                    val category = entries.firstOrNull()?.first?.trim().orEmpty()
                                    if (titles.isEmpty()) null
                                    else SanyProviderShelf(
                                        "SANY Anime",
                                        category.ifBlank { "Anime" }.uppercase(),
                                        titles
                                    )
                                }
                        }
                    }.getOrElse { error ->
                        android.util.Log.w("SANY-Provider-Home", "Could not load provider home shelves", error)
                        emptyList()
                    }
                    runOnUiThread { onLoaded(shelves) }
                }
            }
        )
        aniLightNativeScreen = screen
        aniLightNativeView = screen.create()
        attachAniLightNativeView()
    }

    private fun showCloudStreamProviderBrowser() {
        // Re-sync the allowlisted packages, then show a real provider directory.
        val progress = AlertDialog.Builder(this@MainActivity)
            .setTitle("Anime Providers")
            .setMessage("GitHub থেকে Anime Providers যাচাই ও Load করা হচ্ছে…")
            .setCancelable(false)
            .create()
        progress.show()
        thread(name = "SANY-Anime-ProviderCheck") {
            val result = runCatching {
                val report = SanyExtensionRepositories.syncOfficialAnimePlugins(applicationContext)
                val allowedProviderNames = report.packages.flatMap { packageFile ->
                    val loadResult = SanyCloudStreamPluginRuntime.load(applicationContext, packageFile)
                    if (!loadResult.success) {
                        android.util.Log.e("SANY-CS", "DEX load failed: ${packageFile.name}: ${loadResult.error}")
                        emptyList()
                    } else loadResult.providerNames
                }.toSet()
                val providers = SanyCloudStreamPluginRuntime.providers()
                    .filter { it.name in allowedProviderNames }
                check(providers.isNotEmpty()) {
                    report.failures.joinToString("; ").ifBlank { "No curated Bangla/Hindi Anime provider was registered" }
                }
                providers
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    progress.dismiss()
                    return@runOnUiThread
                }
                progress.dismiss()
                result.onSuccess { providers ->
                    cloudStreamProviderScreen = CloudStreamProviderHomeScreen(
                        context = this@MainActivity,
                        providers = providers,
                        backgroundColor = bg,
                        foregroundColor = white,
                        accentColor = accent,
                        onSearch = { providerName, query -> showCloudStreamSearch(providerName, query) },
                        onOpenItem = { providerName, url, title -> showCloudStreamDetails(providerName, url, title) },
                        onBackToAnime = {
                            cloudStreamProviderScreen = null
                            cloudStreamProviderView = null
                            contentArea.removeAllViews()
                            attachAniLightNativeView()
                        }
                    )
                    cloudStreamProviderView = cloudStreamProviderScreen!!.create()
                    contentArea.removeAllViews()
                    attachCloudStreamProviderView()
                }.onFailure { error ->
                    android.util.Log.e("SANY-CS", "Anime provider sync/load failed from provider browser", error)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Anime Provider Load Error")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun showCloudStreamSearch(providerName: String, initialQuery: String? = null) {
        val input = EditText(this).apply { hint = "Anime title লিখুন"; initialQuery?.let(::setText); setSingleLine(true); setPadding(dp(18), dp(12), dp(18), dp(12)) }
        val dialog = AlertDialog.Builder(this@MainActivity).setTitle("$providerName Search").setView(input)
            .setPositiveButton("SEARCH", null).setNegativeButton("CANCEL", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isBlank()) { input.error = "Search title লিখুন"; return@setOnClickListener }
                dialog.dismiss(); toast("Searching $providerName…")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val result = runCatching { SanyCloudStreamPluginRuntime.search(providerName, query) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        result.onSuccess { items ->
                            if (items.isEmpty()) toast("কোনো ফলাফল পাওয়া যায়নি")
                            else AlertDialog.Builder(this@MainActivity).setTitle("Search Results (${items.size})")
                                .setItems(items.map { it.name }.toTypedArray()) { _, i -> showCloudStreamDetails(providerName, items[i].url, items[i].name) }
                                .setNegativeButton("বন্ধ", null).show()
                        }.onFailure { toast("Search error: ${it.message ?: it.javaClass.simpleName}"); android.util.Log.e("SANY-CS", "Search failed", it) }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showCloudStreamDetails(providerName: String, url: String, fallbackTitle: String) {
        val returnToProviderBrowser = cloudStreamProviderScreen != null
        pendingCloudStreamAnimeItem = Triple(providerName, url, fallbackTitle)
        toast("Loading $fallbackTitle…")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val result = runCatching { SanyCloudStreamPluginRuntime.load(providerName, url) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { response ->
                    if (response == null) { toast("Anime details পাওয়া যায়নি"); return@onSuccess }
                    // CloudStream AnimeLoadResponse stores episodes by dub status;
                    // MovieLoadResponse instead exposes one serialized dataUrl. Handle
                    // both shapes so AnimeMovie/Movie providers are not silently dropped.
                    val episodes = when (response) {
                        is com.lagradost.cloudstream3.AnimeLoadResponse ->
                            response.episodes.values.flatten()
                        // Some Anime providers use CloudStream's standard TV-series response.
                        is com.lagradost.cloudstream3.TvSeriesLoadResponse -> response.episodes
                        is com.lagradost.cloudstream3.MovieLoadResponse -> {
                            val movieProvider = SanyCloudStreamPluginRuntime.providers()
                                .firstOrNull { it.name == providerName }
                            listOfNotNull(
                                response.dataUrl.takeIf { it.isNotBlank() }?.let { data ->
                                    movieProvider?.newEpisode(data) {
                                        name = "Movie"
                                    }
                                }
                            )
                        }
                        else -> emptyList()
                    }
                    if (episodes.isEmpty()) {
                        toast("এই title-এ playable episode/source data নেই")
                        return@onSuccess
                    }
                    val labels = episodes.mapIndexed { index, ep ->
                        buildString {
                            ep.episode?.let { append("Episode $it") }
                            ep.name?.takeIf { it.isNotBlank() }?.let {
                                if (isNotEmpty()) append(" • ")
                                append(it)
                            }
                            if (isEmpty()) append("Episode ${index + 1}")
                        }
                    }.toTypedArray()
                    // Keep the exact parent page so Android Back can return to it
                    // after details or video playback without resetting Anime navigation.
                    animeExternalPageReturnToProvider = returnToProviderBrowser
                    animeExternalPageActive = true
                    // Open a dedicated Anime details page instead of a compact episode popup.
                    contentArea.removeAllViews()
                    val detailPage = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(bg)
                    }
                    val pageHeader = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                    }
                    val backButton = TextView(this@MainActivity).apply {
                        text = "‹  BACK"
                        textSize = 14f
                        setTextColor(white)
                        setPadding(dp(8), dp(10), dp(18), dp(10))
                        setOnClickListener { restoreAnimeExternalPage() }
                    }
                    pageHeader.addView(backButton)
                    pageHeader.addView(TextView(this@MainActivity).apply {
                        text = "ANIME DETAILS"
                        textSize = 15f
                        setTextColor(white)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    detailPage.addView(pageHeader, LinearLayout.LayoutParams(-1, -2))
                    val detailScroll = ScrollView(this@MainActivity)
                    val detailBody = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(4), dp(14), dp(22))
                    }
                    val banner = FrameLayout(this@MainActivity).apply {
                        background = android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(0xFF123D70.toInt(), 0xFF101827.toInt(), 0xFF080B12.toInt())
                        ).apply { cornerRadius = dp(14).toFloat() }
                        clipToOutline = true
                    }
                    val bannerImage = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0.72f
                    }
                    banner.addView(bannerImage, FrameLayout.LayoutParams(-1, -1))
                    val bannerTitle = TextView(this@MainActivity).apply {
                        text = response.name
                        textSize = 23f
                        setTextColor(Color.WHITE)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setShadowLayer(8f, 1f, 2f, Color.BLACK)
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                        gravity = Gravity.BOTTOM or Gravity.START
                    }
                    banner.addView(bannerTitle, FrameLayout.LayoutParams(-1, -1))
                    detailBody.addView(banner, LinearLayout.LayoutParams(-1, dp(176)).apply { bottomMargin = dp(14) })
                    val posterUrl = response.posterUrl.orEmpty()
                    // Reuse the bounded, disk-cached image loader so repeat banner
                    // visits display immediately without another full-size download.
                    if (posterUrl.startsWith("http", true)) {
                        loadCachedChannelLogo(bannerImage, null, posterUrl)
                    }
                    detailBody.addView(TextView(this@MainActivity).apply {
                        text = "EPISODES  •  ${episodes.size}"
                        textSize = 16f
                        setTextColor(white)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                    episodes.indices.sortedWith(compareBy<Int> { episodes[it].season ?: 0 }.thenBy { episodes[it].episode ?: (it + 1) }).forEach { i ->
                        val episode = episodes[i]
                        val episodeNumber = episode.episode ?: (i + 1)
                        val episodeTitle = episode.name?.takeIf { it.isNotBlank() } ?: "Episode $episodeNumber"
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(13), dp(13), dp(13), dp(13))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(0xFF171D28.toInt())
                                cornerRadius = dp(11).toFloat()
                                setStroke(dp(1), 0xFF303B4D.toInt())
                            }
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                resolveCloudStreamEpisode(providerName, response.name, episode.data, episode.episode, episode.name.orEmpty(), i)
                            }
                        }
                        row.addView(TextView(this@MainActivity).apply {
                            text = "▶"
                            textSize = 19f
                            setTextColor(accent)
                        }, LinearLayout.LayoutParams(dp(34), -2))
                        val episodeInfo = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                        episodeInfo.addView(TextView(this@MainActivity).apply {
                            text = "EPISODE $episodeNumber"
                            textSize = 10f
                            setTextColor(accent)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        })
                        episodeInfo.addView(TextView(this@MainActivity).apply {
                            text = episodeTitle
                            textSize = 14f
                            setTextColor(white)
                            maxLines = 2
                        })
                        row.addView(episodeInfo, LinearLayout.LayoutParams(0, -2, 1f))
                        detailBody.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                    }
                    detailScroll.addView(detailBody)
                    detailPage.addView(detailScroll, LinearLayout.LayoutParams(-1, 0, 1f))
                    contentArea.addView(detailPage, LinearLayout.LayoutParams(-1, -1))
                }.onFailure { toast("Details error: ${it.message ?: it.javaClass.simpleName}"); android.util.Log.e("SANY-CS", "Load failed", it) }
            }
        }
    }

    /**
     * Starts with the user's selected source, then tries the remaining resolved
     * links in order only if Media3 reports a playback error.
     */
    private fun startAnimeSourceFallback(
        requests: List<SanyPlaybackRequest>,
        selectedIndex: Int
    ) {
        val ordered = requests.distinctBy { it.url }.filter { it.url.isNotBlank() }
        if (selectedIndex !in ordered.indices) return
        animeFallbackRequests = ordered
        animeFallbackIndex = selectedIndex
        playSanyPlaybackRequest(ordered[selectedIndex], preserveFallbackQueue = true)
        // Rebuild the Anime catalogue for direct catalogue playback, but keep a
        // provider detail page intact underneath the player when playback began there.
        if (section == "Series" && !animeExternalPageActive) renderSection()
    }

    private fun tryNextAnimeFallback(): Boolean {
        val nextIndex = animeFallbackIndex + 1
        if (nextIndex !in animeFallbackRequests.indices) {
            animeFallbackRequests = emptyList()
            animeFallbackIndex = -1
            return false
        }
        animeFallbackIndex = nextIndex
        val next = animeFallbackRequests[nextIndex]
        android.util.Log.w("SANY-PLAYER", "Trying fallback source ${nextIndex + 1}/${animeFallbackRequests.size}: ${next.provider}")
        toast("আগের Source কাজ করেনি। Source ${nextIndex + 1}/${animeFallbackRequests.size} চেষ্টা হচ্ছে…")
        playSanyPlaybackRequest(next, preserveFallbackQueue = true)
        return true
    }

    private fun resolveCloudStreamEpisode(
        providerName: String,
        title: String,
        episodeData: String,
        episodeNumber: Int?,
        episodeName: String,
        episodeIndex: Int
    ) {
        // Ignore rapid repeated taps while resolving or while the source picker is open.
        if (animeEpisodeSourceResolving || animeSourcePickerDialog?.isShowing == true) return
        animeEpisodeSourceResolving = true
        val requestId = ++animeStreamingRequestId
        animeStreamingSourceRequests = mutableListOf()
        animeStreamingSourceAdapter = null
        animeStreamingPlaybackStarted = false
        animeStreamingPickerDismissed = false
        animeSourcePickerDialog?.dismiss()
        animeSourcePickerDialog = null
        toast("এই Episode-এর Source খোঁজা হচ্ছে…")
        // Resolve the selected provider first. Waiting for every alternate provider
        // made playback feel slow even when the selected provider already had a link.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val result = runCatching {
                kotlinx.coroutines.withTimeout(25_000L) {
                    SanyCloudStreamPluginRuntime.resolvePlayback(providerName, title, episodeData) { source ->
                        runOnUiThread {
                            publishAnimeStreamingSources(requestId, "$title — Sources", listOf(source), finished = false)
                        }
                    }
                }
            }
            runOnUiThread {
                // Always release the tap lock when resolution completes, including
                // timeout/error/empty-result cases, so the next episode remains playable.
                animeEpisodeSourceResolving = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { sources ->
                    val available = sources.distinctBy { it.url }.filter { it.url.isNotBlank() }
                    publishAnimeStreamingSources(requestId, "$title — Sources", available, finished = true)
                    if (available.isEmpty()) {
                        toast("এই Episode-এর playable source পাওয়া যায়নি। অন্য Episode চেষ্টা করুন")
                    } else {
                        android.util.Log.i("SANY-CS", "Primary source resolved for $title: ${available.size}")
                    }
                }.onFailure { error ->
                    android.util.Log.w("SANY-CS", "Fast source resolve failed for $title", error)
                    toast("Source পাওয়া যায়নি: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }
    private fun renderAniLightWebsite() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val browser = aniLightWebView ?: WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            // AniLight opens some episode/player links in new windows.
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message
                ): Boolean {
                    val popup = WebView(this@MainActivity).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: android.webkit.WebResourceRequest
                            ): Boolean {
                                val target = request.url
                                if (target.scheme == "https" || target.scheme == "http") {
                                    this@MainActivity.aniLightWebView?.loadUrl(target.toString())
                                }
                                view.stopLoading()
                                view.destroy()
                                return true
                            }
                        }
                    }
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                        popup.destroy()
                        return false
                    }
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val target = request.url
                    return when (target.scheme?.lowercase()) {
                        "http", "https" -> false
                        else -> {
                            try { startActivity(Intent(Intent.ACTION_VIEW, target)) } catch (_: Exception) { toast("এই লিংকটি খোলা যাচ্ছে না") }
                            true
                        }
                    }
                }
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) toast("AniLight load হয়নি (${error.errorCode}) — Refresh চাপুন")
                }
                override fun onReceivedHttpError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    response: android.webkit.WebResourceResponse
                ) {
                    if (request.isForMainFrame) toast("AniLight server error: HTTP ${response.statusCode}")
                }
            }
        }.also { aniLightWebView = it }

        (browser.parent as? ViewGroup)?.removeView(browser)
        page.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        contentArea.addView(page, LinearLayout.LayoutParams(-1, -1))
        if (!aniLightSiteLoaded) {
            aniLightSiteLoaded = true
            browser.loadUrl("https://anilight.live/")
        }
    }

// Legacy WebView code remains temporarily for safe rollback; native pages no longer call it.
    // Movies uses the site's normal public web interface inside a sandboxed WebView.
    // No scraping, DRM circumvention, or stream-link extraction is performed.
    private fun renderMoviesWebsite() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(8))
        }
        toolbar.addView(text("MULTIMOVIES", 12, accent, true), LinearLayout.LayoutParams(0, dp(40), 1f))
        toolbar.addView(actionButton("↻ REFRESH") { moviesWebView?.reload() }, LinearLayout.LayoutParams(dp(112), dp(38)))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        val browser = moviesWebView ?: WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val target = request.url
                    return when (target.scheme?.lowercase()) {
                        "http", "https" -> false
                        else -> {
                            try { startActivity(Intent(Intent.ACTION_VIEW, target)) } catch (_: Exception) {
                                toast("এই লিংকটি খোলা যাচ্ছে না")
                            }
                            true
                        }
                    }
                }
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) toast("Movies site load হয়নি — Refresh চাপুন")
                }
            }
        }.also { moviesWebView = it }

        (browser.parent as? ViewGroup)?.removeView(browser)
        page.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        contentArea.addView(page, LinearLayout.LayoutParams(-1, -1))
        if (!moviesSiteLoaded) {
            moviesSiteLoaded = true
            browser.loadUrl("https://multimovies.makeup/")
        }
    }

    private fun renderSeriesComingSoon() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val emblem = text("▥", 58, accent, true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(dp(18).toFloat(), 0f, 0f, Color.argb(125, 55, 145, 255))
        }
        page.addView(emblem, LinearLayout.LayoutParams(-1, dp(82)))
        val title = text("COMING SOON", 36, accent, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.07f
            setShadowLayer(dp(16).toFloat(), 0f, 0f, Color.argb(145, 55, 145, 255))
        }
        page.addView(title, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        })
        page.addView(text("SERIES LIBRARY IS UNDER DEVELOPMENT", 11, muted, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(12), 0, 0)
        })
        contentArea.addView(page, LinearLayout.LayoutParams(-1, -1))

        val pulse = ValueAnimator.ofFloat(0.82f, 1.04f).apply {
            duration = 1050
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                title.scaleX = scale
                title.scaleY = scale
                title.alpha = 0.78f + (scale - 0.82f) * 1.15f
                emblem.scaleX = 0.94f + (scale - 0.82f) * 0.35f
                emblem.scaleY = emblem.scaleX
            }
        }
        title.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { pulse.start() }
            override fun onViewDetachedFromWindow(view: View) { pulse.cancel() }
        })
    }

    private fun showChannelSearchDialog() {
        // Kept as a compatibility no-op: search is now inline in the fixed header.
        if (!::categoryHeaderContainer.isInitialized) return
        val input = categoryHeaderContainer.findViewWithTag<EditText>("category_inline_search") ?: return
        input.requestFocus()
        input.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun renderCategories() {
        categoryRow.removeAllViews()

        // Full-height category directory inspired by the supplied reference.
        // Channel cards are hidden until the user enters a category.
        val categoryDirectory = section == "Live TV" && selectedGroup == "Select category"
        // Movies opens directly to the movie library; category filters are not shown.
        val movieDirectory = section == "Movies"
        if (movieDirectory) selectedGroup = "All"
        // Entering a Live TV category switches to an immersive channel view:
        // hide the branded/title header and playlist selector, then restore them
        // when the user returns to the category directory.
        val immersiveCategory = section == "Live TV" && !categoryDirectory
        // Category rendering can run after tab changes or playback refreshes; never
        // let it re-show the branded header while a video player is still attached.
        homeHeaderContainer?.visibility = if (playerView != null || immersiveCategory) View.GONE else View.VISIBLE
        // The Live TV title is shown in the replacement banner below the brand header.
        headerTitleView?.visibility = View.VISIBLE
        playlistSelectorContainer?.visibility =
            if (section == "Checker" || (section == "Live TV" && !immersiveCategory && !movieDirectory)) View.VISIBLE
            else View.GONE
        // Keep the category back, view-mode and search toolbar visible above channel rows.
        categoryHeaderContainer.visibility = if (section == "Live TV" && (categoryDirectory || immersiveCategory)) View.VISIBLE else View.GONE
        (categoryRow.parent as? ScrollView)?.visibility =
            if (categoryDirectory) View.VISIBLE else View.GONE
        (categoryRow.parent as? ScrollView)?.let { scroll ->
            scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
                height = 0
                weight = if (categoryDirectory) 1f else 0f
            }
        }
        if (::countLabel.isInitialized) countLabel.visibility =
            if (categoryDirectory) View.GONE else View.VISIBLE
        (if (::listContainer.isInitialized) listContainer.parent as? ScrollView else null)?.visibility =
            if (categoryDirectory) View.GONE else View.VISIBLE

        if (section == "Live TV" && categoryDirectory) {
            categoryHeaderContainer.removeAllViews()
            val toolbar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            val checkerShortcut = text("✓  CHECKER", 11, accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
                background = glass(10)
                isFocusable = true
                contentDescription = "Open Checker page"
                applyTvFocusStyle(this)
                setOnClickListener {
                    section = "Checker"
                    selectedGroup = "All"
                    searchQuery = ""
                    prefs.edit().putString("ui_section", "Checker").apply()
                    renderHome()
                }
            }
            if (!isTvDevice) toolbar.addView(checkerShortcut, LinearLayout.LayoutParams(-2, dp(36)).apply {
                rightMargin = dp(8)
            })
            toolbar.addView(View(this), LinearLayout.LayoutParams(0, dp(36), 1f))
            toolbar.addView(createViewModeDropdown(), LinearLayout.LayoutParams(-2, dp(34)))
            categoryHeaderContainer.addView(toolbar, LinearLayout.LayoutParams(-1, dp(38)))
        }

        if (section == "Live TV" && !categoryDirectory) {
            categoryHeaderContainer.removeAllViews()
            val back = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(5), dp(10), dp(5))
                background = glass(12)
                elevation = dp(4).toFloat()
                translationZ = dp(4).toFloat()
            }
            val categoryTitle = text("‹  ${selectedGroup.uppercase()}", 13, accent, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                applyTvFocusStyle(this)
                setOnClickListener {
                    selectedGroup = "Select category"
                    liveTvSelectedGroup = selectedGroup
                    searchQuery = ""
                    liveTvSearchQuery = ""
                    prefs.edit()
                        .putString("live_tv_group", liveTvSelectedGroup)
                        .putString("live_tv_search", "")
                        .putString("ui_group", selectedGroup)
                        .putString("ui_search", "")
                        .apply()
                    displayedLimit = initialDisplayedLimit
                    renderListOnly()
                }
            }
            back.addView(categoryTitle, LinearLayout.LayoutParams(0, -1, 1f))
            back.addView(createViewModeDropdown(), LinearLayout.LayoutParams(-2, dp(32)).apply { leftMargin = dp(4) })
            val categorySearch = EditText(this).apply {
                tag = "category_inline_search"
                hint = "⌕  Search"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
                setHintTextColor(accent)
                setSingleLine(true)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setPadding(dp(6), 0, dp(6), 0)
                background = glass(10)
                gravity = Gravity.CENTER_VERTICAL
                setText(searchQuery)
                isFocusable = true
                isFocusableInTouchMode = true
                addTextChangedListener(SimpleTextWatcher { value ->
                    searchQuery = value.trim()
                    liveTvSearchQuery = searchQuery
                    prefs.edit().putString("live_tv_search", liveTvSearchQuery).apply()
                    displayedLimit = initialDisplayedLimit
                    renderChannelRows(listContainer)
                })
            }
            back.addView(categorySearch, LinearLayout.LayoutParams(dp(118), dp(32)))
            categoryHeaderContainer.addView(back, LinearLayout.LayoutParams(-1, dp(40)))
            return
        }

        // Build category metadata in a single pass. The previous implementation
        // repeatedly scanned the complete playlist once per category, which made
        // returning to Live TV noticeably slower on large playlists.
        val directoryChannels = if (section == "Live TV") {
            // Once checking has started or saved results exist, build the Live TV
            // category menu from verified working channels only. Before the first
            // check, retain playlist categories so the user can start checking.
            val liveChannels = if (checkerRunning || streamChecks.isNotEmpty()) {
                liveTvVisibleCandidates()
            } else channels
            liveChannels.filter { !isMovie(it) && !isSeries(it) }
        } else channels
        val groupCounts = LinkedHashMap<String, Int>()
        var allCount = 0
        directoryChannels.forEach { channel ->
            val eligibleForAll = when (section) {
                "Movies" -> isMovie(channel)
                "Series" -> isSeries(channel)
                else -> !isMovie(channel) && !isSeries(channel)
            }
            if (eligibleForAll) allCount++
            val group = if (isSportsChannel(channel)) "Sports" else normalizeLiveTvCategory(channel.group)
            if (group.isNotBlank() && !groupCounts.containsKey(group)) {
                groupCounts[group] = 0
            }
            if (group.isNotBlank()) groupCounts[group] = (groupCounts[group] ?: 0) + 1
        }
        val groups = listOf("All") + groupCounts.keys.take(250).sorted()
        val options = when (section) {
            "Movies" -> listOf("All") + movieCategories + groups.drop(1).filterNot { it in movieCategories }
            "Series" -> listOf("All", "Anime") + groups.drop(1).filterNot { it.equals("Anime", true) }
            else -> groups
        }
        val categoryCards = mutableListOf<View>()
        options.forEach { item ->
            val matchingCount = when {
                item == "All" -> allCount
                section == "Movies" && item in movieCategories -> directoryChannels.count { isMovie(it) && matchesMovieCategory(it, item) }
                section == "Series" && item == "Anime" -> directoryChannels.count { isAnime(it) }
                else -> groupCounts.entries.firstOrNull { it.key.equals(item, true) }?.value ?: 0
            }
            if (section == "Live TV" && matchingCount == 0) return@forEach
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(15), dp(10))
                background = glass(10)
                elevation = dp(2).toFloat()
                isFocusable = true
                applyTvFocusStyle(this)
                setOnClickListener {
                    selectedGroup = item
                    searchQuery = ""
                    if (section == "Live TV") {
                        liveTvSelectedGroup = item
                        liveTvSearchQuery = ""
                        prefs.edit()
                            .putString("live_tv_group", liveTvSelectedGroup)
                            .putString("live_tv_search", "")
                            .putString("ui_group", selectedGroup)
                            .putString("ui_search", "")
                            .apply()
                    }
                    displayedLimit = initialDisplayedLimit
                    renderListOnly()
                }
            }
            card.addView(text(if (item == "All") "All channels" else item, 14, white, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(text(matchingCount.toString(), 11, muted, true))
            card.addView(text("›", 24, accent, true).apply {
                setPadding(dp(12), 0, 0, 0)
            })
            categoryCards += card
        }
        if (section == "Live TV" && categoryDirectory && categoryCards.isEmpty()) {
            val emptyMessage = if (checkerRunning) {
                "চ্যানেল পরীক্ষা চলছে… ${liveTvProgressLabel()}"
            } else {
                "কোনো Working Channel পাওয়া যায়নি"
            }
            categoryRow.addView(text(emptyMessage, 14, muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(30), dp(12), dp(30))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (prefs.getString("channel_view_mode", "list") == "grid") {
            categoryCards.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                pair.forEachIndexed { index, itemView ->
                    row.addView(itemView, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                        if (index == 0) rightMargin = dp(5) else leftMargin = dp(5)
                    })
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(5) })
                categoryRow.addView(row, LinearLayout.LayoutParams(-1, dp(57)).apply { bottomMargin = dp(5) })
            }
        } else {
            categoryCards.forEach { card ->
                categoryRow.addView(card, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(1) })
            }
        }
    }

    private fun renderListOnly() {
        if (!::contentArea.isInitialized || section == "Settings" || channels.isEmpty()) return
        if (!::listContainer.isInitialized) return

        // Category cards update the list in place (without calling renderHome).
        // Record those transitions here as well, otherwise Back has no category
        // history and incorrectly jumps straight to the Live TV home.
        val currentNavigation = currentNavigationSnapshot()
        val previousNavigation = lastRenderedNavigation
        if (!restoringNavigation && previousNavigation != null &&
            previousNavigation.section == currentNavigation.section &&
            (previousNavigation.selectedGroup != currentNavigation.selectedGroup ||
             previousNavigation.liveTvSelectedGroup != currentNavigation.liveTvSelectedGroup)
        ) {
            if (navigationHistory.isEmpty() || navigationHistory.peekLast() != previousNavigation) {
                navigationHistory.addLast(previousNavigation)
            }
        }
        lastRenderedNavigation = currentNavigation
        restoringNavigation = false

        renderCategories()
        renderChannelRows(listContainer)
    }

    private fun createViewModeDropdown(): TextView {
        val currentMode = prefs.getString("channel_view_mode", "list") ?: "list"
        return text(if (currentMode == "grid") "▦ Grid ▾" else "☷ List ▾", 10, accent, true).apply {
            tag = "channel_view_mode_dropdown"
            gravity = Gravity.CENTER
            minWidth = dp(72)
            setPadding(dp(10), 0, dp(10), 0)
            background = glass(10)
            isFocusable = true
            contentDescription = "Channel layout: $currentMode. Choose List or Grid."
            setOnClickListener {
                val popup = android.widget.PopupMenu(this@MainActivity, this)
                popup.menu.add("☷  List").setOnMenuItemClickListener {
                    setChannelViewMode("list")
                    true
                }
                popup.menu.add("▦  Grid").setOnMenuItemClickListener {
                    setChannelViewMode("grid")
                    true
                }
                popup.show()
            }
        }
    }

    private fun setChannelViewMode(mode: String) {
        if (mode != "list" && mode != "grid") return
        prefs.edit().putString("channel_view_mode", mode).apply()
        if (::listContainer.isInitialized && ::countLabel.isInitialized) {
            if (section == "Live TV") renderCategories()
            renderChannelRows(listContainer)
        }
        fun refreshButtons(view: View) {
            if (view.tag == "channel_view_mode_list") {
                (view as? TextView)?.setTextColor(if (mode == "list") accent else muted)
            } else if (view.tag == "channel_view_mode_grid") {
                (view as? TextView)?.setTextColor(if (mode == "grid") accent else muted)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) refreshButtons(view.getChildAt(index))
            }
        }
        if (::root.isInitialized) refreshButtons(root)
        val label = if (mode == "grid") "▦ Grid ▾" else "☷ List ▾"
        if (::home.isInitialized) {
            fun refreshDropdowns(view: View) {
                if (view.tag == "channel_view_mode_dropdown") (view as? TextView)?.text = label
                if (view is ViewGroup) for (i in 0 until view.childCount) refreshDropdowns(view.getChildAt(i))
            }
            refreshDropdowns(home)
        }
    }

    private fun renderChannelRows(container: LinearLayout) {
        container.removeAllViews()
        val filtered = filteredChannels()
        countLabel.text = if (section == "Live TV" && streamChecks.isNotEmpty() && !checkerRunning) {
            "${filtered.size} WORKING CHANNELS"
        } else {
            "${filtered.size} ITEMS  •  ${channels.size} IN PLAYLIST"
        }
        if (filtered.isEmpty()) {
            val message = if (section == "Live TV" && selectedGroup == "Select category") "একটি Category নির্বাচন করুন — সেই Category-র চ্যানেল এখানে দেখাবে" else if (section == "Live TV" && checkerRunning) "Checking channels… ${liveTvProgressLabel()}" else "No working channels found"
            container.addView(text(message, 15, muted, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(35), 0, dp(35)) })
            return
        }
        if (prefs.getString("channel_view_mode", "list") == "grid") {
            renderTvChannelCards(container, filtered)
            return
        }
        filtered.take(displayedLimit).forEachIndexed { index, channel ->
            val row = LinearLayout(this).apply {
                isFocusable = true
                isFocusableInTouchMode = false
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = glass(12)
                applyTvFocusStyle(this)
            }
            val number = text((index + 1).toString().padStart(2, '0'), 12, accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(8), 0)
                minWidth = dp(28)
            }
            val isPlayingChannel = currentChannel?.url == channel.url
            if (isPlayingChannel) {
                row.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF126BDF.toInt(), 0xEE0845A4.toInt(), 0xDD071F52.toInt())
                ).apply {
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(3), 0xFF35B8FF.toInt())
                }
                row.elevation = dp(11).toFloat()
                row.foreground = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), 0x99BCEBFF.toInt())
                }
            }
            row.addView(number, LinearLayout.LayoutParams(dp(30), dp(46)))
            val fallbackLogo = text(channel.name.take(1).uppercase(), 18, accent, true).apply {
                gravity = Gravity.CENTER
                isClickable = false
            }
            val logo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = glass(10)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                contentDescription = "${channel.name} logo"
            }
            val logoFrame = FrameLayout(this).apply {
                addView(logo, FrameLayout.LayoutParams(-1, -1))
                addView(fallbackLogo, FrameLayout.LayoutParams(-1, -1))
            }
            channel.logo?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let { logoUrl ->
                loadCachedChannelLogo(logo, fallbackLogo, logoUrl)
            }
            row.addView(logoFrame, LinearLayout.LayoutParams(dp(46), dp(46)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
            details.addView(text(channel.name, 14, if (isPlayingChannel) 0xFF72B9FF.toInt() else white, true))
            details.addView(text(channel.group.ifBlank { "Other" }, 11, muted).apply { setPadding(0, dp(4), 0, 0) })
            // Subtle channel watermark beneath each channel label.
            val watermarkLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                alpha = 1f
                setPadding(0, dp(4), 0, 0)
            }
            // Soft, continuous blinking green indicator beside every SANY IPTV watermark.
            val liveDot = text("●", 9, Color.rgb(0, 235, 90), true).apply {
                contentDescription = "SANY IPTV blinking live indicator"
                alpha = 1f
                if (!isLowMemoryDevice) {
                    startAnimation(android.view.animation.AlphaAnimation(0.22f, 1f).apply {
                        duration = 900L
                        repeatMode = android.view.animation.Animation.REVERSE
                        repeatCount = android.view.animation.Animation.INFINITE
                        startOffset = (index % 6) * 110L
                    })
                }
            }
            if (!isTvDevice) watermarkLine.addView(liveDot, LinearLayout.LayoutParams(-2, -2))
            watermarkLine.addView(text("SANY IPTV", 8, accent, true).apply {
                letterSpacing = 0.12f
                setPadding(dp(4), 0, 0, 0)
            })
            details.addView(watermarkLine)
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            if (isPlayingChannel) {
                val nowPlayingBadge = TextView(this).apply {
                    text = "▶"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    contentDescription = "Now playing channel"
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xFF36C5FF.toInt(), 0xFF0875F5.toInt(), 0xFF123EAA.toInt())
                    ).apply {
                        cornerRadius = dp(20).toFloat()
                        setStroke(dp(2), 0xFFD7F3FF.toInt())
                    }
                    setShadowLayer(dp(9).toFloat(), 0f, 0f, 0xFF168BFF.toInt())
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    // Keep the now-playing badge static to reduce UI-thread animation work.
                    alpha = 1f
                }
                row.addView(nowPlayingBadge, LinearLayout.LayoutParams(dp(42), dp(38)).apply {
                    leftMargin = dp(6)
                })
            }
            // Keep the original full-width, front-facing channel logo presentation.
            logoFrame.rotationY = 0f
            logoFrame.rotationX = 0f
            logoFrame.scaleX = 1f
            logoFrame.scaleY = 1f
            // Live TV and Movies can play; Checker remains diagnostic only.
            if (section == "Live TV" || section == "Live Sports" || section == "Movies") {
                row.setOnClickListener { play(channel) }
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        if (filtered.size > displayedLimit) {
            container.addView(actionButton("LOAD MORE  (${filtered.size - displayedLimit} LEFT)") {
                displayedLimit += displayedLimitIncrement
                renderChannelRows(container)
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8); bottomMargin = dp(12) })
        }
    }

    // Render channel cards in a compact three-column grid on supported grid mode.
    private fun renderTvChannelCards(container: LinearLayout, filtered: List<Channel>) {
        val visible = filtered.take(displayedLimit)
        visible.chunked(3).forEachIndexed { rowIndex, rowItems ->
            val cardRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowItems.forEachIndexed { columnIndex, channel ->
                val itemIndex = rowIndex * 3 + columnIndex
                val isPlayingChannel = currentChannel?.url == channel.url
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = if (isPlayingChannel) GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xFF126BDF.toInt(), 0xEE0845A4.toInt(), 0xDD071F52.toInt())
                    ).apply {
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(3), 0xFF35B8FF.toInt())
                    } else glass(16)
                    if (isPlayingChannel) {
                        elevation = dp(10).toFloat()
                        foreground = GradientDrawable().apply {
                            cornerRadius = dp(16).toFloat()
                            setStroke(dp(1), 0x99BCEBFF.toInt())
                        }
                    }
                    applyTvFocusStyle(this)
                    contentDescription = if (isPlayingChannel) "Currently playing: ${channel.name}" else "Play ${channel.name}"
                    if (section == "Live TV" || section == "Live Sports" || section == "Movies") setOnClickListener { play(channel) }
                }
                val topLine = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topLine.addView(text((itemIndex + 1).toString().padStart(2, '0'), 13, accent, true))
                topLine.addView(text("  •  ${channel.group.ifBlank { "Other" }}", 11, muted))
                if (isPlayingChannel) {
                    topLine.addView(text("  ▶ NOW PLAYING", 10, 0xFF8DDCFF.toInt(), true).apply {
                        setShadowLayer(dp(6).toFloat(), 0f, 0f, 0xFF168BFF.toInt())
                    })
                }
                card.addView(topLine)
                // Show the actual channel logo in Grid/Card mode, with a readable fallback.
                val cardLogoFallback = text(channel.name.take(1).uppercase(), 20, accent, true).apply {
                    gravity = Gravity.CENTER
                    isClickable = false
                }
                val cardLogo = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = if (isLight) rounded(Color.WHITE, 10, Color.rgb(25, 31, 42), 1) else glass(10)
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    contentDescription = "${channel.name} logo"
                }
                val cardLogoFrame = FrameLayout(this).apply {
                    addView(cardLogo, FrameLayout.LayoutParams(-1, -1))
                    addView(cardLogoFallback, FrameLayout.LayoutParams(-1, -1))
                }
                card.addView(cardLogoFrame, LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                    topMargin = dp(8)
                })
                channel.logo?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let { logoUrl ->
                    loadCachedChannelLogo(cardLogo, cardLogoFallback, logoUrl)
                }
                card.addView(text(channel.name, 17, if (isPlayingChannel) 0xFF72B9FF.toInt() else white, true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(8), 0, dp(4))
                })
                card.addView(text("▶  PLAY CHANNEL", 10, accent, true).apply {
                    letterSpacing = 0.06f
                    setPadding(0, dp(5), 0, 0)
                })
                cardRow.addView(card, LinearLayout.LayoutParams(0, dp(if (isTvDevice) 158 else 166), 1f).apply {
                    when (columnIndex) { 0 -> rightMargin = dp(6); 1 -> { leftMargin = dp(3); rightMargin = dp(3) }; else -> leftMargin = dp(6) }
                })
            }
            if (rowItems.size < 3) {
                repeat(3 - rowItems.size) { cardRow.addView(View(this), LinearLayout.LayoutParams(0, dp(if (isTvDevice) 158 else 166), 1f)) }
            }
            container.addView(cardRow, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(if (isTvDevice) 7 else 12)
            })
        }
        if (filtered.size > displayedLimit) {
            container.addView(actionButton("LOAD MORE  (${filtered.size - displayedLimit} LEFT)") {
                displayedLimit += displayedLimitIncrement
                renderChannelRows(container)
            }, LinearLayout.LayoutParams(-1, dp(50)).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            })
        }
    }

    private fun liveTvVisibleCandidates(): List<Channel> {
        if (!checkerRunning && streamChecks.isEmpty()) return emptyList()
        val working = channels.filter { streamChecks[it.url]?.first == "Working" }
        if (!checkerRunning) return working
        val limit = if (channels.isNotEmpty() && checkerProgressCount.get() * 2 >= channels.size) 60 else 30
        return working.take(limit)
    }

    private fun filteredChannels(): List<Channel> {
        // Live TV shows only streams positively verified by the Checker.
        val liveCandidates = if (section == "Live TV" || section == "Live Sports") liveTvVisibleCandidates() else channels
        return liveCandidates.filter { c ->
            val typeMatch = when (section) {
                "Movies" -> isMovie(c) || (selectedGroup != "All" && matchesMovieCategory(c, selectedGroup))
                "Series" -> isSeries(c)
                "Live Sports" -> false
                else -> !isMovie(c) && !isSeries(c)
            }
            val groupMatch = when {
                section == "Live TV" && selectedGroup == "Select category" -> false
                selectedGroup == "All" -> true
                section == "Series" && selectedGroup == "Anime" -> isAnime(c)
                section == "Movies" && selectedGroup in movieCategories -> matchesMovieCategory(c, selectedGroup)
                section == "Live TV" && selectedGroup.equals("Sports", true) -> isSportsChannel(c)
                else -> normalizeLiveTvCategory(c.group).equals(selectedGroup, true)
            }
            val q = searchQuery.trim().lowercase()
            typeMatch && groupMatch &&
                (q.isBlank() || c.name.lowercase().contains(q) || c.group.lowercase().contains(q))
        }
    }

    private val movieCategories = listOf(
        "Hindi Dubbed", "South Indian", "Hollywood", "Bollywood", "Bangla Movies",
        "Korean Movies", "Anime", "Animation", "Web Series", "Action", "Comedy",
        "Horror", "Romance", "Thriller", "Sci-Fi", "Adventure"
    )

    private fun matchesMovieCategory(c: Channel, category: String): Boolean {
        val metadata = (c.group + " " + c.name + " " + c.url).lowercase()
        val keywords = when (category) {
            "Hindi Dubbed" -> listOf("hindi dubbed", "dubbed hindi", "dual audio", "hindi audio")
            "South Indian" -> listOf("south indian", "south", "tamil", "telugu", "malayalam", "kannada")
            "Hollywood" -> listOf("hollywood", "english movie", "english film")
            "Bollywood" -> listOf("bollywood", "hindi movie", "hindi film")
            "Bangla Movies" -> listOf("bangla movie", "bangla film", "bengali movie", "bengali film")
            "Korean Movies" -> listOf("korean movie", "korean film", "k-drama", "kdrama")
            "Anime" -> listOf("anime", "animé")
            "Animation" -> listOf("animation", "animated")
            "Web Series" -> listOf("web series", "web-series", "webseries", "tv series")
            "Action" -> listOf("action")
            "Comedy" -> listOf("comedy")
            "Horror" -> listOf("horror")
            "Romance" -> listOf("romance", "romantic")
            "Thriller" -> listOf("thriller")
            "Sci-Fi" -> listOf("sci-fi", "sci fi", "science fiction")
            "Adventure" -> listOf("adventure")
            else -> listOf(category.lowercase())
        }
        return keywords.any { metadata.contains(it) }
    }

    // Movies is intentionally kept empty for the upcoming Movies feature.
    // Existing playlist entries that were previously classified as Movies are
    // now routed through the Live TV section and its existing category/checker flow.
    private fun isMovie(c: Channel): Boolean = false
    // Live Sports is a focused view over sports-related entries in the existing playlist.
    // Streams still pass through the same checker verification and Media3 playback path.
    private fun isSportsChannel(c: Channel): Boolean {
        val metadata = (c.group + " " + c.name + " " + c.url).lowercase()
        val keywords = listOf(
            "sport", "espn", "star sports", "ten sports", "sony sports", "fox sports",
            "sky sports", "bein sports", "tnt sports", "willow", "cricket", "football",
            "soccer", "fifa", "ipl", "nba", "nfl", "nhl", "wwe", "ufc", "formula 1",
            "formula one", "motogp", "tennis", "golf", "olympic", "premier league",
            "champions league", "la liga", "bundesliga", "serie a", "uefa", "icc",
            "bcci", "rugby", "badminton", "volleyball", "basketball", "baseball",
            "hockey", "boxing", "wimbledon", "euro cup", "world cup"
        )
        return keywords.any(metadata::contains)
    }

    private fun normalizeLiveTvCategory(group: String): String {
        val value = group.trim()
        return when {
            value.equals("Hindi", true) -> "Hindi"
            value.equals("Indian Bangla", true) -> "Indian Bangla"
            value.equals("Bangla", true) ||
            value.equals("Bangladesh", true) ||
            value.equals("Akash Go", true) -> "Bangla"
            else -> value
        }
    }

    private fun isAnime(c: Channel) = (c.group + " " + c.name + " " + c.url).lowercase().let {
        listOf("anime", "animation", "animé").any(it::contains)
    }
    private fun isSeries(c: Channel) = (c.group + " " + c.name + " " + c.url).lowercase().let {
        isAnime(c) || listOf("series", "season", "episode", "tv show", "tvshows").any(it::contains)
    }

    private fun renderBottomNav() {
        bottomNav.removeAllViews()
        val navigationItems = if (isTvDevice) {
            listOf("Live TV" to "📺", "Live Sports" to "🏆", "Movies" to "🎬", "Settings" to "⚙")
        } else {
            listOf("Live TV" to "📺", "Live Sports" to "🏆", "Checker" to "✓", "Movies" to "🎬", "Series" to "▥", "Settings" to "⚙")
        }
        navigationItems.forEach { (name, symbol) ->
            val active = section == name
            val item = LinearLayout(this).apply {
                isFocusable = true
                isFocusableInTouchMode = false
                applyTvFocusStyle(this)
                orientation = if (isTvDevice) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = if (isTvDevice) Gravity.CENTER_VERTICAL else Gravity.CENTER
                // Make the selected destination unmistakable with a filled glass pill,
                // bright outline and a small top indicator; inactive tabs stay transparent.
                background = if (active) {
                    android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            Color.argb(82, 45, 145, 255),
                            Color.argb(38, 35, 105, 220)
                        )
                    ).apply {
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), Color.argb(190, 75, 165, 255))
                    }
                } else {
                    android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                }
                setPadding(dp(if (isTvDevice) 7 else 3), dp(if (isTvDevice) 5 else 4),
                    dp(if (isTvDevice) 5 else 3), dp(if (isTvDevice) 5 else 3))
                translationY = 0f
                setOnClickListener {
                    if (section == name) return@setOnClickListener
                    // Switch immediately; keep the transition animation lightweight.
                    val previousSection = section
                    if (previousSection == "Live TV") {
                        liveTvSelectedGroup = selectedGroup
                        liveTvSearchQuery = searchQuery
                        prefs.edit().putString("live_tv_group", liveTvSelectedGroup)
                            .putString("live_tv_search", liveTvSearchQuery).apply()
                    }
                    section = name
                    updateHomeHeaderVisibility()
                    if (name == "Live TV") {
                        selectedGroup = liveTvSelectedGroup
                        searchQuery = liveTvSearchQuery
                    } else {
                        selectedGroup = "All"
                        searchQuery = ""
                    }
                    displayedLimit = initialDisplayedLimit
                    // Reuse the existing shell and header; rebuild only the page content
                    // and the five small navigation items. This avoids a full-screen layout
                    // pass on every menu tap.
                    headerTitleView?.text = if (section == "Series") "Anime Series" else section
                    playlistSelectorContainer?.visibility =
                        if (section == "Live TV" || section == "Checker") View.VISIBLE else View.GONE
                    renderSection()
                    renderBottomNav()
                    if (isTvDevice) bottomNav.getChildAt(
                        (if (isTvDevice) listOf("Live TV", "Live Sports", "Movies", "Settings") else listOf("Live TV", "Live Sports", "Checker", "Movies", "Series", "Settings")).indexOf(section)
                    )?.requestFocus()
                }
            }
            if (name == "Live TV" && checkerRunning) {
                val loadingRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(if (active) accent else Color.rgb(64, 170, 255))
                }
                loadingRow.addView(spinner, LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                    rightMargin = dp(5)
                })
                val navLabel = text(liveTvProgressLabel(), 13, if (active) accent else Color.rgb(64, 170, 255), true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(2), 0, 0)
                }
                liveTvNavLabel = navLabel
                loadingRow.addView(navLabel)
                item.addView(loadingRow, LinearLayout.LayoutParams(-1, -2))
            } else {
                if (active) {
                    item.addView(android.view.View(this).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.rgb(80, 175, 255))
                        }
                    }, LinearLayout.LayoutParams(dp(5), dp(5)).apply {
                        bottomMargin = dp(2)
                    })
                } else {
                    item.addView(android.view.View(this), LinearLayout.LayoutParams(dp(5), dp(5)))
                }
                item.addView(text(symbol, if (isTvDevice) 17 else if (name == "Live TV" || name == "Movies") 16 else 20,
                    if (active) Color.rgb(95, 180, 255) else muted, true).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(if (isTvDevice) dp(25) else -2, -2))
                if (isTvDevice) {
                    val tvNavLabel = text(
                        if (name == "Series") "Anime" else name, 10,
                        if (active) Color.rgb(115, 190, 255) else muted, active
                    ).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    if (name == "Live TV") liveTvNavLabel = tvNavLabel
                    item.addView(tvNavLabel, LinearLayout.LayoutParams(0, -2, 1f))
                }
                if (!isTvDevice) {
                    val navLabel = text(if (name == "Live TV") liveTvProgressLabel() else if (name == "Series") "Anime Series" else name,
                        12, if (active) Color.rgb(115, 190, 255) else muted, active).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, 0)
                    }
                    if (name == "Live TV") liveTvNavLabel = navLabel
                    item.addView(navLabel)
                }
            }
            bottomNav.addView(item, if (isTvDevice)
                LinearLayout.LayoutParams(-1, 0, 1f)
            else LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun showExtensionManager() {
        val actions = arrayOf(
            "Add CloudStream repository",
            "View / remove saved repositories",
            "Refresh extension catalogue",
            "View downloaded packages",
            "Check CloudStream host API compatibility",
            "Runtime compatibility information"
        )
        AlertDialog.Builder(this)
            .setTitle("Extensions & Repositories")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val field = EditText(this).apply {
                            hint = "https://example.com/repo.json"
                            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                            setSingleLine(true)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Add extension repository")
                            .setMessage("CloudStream repository JSON URL দিন (HTTPS only)।")
                            .setView(field)
                            .setPositiveButton("ADD") { _, _ ->
                                if (SanyExtensionRepositories.add(this, field.text.toString().trim())) {
                                    toast("Repository saved")
                                } else {
                                    toast("Valid HTTPS repository URL দিন")
                                }
                            }
                            .setNegativeButton("CANCEL", null)
                            .show()
                    }
                    1 -> {
                        val urls = SanyExtensionRepositories.all(this)
                        if (urls.isEmpty()) {
                            toast("কোনো repository যোগ করা হয়নি")
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Saved repositories — remove করতে ট্যাপ করুন")
                                .setItems(urls.toTypedArray()) { _, index ->
                                    AlertDialog.Builder(this)
                                        .setMessage("এই repository সরাবেন?")
                                        .setPositiveButton("REMOVE") { _, _ ->
                                            SanyExtensionRepositories.remove(this, urls[index])
                                            toast("Repository removed")
                                        }
                                        .setNegativeButton("CANCEL", null)
                                        .show()
                                }
                                .setNegativeButton("CLOSE", null)
                                .show()
                        }
                    }
                    2 -> {
                        if (SanyExtensionRepositories.all(this).isEmpty()) {
                            toast("আগে একটি HTTPS repository যোগ করুন")
                        } else {
                            toast("Extension catalogue loading…")
                            thread(name = "SANY-Extension-Catalogue") {
                                val catalog = runCatching {
                                    SanyExtensionRepositories.fetchCatalog(this)
                                }.getOrElse { emptyList() }
                                runOnUiThread {
                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                    val plugins = catalog.flatMap { (repository, entries) ->
                                        entries.map { repository to it }
                                    }
                                    if (plugins.isEmpty()) {
                                        AlertDialog.Builder(this)
                                            .setTitle("Extension catalogue")
                                            .setMessage("কোনো plugin metadata পাওয়া যায়নি। Repository URL এবং plugins.json যাচাই করুন।")
                                            .setPositiveButton("OK", null)
                                            .show()
                                    } else {
                                        val labels = plugins.map { (_, plugin) ->
                                            val state = when (plugin.status) {
                                                0 -> "Offline"
                                                2 -> "Slow"
                                                3 -> "Beta"
                                                else -> "Available"
                                            }
                                            "${plugin.name}  •  v${plugin.version}  •  $state"
                                        }.toTypedArray()
                                        AlertDialog.Builder(this)
                                            .setTitle("Available extensions (${plugins.size})")
                                            .setItems(labels) { _, index ->
                                                val (repository, plugin) = plugins[index]
                                                val tvTypes = plugin.tvTypes.joinToString(", ").ifBlank { "Not specified" }
                                                val size = plugin.fileSize?.let { "${it / 1024} KB" } ?: "Unknown"
                                                AlertDialog.Builder(this)
                                                    .setTitle(plugin.name)
                                                    .setMessage(
                                                        "Repository: $repository\n" +
                                                        "Internal name: ${plugin.internalName}\n" +
                                                        "Version: ${plugin.version}\n" +
                                                        "CloudStream API: ${plugin.apiVersion}\n" +
                                                        "Status: ${plugin.status}\n" +
                                                        "Language: ${plugin.language}\n" +
                                                        "Types: $tvTypes\n" +
                                                        "Size: $size\n" +
                                                        "Authors: ${plugin.authors}\n\n" +
                                                        "${plugin.description}\n\n" +
                                                        "Download-এর পর SHA-256, ZIP ও manifest যাচাই করে CloudStream DEX runtime-এ load করার চেষ্টা হবে।"
                                                    )
                                                    .setPositiveButton("DOWNLOAD & VERIFY") { _, _ ->
                                                        toast("Downloading and verifying…")
                                                        thread(name = "SANY-Plugin-Download") {
                                                            val result = runCatching {
                                                                val installed = SanyExtensionRepositories.downloadAndValidate(this, plugin)
                                                                installed to SanyCloudStreamPluginRuntime.load(this, installed.file)
                                                            }
                                                            runOnUiThread {
                                                                if (isFinishing || isDestroyed) return@runOnUiThread
                                                                result.onSuccess { (installed, loaded) ->
                                                                    AlertDialog.Builder(this)
                                                                        .setTitle(if (loaded.success) "CloudStream plugin loaded" else "Package verified; load failed")
                                                                        .setMessage(
                                                                            "Name: ${installed.manifest.name}\n" +
                                                                            "Plugin class: ${installed.manifest.pluginClassName}\n" +
                                                                            "Version: ${installed.manifest.version}\n" +
                                                                            "SHA-256: ${installed.sha256}\n\n" +
                                                                            if (loaded.success) "Registered providers:\n" + loaded.providerNames.joinToString("\n")
                                                                            else "DEX load error: ${loaded.error ?: "Unknown error"}"
                                                                        )
                                                                        .setPositiveButton("OK", null)
                                                                        .show()
                                                                }.onFailure { error ->
                                                                    AlertDialog.Builder(this)
                                                                        .setTitle("Plugin verification failed")
                                                                        .setMessage(error.message ?: "Unknown error")
                                                                        .setPositiveButton("OK", null)
                                                                        .show()
                                                                }
                                                            }
                                                        }
                                                    }
                                                    .setNegativeButton("CLOSE", null)
                                                    .show()
                                            }
                                            .setNegativeButton("CLOSE", null)
                                            .show()
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        val files = SanyExtensionRepositories.installedPackages(this)
                        if (files.isEmpty()) {
                            toast("কোনো verified package নেই")
                        } else {
                            val names = files.map { it.name }.toTypedArray()
                            AlertDialog.Builder(this)
                                .setTitle("Downloaded packages")
                                .setItems(names) { _, index ->
                                    val selected = files[index]
                                    AlertDialog.Builder(this)
                                        .setTitle(selected.name)
                                        .setItems(arrayOf("LOAD / RELOAD", "REMOVE")) { _, action ->
                                            if (action == 0) {
                                                toast("CloudStream DEX loading…")
                                                thread(name = "SANY-Plugin-Load") {
                                                    val result = SanyCloudStreamPluginRuntime.load(this, selected)
                                                    runOnUiThread {
                                                        if (isFinishing || isDestroyed) return@runOnUiThread
                                                        AlertDialog.Builder(this)
                                                            .setTitle(if (result.success) "Plugin loaded" else "Plugin load failed")
                                                            .setMessage(if (result.success) result.providerNames.joinToString("\n") else result.error ?: "Unknown DEX load error")
                                                            .setPositiveButton("OK", null)
                                                            .show()
                                                    }
                                                }
                                            } else {
                                                SanyCloudStreamPluginRuntime.unload(selected)
                                                if (SanyExtensionRepositories.removeInstalledPackage(this, selected.name)) {
                                                    toast("Package removed")
                                                } else {
                                                    toast("Package remove করা যায়নি")
                                                }
                                            }
                                        }
                                        .setNegativeButton("CANCEL", null)
                                        .show()
                                }
                                .setNegativeButton("CLOSE", null)
                                .show()
                        }
                    }
                    4 -> {
                        val report = SanyExtensionRepositories.inspectHostCompatibility(this)
                        val message = if (report.compatible) {
                            "সব ${report.checkedClasses.size}টি core CloudStream API class পাওয়া গেছে.\n\n" +
                            "Core CloudStream ABI এবং AniLight Plugin host class পাওয়া গেছে। এটি runtime load-এর প্রস্তুতি যাচাই; প্রতিটি plugin-এর সফল DEX load আলাদাভাবে পরীক্ষা করতে হবে।"
                        } else {
                            "SANY-তে ${report.missingClasses.size}টি প্রয়োজনীয় CloudStream API class নেই:\n\n" +
                            report.missingClasses.joinToString("\n") { "• $it" } +
                            "\n\nএই classগুলো host app-এ যোগ না হওয়া পর্যন্ত .cs3 plugin চালানো যাবে না।"
                        }
                        AlertDialog.Builder(this)
                            .setTitle(if (report.compatible) "Host API check: classes found" else "Host API check: missing classes")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    5 -> AlertDialog.Builder(this)
                        .setTitle("SANY Extension Runtime")
                        .setMessage(SanyExtensionRepositories.runtimeNotice())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun wifiBandPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun hasWifiBandPermission(): Boolean =
        checkSelfPermission(wifiBandPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // Read only the active Wi-Fi connection's frequency and negotiated link rate.
    // No location coordinates or nearby-network scan results are accessed.
    private fun currentWifiInfo(): WifiInfo? {
        if (!hasWifiBandPermission()) return null
        return try {
            val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivity.activeNetwork ?: return null
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                capabilities.transportInfo as? WifiInfo
            } else {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            }
        } catch (_: Exception) {
            null
        }
    }

    // 0 = unknown/not Wi-Fi, 2 = 2.4 GHz, 5 = 5/6 GHz high-throughput band.
    private fun currentWifiBandCode(): Int {
        val frequency = currentWifiInfo()?.frequency ?: return 0
        return when (frequency) {
            in 2400..2500 -> 2
            in 4900..7125 -> 5
            else -> 0
        }
    }

    private fun wifiConnectionSummary(): String {
        if (!hasWifiBandPermission()) return "Tap to allow Wi-Fi band detection"
        val info = currentWifiInfo() ?: return "Wi-Fi not connected or band unavailable"
        val frequency = info.frequency
        val band = when (frequency) {
            in 2400..2500 -> "2.4 GHz"
            in 4900..5924 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            else -> "Band unavailable"
        }
        val speed = info.linkSpeed
        return if (speed > 0) "Connected • $band • Link speed $speed Mbps"
        else "Connected • $band"
    }

    private fun openWifiBandDetails() {
        if (!hasWifiBandPermission()) {
            requestPermissions(arrayOf(wifiBandPermission()), 905)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi connection")
            .setMessage(wifiConnectionSummary())
            .setPositiveButton("REFRESH") { dialog, _ ->
                dialog.dismiss()
                renderHome()
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 905 && section == "Settings") {
            renderHome()
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("Wi-Fi band detection enabled")
            } else {
                toast("Wi-Fi band detection permission not granted")
            }
        }
    }

    private fun renderSettings() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        scroll.addView(box)
        box.addView(text("APPEARANCE", 11, accent, true).apply { setPadding(0, dp(10), 0, dp(12)) })
        settingsCard(box, "Customize accent color", "Change the app highlight color") {
            val labels = arrayOf("Blue", "Teal", "Purple", "Orange")
            val values = arrayOf("blue", "teal", "purple", "orange")
            AlertDialog.Builder(this).setTitle("Accent color")
                .setSingleChoiceItems(labels, values.indexOf(prefs.getString("accent_color", "blue")).coerceAtLeast(0)) { dialog, which ->
                    prefs.edit().putString("accent_color", values[which]).apply()
                    dialog.dismiss()
                    renderHome()
                }.setNegativeButton("CANCEL", null).show()
        }
        settingsToggleCard(box, "Low internet mode", "Android 6–13: ON by default for faster loading; Android 14+: OFF for unrestricted quality", "low_data_mode", defaultLowInternetMode)
        settingsToggleCard(box, "Reduce UI animations", "ON by default for a smoother, faster interface", "reduce_ui_motion", true)
        settingsCard(box, "App theme", "Choose Day (light), Night (dark), or follow your phone") {
            val modes = arrayOf("Day mode", "Night mode", "System default")
            val values = arrayOf("light", "dark", "system")
            val current = prefs.getString("app_theme", "dark")
            AlertDialog.Builder(this).setTitle("Choose app theme")
                .setSingleChoiceItems(modes, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                    prefs.edit().putString("app_theme", values[which]).apply()
                    dialog.dismiss()
                    // Recreate only the three theme-aware catalogues so cached Views
                    // cannot keep the previous theme's background colors.
                    moviesNativeView = null
                    movieDetailsParentView = null
                    liveSportsCatalogueView = null
                    aniLightNativeScreen = null
                    aniLightNativeView = null
                    cloudStreamProviderScreen = null
                    cloudStreamProviderView = null
                    animeExternalPageActive = false
                    animeExternalPageReturnToProvider = false
                    applySystemBars()
                    renderHome()
                }.setNegativeButton("CANCEL", null).show()
        }
        val ispBypassesDropdown = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = glass(14)
        }
        val ispBypassesHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val ispBypassesLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ispBypassesLabels.addView(text("ISP BYPASSES", 15, white, true))
        ispBypassesLabels.addView(text("Clone Site • DNS • DNS Provider • GitHub Proxy", 11, muted).apply {
            setPadding(0, dp(4), 0, 0)
        })
        ispBypassesHeader.addView(ispBypassesLabels, LinearLayout.LayoutParams(0, -2, 1f))
        val ispBypassesArrow = text("⌄", 22, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(4), 0)
        }
        ispBypassesHeader.addView(ispBypassesArrow)
        ispBypassesDropdown.addView(ispBypassesHeader, LinearLayout.LayoutParams(-1, -2))
        val ispBypassesContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        settingsCard(ispBypassesContent, "Clone Site", "Save an alternate HTTPS URL for an extension/repository host") {
            showCloneSiteDialog()
        }
        settingsToggleCard(ispBypassesContent, "DNS over HTTPS", "Encrypted DNS enabled by default for extension, provider and supported playback requests", "bypass_doh_enabled", true) { enabled ->
            getSharedPreferences("sany_network_bypasses", MODE_PRIVATE).edit().putBoolean("doh_enabled", enabled).apply()
        }
        settingsCard(ispBypassesContent, "DNS Provider", "Choose Cloudflare, Google or Quad9") {
            val labels = arrayOf("Cloudflare", "Google", "Quad9")
            val current = getSharedPreferences("sany_network_bypasses", MODE_PRIVATE).getString("doh_provider", "Cloudflare")
            AlertDialog.Builder(this).setTitle("DNS over HTTPS provider")
                .setSingleChoiceItems(labels, labels.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                    getSharedPreferences("sany_network_bypasses", MODE_PRIVATE).edit().putString("doh_provider", labels[which]).apply()
                    dialog.dismiss()
                    toast("DNS provider saved")
                }.setNegativeButton("CANCEL", null).show()
        }
        settingsToggleCard(ispBypassesContent, "GitHub Proxy", "Use jsDelivr CDN for raw GitHub extension files by default", "bypass_github_proxy", true) { enabled ->
            getSharedPreferences("sany_network_bypasses", MODE_PRIVATE).edit().putBoolean("github_proxy", enabled).apply()
        }
        ispBypassesDropdown.addView(ispBypassesContent, LinearLayout.LayoutParams(-1, -2))
        ispBypassesHeader.setOnClickListener {
            val expanded = ispBypassesContent.visibility != View.VISIBLE
            ispBypassesContent.visibility = if (expanded) View.VISIBLE else View.GONE
            ispBypassesArrow.text = if (expanded) "⌃" else "⌄"
        }
        box.addView(ispBypassesDropdown, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        settingsCard(box, "Wi-Fi Dual-Band", wifiConnectionSummary()) {
            openWifiBandDetails()
        }
        val playbackDropdown = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = glass(14)
        }
        val playbackHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val playbackLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playbackLabels.addView(text("PLAYBACK SETTINGS", 15, white, true))
        playbackLabels.addView(text("Video • Audio • Buffer • Playback controls", 11, muted).apply {
            setPadding(0, dp(4), 0, 0)
        })
        playbackHeader.addView(playbackLabels, LinearLayout.LayoutParams(0, -2, 1f))
        val playbackArrow = text("⌄", 22, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(4), 0)
        }
        playbackHeader.addView(playbackArrow)
        playbackDropdown.addView(playbackHeader, LinearLayout.LayoutParams(-1, -2))
        val playbackContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        settingsToggleCard(
            playbackContent,
            "Wi-Fi Smart Streaming",
            "Automatically tune video buffering for the connected 2.4 GHz or 5/6 GHz Wi-Fi band",
            "wifi_smart_streaming",
            true
        ) { enabled ->
            toast(if (enabled) "Wi-Fi Smart Streaming enabled — applies next playback" else "Wi-Fi Smart Streaming disabled")
        }
        settingsToggleCard(
            playbackContent,
            "High-Speed Streaming",
            "Prioritize maximum available video quality and a larger playback buffer",
            "high_speed_streaming",
            true
        ) { enabled ->
            if (enabled) prefs.edit().putString("network_buffer_size", "high").apply()
            applyVideoQuality()
            toast(if (enabled) "High-speed streaming enabled — buffer applies next playback" else "Standard streaming restored")
        }
        settingsCard(playbackContent, "Playback speed", "Choose normal or faster/slower video playback") {
            val labels = arrayOf("0.75×", "1.0× (Normal)", "1.25×", "1.5×", "2.0×")
            val values = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            val current = prefs.getFloat("playback_speed", 1.0f)
            val selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 1
            AlertDialog.Builder(this).setTitle("Playback speed")
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    prefs.edit().putFloat("playback_speed", values[which]).apply()
                    try { player?.setPlaybackSpeed(values[which]) } catch (_: Exception) { }
                    dialog.dismiss()
                    toast("Playback speed saved")
                }.setNegativeButton("CANCEL", null).show()
        }
        settingsToggleCard(playbackContent, "Auto Play", "Automatically start a stream when you select a channel", "auto_play", true)
        settingsCard(playbackContent, "Video Quality", "Choose Auto, 480p, 720p or 1080p") {
            val labels = arrayOf("Auto", "480p", "720p", "1080p")
            val values = arrayOf("auto", "480", "720", "1080")
            val selected = values.indexOf(prefs.getString("video_quality", "auto")).coerceAtLeast(0)
            AlertDialog.Builder(this).setTitle("Video Quality")
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    prefs.edit().putString("video_quality", values[which]).apply()
                    applyVideoQuality()
                    dialog.dismiss()
                    toast("Video quality saved")
                }.setNegativeButton("CANCEL", null).show()
        }
        settingsToggleCard(playbackContent, "Hardware Decoder", "Prefer hardware video decoding when supported; OFF prefers software decoding", "hardware_decoder", true)
        settingsCard(playbackContent, "Buffer Control", "Choose buffering for slower or faster connections") {
            val labels = arrayOf("Low — faster start", "Balanced — recommended", "High — fewer interruptions")
            val values = arrayOf("low", "balanced", "high")
            val selected = values.indexOf(prefs.getString("network_buffer_size", defaultNetworkBufferMode)).coerceAtLeast(0)
            AlertDialog.Builder(this).setTitle("Buffer Control")
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    prefs.edit().putString("network_buffer_size", values[which]).apply()
                    dialog.dismiss()
                    toast("Buffer setting saved — applies to next playback")
                }.setNegativeButton("CANCEL", null).show()
        }
        settingsToggleCard(playbackContent, "Auto Retry on Failure", "Retry a failed stream up to 2 times automatically", "auto_retry_playback", true)
        playbackDropdown.addView(playbackContent, LinearLayout.LayoutParams(-1, -2))
        playbackHeader.setOnClickListener {
            val expanded = playbackContent.visibility != View.VISIBLE
            playbackContent.visibility = if (expanded) View.VISIBLE else View.GONE
            playbackArrow.text = if (expanded) "⌃" else "⌄"
        }
        box.addView(playbackDropdown, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        box.addView(text("PLAYER & PLAYLIST", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        settingsCard(box, "Playlist manager", "Save multiple M3U links and switch between them") { showPlaylistDialog() }
        settingsCard(box, "Playlist Checker", "Test channel links and report Working, Offline, Restricted or Error") { checkPlaylistStreams() }
        settingsToggleCard(box, "Floating window", "Show video in a floating window when you leave the app", "floating_window_enabled", true) { enabled ->
            if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                closePlayer()
            }
            refreshPictureInPictureParams()
        }
        settingsCard(box, "Reload playlist", "Fetch the saved playlist again") {
            loadFromUrl(permanentPlaylistUrl)
        }
        settingsCard(box, "Permanent SANY playlist", "Built-in owner playlist — locked and cannot be removed from the app") {
            loadFromUrl(permanentPlaylistUrl)
        }
        box.addView(text("APP UPDATES", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        settingsCard(box, "Check for updates", "Look for the latest version and download it") { checkForAppUpdate(true) }
        box.addView(text("DEVICE & ADMIN", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        settingsCard(box, "Copy Device ID (Admin)", "Copy this phone's ID to allow admin access during maintenance") {
            val deviceId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ).orEmpty()
            if (deviceId.isBlank()) {
                toast("Device ID পাওয়া যায়নি")
            } else {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SANY IPTV device ID", deviceId))
                toast("Device ID copied — এখানে পাঠান")
            }
        }
        val additionalSettingsDropdown = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = glass(14)
        }
        val additionalSettingsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val additionalSettingsLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        additionalSettingsLabels.addView(text("ADDITIONAL SETTINGS", 15, white, true))
        additionalSettingsLabels.addView(text("EPG • Automatic EPG • EPG sources", 11, muted).apply {
            setPadding(0, dp(4), 0, 0)
        })
        additionalSettingsHeader.addView(additionalSettingsLabels, LinearLayout.LayoutParams(0, -2, 1f))
        val additionalSettingsArrow = text("⌄", 22, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(4), 0)
        }
        additionalSettingsHeader.addView(additionalSettingsArrow)
        additionalSettingsDropdown.addView(additionalSettingsHeader, LinearLayout.LayoutParams(-1, -2))
        val additionalSettingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        settingsCard(additionalSettingsContent, "Update EPG data", "Manually refresh all 3 XMLTV sources now") {
            refreshEpgSources(true)
        }
        settingsToggleCard(additionalSettingsContent, "Auto add EPG data", "Detect playlist EPG URL automatically; daily EPG refresh remains scheduled", "auto_add_epg", true)
        settingsCard(additionalSettingsContent, "EPG sources (3)", "View the 3 built-in feeds and daily update schedule") {
            showBuiltInEpgSources()
        }
        additionalSettingsDropdown.addView(additionalSettingsContent, LinearLayout.LayoutParams(-1, -2))
        additionalSettingsHeader.setOnClickListener {
            val expanded = additionalSettingsContent.visibility != View.VISIBLE
            additionalSettingsContent.visibility = if (expanded) View.VISIBLE else View.GONE
            additionalSettingsArrow.text = if (expanded) "⌃" else "⌄"
        }
        box.addView(additionalSettingsDropdown, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        box.addView(text("ABOUT", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        box.addView(text("SANY IPTV  •  Version 1.1\nM3U / M3U8 playlist player powered by Media3.", 13, muted).apply {
            setPadding(dp(15), dp(16), dp(15), dp(16)); background = rounded(panel, 12)
        })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(22), dp(12), dp(24))
            addView(text("This App Made By Sany Sarfaraz", 14, white, true))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(text("f", 25, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.rgb(24, 119, 242))
                        }
                        elevation = dp(3).toFloat()
                        setOnClickListener {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/share/1A6x5C9HTM/")))
                            } catch (_: Exception) { toast("Facebook link খুলতে সমস্যা হয়েছে") }
                        }
                        contentDescription = "Open Sany Sarfaraz Facebook profile"
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    })
                    addView(text("Facebook", 11, muted, true).apply { setPadding(0, dp(6), 0, 0) })
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(28), 0, 0, 0)
                    addView(android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_telegram)
                        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.rgb(0, 136, 204))
                        }
                        elevation = dp(3).toFloat()
                        setOnClickListener {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/sanyiptv100")))
                            } catch (_: Exception) { toast("Telegram link খুলতে সমস্যা হয়েছে") }
                        }
                        contentDescription = "Open SANY IPTV Telegram channel"
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    })
                    addView(text("Telegram", 11, muted, true).apply { setPadding(0, dp(6), 0, 0) })
                })
            })
        })
        contentArea.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun checkForAppUpdate(showUpToDate: Boolean) {
        thread(name = "SANY-App-Update-Check") {
            var connection: java.net.HttpURLConnection? = null
            try {
                // Use GitHub's latest-release redirect instead of the Releases API,
                // which can return HTTP 403 when its unauthenticated rate limit is reached.
                connection = (URL("https://github.com/Sany100/Sany-iptv/releases/latest")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "SANY-IPTV-Android")
                    setRequestProperty("Accept", "text/html")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("GitHub HTTP " + code)
                val finalUrl = connection.url.toString()
                connection.disconnect()
                connection = null

                val tag = Regex("/releases/tag/([^/?#]+)", RegexOption.IGNORE_CASE)
                    .find(finalUrl)?.groupValues?.getOrNull(1)
                    ?.trim()?.removePrefix("v")?.removePrefix("V").orEmpty()
                if (tag.isBlank()) throw IllegalStateException("Latest release version পাওয়া যায়নি")

                val current = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                // The current workflow publishes one universal APK for phones,
                // tablets, Android TV and Android TV boxes.
                val apkUrl = "https://github.com/Sany100/Sany-iptv/releases/latest/download/app-release.apk"
                val newer = compareVersions(tag, current) > 0
                runOnUiThread {
                    if (!newer) {
                        prefs.edit().remove("last_prompted_update_version").apply()
                    }
                    if (newer) {
                        prefs.edit().putString("last_prompted_update_version", tag).apply()
                        showSanyUpdateDialog(current, tag, "নতুন সংস্করণ উপলব্ধ।", apkUrl)
                    } else if (showUpToDate) {
                        showUpToDateDialog(current)
                    }
                }
            } catch (e: Exception) {
                connection?.disconnect()
                runOnUiThread {
                    if (showUpToDate) toast("Update check failed: " + (e.message ?: "network error"))
                }
            }
        }
    }

    private fun showUpToDateDialog(current: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8, 38, 83), Color.rgb(18, 91, 132), Color.rgb(25, 54, 119), Color.rgb(76, 42, 132))
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(105, 205, 255))
            }
            elevation = dp(12).toFloat()
        }
        val emblem = TextView(this).apply {
            text = "✓"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(40, 220, 174), Color.rgb(35, 151, 225))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.argb(180, 220, 255, 255))
            }
            elevation = dp(8).toFloat()
        }
        card.addView(emblem, LinearLayout.LayoutParams(dp(68), dp(68)).apply { bottomMargin = dp(14) })
        card.addView(text("SANY IPTV  •  SYSTEM STATUS", 11, Color.rgb(177, 226, 255), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.10f
        })
        card.addView(text("You're up to date", 23, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(6))
        })
        card.addView(text("আপনার অ্যাপটি সর্বশেষ সংস্করণে রয়েছে", 13, Color.rgb(225, 241, 255), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        })
        val versionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(48, 255, 255, 255))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(100, 180, 230, 255))
            }
        }
        versionPanel.addView(text("CURRENT VERSION", 10, Color.rgb(190, 225, 255), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        })
        versionPanel.addView(text("v$current", 21, Color.rgb(255, 224, 132), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(versionPanel, LinearLayout.LayoutParams(-1, -2))
        val ok = TextView(this).apply {
            text = "✓     GREAT, THANK YOU"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(7, 45, 70))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(91, 245, 205), Color.rgb(110, 206, 255))
            ).apply {
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), Color.rgb(200, 255, 255))
            }
        }
        card.addView(ok, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        val dialog = AlertDialog.Builder(this).setView(card).create()
        ok.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setDimAmount(0.62f)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels - dp(42)).coerceAtLeast(dp(280)),
                -2
            )
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(42)).coerceAtLeast(dp(280)),
            -2
        )
    }

    private fun showSanyUpdateDialog(current: String, latest: String, notes: String, apkUrl: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(10, 83, 170), Color.rgb(35, 45, 125), Color.rgb(91, 39, 145))
            ).apply { cornerRadius = dp(22).toFloat() }
        }
        card.addView(text("✦  SANY IPTV", 13, Color.rgb(190, 224, 255), true))
        card.addView(text("A NEW EXPERIENCE\nIS READY!", 24, Color.WHITE, true).apply {
            setPadding(0, dp(10), 0, dp(8))
        })
        card.addView(text("আপনার জন্য নতুন আপডেট এসেছে", 14, Color.WHITE, true))
        val versions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(45, 255, 255, 255))
                cornerRadius = dp(14).toFloat()
            }
        }
        versions.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("INSTALLED", 10, Color.rgb(205, 215, 255), true))
            addView(text("v$current", 16, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        versions.addView(text("➜", 20, Color.rgb(255, 220, 110), true).apply {
            setPadding(dp(8), 0, dp(8), 0)
        })
        versions.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("LATEST", 10, Color.rgb(205, 215, 255), true))
            addView(text("v$latest", 16, Color.rgb(255, 225, 125), true).apply { setPadding(0, dp(4), 0, 0) })
        })
        card.addView(versions.apply { setPadding(dp(12), dp(12), dp(12), dp(12)) })
        card.addView(text("WHAT'S NEW", 11, Color.rgb(190, 224, 255), true).apply {
            setPadding(0, dp(16), 0, dp(6))
        })
        card.addView(text(notes.ifBlank { "নতুন ফিচার, উন্নত পারফরম্যান্স এবং আরও ভালো অভিজ্ঞতা উপভোগ করুন।" }, 13, Color.WHITE).apply {
            maxLines = 7
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(35, 255, 255, 255))
                cornerRadius = dp(12).toFloat()
            }
        })
        val dialog = AlertDialog.Builder(this)
            .setView(card)
            .setNegativeButton("REMIND ME LATER") { reminderDialog, _ -> reminderDialog.dismiss() }
            .create()
        // Let the user postpone the update; the hourly reminder will show it again.
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        val downloadButton = TextView(this).apply {
            text = "⬇  DOWNLOAD & INSTALL"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isAllCaps = false
            setPadding(dp(14), dp(15), dp(14), dp(15))
            elevation = dp(5).toFloat()
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(46, 204, 113), Color.rgb(22, 145, 78))
            ).apply {
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), Color.rgb(153, 255, 190))
            }
            contentDescription = "Download and install SANY IPTV update"
            setOnClickListener {
                dialog.dismiss()
                downloadAndInstallUpdate(apkUrl, latest)
            }
        }
        actionRow.addView(downloadButton, LinearLayout.LayoutParams(-1, -2))
        card.addView(actionRow)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(dp(280)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun compareVersions(first: String, second: String): Int {
        val a = first.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
        val b = second.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun downloadAndInstallUpdate(url: String, version: String) {
        var receiver: android.content.BroadcastReceiver? = null
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            var downloadId = -1L
            val filter = android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)

            // Register before enqueue so a fast download cannot finish before we start listening.
            receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    if (intent?.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                    try {
                        unregisterReceiver(this)
                        val query = android.app.DownloadManager.Query().setFilterById(downloadId)
                        manager.query(query).use { cursor ->
                            if (!cursor.moveToFirst()) {
                                toast("ডাউনলোডের তথ্য পাওয়া যায়নি")
                                return
                            }
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                            if (status != android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON))
                                toast("APK download failed (code " + reason + ")")
                                return
                            }
                        }
                        val apkUri = manager.getUriForDownloadedFile(downloadId)
                        if (apkUri == null) {
                            toast("ডাউনলোড সম্পন্ন, কিন্তু APK ফাইল খুঁজে পাওয়া যায়নি")
                            return
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + packageName)))
                            toast("এই অ্যাপের জন্য Install unknown apps অনুমতি দিন, তারপর Downloads থেকে APK ইনস্টল করুন")
                            return
                        }
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(installIntent)
                    } catch (e: Exception) {
                        toast("Install open failed: " + (e.message ?: "unknown error"))
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }

            val request = android.app.DownloadManager.Request(Uri.parse(url))
                .setTitle("SANY IPTV " + version)
                .setDescription("Downloading update APK…")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    "SANY-IPTV-" + version + ".apk"
                )
            downloadId = manager.enqueue(request)
            toast("APK ডাউনলোড শুরু হয়েছে — Notification দেখুন")
        } catch (e: Exception) {
            try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) { }
            toast("Download failed: " + (e.message ?: "unknown error"))
        }
    }

    private fun showCloneSiteDialog() {
        val bypassPrefs = getSharedPreferences("sany_network_bypasses", MODE_PRIVATE)
        val mappings = SanyNetworkBypasses.cloneMappings(this)
        val hostInput = EditText(this).apply {
            hint = "Original host (e.g. raw.githubusercontent.com)"
            setSingleLine(true); setTextColor(white); setHintTextColor(muted)
        }
        val mirrorInput = EditText(this).apply {
            hint = "Mirror base URL (https://...)"
            setSingleLine(true); setTextColor(white); setHintTextColor(muted)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(hostInput, LinearLayout.LayoutParams(-1, dp(50)))
            addView(mirrorInput, LinearLayout.LayoutParams(-1, dp(50)))
            if (mappings.isNotEmpty()) addView(text("Saved:\\n" + mappings.entries.joinToString("\\n") { "${it.key} → ${it.value}" }, 12, muted).apply {
                setPadding(0, dp(10), 0, dp(10))
            })
        }
        AlertDialog.Builder(this).setTitle("Clone Site")
            .setMessage("Original host-এর বিকল্প HTTPS mirror URL দিন। এটি SANY extension repository/catalogue ও package request-এ প্রয়োগ হবে।")
            .setView(content)
            .setPositiveButton("SAVE") { _, _ ->
                if (SanyNetworkBypasses.saveClone(this, hostInput.text.toString(), mirrorInput.text.toString())) toast("Clone site saved")
                else toast("সঠিক host এবং HTTPS mirror URL দিন")
            }
            .setNeutralButton("REMOVE ALL") { _, _ ->
                bypassPrefs.edit().putString("clone_sites", "{}").apply()
                toast("Clone site mappings removed")
            }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun settingsToggleCard(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        prefKey: String,
        defaultValue: Boolean,
        onChanged: ((Boolean) -> Unit)? = null
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(12), dp(12), dp(12))
            background = glass(12)
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 15, white, true))
        labels.addView(text(subtitle, 12, muted).apply { setPadding(0, dp(5), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(prefKey, defaultValue)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(prefKey, enabled).apply()
                onChanged?.invoke(enabled)
                toast(if (enabled) "$title ON" else "$title OFF")
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun settingsCard(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = glass(12)
            // Settings cards must be reachable with a TV remote's DPAD.
            // Keep the phone touch interaction unchanged.
            isFocusable = packageName.endsWith(".tv")
            isClickable = true
            setOnClickListener { action() }
        }
        card.addView(text(title, 15, white, true))
        card.addView(text(subtitle, 12, muted).apply { setPadding(0, dp(5), 0, 0) })
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun editServer(prefKey: String, title: String) {
        val input = EditText(this).apply {
            hint = "https://your-provider.example/playlist.m3u"
            setSingleLine(true); setText(prefs.getString(prefKey, ""))
            setTextColor(white); setHintTextColor(muted)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        }
        AlertDialog.Builder(this).setTitle(title).setView(wrap)
            .setPositiveButton("CONNECT") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) toast("Server URL দিন") else loadServerPlaylist(prefKey, url)
            }
            .setNeutralButton("REMOVE") { _, _ -> prefs.edit().remove(prefKey).apply(); toast("$title removed") }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun loadServerPlaylist(prefKey: String, url: String) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            toast("সঠিক HTTP/HTTPS playlist URL দিন"); return
        }
        toast("Connecting server…")
        thread {
            try {
                val content = URL(url).openConnection().apply {
                    connectTimeout = 9000; readTimeout = 12000
                }.getInputStream().bufferedReader().use { it.readText() }
                val parsed = M3uParser.parse(content)
                val prefix = when (prefKey) {
                    "server_live_url" -> "Live • "
                    "server_series_url" -> "Series • "
                    else -> "VOD • "
                }
                val tagged = parsed.map { it.copy(group = prefix + it.group) }
                runOnUiThread {
                    if (tagged.isEmpty()) toast("এই সার্ভারে কোনো playable item নেই")
                    else {
                        val urls = tagged.map { it.url }.toSet()
                        channels = (channels.filterNot { it.url in urls } + tagged).distinctBy { it.url }
                        prefs.edit().putString(prefKey, url).apply()
                        selectedGroup = "All"; renderHome()
                        toast("Server connected: " + tagged.size + " items")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Server connection failed: " + (e.message ?: "network error")) }
            }
        }
    }

    private fun playlistCacheFile(url: String) = java.io.File(filesDir, "playlist_" + url.hashCode() + ".m3u")

    private fun readPlaylistUrls(): List<String> {
        return try {
            val json = org.json.JSONArray(prefs.getString("playlist_urls", "[]"))
            (0 until json.length()).map { json.optString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    private fun permanentPlaylistUrls(): Set<String> {
        return prefs.getStringSet("permanent_playlist_urls", emptySet())?.toSet().orEmpty() + permanentPlaylistUrl
    }

    private fun savePlaylistUrl(url: String, permanent: Boolean = false) {
        if (url != permanentPlaylistUrl && url !in playlistUrls) playlistUrls.add(url)
        if (permanent) {
            val locked = permanentPlaylistUrls().toMutableSet().apply { add(url) }
            prefs.edit().putStringSet("permanent_playlist_urls", locked).apply()
        }
        // Keep the built-in owner playlist at position 1 in the saved list.
        playlistUrls.remove(permanentPlaylistUrl)
        playlistUrls.add(0, permanentPlaylistUrl)
        val json = org.json.JSONArray()
        playlistUrls.forEach { json.put(it) }
        prefs.edit().putString("playlist_urls", json.toString()).putString("playlist_url", permanentPlaylistUrl).apply()
    }

    private fun removeSavedPlaylist(url: String) {
        if (url in permanentPlaylistUrls()) {
            toast("এই Playlist স্থায়ীভাবে LOCK করা — Delete করা যাবে না")
            return
        }
        playlistUrls.removeAll { it == url }
        val json = org.json.JSONArray()
        playlistUrls.forEach { json.put(it) }
        prefs.edit().putString("playlist_urls", json.toString()).apply()
        if (prefs.getString("playlist_url", permanentPlaylistUrl) == url) {
            prefs.edit().putString("playlist_url", permanentPlaylistUrl).apply()
            loadFromUrl(permanentPlaylistUrl)
        }
        toast("Playlist removed")
    }

    private fun showPlaylistDialog() {
        val locked = permanentPlaylistUrls()
        val items = playlistUrls.toList()
        val labels = items.mapIndexed { index, url ->
            when {
                url == permanentPlaylistUrl -> "🔒 SANY IPTV — BUILT-IN / PERMANENT"
                url in locked -> "🔒 PERMANENT — " + url
                else -> (index + 1).toString() + ". " + url + "  •  REMOVABLE"
            }
        }.toTypedArray()
        val builder = AlertDialog.Builder(this).setTitle("Playlist manager")
        if (labels.isNotEmpty()) builder.setItems(labels) { _, which ->
            val selected = items[which]
            val actions = if (selected in locked) arrayOf("LOAD PLAYLIST", "CANCEL")
                else arrayOf("LOAD PLAYLIST", "DELETE PLAYLIST", "CANCEL")
            AlertDialog.Builder(this).setTitle(if (selected in locked) "🔒 Permanent playlist" else "Playlist options")
                .setItems(actions) { _, action ->
                    when (actions[action]) {
                        "LOAD PLAYLIST" -> loadFromUrl(selected)
                        "DELETE PLAYLIST" -> AlertDialog.Builder(this)
                            .setTitle("Delete playlist?")
                            .setMessage("শুধু এই saved playlist মুছবে। মূল M3U server মুছবে না।")
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("DELETE") { _, _ -> removeSavedPlaylist(selected); showPlaylistDialog() }
                            .show()
                    }
                }.show()
        } else builder.setMessage("এখনো কোনো playlist যোগ করা হয়নি।")
        builder.setNeutralButton("＋ ADD PLAYLIST") { _, _ -> showAddPlaylistDialog() }
            .setPositiveButton("IMPORT FILE") { _, _ -> openFilePicker() }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showAddPlaylistDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/playlist.m3u"
            setTextColor(white); setHintTextColor(muted); setSingleLine(true)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        }
        AlertDialog.Builder(this).setTitle("Add playlist URL").setView(wrap)
            .setPositiveButton("ADD & LOAD") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) loadFromUrl(url)
                else toast("সঠিক HTTP/HTTPS playlist URL দিন")
            }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun showQuickMenu() {
        AlertDialog.Builder(this).setItems(arrayOf("Playlist manager / Switch playlist", "Add playlist URL", "Import M3U file", "Reload active playlist", "Settings")) { _, which ->
            when (which) {
                0 -> showPlaylistDialog()
                1 -> showAddPlaylistDialog()
                2 -> openFilePicker()
                3 -> loadFromUrl(permanentPlaylistUrl)
                4 -> { section = "Settings"; renderHome() }
            }
        }.show()
    }

    private fun openFilePicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, pickFile)
    }

    // Try multiple public delivery routes; use the last successful local copy offline.
    private fun fetchPlaylistText(url: String): String {
        val candidates = if (url == permanentPlaylistUrl) listOf(
            "https://cdn.jsdelivr.net/gh/Sany100/Sany-iptv@main/playlist.m3u",
            permanentPlaylistUrl,
            "https://github.com/Sany100/Sany-iptv/raw/refs/heads/main/playlist.m3u"
        ) else listOf(url)
        var lastError: Exception? = null
        for (candidate in candidates.distinct()) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(candidate).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 12000
                connection.instanceFollowRedirects = true
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SANY-IPTV/1.0")
                connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl, audio/x-mpegurl, text/plain, */*")
                connection.setRequestProperty("Connection", "keep-alive")
                val code = connection.responseCode
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (body.lineSequence().none { it.trim().startsWith("#EXTM3U", true) }) throw java.io.IOException("Invalid M3U response")
                return body
            } catch (e: Exception) {
                lastError = e
            } finally {
                connection?.disconnect()
            }
        }
        throw lastError ?: java.io.IOException("All playlist sources failed")
    }

    private fun loadFromUrl(url: String, showStatus: Boolean = true) {
        if (url.isBlank()) { showPlaylistDialog(); return }
        if (showStatus) toast("Loading playlist…")
        thread(name = "SANY-Playlist-Load") {
            var usedCache = false
            try {
                val content = try {
                    fetchPlaylistText(url)
                } catch (networkError: Exception) {
                    val cached = playlistCacheFile(url)
                    if (!cached.exists()) throw networkError
                    usedCache = true
                    cached.readText()
                }
                val parsed = M3uParser.parse(content)
                val detectedEpgUrl = extractEpgUrl(content)
                if (parsed.isNotEmpty() && !usedCache) {
                    try { playlistCacheFile(url).writeText(content) } catch (_: Exception) { }
                }
                runOnUiThread {
                    if (parsed.isEmpty()) {
                        toast("No playable entries found in this playlist")
                    } else {
                        val playlistChanged = parsed != channels
                        val needsLiveTvRender = playlistChanged || section != "Live TV" ||
                            selectedGroup != "Select category" || searchQuery.isNotEmpty()
                        channels = parsed
                        // Keep the last known stream-health results so verified channels
                        // remain visible immediately while this launch's refresh runs.
                        val currentUrls = parsed.mapTo(HashSet()) { it.url }
                        streamChecks.keys.retainAll(currentUrls)
                        savePlaylistUrl(url)
                        section = "Live TV"
                        selectedGroup = "Select category"; searchQuery = ""
                        prefs.edit().putString("ui_section", "Live TV")
                            .putString("ui_group", "Select category").putString("ui_search", "").apply()
                        if (!detectedEpgUrl.isNullOrBlank()) {
                            prefs.edit().putString("epg_url", detectedEpgUrl).apply()
                            if (prefs.getBoolean("auto_add_epg", true)) updateEpgData(detectedEpgUrl, false)
                        }
                        if (needsLiveTvRender) renderHome()
                        startPlaylistCheck(false)
                        toast(if (usedCache) "Offline mode: loaded ${channels.size} saved items"
                            else "Loaded ${channels.size} items — checking streams")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("Playlist unavailable: " + (e.message ?: "network error").take(90))
                }
            }
        }
    }

    private fun extractEpgUrl(m3uContent: String): String? {
        val header = m3uContent.lineSequence().firstOrNull { it.trimStart().startsWith("#EXTM3U", true) }
            ?: return null
        val match = Regex("""(?i)(?:url-tvg|x-tvg-url)\s*=\s*["']?([^"'\s,]+)""").find(header)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        }
    }

    private fun showEpgUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/epg.xml"
            setSingleLine(true)
            setText(prefs.getString("epg_url", ""))
            setTextColor(white); setHintTextColor(muted)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        }
        AlertDialog.Builder(this).setTitle("EPG source URL").setView(wrap)
            .setPositiveButton("SAVE & UPDATE") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    prefs.edit().putString("epg_url", url).apply()
                    updateEpgData(url)
                } else toast("সঠিক HTTP/HTTPS EPG URL দিন")
            }
            .setNeutralButton("REMOVE") { _, _ ->
                prefs.edit().remove("epg_url").apply()
                try { java.io.File(filesDir, "epg_data.xml").delete() } catch (_: Exception) {}
                toast("EPG source removed")
            }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun showBuiltInEpgSources() {
        val details = EpgRepository.defaultSources.joinToString("\n\n") { it } +
            "\n\nAutomatic update: once every 24 hours when internet is available."
        AlertDialog.Builder(this)
            .setTitle("SANY IPTV — EPG sources")
            .setMessage(details)
            .setPositiveButton("UPDATE NOW") { _, _ -> refreshEpgSources(true) }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun refreshEpgSources(showStatus: Boolean) {
        if (showStatus) toast("তিনটি EPG source আপডেট হচ্ছে…")
        thread(name = "SANY-EPG-Update") {
            try {
                val result = EpgRepository.update(this)
                runOnUiThread { if (!isFinishing) toast("$result successfully") }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) toast("EPG update failed: " + (e.message ?: "network error").take(100))
                }
            }
        }
    }

    private fun updateEpgData(url: String, showStatus: Boolean = true) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            if (showStatus) toast("সঠিক EPG URL সেট করুন")
            return
        }
        if (showStatus) toast("Updating EPG data…")
        thread {
            try {
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "SANY-IPTV-Android")
                }
                val xml = connection.getInputStream().bufferedReader().use { it.readText() }
                if (!xml.contains("<tv", true) || !xml.contains("</tv>", true)) {
                    throw IllegalStateException("এটি valid XMLTV EPG data নয়")
                }
                java.io.File(filesDir, "epg_data.xml").writeText(xml)
                prefs.edit().putString("epg_last_updated", System.currentTimeMillis().toString()).apply()
                runOnUiThread { toast("EPG data updated successfully") }
            } catch (e: Exception) {
                runOnUiThread { toast("EPG update failed: " + (e.message ?: "network error")) }
            }
        }
    }

    private fun checkPlaylistStreams() {
        if (channels.isEmpty()) { toast("আগে একটি playlist যোগ করুন"); return }
        section = "Checker"
        renderHome()
        startPlaylistCheck(true)
    }

    // Coalesce many network responses into one UI refresh. Rebuilding every channel
    // row for every probe result was causing frame drops and apparent freezes.
    private fun scheduleCheckerUiRefresh() {
        if (!checkerUiRefreshQueued.compareAndSet(false, true)) return
        checkerUiHandler.postDelayed({
            checkerUiRefreshQueued.set(false)
            if (!isFinishing) {
                updateLiveTvProgress()
                if (::contentArea.isInitialized && section == "Checker") updateCheckerUi()
                if (::contentArea.isInitialized && (section == "Live TV" || section == "Live Sports")) {
                    val checkedCount = checkerProgressCount.get()
                    val publishLimit = when {
                        !checkerRunning -> Int.MAX_VALUE
                        channels.isNotEmpty() && checkedCount * 2 >= channels.size -> 60
                        else -> 30
                    }
                    val readyCount = channels.count { streamChecks[it.url]?.first == "Working" }.coerceAtMost(publishLimit)
                    if (readyCount > liveTvPublishedCount || (!checkerRunning && liveTvPublishedCount != readyCount)) {
                        liveTvPublishedCount = readyCount
                        renderListOnly()
                    }
                }
            }
        }, 500L)
    }

    private fun updateCheckerUi() {
        checkerSummaryView?.text = checkerSummaryText()
        checkerListAdapter?.notifyDataSetChanged()
        checkerRunButton?.text = if (checkerRunning) (if (checkerPaused) "▶  RESUME CHECKING" else "Ⅱ  PAUSE CHECKING") else "↻  CHECK ALL CHANNELS AGAIN"
    }

    private fun liveTvProgressLabel(): String {
        if (channels.isEmpty()) return "Live TV"
        if (!checkerRunning && streamChecks.isEmpty()) return "Live TV"
        val denominator = if (checkerRunning) checkerTargetCount.coerceAtLeast(1) else channels.size.coerceAtLeast(1)
        val percent = ((if (checkerRunning) checkerProgressCount.get() else streamChecks.size) * 100 / denominator).coerceIn(0, 100)
        return if (checkerRunning) "Live TV $percent%" else "Live TV"
    }

    private fun updateLiveTvProgress() {
        liveTvNavLabel?.text = liveTvProgressLabel()
    }

    private fun saveStreamCheckResults() {
        try {
            val json = JSONObject()
            streamChecks.forEach { (url, result) ->
                json.put(url, JSONArray().put(result.first).put(result.second))
            }
            prefs.edit().putString("stream_check_results", json.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun restoreStreamCheckResults() {
        try {
            val json = JSONObject(prefs.getString("stream_check_results", "{}") ?: "{}")
            channels.forEach { channel ->
                val value = json.optJSONArray(channel.url) ?: return@forEach
                if (value.length() >= 2) streamChecks[channel.url] = value.optString(0) to value.optString(1)
            }
        } catch (_: Exception) { streamChecks.clear() }
    }

    private fun showCheckerSecretKeyDialog() {
        if (checkerCopyUnlocked) {
            renderCheckerSection()
            return
        }
        val keyInput = EditText(this).apply {
            hint = "Enter secret key"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Unlock Copy Options")
            .setMessage("Copy and export options are hidden. Enter the authorized secret key to show them.")
            .setView(keyInput)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("UNLOCK", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val enteredHash = try {
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(keyInput.text.toString().trim().toByteArray(Charsets.UTF_8))
                                .joinToString("") { byte -> "%02x".format(byte) }
                        } catch (_: Exception) { "" }
                        // Compare a digest rather than storing the key as readable source text.
                        if (enteredHash == "0978f052a4bb8742bcd2bb5a8f70330b4e8481d6167eddddbba65bc16b22e02e") {
                            checkerCopyUnlocked = true
                            dismiss()
                            renderCheckerSection()
                            toast("Copy options unlocked")
                        } else {
                            keyInput.error = "Incorrect secret key"
                        }
                    }
                }
                show()
            }
    }

    private fun copyCheckerResults(statusFilter: String? = null, fullReport: Boolean = false) {
        if (!checkerCopyUnlocked) {
            showCheckerSecretKeyDialog()
            return
        }
        val selected = if (statusFilter == null || statusFilter == "All") {
            channels.filter { fullReport || streamChecks.containsKey(it.url) }
        } else {
            channels.filter { streamChecks[it.url]?.first == statusFilter }
        }
        if (selected.isEmpty()) {
            toast("কপি করার মতো ফলাফল নেই")
            return
        }
        val working = streamChecks.values.count { it.first == "Working" }
        val offline = streamChecks.values.count { it.first == "Offline" }
        val restricted = streamChecks.values.count { it.first == "Restricted" }
        val errors = streamChecks.values.count { it.first == "Error" }
        val report = buildString {
            if (fullReport) {
                appendLine("SANY IPTV CHECKER REPORT")
                appendLine("Total channels: ${channels.size}")
                appendLine("Working: $working | Offline: $offline | Restricted: $restricted | Error: $errors")
                appendLine("Checked: ${streamChecks.size}")
                appendLine("--------------------------------")
            } else {
                appendLine("SANY IPTV - ${statusFilter ?: "All"} CHANNELS")
                appendLine("Count: ${selected.size}")
                appendLine("--------------------------------")
            }
            selected.forEachIndexed { index, channel ->
                val result = streamChecks[channel.url]
                val status = result?.first ?: "Not checked"
                appendLine("${index + 1}. ${channel.name}")
                appendLine("Status: $status")
                appendLine("Info: ${result?.second ?: channel.group}")
                appendLine("URL: ${channel.url}")
                appendLine()
            }
        }.trim()
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SANY IPTV Checker", report))
        toast(if (fullReport) "সম্পূর্ণ Checker Report কপি হয়েছে" else "${selected.size}টি channel কপি হয়েছে")
    }

    private fun checkerSummaryText(): String {
        val working = streamChecks.values.count { it.first == "Working" }
        val offline = streamChecks.values.count { it.first == "Offline" }
        val restricted = streamChecks.values.count { it.first == "Restricted" }
        val errors = streamChecks.values.count { it.first == "Error" }
        val progress = if (checkerRunning) " • ${checkerProgressCount.get()}/${checkerTargetCount}" else ""
        val state = if (checkerPaused) "PAUSED • " else if (checkerRunning) "CHECKING • " else ""
        return "${state}Total ${channels.size}${progress} • Working ${working} • Offline ${offline} • Restricted ${restricted} • Error ${errors}"
    }

    private fun startPlaylistCheck(showToast: Boolean, onlyOffline: Boolean = false) {
        if (channels.isEmpty() || checkerRunning) return
        val snapshot = if (onlyOffline) channels.filter { streamChecks[it.url]?.first == "Offline" }
            else channels.toList()
        if (snapshot.isEmpty()) {
            toast(if (onlyOffline) "আবার পরীক্ষা করার মতো Offline channel নেই" else "চ্যানেল পাওয়া যায়নি")
            return
        }
        checkerRunning = true
        checkerPaused = false
        checkerTargetCount = snapshot.size
        checkerProgressCount.set(0)
        liveTvPublishedCount = 0
        updateLiveTvProgress()
        // Keep the last known health snapshot visible in Checker while refreshing.
        checkerDisplayedLimit = 250
        if (showToast) toast("চ্যানেল পরীক্ষা শুরু হয়েছে")
        if (::contentArea.isInitialized && section == "Checker") renderCheckerSection()
        if (::contentArea.isInitialized && (section == "Live TV" || section == "Live Sports")) renderListOnly()

        // A fixed number of workers pull the next channel from an atomic index.
        // This avoids creating thousands of queued Runnables for huge playlists.
        val nextIndex = java.util.concurrent.atomic.AtomicInteger(0)
        // Use higher parallelism on Android 10+ while keeping older devices
        // conservative to avoid memory pressure and excessive socket contention.
        // Android 10+ uses 48 workers; Android 6-9 uses 8 workers.
        val maxWorkers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 48 else 8
        val workerCount = minOf(maxWorkers, snapshot.size)
        val workers = (0 until workerCount).map { worker ->
            thread(name = "SANY-Stream-Check-$worker") {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= snapshot.size || Thread.currentThread().isInterrupted) break
                    while (checkerPaused && !Thread.currentThread().isInterrupted) {
                        try { Thread.sleep(150L) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    if (Thread.currentThread().isInterrupted) break
                    val channel = snapshot[index]
                    streamChecks[channel.url] = probeStream(channel.url)
                    checkerProgressCount.incrementAndGet()
                    scheduleCheckerUiRefresh()
                }
            }
        }
        thread(name = "SANY-Playlist-Check-Finish") {
            workers.forEach { worker ->
                try { worker.join() } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
            }
            runOnUiThread {
                checkerRunning = false
                checkerPaused = false
                checkerProgressCount.set(snapshot.size)
                saveStreamCheckResults()
                updateLiveTvProgress()
                checkerUiRefreshQueued.set(false)
                checkerUiHandler.removeCallbacksAndMessages(null)
                scheduleOfflineChannelRecheck()

                // Once checking finishes, automatically switch to Live TV and
                // refresh the complete screen/navigation so every checked channel
                // is immediately visible without a manual tab change.
                section = "Live TV"
                // Checking completion returns to the category directory; never auto-enter All.
                selectedGroup = "Select category"
                searchQuery = ""
                prefs.edit()
                    .putString("ui_section", "Live TV")
                    .putString("ui_group", "Select category")
                    .putString("ui_search", "")
                    .apply()
                if (::contentArea.isInitialized) renderHome()
                if (showToast) toast("Check complete: " + streamChecks.values.count { it.first == "Working" } + " / " + snapshot.size + " responding")
            }
        }
    }

    private fun renderCheckerSection() {
        contentArea.removeAllViews()
        checkerSummaryView = null
        checkerRunButton = null
        checkerListAdapter = null

        // Keep controls fixed and let ListView virtualize rows. Only visible rows
        // are created; recycled row views prevent huge playlists from flooding RAM.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
            setBackgroundColor(bg)
        }
        box.addView(text("PLAYLIST HEALTH", 12, accent, true))
        checkerSummaryView = text(checkerSummaryText(), 13, muted).apply {
            setPadding(0, dp(7), 0, dp(12))
        }
        box.addView(checkerSummaryView)
        checkerRunButton = actionButton(
            if (checkerRunning) (if (checkerPaused) "▶  RESUME CHECKING" else "Ⅱ  PAUSE CHECKING")
            else "↻  CHECK ALL CHANNELS AGAIN"
        ) {
            if (checkerRunning) {
                checkerPaused = !checkerPaused
                updateCheckerUi()
            } else startPlaylistCheck(true)
        }
        box.addView(checkerRunButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            bottomMargin = dp(6)
        })
        if (checkerCopyUnlocked) {
            box.addView(actionButton("▤  COPY REPORT") {
                copyCheckerResults(fullReport = true)
            }, LinearLayout.LayoutParams(-1, dp(40)).apply {
                bottomMargin = dp(8)
            })
        } else {
            box.addView(actionButton("🔒  UNLOCK COPY OPTIONS") {
                showCheckerSecretKeyDialog()
            }, LinearLayout.LayoutParams(-1, dp(40)).apply {
                bottomMargin = dp(8)
            })
        }

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            setBackgroundColor(bg)
            addView(filters)
        }
        listOf("All", "Working", "Offline", "Restricted", "Error", "Checking").forEach { filter ->
            val count = when (filter) {
                "All" -> channels.size
                "Checking" -> if (checkerRunning) channels.size - streamChecks.size else 0
                else -> streamChecks.values.count { it.first == filter }
            }
            val filterGroup = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glass(24)
            }
            filterGroup.addView(text(filter + " (" + count + ")", 11, if (checkerFilter == filter) bg else muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(10), 0)
                background = if (checkerFilter == filter) rounded(accent, 24) else null
                setOnClickListener {
                    checkerFilter = filter
                    renderCheckerSection()
                }
            }, LinearLayout.LayoutParams(-2, dp(36)))
            if (filter != "Checking" && checkerCopyUnlocked) {
                filterGroup.addView(text("⧉", 16, accent, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), 0, dp(10), 0)
                    contentDescription = "Copy $filter channels"
                    setOnClickListener { copyCheckerResults(statusFilter = filter) }
                }, LinearLayout.LayoutParams(-2, dp(36)))
            }
            filters.addView(filterGroup, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(6) })
        }
        box.addView(filterScroll, LinearLayout.LayoutParams(-1, dp(44)))

        val list = ListView(this).apply {
            divider = null
            dividerHeight = dp(5)
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            // Checker is diagnostic only: selecting a result never starts playback.
            isClickable = false
            isLongClickable = false
        }
        checkerListAdapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = checkerSelectedChannels().size
            override fun getItem(position: Int): Any = checkerSelectedChannels()[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun hasStableIds(): Boolean = false
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row: LinearLayout
                val nameView: TextView
                val statusView: TextView
                val detailView: TextView
                if (convertView is LinearLayout && convertView.tag is Array<*>) {
                    row = convertView
                    @Suppress("UNCHECKED_CAST")
                    val views = convertView.tag as Array<TextView>
                    nameView = views[0]
                    statusView = views[1]
                    detailView = views[2]
                } else {
                    row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = glass(12)
                    }
                    val line = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                    nameView = text("", 13, white, true)
                    statusView = text("", 10, muted, true)
                    line.addView(nameView, LinearLayout.LayoutParams(0, -2, 1f))
                    line.addView(statusView)
                    row.addView(line)
                    detailView = text("", 10, muted).apply { setPadding(0, dp(4), 0, 0) }
                    row.addView(detailView)
                    row.tag = arrayOf(nameView, statusView, detailView)
                }
                val channel = checkerSelectedChannels().getOrNull(position)
                    ?: return row
                val result = streamChecks[channel.url]
                val label = result?.first ?: if (checkerRunning) "Checking…" else "Not checked"
                nameView.text = channel.name
                statusView.text = label.uppercase()
                statusView.setTextColor(when (label) {
                    "Working" -> Color.rgb(35, 190, 120)
                    "Offline" -> Color.rgb(245, 85, 85)
                    "Restricted" -> Color.rgb(255, 170, 55)
                    else -> muted
                })
                detailView.text = result?.second ?: channel.group
                row.alpha = 1f
                return row
            }
        }
        list.adapter = checkerListAdapter
        box.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        contentArea.addView(box, LinearLayout.LayoutParams(-1, -1))
        updateCheckerUi()
    }

    private fun checkerSelectedChannels(): List<Channel> = channels.filter { channel ->
        val status = streamChecks[channel.url]?.first
        when (checkerFilter) {
            "All" -> true
            "Checking" -> checkerRunning && status == null
            else -> status == checkerFilter
        }
    }

    private val offlineRecheckRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || channels.isEmpty() || checkerRunning) return
            val retry = channels.filter { streamChecks[it.url]?.first != "Working" }
            if (retry.isNotEmpty()) {
                thread(name = "SANY-Offline-Recheck") {
                    val next = java.util.concurrent.atomic.AtomicInteger(0)
                    val retryWorkers = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) 2 else 4
                    val workers = (0 until minOf(retryWorkers, retry.size)).map { worker ->
                        thread(name = "SANY-Offline-Recheck-$worker") {
                            while (true) {
                                val index = next.getAndIncrement()
                                if (index >= retry.size) break
                                val channel = retry[index]
                                streamChecks[channel.url] = probeStream(channel.url)
                            }
                        }
                    }
                    workers.forEach { it.join() }
                    saveStreamCheckResults()
                    runOnUiThread {
                        if (!isFinishing && (section == "Live TV" || section == "Live Sports")) renderHome()
                        else updateLiveTvProgress()
                    }
                }
            }
            checkerUiHandler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    private fun scheduleOfflineChannelRecheck() {
        checkerUiHandler.removeCallbacks(offlineRecheckRunnable)
        checkerUiHandler.postDelayed(offlineRecheckRunnable, 5 * 60 * 1000L)
    }

    private fun probeStream(streamUrl: String): Pair<String, String> {
        // FTP cannot be positively verified by this HTTP probe. Keep it out
        // of Live TV's working-only list rather than reporting an untested URL as working.
        if (streamUrl.startsWith("ftp://", true)) {
            return "Error" to "FTP cannot be verified by this checker"
        }
        if (!streamUrl.startsWith("http://", true) && !streamUrl.startsWith("https://", true)) {
            return "Error" to "Unsupported URL protocol"
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(streamUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 1600
            connection.readTimeout = 1900
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.defaultUseCaches = false
            connection.setRequestProperty("User-Agent", "SANY-IPTV-Android")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("Connection", "keep-alive")
            val code = connection.responseCode
            when {
                code in 200..299 || code == 206 -> "Working" to ("HTTP " + code)
                code == 401 || code == 403 -> "Restricted" to ("HTTP " + code)
                code == 404 || code == 410 -> "Offline" to ("HTTP " + code)
                else -> "Error" to ("HTTP " + code)
            }
        } catch (e: Exception) {
            val message = if (e is java.net.SocketTimeoutException) "Timeout"
                else if (e is java.net.UnknownHostException) "Host not found"
                else (e.message ?: "Connection failed").take(34)
            "Error" to message
        } finally {
            connection?.disconnect()
        }
    }

    private fun showChannelNumberDialog() {
        if (channels.isEmpty()) {
            toast("আগে একটি playlist যোগ করুন")
            return
        }
        val input = EditText(this).apply {
            hint = "Channel number (1-${channels.size})"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Jump to channel")
            .setMessage("চ্যানেলের নম্বর লিখে সরাসরি চালু করুন")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("PLAY") { _, _ ->
                val number = input.text.toString().toIntOrNull()
                val target = number?.let { channels.getOrNull(it - 1) }
                if (target == null) toast("সঠিক channel number দিন") else play(target)
            }
            .show()
        input.requestFocus()
    }

    private fun play(
        channel: Channel,
        startPlayback: Boolean = true,
        ftpCredentials: Pair<String, String>? = null,
        subtitleUrl: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        streamMimeTypeHint: String? = null,
        subtitleTracks: List<SanySubtitleTrack> = emptyList(),
        preferredAudioLanguages: List<String>? = null,
        qualityHint: String? = null,
        externalAudioTracks: List<SanyAudioTrack> = emptyList()
    ) {
        if (section != "Live TV" && section != "Live Sports" && section != "Movies" && section != "Series") return
        if (channel.url.isBlank()) {
            toast("এই চ্যানেলের stream link নেই")
            return
        }
        val streamUrl = channel.url.trim()
        activeProviderAudioLanguages = preferredAudioLanguages
            ?.map(String::trim)
            ?.filter { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]+)*")) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        activeProviderQualityHint = qualityHint?.trim()?.takeIf { it.isNotBlank() }
        val isFtpStream = streamUrl.startsWith("ftp://", true)
        if (!isFtpStream && !streamUrl.startsWith("http://", true) && !streamUrl.startsWith("https://", true)) {
            toast("Unsupported stream type. HTTP, HTTPS and FTP links are supported.")
            return
        }

        // Accept credentials embedded in an FTP URL (ftp://user:pass@host/path).
        // If the playlist omits them, ask the user at playback time; never store them.
        var resolvedFtpCredentials = ftpCredentials
        if (isFtpStream && resolvedFtpCredentials == null) {
            val userInfo = try { java.net.URI(streamUrl).rawUserInfo } catch (_: Exception) { null }
            if (!userInfo.isNullOrBlank()) {
                val decoded = java.net.URLDecoder.decode(userInfo, "UTF-8")
                val separator = decoded.indexOf(':')
                if (separator > 0) {
                    resolvedFtpCredentials = decoded.substring(0, separator) to decoded.substring(separator + 1)
                }
            }
            if (resolvedFtpCredentials == null) {
                val usernameInput = EditText(this).apply {
                    hint = "FTP username"
                    setSingleLine(true)
                    setText("anonymous")
                }
                val passwordInput = EditText(this).apply {
                    hint = "FTP password"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setSingleLine(true)
                }
                val form = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(8), dp(20), 0)
                    addView(usernameInput)
                    addView(passwordInput)
                }
                AlertDialog.Builder(this)
                    .setTitle("FTP server login")
                    .setMessage("এই FTP stream চালাতে সার্ভারের username ও password দিন।")
                    .setView(form)
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("CONNECT") { _, _ ->
                        val username = usernameInput.text.toString().trim()
                        val password = passwordInput.text.toString()
                        if (username.isBlank()) {
                            toast("FTP username দিন")
                        } else {
                            play(channel, startPlayback, username to password)
                        }
                    }
                    .show()
                return
            }
        }

        // Preserve the current viewing mode when switching channels.
        // First playback opens fullscreen; a switch from portrait stays portrait.
        val wasAlreadyPlaying = playerView != null
        if (!wasAlreadyPlaying) {
            playbackReturnSection = section
            playbackReturnGroup = selectedGroup
            playbackReturnSearch = searchQuery
            playbackReturnScrollY = (listContainer.parent as? ScrollView)?.scrollY ?: 0
            if (section == "Live TV") {
                liveTvSelectedGroup = selectedGroup
                liveTvSearchQuery = searchQuery
            }
        }
        val keepFullscreenMode = if (wasAlreadyPlaying) isPlayerFullscreen else true
        val oldChannel = currentChannel
        if (oldChannel != null && oldChannel.url != channel.url) previousChannel = oldChannel
        closePlayer(keepOrientation = true)
        currentChannel = channel
        if (section == "Movies") {
            prefs.edit()
                .putString("movies_now_playing_title", channel.name.trim())
                .putString("sany_last_played_section", "Movies")
                .putString("sany_last_played_provider", channel.group)
                .putString("sany_last_played_title", channel.name.trim())
                .putString("sany_last_played_item_url", channel.url.trim())
                .apply()
        } else if (section == "Live TV") {
            prefs.edit()
                .putString("sany_last_played_section", "Live TV")
                .putString("sany_last_played_provider", channel.group)
                .putString("sany_last_played_title", channel.name.trim())
                .putString("sany_last_played_item_url", channel.url.trim())
                .apply()
        }
        // Move the single active-channel highlight immediately when playback selection changes.
        if (::listContainer.isInitialized && (section == "Live TV" || section == "Live Sports")) {
            val scroll = listContainer.parent as? ScrollView
            val previousScrollY = scroll?.scrollY ?: 0
            renderChannelRows(listContainer)
            scroll?.post { scroll.scrollTo(0, previousScrollY) }
        }

        val mediaHeaders = linkedMapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        ).apply {
            putAll(requestHeaders)
        }
        val highSpeedStreaming = prefs.getBoolean("high_speed_streaming", false)
        val wifiSmartStreaming = prefs.getBoolean("wifi_smart_streaming", true)
        val wifiBandCode = if (wifiSmartStreaming) currentWifiBandCode() else 0
        val wifiOn24Ghz = wifiBandCode == 2
        val wifiOnHighBand = wifiBandCode == 5
        val httpFactory = OkHttpDataSource.Factory(
            SanyNetworkBypasses.client(
                this,
                connectTimeoutMs = if (highSpeedStreaming) 10_000 else 5_000,
                readTimeoutMs = if (highSpeedStreaming) 30_000 else 15_000
            )
        )
            // Keep standard IPTV headers, and add provider-specific headers only
            // for AniLight playback (including HLS segments and subtitle requests).
            .setUserAgent(
                requestHeaders["User-Agent"]
                    ?: "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .setDefaultRequestProperties(mediaHeaders)

        val subtitleRequestHeaders = buildMap<String, Map<String, String>> {
            if (!subtitleUrl.isNullOrBlank() && requestHeaders.isNotEmpty()) {
                put(subtitleUrl, requestHeaders)
            }
            subtitleTracks.forEach { subtitle ->
                if (subtitle.headers.isNotEmpty()) put(subtitle.url, subtitle.headers)
            }
        }
        val streamDataSourceFactory: DataSource.Factory = if (isFtpStream) {
            FtpDataSource.Factory(
                resolvedFtpCredentials?.first ?: "anonymous",
                resolvedFtpCredentials?.second ?: ""
            )
        } else ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            // Subtitle files can be hosted on a different CDN from the video and may
            // require their own Referer/Cookie headers. Apply those only to the exact
            // subtitle URI, leaving the provider's shared headers on all other requests.
            val specificHeaders = subtitleRequestHeaders[dataSpec.uri.toString()]
            if (specificHeaders.isNullOrEmpty()) dataSpec
            else dataSpec.buildUpon().setHttpRequestHeaders(specificHeaders).build()
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(streamDataSourceFactory)

        // Use Media3's platform-default decoder ordering for every section.
        // A custom codec-priority selector can force a broken software/hardware decoder
        // to the front of the list and crash playback across Live TV, Movies and Anime.
        // Keep decoder fallback enabled so Media3 can try another compatible decoder.
        val rendererFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
        val p = player ?: ExoPlayer.Builder(
            this,
            rendererFactory
        ).setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(DefaultTrackSelector(this).also { selector ->
                trackSelector = selector
                if (section == "Series" || section == "Movies") {
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setPreferredAudioLanguages("bn", "ben", "hi", "hin", "en", "eng")
                            // Audio preference: Bengali, then Hindi, then English. Subtitles remain off by default.
                            .setMaxVideoSize(1920, 1080)
                            .setMaxVideoBitrate(if (section == "Movies") 12_000_000 else Int.MAX_VALUE)
                    )
                }
                applyVideoQuality(selector)
            })
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        if (section == "Movies") {
                            when {
                                wifiOn24Ghz -> 6000
                                wifiOnHighBand -> 3500
                                highSpeedStreaming -> 5000
                                else -> 4000
                            }
                        } else if (section == "Live TV") {
                            1000
                        } else when {
                            wifiOn24Ghz -> 4500
                            wifiOnHighBand -> 2500
                            highSpeedStreaming -> 3000
                            else -> when (prefs.getString("network_buffer_size", defaultNetworkBufferMode)) {
                                "low" -> 1500
                                "high" -> 3500
                                else -> 2500
                            }
                        },
                        if (section == "Movies") {
                            when {
                                wifiOn24Ghz -> 45000
                                wifiOnHighBand -> 30000
                                highSpeedStreaming -> 45000
                                else -> 30000
                            }
                        } else if (section == "Live TV") {
                            when {
                                wifiOn24Ghz -> 30000
                                wifiOnHighBand -> 18000
                                highSpeedStreaming -> 24000
                                else -> 20000
                            }
                        } else when {
                            wifiOn24Ghz -> 36000
                            wifiOnHighBand -> 24000
                            highSpeedStreaming -> 24000
                            else -> when (prefs.getString("network_buffer_size", defaultNetworkBufferMode)) {
                                "low" -> 12000
                                "high" -> 30000
                                else -> 20000
                            }
                        },
                        if (section == "Movies") {
                            if (wifiOn24Ghz) 1800 else if (wifiOnHighBand) 1000 else 1500
                        } else if (section == "Live TV") 500 else when {
                            wifiOn24Ghz -> 1200
                            wifiOnHighBand -> 800
                            highSpeedStreaming -> 1200
                            else -> when (prefs.getString("network_buffer_size", defaultNetworkBufferMode)) {
                                "low" -> 750
                                "high" -> 1500
                                else -> 1000
                            }
                        },
                        // Media3 requires minBufferMs >= bufferForPlaybackAfterRebufferMs.
                        // Keep the rebuffer threshold below the selected minimum for every mode.
                        if (section == "Movies") {
                            if (wifiOn24Ghz) 3000 else if (wifiOnHighBand) 2000 else 3000
                        } else if (section == "Live TV") 750 else when {
                            wifiOn24Ghz -> 2500
                            wifiOnHighBand -> 1500
                            highSpeedStreaming -> 2500
                            else -> when (prefs.getString("network_buffer_size", defaultNetworkBufferMode)) {
                                "low" -> 1000
                                "high" -> 3000
                                else -> 2000
                            }
                        }
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build().also { created ->
                created.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e("SANY-PLAYER", "Media3 playback failure: ${error.errorCodeName}", error)
                        var cause: Throwable? = error
                        var httpStatus: Int? = null
                        while (cause != null) {
                            if (cause is HttpDataSource.InvalidResponseCodeException) {
                                httpStatus = cause.responseCode
                                break
                            }
                            cause = cause.cause
                        }
                        if (section == "Movies") {
                            android.util.Log.e(
                                "SANY-MOVIE",
                                "Movie playback error: code=${error.errorCodeName} httpStatus=${httpStatus ?: "unknown"}",
                                error
                            )
                        }
                        runOnUiThread {
                            if (section == "Series") showAnimePlaybackLoading("", false)
                            if ((section == "Series" || section == "Movies" || section == "Live Sports") &&
                                tryNextAnimeFallback()
                            ) {
                                return@runOnUiThread
                            }
                            if (httpStatus != null) {
                                toast("সার্ভার HTTP $httpStatus দিয়েছে — অন্য stream link চেষ্টা করুন")
                            } else if (currentChannel?.url?.startsWith("ftp://", true) == true) {
                                toast("FTP stream চালানো যায়নি। Username/password, server permission ও FTP port পরীক্ষা করুন।")
                            } else {
                                toast("প্লে হচ্ছে না: ${error.errorCodeName}")
                            }
                            val permanentHttpError = httpStatus == 401 || httpStatus == 403 || httpStatus == 404
                            val isCurrentFtp = currentChannel?.url?.startsWith("ftp://", true) == true
                            if (!isCurrentFtp && prefs.getBoolean("auto_retry_playback", true) && !permanentHttpError && playbackRetryCount < 2) {
                                playbackRetryCount++
                                toast("Auto retry ${playbackRetryCount}/2…")
                                playbackRetryHandler.postDelayed({
                                    if (player === this@MainActivity.player && playerView != null) {
                                        // A retry is a fresh load: keep audio/video held until
                                        // the next first frame, just like the initial request.
                                        animeAutoPlayWhenReady = prefs.getBoolean("auto_play", true)
                                        animeFirstFrameRendered = false
                                        animePlaybackLoadingRequested = true
                                        player?.playWhenReady = false
                                        updateAnimeFullscreenBuffering()
                                        player?.prepare()
                                    }
                                }, 1800L)
                            }
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        runOnUiThread { updateAnimeFullscreenBuffering() }
                        if (section == "Movies") {
                            val stateName = when (state) {
                                Player.STATE_IDLE -> "IDLE"
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> state.toString()
                            }
                            android.util.Log.i(
                                "SANY-MOVIE",
                                "Player state: state=$stateName elapsedMs=${if (moviePlaybackStartedAtMs > 0L) System.currentTimeMillis() - moviePlaybackStartedAtMs else -1L}"
                            )
                        }
                        if (state == Player.STATE_READY) {
                            // Do not release playback here: READY can arrive before the
                            // first frame reaches the surface. The first-frame callback below
                            // is the single release point for every category and screen mode.
                            updateAnimeFullscreenBuffering()
                            playbackRetryCount = 0
                            animeAutoNextInProgress = false
                            refreshPictureInPictureParams()
                        } else if (state == Player.STATE_ENDED && section == "Live Sports" &&
                            animeFallbackIndex >= 0
                        ) {
                            // A live provider returning an already-ended item is usually a dead source.
                            runOnUiThread { tryNextAnimeFallback() }
                        } else if (state == Player.STATE_ENDED && section == "Series" && !animeAutoNextInProgress) {
                            // Advance Anime Series automatically to the next numeric episode.
                            animeAutoNextInProgress = true
                            runOnUiThread {
                                aniLightNativeScreen?.playNextEpisode()
                                    ?: run { animeAutoNextInProgress = false }
                            }
                        }
                    }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        refreshPictureInPictureParams()
                    }
                    override fun onIsLoadingChanged(isLoading: Boolean) {
                        runOnUiThread { updateAnimeFullscreenBuffering() }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Auto-entry depends on actual playback; keep PiP action
                        // labels synchronized with the player as well.
                        refreshPictureInPictureParams()
                    }
                    override fun onPlayWhenReadyChanged(
                        playWhenReady: Boolean,
                        reason: Int
                    ) {
                        // Refresh the Pause/Resume RemoteAction on every user-intent
                        // change, including while the stream is buffering.
                        refreshPictureInPictureParams()
                    }
                })
                created.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onRenderedFirstFrame(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long
                    ) {
                        if (section == "Live Sports") {
                            liveSportsFirstFrameWatchdog?.let(playbackRetryHandler::removeCallbacks)
                            liveSportsFirstFrameWatchdog = null
                            animeFallbackRequests = emptyList()
                            animeFallbackIndex = -1
                        }
                        if (section == "Movies") {
                            android.util.Log.i(
                                "SANY-MOVIE",
                                "First video frame rendered: elapsedMs=${if (moviePlaybackStartedAtMs > 0L) System.currentTimeMillis() - moviePlaybackStartedAtMs else -1L}"
                            )
                            moviePlaybackStartedAtMs = 0L
                        }
                        runOnUiThread {
                            animeFirstFrameRendered = true
                            animePlaybackLoadingRequested = false
                            updateAnimeFullscreenBuffering()
                            if (animeAutoPlayWhenReady) {
                                animeAutoPlayWhenReady = false
                                player?.playWhenReady = true
                            }
                        }
                    }
                })
                player = created
            }

        val screen = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val isAnimeEpisode = section == "Series"
        // Movies and Anime use the same VOD player controls and overlay behavior.
        val isVodPlayer = isAnimeEpisode || section == "Movies"
        playerScreen = screen
        isPlayerFullscreen = keepFullscreenMode
        playerHeader = null

        val view = (layoutInflater.inflate(R.layout.player_view_texture, screen, false) as PlayerView).apply {
            useController = false
            player = p
            resizeMode = if (keepFullscreenMode) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            setShutterBackgroundColor(Color.BLACK)
            // Retain the last rendered frame during short surface/Activity transitions
            // so the app icon or an empty shutter does not flash before video resumes.
            setKeepContentOnPlayerReset(true)
            // A single in-video loading overlay is used for all categories.
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }
        playerView = view
        updateHomeHeaderVisibility()
        screen.addView(view, FrameLayout.LayoutParams(-1, dp(235), Gravity.TOP))
        run {
            animeFirstFrameRendered = false
            val loading = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                isClickable = false
            }
            val spinner = android.widget.ProgressBar(this).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            loading.addView(spinner, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
            // Add the indicator as a PlayerView child, not a screen-level
            // overlay. This keeps it directly over the video in both FIT and
            // fullscreen layouts.
            view.addView(loading, FrameLayout.LayoutParams(-1, -1))
            animeFullscreenBufferingOverlay = loading
            updateAnimeFullscreenBuffering()
        }
        animeEpisodePanel = if (isAnimeEpisode) aniLightNativeScreen?.createPlayerEpisodePanel() else null
        animeEpisodePanel?.let { episodePanel ->
            episodePanel.visibility = View.GONE
            screen.addView(episodePanel, FrameLayout.LayoutParams(-1, -1, Gravity.END))
        }
        if (isVodPlayer) {
            val animeTitle = TextView(this).apply {
                text = channel.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x99000000.toInt(), Color.TRANSPARENT)
                )
            }
            animePlayerTitleOverlay = animeTitle
            screen.addView(animeTitle, FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP))
        }
        // Register PiP parameters only after the real video view has a laid-out
        // screen rectangle. Refresh it whenever the player bounds change so the
        // system animates directly from video instead of briefly showing the app icon.
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (p.isPlaying) refreshPictureInPictureParams()
        }
        view.post { if (p.isPlaying) refreshPictureInPictureParams() }

        // The minimized player is a compact top strip. The actual active menu
        // remains visible directly underneath it; never replace menu content with
        // a channel directory while navigating between sections.
        val miniChannelScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(bg)
            visibility = View.GONE
        }
        val miniChannelRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        miniChannelScroll.addView(miniChannelRows)
        screen.addView(miniChannelScroll, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = dp(235)
        })
        playerChannelScroll = miniChannelScroll
        playerChannelRows = miniChannelRows

        // Custom control dock sits below the video, keeping the channel list unobstructed.
        val controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = glass(12)
            visibility = View.GONE
            alpha = 0f
            elevation = dp(5).toFloat()
        }
        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun playerAction(label: String, description: String, action: () -> Unit) =
            TextView(this).apply {
                text = label
                contentDescription = description
                textSize = if (label == "Ⅱ" || label == "▶") 25f else 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                isFocusable = true
                applyTvFocusStyle(this)
                setOnClickListener { action() }
            }
        // Channel identity is anchored at the far-left of the transport row.
        val channelIdentity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), 0, dp(4), 0)
        }
        val previousButton = playerAction("|◀", "Previous channel") { playAdjacentChannel(-1) }
        val playPauseButton = playerAction(if (p.isPlaying) "Ⅱ" else "▶", "Play or pause") {
            if (p.isPlaying) {
                // Save the current position before pausing.
                saveAnimeResumePosition(p, channel)
                p.pause()
                p.playWhenReady = false
            } else {
                if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                p.playWhenReady = true
                p.play()
            }
        }
        val nextButton = playerAction("▶|", "Next channel") { playAdjacentChannel(1) }
        // Anime episodes do not use channel identity or channel-skip controls.
        channelIdentity.visibility = if (isVodPlayer) View.GONE else View.VISIBLE
        previousButton.visibility = if (isVodPlayer) View.GONE else View.VISIBLE
        nextButton.visibility = if (isVodPlayer) View.GONE else View.VISIBLE
        if (!isVodPlayer) transportRow.addView(channelIdentity, LinearLayout.LayoutParams(dp(126), -1))
        listOf(previousButton, playPauseButton, nextButton).forEachIndexed { index, button ->
            button.textSize = if (index == 1) 21f else 19f
            button.typeface = if (index == 1) Typeface.create("sans-serif-light", Typeface.NORMAL) else Typeface.DEFAULT
            if (button.visibility == View.VISIBLE) {
                transportRow.addView(button, LinearLayout.LayoutParams(0, dp(27), 1f).apply {
                    if (index == 1 && !isVodPlayer) { leftMargin = dp(4); rightMargin = dp(4) }
                })
            }
        }
        controlsPanel.addView(transportRow, LinearLayout.LayoutParams(-1, dp(27)))
        if (isVodPlayer) {
            transportRow.visibility = View.GONE
            transportRow.removeView(playPauseButton)
            playPauseButton.textSize = 34f
            playPauseButton.background = SanyLiquidGlass.background(this@MainActivity, 50, false, accent)
            animeCenterPlayButton = playPauseButton
        }

        // Anime Series-only actions. Live TV and Movies keep their existing player UI.
        val isMoviePlayback = section == "Movies"
        val showVodActions = isVodPlayer || isMoviePlayback
        val animeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (showVodActions) View.VISIBLE else View.GONE
        }
        var animeControlsLocked = false
        fun animeAction(text: String, description: String, action: () -> Unit) =
            playerAction(text, description, action).apply {
                textSize = 12f
                minHeight = dp(30)
                setPadding(dp(4), 0, dp(4), 0)
            }
                val sourceAction = animeAction("☷  Source", "Show current video stream source") {
            val host = try { java.net.URI(channel.url).host ?: "Unknown host" } catch (_: Exception) { "Unknown host" }
            AlertDialog.Builder(this).setTitle("Video stream source")
                .setMessage("Current source: $host")
                .setPositiveButton("OK", null).show()
        }
        // Track control sits between Source and Full Screen for Anime playback.
        val trackAction = animeAction("≡  Track", "Select audio, subtitle or video tracks") {
            val choices = arrayOf("Audio tracks", "Subtitles", "Video quality")
            AlertDialog.Builder(this)
                .setTitle("Tracks")
                .setItems(choices) { _, which ->
                    when (which) {
                        0 -> showAnimeTrackSelection(C.TRACK_TYPE_AUDIO, "Audio tracks", false)
                        1 -> showAnimeTrackSelection(C.TRACK_TYPE_TEXT, "Subtitles", true)
                        2 -> showAnimeTrackSelection(C.TRACK_TYPE_VIDEO, "Video quality", false)
                    }
                }
                .setNegativeButton("CLOSE", null)
                .show()
        }
        var onAnimeFullscreenToggle: (() -> Unit)? = null
        val fullscreenAction = animeAction("⛶  Full Screen", "Switch between Full Screen and Fit Screen") {
            onAnimeFullscreenToggle?.invoke() ?: setPlayerFullscreen(!isPlayerFullscreen)
        }.also {
            animeFullscreenActionButton = it
            it.text = if (isPlayerFullscreen) "⤢  Fit Screen" else "⛶  Full Screen"
        }
        // Replace Next Episode with Track and Full Screen shortcuts in the Anime action strip.
        // Play/Pause remains available only as the large centre overlay.
        listOf(sourceAction, trackAction, fullscreenAction).forEach { action ->
            animeActions.addView(action, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        // Keep Source, Track and Full/Fit Screen on one baseline and move the
        // complete three-button row slightly lower beneath the video.
        controlsPanel.addView(animeActions, LinearLayout.LayoutParams(-1, dp(38)).apply {
            topMargin = dp(10)
            bottomMargin = dp(2)
        })

        val progressBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x668899AA)
            setPadding(dp(2), 0, dp(2), 0)
        }
        controlsPanel.addView(progressBar, LinearLayout.LayoutParams(-1, dp(15)))
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun timeLabel() = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val positionLabel = timeLabel()
        val durationLabel = timeLabel().apply { setTextColor(0xFFB8C4D4.toInt()) }
        infoRow.addView(positionLabel)
        infoRow.addView(durationLabel, LinearLayout.LayoutParams(0, -2, 0.7f).apply { leftMargin = dp(8) })

        // Compact now-playing identity: channel logo and name follow every channel switch.
        val nowPlayingLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                setColor(0x332D8CFF)
                cornerRadius = dp(5).toFloat()
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            contentDescription = "${channel.name} logo"
        }
        val nowPlayingName = TextView(this).apply {
            text = channel.name
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFBBD9FF.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        channelIdentity.addView(nowPlayingLogo, LinearLayout.LayoutParams(dp(27), dp(27)))
        channelIdentity.addView(nowPlayingName, LinearLayout.LayoutParams(0, -1, 1f).apply {
            leftMargin = dp(5)
        })
        channel.logo?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let { logoUrl ->
            loadCachedChannelLogo(nowPlayingLogo, null, logoUrl)
        }

        fun openPlayerSettings() {
            val isAnimePlayback = section == "Series" || section == "Movies"
            val options = if (isAnimePlayback)
                arrayOf("Playback speed", "Subtitle", "Video quality")
                else arrayOf("Playback speed", "Video quality")
            AlertDialog.Builder(this).setTitle("Player settings").setItems(options) { _, which ->
                if (which == 0) {
                    val speeds = arrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    val labels = speeds.map { if (it == 1f) "1.0× (Normal)" else "${it}×" }.toTypedArray()
                    val selected = speeds.indexOf(prefs.getFloat("playback_speed", 1.0f)).coerceAtLeast(0)
                    AlertDialog.Builder(this).setTitle("Playback speed").setSingleChoiceItems(labels, selected) { dialog, choice ->
                        prefs.edit().putFloat("playback_speed", speeds[choice]).apply()
                        p.setPlaybackSpeed(speeds[choice])
                        dialog.dismiss()
                    }.setNegativeButton("CLOSE", null).show()
                } else if (isAnimePlayback && which == 1) {
                    showAnimeTrackSelection(C.TRACK_TYPE_TEXT, "Subtitles", true)
                } else if (isAnimePlayback && which == 2) {
                    showAnimeTrackSelection(C.TRACK_TYPE_VIDEO, "Video quality", false)
                } else if (!isAnimePlayback && which == 1) {
                    val qualities = arrayOf("auto", "480", "720", "1080")
                    val labels = arrayOf("Auto", "480p", "720p", "1080p")
                    val selected = qualities.indexOf(prefs.getString("video_quality", "auto")).coerceAtLeast(0)
                    AlertDialog.Builder(this).setTitle("Video quality").setSingleChoiceItems(labels, selected) { dialog, choice ->
                        prefs.edit().putString("video_quality", qualities[choice]).apply()
                        applyVideoQuality()
                        dialog.dismiss()
                    }.setNegativeButton("CLOSE", null).show()
                }
            }.show()
        }
        val settingsButton = playerAction("⚙", "Player settings") { openPlayerSettings() }
        // Compact player actions are placed at the far-right of the lower
        // control strip, replacing the duplicate floating settings buttons.
        val topSettingsButton = playerAction("⚙", "Player settings") {
            openPlayerSettings()
        }.apply {
            textSize = 20f
            background = glass(9)
            minWidth = dp(38)
            minHeight = dp(34)
        }
        playerSettingsButton = topSettingsButton
        val minimizeButton = playerAction("⤡", "Minimize player") {
            setPlayerFullscreen(false)
        }.apply {
            textSize = 21f
            minWidth = dp(38)
            minHeight = dp(34)
            background = GradientDrawable().apply {
                setColor(0xB5102745.toInt())
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), Color.rgb(25, 135, 255))
            }
        }
        playerMinimizeButton = minimizeButton
        val fullscreenButton = playerAction("⛶", "Fullscreen / Minimize") {
            setPlayerFullscreen(!isPlayerFullscreen)
        }.apply {
            textSize = 19f
            minWidth = dp(38)
            minHeight = dp(34)
        }
        playerFullscreenButton = fullscreenButton
        playerControlsPanel = controlsPanel
        infoRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        infoRow.addView(topSettingsButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply {
            leftMargin = dp(5)
        })
        // Remove the extra minimize icon beside Settings from the Anime control dock.
        if (!isVodPlayer) {
            infoRow.addView(minimizeButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply {
                leftMargin = dp(5)
            })
        }
        // Fit Screen stays in its original bottom control row for Anime as well.
        // Anime gets its Full Screen control in the action strip above;
        // keep the original bottom-row button for non-Anime players only.
        if (!isVodPlayer) {
            infoRow.addView(fullscreenButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply {
                leftMargin = dp(5)
            })
        }
        if (isVodPlayer) {
            // VOD Pause/Resume is moved to the centre of the video.
            screen.addView(playPauseButton, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
        }
        controlsPanel.addView(infoRow, LinearLayout.LayoutParams(-1, dp(38)))
        if (isVodPlayer) {
            // Keep the same Anime layout order for Movies: timeline, action strip, utility controls.
            controlsPanel.removeView(animeActions)
            controlsPanel.removeView(progressBar)
            controlsPanel.removeView(infoRow)
            // Slimmer Anime control dock: compact timeline and action row.
            controlsPanel.addView(progressBar, LinearLayout.LayoutParams(-1, dp(10)))
            controlsPanel.addView(animeActions, LinearLayout.LayoutParams(-1, dp(28)))
            controlsPanel.addView(infoRow, LinearLayout.LayoutParams(-1, dp(34)))
        }
        screen.addView(controlsPanel, FrameLayout.LayoutParams(-1, if (isVodPlayer) dp(82) else dp(88), Gravity.TOP).apply {
            topMargin = dp(155)
            leftMargin = dp(8); rightMargin = dp(8)
        })

        val controlsHandler = Handler(Looper.getMainLooper())
        val animeControlsAutoHideDelay = 3000L
        // Anime Series uses one synchronized overlay group: centre Play/Pause,
        // top title, and the complete lower control dock always show/hide together.
        val hideControls = Runnable {
            // Keep the complete control group visible while paused so Resume is accessible.
            if (isVodPlayer && !p.isPlaying) return@Runnable
            val groupedOverlays = if (isVodPlayer)
                listOfNotNull(controlsPanel, playPauseButton, animePlayerTitleOverlay)
            else listOf(controlsPanel)
            groupedOverlays.forEach { overlay ->
                overlay.animate().cancel()
                overlay.animate().alpha(0f).setDuration(180).withEndAction {
                    overlay.visibility = View.GONE
                }.start()
            }
        }
        fun revealControls(autoHide: Boolean = true) {
            if (animeControlsLocked) {
                animeControlsLocked = false
                toast("Player controls unlocked")
            }
            controlsHandler.removeCallbacks(hideControls)
            val groupedOverlays = if (isVodPlayer)
                listOfNotNull(controlsPanel, playPauseButton, animePlayerTitleOverlay)
            else listOf(controlsPanel)
            groupedOverlays.forEach { overlay ->
                overlay.animate().cancel()
                overlay.visibility = View.VISIBLE
                overlay.alpha = 1f
            }
            if (isVodPlayer) {
                playPauseButton.text = if (p.isPlaying) "Ⅱ" else "▶"
            }
            if (autoHide && (isVodPlayer.not() || p.isPlaying)) {
                controlsHandler.postDelayed(
                    hideControls, if (isVodPlayer) animeControlsAutoHideDelay else 2500L
                )
            }
        }
        if (isVodPlayer) {
            // A pause keeps every overlay visible; resuming restarts the shared 3-second timer.
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    revealControls(autoHide = isPlaying)
                }
            })
            revealControls(autoHide = p.isPlaying)
        }
        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && p.duration > 0) {
                    val target = p.duration * progress / 1000L
                    positionLabel.text = formatPlayerTime(target)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                controlsHandler.removeCallbacks(hideControls)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = p.duration
                if (duration > 0) p.seekTo(duration * (seekBar?.progress ?: 0) / 1000L)
                revealControls()
            }
        })
        val progressUpdater = object : Runnable {
            override fun run() {
                if (player !== p || playerView == null) return
                val duration = p.duration
                val position = p.currentPosition
                if (!progressBar.isPressed && duration > 0) {
                    progressBar.progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
                }
                positionLabel.text = formatPlayerTime(position)
                durationLabel.text = formatPlayerTime(if (duration > 0) duration else 0L)
                playPauseButton.text = if (p.isPlaying) "Ⅱ" else "▶"
                if (isVodPlayer) {
                    // Hide the large center Play control while running; the compact
                    // Pause/Play action appears only when the control dock is revealed.
                    playPauseButton.visibility = if (p.isPlaying) View.GONE else View.VISIBLE
                }
                if (section == "Series" && position > 0L && position % 5_000L < 600L) saveAnimeResumePosition(p, channel)
                controlsHandler.postDelayed(this, 500L)
            }
        }
        controlsHandler.post(progressUpdater)

        // Full Screen / Fit Screen changes preserve the same synchronized overlay timer.
        onAnimeFullscreenToggle = {
            setPlayerFullscreen(!isPlayerFullscreen)
            revealControls()
        }
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold = dp(64)
            private val swipeVelocityThreshold = dp(120)
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // One tap reveals the centre button, title, and bottom dock as a single group.
                // The group shares one 3-second hide deadline in FIT and fullscreen.
                revealControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Double-tap either half of the video to seek by ten seconds.
                // This works in both inline/FIT and fullscreen playback.
                val duration = p.duration
                if (duration <= 0L || duration == C.TIME_UNSET) return true
                val forward = e.x >= (playerView?.width ?: screen.width).coerceAtLeast(1) / 2f
                val delta = if (forward) 10_000L else -10_000L
                val target = (p.currentPosition + delta).coerceIn(0L, duration)
                p.seekTo(target)
                revealControls()
                toast(if (forward) "+10 seconds" else "−10 seconds")
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                // A clear downward swipe minimizes fullscreen playback to the inline player.
                // Keep horizontal swipes reserved for channel navigation.
                if (isPlayerFullscreen &&
                    dy > swipeThreshold &&
                    dy > kotlin.math.abs(dx) * 1.25f &&
                    velocityY > swipeVelocityThreshold) {
                    setPlayerFullscreen(false)
                    return true
                }
                // A clear upward swipe on the minimized video restores fullscreen playback.
                if (!isPlayerFullscreen &&
                    dy < -swipeThreshold &&
                    kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.25f &&
                    velocityY < -swipeVelocityThreshold) {
                    setPlayerFullscreen(true)
                    return true
                }
                // Horizontal channel navigation is a Live TV-only gesture.
                // Anime Series (VOD) must never show or trigger channel switching.
                // Only a genuine Live TV stream may use horizontal channel navigation.
                // Anime episodes can remain in the player while the surrounding section changes,
                // so also inspect the active item itself before allowing this gesture.
                if (section == "Live TV" &&
                    currentChannel?.let { !isAnime(it) && !isSeries(it) } == true &&
                    kotlin.math.abs(dx) > swipeThreshold &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f &&
                    kotlin.math.abs(velocityX) > swipeVelocityThreshold) {
                    playAdjacentChannel(if (dx < 0) 1 else -1)
                    return true
                }
                return true
            }
        })
        view.setOnTouchListener { _, event -> tapDetector.onTouchEvent(event); true }

        val tvRemoteHint: TextView? = if (isTvDevice) TextView(this).apply {
            text = "▲ / ▼  CHANGE CHANNEL     BACK  •  EXIT"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0xB8000000.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x6688BBFF)
            }
            isFocusable = false
            contentDescription = "Use Up or Down on the TV remote to change channel"
        } else null
        tvRemoteHint?.let { hint ->
            screen.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(18)
            })
        }

        // Do not reset the channel/movie list to the top when playback starts.
        // Keep the user's current category and scroll offset; the list is restored
        // when the player closes or returns from fullscreen.

        // Start channel cards immediately below the inline video; no artificial blank spacer.
        if (::listContainer.isInitialized) {
            listContainer.setPadding(
                listContainer.paddingLeft, 0,
                listContainer.paddingRight, listContainer.paddingBottom
            )
        }
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        setPlayerFullscreen(keepFullscreenMode)
        val lowerUrl = streamUrl.substringBefore("?").substringBefore("#").lowercase()
        val itemBuilder = MediaItem.Builder().setUri(streamUrl)
        when (streamMimeTypeHint?.lowercase()) {
            MimeTypes.APPLICATION_M3U8.lowercase(), "m3u8", "hls" ->
                itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            MimeTypes.APPLICATION_MPD.lowercase(), "mpd", "dash" ->
                itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            else -> when {
                lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".m3u") ||
                    lowerUrl.contains("/hls/") || lowerUrl.contains("format=m3u8") ->
                    itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                lowerUrl.endsWith(".mpd") || lowerUrl.contains("/dash/") ->
                    itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }

        // Merge the legacy single WebVTT URL with provider-supplied subtitle tracks.
        // The same provider request headers are applied by the shared Media3 HTTP
        // DataSource factory, including subtitle requests.
        val subtitleCandidates = buildList {
            if (!subtitleUrl.isNullOrBlank()) {
                add(SanySubtitleTrack(url = subtitleUrl, mimeType = MimeTypes.TEXT_VTT))
            }
            addAll(subtitleTracks)
        }
        val mediaSubtitles = subtitleCandidates
            .filter { candidate ->
                candidate.url.startsWith("https://", true) ||
                    candidate.url.startsWith("http://", true)
            }
            .distinctBy { it.url }
            .map { candidate ->
                val subtitlePath = candidate.url.substringBefore("?").substringBefore("#").lowercase()
                val inferredMime = when {
                    subtitlePath.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                    subtitlePath.endsWith(".ttml") || subtitlePath.endsWith(".xml") ->
                        MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(candidate.url))
                    .setMimeType(candidate.mimeType?.takeIf { it.isNotBlank() } ?: inferredMime)
                    .setLanguage(candidate.language.ifBlank { "und" })
                    .apply { candidate.label?.takeIf { it.isNotBlank() }?.let(::setLabel) }
                    .setSelectionFlags(0)
                    .build()
            }
        if (mediaSubtitles.isNotEmpty()) {
            itemBuilder.setSubtitleConfigurations(mediaSubtitles)
        }
        // Anime captions are disabled by default; other sections keep their normal track behavior.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, section == "Series" || section == "Movies")
            .apply { if (section == "Series" || section == "Movies") setPreferredAudioLanguages("bn", "ben", "hi", "hin", "en", "eng") }
            .build()
        val mediaItem = itemBuilder.build()
        val animeResumePosition = if (section == "Series") prefs.getLong(animeResumePreferenceKey(channel), 0L).coerceAtLeast(0L) else 0L
        val validExternalAudio = externalAudioTracks.filter { track ->
            track.url.startsWith("https://", true) || track.url.startsWith("http://", true)
        }.distinctBy { it.url }
        if (validExternalAudio.isEmpty()) {
            if (animeResumePosition > 0L) p.setMediaItem(mediaItem, animeResumePosition) else p.setMediaItem(mediaItem)
        } else {
            // CloudStream exposes some alternate dubs as separate audio files rather than
            // embedded HLS/DASH renditions. Merge each audio-only source with the video so
            // Media3 exposes them as selectable audio track groups.
            val videoSource = mediaSourceFactory.createMediaSource(mediaItem)
            val audioSources = validExternalAudio.map { track ->
                val audioHeaders = linkedMapOf<String, String>().apply {
                    putAll(mediaHeaders)
                    putAll(track.headers)
                }
                val audioFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(audioHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                    .setDefaultRequestProperties(audioHeaders)
                    .setConnectTimeoutMs(if (highSpeedStreaming) 10000 else 5000)
                    .setReadTimeoutMs(if (highSpeedStreaming) 30000 else 15000)
                    .setAllowCrossProtocolRedirects(true)
                val audioMediaSourceFactory = DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(audioFactory)
                audioMediaSourceFactory.createMediaSource(MediaItem.fromUri(track.url))
            }
            val mergedSource = MergingMediaSource(videoSource, *audioSources.toTypedArray())
            if (animeResumePosition > 0L) p.setMediaSource(mergedSource, animeResumePosition)
            else p.setMediaSource(mergedSource)
        }
        p.setPlaybackSpeed(prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f))
        // Hold both audio and video until Media3 renders the first frame.
        animeAutoPlayWhenReady = startPlayback
        animeFirstFrameRendered = false
        animePlaybackLoadingRequested = true
        p.playWhenReady = false
        updateAnimeFullscreenBuffering()
        p.prepare()
        if (isAnimeEpisode && startPlayback) animeCenterPlayButton?.visibility = View.GONE
        prefs.edit().putString("last_channel", streamUrl).apply()
    }


    private fun showAnimeTrackSelection(trackType: Int, title: String, allowDisable: Boolean) {
        if (section != "Series" && section != "Movies") return
        val activePlayer = player ?: return
        if (trackType == C.TRACK_TYPE_TEXT && subtitleTrackDialog?.isShowing == true) return
        if (trackType == C.TRACK_TYPE_TEXT) {
            activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
                .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        }
        val dialog = TrackSelectionDialogBuilder(this, title, activePlayer, trackType)
            .setAllowAdaptiveSelections(false)
            .setShowDisableOption(allowDisable)
            .build()
        if (trackType == C.TRACK_TYPE_TEXT) {
            subtitleTrackDialog = dialog
            dialog.setOnDismissListener { if (subtitleTrackDialog === dialog) subtitleTrackDialog = null }
        }
        dialog.show()
    }

    private fun showAnimeSubtitleOptions() {
        val choices = arrayOf("Off", "Choose available subtitle language", "Add subtitle file (.srt / .vtt)")
        AlertDialog.Builder(this).setTitle("Subtitles").setItems(choices) { _, which ->
            when (which) {
                0 -> applyAnimeSubtitle(null)
                1 -> showAnimeTrackSelection(C.TRACK_TYPE_TEXT, "Subtitle language", true)
                2 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "text/vtt", "text/plain"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, pickAnimeSubtitleFile)
            }
        }.setNegativeButton("CANCEL", null).show()
    }

    private fun applyAnimeSubtitle(subtitleUri: Uri?) {
        if (section != "Series") return
        val activePlayer = player ?: return
        val activeChannel = currentChannel ?: return
        val streamUrl = activeChannel.url
        val position = activePlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = activePlayer.playWhenReady
        val item = MediaItem.Builder().setUri(streamUrl)
        val lower = streamUrl.substringBefore("?").substringBefore("#").lowercase()
        when {
            lower.endsWith(".m3u8") || lower.endsWith(".m3u") || lower.contains("/hls/") || lower.contains("format=m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") || lower.contains("/dash/") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        if (subtitleUri != null) {
            val path = subtitleUri.lastPathSegment.orEmpty().lowercase()
            val mime = if (path.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            item.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                    .setMimeType(mime).setLanguage("und").setSelectionFlags(0).build()
            ))
            activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build()
        } else {
            activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
        }
        activePlayer.setMediaItem(item.build(), position)
        activePlayer.prepare()
        activePlayer.playWhenReady = wasPlaying
        toast(if (subtitleUri == null) "Anime subtitles Off" else "Subtitle added")
    }

    private fun applyVideoQuality(selector: DefaultTrackSelector? = trackSelector) {
        val target = selector ?: return
        val quality = prefs.getString("video_quality", "auto") ?: "auto"
        val builder = target.buildUponParameters()
        // VOD Auto mode delegates quality selection to Media3's adaptive track
        // selector. HLS/DASH renditions can move up/down with measured bandwidth.
        // A single-file MP4 remains at the quality supplied by its source.
        if (section == "Series" || section == "Movies") {
            val audioLanguages = activeProviderAudioLanguages ?: listOf("bn", "ben", "hi", "hin", "en", "eng")
            builder.setPreferredAudioLanguages(*audioLanguages.toTypedArray())
            if (section == "Series") builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            val quality = prefs.getString("video_quality", "auto") ?: "auto"
            when (quality) {
                "480" -> builder.setMaxVideoSize(854, 480).setMaxVideoBitrate(1_200_000)
                "720" -> builder.setMaxVideoSize(1280, 720).setMaxVideoBitrate(2_500_000)
                "1080" -> builder.setMaxVideoSize(1920, 1080).setMaxVideoBitrate(5_000_000)
                else -> builder.clearVideoSizeConstraints().setMaxVideoBitrate(Int.MAX_VALUE)
            }
            target.setParameters(builder)
            return
        }
        when (quality) {
            "480" -> builder.setMaxVideoSize(854, 480).setMaxVideoBitrate(1_200_000)
            "720" -> builder.setMaxVideoSize(1280, 720).setMaxVideoBitrate(2_500_000)
            "1080" -> builder.setMaxVideoSize(1920, 1080).setMaxVideoBitrate(5_000_000)
            else -> if (prefs.getBoolean("high_speed_streaming", true)) {
                // In high-speed mode, allow the source's highest available adaptive track.
                builder.clearVideoSizeConstraints().setMaxVideoBitrate(Int.MAX_VALUE)
            } else if (prefs.getBoolean("low_data_mode", defaultLowInternetMode)) {
                builder.setMaxVideoSize(854, 480).setMaxVideoBitrate(650_000)
            } else {
                builder.clearVideoSizeConstraints().setMaxVideoBitrate(Int.MAX_VALUE)
            }
        }
        target.setParameters(builder)
    }

    private fun animeResumePreferenceKey(channel: Channel): String = "anime_resume_" + channel.name.trim().hashCode().toString()

    private fun saveAnimeResumePosition(activePlayer: ExoPlayer?, channel: Channel?) {
        if (section != "Series" || activePlayer == null || channel == null) return
        val duration = activePlayer.duration
        val position = activePlayer.currentPosition.coerceAtLeast(0L)
        if (duration <= 0L || position < 10_000L) return
        val editor = prefs.edit()
        if (activePlayer.playbackState == Player.STATE_ENDED || duration - position <= 30_000L) editor.remove(animeResumePreferenceKey(channel))
        else editor.putLong(animeResumePreferenceKey(channel), position)
        editor.apply()
    }

    private fun formatPlayerTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
            else String.format("%02d:%02d", minutes, seconds)
    }

    private fun playAdjacentChannel(direction: Int) {
        val current = currentChannel ?: return
        val index = channels.indexOfFirst { it.url == current.url }
        if (index < 0 || channels.size < 2) {
            toast("আর কোনো চ্যানেল নেই")
            return
        }
        val nextIndex = (index + direction + channels.size) % channels.size
        play(channels[nextIndex])
        toast("Playing: ${channels[nextIndex].name}")
    }

    private fun renderMinimizedPlayerChannels() {
        val container = playerChannelRows ?: return
        container.removeAllViews()
        container.addView(text("ALL CHANNELS  •  PLAYLIST ORDER", 11, accent, true).apply {
            setPadding(dp(4), dp(4), dp(4), dp(10))
            letterSpacing = 0.04f
        })
        channels.forEachIndexed { index, channel ->
            val isPlaying = currentChannel?.url == channel.url
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = if (isPlaying) GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF126BDF.toInt(), 0xEE0845A4.toInt(), 0xDD071F52.toInt())
                ).apply {
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(2), 0xFF35B8FF.toInt())
                } else glass(12)
                if (isPlaying) elevation = dp(7).toFloat()
                applyTvFocusStyle(this)
                setOnClickListener { play(channel) }
            }
            row.addView(text((index + 1).toString().padStart(2, '0'), 12, accent, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(34), dp(44)))
            val title = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            title.addView(text(channel.name, 14, if (isPlaying) Color.WHITE else white, true))
            title.addView(text(channel.group.ifBlank { "Other" }, 10, if (isPlaying) 0xFFD6E9FF.toInt() else muted))
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            if (isPlaying) row.addView(text("▶", 17, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(4), 0)
            })
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            })
        }
    }

    private fun setPlayerFullscreen(enabled: Boolean) {
        isPlayerFullscreen = enabled
        updateAnimeFullscreenBuffering()
        if (enabled) {
            // A channel selected from the inline category search can leave the
            // soft keyboard visible over the landscape player. Dismiss the IME
            // before rotating, clear EditText focus, then repeat after rotation
            // because Android may restore the keyboard during Activity relayout.
            val searchInput = if (::categoryHeaderContainer.isInitialized)
                categoryHeaderContainer.findViewWithTag<EditText>("category_inline_search")
            else null
            searchInput?.clearFocus()
            if (::root.isInitialized) root.clearFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as
                android.view.inputmethod.InputMethodManager
            val token = (searchInput?.windowToken ?: window.decorView.windowToken)
            imm.hideSoftInputFromWindow(token, 0)
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && isPlayerFullscreen) {
                    searchInput?.clearFocus()
                    root.clearFocus()
                    imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                }
            }, 350L)
        }
        playerHeader?.visibility = if (enabled) View.GONE else View.VISIBLE
        playerFullscreenButton?.text = if (enabled) "⤢" else "⛶"
        animeFullscreenActionButton?.text = if (enabled) "⤢  Fit Screen" else "⛶  Full Screen"
        playerControlsPanel?.let { panel ->
            (panel.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.gravity = if (enabled) Gravity.BOTTOM else Gravity.TOP
                params.topMargin = if (enabled) 0 else dp(155)
                params.bottomMargin = if (enabled) dp(18) else 0
                panel.layoutParams = params
            }
        }
        playerView?.let { view ->
            // Anime fullscreen must use the entire landscape display. Do not keep
            // the season/episode sidebar beside the video; stretch the picture
            // to the full PlayerView bounds as requested.
            // Use true stretch-to-bounds in every fullscreen player section.
            // This fills the entire landscape video area without the extra crop/zoom.
            view.resizeMode = if (enabled) AspectRatioFrameLayout.RESIZE_MODE_FILL
                else AspectRatioFrameLayout.RESIZE_MODE_FIT
            view.layoutParams = FrameLayout.LayoutParams(
                -1,
                if (enabled) -1 else dp(235),
                if (enabled) Gravity.CENTER else Gravity.TOP
            )
        }
        animeEpisodePanel?.visibility = View.GONE
        // Anime centre actions are positioned as one group and remain centred in both modes.
        animePlayerTitleOverlay?.layoutParams = FrameLayout.LayoutParams(
            -1, dp(58), Gravity.TOP
        )
        playerControlsPanel?.let { controls ->
            (controls.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = -1
                lp.gravity = if (enabled) Gravity.BOTTOM else Gravity.TOP
                lp.leftMargin = dp(8)
                lp.rightMargin = dp(8)
                lp.topMargin = if (enabled) 0 else dp(155)
                // Keep the slimmer Anime dock close to the bottom edge in fullscreen.
                lp.bottomMargin = if (enabled) dp(2) else 0
                controls.layoutParams = lp
            }
        }
        // In minimized mode, reserve the top 235dp for video and lay out the
        // currently selected menu in the remaining screen area.
        playerScreen?.layoutParams = FrameLayout.LayoutParams(
            -1, if (enabled) -1 else dp(235), Gravity.TOP
        )
        if (::home.isInitialized) {
            home.layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
                topMargin = if (enabled) 0 else dp(235)
            }
        }
        playerHeader?.let { header ->
            (header.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.topMargin = if (enabled) 0 else dp(235)
                header.layoutParams = params
            }
        }
        playerFullscreenButton?.visibility = if (enabled) View.GONE else View.VISIBLE
        playerSettingsButton?.visibility = if (enabled) View.VISIBLE else View.GONE
        playerMinimizeButton?.visibility = if (enabled) View.VISIBLE else View.GONE
        playerChannelScroll?.visibility = View.GONE
        requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        playerScreen?.setBackgroundColor(if (enabled) Color.BLACK else Color.TRANSPARENT)
        if (!enabled) { applySystemBars(); return }
        // Fullscreen video hides both Android system bars. Fit Screen restores
        // the normal app layout and the real status bar through applySystemBars().
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun closePlayer(keepOrientation: Boolean = false) {
        val tvVisibleSection = if (isTvDevice && !keepOrientation) section else null
        val tvVisibleGroup = if (isTvDevice && !keepOrientation) selectedGroup else null
        val tvVisibleSearch = if (isTvDevice && !keepOrientation) searchQuery else null
        val tvVisibleScrollY = if (isTvDevice && !keepOrientation && ::listContainer.isInitialized)
            (listContainer.parent as? ScrollView)?.scrollY else null
        // Save Anime Series progress before the player is stopped or released.
        saveAnimeResumePosition(player, currentChannel)
        // When changing channels, do not briefly unlock orientation: Android may
        // rotate the Activity to portrait before the next fullscreen player is ready.
        if (!keepOrientation) {
            if (isPlayerFullscreen) setPlayerFullscreen(false)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            applySystemBars()
        }
        playerHeader = null
        playerFullscreenButton = null
        playerMinimizeButton = null
        playerSettingsButton = null
        playerTopActions = null
        playerChannelScroll = null
        playerChannelRows = null
        playerControlsPanel = null
        if (::listContainer.isInitialized) {
            listContainer.setPadding(
                listContainer.paddingLeft, 0,
                listContainer.paddingRight, listContainer.paddingBottom
            )
        }
        if (::home.isInitialized) {
            home.layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        playbackRetryHandler.removeCallbacksAndMessages(null)
        player?.stop()
        pipPlaybackActionHandler = null
        playerView?.player = null
        // Release codec, audio and renderer resources as soon as playback is closed.
        player?.release()
        player = null
        trackSelector = null
        playerView = null
        animeEpisodePanel = null
        animeCenterPlayButton = null
        animePlayerTitleOverlay = null
        playerScreen?.let { root.removeView(it) }
        playerScreen = null
        updateHomeHeaderVisibility()
        if (!keepOrientation) {
            isPlayerFullscreen = false
            // Restore the exact list context after the player overlay is removed.
            section = tvVisibleSection ?: playbackReturnSection
            selectedGroup = tvVisibleGroup ?: playbackReturnGroup
            searchQuery = tvVisibleSearch ?: playbackReturnSearch
            if (section == "Live TV") {
                liveTvSelectedGroup = selectedGroup
                liveTvSearchQuery = searchQuery
                prefs.edit().putString("live_tv_group", selectedGroup)
                    .putString("live_tv_search", searchQuery).apply()
            }
            if (::listContainer.isInitialized) {
                (listContainer.parent as? ScrollView)?.post {
                    (listContainer.parent as? ScrollView)?.scrollTo(0, tvVisibleScrollY ?: playbackReturnScrollY)
                }
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickAnimeSubtitleFile && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                applyAnimeSubtitle(uri)
            }
            return
        }
        if (requestCode == pickFile && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                toast("Importing playlist…")
                thread {
                    try {
                        val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val parsed = M3uParser.parse(raw)
                        val detectedEpgUrl = extractEpgUrl(raw)
                        runOnUiThread {
                            if (parsed.isEmpty()) toast("No channels found in file") else {
                                channels = parsed; selectedGroup = "All"; searchQuery = ""
                                streamChecks.clear()
                                if (!detectedEpgUrl.isNullOrBlank()) {
                                    prefs.edit().putString("epg_url", detectedEpgUrl).apply()
                                    if (prefs.getBoolean("auto_add_epg", true)) updateEpgData(detectedEpgUrl, false)
                                }
                                renderHome()
                                startPlaylistCheck(false)
                                toast("Imported ${channels.size} items — checking streams")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("File error: ${e.message ?: "could not read file"}") }
                    }
                }
            }
        }
    }

    // Prepare Android 12+ auto-enter PiP while playback is active. Gesture navigation
    // may not deliver a reliable last-moment callback, so PiP parameters must already
    // be registered before the user swipes Home.
    private fun refreshPictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val videoSize = player?.videoSize
        val hasVideo = videoSize != null && videoSize.width > 0 && videoSize.height > 0
        val canEnterFloatingWindow = prefs.getBoolean("floating_window_enabled", true) &&
            playerView != null && player?.isPlaying == true && hasVideo

        // Use one standard 16:9 PiP window for every playback section/provider.
        // Do not let a source's encoded aspect ratio change the floating-window size.
        // PiP entry is enabled only for active video playback, never audio-only,
        // paused, stopped, or empty-player states.
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (playerView != null && player != null) {
            // Use playWhenReady for the action state so PiP does not
            // incorrectly switch to Resume during a short rebuffer.
            val isPlaying = player?.playWhenReady == true
            fun pipAction(action: String, requestCode: Int, iconRes: Int, label: String): RemoteAction {
                val actionIntent = Intent(this, PipPlaybackActionReceiver::class.java).setAction(action)
                val pendingAction = PendingIntent.getBroadcast(
                    this, requestCode, actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                return RemoteAction(Icon.createWithResource(this, iconRes), label, label, pendingAction)
            }
            builder.setActions(listOf(
                pipAction(ACTION_PIP_SEEK_BACK, 7102, android.R.drawable.ic_media_rew, "Back 10 seconds"),
                pipAction(
                    ACTION_PIP_TOGGLE_PLAYBACK, 7101,
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) "Pause" else "Play"
                ),
                pipAction(ACTION_PIP_SEEK_FORWARD, 7103, android.R.drawable.ic_media_ff, "Forward 10 seconds")
            ))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Always update this flag, including false, so a previous video
            // cannot leave stale auto-enter enabled after pause/stop.
            builder.setAutoEnterEnabled(canEnterFloatingWindow)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canEnterFloatingWindow) {
            val rect = android.graphics.Rect()
            if (playerView?.getGlobalVisibleRect(rect) == true && !rect.isEmpty) {
                builder.setSourceRectHint(rect)
            }
        }
        setPictureInPictureParams(builder.build())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val videoSize = player?.videoSize
        val hasVideo = videoSize != null && videoSize.width > 0 && videoSize.height > 0
        if (playerView == null || player?.isPlaying != true || !hasVideo) return
        if (!prefs.getBoolean("floating_window_enabled", true)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ uses the pre-registered auto-enter transition.
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            refreshPictureInPictureParams()
            playerScreen?.layoutParams = FrameLayout.LayoutParams(-1, -1)
            playerView?.let { view ->
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.layoutParams = FrameLayout.LayoutParams(-1, -1)
                view.setUseController(false)
            }
            enterPictureInPictureMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // Android 12+ can auto-enter PiP directly from the Home gesture,
            // without onUserLeaveHint. Expand the video and its clipping parent
            // here as well so the PiP surface contains only the video.
            playerScreen?.layoutParams = FrameLayout.LayoutParams(-1, -1)
            playerView?.let { view ->
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.layoutParams = FrameLayout.LayoutParams(-1, -1)
                view.setUseController(false)
            }
            playerHeader?.visibility = View.GONE
            playerFullscreenButton?.visibility = View.GONE
            playerMinimizeButton?.visibility = View.GONE
            // Keep the PiP surface clean: Anime title text is an in-app overlay,
            // not part of the video, so hide it while Android displays the PiP window.
            animePlayerTitleOverlay?.visibility = View.GONE
        } else if (playerView != null) {
            // Restore the normal embedded-player container after PiP closes.
            playerScreen?.layoutParams = FrameLayout.LayoutParams(
                -1, if (isPlayerFullscreen) -1 else dp(235), Gravity.TOP
            )
            playerView?.let { view ->
                view.setUseController(false)
                view.resizeMode = if (isPlayerFullscreen) AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.layoutParams = FrameLayout.LayoutParams(
                    -1, if (isPlayerFullscreen) -1 else dp(235),
                    if (isPlayerFullscreen) Gravity.CENTER else Gravity.TOP
                )
            }
            playerHeader?.visibility = if (isPlayerFullscreen) View.GONE else View.VISIBLE
            playerFullscreenButton?.visibility = if (isPlayerFullscreen) View.GONE else View.VISIBLE
            playerMinimizeButton?.visibility = View.GONE
            // Restore the Anime title only after returning from PiP to the app player.
            animePlayerTitleOverlay?.visibility = View.VISIBLE
        }
    }

    override fun onStart() {
        super.onStart()
        scheduleOfflineChannelRecheck()
        // Restore playback after returning from Android Home. onStop() stops the
        // engine to prevent background audio, so restore the saved position and
        // the exact play/pause intent even when Floating Window is enabled.
        val savedUrl = prefs.getString("resume_channel_url", null)
        if (!savedUrl.isNullOrBlank()) {
            val savedSection = prefs.getString("resume_channel_section", "Live TV") ?: "Live TV"
            val shouldResumePlaying = prefs.getBoolean("resume_channel_playing", false)
            val savedPosition = prefs.getLong("resume_channel_position", 0L).coerceAtLeast(0L)
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    val existingChannel = currentChannel?.takeIf { it.url.trim() == savedUrl }
                    if (playerView != null && existingChannel != null && player != null) {
                        // Same Activity instance: prepare the stopped item at its last position.
                        player?.prepare()
                        if (savedPosition > 0L) player?.seekTo(savedPosition)
                        player?.playWhenReady = shouldResumePlaying
                        refreshPictureInPictureParams()
                    } else if (playerView == null) {
                        // Activity/process was recreated: rebuild from available playlist data.
                        val channel = channels.firstOrNull { it.url.trim() == savedUrl }
                        if (channel != null) {
                            section = if (savedSection == "Movies") "Movies" else "Live TV"
                            play(channel, startPlayback = shouldResumePlaying)
                            if (savedPosition > 0L) player?.seekTo(savedPosition)
                        }
                    }
                    prefs.edit().remove("resume_channel_url")
                        .remove("resume_channel_section")
                        .remove("resume_channel_playing")
                .remove("resume_channel_position")
                        .remove("resume_channel_position").apply()
                }
            }, 700L)
        }
    }

    // A screen snapshot lets Back unwind the actual section/category path one step at a time.
    private data class NavigationSnapshot(
        val section: String,
        val selectedGroup: String,
        val searchQuery: String,
        val liveTvSelectedGroup: String,
        val liveTvSearchQuery: String,
        val scrollY: Int
    )
    private val navigationHistory = java.util.ArrayDeque<NavigationSnapshot>()
    private var lastRenderedNavigation: NavigationSnapshot? = null
    private var restoringNavigation = false
    private var lastBackPressedAt = 0L

    companion object {
        @Volatile private var pipPlaybackActionHandler: ((String) -> Unit)? = null

        internal fun dispatchPipPlaybackAction(action: String) {
            pipPlaybackActionHandler?.invoke(action)
        }
    }

    private fun currentNavigationSnapshot() = NavigationSnapshot(
        section = section,
        selectedGroup = selectedGroup,
        searchQuery = searchQuery,
        liveTvSelectedGroup = liveTvSelectedGroup,
        liveTvSearchQuery = liveTvSearchQuery,
        scrollY = if (::listContainer.isInitialized)
            ((listContainer.parent as? ScrollView)?.scrollY ?: playbackReturnScrollY)
        else playbackReturnScrollY
    )

    private fun restorePreviousNavigation() {
        if (navigationHistory.isEmpty()) return
        val previous = navigationHistory.removeLast()
        section = previous.section
        selectedGroup = previous.selectedGroup
        searchQuery = previous.searchQuery
        liveTvSelectedGroup = previous.liveTvSelectedGroup
        liveTvSearchQuery = previous.liveTvSearchQuery
        // While a video is active, keep its original return point unchanged.
        // Back may temporarily visit Settings or another tab, but closing the player
        // must still restore the exact section/category where playback began.
        if (playerView == null) {
            playbackReturnSection = previous.section
            playbackReturnGroup = previous.selectedGroup
            playbackReturnSearch = previous.searchQuery
            playbackReturnScrollY = previous.scrollY
        }
        prefs.edit()
            .putString("ui_section", section)
            .putString("ui_group", selectedGroup)
            .putString("ui_search", searchQuery)
            .putString("live_tv_group", liveTvSelectedGroup)
            .putString("live_tv_search", liveTvSearchQuery)
            .apply()
        restoringNavigation = true
        renderHome()
        if (::listContainer.isInitialized) {
            (listContainer.parent as? ScrollView)?.post {
                (listContainer.parent as? ScrollView)?.scrollTo(0, previous.scrollY)
            }
        }
    }

    private fun returnToLiveTvHome(keepPlayback: Boolean = false) {
        if (keepPlayback && playerView != null && player != null) {
            // Keep Anime Series playback alive while navigating back to Live TV.
            // The next Back press on Live TV is the explicit action that closes it.
            setPlayerFullscreen(false)
        } else {
            closePlayer()
        }
        section = "Live TV"
        // Return to the category directory, not an "All" channel filter.
        selectedGroup = "Select category"
        searchQuery = ""
        liveTvSelectedGroup = "Select category"
        liveTvSearchQuery = ""
        navigationHistory.clear()
        lastRenderedNavigation = null
        playbackReturnSection = "Live TV"
        playbackReturnGroup = "Select category"
        playbackReturnSearch = ""
        playbackReturnScrollY = 0
        prefs.edit()
            .putString("ui_section", "Live TV")
            .putString("ui_group", "Select category")
            .putString("ui_search", "")
            .putString("live_tv_group", "Select category")
            .putString("live_tv_search", "")
            .apply()
        lastBackPressedAt = 0L
        renderHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // First Back from fullscreen (or a downward swipe) changes only the
        // presentation to FIT. The same player and playback position remain active.
        if (playerView != null && isPlayerFullscreen) {
            setPlayerFullscreen(false)
            lastBackPressedAt = 0L
            return
        }

        // Movie details are the deepest Movies page. Restore the exact provider/
        // category page first; the player stays attached and keeps its position.
        if (section == "Movies" && movieDetailsParentView != null) {
            restoreMovieDetailsParent()
            lastBackPressedAt = 0L
            return
        }

        // Embedded browser/provider navigation is deeper than the app-level stack.
        if (section == "Movies" && moviesWebView?.canGoBack() == true) {
            moviesWebView?.goBack()
            lastBackPressedAt = 0L
            return
        }
        if (section == "Series") {
            if (animeExternalPageActive) {
                restoreAnimeExternalPage()
                lastBackPressedAt = 0L
                return
            }
            if (cloudStreamProviderScreen?.handleBack() == true) {
                lastBackPressedAt = 0L
                return
            }
            if (cloudStreamProviderScreen != null) {
                cloudStreamProviderScreen = null
                cloudStreamProviderView = null
                contentArea.removeAllViews()
                attachAniLightNativeView()
                lastBackPressedAt = 0L
                return
            }
            if (aniLightNativeScreen?.handleBack() == true) {
                lastBackPressedAt = 0L
                return
            }
        }

        // Settings without a running video always returns to the Live TV home.
        // With playback active, the navigation snapshot below returns to the
        // exact option/page from which the video was started.
        if (section == "Settings" && playerView == null) {
            returnToLiveTvHome(keepPlayback = false)
            return
        }

        // Preserve every section/category transition in order. In particular,
        // do not discard the previous section when Settings or another tab opens.
        // If playback is active and we are already back at its origin, only unwind
        // pages within that same option; never jump to an older unrelated tab.
        if (navigationHistory.isNotEmpty()) {
            val previous = navigationHistory.peekLast()
            val atPlaybackOrigin = playerView != null && section == playbackReturnSection
            if (!atPlaybackOrigin || previous.section == section) {
                lastBackPressedAt = 0L
                restorePreviousNavigation()
                return
            }
        }

        // At the original option's home, the next Back closes only the video.
        // Keep the current home page visible and do not navigate to Live TV.
        if (playerView != null && section == playbackReturnSection) {
            closePlayer()
            lastBackPressedAt = 0L
            return
        }

        // If a video is active on a temporary tab but its history is unavailable,
        // restore the original playback location without stopping the video.
        if (playerView != null) {
            section = playbackReturnSection
            selectedGroup = playbackReturnGroup
            searchQuery = playbackReturnSearch
            if (section == "Live TV") {
                liveTvSelectedGroup = selectedGroup
                liveTvSearchQuery = searchQuery
            }
            restoringNavigation = true
            renderHome()
            if (::listContainer.isInitialized) {
                (listContainer.parent as? ScrollView)?.post {
                    (listContainer.parent as? ScrollView)?.scrollTo(0, playbackReturnScrollY)
                }
            }
            lastBackPressedAt = 0L
            return
        }

        // Without playback, Back from another main option returns to Live TV home.
        if (section != "Live TV" ||
            selectedGroup != "Select category" ||
            searchQuery.isNotEmpty()
        ) {
            returnToLiveTvHome(keepPlayback = false)
            return
        }

        // At Live TV home, two consecutive Back presses exit the app.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackPressedAt in 1..2000L) {
            lastBackPressedAt = 0L
            finish()
        } else {
            lastBackPressedAt = now
            toast("অ্যাপ বন্ধ করতে আবার Back চাপুন")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("ui_section", section)
        outState.putString("ui_group", selectedGroup)
        outState.putString("ui_search", searchQuery)
        outState.putString("live_tv_group", liveTvSelectedGroup)
        outState.putString("live_tv_search", liveTvSearchQuery)
        val visibleScrollY = if (playerView != null) playbackReturnScrollY
            else (if (::listContainer.isInitialized) (listContainer.parent as? ScrollView)?.scrollY else null) ?: playbackReturnScrollY
        outState.putInt("list_scroll_y", visibleScrollY)
        outState.putString("playback_return_section", playbackReturnSection)
        outState.putString("playback_return_group", playbackReturnGroup)
        outState.putString("playback_return_search", playbackReturnSearch)
        outState.putInt("playback_return_scroll_y", playbackReturnScrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        checkerUiHandler.removeCallbacks(offlineRecheckRunnable)
        val closingPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        // Save both channel identity and whether playback was running before minimizing.
        // Stop the engine while backgrounded, then restore the same play/pause intent on return.
        val shouldRestorePlayer = !closingPip && playerView != null &&
            currentChannel != null && player?.playbackState != Player.STATE_IDLE
        // Save Anime Series episode progress before stopping playback in the background.
        saveAnimeResumePosition(player, currentChannel)
        val stateEditor = prefs.edit()
        if (shouldRestorePlayer) {
            stateEditor.putString("resume_channel_url", currentChannel?.url)
                .putString("resume_channel_section", section)
                .putBoolean("resume_channel_playing", player?.playWhenReady == true)
                .putLong("resume_channel_position", player?.currentPosition?.coerceAtLeast(0L) ?: 0L)
        } else {
            stateEditor.remove("resume_channel_url")
                .remove("resume_channel_section")
                .remove("resume_channel_playing")
        }
        stateEditor.apply()
        resumeChannelOnForeground = null
        // No background audio: stop the player after saving its state for foreground restoration.
        player?.pause()
        player?.stop()
        prefs.edit()
            .putString("ui_section", section)
            .putString("ui_group", selectedGroup)
            .putString("ui_search", searchQuery)
            .putString("live_tv_group", liveTvSelectedGroup)
            .putString("live_tv_search", liveTvSearchQuery)
            .putBoolean("resume_without_splash", true)
            .remove("resume_stream")
            .apply()
        if (closingPip) prefs.edit().putBoolean("resume_without_splash", false).apply()
        super.onStop()
    }

    override fun onDestroy() {
        // Swiping this task away from Recents finishes SANY IPTV only.
        if (isFinishing) {
            prefs.edit().putBoolean("resume_without_splash", false).remove("resume_stream").apply()
        }
        playerView?.player = null
        moviesWebView?.apply { stopLoading(); destroy() }
        moviesWebView = null
        player?.release(); player = null
        logoExecutor.shutdownNow()
        if (CommonActivity.activity === this) CommonActivity.setActivityInstance(null)
        super.onDestroy()
    }

    private fun actionButton(label: String, action: () -> Unit) = TextView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = false
        applyTvFocusStyle(this)
        text = label; textSize = if (isTvDevice) 16f else 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(bg); gravity = Gravity.CENTER
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(235, 0, 190, 150), Color.argb(205, 0, 115, 115))
        ).apply { cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.argb(150, 200, 255, 240)) }
        elevation = dp(2).toFloat()
        setOnClickListener { action() }
    }
    private fun iconButton(label: String, action: () -> Unit) = TextView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = false
        applyTvFocusStyle(this)
        text = label; textSize = if (isTvDevice) 28f else 22f; setTextColor(white); gravity = Gravity.CENTER
        background = glass(12); elevation = dp(5).toFloat()
        setShadowLayer(dp(7).toFloat(), 0f, 0f, Color.argb(75, 75, 135, 255))
        setOnClickListener {
            // Run the requested action on the same tap; animation is only visual feedback.
            action()
            animate().cancel()
            scaleX = 0.94f; scaleY = 0.94f
            animate().scaleX(1f).scaleY(1f).setDuration(110)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) }
    }
    private fun pill(label: String, color: Int) = TextView(this).apply {
        text = label; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(color); setPadding(dp(10), dp(7), dp(10), dp(7))
        background = glass(30)
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = (size * if (isTvDevice) 1.15f else 1f); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun premiumBackdrop() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (isLight) intArrayOf(Color.WHITE, Color.WHITE, Color.WHITE)
        else intArrayOf(Color.BLACK, Color.BLACK, Color.BLACK)
    )

    // Shared Liquid Glass material used across Home, Live TV, Sports, Settings and player controls.
    private fun glass(radius: Int) =
        SanyLiquidGlass.background(this, radius, isLight, accent)

    private fun glassWithoutTopStroke(radius: Int) =
        SanyLiquidGlass.background(this, radius, isLight, accent, topRim = false)

    private fun rounded(color: Int, radius: Int, strokeColor: Int = 0, strokeWidth: Int = 0, ignored: Int = 0) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor) }
    private fun dp(v: Int): Int {
        val tvScale = if (isTvDevice) 1.15f else 1f
        return (v * resources.displayMetrics.density * tvScale).toInt()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

private class SimpleTextWatcher(val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}