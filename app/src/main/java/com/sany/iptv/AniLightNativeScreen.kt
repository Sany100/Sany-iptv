package com.sany.iptv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import java.net.URLEncoder
import kotlin.concurrent.thread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Native AniLight browser backed by the provider's public JSON API. */
class AniLightNativeScreen(
    private val context: Context,
    private val light: Boolean,
    private val accent: Int,
    private val onPlay: (String, String, String?) -> Unit
) {
    @Volatile private var api = "https://api.anilight.live/api"
    @Volatile private var site = "https://anilight.live"
    @Volatile private var domainCheckedAt = 0L
    @Volatile private var homepageCache: JSONObject? = null
    @Volatile private var homepageCheckedAt = 0L
    private var homeRequestVersion = 0
    // Keep recently loaded anime posters in memory so SUB/DUB switches do not re-download them.
    private val posterCache = ConcurrentHashMap<String, Bitmap>()
    private val posterExecutor = Executors.newFixedThreadPool(4)
    private val bg = if (light) 0xFFF2F6FC.toInt() else Color.BLACK
    private val fg = if (light) 0xFF141B26.toInt() else Color.WHITE
    private val muted = if (light) 0xFF5A6576.toInt() else 0xFF94A0B4.toInt()
    private val surface = if (light) Color.WHITE else 0xFF16191F.toInt()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var contentHost: LinearLayout
    private var catalogueScroll: ScrollView? = null
    private var autoPageLoading = false
    private var slug = ""
    private var animeId = 0
    private var animeTitle = ""
    private var language = "sub"
    private var anime: JSONObject? = null
    private var isHomePage = true
    // Category dropdown pages must be exited with the visible Back button, never a swipe gesture.
    private var isShelfCategoryPage = false
    private var filterPage = 1
    private var filterSort = "POPULARITY_DESC"
    private var filterGenre: String? = null
    private var filterHasNext = false
    private var showingFilterResults = false
    @Volatile private var session: String? = null
    @Volatile private var sessionExpiry = 0L

    // Anime-only page history. Detached page views preserve their exact UI and scroll state.
    private data class AnimePageSnapshot(
        val children: List<View>,
        val scrollY: Int,
        val isHomePage: Boolean,
        val isShelfCategoryPage: Boolean,
        val showingFilterResults: Boolean,
        val filterHasNext: Boolean,
        val filterPage: Int,
        val filterSort: String,
        val filterGenre: String?,
        val autoPageLoading: Boolean,
        val slug: String,
        val animeId: Int,
        val animeTitle: String,
        val language: String,
        val anime: JSONObject?,
        val contentHost: LinearLayout?
    )
    private val pageHistory = java.util.ArrayDeque<AnimePageSnapshot>()

    private fun pushCurrentPage() {
        if (!::body.isInitialized || body.childCount == 0) return
        val savedContentHost = if (isHomePage && ::contentHost.isInitialized) contentHost else null
        val children = (0 until body.childCount).map { body.getChildAt(it) }
        pageHistory.addLast(
            AnimePageSnapshot(
                children, catalogueScroll?.scrollY ?: 0, isHomePage,
                isShelfCategoryPage, showingFilterResults, filterHasNext, filterPage, filterSort,
                filterGenre, autoPageLoading, slug, animeId, animeTitle,
                language, anime, savedContentHost
            )
        )
        body.removeAllViews()
        // Keep the saved homepage view untouched while the destination renders.
        if (savedContentHost != null) {
            contentHost = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(24))
            }
            body.addView(contentHost, bottom(12))
        }
    }

    private fun restorePreviousPage(): Boolean {
        if (pageHistory.isEmpty() || !::body.isInitialized) return false
        val previous = pageHistory.removeLast()
        body.removeAllViews()
        previous.children.forEach { body.addView(it) }
        if (previous.contentHost != null) contentHost = previous.contentHost
        isHomePage = previous.isHomePage
        isShelfCategoryPage = previous.isShelfCategoryPage
        showingFilterResults = previous.showingFilterResults
        filterHasNext = previous.filterHasNext
        filterPage = previous.filterPage
        filterSort = previous.filterSort
        filterGenre = previous.filterGenre
        autoPageLoading = previous.autoPageLoading
        slug = previous.slug
        animeId = previous.animeId
        animeTitle = previous.animeTitle
        language = previous.language
        anime = previous.anime
        catalogueScroll?.post { catalogueScroll?.scrollTo(0, previous.scrollY) }
        return true
    }

    private fun goHome() {
        pageHistory.clear()
        home()
    }

    /** Returns true when Back was consumed inside the AniLight screen. */
    fun handleBack(): Boolean {
        if (restorePreviousPage()) return true
        if (!isHomePage) {
            home()
            return true
        }
        return false
    }

    fun create(): View {
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
            setOnScrollChangeListener { view: View, _: Int, scrollY: Int, _: Int, _: Int ->
                val sv = view as ScrollView
                val nearBottom = scrollY + sv.height >= body.height - dp(520)
                if (nearBottom && showingFilterResults && filterHasNext && !autoPageLoading) {
                    autoPageLoading = true
                    loadFiltered(filterSort, filterGenre, filterPage + 1, keepHomeChrome = isHomePage)
                }
            }
            var pageSwipeStartY = 0f
            setOnTouchListener { view, event ->
                // Keep the category grid open while users scroll/swipe upward; its top Back button is the only exit.
                if (isShelfCategoryPage || pageHistory.isEmpty() || isHomePage) return@setOnTouchListener false
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        pageSwipeStartY = event.y
                        false
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val swipeUp = pageSwipeStartY - event.y
                        val startedNearBottom = pageSwipeStartY >= height - dp(140)
                        val atBottom = scrollY + height >= body.height - dp(24)
                        if (startedNearBottom && atBottom && swipeUp >= dp(65)) {
                            restorePreviousPage()
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
        catalogueScroll = scroll
        // Render from memory/API immediately. Domain discovery must never block first paint.
        home()
        thread(name = "AniLight-Domain") {
            val previousApi = api
            refreshDomainBlocking()
            if (previousApi != api) {
                ui.post {
                    if (isHomePage) home()
                }
            }
        }
        return scroll
    }

    private fun home() {
        isHomePage = true
        isShelfCategoryPage = false
        showingFilterResults = false
        filterHasNext = false
        body.removeAllViews()

        // CloudStream-style catalogue: one vertical page with a featured hero
        // followed by independently horizontally-scrolling category shelves.
        contentHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
            clipChildren = false
            clipToPadding = false
        }
        body.addView(contentHost, bottom(12))
        contentHost.addView(label("Loading AniLight catalogue…", 14, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(55), dp(10), dp(55))
        })

        val requestVersion = ++homeRequestVersion
        val cached = homepageCache ?: sharedHomepageCache
        val cachedAt = if (homepageCache != null) homepageCheckedAt else sharedHomepageCheckedAt
        if (cached != null && System.currentTimeMillis() - cachedAt < 10 * 60_000L) {
            renderFullHome(cached)
            // Refresh stale catalogue in the background while cached shelves stay visible.
            if (System.currentTimeMillis() - cachedAt > 60_000L) {
                request("$api/homepage") { fresh ->
                    if (requestVersion != homeRequestVersion || !isHomePage || fresh == null) return@request
                    homepageCache = fresh
                    homepageCheckedAt = System.currentTimeMillis()
                    sharedHomepageCache = fresh
                    sharedHomepageCheckedAt = homepageCheckedAt
                    // Existing visible content is retained; new data is used on next entry.
                }
            }
        } else {
            request("$api/homepage") { data ->
                if (requestVersion != homeRequestVersion || !isHomePage) return@request
                if (data != null) {
                    homepageCache = data
                    homepageCheckedAt = System.currentTimeMillis()
                    sharedHomepageCache = data
                    sharedHomepageCheckedAt = homepageCheckedAt
                }
                renderFullHome(data ?: homepageCache ?: sharedHomepageCache)
            }
        }
    }

    private fun renderFullHome(data: JSONObject?) {
        if (!isHomePage) return
        contentHost.removeAllViews()
        if (data == null) {
            contentHost.addView(label("AniLight catalogue load হয়নি। আবার চেষ্টা করো।", 14, muted, false), bottom(12))
            contentHost.addView(button("RETRY") { home() })
            return
        }

        // Keep the homepage edge-to-edge: Watch Later is the first section.
        // The featured Play/Info hero is intentionally removed to avoid the
        // large unused banner area above the catalogue.
        renderWatchLaterShelf()

        val trending = items(data.optJSONObject("trending")?.optJSONArray("media"))
            .filterNot { it.optBoolean("isAdult", false) }

        val shelves = listOf(
            Triple("Trending Now", "trending", items(data.optJSONObject("trending")?.optJSONArray("media"))),
            Triple("Most Popular", "popular", items(data.optJSONObject("popular")?.optJSONArray("media"))),
            Triple("This Season", "seasonal", items(data.optJSONObject("seasonal")?.optJSONArray("media"))),
            Triple("Previous Season", "previousSeason", items(data.optJSONObject("previousSeason")?.optJSONArray("media"))),
            Triple("Popular Movies", "movies", items(data.optJSONObject("popularMovies")?.optJSONArray("media"))),
            Triple("Top Rated", "topRated", emptyList<JSONObject>()),
            Triple("Recently Added", "recent", items(data.optJSONArray("recentlyAddedEpisodes"))),
            Triple("Upcoming", "upcoming", items(data.optJSONObject("upcoming")?.optJSONArray("media")))
        )
        shelves.forEach { (title, key, entries) ->
            if (key == "topRated") {
                loadHomeShelf(title, key, null)
            } else if (entries.isNotEmpty()) {
                addAnimeShelf(title, key, entries.filterNot { it.optBoolean("isAdult", false) })
            }
        }

        val genres = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror",
            "Mecha", "Mystery", "Psychological", "Romance", "Sci-Fi",
            "Slice of Life", "Sports", "Supernatural", "Thriller"
        )
        genres.forEach { genre -> loadHomeShelf(genre, "genre:$genre", genre) }
    }

    private fun addFeaturedHero(item: JSONObject) {
        val titleObj = item.optJSONObject("title")
        val name = titleObj?.optString("english").orEmpty()
            .ifBlank { titleObj?.optString("romaji").orEmpty().ifBlank { "Featured Anime" } }
        val banner = item.optString("bannerImage").takeIf { it.startsWith("http") }
            ?: item.optJSONObject("coverImage")?.optString("extraLarge").orEmpty()
        val genres = item.optJSONArray("genres")
        val genreText = (0 until (genres?.length() ?: 0)).mapNotNull { genres?.optString(it) }
            .take(7).joinToString(" • ")
        // Full-bleed featured artwork: use the complete available Anime
        // screen width with no side gutters or rounded black corners.
        val hero = FrameLayout(context).apply {
            setBackgroundColor(surface)
            clipToOutline = false
            isFocusable = true
            setOnClickListener { openAnime(item) }
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(surface)
        }
        hero.addView(image, FrameLayout.LayoutParams(-1, -1))
        if (banner.startsWith("http")) loadImage(banner, image)
        hero.addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00101018, 0x66101018, 0xF0000000.toInt())
            )
        }, FrameLayout.LayoutParams(-1, -1))
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        details.addView(label(name, 23, Color.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 1 }, bottom(5))
        if (genreText.isNotBlank()) details.addView(label(genreText, 12, Color.WHITE, false).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, bottom(12))
        val actions = row().apply { gravity = Gravity.CENTER }
        actions.addView(button("▶  Play") { openAnime(item) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        actions.addView(button("ⓘ  Info") { openAnime(item) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        details.addView(actions)
        hero.addView(details, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        contentHost.addView(hero, LinearLayout.LayoutParams(-1, dp(380)).apply {
            bottomMargin = dp(24)
        })
    }

    private fun loadHomeShelf(title: String, key: String, genre: String?) {
        val query = if (genre != null) {
            "$api/filter?page=1&perPage=30&sort=POPULARITY_DESC&genres=" + enc(genre) + "&hideAdult=true"
        } else {
            "$api/filter?page=1&perPage=18&sort=SCORE_DESC&hideAdult=true"
        }
        request(query) { data ->
            if (!isHomePage) return@request
            val payload = data?.optJSONObject("data") ?: data
            val cards = items(payload?.optJSONArray("media"))
                .filterNot { it.optBoolean("isAdult", false) }
            if (cards.isNotEmpty()) addAnimeShelf(title, key, cards)
        }
    }

    private fun addAnimeShelf(title: String, key: String, cards: List<JSONObject>) {
        if (!isHomePage || cards.isEmpty()) return

        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(title, 19, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = label("⌄", 25, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(8), 0)
            isFocusable = true
            contentDescription = "Open $title category page"
            setOnClickListener { openShelfPage(title, cards) }
        }
        heading.addView(arrow)
        contentHost.addView(heading, bottom(8))

        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val rail = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        scroller.addView(rail)
        val screenWidthDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        val cardWidth = (screenWidthDp / 3f).toInt()
        val posterHeight = (cardWidth * 1.45f).toInt()
        cards.forEach { item ->
            val cover = item.optJSONObject("coverImage")
            val posterUrl = cover?.optString("extraLarge").orEmpty()
                .ifBlank { cover?.optString("large").orEmpty().ifBlank { cover?.optString("medium").orEmpty() } }
            val titleObj = item.optJSONObject("title")
            val name = titleObj?.optString("english").orEmpty()
                .ifBlank { titleObj?.optString("romaji").orEmpty().ifBlank { "Anime" } }
            val score = item.optDouble("averageScore", 0.0).takeIf { it > 0 }
                ?.let { String.format(java.util.Locale.US, "%.1f", it / 10.0) }.orEmpty()
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                setOnClickListener { openAnime(item) }
            }
            applyNowPlayingHighlight(card, item)
            val posterFrame = FrameLayout(context).apply {
                background = rounded(surface, 14)
                clipToOutline = true
            }
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
            }
            posterFrame.addView(poster, FrameLayout.LayoutParams(-1, -1))
            if (posterUrl.startsWith("http")) loadImage(posterUrl, poster)
            if (score.isNotBlank()) {
                val scoreTag = label("$score ★", 11, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = rounded(0xDD111318.toInt(), 20)
                }
                posterFrame.addView(scoreTag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(6), dp(6), 0)
                })
            }
            card.addView(posterFrame, LinearLayout.LayoutParams(dp(cardWidth), dp(posterHeight)))
            card.addView(label(name, 13, fg, false).apply {
                maxLines = 2
                gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(6), dp(3), 0)
            }, LinearLayout.LayoutParams(dp(cardWidth), dp(42)))
            rail.addView(card, LinearLayout.LayoutParams(dp(cardWidth), -2).apply { rightMargin = dp(4) })
        }
        contentHost.addView(scroller, bottom(22))
    }

    /** Open a category as a dedicated, vertically-scrollable page. */
    private fun openShelfPage(title: String, cards: List<JSONObject>) {
        if (!isHomePage || cards.isEmpty()) return
        pushCurrentPage()
        isHomePage = false
        isShelfCategoryPage = true
        showingFilterResults = false
        filterHasNext = false
        body.removeAllViews()

        val pageHeader = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        val back = button("←  BACK") {
            restorePreviousPage()
        }.apply {
            contentDescription = "Back to Anime categories"
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        pageHeader.addView(back)
        pageHeader.addView(label(title, 21, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
        pageHeader.addView(label("${cards.size} titles", 12, muted, false))
        body.addView(pageHeader, bottom(8))

        val screenWidthDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        val cardWidth = (screenWidthDp / 3f).toInt()
        val posterHeight = (cardWidth * 1.42f).toInt()
        var gridRow = row().apply { gravity = Gravity.TOP; clipChildren = false; clipToPadding = false }
        cards.forEachIndexed { index, item ->
            val cover = item.optJSONObject("coverImage")
            val posterUrl = cover?.optString("extraLarge").orEmpty()
                .ifBlank { cover?.optString("large").orEmpty().ifBlank { cover?.optString("medium").orEmpty() } }
            val titleObj = item.optJSONObject("title")
            val name = titleObj?.optString("english").orEmpty()
                .ifBlank { titleObj?.optString("romaji").orEmpty().ifBlank { "Anime" } }
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                setPadding(dp(4), 0, dp(4), dp(10))
                setOnClickListener { openAnime(item) }
            }
            applyNowPlayingHighlight(tile, item)
            val frame = FrameLayout(context).apply {
                background = rounded(surface, 10)
                clipToOutline = true
            }
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
            }
            frame.addView(poster, FrameLayout.LayoutParams(-1, -1))
            if (posterUrl.startsWith("http")) loadImage(posterUrl, poster)
            tile.addView(frame, LinearLayout.LayoutParams(-1, dp(posterHeight)))
            tile.addView(label(name, 11, fg, true).apply {
                maxLines = 2
                gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(5), dp(2), dp(3))
            }, LinearLayout.LayoutParams(-1, dp(42)))
            gridRow.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
            if (index % 3 == 2) {
                body.addView(gridRow, LinearLayout.LayoutParams(-1, -2))
                gridRow = row().apply { gravity = Gravity.TOP; clipChildren = false; clipToPadding = false }
            }
        }
        if (cards.size % 3 != 0) {
            repeat(3 - (cards.size % 3)) {
                gridRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            body.addView(gridRow, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(View(context), LinearLayout.LayoutParams(1, dp(24)))
        catalogueScroll?.post { catalogueScroll?.scrollTo(0, 0) }
    }

    private fun category(key: String, homeLanding: Boolean = false) {
        val keepHomeChrome = isHomePage
        val selectedGenre = key.takeIf { it.startsWith("genre:") }?.removePrefix("genre:")
        val sort = when (key) {
            "topRated" -> "SCORE_DESC"
            "recent" -> "UPDATED_AT_DESC"
            "upcoming" -> "START_DATE_DESC"
            "seasonal", "previousSeason" -> "POPULARITY_DESC"
            else -> "POPULARITY_DESC"
        }
        // Save the current page before resetting its scroll position.
        loadFiltered(sort, selectedGenre, keepHomeChrome = keepHomeChrome)
        catalogueScroll?.post { catalogueScroll?.scrollTo(0, 0) }
    }

    private fun showHomeCategory(key: String, data: JSONObject?) {
        val sectionKey = if (key == "movies") "popularMovies" else key
        val array = when (key) {
            "recent" -> data?.optJSONArray("recentlyAddedEpisodes")
            "previousSeason" -> data?.optJSONObject("previousSeason")?.optJSONArray("media")
            else -> data?.optJSONObject(sectionKey)?.optJSONArray("media")
        }
        showCards(key, items(array).filterNot { it.optBoolean("isAdult", false) })
    }

    private fun showFilterDialog() {
        // Do not change the active page until the user actually applies a filter.
        val sorts = arrayOf(
            "Most Popular" to "POPULARITY_DESC",
            "Highest Rated" to "SCORE_DESC",
            "Trending" to "TRENDING_DESC",
            "Recently Updated" to "UPDATED_AT_DESC",
            "Newest First" to "START_DATE_DESC"
        )
        val genres = arrayOf("All genres", "Action", "Adventure", "Comedy", "Drama", "Fantasy",
            "Horror", "Mecha", "Mystery", "Psychological", "Romance", "Sci-Fi",
            "Slice of Life", "Sports", "Supernatural", "Thriller")
        AlertDialog.Builder(context).setTitle("Sort anime")
            .setItems(sorts.map { it.first }.toTypedArray()) { _, sortIndex ->
                AlertDialog.Builder(context).setTitle("Choose genre")
                    .setItems(genres) { _, genreIndex ->
                        loadFiltered(sorts[sortIndex].second, genres[genreIndex].takeUnless { it == "All genres" })
                    }.setNegativeButton("CANCEL", null).show()
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun loadFiltered(sort: String, genre: String?, page: Int = 1, keepHomeChrome: Boolean = false) {
        if (page == 1) pushCurrentPage()
        isHomePage = keepHomeChrome
        showingFilterResults = true
        if (page == 1) {
            autoPageLoading = false
            filterSort = sort
            filterGenre = genre
            filterPage = 1
            filterHasNext = false
            if (keepHomeChrome) {
                contentHost.removeAllViews()
                contentHost.addView(label("Loading " + (genre ?: "anime") + "…", 14, muted, false).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(35), dp(10), dp(35))
                })
            } else {
                loading("Applying anime filters…")
            }
        } else {
            filterPage = page
        }
        val query = "$api/filter?page=$page&perPage=30&sort=" + enc(sort) +
            (if (genre.isNullOrBlank()) "" else "&genres=" + enc(genre)) + "&hideAdult=true"
        request(query) { data ->
            val payload = data?.optJSONObject("data") ?: data
            val media = payload?.optJSONArray("media")
            filterHasNext = payload?.optJSONObject("pageInfo")?.optBoolean("hasNextPage", false) ?: false
            val results = items(media).filterNot { it.optBoolean("isAdult", false) }
            if (page > 1 && results.isEmpty()) filterHasNext = false
            autoPageLoading = false
            showCards("Filtered anime", results, append = page > 1)
        }
    }

    private fun searchAnime(q: String) {
        if (q.isBlank()) { category("trending"); return }
        pushCurrentPage()
        showingFilterResults = false
        filterHasNext = false
        isHomePage = false
        loading("Searching…")
        request("$api/search?q=${enc(q)}&hideAdult=true") { data ->
            val arr = data?.optJSONArray("results") ?: data?.optJSONArray("data")
            showCards("Search results", items(arr).filterNot { it.optBoolean("isAdult", false) })
        }
    }

    private fun showCards(title: String, cards: List<JSONObject>, append: Boolean = false) {
        val target = if (isHomePage) contentHost else body
        if (append) {
            if (target.childCount > 0 && target.getChildAt(target.childCount - 1).tag == "anilight_load_more") {
                target.removeViewAt(target.childCount - 1)
            }
        } else {
            target.removeAllViews()
            val head = row()
            head.addView(label(title.uppercase(), 20, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(button("HOME") { goHome() })
            target.addView(head, bottom(14))
        }
        if (cards.isEmpty() && !append) {
            target.addView(label("কোনো anime পাওয়া যায়নি। API unavailable হলে পরে আবার চেষ্টা করো।", 14, muted, false))
            return
        }

        // Featured wide banner, inspired by the reference Cloudstream layout.
        if (!append && cards.isNotEmpty()) {
            val featured = cards.first()
            val cover = featured.optJSONObject("coverImage")
            val imageUrl = cover?.optString("extraLarge").orEmpty()
                .ifBlank { cover?.optString("large").orEmpty().ifBlank { cover?.optString("medium").orEmpty() } }
            val titleObj = featured.optJSONObject("title")
            val featuredName = titleObj?.optString("english").orEmpty()
                .ifBlank { titleObj?.optString("romaji").orEmpty().ifBlank { "Featured Anime" } }
            val year = featured.optInt("seasonYear").takeIf { it > 0 }?.toString().orEmpty()
            val score = featured.optDouble("averageScore", 0.0).takeIf { it > 0 }?.toString()?.let { "★ $it" }.orEmpty()

            val hero = FrameLayout(context).apply {
                background = rounded(surface, 20)
                clipToOutline = true
                isFocusable = true
                setOnClickListener { openAnime(featured) }
            }
            val heroImage = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
            }
            hero.addView(heroImage, FrameLayout.LayoutParams(-1, -1))
            if (imageUrl.startsWith("http")) loadImage(imageUrl, heroImage)

            val shade = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00101018, 0x66101018, 0xF0101018.toInt())
                )
            }
            hero.addView(shade, FrameLayout.LayoutParams(-1, -1))

            val glass = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(8), dp(11), dp(8))
                background = GradientDrawable().apply {
                    setColor(0x99111624.toInt())
                    cornerRadius = dp(17).toFloat()
                    setStroke(dp(1), 0x66FFFFFF)
                }
                elevation = dp(5).toFloat()
            }
            glass.addView(label("FEATURED", 9, 0xFFD6D9FF.toInt(), true), bottom(2))
            glass.addView(label(featuredName, 15, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, bottom(2))
            glass.addView(label(listOf(year, score).filter { it.isNotBlank() }.joinToString(" • "), 9, 0xFFE1E5EF.toInt(), false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            val glassParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(dp(5), 0, dp(5), dp(5))
            }
            hero.addView(glass, glassParams)
            target.addView(hero, LinearLayout.LayoutParams(-1, dp(245)).apply { bottomMargin = dp(14) })
        }

        val sectionHead = label(if (title.equals("trending", true)) "TRENDING NOW" else title.uppercase(), 16, fg, true)
        target.addView(sectionHead, bottom(10))
        // SEE MORE opens a vertically scrolling catalogue (three-column poster grid).
        // The parent ScrollView handles top-to-bottom browsing.
        val visibleCards = if (!append && cards.isNotEmpty()) cards.drop(1) else cards
        val screenWidthDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        val cardWidth = ((screenWidthDp - 24f) / 3f).toInt()
        var gridRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        visibleCards.forEachIndexed { index, item ->
            val cover = item.optJSONObject("coverImage")
            val posterUrl = cover?.optString("extraLarge").orEmpty()
                .ifBlank { cover?.optString("large").orEmpty().ifBlank { cover?.optString("medium").orEmpty() } }
            val titleObj = item.optJSONObject("title")
            val name = titleObj?.optString("english").orEmpty()
                .ifBlank { titleObj?.optString("romaji").orEmpty().ifBlank { "Anime" } }
            val year = item.optInt("seasonYear").takeIf { it > 0 }?.toString().orEmpty()
            val score = item.optDouble("averageScore", 0.0).takeIf { it > 0 }
                ?.let { String.format(java.util.Locale.US, "%.1f", it / 10.0) }
                ?.let { "★ $it" }.orEmpty()

            val card = FrameLayout(context).apply {
                background = rounded(surface, 14)
                clipToOutline = true
                isFocusable = true
                setOnClickListener { openAnime(item) }
                applyNowPlayingHighlight(this, item)
            }
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
            }
            card.addView(poster, FrameLayout.LayoutParams(-1, -1))
            if (posterUrl.startsWith("http")) loadImage(posterUrl, poster)
            val shade = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00101018, 0xD9101018.toInt())
                )
            }
            card.addView(shade, FrameLayout.LayoutParams(-1, dp(66), Gravity.BOTTOM))
            val info = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), dp(6), dp(7), dp(6))
                addView(label(name, 11, Color.WHITE, true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(label(listOf(year, score).filter { it.isNotBlank() }.joinToString(" • "), 9, 0xFFD8DCE8.toInt(), false))
            }
            card.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(card, LinearLayout.LayoutParams(-1, dp((cardWidth * 1.42f).toInt())))
            }
            gridRow.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                bottomMargin = dp(14)
            })
            if (index % 3 == 2) {
                target.addView(gridRow, LinearLayout.LayoutParams(-1, -2))
                gridRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
            }
        }
        if (visibleCards.size % 3 != 0) {
            repeat(3 - (visibleCards.size % 3)) {
            gridRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            })
            target.addView(gridRow, LinearLayout.LayoutParams(-1, -2))
            }
        }

        // Pagination is automatic on scroll; no Load More button is shown.
    }

    private fun openAnime(item: JSONObject) {
        val nextSlug = item.optString("slug")
        if (nextSlug.isBlank()) { toast("Anime slug পাওয়া যায়নি"); return }
        pushCurrentPage()
        isHomePage = false
        slug = nextSlug
        language = "sub"
        loading("Loading anime details…")
        request("$api/anime/$slug") { detail ->
            if (detail == null) { toast("Anime details load হয়নি"); return@request }
            anime = detail
            animeId = detail.optInt("id")
            val titles = detail.optJSONObject("title")
            animeTitle = titles?.optString("english").orEmpty().ifBlank { titles?.optString("romaji").orEmpty().ifBlank { "Anime" } }
            request("$api/watch/$slug") { watch -> showDetails(detail, watch) }
        }
    }

    private fun showDetails(detail: JSONObject, watch: JSONObject?) {
        body.removeAllViews()
        val head = row()
        head.addView(button("‹ BACK") { if (!restorePreviousPage()) home() })
        head.addView(label("ANIME DETAILS", 13, accent, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(head, bottom(12))
        val cover = detail.optJSONObject("coverImage")
        val posterUrl = cover?.optString("extraLarge").orEmpty().ifBlank { cover?.optString("large").orEmpty().ifBlank { cover?.optString("medium").orEmpty() } }
        val info = row()
        val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(surface) }
        info.addView(poster, LinearLayout.LayoutParams(dp(112), dp(158)).apply { rightMargin = dp(12) })
        if (posterUrl.startsWith("http")) loadImage(posterUrl, poster)
        val meta = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        meta.addView(label(animeTitle, 18, fg, true))
        meta.addView(label("${detail.optString("format")} • ${detail.optString("status")} • ${detail.optInt("seasonYear").takeIf { it > 0 } ?: ""}", 11, muted, false))
        meta.addView(label("Episodes: ${detail.optInt("episodes", watch?.optJSONArray("episodes")?.length() ?: 0)}", 12, muted, false))
        meta.addView(label("Score: ${detail.optDouble("averageScore", 0.0)}", 12, muted, false))
        info.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(info, bottom(12))
        val plot = android.text.Html.fromHtml(detail.optString("description"), android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
        if (plot.isNotBlank()) body.addView(label(plot, 13, muted, false), bottom(12))
        val eps = watch?.optJSONArray("episodes")
        val sub = (0 until (eps?.length() ?: 0)).any { eps?.optJSONObject(it)?.optBoolean("sub") == true }
        val dub = (0 until (eps?.length() ?: 0)).any { eps?.optJSONObject(it)?.optBoolean("dub") == true }
        val langs = row()
        if (sub) langs.addView(button(if (language == "sub") "✓ SUB" else "SUB") { language = "sub"; showDetails(detail, watch) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        if (dub) langs.addView(button(if (language == "dub") "✓ DUB" else "DUB") { language = "dub"; showDetails(detail, watch) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        if (langs.childCount > 0) body.addView(langs, bottom(12))
        body.addView(label("EPISODES", 16, fg, true), bottom(8))
        val available = (0 until (eps?.length() ?: 0)).mapNotNull { eps?.optJSONObject(it) }.filter { it.optBoolean(language, false) }
        available.forEach { ep ->
            val number = ep.optInt("number", 1)
            val line = row().apply { gravity = Gravity.CENTER_VERTICAL; background = rounded(surface, 10); setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = true
                setOnClickListener { resolveEpisode(number) } }
            line.addView(label("▶", 17, accent, true), LinearLayout.LayoutParams(dp(32), -2))
            line.addView(label("$number. ${ep.optString("title").ifBlank { "Episode $number" }}", 13, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(label(language.uppercase(), 10, accent, true))
            body.addView(line, bottom(6))
        }
        if (available.isEmpty()) body.addView(label("এই ভাষায় episode পাওয়া যায়নি।", 13, muted, false))
    }

    private fun resolveEpisode(episode: Int) {
        loading("Resolving ${language.uppercase()} Episode $episode…")
        thread(name = "AniLight-Resolve") {
            try {
                val base = "$api/sources?id=$animeId&episode=$episode&language=$language"
                val own = sourceBlocking(base)
                val backup = sourceBlocking("$base&backup=true")
                        val sources = mutableListOf<Pair<String, String>>()
                val subtitles = linkedSetOf<String>()
                fun add(data: JSONObject?, checkHls: Boolean) {
                    val tracks = data?.optJSONArray("tracks")
                    for (i in 0 until (tracks?.length() ?: 0)) {
                        val t = tracks?.optJSONObject(i) ?: continue
                        if (t.optString("kind").isNotBlank() && t.optString("kind") != "captions") continue
                        t.optString("url").takeIf { it.isNotBlank() }?.let { subtitles.add(captionsUrl(it)) }
                    }
                    val arr = data?.optJSONArray("sources")
                    for (i in 0 until (arr?.length() ?: 0)) {
                        val entry = arr?.optJSONObject(i) ?: continue
                        val raw = entry.opt("url")
                        val tokens = when (raw) {
                            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf { s -> s.isNotBlank() } }
                            is String -> listOf(raw).filter { it.isNotBlank() }
                            else -> emptyList()
                        }
                        for (token in tokens) {
                            val resolved = resolveToken(token) ?: continue
                            if (checkHls && !validHls(resolved)) continue
                            sources.add((entry.optString("quality").ifBlank { if (checkHls) "HLS" else "Auto" }) to resolved)
                            if (!checkHls) break
                        }
                    }
                }
                // Resolve the primary provider first. Only pay the backup API/CDN
                // round-trip when the primary source did not yield a playable URL.
                add(own, false)
                if (sources.isEmpty()) add(sourceBlocking("$base&backup=true"), true)
                val unique = sources.distinctBy { it.second }
                ui.post {
                    if (unique.isEmpty()) { toast("Stream পাওয়া যায়নি। AniLight API/source unavailable হতে পারে।"); showDetailsAgain(); return@post }
                    AlertDialog.Builder(context).setTitle("$animeTitle — Episode $episode")
                        .setItems(unique.map { "${it.first} • ${language.uppercase()}" }.toTypedArray()) { _, which ->
                            saveWatchLater(anime ?: JSONObject(), episode, language)
                            onPlay("$animeTitle • Episode $episode", unique[which].second, subtitles.firstOrNull())
                        }.setNegativeButton("CANCEL", null).show()
                    showDetailsAgain()
                }
            } catch (e: Exception) { ui.post { toast(e.message ?: "Stream resolve failed"); showDetailsAgain() } }
        }
    }

    // Highlight the anime whose episode was most recently selected for playback.
    private fun applyNowPlayingHighlight(card: View, item: JSONObject) {
        val currentSlug = context.getSharedPreferences("anilight_watch_later", Context.MODE_PRIVATE)
            .getString("current_playing_slug", "").orEmpty()
        val itemSlug = item.optString("slug")
        if (currentSlug.isBlank() || itemSlug.isBlank() || currentSlug != itemSlug) return
        card.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(3), accent)
        }
        // Put the badge over the actual poster frame, including posters nested in LinearLayout cards.
        fun findPosterFrame(view: View): FrameLayout? {
            if (view is FrameLayout) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    val found = findPosterFrame(view.getChildAt(index))
                    if (found != null) return found
                }
            }
            return null
        }
        val posterFrame = findPosterFrame(card) ?: return
        val tag = label("▶ PLAYING", 10, Color.WHITE, true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(accent, 0xFFE040A0.toInt(), 0xFF24B6D8.toInt())
            ).apply { cornerRadius = dp(18).toFloat() }
            setShadowLayer(dp(7).toFloat(), 0f, dp(2).toFloat(), accent)
        }
        posterFrame.addView(tag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(6), dp(6), 0, 0)
        })
    }

    // Persist every episode the user actually starts playing, newest first.
    private fun saveWatchLater(animeItem: JSONObject, episode: Int, languageCode: String) {
        val prefs = context.getSharedPreferences("anilight_watch_later", Context.MODE_PRIVATE)
        val saved = runCatching { JSONArray(prefs.getString("items", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val slugKey = animeItem.optString("slug")
        val key = "$slugKey|$episode|$languageCode"
        prefs.edit().putString("current_playing_slug", slugKey).putString("current_playing_key", key).apply()
        val updated = JSONArray()
        val entry = JSONObject()
            .put("key", key)
            .put("anime", animeItem)
            .put("episode", episode)
            .put("language", languageCode)
            .put("playedAt", System.currentTimeMillis())
        updated.put(entry)
        for (i in 0 until saved.length()) {
            val old = saved.optJSONObject(i) ?: continue
            if (old.optString("key") != key) updated.put(old)
        }
        val capped = JSONArray()
        for (i in 0 until minOf(updated.length(), 200)) capped.put(updated.get(i))
        prefs.edit().putString("items", capped.toString()).apply()
    }

    // Fixed three-column grid, positioned directly above Trending Now.
    // No dropdown/expand control; entries persist across app restarts.
    private fun renderWatchLaterShelf() {
        val prefs = context.getSharedPreferences("anilight_watch_later", Context.MODE_PRIVATE)
        val saved = runCatching { JSONArray(prefs.getString("items", "[]") ?: "[]") }.getOrElse { JSONArray() }
        if (saved.length() == 0) return

        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(2))
        }
        section.addView(label("Watch Later", 19, fg, true), bottom(10))
        val screenWidthDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        val cardWidth = ((screenWidthDp - 40f) / 3f).toInt()
        var gridRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        for (index in 0 until saved.length()) {
            val entry = saved.optJSONObject(index) ?: continue
            val item = entry.optJSONObject("anime") ?: continue
            val cover = item.optJSONObject("coverImage")
            val posterUrl = cover?.optString("extraLarge").orEmpty()
                .ifBlank { cover?.optString("large").orEmpty().ifBlank { cover?.optString("medium").orEmpty() } }
            val titleObj = item.optJSONObject("title")
            val name = titleObj?.optString("english").orEmpty()
                .ifBlank { titleObj?.optString("romaji").orEmpty().ifBlank { "Anime" } }
            val episode = entry.optInt("episode", 1)
            val lang = entry.optString("language", "sub").uppercase()
            val card = FrameLayout(context).apply {
                background = rounded(surface, 12)
                clipToOutline = true
                isFocusable = true
                setOnClickListener { openAnime(item) }
            }
            applyNowPlayingHighlight(card, item)
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
            }
            card.addView(poster, FrameLayout.LayoutParams(-1, -1))
            if (posterUrl.startsWith("http")) loadImage(posterUrl, poster)
            card.addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00101018, 0xE8101018.toInt())
                )
            }, FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM))
            val caption = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(5), dp(5), dp(5))
                addView(label(name, 10, Color.WHITE, true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(label("Episode $episode • $lang", 9, 0xFFE1E5EF.toInt(), false))
            }
            card.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            gridRow.addView(card, LinearLayout.LayoutParams(0, dp((cardWidth * 1.48f).toInt()), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                bottomMargin = dp(10)
            })
            if (gridRow.childCount == 3) {
                section.addView(gridRow, LinearLayout.LayoutParams(-1, -2))
                gridRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
            }
        }
        if (gridRow.childCount > 0) {
            while (gridRow.childCount < 3) {
                gridRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                })
            }
            section.addView(gridRow, LinearLayout.LayoutParams(-1, -2))
        }
        contentHost.addView(section, bottom(18))
    }

    private fun showDetailsAgain() {
        val selected = anime ?: return
        request("$api/watch/$slug") { showDetails(selected, it) }
    }

    private fun refreshDomainBlocking() {
        val now = System.currentTimeMillis()
        if (now - domainCheckedAt < 5 * 60_000L) return
        domainCheckedAt = now
        val c = open("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", emptyMap(), true).apply {
            connectTimeout = 5000
            readTimeout = 5000
        }
        try {
            if (c.responseCode !in 200..299) return
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val candidate = listOf("anilight", "anilight_url", "anilight_domain")
                .firstNotNullOfOrNull { key -> root.optString(key).takeIf { it.isNotBlank() } }
                ?.removeSuffix("/") ?: return
            val parsed = android.net.Uri.parse(candidate)
            val host = parsed.host ?: return
            if (parsed.scheme != "https" || host.isBlank()) return
            val newApi = "https://api.$host/api"
            if (candidate != site || newApi != api) {
                site = candidate
                api = newApi
                session = null
                sessionExpiry = 0L
                homepageCache = null
                homepageCheckedAt = 0L
                sharedHomepageCache = null
                sharedHomepageCheckedAt = 0L
            }
        } catch (_: Exception) {
            // Keep the default domain if the helper cannot be reached.
        } finally { c.disconnect() }
    }

    /** Headers required by AniLight's public playback/CDN endpoints. */
    fun playbackHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36",
        "Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, video/*, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$site/"
    )

    private fun captionsUrl(token: String): String = "$api/proxy/captions?token=" + enc(token)

    private fun getSession(force: Boolean = false): String? {
        if (!force && !session.isNullOrBlank() && System.currentTimeMillis() < sessionExpiry - 60000) return session
        val result = jsonBlocking("$api/sources/verify", method = "POST", body = JSONObject().put("token", "direct").toString()) ?: return null
        val token = result.optString("sessionToken").takeIf { it.isNotBlank() } ?: return null
        session = token
        sessionExpiry = result.optLong("expiresAt", System.currentTimeMillis() + 86400000)
        return token
    }

    // Retry the provider's documented session handshake once when the public API
    // reports an expired/invalid session. This does not bypass site protections.
    private fun sourceBlocking(url: String): JSONObject? {
        for (attempt in 0..1) {
            val token = getSession(force = attempt == 1) ?: return null
            val c = open(url, mapOf("x-source-session" to token), true)
            try {
                val code = c.responseCode
                if (code == 401 || code == 403) {
                    if (attempt == 0) continue
                    return null
                }
                if (code !in 200..299) return null
                val payload = c.inputStream.bufferedReader().use { it.readText() }
                return if (payload.trimStart().startsWith("[")) JSONObject().put("results", JSONArray(payload))
                    else JSONObject(payload)
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    private fun resolveToken(token: String): String? {
        val c = open("$api/lb/e/$token", emptyMap(), false)
        return try {
            val code = c.responseCode
            val location = c.getHeaderField("Location")
            when { code in 300..399 && !location.isNullOrBlank() && location.startsWith("http") -> location
                code in 300..399 -> "$api/lb/e/$token"; else -> null }
        } finally { c.disconnect() }
    }

    private fun validHls(url: String): Boolean {
        val c = open(url, emptyMap(), true)
        return try { c.responseCode in 200..299 && c.inputStream.bufferedReader().use { it.readText().take(4096) }.startsWith("#EXTM3U") }
        catch (_: Exception) { false } finally { c.disconnect() }
    }

    private fun request(url: String, done: (JSONObject?) -> Unit) {
        thread(name = "AniLight-API") { val result = runCatching { jsonBlocking(url) }.getOrNull(); ui.post { done(result) } }
    }

    private fun jsonBlocking(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET", body: String? = null): JSONObject? {
        val c = open(url, headers, true)
        return try {
            c.requestMethod = method
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            if (c.responseCode !in 200..299) return null
            val payload = c.inputStream.bufferedReader().use { it.readText() }
            if (payload.trimStart().startsWith("[")) JSONObject().put("results", JSONArray(payload))
            else JSONObject(payload)
        } finally { c.disconnect() }
    }

    private fun open(url: String, headers: Map<String, String>, redirects: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 20000; instanceFollowRedirects = redirects
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Referer", "$site/")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun loadImage(url: String, target: ImageView) {
        posterCache[url]?.let { cached ->
            target.setImageBitmap(cached)
            return
        }
        target.tag = url
        posterExecutor.execute {
            try {
                val connection = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 7000
                    instanceFollowRedirects = true
                }
                val bitmap = try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
                if (bitmap != null) {
                    if (posterCache.size >= 100) posterCache.clear()
                    posterCache[url] = bitmap
                    ui.post { if (target.tag == url) target.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) { }
        }
    }
    private fun items(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
    private fun loading(message: String) { body.removeAllViews(); body.addView(label(message, 14, muted, false).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(70), dp(10), dp(70)) }) }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun bottom(n: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(n) }
    private fun button(value: String, action: () -> Unit) = TextView(context).apply { text = value; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); isFocusable = true; setPadding(dp(12), dp(9), dp(12), dp(9)); background = rounded(accent, 10); setOnClickListener { action() } }
    private fun label(value: String, size: Int, color: Int, bold: Boolean) = TextView(context).apply { text = value; textSize = size.toFloat(); setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun openSite() { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(site))) } }
    private fun toast(s: String) = android.widget.Toast.makeText(context, s, android.widget.Toast.LENGTH_LONG).show()
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    companion object {
        @Volatile private var sharedHomepageCache: JSONObject? = null
        @Volatile private var sharedHomepageCheckedAt: Long = 0L
    }
}
