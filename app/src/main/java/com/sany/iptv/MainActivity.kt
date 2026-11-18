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

class MainActivity : AppCompatActivity() {
    private val isTvDevice: Boolean
        get() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    private val isLight: Boolean
        get() = when (prefs.getString("app_theme", "dark")) {
            "light" -> true
            "system" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
    // Day mode uses a clean pure-white canvas and white surfaces throughout.
    private val bg get() = if (isLight) Color.WHITE else Color.rgb(8, 12, 21)
    private val panel get() = if (isLight) Color.WHITE else Color.rgb(18, 25, 39)
    private val panel2 get() = if (isLight) Color.WHITE else Color.rgb(25, 34, 51)
    private val accent: Int
        get() = when (prefs.getString("accent_color", "teal")) {
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
    private var displayedLimit = 120
    private var playerHeader: LinearLayout? = null
    private var isPlayerFullscreen = false
    private var playerFullscreenButton: TextView? = null
    private var pausedForBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep automatic update checks enabled for every installation; there is no user-facing switch.
        prefs.edit().putBoolean("auto_update_check", true).apply()
        // Restore the user's existing playlist manager; discard only the three rejected demo sources.
        val rejectedSources = setOf(
            "https://iptv-org.github.io/iptv/categories/animation.m3u",
            "https://iptv-org.github.io/iptv/categories/series.m3u",
            "https://raw.githubusercontent.com/wizakorhd/iptv/main/playlist-anime.m3u"
        )
        playlistUrls.addAll(readPlaylistUrls().filterNot { it in rejectedSources })
        val savedPlaylist = prefs.getString("playlist_url", null)?.takeUnless { it in rejectedSources }
        if (savedPlaylist == null) prefs.edit().remove("playlist_url").apply()
        val restoringBackground = savedInstanceState != null || prefs.getBoolean("resume_without_splash", false)
        section = savedInstanceState?.getString("ui_section") ?: prefs.getString("ui_section", "Live TV") ?: "Live TV"
        selectedGroup = savedInstanceState?.getString("ui_group") ?: prefs.getString("ui_group", "All") ?: "All"
        searchQuery = savedInstanceState?.getString("ui_search") ?: prefs.getString("ui_search", "") ?: ""
        val resumeStream = if (restoringBackground) prefs.getString("resume_stream", null) else null
        prefs.edit().putBoolean("resume_without_splash", false).apply()
        val cachedPlaylist = savedPlaylist?.let { playlistCacheFile(it) }
        if (cachedPlaylist?.exists() == true) {
            try { channels = M3uParser.parse(cachedPlaylist.readText()) } catch (_: Exception) { channels = emptyList() }
        }
        applySystemBars()
        buildShell(showSplash = !restoringBackground)
        loadArchiveWatchPlaylist()
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
        if (channels.isEmpty() && !savedPlaylist.isNullOrBlank()) loadFromUrl(savedPlaylist, false)
        else if (!resumeStream.isNullOrBlank() && channels.isNotEmpty()) {
            channels.firstOrNull { it.url == resumeStream }?.let { channel ->
                Handler(Looper.getMainLooper()).post { play(channel) }
            }
        }
        if (prefs.getBoolean("auto_update_check", false)) {
            Handler(Looper.getMainLooper()).postDelayed({ checkForAppUpdate(false) }, 1800)
        }
    }

    private fun applySystemBars() {
        window.statusBarColor = bg
        window.navigationBarColor = bg
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
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        renderHome()
        if (showSplash) showStartupAnimation()
    }

    private fun showStartupAnimation() {
        val splash = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 12, 22))
            elevation = dp(30).toFloat()
        }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val logo = text("SANY", 46, Color.rgb(0, 220, 175), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.20f
            setShadowLayer(dp(18).toFloat(), 0f, 0f, Color.argb(170, 0, 220, 175))
            alpha = 0f
            scaleX = 0.68f
            scaleY = 0.68f
            rotationY = -78f
            rotationX = 24f
            cameraDistance = 9000f * resources.displayMetrics.density
            translationZ = dp(22).toFloat()
        }
        center.addView(logo)
        center.addView(text("IPTV", 15, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.42f
            alpha = 0f
        })
        center.addView(text("YOUR ENTERTAINMENT. YOUR WAY.", 9, Color.rgb(145, 160, 180), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(0, dp(12), 0, 0)
            alpha = 0f
        })
        splash.addView(center, FrameLayout.LayoutParams(-1, -1))
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))
        // A 3D entrance: the SANY wordmark rotates on its Y axis
        // while tilting back into place, with depth and a teal glow.
        logo.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .rotationY(360f).rotationX(0f)
            .setDuration(1350)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
        center.getChildAt(1).animate().alpha(1f).setStartDelay(420).setDuration(500).start()
        center.getChildAt(2).animate().alpha(1f).setStartDelay(620).setDuration(500).start()
        splash.animate().alpha(0f).setStartDelay(1550).setDuration(400).withEndAction {
            root.removeView(splash)
        }.start()
    }

    private fun renderHome() {
        home.removeAllViews()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = glass(22)
        }
        val brandLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = text("SANY", 32, accent, true).apply { letterSpacing = 0.12f; setShadowLayer(dp(10).toFloat(), 0f, 0f, Color.argb(75, 0, 220, 180)) }
        brandLine.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        brandLine.addView(pill(if (channels.isEmpty()) "NOT CONNECTED" else "● CONNECTED", if (channels.isEmpty()) muted else accent))
        top.addView(brandLine)
        top.addView(text("✦  PREMIUM IPTV EXPERIENCE  •  WELCOME TO SANY", 10, muted, true).apply { letterSpacing = 0.10f; setPadding(0, dp(1), 0, dp(14)) })
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(text(section, 23, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(iconButton("#") { showChannelNumberDialog() })
        titleRow.addView(iconButton("↶") {
            val previous = previousChannel
            if (previous == null) toast("আগের কোনো চ্যানেল নেই") else play(previous)
        })
        titleRow.addView(iconButton("＋") { showPlaylistDialog() })
        titleRow.addView(iconButton("⋮") { showQuickMenu() })
        top.addView(titleRow)
        home.addView(top, LinearLayout.LayoutParams(-1, -2))

        contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), 0)
        }
        home.addView(contentArea, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(8), dp(5), dp(8))
            background = glass(18)
        }
        home.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(66)))
        renderSection()
        renderBottomNav()
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
        contentArea.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        search.addTextChangedListener(SimpleTextWatcher { searchQuery = it; displayedLimit = 120; renderListOnly() })

        val categoriesScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        categoriesScroll.addView(categoryRow)
        contentArea.addView(categoriesScroll, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        renderCategories()

        countLabel = text("", 12, muted).apply { setPadding(dp(2), dp(4), 0, dp(8)) }
        contentArea.addView(countLabel)
        val listScroll = ScrollView(this).apply { isFillViewport = true }
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
            setShadowLayer(dp(18).toFloat(), 0f, 0f, Color.argb(115, 0, 220, 180))
        }
        page.addView(emblem, LinearLayout.LayoutParams(-1, dp(82)))
        val title = text("COMING SOON", 36, accent, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.07f
            setShadowLayer(dp(16).toFloat(), 0f, 0f, Color.argb(125, 0, 220, 180))
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
                background = if (active) rounded(Color.argb(220, 0, 170, 130), 30, Color.argb(160, 160, 255, 230), 1)
                    else glass(30)
                setOnClickListener {
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(65).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                        selectedGroup = item; renderListOnly()
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
            container.addView(text("No items found", 15, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(35), 0, dp(35)) })
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
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
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

    private fun filteredChannels(): List<Channel> {
        return channels.filter { c ->
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
            typeMatch && groupMatch && (q.isBlank() || c.name.lowercase().contains(q) || c.group.lowercase().contains(q))
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
        listOf("Live TV" to "▣", "Movies" to "▤", "Series" to "▥", "Settings" to "⚙").forEach { (name, symbol) ->
            val active = section == name
            val item = LinearLayout(this).apply {
                isFocusable = true
                isFocusableInTouchMode = false
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener {
                    animate().scaleX(0.94f).scaleY(0.94f).setDuration(65).withEndAction {
                        section = name; selectedGroup = "All"; searchQuery = ""
                        renderHome()
                        home.alpha = 0.72f; home.scaleX = 0.985f; home.scaleY = 0.985f
                        home.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).start()
                    }.start()
                }
            }
            item.addView(text(symbol, 20, if (active) accent else muted, true).apply { gravity = Gravity.CENTER })
            item.addView(text(name, 10, if (active) accent else muted, active).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
            bottomNav.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun renderSettings() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        box.addView(text("APPEARANCE", 11, accent, true).apply { setPadding(0, dp(10), 0, dp(12)) })
        settingsCard(box, "Customize accent color", "Change the app highlight color") {
            val labels = arrayOf("Teal", "Blue", "Purple", "Orange")
            val values = arrayOf("teal", "blue", "purple", "orange")
            AlertDialog.Builder(this).setTitle("Accent color")
                .setSingleChoiceItems(labels, values.indexOf(prefs.getString("accent_color", "teal")).coerceAtLeast(0)) { dialog, which ->
                    prefs.edit().putString("accent_color", values[which]).apply()
                    dialog.dismiss()
                    renderHome()
                }.setNegativeButton("CANCEL", null).show()
        }
        settingsToggleCard(box, "Low internet mode", "Reduce video bitrate for slower mobile data", "low_data_mode", false)
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
        settingsToggleCard(box, "Background video playback", "Keep video playing in a floating window when you leave the app", "background_playback", true)
        settingsCard(box, "Reload playlist", "Fetch the saved playlist again") {
            val url = prefs.getString("playlist_url", null)
            if (url.isNullOrBlank()) showPlaylistDialog() else loadFromUrl(url)
        }
        settingsCard(box, "Clear playlist URL", "Forget the saved playlist address") {
            prefs.edit().remove("playlist_url").apply(); toast("Saved playlist URL cleared")
        }
        box.addView(text("APP UPDATES", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        settingsCard(box, "Check for updates", "Look for the latest version and download it") { checkForAppUpdate(true) }
        box.addView(text("ABOUT", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        box.addView(text("SANY IPTV  •  Version 1.1\nM3U / M3U8 playlist player powered by Media3.", 13, muted).apply {
            setPadding(dp(15), dp(16), dp(15), dp(16)); background = rounded(panel, 12)
        })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(22), dp(12), dp(24))
            addView(text("This App Made By Sany Sarfaraz", 14, white, true))
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
                    } catch (_: Exception) {
                        toast("Facebook link খুলতে সমস্যা হয়েছে")
                    }
                }
                contentDescription = "Open Sany Sarfaraz Facebook profile"
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(12) }
            })
        })
        contentArea.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun checkForAppUpdate(showUpToDate: Boolean) {
        thread {
            try {
                val connection = URL("https://api.github.com/repos/Sany100/Sany-iptv/releases/latest")
                    .openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "SANY-IPTV-Android")
                val code = connection.responseCode
                if (code == 404) {
                    runOnUiThread { if (showUpToDate) toast("এখনো কোনো update release প্রকাশ করা হয়নি") }
                    connection.disconnect()
                    return@thread
                }
                if (code !in 200..299) throw IllegalStateException("GitHub HTTP " + code)
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val release = org.json.JSONObject(json)
                val tagVersion = release.optString("tag_name").trim().removePrefix("v").removePrefix("V")
                // Some releases have a title like "SANY IPTV v1.2" while the tag is still V1.1.
                // Prefer the explicit version in the release title when it is newer than the tag.
                val titleVersion = Regex("(?i)\\bv?([0-9]+(?:\\.[0-9]+){1,3})\\b")
                    .find(release.optString("name"))?.groupValues?.getOrNull(1).orEmpty()
                val tag = if (titleVersion.isNotBlank() && compareVersions(titleVersion, tagVersion) > 0)
                    titleVersion else tagVersion
                val current = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                val apkUrl = release.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length()).map { assets.optJSONObject(it) }
                        .firstOrNull { it?.optString("name")?.endsWith(".apk", true) == true }
                        ?.optString("browser_download_url")
                }.orEmpty()
                if (tag.isBlank() || apkUrl.isBlank()) throw IllegalStateException("Release APK পাওয়া যায়নি")
                val newer = compareVersions(tag, current) > 0
                val lastPrompted = prefs.getString("last_prompted_update_version", "")
                runOnUiThread {
                    if (!newer) {
                        // Once the installed app reaches the release version, clear the reminder.
                        prefs.edit().remove("last_prompted_update_version").apply()
                    }
                    // Automatic checks show a given release only once. Manual checks can still
                    // display the available update whenever the user explicitly asks.
                    if (newer && lastPrompted != tag) {
                        prefs.edit().putString("last_prompted_update_version", tag).apply()
                        val notes = release.optString("body").take(1200)
                        AlertDialog.Builder(this)
                            .setTitle("SANY IPTV update available")
                            .setMessage("Installed: " + current + "\nLatest: " + tag + "\n\n" + notes.ifBlank { "নতুন সংস্করণ পাওয়া গেছে।" })
                            .setPositiveButton("DOWNLOAD & INSTALL") { _, _ -> downloadAndInstallUpdate(apkUrl, tag) }
                            .setNegativeButton("LATER", null)
                            .show()
                    } else if (showUpToDate && !newer) {
                        AlertDialog.Builder(this).setTitle("You're up to date")
                            .setMessage("বর্তমান ভার্সন: " + current)
                            .setPositiveButton("OK", null).show()
                    } else if (showUpToDate && newer) {
                        // Do not repeat the same release notification after it has already been shown.
                        toast("এই আপডেটের নোটিফিকেশন আগে দেখানো হয়েছে: " + tag)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { if (showUpToDate) toast("Update check failed: " + (e.message ?: "network error")) }
            }
        }
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
        try {
            val request = android.app.DownloadManager.Request(Uri.parse(url))
                .setTitle("SANY IPTV " + version)
                .setDescription("Downloading app update…")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "SANY-IPTV-" + version + ".apk")
            val manager = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            val downloadId = manager.enqueue(request)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    if (intent?.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                    try {
                        unregisterReceiver(this)
                        val apkUri = manager.getUriForDownloadedFile(downloadId)
                        if (apkUri == null) {
                            toast("APK download failed")
                            return
                        }
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            startActivity(installIntent)
                        } catch (_: Exception) {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + packageName)))
                            toast("অনুমতি দিন, তারপর Downloads থেকে SANY IPTV APK খুলুন")
                        }
                    } catch (e: Exception) {
                        toast("Install open failed: " + (e.message ?: "unknown error"))
                    }
                }
            }
            val filter = android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            toast("APK ডাউনলোড শুরু হয়েছে")
        } catch (e: Exception) {
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

    // Load a free, public-domain M3U catalogue and split its VOD entries into
    // the app's existing Movies and Series sections. TVmaze metadata is not used.
    private fun loadArchiveWatchPlaylist() {
        val sourceUrl = "https://archivewatch.org/feeds/archivewatch.m3u"
        thread {
            try {
                val content = URL(sourceUrl).openConnection().apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                }.getInputStream().bufferedReader().use { it.readText() }
                val parsed = M3uParser.parse(content)
                val movieItems = parsed.filter { isMovie(it) }
                    .map { it.copy(group = "Archive Watch • Movies • " + it.group) }
                val seriesItems = parsed.filter { !isMovie(it) && isSeries(it) }
                    .map { it.copy(group = "Archive Watch • Series • " + it.group) }
                val catalogue = movieItems + seriesItems
                if (catalogue.isNotEmpty()) {
                    try { playlistCacheFile(sourceUrl).writeText(content) } catch (_: Exception) { }
                    runOnUiThread {
                        channels = (channels + catalogue).distinctBy { it.url }
                        if (::home.isInitialized) renderHome()
                        toast("Archive Watch: ${movieItems.size} movies, ${seriesItems.size} series")
                    }
                } else {
                    runOnUiThread { toast("Archive Watch playlist-এ playable Movies/Series পাওয়া যায়নি") }
                }
            } catch (_: Exception) {
                // Keep the app usable offline; a previously cached catalogue can still be imported later.
                runOnUiThread { /* Existing playlists remain available. */ }
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
        if (url !in playlistUrls) playlistUrls.add(url)
        val json = org.json.JSONArray()
        playlistUrls.forEach { json.put(it) }
        prefs.edit().putString("playlist_urls", json.toString()).putString("playlist_url", url).apply()
    }

    private fun showPlaylistDialog() {
        val labels = playlistUrls.mapIndexed { index, url -> (index + 1).toString() + ". " + url }.toTypedArray()
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
                3 -> prefs.getString("playlist_url", null)?.let { loadFromUrl(it) } ?: showPlaylistDialog()
                4 -> { section = "Settings"; renderHome() }
            }
        }.show()
    }

    private fun openFilePicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, pickFile)
    }

    private fun loadFromUrl(url: String, showStatus: Boolean = true) {
        if (url.isBlank()) { showPlaylistDialog(); return }
        if (showStatus) toast("Loading playlist…")
        thread {
            try {
                val content = URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 25000 }
                    .getInputStream().bufferedReader().use { it.readText() }
                val parsed = M3uParser.parse(content)
                if (parsed.isNotEmpty()) {
                    // Write the cache on the worker thread, not the UI thread.
                    try { playlistCacheFile(url).writeText(content) } catch (_: Exception) { /* Keep the live playlist usable if caching fails. */ }
                }
                runOnUiThread {
                    if (parsed.isEmpty()) toast("No playable entries found in this playlist")
                    else {
                        channels = parsed
                        savePlaylistUrl(url)
                        selectedGroup = "All"; searchQuery = ""
                        renderHome()
                        toast("Loaded " + channels.size + " items")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Playlist load failed: ${e.message ?: "network error"}") }
            }
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
                if (prefs.getBoolean("low_data_mode", false)) {
                    setParameters(buildUponParameters().setMaxVideoBitrate(650_000))
                }
            })
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2500, 15000, 1000, 2500)
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
                        runOnUiThread {
                            if (parsed.isEmpty()) toast("No channels found in file") else {
                                channels = parsed; selectedGroup = "All"; searchQuery = ""; renderHome()
                                toast("Imported ${channels.size} items")
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
        prefs.edit()
            .putString("ui_section", section)
            .putString("ui_group", selectedGroup)
            .putString("ui_search", searchQuery)
            .putBoolean("resume_without_splash", true)
            .putString("resume_stream", if (player?.isPlaying == true) currentChannel?.url else null)
            .apply()
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
        text = label; textSize = if (isTvDevice) 28f else 22f; setTextColor(white); gravity = Gravity.CENTER
        background = glass(12); elevation = dp(2).toFloat(); setOnClickListener { action() }
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
    private fun glass(radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (isLight) intArrayOf(Color.WHITE, Color.WHITE)
        else intArrayOf(Color.argb(225, 30, 43, 64), Color.argb(205, 17, 27, 44))
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), if (isLight) Color.rgb(232, 236, 242) else Color.argb(75, 210, 235, 255))
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
