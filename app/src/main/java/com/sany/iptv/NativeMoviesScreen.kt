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
import android.text.Editable
import android.text.TextWatcher
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
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

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
    private class FastMovieScrollView(context: Context) : ScrollView(context) {
        override fun fling(velocityY: Int) { super.fling((velocityY * 1.45f).toInt()) }
    }
    private class FastMovieHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
        override fun fling(velocityX: Int) { super.fling((velocityX * 1.45f).toInt()) }
    }
    private data class Movie(val title: String, val url: String, val category: String, val poster: String)
    private data class MovieShelfCard(val title: String, val poster: String, val onClick: () -> Unit)
    private val prefs = context.getSharedPreferences("sany_iptv", Context.MODE_PRIVATE)
    private val bg = Color.BLACK
    private val card = Color.rgb(25, 25, 28)
    private val fg = Color.WHITE
    private val muted = Color.rgb(185, 185, 190)
    private val mainHandler = Handler(Looper.getMainLooper())
    // Keep image networking and bitmap decoding off the UI thread with a bounded worker pool.
    private val posterExecutor = Executors.newFixedThreadPool(3)
    // Keep homepage jobs attached to this screen and cancel stale work on rerender.
    private val homeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val homeJobs = mutableListOf<Job>()
    private var movieSearchJob: Job? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchGeneration = 0
    private var activeMovieProviders: List<String> = emptyList()
    private var searchResultsHost: LinearLayout? = null
    private lateinit var body: LinearLayout
    // Shared outer scroll container lets numbered category pages always reopen at the top.
    private var catalogueScroll: ScrollView? = null

    fun create(): View {
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Full-bleed Movie catalogue: remove horizontal gutters.
            setPadding(0, dp(8), 0, dp(24))
            setBackgroundColor(bg)
        }
        val scroll = FastMovieScrollView(context).apply {
            isFillViewport = true
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
        }
        catalogueScroll = scroll
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
        homeJobs.forEach { it.cancel() }
        homeJobs.clear()
        body.removeAllViews()
        val movies = readMovies()
        val searchBanner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent, 0xFF172B4D.toInt())).apply { cornerRadius = dp(18).toFloat() }
        }
        searchBanner.addView(label("DISCOVER MOVIES", 20, Color.WHITE, true))
        val searchBannerSubtitle = label("Search all selected providers at once", 12, 0xFFE5ECF7.toInt(), false)
        searchBanner.addView(searchBannerSubtitle,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val searchField = EditText(context).apply {
            hint = "Movie name লিখুন…"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFD5DFEF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0x33202020)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0x88FFFFFF.toInt())
            }
        }
        searchBanner.addView(searchField, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        body.addView(searchBanner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val liveResults = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        searchResultsHost = liveResults
        body.addView(liveResults, LinearLayout.LayoutParams(-1, -2))

        // Only manually selected, installed Movie extensions are rendered here.
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

        activeMovieProviders = extensionProviders.map { it.name }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                searchBannerSubtitle.text = if (query.isBlank()) {
                    "Search all selected providers at once"
                } else {
                    "LIVE SEARCH • $query"
                }
                scheduleMovieSearch(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        if (extensionProviders.isNotEmpty()) {
            addSectionTitle("MOVIE CATEGORIES")
            val homeHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(homeHost, LinearLayout.LayoutParams(-1, -2))
            loadMergedExtensionHome(extensionProviders.map { it.name }, homeHost)
        } else {
            body.addView(label(
                "কোনো Movie Extension নির্বাচন করা হয়নি। ADD / SELECT চাপ দিয়ে ইনস্টল করা Movie provider নির্বাচন করুন।",
                13, muted, false
            ).apply { setPadding(dp(4), dp(12), dp(4), dp(16)) })
        }

        val lastUrl = prefs.getString("movies_continue_url", null)
        movies.firstOrNull { it.url == lastUrl }?.let { movie ->
            addMovieShelf("Continue Watching", listOf(
                MovieShelfCard(movie.title, movie.poster) { playMovie(movie) }
            ), showMore = false)
        }

        movies.groupBy { it.category }.forEach { (category, categoryMovies) ->
            val cards = categoryMovies.map { movie ->
                MovieShelfCard(movie.title, movie.poster) { playMovie(movie) }
            }
            addMovieShelf(category, cards)
        }
        if (movies.isEmpty() && extensionProviders.isEmpty()) {
            body.addView(label(
                "এখনো কোনো Movie link বা active Movie extension নেই।\nEXTENSIONS চাপ দিয়ে Movie provider install করো।",
                14, muted, false
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(44), dp(12), dp(44))
            })
        }
    }

    /** Merge selected providers into category shelves and remove duplicate titles. */
    private fun loadMergedExtensionHome(providerNames: List<String>, host: LinearLayout) {
        host.removeAllViews()
        host.addView(label("Loading movie categories…", 13, muted, false))
        val job = homeScope.launch {
            val result = runCatching {
                kotlinx.coroutines.withTimeout(30_000L) {
                    kotlinx.coroutines.coroutineScope {
                        providerNames.map { providerName ->
                            this.async(Dispatchers.IO) {
                                providerName to runCatching {
                                    SanyCloudStreamPluginRuntime.homePage(providerName, 1)
                                }.getOrDefault(emptyList())
                            }
                        }.map { it.await() }
                    }
                }
            }
            mainHandler.post {
                if (host.parent == null) return@post
                host.removeAllViews()
                result.onSuccess { providerShelves ->
                    val categoryItems = LinkedHashMap<String, LinkedHashMap<String, MovieShelfCard>>()
                    providerShelves.forEach { (providerName, shelves) ->
                        shelves.forEach shelfLoop@{ shelf ->
                            val category = shelf.name.trim().ifBlank { "Movies" }
                            val bucket = categoryItems.getOrPut(category) { LinkedHashMap() }
                            shelf.list.forEach itemLoop@{ item ->
                                val title = item.name.trim()
                                val url = item.url.trim()
                                if (title.isBlank() || url.isBlank()) return@itemLoop
                                val key = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
                                if (key.isBlank() || bucket.containsKey(key)) return@itemLoop
                                bucket[key] = MovieShelfCard(title, item.posterUrl.orEmpty()) {
                                    onOpenExtensionTitle(providerName, url, title)
                                }
                            }
                        }
                    }
                    val mergedShelves = categoryItems.entries.sortedBy { it.key.lowercase() }
                        .filter { it.value.isNotEmpty() }
                    if (mergedShelves.isEmpty()) {
                        host.addView(label("Homepage category পাওয়া যায়নি। উপরের search ব্যবহার করুন।", 13, muted, false))
                    } else {
                        renderMovieCategoryPage(host, mergedShelves, 1)
                    }
                    val errors = providerShelves.filter { it.second.isEmpty() }.map { it.first }
                    if (errors.isNotEmpty()) host.addView(label(
                        "কিছু provider-এর homepage পাওয়া যায়নি: ${errors.joinToString()}", 11, muted, false))
                }.onFailure { error ->
                    host.addView(label("Movie catalogue load failed: ${error.message ?: error.javaClass.simpleName}", 13, muted, false))
                }
            }
        }
        homeJobs.add(job)
    }

    /** Show six Movie categories at a time, with numbered navigation below. */
    private fun renderMovieCategoryPage(
        host: LinearLayout,
        shelves: List<Map.Entry<String, LinkedHashMap<String, MovieShelfCard>>>,
        requestedPage: Int
    ) {
        if (host.parent == null) return
        val pageSize = 6
        val pageCount = ((shelves.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = requestedPage.coerceIn(1, pageCount)
        host.removeAllViews()
        shelves.drop((page - 1) * pageSize).take(pageSize).forEach { (category, cards) ->
            addMovieShelf(category, cards.values.toList(), host)
        }
        if (pageCount > 1) {
            val pager = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(22))
            }
            pager.addView(button("‹") {
                renderMovieCategoryPage(host, shelves, page - 1)
                catalogueScroll?.post { catalogueScroll?.scrollTo(0, 0) }
            }.apply {
                isEnabled = page > 1
                alpha = if (page > 1) 1f else 0.4f
            })
            val first = maxOf(1, minOf(page - 2, pageCount - 4))
            val last = minOf(pageCount, first + 4)
            for (number in first..last) {
                pager.addView(button(number.toString()) {
                    renderMovieCategoryPage(host, shelves, number)
                    catalogueScroll?.post { catalogueScroll?.scrollTo(0, 0) }
                }.apply {
                    alpha = if (number == page) 1f else 0.72f
                }, LinearLayout.LayoutParams(dp(42), dp(40)).apply {
                    leftMargin = dp(3)
                    rightMargin = dp(3)
                })
            }
            pager.addView(button("›") {
                renderMovieCategoryPage(host, shelves, page + 1)
                catalogueScroll?.post { catalogueScroll?.scrollTo(0, 0) }
            }.apply {
                isEnabled = page < pageCount
                alpha = if (page < pageCount) 1f else 0.4f
            })
            host.addView(pager, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** Debounced live search across every selected Movie provider. */
    private fun scheduleMovieSearch(rawQuery: String) {
        searchHandler.removeCallbacksAndMessages("movie-search")
        val query = rawQuery.trim()
        val host = searchResultsHost ?: return
        searchGeneration += 1
        val generation = searchGeneration
        movieSearchJob?.cancel()
        host.removeAllViews()
        if (query.isBlank()) return
        host.addView(label("Searching all providers…", 13, muted, false))
        val searchTask = Runnable {
            movieSearchJob = homeScope.launch {
                val result = runCatching {
                    kotlinx.coroutines.withTimeout(20_000L) {
                        kotlinx.coroutines.coroutineScope {
                            activeMovieProviders.map { providerName ->
                                this.async(Dispatchers.IO) {
                                    providerName to runCatching {
                                        SanyCloudStreamPluginRuntime.search(providerName, query)
                                    }.getOrDefault(emptyList())
                                }
                            }.map { it.await() }
                        }
                    }
                }
                mainHandler.post {
                    if (generation != searchGeneration || host.parent == null) return@post
                    host.removeAllViews()
                    result.onSuccess { providerResults ->
                        val unique = LinkedHashMap<String, Triple<String, String, MovieShelfCard>>()
                        providerResults.forEach { (providerName, items) ->
                            items.forEach { item ->
                                val title = item.name.trim()
                                val url = item.url.trim()
                                val key = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
                                if (title.isNotBlank() && url.isNotBlank() && key.isNotBlank() && !unique.containsKey(key)) {
                                    unique[key] = Triple(
                                        providerName,
                                        url,
                                        MovieShelfCard(title, item.posterUrl.orEmpty()) {
                                            onOpenExtensionTitle(providerName, url, title)
                                        }
                                    )
                                }
                            }
                        }
                        if (unique.isEmpty()) host.addView(label("কোনো Movie পাওয়া যায়নি।", 13, muted, false))
                        else addMovieShelf("SEARCH RESULTS • ${unique.size}", unique.values.map { it.third }, host, showMore = false)
                    }.onFailure { error ->
                        host.addView(label("Search failed: ${error.message ?: error.javaClass.simpleName}", 13, muted, false))
                    }
                }
            }
        }
        searchHandler.postAtTime(searchTask, "movie-search", android.os.SystemClock.uptimeMillis() + 450L)
    }

    /** Anime-style category rail: three large posters visible, swipe sideways for the rest. */
    private fun addMovieShelf(
        title: String,
        cards: List<MovieShelfCard>,
        parent: LinearLayout = body,
        showMore: Boolean = true
    ) {
        if (cards.isEmpty()) return
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(label(title, 19, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (showMore) {
            heading.addView(label("SEE MORE  ⌄", 12, accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(8), dp(8))
                isFocusable = true
                contentDescription = "See more $title"
                setOnClickListener { openMovieShelfPage(title, cards) }
            })
        }
        parent.addView(heading, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        val scroller = FastMovieHorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val rail = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        scroller.addView(rail)
        val screenWidthDp = context.resources.displayMetrics.widthPixels /
            context.resources.displayMetrics.density
        // Fit exactly three poster cards across the available screen width.
        val cardWidth = (screenWidthDp / 3f).toInt().coerceAtLeast(96)
        val posterHeight = (cardWidth * 1.45f).toInt()
        cards.forEach { item ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                setOnClickListener { item.onClick() }
            }
            val isNowPlaying = prefs.getString("movies_now_playing_title", "").equals(item.title, ignoreCase = true)
            val frame = FrameLayout(context).apply {
                background = rounded(card, 14)
                clipToOutline = true
                if (isNowPlaying) {
                    foreground = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(3), 0xFF35B8FF.toInt())
                    }
                    elevation = dp(8).toFloat()
                }
            }
            if (isNowPlaying) frame.addView(label("▶ PLAYING", 9, Color.WHITE, true).apply {
                setPadding(dp(7), dp(4), dp(7), dp(4))
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF36C5FF.toInt(), 0xFF0875F5.toInt())).apply { cornerRadius = dp(16).toFloat() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(6), dp(6), 0, 0)
            })
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(card)
            }
            frame.addView(poster, FrameLayout.LayoutParams(-1, -1))
            if (item.poster.startsWith("http")) loadPoster(item.poster, poster)
            else frame.addView(label("SANY\nMOVIES", 16, muted, true).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, FrameLayout.LayoutParams(-1, -1))
            tile.addView(frame, LinearLayout.LayoutParams(dp(cardWidth), dp(posterHeight)))
            tile.addView(label(item.title, 13, fg, false).apply {
                maxLines = 2
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(6), dp(3), 0)
            }, LinearLayout.LayoutParams(dp(cardWidth), dp(44)))
            rail.addView(tile, LinearLayout.LayoutParams(dp(cardWidth), -2).apply {
                rightMargin = dp(2)
            })
        }
        parent.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(18)
        })
    }

    /** Dedicated category dropdown page, matching Anime's three-column poster grid. */
    private fun openMovieShelfPage(title: String, cards: List<MovieShelfCard>) {
        if (cards.isEmpty()) return
        homeJobs.forEach { it.cancel() }
        homeJobs.clear()
        body.removeAllViews()

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(14))
        }
        header.addView(button("←  BACK") { renderMovies() })
        header.addView(label(title, 20, fg, true), LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(12)
        })
        header.addView(label("${cards.size} titles", 12, muted, false))
        body.addView(header, LinearLayout.LayoutParams(-1, -2))

        val screenWidthDp = context.resources.displayMetrics.widthPixels /
            context.resources.displayMetrics.density
        val cardWidth = ((screenWidthDp - 12f) / 3f).toInt().coerceAtLeast(96)
        val posterHeight = (cardWidth * 1.42f).toInt()
        var gridRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        cards.forEachIndexed { index, item ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                setPadding(dp(1), 0, dp(1), dp(8))
                setOnClickListener { item.onClick() }
            }
            val isNowPlaying = prefs.getString("movies_now_playing_title", "").equals(item.title, ignoreCase = true)
            val frame = FrameLayout(context).apply {
                background = rounded(card, 12)
                clipToOutline = true
                if (isNowPlaying) {
                    foreground = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(3), 0xFF35B8FF.toInt())
                    }
                    elevation = dp(8).toFloat()
                }
            }
            if (isNowPlaying) frame.addView(label("▶ PLAYING", 9, Color.WHITE, true).apply {
                setPadding(dp(7), dp(4), dp(7), dp(4))
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF36C5FF.toInt(), 0xFF0875F5.toInt())).apply { cornerRadius = dp(16).toFloat() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(6), dp(6), 0, 0)
            })
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(card)
            }
            frame.addView(poster, FrameLayout.LayoutParams(-1, -1))
            if (item.poster.startsWith("http")) loadPoster(item.poster, poster)
            else frame.addView(label("SANY\nMOVIES", 16, muted, true).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, FrameLayout.LayoutParams(-1, -1))
            tile.addView(frame, LinearLayout.LayoutParams(-1, dp(posterHeight)))
            tile.addView(label(item.title, 12, fg, true).apply {
                maxLines = 2
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(5), dp(2), dp(3))
            }, LinearLayout.LayoutParams(-1, dp(42)))
            gridRow.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
            if (index % 3 == 2) {
                body.addView(gridRow, LinearLayout.LayoutParams(-1, -2))
                gridRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    clipChildren = false
                    clipToPadding = false
                }
            }
        }
        if (cards.size % 3 != 0) {
            repeat(3 - (cards.size % 3)) {
                gridRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            body.addView(gridRow, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(View(context), LinearLayout.LayoutParams(1, dp(24)))
    }

    private fun addSectionTitle(title: String) {
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(label(title, 19, fg, true))
        body.addView(heading, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })
    }

    private fun loadPoster(url: String, target: ImageView) {
        target.tag = url
        posterExecutor.execute {
            var connection: HttpURLConnection? = null
            var decoded: android.graphics.Bitmap? = null
            var tempFile: java.io.File? = null
            try {
                val directory = java.io.File(context.cacheDir, "movie_posters").apply { mkdirs() }
                val cachedFile = java.io.File(directory, url.hashCode().toString() + ".img")
                if (!cachedFile.exists() || cachedFile.length() == 0L) {
                    val temp = java.io.File(directory, cachedFile.name + ".tmp")
                    tempFile = temp
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2500
                        readTimeout = 3500
                        instanceFollowRedirects = true
                        useCaches = true
                        setRequestProperty("User-Agent", "Mozilla/5.0 SANY-IPTV")
                        setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                    }
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > 8 * 1024 * 1024) throw java.io.IOException("Poster image exceeds 8 MB")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    connection.disconnect()
                    connection = null
                    if (!temp.renameTo(cachedFile)) {
                        temp.copyTo(cachedFile, overwrite = true)
                        temp.delete()
                    }
                }
                if (cachedFile.isFile && cachedFile.length() in 1..(8L * 1024L * 1024L)) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(cachedFile.absolutePath, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 420) sample *= 2
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    decoded = BitmapFactory.decodeFile(cachedFile.absolutePath, options)
                }
                val bitmap = decoded
                if (bitmap != null) {
                    mainHandler.post {
                        if (target.tag == url && !bitmap.isRecycled) target.setImageBitmap(bitmap)
                        else if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            } catch (_: Exception) {
                decoded?.takeUnless { it.isRecycled }?.recycle()
                tempFile?.delete()
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
