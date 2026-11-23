package com.sany.iptv

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class NativeMoviesScreen(
    private val context: Context,
    private val seriesMode: Boolean,
    private val light: Boolean,
    private val accent: Int,
    private val onPlay: (String, String) -> Unit,
    private val onManageExtensions: () -> Unit = {}
) {
    private data class Movie(val title: String, val url: String, val category: String, val poster: String)
    private val prefs = context.getSharedPreferences("sany_iptv", Context.MODE_PRIVATE)
    private val bg = Color.BLACK
    private val card = Color.rgb(25, 25, 28)
    private val fg = Color.WHITE
    private val muted = Color.rgb(185, 185, 190)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout

    fun create(): View {
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
        }
        renderMovies()
        return scroll
    }

    private fun readMovies(): MutableList<Movie> {
        val result = mutableListOf<Movie>()
        try {
            val array = JSONArray(prefs.getString("owner_added_movies", "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val url = item.optString("url").trim()
                if (title.isBlank() || !isHttpUrl(url)) continue
                result += Movie(title, url,
                    item.optString("category", "My Movies").trim().ifBlank { "My Movies" },
                    item.optString("poster").trim())
            }
        } catch (_: Exception) { }
        return result
    }

    private fun saveMovies(items: List<Movie>) {
        val array = JSONArray()
        items.forEach { m ->
            array.put(JSONObject().put("title", m.title).put("url", m.url)
                .put("category", m.category).put("poster", m.poster))
        }
        prefs.edit().putString("owner_added_movies", array.toString()).apply()
    }

    private fun renderMovies() {
        body.removeAllViews()
        val movies = readMovies()
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("MY MOVIES", 23, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("EXTENSIONS") { onManageExtensions() }, LinearLayout.LayoutParams(dp(104), dp(40)).apply {
            rightMargin = dp(8)
        })
        top.addView(button("+ ADD") { showAddMovieDialog() }, LinearLayout.LayoutParams(dp(82), dp(40)))
        body.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        val lastUrl = prefs.getString("movies_continue_url", null)
        movies.firstOrNull { it.url == lastUrl }?.let {
            addSectionTitle("Continue Watching")
            addMovieRow(listOf(it), true)
        }

        if (movies.isEmpty()) {
            body.addView(label("এখনো কোনো Movie link যোগ করা হয়নি।\n+ ADD চাপ দিয়ে তোমার মুভি, ক্যাটাগরি ও পোস্টার যোগ করো।",
                14, muted, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(44), dp(12), dp(44))
            })
            return
        }
        movies.map { it.category }.distinct().forEach { category ->
            addSectionTitle(category)
            addMovieRow(movies.filter { it.category == category })
        }
    }

    private fun addSectionTitle(title: String) {
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(title, 20, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label("›", 32, fg, false).apply { gravity = Gravity.CENTER })
        body.addView(heading, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8); bottomMargin = dp(12)
        })
    }

    private fun addMovieRow(items: List<Movie>, continueBadge: Boolean = false) {
        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { movie ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                setOnClickListener { playMovie(movie) }
                setOnLongClickListener { showRemoveDialog(movie); true }
            }
            val frame = FrameLayout(context).apply { background = rounded(card, 16); clipToOutline = true }
            val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(card) }
            frame.addView(poster, FrameLayout.LayoutParams(-1, -1))
            if (movie.poster.isNotBlank() && isHttpUrl(movie.poster)) loadPoster(movie.poster, poster)
            else frame.addView(label("SANY\nMOVIES", 17, muted, true).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, FrameLayout.LayoutParams(-1, -1))

            // Subtle classic glass finish: translucent diagonal sheen and a fine frosted edge.
            // Kept light enough to preserve the original poster artwork and colours.
            val glassSheen = View(context).apply {
                isClickable = false
                isFocusable = false
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        0x38FFFFFF,
                        0x12FFFFFF,
                        0x03FFFFFF,
                        0x1CFFFFFF
                    )
                ).apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), 0x55FFFFFF)
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                }
            }
            frame.addView(glassSheen, FrameLayout.LayoutParams(-1, -1))
            val play = TextView(context).apply {
                text = "▶"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x99000000.toInt()); setStroke(dp(2), Color.WHITE)
                }
            }
            frame.addView(play, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
            if (continueBadge) frame.addView(label("CONTINUE", 10, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(4))
                background = rounded(0xDD111111.toInt(), 20)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
            tile.addView(frame, LinearLayout.LayoutParams(dp(148), dp(222)))
            tile.addView(label(movie.title, 14, fg, false).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                textAlignment = View.TEXT_ALIGNMENT_CENTER; maxLines = 2
                setPadding(dp(3), dp(8), dp(3), 0)
            }, LinearLayout.LayoutParams(dp(148), dp(52)))
            row.addView(tile, LinearLayout.LayoutParams(dp(148), -2).apply { rightMargin = dp(9) })
        }
        horizontal.addView(row)
        body.addView(horizontal, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }

    private fun loadPoster(url: String, target: ImageView) {
        thread(name = "SANY-Movie-Poster") {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000; readTimeout = 7000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 SANY-IPTV")
                }
                connection.inputStream.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) mainHandler.post { target.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) {
            } finally { connection?.disconnect() }
        }
    }

    private fun playMovie(movie: Movie) {
        prefs.edit().putString("movies_continue_url", movie.url).apply()
        onPlay(movie.title, movie.url)
    }

    private fun showAddMovieDialog() {
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(hintText: String, uri: Boolean = false) = EditText(context).apply {
            hint = hintText; setSingleLine(true); setTextColor(Color.WHITE)
            if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val title = field("Movie name")
        val url = field("Movie video URL (HTTP / HTTPS)", true)
        val category = field("Category (e.g. Bollywood, Bangla, Netflix)").apply { setText("My Movies") }
        val poster = field("Poster image URL (optional)", true)
        form.addView(title); form.addView(url); form.addView(category); form.addView(poster)
        AlertDialog.Builder(context).setTitle("Add your movie").setView(form)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("ADD") { _, _ ->
                val name = title.text.toString().trim()
                val video = url.text.toString().trim()
                val group = category.text.toString().trim().ifBlank { "My Movies" }
                val image = poster.text.toString().trim()
                if (name.isBlank() || !isHttpUrl(video) || (image.isNotBlank() && !isHttpUrl(image))) {
                    AlertDialog.Builder(context).setMessage("Movie name এবং সঠিক HTTP/HTTPS video URL দিন। Poster URL দিলে সেটিও সঠিক হতে হবে।")
                        .setPositiveButton("OK", null).show()
                } else {
                    val items = readMovies()
                    if (items.none { it.url == video }) { items.add(Movie(name, video, group, image)); saveMovies(items) }
                    renderMovies()
                }
            }.show()
    }

    private fun showRemoveDialog(movie: Movie) {
        AlertDialog.Builder(context).setTitle("Remove movie?")
            .setMessage(movie.title + "\n\nRemove করতে মুভির কার্ডে long-press করো।")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("REMOVE") { _, _ ->
                saveMovies(readMovies().filterNot { it.url == movie.url })
                if (prefs.getString("movies_continue_url", null) == movie.url) prefs.edit().remove("movies_continue_url").apply()
                renderMovies()
            }.show()
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val parsed = URL(value)
        (parsed.protocol == "http" || parsed.protocol == "https") && parsed.host.isNotBlank()
    }.getOrDefault(false)

    private fun label(value: String, size: Int, color: Int, bold: Boolean) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(value: String, action: () -> Unit) = TextView(context).apply {
        text = value; gravity = Gravity.CENTER; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); isFocusable = true; setPadding(dp(8), dp(8), dp(8), dp(8))
        background = rounded(accent, 12); setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (color == card) {
            if (light) intArrayOf(0xEFFFFFFF.toInt(), 0xDDE6F1FF.toInt(), 0xF7FFFFFF.toInt())
            else intArrayOf(0xAA49688F.toInt(), 0xB0121D30.toInt(), 0xAA202F49.toInt())
        } else intArrayOf(color, color)
    ).apply {
        cornerRadius = dp(radius).toFloat()
        if (color == card) setStroke(dp(1), if (light) 0xDFFFFFFF.toInt() else 0xBDA9CFFF.toInt())
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
