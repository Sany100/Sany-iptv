package com.sany.iptv

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Native TMDB catalogue for Movies and Series.
 * CloudStream-specific provider classes require the CloudStream runtime and are not
 * directly loadable in SANY IPTV. Until a native provider adapter is audited,
 * playback accepts a user-authorized direct HTTP(S) media URL.
 */
class NativeMoviesScreen(
    private val context: Context,
    private val seriesMode: Boolean,
    private val light: Boolean,
    private val accent: Int,
    private val onPlay: (String, String) -> Unit
) {
    companion object {
        private const val API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
        private const val API = "https://api.themoviedb.org/3"
        private const val IMAGE = "https://image.tmdb.org/t/p/w500"
    }

    private val worker = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val bg = if (light) Color.rgb(242, 246, 252) else Color.BLACK
    private val card = if (light) Color.WHITE else Color.rgb(18, 21, 28)
    private val fg = if (light) Color.rgb(22, 28, 38) else Color.WHITE
    private val muted = if (light) Color.rgb(90, 101, 118) else Color.rgb(160, 170, 185)
    private val type = if (seriesMode) "tv" else "movie"
    private lateinit var body: LinearLayout
    private lateinit var search: EditText
    private var detailId = -1

    fun create(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(bg)
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        search = EditText(context).apply {
            hint = if (seriesMode) "Search series…" else "Search movies…"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(fg)
            setHintTextColor(muted)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(card)
            setOnEditorActionListener { _, _, _ -> searchNow(); true }
        }
        row.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(button("SEARCH") { searchNow() }, LinearLayout.LayoutParams(dp(92), dp(48)).apply { leftMargin = dp(7) })
        root.addView(row)
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        root.addView(ScrollView(context).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        loadHome()
        return root
    }

    private fun loadHome() {
        loading()
        request(API + "/trending/" + type + "/week") { json ->
            renderList(json?.optJSONArray("results"), if (seriesMode) "Trending Series" else "Trending Movies")
        }
    }

    private fun searchNow() {
        val q = search.text.toString().trim()
        if (q.isEmpty()) { loadHome(); return }
        loading()
        request(API + "/search/" + type + "?query=" + URLEncoder.encode(q, "UTF-8") + "&include_adult=false") { json ->
            renderList(json?.optJSONArray("results"), "Search Results")
        }
    }

    private fun renderList(items: org.json.JSONArray?, heading: String) {
        body.removeAllViews()
        title(heading)
        if (items == null || items.length() == 0) { message("কোনো ফলাফল পাওয়া যায়নি।"); return }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            if (id <= 0) continue
            val name = item.optString(if (seriesMode) "name" else "title", "Untitled")
            val date = item.optString(if (seriesMode) "first_air_date" else "release_date")
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(card)
                isFocusable = true
                setOnClickListener { openDetails(id) }
            }
            val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            row.addView(poster, LinearLayout.LayoutParams(dp(84), dp(118)))
            loadPoster(item.optString("poster_path"), poster)
            val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            info.addView(label(name, 15, fg, true))
            info.addView(label(date.take(4) + "  •  ★ " + item.optDouble("vote_average", 0.0).toString(), 12, accent, true))
            val overview = item.optString("overview")
            if (overview.isNotBlank()) info.addView(label(overview, 12, muted, false).apply { maxLines = 3 })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun openDetails(id: Int) {
        detailId = id
        loading()
        request(API + "/" + type + "/" + id + "?append_to_response=credits") { data ->
            body.removeAllViews()
            if (data == null) { message("Details load করা যায়নি। আবার চেষ্টা করুন।"); return@request }
            val name = data.optString(if (seriesMode) "name" else "title", "Details")
            val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            body.addView(poster, LinearLayout.LayoutParams(dp(148), dp(218)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
            loadPoster(data.optString("poster_path"), poster)
            title(name)
            message("★ " + data.optDouble("vote_average", 0.0).toString() + "  •  " + data.optString(if (seriesMode) "first_air_date" else "release_date").take(4))
            message(data.optString("overview").ifBlank { "বিবরণ পাওয়া যায়নি।" })
            val genres = data.optJSONArray("genres")
            if (genres != null) {
                val names = (0 until genres.length()).mapNotNull { genres.optJSONObject(it)?.optString("name") }
                if (names.isNotEmpty()) message(names.joinToString(" • "))
            }
            if (seriesMode) {
                title("SEASONS")
                val seasons = data.optJSONArray("seasons")
                if (seasons != null) for (i in 0 until seasons.length()) {
                    val s = seasons.optJSONObject(i) ?: continue
                    val n = s.optInt("season_number", -1)
                    if (n < 0) continue
                    body.addView(button("Season " + n + "  •  " + s.optInt("episode_count") + " Episodes") {
                        openSeason(id, n, name)
                    }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })
                }
            } else {
                body.addView(button("FIND MULTIMOVIES SOURCES") { findMultimoviesSources(name) }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
                body.addView(button("PLAY WITH DIRECT URL") { askStream(name) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
            }
            if (seriesMode) {
                body.addView(button("FIND MULTIMOVIES SOURCES") { findMultimoviesSources(name) }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
            }
            body.addView(button("← BACK") { loadHome() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        }
    }

    private fun openSeason(id: Int, season: Int, seriesName: String) {
        loading()
        request(API + "/tv/" + id + "/season/" + season) { data ->
            body.removeAllViews()
            title(seriesName + " — Season " + season)
            val episodes = data?.optJSONArray("episodes")
            if (episodes == null || episodes.length() == 0) { message("Episode পাওয়া যায়নি।"); return@request }
            for (i in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(i) ?: continue
                val number = ep.optInt("episode_number", i + 1)
                val epName = ep.optString("name", "Episode " + number)
                body.addView(button("Episode " + number + " — " + epName) {
                    findMultimoviesSources(seriesName + " S" + season + "E" + number + " — " + epName)
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
            }
            body.addView(button("← BACK TO SERIES") { openDetails(id) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        }
    }

    private fun findMultimoviesSources(title: String) {
        body.removeAllViews()
        title("MULTIMOVIES SOURCES")
        message("Multimovies-এর প্রকাশ্য সার্ভার তালিকা খোঁজা হচ্ছে…")
        worker.execute {
            val hits = MultimoviesNativeResolver.search(title, seriesMode)
            val found = hits.flatMap { hit ->
                MultimoviesNativeResolver.sources(hit.url).map { hit.title to it }
            }
            main.post {
                body.removeAllViews()
                title("MULTIMOVIES SOURCES")
                if (found.isEmpty()) {
                    message("কোনো প্রকাশ্য সরাসরি stream পাওয়া যায়নি। সাইটের page/server unavailable, protected, অথবা embed-only হতে পারে।")
                } else {
                    found.forEach { (matchTitle, source) ->
                        val label = matchTitle + " — " + source.name
                        if (source.playable) {
                            body.addView(button("PLAY  •  " + label) { onPlay(label, source.url) },
                                LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })
                        } else {
                            message(label + " — Embed server; সরাসরি HLS/MP4 পাওয়া যায়নি।")
                        }
                    }
                }
                body.addView(button("← BACK") { loadHome() },
                    LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
            }
        }
    }

    private fun askStream(name: String) {
        val input = EditText(context).apply {
            hint = "Authorized direct HLS / MP4 URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        AlertDialog.Builder(context)
            .setTitle("Play: " + name)
            .setMessage("অনুমোদিত সরাসরি stream URL দিন।")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("PLAY") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("https://") || url.startsWith("http://")) onPlay(name, url)
                else message("সঠিক HTTP বা HTTPS URL দিন।")
            }.show()
    }

    private fun request(path: String, callback: (JSONObject?) -> Unit) {
        worker.execute {
            val result = runCatching {
                val url = path + (if (path.contains("?")) "&" else "?") + "api_key=" + API_KEY
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 9000
                connection.readTimeout = 12000
                try {
                    if (connection.responseCode in 200..299) JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) else null
                } finally { connection.disconnect() }
            }.getOrNull()
            main.post { if (::body.isInitialized) callback(result) }
        }
    }

    private fun loadPoster(path: String?, target: ImageView) {
        if (path.isNullOrBlank()) return
        worker.execute {
            val bitmap = runCatching { URL(IMAGE + path).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bitmap != null) main.post { target.setImageBitmap(bitmap) }
        }
    }

    private fun loading() {
        body.removeAllViews()
        body.addView(ProgressBar(context), LinearLayout.LayoutParams(dp(46), dp(46)).apply { gravity = Gravity.CENTER; topMargin = dp(30) })
        message("Loading…")
    }

    private fun title(value: String) { body.addView(label(value, 18, accent, true).apply { setPadding(dp(3), dp(7), dp(3), dp(9)) }) }
    private fun message(value: String) { if (value.isNotBlank()) body.addView(label(value, 13, muted, false).apply { setPadding(dp(3), dp(3), dp(3), dp(9)) }) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun button(value: String, action: () -> Unit) = TextView(context).apply {
        text = value; gravity = Gravity.CENTER; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); isFocusable = true; setPadding(dp(8), dp(8), dp(8), dp(8))
        background = rounded(accent); setOnClickListener { action() }
    }
    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
