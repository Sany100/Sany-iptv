package com.sany.iptv

import android.app.Activity
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.graphics.Color
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.media3.ui.AspectRatioFrameLayout
import java.net.URL
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {
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
    private val permanentPlaylistUrl = "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u"
    private val playlistUrls = mutableListOf<String>()
    private var selectedGroup = "All"
    private var liveTvSelectedGroup = "Select category"
    private var section = "Live TV"
    private var searchQuery = ""
    private var liveTvSearchQuery = ""
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private val playbackRetryHandler = Handler(Looper.getMainLooper())
    private var playbackRetryCount = 0
    private var animeAutoNextInProgress = false
    private var playerView: PlayerView? = null
    private var moviesWebView: WebView? = null
    private var moviesSiteLoaded = false
    private var aniLightWebView: WebView? = null
    private var aniLightSiteLoaded = false
    private var aniLightNativeScreen: AniLightNativeScreen? = null
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
    private var displayedLimit = 60
    private var playerHeader: LinearLayout? = null
    private var isPlayerFullscreen = false
    private var playerFullscreenButton: TextView? = null
    private var playerMinimizeButton: TextView? = null
    private var playerSettingsButton: TextView? = null
    private var playerTopActions: View? = null
    private var playerChannelScroll: ScrollView? = null
    private var playerChannelRows: LinearLayout? = null
    private var playerControlsPanel: View? = null
    private var headerTitleView: TextView? = null
    private var homeHeaderContainer: View? = null
    private var playlistSelectorContainer: View? = null
    private var liveTvNavLabel: TextView? = null
    private var maintenanceOverlay: View? = null
    private val maintenanceHandler = Handler(Looper.getMainLooper())
    private val maintenanceConfigUrl = "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/maintenance.json"
    // A small bounded pool prevents one thread per channel logo on low-memory phones.
    private val logoExecutor by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) 2 else 4
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
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
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
        installSanyCrashLogger()
        EpgRepository.scheduleDaily(this)
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
            liveTvSelectedGroup = savedInstanceState?.getString("live_tv_group") ?: "Select category"
            liveTvSearchQuery = savedInstanceState?.getString("live_tv_search") ?: ""
            selectedGroup = savedInstanceState?.getString("ui_group") ?: "All"
            searchQuery = savedInstanceState?.getString("ui_search") ?: ""
            if (section == "Live TV") {
                selectedGroup = liveTvSelectedGroup
                searchQuery = liveTvSearchQuery
            }
            playbackReturnSection = savedInstanceState?.getString("playback_return_section") ?: section
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
        // Automatically sync and load every allowlisted Anime provider from the
        // SANY builds index. Each package is hash-verified and failures are isolated.
        thread(name = "SANY-Anime-Plugins-AutoSync") {
            runCatching {
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
            }.onFailure { error ->
                android.util.Log.e("SANY-CS", "Anime plugin auto-sync failed", error)
            }
        }
        // If the previous playback attempt crashed the Activity, surface the saved
        // stack trace on the next launch so the failure can be diagnosed precisely.
        showPreviousCrashReport()
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
        // Keep the Activity window itself in sync with the selected theme; this
        // prevents dark gaps showing through behind transparent/scrolling views.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        // Android 15+ enforces edge-to-edge for targetSdk 35. Explicitly color
        // the decor and system-bar surfaces so Day mode never reveals black gaps.
        window.decorView.setBackgroundColor(bg)
        // Use fully opaque system bars in both Day and Night themes.
        window.statusBarColor = if (isLight) Color.WHITE else Color.rgb(6, 18, 38)
        window.navigationBarColor = if (isLight) Color.WHITE else Color.rgb(6, 18, 38)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.navigationBarDividerColor = bg
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (isLight) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            } else 0
        }
    }

    private fun buildShell(showSplash: Boolean = true) {
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isTvDevice) 14 else 16), dp(if (isTvDevice) 8 else 12), dp(if (isTvDevice) 14 else 16), dp(if (isTvDevice) 14 else 12))
            // Opaque theme-matched header: no translucent grey tint in either theme.
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (isLight) intArrayOf(Color.WHITE, Color.rgb(246, 250, 255))
                else intArrayOf(Color.rgb(7, 22, 45), Color.rgb(10, 28, 55))
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), if (isLight) Color.rgb(20, 30, 45) else Color.rgb(65, 125, 205))
            }
            // Premium raised header; opaque fill prevents the content behind it tinting the surface.
            cameraDistance = 11000f * resources.displayMetrics.density
            rotationX = 2.2f
            translationZ = dp(10).toFloat()
            elevation = dp(14).toFloat()
        }
        // Keep the original stacked brand header. Move connection status below
        // SANY IPTV instead of showing it as a pill on the right side.
        val brandLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = text("SANY IPTV", if (isTvDevice) 23 else 29, accent, true).apply {
            letterSpacing = 0.06f
            setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.argb(150, 55, 145, 255))
            setPadding(dp(8), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            translationZ = dp(10).toFloat()
        }
        brandLine.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val connectionStatus = text(
            if (channels.isEmpty()) "NOT CONNECTED" else "● CONNECTED",
            9, if (channels.isEmpty()) muted else accent, true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(5), dp(9), dp(5))
            letterSpacing = 0.035f
            background = glass(18)
        }
        brandLine.addView(connectionStatus, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = dp(8)
        })
        top.addView(brandLine)
        top.addView(text("PREMIUM IPTV EXPERIENCE", 10, white, true).apply {
            letterSpacing = 0.02f
            setPadding(dp(8), 0, 0, dp(3))
        })
        top.addView(text("✦  FAST  •  SMOOTH  •  UNLIMITED", 9, muted, true).apply {
            letterSpacing = 0.075f
            setPadding(0, 0, 0, dp(7))
        })
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerTitleView = text(if (section == "Series") "Anime Series" else section, 19, white, true)
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
        // Lower the complete branded header card slightly, matching the requested
        // red-marked area while keeping the content below in normal flow.
        home.addView(top, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(if (isTvDevice) 24 else 18)
        })
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
            // Anime Series is edge-to-edge; other sections retain the standard side gutters.
            setPadding(if (section == "Series") 0 else dp(18), 0,
                if (section == "Series") 0 else dp(18), 0)
            setBackgroundColor(bg)
        }
        home.addView(contentArea, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(2), dp(5), dp(2))
            // Solid bottom dock keeps the gesture/navigation area visually consistent.
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (isLight) intArrayOf(Color.WHITE, Color.rgb(245, 249, 255))
                else intArrayOf(Color.rgb(6, 18, 38), Color.rgb(9, 25, 49))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), if (isLight) Color.rgb(20, 30, 45) else Color.rgb(65, 125, 205))
            }
        }
        home.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(if (isTvDevice) 62 else 74)))
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
                listOf("Live TV", "Movies", "Series", "Settings").indexOf(section).coerceAtLeast(0)
            )
        }
        target?.requestFocus()
    }

    private fun applyTvFocusStyle(view: View) {
        if (!isTvDevice) return
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            focusedView.foreground = if (hasFocus) GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), accent)
            } else null
            focusedView.animate().cancel()
            focusedView.animate().scaleX(if (hasFocus) 1.035f else 1f)
                .scaleY(if (hasFocus) 1.035f else 1f)
                .setDuration(110).start()
            if (hasFocus) focusedView.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, focusedView.width, focusedView.height), true
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isTvDevice) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (player != null) { playAdjacentChannel(1); return true }
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (player != null) { playAdjacentChannel(-1); return true }
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    if (playerScreen != null) { closePlayer(); return true }
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
            renderMoviesDevelopmentSection()
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

    private fun renderNativeMovies(showSeries: Boolean) {
        if (showSeries) {
            renderDevelopmentSection()
            return
        }
        val screen = NativeMoviesScreen(
            context = this,
            seriesMode = false,
            light = isLight,
            accent = accent,
            onPlay = { title, url -> play(Channel(title, url, "Movies")) }
        )
        contentArea.addView(screen.create(), LinearLayout.LayoutParams(-1, -1))
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

    private fun showAnimePlaybackLoading(title: String, show: Boolean) {
        runOnUiThread {
            if (!show) {
                (animeLoadingOverlay?.parent as? android.view.ViewGroup)?.removeView(animeLoadingOverlay)
                animeLoadingOverlay = null
                return@runOnUiThread
            }
            (animeLoadingOverlay?.parent as? android.view.ViewGroup)?.removeView(animeLoadingOverlay)
            val overlay = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
                elevation = dp(40).toFloat()
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
            }
            panel.addView(android.widget.ProgressBar(this).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { bottomMargin = dp(22) })
            panel.addView(TextView(this).apply {
                text = "LOADING"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            panel.addView(TextView(this).apply {
                text = title
                setTextColor(0xFFB8C7DD.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(8), 0, 0)
            })
            overlay.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            (window.decorView as? android.view.ViewGroup)?.addView(
                overlay, android.view.ViewGroup.LayoutParams(-1, -1)
            )
            animeLoadingOverlay = overlay
        }
    }

    private fun playSanyPlaybackRequest(request: SanyPlaybackRequest) {
        val title = request.title.ifBlank { "AniLight" }
        val provider = request.provider.ifBlank { "Extension" }
        val parsedUrl = runCatching { Uri.parse(request.url.trim()) }.getOrNull()
        if (parsedUrl?.scheme !in setOf("http", "https")) {
            showAnimePlaybackLoading("", false)
            toast("AniLight-এর stream link সঠিক নয়")
            android.util.Log.e("SANY-PLAYER", "Rejected AniLight URL: invalid or unsupported scheme")
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
                subtitleTracks = request.subtitles
            )
        } catch (error: Exception) {
            showAnimePlaybackLoading("", false)
            android.util.Log.e("SANY-PLAYER", "Could not initialize provider player", error)
            toast("Player চালু করা যায়নি: ${error.javaClass.simpleName}")
        }
    }

    private fun renderAniLightNative() {
        // Keep CloudStream DEX provider management internal. The verified providers
        // are synchronized and loaded automatically at app startup; no manual
        // provider/search entry is shown in the Anime Series UI.
        lateinit var screen: AniLightNativeScreen
        screen = AniLightNativeScreen(
            context = this,
            light = isLight,
            accent = accent,
            onPlay = { playbackRequest ->
                // AniLight resolves the source, request headers, audio language and
                // all matching subtitle tracks before handing the request to Media3.
                playSanyPlaybackRequest(playbackRequest)
            },
            onPlaybackLoading = { title, show -> showAnimePlaybackLoading(title, show) },
            onBrowseCloudStreamProviders = { showCloudStreamProviderBrowser() },
            onCloudStreamResult = { providerName, url, title ->
                showCloudStreamDetails(providerName, url, title)
            }
        )
        aniLightNativeScreen = screen
        contentArea.addView(screen.create(), LinearLayout.LayoutParams(-1, -1))
    }

    private fun showCloudStreamProviderBrowser() {
        // Retry sync/load for the complete built-in Anime provider set.
        val progress = AlertDialog.Builder(this@MainActivity)
            .setTitle("Anime Providers")
            .setMessage("GitHub থেকে Anime Providers যাচাই ও Load করা হচ্ছে…")
            .setCancelable(false)
            .create()
        progress.show()
        thread(name = "SANY-Anime-ProviderCheck") {
            val result = runCatching {
                val report = SanyExtensionRepositories.syncOfficialAnimePlugins(applicationContext)
                report.packages.forEach { packageFile ->
                    val loadResult = SanyCloudStreamPluginRuntime.load(applicationContext, packageFile)
                    if (!loadResult.success) {
                        android.util.Log.e("SANY-CS", "DEX load failed: ${packageFile.name}: ${loadResult.error}")
                    }
                }
                val providers = SanyCloudStreamPluginRuntime.providers()
                check(providers.isNotEmpty()) {
                    report.failures.joinToString("; ").ifBlank { "No Anime MainAPI provider was registered" }
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
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("CloudStream Provider নির্বাচন")
                        .setItems(providers.map { it.name }.toTypedArray()) { _, index ->
                            showCloudStreamSearch(providers[index].name)
                        }
                        .setNegativeButton("বন্ধ", null).show()
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

    private fun showCloudStreamSearch(providerName: String) {
        val input = EditText(this).apply { hint = "Anime title লিখুন"; setSingleLine(true); setPadding(dp(18), dp(12), dp(18), dp(12)) }
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
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(response.name)
                        .setItems(labels) { _, i ->
                            resolveCloudStreamEpisode(providerName, response.name, episodes[i].data)
                        }
                        .setNegativeButton("বন্ধ", null)
                        .show()
                }.onFailure { toast("Details error: ${it.message ?: it.javaClass.simpleName}"); android.util.Log.e("SANY-CS", "Load failed", it) }
            }
        }
    }

    private fun resolveCloudStreamEpisode(providerName: String, title: String, episodeData: String) {
        toast("Extracting video links…")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val result = runCatching { SanyCloudStreamPluginRuntime.resolvePlayback(providerName, title, episodeData) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { links ->
                    if (links.isEmpty()) toast("কোনো video source পাওয়া যায়নি")
                    else {
                        val labels = links.map { req -> listOfNotNull(req.quality, req.streamMimeType?.substringAfterLast('/'), req.provider).joinToString(" • ").ifBlank { req.url.substringBefore('?').takeLast(48) } }.toTypedArray()
                        AlertDialog.Builder(this@MainActivity).setTitle("$title — Sources").setItems(labels) { _, i -> playSanyPlaybackRequest(links[i]) }.setNegativeButton("বন্ধ", null).show()
                    }
                }.onFailure { toast("Extractor error: ${it.message ?: it.javaClass.simpleName}"); android.util.Log.e("SANY-CS", "Extractor failed", it) }
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
            toolbar.addView(checkerShortcut, LinearLayout.LayoutParams(-2, dp(36)).apply {
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
                    displayedLimit = 60
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
                    displayedLimit = 60
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
            channels.filter { !isMovie(it) && !isSeries(it) }
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
            val group = channel.group
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
                background = if (isLight) rounded(Color.WHITE, 10, Color.BLACK, 2) else glass(10)
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
                    displayedLimit = 60
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
                background = if (isLight) rounded(Color.WHITE, 12, Color.BLACK, 2) else glass(12)
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
                background = if (isLight) rounded(Color.WHITE, 10, Color.rgb(25, 31, 42), 1) else glass(10)
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
                startAnimation(android.view.animation.AlphaAnimation(0.22f, 1f).apply {
                    duration = 900L
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                    startOffset = (index % 6) * 110L
                })
            }
            watermarkLine.addView(liveDot, LinearLayout.LayoutParams(-2, -2))
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
            if (section == "Live TV" || section == "Movies") {
                row.setOnClickListener { play(channel) }
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        if (filtered.size > displayedLimit) {
            container.addView(actionButton("LOAD MORE  (${filtered.size - displayedLimit} LEFT)") {
                displayedLimit += 120
                renderChannelRows(container)
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8); bottomMargin = dp(12) })
        }
    }

    // Android TV gets a two-column, remote-friendly card grid. Phone rows remain unchanged.
    private fun renderTvChannelCards(container: LinearLayout, filtered: List<Channel>) {
        val visible = filtered.take(displayedLimit)
        visible.chunked(2).forEachIndexed { rowIndex, pair ->
            val cardRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pair.forEachIndexed { columnIndex, channel ->
                val itemIndex = rowIndex * 2 + columnIndex
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
                    } else if (isLight) rounded(Color.WHITE, 16, Color.BLACK, 2) else glass(16)
                    if (isPlayingChannel) {
                        elevation = dp(10).toFloat()
                        foreground = GradientDrawable().apply {
                            cornerRadius = dp(16).toFloat()
                            setStroke(dp(1), 0x99BCEBFF.toInt())
                        }
                    }
                    applyTvFocusStyle(this)
                    contentDescription = if (isPlayingChannel) "Currently playing: ${channel.name}" else "Play ${channel.name}"
                    if (section == "Live TV" || section == "Movies") setOnClickListener { play(channel) }
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
                    if (columnIndex == 0) rightMargin = dp(8) else leftMargin = dp(8)
                })
            }
            if (pair.size == 1) {
                cardRow.addView(View(this), LinearLayout.LayoutParams(0, dp(if (isTvDevice) 158 else 166), 1f).apply { leftMargin = dp(8) })
            }
            container.addView(cardRow, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(if (isTvDevice) 7 else 12)
            })
        }
        if (filtered.size > displayedLimit) {
            container.addView(actionButton("LOAD MORE  (${filtered.size - displayedLimit} LEFT)") {
                displayedLimit += 120
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
        val liveCandidates = if (section == "Live TV") liveTvVisibleCandidates() else channels
        return liveCandidates.filter { c ->
            val typeMatch = when (section) {
                "Movies" -> isMovie(c) || (selectedGroup != "All" && matchesMovieCategory(c, selectedGroup))
                "Series" -> isSeries(c)
                else -> !isMovie(c) && !isSeries(c)
            }
            val groupMatch = when {
                section == "Live TV" && selectedGroup == "Select category" -> false
                selectedGroup == "All" -> true
                section == "Series" && selectedGroup == "Anime" -> isAnime(c)
                section == "Movies" && selectedGroup in movieCategories -> matchesMovieCategory(c, selectedGroup)
                else -> c.group.equals(selectedGroup, true)
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
    private fun isAnime(c: Channel) = (c.group + " " + c.name + " " + c.url).lowercase().let {
        listOf("anime", "animation", "animé").any(it::contains)
    }
    private fun isSeries(c: Channel) = (c.group + " " + c.name + " " + c.url).lowercase().let {
        isAnime(c) || listOf("series", "season", "episode", "tv show", "tvshows").any(it::contains)
    }

    private fun renderBottomNav() {
        bottomNav.removeAllViews()
        listOf("Live TV" to "▣", "Movies" to "▤", "Series" to "▥", "Settings" to "⚙").forEach { (name, symbol) ->
            val active = section == name
            val item = LinearLayout(this).apply {
                isFocusable = true
                isFocusableInTouchMode = false
                applyTvFocusStyle(this)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = if (isLight) {
                    if (active) rounded(Color.rgb(229, 241, 255), 14, Color.BLACK, 2)
                    else rounded(Color.WHITE, 14, Color.BLACK, 1)
                } else if (active) glass(14) else rounded(Color.TRANSPARENT, 14)
                setPadding(dp(3), 0, dp(3), 0)
                translationY = -dp(3).toFloat()
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
                    displayedLimit = 60
                    // Reuse the existing shell and header; rebuild only the page content
                    // and the five small navigation items. This avoids a full-screen layout
                    // pass on every menu tap.
                    headerTitleView?.text = if (section == "Series") "Anime Series" else section
                    playlistSelectorContainer?.visibility =
                        if (section == "Live TV" || section == "Checker") View.VISIBLE else View.GONE
                    renderSection()
                    renderBottomNav()
                    if (isTvDevice) bottomNav.getChildAt(
                        listOf("Live TV", "Movies", "Series", "Settings").indexOf(section)
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
                item.addView(text(symbol, 20, if (active) accent else muted, true).apply { gravity = Gravity.CENTER })
                val navLabel = text(if (name == "Live TV") liveTvProgressLabel() else if (name == "Series") "Anime Series" else name, 12, if (active) accent else muted, active).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                }
                if (name == "Live TV") liveTvNavLabel = navLabel
                item.addView(navLabel)
            }
            bottomNav.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
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
                    applySystemBars()
                    renderHome()
                }.setNegativeButton("CANCEL", null).show()
        }
        box.addView(text("PLAYBACK SETTINGS", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        settingsToggleCard(
            box,
            "High-Speed Streaming",
            "Prioritize maximum available video quality and a larger playback buffer",
            "high_speed_streaming",
            true
        ) { enabled ->
            if (enabled) prefs.edit().putString("network_buffer_size", "high").apply()
            applyVideoQuality()
            toast(if (enabled) "High-speed streaming enabled — buffer applies next playback" else "Standard streaming restored")
        }
        settingsCard(box, "Playback speed", "Choose normal or faster/slower video playback") {
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
        settingsToggleCard(box, "Auto Play", "Automatically start a stream when you select a channel", "auto_play", true)
        settingsCard(box, "Video Quality", "Choose Auto, 480p, 720p or 1080p") {
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
        settingsToggleCard(box, "Hardware Decoder", "Prefer hardware video decoding when supported; OFF prefers software decoding", "hardware_decoder", true)
        settingsCard(box, "Buffer Control", "Choose buffering for slower or faster connections") {
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
        settingsToggleCard(box, "Auto Retry on Failure", "Retry a failed stream up to 2 times automatically", "auto_retry_playback", true)
        box.addView(text("PLAYER & PLAYLIST", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        settingsCard(box, "Extensions & Repositories", "Browse CloudStream-compatible repository catalogues and manage repository URLs") { showExtensionManager() }
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
        box.addView(text("ADDITIONAL SETTINGS", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        settingsCard(box, "Update EPG data", "Manually refresh all 3 XMLTV sources now") {
            refreshEpgSources(true)
        }
        settingsToggleCard(box, "Auto add EPG data", "Detect playlist EPG URL automatically; daily EPG refresh remains scheduled", "auto_add_epg", true)
        settingsCard(box, "EPG sources (3)", "View the 3 built-in feeds and daily update schedule") {
            showBuiltInEpgSources()
        }
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
            orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14))
            background = glass(12); setOnClickListener { action() }
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
                if (::contentArea.isInitialized && section == "Live TV") {
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
        if (::contentArea.isInitialized && section == "Live TV") renderListOnly()

        // A fixed number of workers pull the next channel from an atomic index.
        // This avoids creating thousands of queued Runnables for huge playlists.
        val nextIndex = java.util.concurrent.atomic.AtomicInteger(0)
        // Scale checker concurrency by Android version.
        // Android 14+ uses 48 workers; Android 10-13 uses 32.
        // Android 6-9 uses 8 workers.
        // Actual throughput still depends on device resources and server throttling.
        val maxWorkers = when {
            Build.VERSION.SDK_INT >= 34 -> 48
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> 32
            else -> 8
        }
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
                        if (!isFinishing && section == "Live TV") renderHome()
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
        // FTP needs credentials and a protocol-specific transfer; don't mark it offline
        // merely because the background HTTP checker cannot authenticate it.
        if (streamUrl.startsWith("ftp://", true)) {
            return "Working" to "FTP — login required when playing"
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
        subtitleTracks: List<SanySubtitleTrack> = emptyList()
    ) {
        if (section != "Live TV" && section != "Movies" && section != "Series") return
        if (channel.url.isBlank()) {
            toast("এই চ্যানেলের stream link নেই")
            return
        }
        val streamUrl = channel.url.trim()
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
        // Move the single active-channel highlight immediately when playback selection changes.
        if (::listContainer.isInitialized && (section == "Live TV" || section == "Movies")) {
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
        val httpFactory = DefaultHttpDataSource.Factory()
            // Keep standard IPTV headers, and add provider-specific headers only
            // for AniLight playback (including HLS segments and subtitle requests).
            .setUserAgent(
                requestHeaders["User-Agent"]
                    ?: "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .setDefaultRequestProperties(mediaHeaders)
            .setConnectTimeoutMs(if (highSpeedStreaming) 10000 else 5000)
            .setReadTimeoutMs(if (highSpeedStreaming) 30000 else 15000)
            .setAllowCrossProtocolRedirects(true)

        val streamDataSourceFactory: DataSource.Factory = if (isFtpStream) {
            FtpDataSource.Factory(
                resolvedFtpCredentials?.first ?: "anonymous",
                resolvedFtpCredentials?.second ?: ""
            )
        } else httpFactory

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
                if (section == "Series") {
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setPreferredAudioLanguages("bn", "hi", "en")
                            // Audio preference: Bengali, then Hindi, then English. Subtitles remain off by default.
                            .setMaxVideoSize(1920, 1080)
                            .setMaxVideoBitrate(Int.MAX_VALUE)
                    )
                }
                applyVideoQuality(selector)
            })
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        if (highSpeedStreaming) 3000 else when (prefs.getString("network_buffer_size", defaultNetworkBufferMode)) {
                            "low" -> 1500
                            "high" -> 3500
                            else -> 2500
                        },
                        if (highSpeedStreaming) 24000 else when (prefs.getString("network_buffer_size", defaultNetworkBufferMode)) {
                            "low" -> 12000
                            "high" -> 30000
                            else -> 20000
                        },
                        if (highSpeedStreaming) 1200 else when (prefs.getString("network_buffer_size", defaultNetworkBufferMode)) {
                            "low" -> 750
                            "high" -> 1500
                            else -> 1000
                        },
                        // Media3 requires minBufferMs >= bufferForPlaybackAfterRebufferMs.
                        // Keep the rebuffer threshold below the selected minimum for every mode.
                        if (highSpeedStreaming) 2500 else when (prefs.getString("network_buffer_size", defaultNetworkBufferMode)) {
                            "low" -> 1000
                            "high" -> 3000
                            else -> 2000
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
                        runOnUiThread {
                            if (section == "Series") showAnimePlaybackLoading("", false)
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
                                        player?.prepare()
                                        player?.playWhenReady = prefs.getBoolean("auto_play", true)
                                    }
                                }, 1800L)
                            }
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            if (section == "Series") showAnimePlaybackLoading("", false)
                            playbackRetryCount = 0
                            animeAutoNextInProgress = false
                            refreshPictureInPictureParams()
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
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Enable PiP auto-entry only while an actual video is playing.
                        // Pausing/stopping immediately unregisters the PiP entry path.
                        refreshPictureInPictureParams()
                    }
                })
                player = created
            }

        val screen = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val isAnimeEpisode = section == "Series"
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
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        playerView = view
        updateHomeHeaderVisibility()
        screen.addView(view, FrameLayout.LayoutParams(-1, dp(235), Gravity.TOP))
        animeEpisodePanel = if (isAnimeEpisode) aniLightNativeScreen?.createPlayerEpisodePanel() else null
        animeEpisodePanel?.let { episodePanel ->
            episodePanel.visibility = View.GONE
            screen.addView(episodePanel, FrameLayout.LayoutParams(-1, -1, Gravity.END))
        }
        if (isAnimeEpisode) {
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xA6101A2A.toInt(), 0xB20A1220.toInt())
            ).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0x884D8DDA.toInt())
            }
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
        channelIdentity.visibility = if (isAnimeEpisode) View.GONE else View.VISIBLE
        previousButton.visibility = if (isAnimeEpisode) View.GONE else View.VISIBLE
        nextButton.visibility = if (isAnimeEpisode) View.GONE else View.VISIBLE
        if (!isAnimeEpisode) transportRow.addView(channelIdentity, LinearLayout.LayoutParams(dp(126), -1))
        listOf(previousButton, playPauseButton, nextButton).forEachIndexed { index, button ->
            button.textSize = if (index == 1) 21f else 19f
            button.typeface = if (index == 1) Typeface.create("sans-serif-light", Typeface.NORMAL) else Typeface.DEFAULT
            if (button.visibility == View.VISIBLE) {
                transportRow.addView(button, LinearLayout.LayoutParams(0, dp(27), 1f).apply {
                    if (index == 1 && !isAnimeEpisode) { leftMargin = dp(4); rightMargin = dp(4) }
                })
            }
        }
        controlsPanel.addView(transportRow, LinearLayout.LayoutParams(-1, dp(27)))
        if (isAnimeEpisode) {
            transportRow.visibility = View.GONE
            transportRow.removeView(playPauseButton)
            playPauseButton.textSize = 34f
            playPauseButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000)
                setStroke(dp(1), 0x99FFFFFF.toInt())
            }
            animeCenterPlayButton = playPauseButton
        }

        // Anime Series-only actions. Live TV and Movies keep their existing player UI.
        val animeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = if (isAnimeEpisode) View.VISIBLE else View.GONE
        }
        var animeControlsLocked = false
        fun animeAction(text: String, description: String, action: () -> Unit) =
            playerAction(text, description, action).apply {
                textSize = 12f
                minHeight = dp(30)
                setPadding(dp(4), 0, dp(4), 0)
            }
                val sourceAction = animeAction("☷  Source", "Show current Anime stream source") {
            val host = try { java.net.URI(channel.url).host ?: "Unknown host" } catch (_: Exception) { "Unknown host" }
            AlertDialog.Builder(this).setTitle("Anime stream source")
                .setMessage("Current source: $host")
                .setPositiveButton("OK", null).show()
        }
        val tracksAction = animeAction("▥  Tracks", "Change Anime audio language") {
            showAnimeTrackSelection(C.TRACK_TYPE_AUDIO, "Audio language", false)
        }
        var onAnimeFullscreenToggle: (() -> Unit)? = null
        val fullscreenAction = animeAction("⛶  Fit Screen", "Switch between Fit Screen and Full Screen") {
            onAnimeFullscreenToggle?.invoke() ?: setPlayerFullscreen(!isPlayerFullscreen)
        }
        // Replace Next Episode with the Full Screen shortcut in the Anime action strip.
        // Play/Pause remains available only as the large centre overlay.
        listOf(sourceAction, tracksAction, fullscreenAction).forEach { action ->
            animeActions.addView(action, LinearLayout.LayoutParams(0, dp(32), 1f))
        }
        controlsPanel.addView(animeActions, LinearLayout.LayoutParams(-1, dp(32)))

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
            val isAnimePlayback = section == "Series"
            val options = if (isAnimePlayback)
                arrayOf("Playback speed", "Audio language", "Subtitle", "Video quality")
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
                    showAnimeTrackSelection(C.TRACK_TYPE_AUDIO, "Audio language", false)
                } else if (isAnimePlayback && which == 2) {
                    showAnimeTrackSelection(C.TRACK_TYPE_TEXT, "Subtitles", true)
                } else if (isAnimePlayback && which == 3) {
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
        if (!isAnimeEpisode) {
            infoRow.addView(minimizeButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply {
                leftMargin = dp(5)
            })
        }
        // Fit Screen stays in its original bottom control row for Anime as well.
        // Anime gets its Full Screen control in the action strip above;
        // keep the original bottom-row button for non-Anime players only.
        if (!isAnimeEpisode) {
            infoRow.addView(fullscreenButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply {
                leftMargin = dp(5)
            })
        }
        if (isAnimeEpisode) {
            // Only Pause/Resume is moved to the centre of the video.
            screen.addView(playPauseButton, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
        }
        controlsPanel.addView(infoRow, LinearLayout.LayoutParams(-1, dp(38)))
        if (isAnimeEpisode) {
            // Reference layout order: timeline, action strip, then time / utility controls.
            controlsPanel.removeView(animeActions)
            controlsPanel.removeView(progressBar)
            controlsPanel.removeView(infoRow)
            // Slimmer Anime control dock: compact timeline and action row.
            controlsPanel.addView(progressBar, LinearLayout.LayoutParams(-1, dp(10)))
            controlsPanel.addView(animeActions, LinearLayout.LayoutParams(-1, dp(28)))
            controlsPanel.addView(infoRow, LinearLayout.LayoutParams(-1, dp(34)))
        }
        screen.addView(controlsPanel, FrameLayout.LayoutParams(-1, if (isAnimeEpisode) dp(82) else dp(88), Gravity.TOP).apply {
            topMargin = dp(155)
            leftMargin = dp(8); rightMargin = dp(8)
        })

        val controlsHandler = Handler(Looper.getMainLooper())
        val animeControlsAutoHideDelay = 3000L
        // Anime Series uses one synchronized overlay group: centre Play/Pause,
        // top title, and the complete lower control dock always show/hide together.
        val hideControls = Runnable {
            // Keep the complete control group visible while paused so Resume is accessible.
            if (isAnimeEpisode && !p.isPlaying) return@Runnable
            val groupedOverlays = if (isAnimeEpisode)
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
            val groupedOverlays = if (isAnimeEpisode)
                listOfNotNull(controlsPanel, playPauseButton, animePlayerTitleOverlay)
            else listOf(controlsPanel)
            groupedOverlays.forEach { overlay ->
                overlay.animate().cancel()
                overlay.visibility = View.VISIBLE
                overlay.alpha = 1f
            }
            if (isAnimeEpisode) {
                playPauseButton.text = if (p.isPlaying) "Ⅱ" else "▶"
            }
            if (autoHide && (isAnimeEpisode.not() || p.isPlaying)) {
                controlsHandler.postDelayed(
                    hideControls, if (isAnimeEpisode) animeControlsAutoHideDelay else 2500L
                )
            }
        }
        if (isAnimeEpisode) {
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
                if (isAnimeEpisode) {
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
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, section == "Series")
            .apply { if (section == "Series") setPreferredAudioLanguages("bn", "hi", "en") }
            .build()
        val mediaItem = itemBuilder.build()
        val animeResumePosition = if (section == "Series") prefs.getLong(animeResumePreferenceKey(channel), 0L).coerceAtLeast(0L) else 0L
        if (animeResumePosition > 0L) p.setMediaItem(mediaItem, animeResumePosition) else p.setMediaItem(mediaItem)
        p.setPlaybackSpeed(prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f))
        p.prepare()
        p.playWhenReady = startPlayback
        if (isAnimeEpisode && startPlayback) animeCenterPlayButton?.visibility = View.GONE
        prefs.edit().putString("last_channel", streamUrl).apply()
    }


    private fun showAnimeTrackSelection(trackType: Int, title: String, allowDisable: Boolean) {
        if (section != "Series") return
        val activePlayer = player ?: return
        if (trackType == C.TRACK_TYPE_TEXT) {
            activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
                .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        }
        TrackSelectionDialogBuilder(this, title, activePlayer, trackType)
            .setAllowAdaptiveSelections(false)
            .setShowDisableOption(allowDisable)
            .build()
            .show()
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
        // Auto mode caps Anime at 720p to reduce sustained decoder/GPU load.
        // Explicit 480p and 1080p selections remain available to the viewer.
        if (section == "Series") {
            // Anime always starts in adaptive Auto quality. Audio track preference is Bengali > Hindi > English.
            // Subtitle tracks remain disabled until the viewer explicitly enables them inside the player.
            builder.setPreferredAudioLanguages("bn", "hi", "en")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearVideoSizeConstraints()
                .setMaxVideoBitrate(Int.MAX_VALUE)
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
                } else if (isLight) rounded(Color.WHITE, 12, Color.BLACK, 1) else glass(12)
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
        window.decorView.systemUiVisibility = if (enabled) {
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        } else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun closePlayer(keepOrientation: Boolean = false) {
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
            section = playbackReturnSection
            selectedGroup = playbackReturnGroup
            searchQuery = playbackReturnSearch
            if (section == "Live TV") {
                liveTvSelectedGroup = selectedGroup
                liveTvSearchQuery = searchQuery
                prefs.edit().putString("live_tv_group", selectedGroup)
                    .putString("live_tv_search", searchQuery).apply()
            }
            if (::listContainer.isInitialized) {
                (listContainer.parent as? ScrollView)?.post {
                    (listContainer.parent as? ScrollView)?.scrollTo(0, playbackReturnScrollY)
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

        var width = videoSize?.width ?: 0
        var height = videoSize?.height ?: 0
        if (videoSize?.unappliedRotationDegrees == 90 || videoSize?.unappliedRotationDegrees == 270) {
            val swap = width; width = height; height = swap
        }
        val ratio = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.5f, 2.39f) else (16f / 9f)
        // PiP entry is enabled only for active video playback, never audio-only,
        // paused, stopped, or empty-player states.
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
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
        playbackReturnSection = previous.section
        playbackReturnGroup = previous.selectedGroup
        playbackReturnSearch = previous.searchQuery
        playbackReturnScrollY = previous.scrollY
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
        // Video navigation has priority over page navigation.
        if (playerView != null && isPlayerFullscreen) {
            setPlayerFullscreen(false)
            lastBackPressedAt = 0L
            return
        }

        if (section == "Movies" && moviesWebView?.canGoBack() == true) {
            moviesWebView?.goBack()
            lastBackPressedAt = 0L
            return
        }

        // AniLight owns its own catalogue/detail navigation. Keep Back inside
        // Anime Series until its home screen is reached; only then switch to Live TV.
        if (section == "Series") {
            if (aniLightNativeScreen?.handleBack() == true) {
                lastBackPressedAt = 0L
                return
            }
            returnToLiveTvHome(keepPlayback = playerView != null && player != null)
            return
        }

        // Live TV Back flow is deliberately isolated from Movies and Anime Series.
        // First Back from fullscreen is handled above and only changes the player to FIT.
        // While inline, unwind one navigation-history page at a time without touching playback.
        if (section == "Live TV" && playerView != null) {
            if (navigationHistory.isNotEmpty()) {
                lastBackPressedAt = 0L
                restorePreviousNavigation()
            } else if (selectedGroup != "Select category" || searchQuery.isNotEmpty()) {
                // Once history is exhausted, show the Live TV category directory and keep video alive.
                returnToLiveTvHome(keepPlayback = true)
            } else {
                // At the category directory, this Back closes only the player.
                // The next two Back presses can then exit the app.
                closePlayer()
                lastBackPressedAt = 0L
            }
            return
        }

        if (playerView != null) {
            // With playback active, every non-Live-TV section also unwinds its
            // own page history first. Never stop the video just because the user
            // presses Back from Settings or another menu.
            if (navigationHistory.isNotEmpty()) {
                lastBackPressedAt = 0L
                restorePreviousNavigation()
            } else {
                // The final destination before app exit is always the Live TV
                // category directory; keep the current stream running there.
                returnToLiveTvHome(keepPlayback = true)
            }
            return
        }

        // Settings, Checker, Series and other pages unwind one screen at a time.
        // Once their history is exhausted, return to the Live TV home before exit.
        if (navigationHistory.isNotEmpty()) {
            lastBackPressedAt = 0L
            restorePreviousNavigation()
            return
        }

        if (section != "Live TV" || selectedGroup != "Select category" || searchQuery.isNotEmpty()) {
            returnToLiveTvHome()
            return
        }

        // Only the Live TV home can be exited, using two consecutive Back presses.
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

    // Shared translucent glass surface used by menu cards, controls, pills and icon buttons.
    // Native Android View backgrounds cannot blur arbitrary sibling content on every API level,
    // so use layered alpha gradients, a bright top edge and a soft tinted outline for a consistent glass look.
    // Shared translucent glass surface used by menu cards, controls, pills and icon buttons.
    // Native Android View backgrounds cannot blur arbitrary sibling content on every API level,
    // so use layered alpha gradients, a bright top edge and a soft tinted outline for a consistent glass look.
    private fun glass(radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (isLight) intArrayOf(
            Color.argb(205, 255, 255, 255),
            Color.argb(168, 232, 242, 255),
            Color.argb(188, 255, 255, 255)
        ) else intArrayOf(
            Color.argb(178, 35, 58, 88),
            Color.argb(148, 12, 25, 46),
            Color.argb(172, 32, 43, 75)
        )
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(
            dp(1),
            if (isLight) Color.argb(210, 255, 255, 255)
            else Color.argb(185, 145, 195, 255)
        )
    }
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