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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import java.net.URL
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
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
    private val pickFile = 41
    private var channels = listOf<Channel>()
    private val streamChecks = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    @Volatile private var checkerRunning = false
    private val checkerProgressCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var checkerFilter = "All"
    private var checkerDisplayedLimit = 250
    private var checkerSummaryView: TextView? = null
    private var checkerRunButton: TextView? = null
    private var checkerListAdapter: android.widget.BaseAdapter? = null
    private val checkerUiHandler = Handler(Looper.getMainLooper())
    private val checkerUiRefreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val permanentPlaylistUrl = "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u"
    private val playlistUrls = mutableListOf<String>()
    private var selectedGroup = "All"
    private var section = "Live TV"
    private var searchQuery = ""
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentChannel: Channel? = null
    private var previousChannel: Channel? = null
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var contentArea: LinearLayout
    private lateinit var search: EditText
    private lateinit var countLabel: TextView
    private lateinit var categoryRow: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var listContainer: LinearLayout
    private var playerScreen: FrameLayout? = null
    private var displayedLimit = 60
    private var playerHeader: LinearLayout? = null
    private var isPlayerFullscreen = false
    private var playerFullscreenButton: TextView? = null
    private var headerTitleView: TextView? = null
    private var liveTvNavLabel: TextView? = null
    private var pausedForBackground = false
    private var maintenanceOverlay: View? = null
    private val maintenanceHandler = Handler(Looper.getMainLooper())
    private val maintenanceConfigUrl = "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/maintenance.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSanyCrashLogger()
        EpgRepository.scheduleDaily(this)
        val lastEpgUpdate = prefs.getString("epg_last_updated", "0")?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - lastEpgUpdate >= 24L * 60L * 60L * 1000L) {
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
        section = savedInstanceState?.getString("ui_section") ?: prefs.getString("ui_section", "Live TV") ?: "Live TV"
        selectedGroup = savedInstanceState?.getString("ui_group") ?: prefs.getString("ui_group", "All") ?: "All"
        searchQuery = savedInstanceState?.getString("ui_search") ?: prefs.getString("ui_search", "") ?: ""
        val resumeStream = if (restoringBackground) prefs.getString("resume_stream", null) else null
        prefs.edit().putBoolean("resume_without_splash", false).apply()
        val cachedPlaylist = savedPlaylist?.let { playlistCacheFile(it) }
        if (cachedPlaylist?.exists() == true) {
            try { channels = M3uParser.parse(cachedPlaylist.readText()) } catch (_: Exception) { channels = emptyList() }
        }
        // Android 15 (targetSdk 35) draws edge-to-edge by default. Keep the app
        // inside the safe system-bar area where possible and paint all exposed
        // system/decor regions with the currently selected theme color.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        applySystemBars()
        buildShell(showSplash = !restoringBackground)
        checkMaintenanceMode()
        showPreviousCrashReport()
        if (!restoringBackground) {
            home.alpha = 0f
            home.translationY = dp(14).toFloat()
            home.scaleX = 0.985f
            home.scaleY = 0.985f
            home.post {
                home.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(420).start()
            }
        }
        restoreStreamCheckResults()
        if (channels.isNotEmpty()) startPlaylistCheck(false)
        if (channels.isEmpty() && !savedPlaylist.isNullOrBlank()) loadFromUrl(savedPlaylist, false)
        else if (!resumeStream.isNullOrBlank() && channels.isNotEmpty()) {
            channels.firstOrNull { it.url == resumeStream }?.let { channel ->
                Handler(Looper.getMainLooper()).post { play(channel) }
            }
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
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 13, 32), Color.rgb(20, 31, 62), Color.rgb(8, 12, 27))
            )
        }
        val icon = TextView(this).apply {
            text = "🛠️"
            textSize = 54f
            gravity = Gravity.CENTER
        }
        overlay.addView(icon, LinearLayout.LayoutParams(-1, dp(82)))
        val title = TextView(this).apply {
            text = "SANY IPTV"
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        overlay.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val heading = TextView(this).apply {
            text = "App Under Maintenance"
            textSize = 19f
            setTextColor(Color.rgb(255, 195, 75))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        overlay.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        val detail = TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(Color.rgb(220, 228, 242))
            gravity = Gravity.CENTER
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        overlay.addView(detail, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
            bottomMargin = dp(22)
        })
        val status = TextView(this).apply {
            text = "MAINTENANCE MODE ACTIVE"
            textSize = 12f
            setTextColor(Color.rgb(255, 195, 75))
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        overlay.addView(status, LinearLayout.LayoutParams(-1, -2))
        val copy = TextView(this).apply {
            text = "COPY THIS DEVICE ID (ADMIN)"
            textSize = 12f
            setTextColor(Color.rgb(120, 195, 255))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(18), dp(12), dp(8))
            setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SANY IPTV device ID", deviceId))
                toast("Device ID copied")
            }
        }
        overlay.addView(copy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
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
        window.statusBarColor = bg
        window.navigationBarColor = bg
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
            topMargin = dp(95)
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
            translationY = dp(-4).toFloat()
        }
        center.addView(welcome)

        val thankYou = text("Thank you for choosing SANY IPTV", 12, Color.rgb(126, 220, 255), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.025f
            setPadding(0, dp(7), 0, dp(14))
            alpha = 0f
        }
        center.addView(thankYou)

        // Static welcome text colors: color/lighting animation intentionally disabled.
        welcome.setTextColor(if (isLight) Color.rgb(25, 55, 100) else Color.WHITE)
        thankYou.setTextColor(if (isLight) Color.rgb(0, 130, 190) else Color.rgb(126, 220, 255))

        val loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(64, 170, 255))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(85, 210, 230, 255))
        }
        center.addView(loadingBar, LinearLayout.LayoutParams(dp(230), dp(7)))
        val percentLabel = text("0%", 13, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
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
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed && playerScreen == null) checkForAppUpdate(false)
        }, 700L)
    }

    private fun renderHome() {
        home.removeAllViews()
        // Preserve the exact welcome-message slot as blank space on the Home screen.
        val homeWelcomeSpacer = text(" ", 14, accent, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.025f
            setPadding(dp(8), dp(5), dp(8), dp(9))
            visibility = View.INVISIBLE
        }
        home.addView(homeWelcomeSpacer, LinearLayout.LayoutParams(-1, -2))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isTvDevice) 16 else 20), dp(if (isTvDevice) 10 else 18), dp(if (isTvDevice) 16 else 20), dp(if (isTvDevice) 8 else 12))
            background = glass(22)
            // Premium 3D glass header: perspective, depth and a soft raised-card finish.
            cameraDistance = 11000f * resources.displayMetrics.density
            rotationX = 2.2f
            translationZ = dp(10).toFloat()
            elevation = dp(14).toFloat()
        }
        val brandLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // The header logo is intentionally removed; keep the brand name clean and prominent.
        val brand = text("SANY IPTV", if (isTvDevice) 24 else 30, accent, true).apply {
            letterSpacing = 0.06f
            setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.argb(150, 55, 145, 255))
            setPadding(dp(8), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            translationZ = dp(10).toFloat()
        }
        brandLine.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        brandLine.addView(pill(if (channels.isEmpty()) "NOT CONNECTED" else "● CONNECTED", if (channels.isEmpty()) muted else accent))
        top.addView(brandLine)
        top.addView(text("PREMIUM IPTV EXPERIENCE", 12, white, true).apply {
            letterSpacing = 0.025f
            setPadding(dp(8), dp(1), 0, dp(7))
        })
        top.addView(text("✦  FAST  •  SMOOTH  •  UNLIMITED", 10, muted, true).apply { letterSpacing = 0.10f; setPadding(0, dp(1), 0, dp(14)) })
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerTitleView = text(if (section == "Movies") "Movies" else section, 23, white, true)
        titleRow.addView(headerTitleView, LinearLayout.LayoutParams(0, -2, 1f))
        // Keep the Home header clean: the four quick-action controls are hidden as requested.
        top.addView(titleRow)
        home.addView(top, LinearLayout.LayoutParams(-1, -2))
        // Animate the header into a front-facing 3D position when the home screen opens.
        top.alpha = 0f
        top.translationY = dp(-8).toFloat()
        top.post {
            top.animate().alpha(1f).translationY(0f).rotationX(0f)
                .setDuration(620).setInterpolator(DecelerateInterpolator(1.5f)).start()
        }

        contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), 0)
            setBackgroundColor(bg)
        }
        home.addView(contentArea, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(8), dp(5), dp(8))
            background = glass(18)
        }
        home.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(if (isTvDevice) 56 else 66)))
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
                listOf("Live TV", "Checker", "Movies", "Series", "Settings").indexOf(section).coerceAtLeast(0)
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
        if (section == "Settings") {
            renderSettings()
            return
        }
        if (section == "Series") {
            renderSeriesComingSoon()
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

        search = EditText(this).apply {
            hint = "⌕   Search channels, movies, series..."
            setTextColor(white); setHintTextColor(muted); textSize = 14f
            isSingleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            background = glass(12)
            setText(searchQuery)
        }
        contentArea.addView(search, LinearLayout.LayoutParams(-1, dp(if (isTvDevice) 42 else 48)))
        search.addTextChangedListener(SimpleTextWatcher { searchQuery = it; displayedLimit = 60; renderListOnly() })

        val categoriesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(bg)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        categoriesScroll.addView(categoryRow)
        contentArea.addView(categoriesScroll, LinearLayout.LayoutParams(-1, dp(if (isTvDevice) 42 else 52)).apply { topMargin = dp(if (isTvDevice) 4 else 8) })
        renderCategories()

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
        renderChannelRows(listContainer)
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

    private fun renderCategories() {
        categoryRow.removeAllViews()
        val groups = listOf("All") + channels.asSequence().map { it.group }.distinct().filter { it.isNotBlank() }.take(250).sorted().toList()
        val options = when (section) {
            "Movies" -> listOf("All") + movieCategories + groups.drop(1).filterNot { it in movieCategories }
            "Series" -> listOf("All", "Anime") + groups.drop(1).filterNot { it.equals("Anime", true) }
            else -> groups
        }
        options.forEach { item ->
            val active = selectedGroup == item
            val chip = text(item, 12, if (active) bg else muted, true).apply {
                isFocusable = true
                gravity = Gravity.CENTER
                setPadding(dp(15), 0, dp(15), 0)
                applyTvFocusStyle(this)
                background = if (active) rounded(Color.argb(220, 0, 170, 130), 30, Color.argb(160, 160, 255, 230), 1)
                    else glass(30)
                setOnClickListener {
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(65).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                        selectedGroup = item; displayedLimit = 60; renderListOnly()
                    }.start()
                }
            }
            categoryRow.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(7) })
        }
    }

    private fun renderListOnly() {
        if (!::contentArea.isInitialized || section == "Settings" || channels.isEmpty()) return
        if (!::listContainer.isInitialized) return
        renderCategories()
        renderChannelRows(listContainer)
    }

    private fun renderChannelRows(container: LinearLayout) {
        container.removeAllViews()
        val filtered = filteredChannels()
        countLabel.text = "${filtered.size} ITEMS  •  ${channels.size} IN PLAYLIST"
        if (filtered.isEmpty()) {
            val message = if (section == "Live TV" && checkerRunning) "Checking channels… ${liveTvProgressLabel()}" else "No working channels found"
            container.addView(text(message, 15, muted, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(35), 0, dp(35)) })
            return
        }
        if (isTvDevice) {
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
                logo.tag = logoUrl
                thread {
                    try {
                        val connection = URL(logoUrl).openConnection().apply {
                            connectTimeout = 8000
                            readTimeout = 8000
                        }
                        val bitmap = connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                        if (bitmap != null) runOnUiThread {
                            if (logo.tag == logoUrl) {
                                logo.setImageBitmap(bitmap)
                                fallbackLogo.visibility = View.GONE
                            }
                        }
                    } catch (_: Exception) { /* Keep the channel initial as fallback. */ }
                }
            }
            row.addView(logoFrame, LinearLayout.LayoutParams(dp(46), dp(46)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
            details.addView(text(channel.name, 14, white, true))
            details.addView(text(channel.group.ifBlank { "Other" }, 11, muted).apply { setPadding(0, dp(4), 0, 0) })
            // Subtle channel watermark beneath each channel label.
            val watermarkLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                alpha = 0.42f
                setPadding(0, dp(4), 0, 0)
            }
            watermarkLine.addView(ImageView(this).apply {
                setImageResource(com.sany.iptv.R.drawable.sany_logo_icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "SANY IPTV watermark"
            }, LinearLayout.LayoutParams(dp(11), dp(11)))
            watermarkLine.addView(text("SANY IPTV", 8, accent, true).apply {
                letterSpacing = 0.12f
                setPadding(dp(4), 0, 0, 0)
            })
            details.addView(watermarkLine)
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            // A gentle 3D flip gives the actual channel logo a premium animated entrance.
            logoFrame.cameraDistance = 9000f * resources.displayMetrics.density
            logoFrame.rotationY = -72f
            logoFrame.scaleX = 0.82f
            logoFrame.scaleY = 0.82f
            logoFrame.animate().rotationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay((index % 8) * 45L)
                .setDuration(620L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
            row.setOnClickListener {
                row.animate().alpha(0.68f).scaleX(0.985f).scaleY(0.985f).setDuration(75).withEndAction { play(channel) }.start()
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
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = glass(16)
                    applyTvFocusStyle(this)
                    contentDescription = "Play ${channel.name}"
                    setOnClickListener { play(channel) }
                }
                val topLine = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topLine.addView(text((itemIndex + 1).toString().padStart(2, '0'), 13, accent, true))
                topLine.addView(text("  •  ${channel.group.ifBlank { "Other" }}", 11, muted))
                card.addView(topLine)
                card.addView(text(channel.name, 17, white, true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(8), 0, dp(4))
                })
                card.addView(text("▶  PLAY CHANNEL", 10, accent, true).apply {
                    letterSpacing = 0.06f
                    setPadding(0, dp(5), 0, 0)
                })
                cardRow.addView(card, LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                    if (columnIndex == 0) rightMargin = dp(8) else leftMargin = dp(8)
                })
            }
            if (pair.size == 1) {
                cardRow.addView(View(this), LinearLayout.LayoutParams(0, dp(92), 1f).apply { leftMargin = dp(8) })
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

    private fun filteredChannels(): List<Channel> {
        return channels.filter { c ->
            // Show every Live TV channel while the playlist is first loading/checking.
            // Once the check is complete, keep only channels reported as Working.
            // Starting a re-check clears results and temporarily restores the full list.
            val liveTvHealthMatch = section != "Live TV" ||
                (!checkerRunning && (streamChecks.isEmpty() || streamChecks[c.url]?.first == "Working"))
            val typeMatch = when (section) {
                "Movies" -> isMovie(c) || (selectedGroup != "All" && matchesMovieCategory(c, selectedGroup))
                "Series" -> isSeries(c)
                else -> !isMovie(c) && !isSeries(c)
            }
            val groupMatch = when {
                selectedGroup == "All" -> true
                section == "Series" && selectedGroup == "Anime" -> isAnime(c)
                section == "Movies" && selectedGroup in movieCategories -> matchesMovieCategory(c, selectedGroup)
                else -> c.group.equals(selectedGroup, true)
            }
            val q = searchQuery.trim().lowercase()
            liveTvHealthMatch && typeMatch && groupMatch &&
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

    private fun isMovie(c: Channel) = (c.group + " " + c.name + " " + c.url).lowercase().let {
        listOf("movie", "movies", "vod", "film", "4k film").any(it::contains) || c.url.substringBefore("?").lowercase().matches(Regex(".*\\.(mp4|mkv|avi|mov)$"))
    }
    private fun isAnime(c: Channel) = (c.group + " " + c.name + " " + c.url).lowercase().let {
        listOf("anime", "animation", "animé").any(it::contains)
    }
    private fun isSeries(c: Channel) = (c.group + " " + c.name + " " + c.url).lowercase().let {
        isAnime(c) || listOf("series", "season", "episode", "tv show", "tvshows").any(it::contains)
    }

    private fun renderBottomNav() {
        bottomNav.removeAllViews()
        listOf("Live TV" to "▣", "Checker" to "✓", "Movies" to "▤", "Series" to "▥", "Settings" to "⚙").forEach { (name, symbol) ->
            val active = section == name
            val item = LinearLayout(this).apply {
                isFocusable = true
                isFocusableInTouchMode = false
                applyTvFocusStyle(this)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = if (active) glass(14) else rounded(Color.TRANSPARENT, 14)
                setPadding(dp(3), dp(4), dp(3), dp(4))
                setOnClickListener {
                    if (section == name) return@setOnClickListener
                    // Switch immediately; keep the transition animation lightweight.
                    section = name
                    selectedGroup = "All"
                    searchQuery = ""
                    displayedLimit = 60
                    // Reuse the existing shell and header; rebuild only the page content
                    // and the five small navigation items. This avoids a full-screen layout
                    // pass on every menu tap.
                    headerTitleView?.text = if (section == "Movies") "Movies" else section
                    renderSection()
                    renderBottomNav()
                    if (isTvDevice) bottomNav.getChildAt(
                        listOf("Live TV", "Checker", "Movies", "Series", "Settings").indexOf(section)
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
                loadingRow.addView(spinner, LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(5) })
                val navLabel = text(liveTvProgressLabel(), 13, if (active) accent else Color.rgb(64, 170, 255), true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(2), 0, 0)
                }
                liveTvNavLabel = navLabel
                loadingRow.addView(spinner, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    rightMargin = dp(5)
                })
                loadingRow.addView(navLabel)
                item.addView(loadingRow, LinearLayout.LayoutParams(-1, -2))
            } else {
                item.addView(text(symbol, 20, if (active) accent else muted, true).apply { gravity = Gravity.CENTER })
                val navLabel = text(if (name == "Live TV") liveTvProgressLabel() else name, 12, if (active) accent else muted, active).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                }
                if (name == "Live TV") liveTvNavLabel = navLabel
                item.addView(navLabel)
            }
            bottomNav.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
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
        settingsToggleCard(box, "Low internet mode", "Reduce video bitrate for slower mobile data", "low_data_mode", true)
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
        box.addView(text("PLAYER & PLAYLIST", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        settingsCard(box, "Playlist manager", "Save multiple M3U links and switch between them") { showPlaylistDialog() }
        settingsCard(box, "Playlist Checker", "Test channel links and report Working, Offline, Restricted or Error") { checkPlaylistStreams() }
        settingsToggleCard(box, "Background video playback", "Keep video playing in a floating window when you leave the app", "background_playback", true)
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
        settingsCard(box, "Network buffer size", "Choose buffering for unstable or slower internet") {
            val labels = arrayOf("Low — 10 seconds (faster start)", "Balanced — 20 seconds (recommended)", "High — 40 seconds (more buffering)")
            val values = arrayOf("low", "balanced", "high")
            val current = prefs.getString("network_buffer_size", "balanced")
            AlertDialog.Builder(this).setTitle("Network buffer size")
                .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                    prefs.edit().putString("network_buffer_size", values[which]).apply()
                    dialog.dismiss()
                    toast("Buffer size saved. Applies to the next video playback.")
                    renderHome()
                }.setNegativeButton("CANCEL", null).show()
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
                    addView(text("GH", 16, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.rgb(35, 40, 45))
                        }
                        elevation = dp(3).toFloat()
                        setOnClickListener {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Sany100")))
                            } catch (_: Exception) { toast("GitHub profile খুলতে সমস্যা হয়েছে") }
                        }
                        contentDescription = "Open Sany100 GitHub profile"
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    })
                    addView(text("GitHub", 11, muted, true).apply { setPadding(0, dp(6), 0, 0) })
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
            .create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
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
        defaultValue: Boolean
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

    private fun savePlaylistUrl(url: String) {
        if (url != permanentPlaylistUrl && url !in playlistUrls) playlistUrls.add(url)
        // Keep the permanent owner playlist at position 1 in the saved list.
        playlistUrls.remove(permanentPlaylistUrl)
        playlistUrls.add(0, permanentPlaylistUrl)
        val json = org.json.JSONArray()
        playlistUrls.forEach { json.put(it) }
        prefs.edit().putString("playlist_urls", json.toString()).putString("playlist_url", permanentPlaylistUrl).apply()
    }

    private fun showPlaylistDialog() {
        val labels = playlistUrls.mapIndexed { index, url ->
            if (url == permanentPlaylistUrl) "★ SANY IPTV — PERMANENT / LOCKED"
            else (index + 1).toString() + ". " + url
        }.toTypedArray()
        val builder = AlertDialog.Builder(this).setTitle("Playlist manager")
        if (labels.isNotEmpty()) builder.setItems(labels) { _, which -> loadFromUrl(playlistUrls[which]) }
        else builder.setMessage("এখনো কোনো playlist যোগ করা হয়নি।")
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
            permanentPlaylistUrl,
            "https://cdn.jsdelivr.net/gh/Sany100/Sany-iptv@main/playlist.m3u",
            "https://github.com/Sany100/Sany-iptv/raw/refs/heads/main/playlist.m3u"
        ) else listOf(url)
        var lastError: Exception? = null
        for (candidate in candidates.distinct()) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(candidate).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = true
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SANY-IPTV/1.0")
                connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl, audio/x-mpegurl, text/plain, */*")
                connection.setRequestProperty("Connection", "close")
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
                        channels = parsed
                        streamChecks.clear()
                        savePlaylistUrl(url)
                        section = "Live TV"
                        selectedGroup = "All"; searchQuery = ""
                        prefs.edit().putString("ui_section", "Live TV")
                            .putString("ui_group", "All").putString("ui_search", "").apply()
                        if (!detectedEpgUrl.isNullOrBlank()) {
                            prefs.edit().putString("epg_url", detectedEpgUrl).apply()
                            if (prefs.getBoolean("auto_add_epg", true)) updateEpgData(detectedEpgUrl, false)
                        }
                        renderHome()
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
            }
        }, 500L)
    }

    private fun updateCheckerUi() {
        checkerSummaryView?.text = checkerSummaryText()
        checkerListAdapter?.notifyDataSetChanged()
        checkerRunButton?.text = if (checkerRunning) "CHECKING CHANNELS…" else "↻  CHECK ALL CHANNELS AGAIN"
    }

    private fun liveTvProgressLabel(): String {
        if (channels.isEmpty()) return "Live TV"
        if (!checkerRunning && streamChecks.isEmpty()) return "Live TV"
        val percent = if (channels.isEmpty()) 100 else ((if (checkerRunning) checkerProgressCount.get() else streamChecks.size) * 100 / channels.size).coerceIn(0, 100)
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

    private fun checkerSummaryText(): String =
        if (checkerRunning) "Checking… ${checkerProgressCount.get()} / ${channels.size}"
        else "Total ${channels.size} • Working ${streamChecks.values.count { it.first == "Working" }} • Not working ${streamChecks.values.count { it.first != "Working" }}"

    private fun startPlaylistCheck(showToast: Boolean) {
        if (channels.isEmpty() || checkerRunning) return
        val snapshot = channels.toList()
        checkerRunning = true
        checkerProgressCount.set(0)
        updateLiveTvProgress()
        // Keep the last known health snapshot visible in Checker while refreshing.
        checkerDisplayedLimit = 250
        if (showToast) toast("চ্যানেল পরীক্ষা শুরু হয়েছে")
        if (::contentArea.isInitialized && section == "Checker") renderCheckerSection()
        if (::contentArea.isInitialized && section == "Live TV") renderListOnly()

        // A fixed number of workers pull the next channel from an atomic index.
        // This avoids creating thousands of queued Runnables for huge playlists.
        val nextIndex = java.util.concurrent.atomic.AtomicInteger(0)
        val workerCount = minOf(8, snapshot.size)
        val workers = (0 until workerCount).map { worker ->
            thread(name = "SANY-Stream-Check-$worker") {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= snapshot.size || Thread.currentThread().isInterrupted) break
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
                selectedGroup = "All"
                searchQuery = ""
                prefs.edit()
                    .putString("ui_section", "Live TV")
                    .putString("ui_group", "All")
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
            if (checkerRunning) "CHECKING CHANNELS…" else "↻  CHECK ALL CHANNELS AGAIN"
        ) {
            if (!checkerRunning) startPlaylistCheck(true)
        }
        box.addView(checkerRunButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            bottomMargin = dp(10)
        })

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
            filters.addView(text(filter + " (" + count + ")", 11, if (checkerFilter == filter) bg else muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                background = if (checkerFilter == filter) rounded(accent, 24) else glass(24)
                setOnClickListener {
                    checkerFilter = filter
                    renderCheckerSection()
                }
            }, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(6) })
        }
        box.addView(filterScroll, LinearLayout.LayoutParams(-1, dp(44)))

        val list = ListView(this).apply {
            divider = null
            dividerHeight = dp(5)
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnItemClickListener { _, _, position, _ ->
                val selected = checkerSelectedChannels()
                selected.getOrNull(position)?.let { channel ->
                    if (streamChecks[channel.url]?.first == "Working") play(channel)
                }
            }
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
                    val workers = (0 until minOf(6, retry.size)).map { worker ->
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
        if (!streamUrl.startsWith("http://", true) && !streamUrl.startsWith("https://", true)) {
            return "Error" to "Invalid URL"
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(streamUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.defaultUseCaches = false
            connection.setRequestProperty("User-Agent", "SANY-IPTV-Android")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("Connection", "close")
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

    private fun play(channel: Channel) {
        if (channel.url.isBlank()) {
            toast("এই চ্যানেলের stream link নেই")
            return
        }
        val streamUrl = channel.url.trim()
        if (!streamUrl.startsWith("http://", true) && !streamUrl.startsWith("https://", true)) {
            toast("Unsupported stream URL — FTP needs server credentials and setup")
            return
        }

        // Preserve the current viewing mode when switching channels.
        // First playback opens fullscreen; a switch from portrait stays portrait.
        val wasAlreadyPlaying = playerView != null
        val keepFullscreenMode = if (wasAlreadyPlaying) isPlayerFullscreen else true
        val oldChannel = currentChannel
        if (oldChannel != null && oldChannel.url != channel.url) previousChannel = oldChannel
        closePlayer(keepOrientation = true)
        currentChannel = channel

        val httpFactory = DefaultHttpDataSource.Factory()
            // Common IPTV endpoints reject minimal/custom user agents; send normal
            // media request headers and allow redirects without adding long waits.
            .setUserAgent("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(mapOf(
                "Accept" to "*/*",
                "Connection" to "keep-alive"
            ))
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val p = player ?: ExoPlayer.Builder(this, mediaSourceFactory)
            .setTrackSelector(DefaultTrackSelector(this).apply {
                if (prefs.getBoolean("low_data_mode", true)) {
                    setParameters(buildUponParameters().setMaxVideoBitrate(650_000).setMaxVideoSize(854, 480))
                }
            })
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        when (prefs.getString("network_buffer_size", "balanced")) {
                            "low" -> 5000
                            "high" -> 20000
                            else -> 10000
                        },
                        when (prefs.getString("network_buffer_size", "balanced")) {
                            "low" -> 10000
                            "high" -> 40000
                            else -> 20000
                        },
                        when (prefs.getString("network_buffer_size", "balanced")) {
                            "low" -> 1000
                            "high" -> 2000
                            else -> 1500
                        },
                        when (prefs.getString("network_buffer_size", "balanced")) {
                            "low" -> 2000
                            "high" -> 5000
                            else -> 3000
                        }
                    )
                    .build()
            )
            .build().also { created ->
                created.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
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
                            if (httpStatus != null) {
                                toast("সার্ভার HTTP $httpStatus দিয়েছে — অন্য stream link চেষ্টা করুন")
                            } else {
                                toast("প্লে হচ্ছে না: ${error.errorCodeName}")
                            }
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            runOnUiThread { /* first frame can now start */ }
                        }
                    }
                })
                player = created
            }

        val screen = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        playerScreen = screen
        val tvRemoteHint: TextView? = if (isTvDevice) TextView(this).apply {
            // TV-friendly channel switch hint stays visible when the player controls fade.
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
        isPlayerFullscreen = keepFullscreenMode
        playerHeader = null
        val view = PlayerView(this).apply {
            useController = true
            player = p
            // Crop slightly when needed so the video fills the entire landscape display.
            // The PlayerView and its shutter remain black, never showing the home screen behind it.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            setShutterBackgroundColor(Color.BLACK)
            setControllerShowTimeoutMs(2500)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        playerView = view
        screen.addView(view, FrameLayout.LayoutParams(-1, dp(230), Gravity.TOP).apply {
            leftMargin = dp(0); rightMargin = dp(0)
        })
        tvRemoteHint?.let { hint ->
            screen.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(18)
            })
        }

        val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val threshold = dp(65)
            private val velocity = dp(120)
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                    kotlin.math.abs(velocityX) > velocity) {
                    playAdjacentChannel(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        view.setOnTouchListener { _, event ->
            swipeDetector.onTouchEvent(event)
            false
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), Color.TRANSPARENT)
            )
        }
        val back = text("‹", 34, Color.WHITE, true).apply {
            isFocusable = true
            applyTvFocusStyle(this)
            gravity = Gravity.CENTER
            setOnClickListener { closePlayer() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))
        header.addView(text(channel.name, 16, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        playerHeader = header
        screen.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = dp(230)
        })
        val fullscreenButton = text("⛶  FULL", 13, Color.WHITE, true).apply {
            isFocusable = true
            applyTvFocusStyle(this)
            gravity = Gravity.CENTER
            setPadding(dp(13), 0, dp(13), 0)
            background = GradientDrawable().apply {
                setColor(0xCC101A28.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0x99FFFFFF.toInt())
            }
            elevation = dp(8).toFloat()
            setOnClickListener { setPlayerFullscreen(!isPlayerFullscreen) }
            contentDescription = "Toggle fullscreen"
        }
        playerFullscreenButton = fullscreenButton
        screen.addView(fullscreenButton, FrameLayout.LayoutParams(-2, dp(46), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(244); rightMargin = dp(16)
        })
        // Hide the custom fullscreen button together with Media3 controls.
        // It reappears when the user taps the video to show the controls again.
        view.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                val controlsVisible = visibility == View.VISIBLE
                playerFullscreenButton?.visibility = if (controlsVisible) View.VISIBLE else View.GONE
                playerHeader?.visibility = if (isPlayerFullscreen && controlsVisible) View.VISIBLE
                    else if (isPlayerFullscreen) View.GONE else View.VISIBLE
            }
        )
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        // Retain portrait/fullscreen mode across channel changes.
        setPlayerFullscreen(keepFullscreenMode)
        view.showController()

        val lowerUrl = streamUrl.substringBefore("?").substringBefore("#").lowercase()
        val itemBuilder = MediaItem.Builder().setUri(streamUrl)
        when {
            lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".m3u") ||
                lowerUrl.contains("/hls/") || lowerUrl.contains("format=m3u8") ->
                itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lowerUrl.endsWith(".mpd") || lowerUrl.contains("/dash/") ->
                itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        p.setMediaItem(itemBuilder.build())
        p.prepare()
        p.playWhenReady = true
        prefs.edit().putString("last_channel", streamUrl).apply()
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

    private fun setPlayerFullscreen(enabled: Boolean) {
        isPlayerFullscreen = enabled
        playerHeader?.visibility = if (enabled) View.GONE else View.VISIBLE
        playerFullscreenButton?.text = if (enabled) "⤢  EXIT" else "⛶  FULL"
        if (!enabled) playerFullscreenButton?.visibility = View.VISIBLE
        playerView?.layoutParams = FrameLayout.LayoutParams(
            -1, if (enabled) -1 else dp(230), if (enabled) Gravity.CENTER else Gravity.TOP
        )
        playerHeader?.let { header ->
            (header.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.topMargin = if (enabled) 0 else dp(230)
                header.layoutParams = params
            }
        }
        playerFullscreenButton?.let { button ->
            (button.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.gravity = if (enabled) Gravity.BOTTOM or Gravity.END else Gravity.TOP or Gravity.END
                params.topMargin = if (enabled) 0 else dp(244)
                params.bottomMargin = if (enabled) dp(24) else 0
                button.layoutParams = params
            }
        }
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
        // When changing channels, do not briefly unlock orientation: Android may
        // rotate the Activity to portrait before the next fullscreen player is ready.
        if (!keepOrientation) {
            if (isPlayerFullscreen) setPlayerFullscreen(false)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            applySystemBars()
        }
        playerHeader = null
        playerFullscreenButton = null
        player?.stop()
        playerView?.player = null
        playerView = null
        playerScreen?.let { root.removeView(it) }
        playerScreen = null
        if (!keepOrientation) isPlayerFullscreen = false
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (playerView == null || player?.isPlaying != true) return

        if (!prefs.getBoolean("background_playback", true)) {
            pausedForBackground = true
            player?.pause()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // PiP must use the whole Activity bounds. Keeping the portrait 230dp
            // inline-player height here makes Android crop the floating video.
            playerView?.let { view ->
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.layoutParams = FrameLayout.LayoutParams(-1, -1)
                view.setUseController(false)
            }
            val videoSize = player?.videoSize
            var width = videoSize?.width ?: 0
            var height = videoSize?.height ?: 0
            if (videoSize?.unappliedRotationDegrees == 90 || videoSize?.unappliedRotationDegrees == 270) {
                val swap = width; width = height; height = swap
            }
            val ratio = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.5f, 2.39f) else (16f / 9f)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerHeader?.visibility = View.GONE
            playerFullscreenButton?.visibility = View.GONE
            playerView?.setUseController(false)
        } else if (playerView != null) {
            playerView?.let { view ->
                view.setUseController(true)
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.layoutParams = FrameLayout.LayoutParams(
                    -1, if (isPlayerFullscreen) -1 else dp(230),
                    if (isPlayerFullscreen) Gravity.CENTER else Gravity.TOP
                )
            }
            playerHeader?.visibility = if (isPlayerFullscreen) View.GONE else View.VISIBLE
            playerFullscreenButton?.visibility = View.VISIBLE
        }
    }

    override fun onStart() {
        super.onStart()
        scheduleOfflineChannelRecheck()
        if (pausedForBackground) {
            pausedForBackground = false
            player?.play()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playerView != null && isPlayerFullscreen) setPlayerFullscreen(false)
        else if (playerView != null) closePlayer() else if (section != "Live TV") {
            section = "Live TV"; selectedGroup = "All"; searchQuery = ""; renderHome()
        } else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("ui_section", section)
        outState.putString("ui_group", selectedGroup)
        outState.putString("ui_search", searchQuery)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        checkerUiHandler.removeCallbacks(offlineRecheckRunnable)
        val closingPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        if (closingPip) {
            // Android's PiP close (X) dismisses the Activity; explicitly stop playback
            // so audio cannot continue after the floating window is closed.
            player?.pause()
            player?.stop()
        }
        prefs.edit()
            .putString("ui_section", section)
            .putString("ui_group", selectedGroup)
            .putString("ui_search", searchQuery)
            .putBoolean("resume_without_splash", true)
            .putString("resume_stream", if (!closingPip && player?.isPlaying == true) currentChannel?.url else null)
            .apply()
        if (closingPip) prefs.edit().remove("resume_stream").putBoolean("resume_without_splash", false).apply()
        super.onStop()
    }

    override fun onDestroy() {
        // Swiping this task away from Recents finishes SANY IPTV only.
        if (isFinishing) {
            prefs.edit().putBoolean("resume_without_splash", false).remove("resume_stream").apply()
        }
        playerView?.player = null
        player?.release(); player = null
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
