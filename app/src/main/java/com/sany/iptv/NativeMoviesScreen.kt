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
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

class NativeMoviesScreen(
    private val context: Context,
    private val seriesMode: Boolean,
    private val light: Boolean,
    private val accent: Int,
    private val onPlay: (String, String) -> Unit,
    private val onManageExtensions: () -> Unit = {},
    private val onOpenExtensionProvider: (String) -> Unit = {},
    private val onOpenExtensionTitle: (String, String, String) -> Unit = { _, _, _ -> }
) {
    private data class Movie(val title: String, val url: String, val category: String, val poster: String)
    private val prefs = context.getSharedPreferences("sany_iptv", Context.MODE_PRIVATE)
    private val bg = Color.BLACK
    private val card = Color.rgb(25, 25, 28)
    private val fg = Color.WHITE
    private val muted = Color.rgb(185, 185, 190)
    private val mainHandler = Handler(Looper.getMainLooper())
    // Reuse a small worker pool; never create one OS thread per poster request.
    private val posterExecutor = Executors.newFixedThreadPool(3)
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
        top.addView(button("ADD / SELECT") { onManageExtensions() }, LinearLayout.LayoutParams(dp(112), dp(40)).apply {
            rightMargin = dp(8)
        })
        top.addView(button("+ ADD") { showAddMovieDialog() }, LinearLayout.LayoutParams(dp(82), dp(40)))
        body.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        // Only manually selected, installed Movie extensions are rendered here.
        // Anime-only providers such as AniKoto are excluded by their declared TV types.
        val installedExtensions = SanyMovieExtensionManager.installed(context)
        val selectedIds = SanyMovieExtensionManager.selectedIds(context)
        val selectedFiles = installedExtensions.filter { it.id in selectedIds }
            .map { java.io.File(it.packagePath) }
        val selectedPaths = selectedFiles.mapNotNull {
            runCatching { it.canonicalPath }.getOrNull()
        }.toSet()
        val selectedFileNames = selectedFiles.map { it.name }.toSet()
        val extensionProviders = SanyCloudStreamPluginRuntime.providers()
            .filter { provider ->
                val supportsMovies = provider.supportedTypes.any {
                    it == com.lagradost.cloudstream3.TvType.Movie ||
                        it == com.lagradost.cloudstream3.TvType.AnimeMovie
                }
                val sourceFile = runCatching { java.io.File(provider.sourcePlugin) }.getOrNull()
                val belongsToSelectedExtension = sourceFile != null && (
                    runCatching { sourceFile.canonicalPath in selectedPaths }.getOrDefault(false) ||
                        sourceFile.name in selectedFileNames
                    )
                supportsMovies && belongsToSelectedExtension
            }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }

        if (extensionProviders.isNotEmpty()) {
            addSectionTitle("SELECTED MOVIE EXTENSIONS")
            extensionProviders.forEach { provider ->
                val entry = button("▶   ${provider.name}   •   SEARCH MOVIES") {
                    onOpenExtensionProvider(provider.name)
                }
                entry.gravity = Gravity.CENTER_VERTICAL
                entry.textSize = 14f
                body.addView(entry, LinearLayout.LayoutParams(-1, dp(52)).apply {
                    bottomMargin = dp(8)
                })
                val homeHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                body.addView(homeHost, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(14)
                })
                loadExtensionHome(provider.name, homeHost)
            }
        } else {
            body.addView(label(
                "কোনো Movie Extension নির্বাচন করা হয়নি। ADD / SELECT চাপ দিয়ে ইনস্টল করা Movie provider নির্বাচন করুন।",
                13, muted, false
            ).apply { setPadding(dp(4), dp(12), dp(4), dp(16)) })
        }

        val lastUrl = prefs.getString("movies_continue_url", null)
        movies.firstOrNull { it.url == lastUrl }?.let {
            addSectionTitle("Continue Watching")
            addMovieRow(listOf(it), true)
        }

        if (movies.isEmpty()) {
            if (extensionProviders.isEmpty()) {
                body.addView(label("এখনো কোনো Movie link বা active Movie extension নেই।\nEXTENSIONS চাপ দিয়ে Movie provider install করো।",
                    14, muted, false).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(44), dp(12), dp(44))
                })
            }
            return
        }
        movies.map { it.category }.distinct().forEach { category ->
            addSectionTitle(category)
            addMovieRow(movies.filter { it.category == category })
        }
    }

    /** Load the provider's own homepage catalogue into the separate Movies screen. */
    private fun loadExtensionHome(providerName: String, host: LinearLayout) {
        host.removeAllViews()
        host.addView(label("Loading $providerName movies…", 13, muted, false))
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val result = runCatching {
                kotlinx.coroutines.withTimeout(25_000L) {
                    SanyCloudStreamPluginRuntime.homePage(providerName, 1)
                }
            }
            mainHandler.post {
                if (host.parent == null) return@post
                host.removeAllViews()
                result.onSuccess { shelves ->
                    val visibleShelves = shelves.filter { it.list.isNotEmpty() }
                    if (visibleShelves.isEmpty()) {
                        host.addView(label(
                            "$providerName-এর homepage catalogue নেই। SEARCH MOVIES দিয়ে খুঁজুন।",
                            13, muted, false
                        ))
                    } else {
                        visibleShelves.forEach { shelf ->
                            host.addView(label(shelf.name, 17, fg, true),
                                LinearLayout.LayoutParams(-1, -2).apply {
                                    topMargin = dp(8); bottomMargin = dp(8)
                                })
                            val row = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                            }
                            shelf.list.forEach { item ->
                                val tile = FrameLayout(context).apply {
                                    background = rounded(card, 14)
                                    clipToOutline = true
                                    isFocusable = true
                                    setOnClickListener {
                                        onOpenExtensionTitle(providerName, item.url, item.name)
                                    }
                                }
                                val poster = ImageView(context).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    setBackgroundColor(card)
                                }
                                tile.addView(poster, FrameLayout.LayoutParams(dp(142), dp(206)))
                                item.posterUrl?.takeIf { isHttpUrl(it) }?.let { loadPoster(it, poster) }
                                val shade = View(context).apply {
                                    background = GradientDrawable(
                                        GradientDrawable.Orientation.TOP_BOTTOM,
                                        intArrayOf(0x00000000, 0xDD000000.toInt())
                                    )
                                }
                                tile.addView(shade, FrameLayout.LayoutParams(-1, dp(86), Gravity.BOTTOM))
                                tile.addView(label(item.name, 12, Color.WHITE, true).apply {
                                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                                    maxLines = 3
                                    setPadding(dp(8), dp(8), dp(8), dp(10))
                                }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                                row.addView(tile, LinearLayout.LayoutParams(dp(142), dp(206)).apply {
                                    rightMargin = dp(9)
                                })
                            }
                            val horizontal = HorizontalScrollView(context).apply {
                                isHorizontalScrollBarEnabled = false
                                overScrollMode = View.OVER_SCROLL_NEVER
                                addView(row)
                            }
                            host.addView(horizontal, LinearLayout.LayoutParams(-1, -2).apply {
                                bottomMargin = dp(10)
                            })
                        }
                    }
                }.onFailure { error ->
                    host.addView(label(
                        "Movie catalogue load failed: ${error.message ?: error.javaClass.simpleName}",
                        13, muted, false
                    ))
                }
            }
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
        target.tag = url
        posterExecutor.execute {
            var connection: HttpURLConnection? = null
            var bitmap: android.graphics.Bitmap? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 5000
                    instanceFollowRedirects = true
                    useCaches = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 SANY-IPTV")
                    setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                }
                connection.inputStream.use { stream ->
                    val bytes = stream.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
                val decoded = bitmap
                if (decoded != null) {
                    mainHandler.post {
                        if (target.tag == url && !decoded.isRecycled) target.setImageBitmap(decoded)
                        else if (!decoded.isRecycled) decoded.recycle()
                    }
                }
            } catch (_: Exception) {
                bitmap?.takeUnless { it.isRecycled }?.recycle()
            } finally {
                connection?.disconnect()
            }
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
