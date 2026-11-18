package com.sany.iptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val bg = Color.rgb(8, 12, 21)
    private val panel = Color.rgb(18, 25, 39)
    private val panel2 = Color.rgb(25, 34, 51)
    private val accent = Color.rgb(0, 210, 160)
    private val muted = Color.rgb(148, 160, 180)
    private val white = Color.WHITE
    private val prefs by lazy { getSharedPreferences("sany_iptv", MODE_PRIVATE) }
    private val pickFile = 41
    private var channels = listOf<Channel>()
    private var favorites = mutableSetOf<String>()
    private var selectedGroup = "All"
    private var section = "Live TV"
    private var searchQuery = ""
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentChannel: Channel? = null
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var contentArea: LinearLayout
    private lateinit var search: EditText
    private lateinit var countLabel: TextView
    private lateinit var categoryRow: LinearLayout
    private lateinit var bottomNav: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        buildShell()
        val saved = prefs.getString("playlist_url", null)
        if (!saved.isNullOrBlank()) loadFromUrl(saved, false)
    }

    private fun buildShell() {
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        renderHome()
    }

    private fun renderHome() {
        home.removeAllViews()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = rounded(panel, 0, 0, 22, 22)
        }
        val brandLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = text("SANY", 25, accent, true)
        brandLine.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        brandLine.addView(pill(if (channels.isEmpty()) "NOT CONNECTED" else "● CONNECTED", if (channels.isEmpty()) muted else accent))
        top.addView(brandLine)
        top.addView(text("IPTV PLAYER", 10, muted, true).apply { letterSpacing = 0.18f; setPadding(0, dp(2), 0, dp(14)) })
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(text(section, 23, white, true), LinearLayout.LayoutParams(0, -2, 1f))
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
            setBackgroundColor(panel)
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
            singleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(panel2, 12)
            setText(searchQuery)
        }
        contentArea.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        search.addTextChangedListener(SimpleTextWatcher { searchQuery = it; renderListOnly() })

        val categoriesScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        categoriesScroll.addView(categoryRow)
        contentArea.addView(categoriesScroll, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        renderCategories()

        countLabel = text("", 12, muted).apply { setPadding(dp(2), dp(4), 0, dp(8)) }
        contentArea.addView(countLabel)
        val listScroll = ScrollView(this).apply { isFillViewport = true }
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(listContainer)
        contentArea.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderChannelRows(listContainer)
    }

    private fun renderCategories() {
        categoryRow.removeAllViews()
        val groups = listOf("All") + channels.map { it.group }.distinct().filter { it.isNotBlank() }.sorted()
        val options = when (section) {
            "Favorites" -> listOf("All", "★ Favorites")
            "Movies" -> listOf("All") + groups.drop(1)
            "Series" -> listOf("All") + groups.drop(1)
            else -> groups
        }
        options.forEach { item ->
            val active = selectedGroup == item
            val chip = text(item, 12, if (active) bg else muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(15), 0, dp(15), 0)
                background = rounded(if (active) accent else panel2, 30)
                setOnClickListener { selectedGroup = item; renderListOnly() }
            }
            categoryRow.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(7) })
        }
    }

    private fun renderListOnly() {
        if (!::contentArea.isInitialized || section == "Settings" || channels.isEmpty()) return
        val scroll = contentArea.getChildAt(3) as? ScrollView ?: return
        val list = scroll.getChildAt(0) as? LinearLayout ?: return
        renderCategories()
        renderChannelRows(list)
    }

    private fun renderChannelRows(container: LinearLayout) {
        container.removeAllViews()
        val filtered = filteredChannels()
        countLabel.text = "${filtered.size} ITEMS  •  ${channels.size} IN PLAYLIST"
        if (filtered.isEmpty()) {
            container.addView(text("No items found", 15, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(35), 0, dp(35)) })
            return
        }
        filtered.forEachIndexed { index, channel ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = rounded(panel, 12)
            }
            val logo = text(channel.name.take(1).uppercase(), 18, accent, true).apply {
                gravity = Gravity.CENTER
                background = rounded(panel2, 10)
            }
            row.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
            details.addView(text(channel.name, 14, white, true))
            details.addView(text(channel.group.ifBlank { "Other" }, 11, muted).apply { setPadding(0, dp(4), 0, 0) })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val star = text(if (channel.url in favorites) "★" else "☆", 21, if (channel.url in favorites) accent else muted).apply {
                gravity = Gravity.CENTER
                setOnClickListener { toggleFavorite(channel) }
            }
            row.addView(star, LinearLayout.LayoutParams(dp(38), dp(44)))
            row.setOnClickListener { play(channel) }
            row.setOnLongClickListener { toggleFavorite(channel); true }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
    }

    private fun filteredChannels(): List<Channel> {
        return channels.filter { c ->
            val typeMatch = when (section) {
                "Movies" -> isMovie(c)
                "Series" -> isSeries(c)
                "Favorites" -> c.url in favorites
                else -> !isMovie(c) && !isSeries(c)
            }
            val groupMatch = selectedGroup == "All" || (selectedGroup == "★ Favorites" && c.url in favorites) || c.group == selectedGroup
            val q = searchQuery.trim().lowercase()
            typeMatch && groupMatch && (q.isBlank() || c.name.lowercase().contains(q) || c.group.lowercase().contains(q))
        }
    }

    private fun isMovie(c: Channel) = (c.group + " " + c.name + " " + c.url).lowercase().let {
        listOf("movie", "movies", "vod", "film", "4k film").any(it::contains) || c.url.substringBefore("?").lowercase().matches(Regex(".*\\.(mp4|mkv|avi|mov)$"))
    }
    private fun isSeries(c: Channel) = (c.group + " " + c.name).lowercase().let {
        listOf("series", "season", "episode", "tv show", "tvshows").any(it::contains)
    }

    private fun renderBottomNav() {
        bottomNav.removeAllViews()
        listOf("Live TV" to "▣", "Movies" to "▤", "Series" to "▥", "Favorites" to "☆", "Settings" to "⚙").forEach { (name, symbol) ->
            val active = section == name
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { section = name; selectedGroup = "All"; searchQuery = ""; renderHome() }
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
        box.addView(text("PLAYER & PLAYLIST", 11, accent, true).apply { setPadding(0, dp(10), 0, dp(12)) })
        settingsCard(box, "Playlist manager", "Add a playlist URL or import an M3U file") { showPlaylistDialog() }
        settingsCard(box, "Reload playlist", "Fetch the saved playlist again") {
            val url = prefs.getString("playlist_url", null)
            if (url.isNullOrBlank()) showPlaylistDialog() else loadFromUrl(url)
        }
        settingsCard(box, "Clear favorites", "Remove all saved favorite items") {
            favorites.clear(); prefs.edit().remove("favorites").apply(); toast("Favorites cleared"); renderHome()
        }
        settingsCard(box, "Clear playlist URL", "Forget the saved playlist address") {
            prefs.edit().remove("playlist_url").apply(); toast("Saved playlist URL cleared")
        }
        box.addView(text("ABOUT", 11, accent, true).apply { setPadding(0, dp(22), 0, dp(12)) })
        box.addView(text("SANY IPTV  •  Version 1.1\nM3U / M3U8 playlist player powered by Media3.", 13, muted).apply {
            setPadding(dp(15), dp(16), dp(15), dp(16)); background = rounded(panel, 12)
        })
        contentArea.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun settingsCard(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(panel, 12); setOnClickListener { action() }
        }
        card.addView(text(title, 15, white, true))
        card.addView(text(subtitle, 12, muted).apply { setPadding(0, dp(5), 0, 0) })
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun showPlaylistDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/playlist.m3u"
            setText(prefs.getString("playlist_url", ""))
            setTextColor(white); setHintTextColor(muted)
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, dp(52))) }
        AlertDialog.Builder(this).setTitle("Add playlist URL").setView(wrap)
            .setPositiveButton("LOAD") { _, _ -> loadFromUrl(input.text.toString().trim()) }
            .setNeutralButton("IMPORT FILE") { _, _ -> openFilePicker() }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun showQuickMenu() {
        AlertDialog.Builder(this).setItems(arrayOf("Add playlist URL", "Import M3U file", "Reload playlist", "Settings")) { _, which ->
            when (which) {
                0 -> showPlaylistDialog()
                1 -> openFilePicker()
                2 -> prefs.getString("playlist_url", null)?.let { loadFromUrl(it) } ?: showPlaylistDialog()
                3 -> { section = "Settings"; renderHome() }
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
                runOnUiThread {
                    if (parsed.isEmpty()) toast("No playable entries found in this playlist")
                    else {
                        channels = parsed
                        prefs.edit().putString("playlist_url", url).apply()
                        selectedGroup = "All"; searchQuery = ""
                        renderHome()
                        toast("Loaded ${channels.size} items")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Playlist load failed: ${e.message ?: "network error"}") }
            }
        }
    }

    private fun toggleFavorite(channel: Channel) {
        if (!favorites.add(channel.url)) favorites.remove(channel.url)
        prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
        if (section == "Favorites") renderListOnly() else renderListOnly()
    }

    private fun play(channel: Channel) {
        currentChannel = channel
        val p = player ?: ExoPlayer.Builder(this).build().also { player = it }
        val screen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            useController = true
            player = p
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        playerView = view
        screen.addView(view, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), Color.TRANSPARENT))
        }
        val back = text("‹", 34, white, true).apply { gravity = Gravity.CENTER; setOnClickListener { closePlayer() } }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))
        header.addView(text(channel.name, 16, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        screen.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        p.setMediaItem(MediaItem.fromUri(channel.url)); p.prepare(); p.play()
        prefs.edit().putString("last_channel", channel.url).apply()
    }

    private fun closePlayer() {
        player?.pause()
        playerView?.player = null
        playerView = null
        root.removeViewAt(root.childCount - 1)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFile && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val parsed = M3uParser.parse(raw)
                    if (parsed.isEmpty()) toast("No channels found in file") else {
                        channels = parsed; selectedGroup = "All"; searchQuery = ""; renderHome()
                        toast("Imported ${channels.size} items")
                    }
                } catch (e: Exception) { toast("File error: ${e.message}") }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playerView != null) closePlayer() else if (section != "Live TV") {
            section = "Live TV"; selectedGroup = "All"; searchQuery = ""; renderHome()
        } else super.onBackPressed()
    }

    override fun onDestroy() {
        playerView?.player = null
        player?.release(); player = null
        super.onDestroy()
    }

    private fun actionButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(bg); gravity = Gravity.CENTER
        background = rounded(accent, 12); setOnClickListener { action() }
    }
    private fun iconButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 22f; setTextColor(white); gravity = Gravity.CENTER
        background = rounded(panel2, 12); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) }
    }
    private fun pill(label: String, color: Int) = TextView(this).apply {
        text = label; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(color); setPadding(dp(10), dp(7), dp(10), dp(7))
        background = rounded(panel2, 30)
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun rounded(color: Int, radius: Int, strokeColor: Int = 0, strokeWidth: Int = 0, ignored: Int = 0) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

private class SimpleTextWatcher(val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
