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
import java.io.File
import android.util.LruCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class SanyProviderItem(val title: String, val url: String, val posterUrl: String, val sourceProvider: String = "")
data class SanyProviderShelf(val providerName: String, val shelfName: String, val items: List<SanyProviderItem>)

/** Native AniLight browser backed by the provider's public JSON API. */
class AniLightNativeScreen(
    private val context: Context,
    private val light: Boolean,
    private val accent: Int,
    private val onPlay: (SanyPlaybackRequest) -> Unit,
    private val onPlaybackLoading: (String, Boolean) -> Unit = { _, _ -> },
    private val onBrowseCloudStreamProviders: () -> Unit = {},
    private val onCloudStreamResult: (providerName: String, url: String, title: String) -> Unit = { _, _, _ -> },
    private val onSearchProvider: (providerName: String) -> Unit = {},
    private val onLoadProviderShelves: (onLoaded: (List<SanyProviderShelf>) -> Unit) -> Unit = { it(emptyList()) }
) {
    @Volatile private var api = "https://api.anilight.live/api"
    @Volatile private var site = "https://anilight.live"
    @Volatile private var domainCheckedAt = 0L
    @Volatile private var homepageCache: JSONObject? = null
    @Volatile private var homepageCheckedAt = 0L
    private var homeRequestVersion = 0
    private var homeCategoryPage = 1
    private var cachedProviderShelves: List<SanyProviderShelf> = emptyList()
    private val homeCategoriesPerPage = 6
    // Bounded poster pipeline: memory LRU + disk cache + in-flight de-duplication.
    // This avoids decoding full-size artwork repeatedly and prevents a burst of requests
    // when a long catalogue page is rendered on a mobile device.
    private val posterMemoryCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L).coerceAtMost(32L * 1024L * 1024L).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val posterRequests = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var posterCacheLastCleanupAt = 0L
    // Limit concurrent poster downloads more aggressively on low-RAM phones.
    private val posterExecutor = Executors.newFixedThreadPool(
        if (Runtime.getRuntime().maxMemory() <= 256L * 1024L * 1024L) 2 else 4
    )
    private val bg = if (light) Color.WHITE else Color.BLACK
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
    private var activeWatch: JSONObject? = null
    private var currentEpisodeNumber = 0
    @Volatile private var episodeSourceResolving = false
    private var activeSourceDialog: AlertDialog? = null
    private var activeAnimePosterUrl = ""
    private var isHomePage = true
    // Category dropdown pages must be exited with the visible Back button, never a swipe gesture.
    private var isShelfCategoryPage = false
    private var filterPage = 1
    private var filterSort = "POPULARITY_DESC"
    private var filterGenre: String? = null
    private var filterHasNext = false
    private var showingFilterResults = false
    private var isAnimeSearchPage = false
    private var animeSearchResultsHost: LinearLayout? = null
    private var animeSearchRequestVersion = 0
    private var homeLoadGeneration = 0
    private var animeSearchDebounce: Runnable? = null

    private data class UnifiedAnimeSource(
        val providerName: String,
        val url: String,
        val title: String,
        val bannerUrl: String = "",
        val nativeItem: JSONObject? = null
    )

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
        val contentHost: LinearLayout?,
        val isAnimeSearchPage: Boolean,
        val animeSearchResultsHost: LinearLayout?
    )
    private val pageHistory = java.util.ArrayDeque<AnimePageSnapshot>()

    private class FastAnimeScrollView(context: Context) : ScrollView(context) {
        override fun fling(velocityY: Int) {
            super.fling((velocityY * 2.0f).toInt())
        }
    }
    private class FastAnimeHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
        override fun fling(velocityX: Int) {
            super.fling((velocityX * 2.0f).toInt())
        }
    }

    private fun pushCurrentPage() {
        if (!::body.isInitialized || body.childCount == 0) return
        val savedContentHost = if (isHomePage && ::contentHost.isInitialized) contentHost else null
        val children = (0 until body.childCount).map { body.getChildAt(it) }
        pageHistory.addLast(
            AnimePageSnapshot(
                children, catalogueScroll?.scrollY ?: 0, isHomePage,
                isShelfCategoryPage, showingFilterResults, filterHasNext, filterPage, filterSort,
                filterGenre, autoPageLoading, slug, animeId, animeTitle,
                language, anime, savedContentHost, isAnimeSearchPage, animeSearchResultsHost
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
        isAnimeSearchPage = previous.isAnimeSearchPage
        animeSearchResultsHost = previous.animeSearchResultsHost
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
        val scroll = FastAnimeScrollView(context).apply {
            isFillViewport = true
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
            setOnScrollChangeListener { view: View, _: Int, scrollY: Int, _: Int, _: Int ->
                val sv = view as ScrollView
                val nearBottom = scrollY + sv.height >= body.height - dp(80)
                if (nearBottom && showingFilterResults && filterHasNext && !autoPageLoading) {
                    autoPageLoading = true
                    showPaginationFooter(isLoading = true)
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
        // Anime Series is now exclusively CloudStream-extension driven.
        // Do not contact AniLight, its API, or its legacy catalogue.
        home()
        return scroll
    }

    private fun home() {
        val requestGeneration = ++homeLoadGeneration
        isHomePage = true
        isShelfCategoryPage = false
        showingFilterResults = false
        filterHasNext = false
        body.removeAllViews()
        pageHistory.clear()

        contentHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(0), dp(10), dp(0), dp(24))
            clipChildren = false
            clipToPadding = false
        }
        body.addView(contentHost, bottom(12))
        addAnimeHomeHeader()
        val loading = label("নতুন Bangla ও Hindi Anime Extension লোড হচ্ছে…", 14, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(24), dp(12), dp(36))
        }
        contentHost.addView(loading)

        onLoadProviderShelves { providerShelves ->
            ui.post {
                // Ignore stale async homepage responses after retry/navigation.
                if (!isHomePage || requestGeneration != homeLoadGeneration || !::contentHost.isInitialized) return@post
                // The loading view may already have been detached; clear safely rather than
                // calling removeViews() with an invalid start index on an empty container.
                contentHost.removeAllViews()
                addAnimeHomeHeader()
                if (providerShelves.isEmpty()) {
                    contentHost.addView(label(
                        "নতুন Anime Extension পাওয়া যায়নি। Internet connection ও Extension sync পরীক্ষা করো।",
                        14, muted, false
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(18), dp(28), dp(18), dp(28))
                    })
                    contentHost.addView(button("RETRY") { home() })
                } else {
                    cachedProviderShelves = providerShelves.filter { it.items.isNotEmpty() }
                    homeCategoryPage = 1
                    renderHomeCategoryPage()
                }
            }
        }
    }

    /** Render the Anime header immediately, before provider network requests complete. */
    private fun addAnimeHomeHeader() {
        val homeHeader = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(8))
        }
        homeHeader.addView(label("ANIME SERIES", 16, fg, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        homeHeader.addView(label("⌕  SEARCH", 12, accent, true).apply {
            gravity = Gravity.CENTER
            background = rounded(surface, 18)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            isFocusable = true
            contentDescription = "Search Anime"
            setOnClickListener { openAnimeSearch() }
        })
        contentHost.addView(homeHeader, bottom(5))
    }

    /** Show exactly six Anime categories per numbered homepage page. */
    private fun renderHomeCategoryPage() {
        if (!isHomePage || !::contentHost.isInitialized) return
        // Page rendering can be called after the async loader has already cleared the host.
        // removeViews(1, 0) is still invalid on Android when childCount is 0 or 1.
        contentHost.removeAllViews()
        addAnimeHomeHeader()
        val pageCount = ((cachedProviderShelves.size + homeCategoriesPerPage - 1) / homeCategoriesPerPage).coerceAtLeast(1)
        homeCategoryPage = homeCategoryPage.coerceIn(1, pageCount)
        val from = (homeCategoryPage - 1) * homeCategoriesPerPage
        cachedProviderShelves.drop(from).take(homeCategoriesPerPage).forEach { shelf ->
            addProviderShelf(shelf)
        }
        if (pageCount > 1) {
            val pagination = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(24))
            }
            fun addPageButton(text: String, targetPage: Int, selected: Boolean = false) {
                val item = TextView(context).apply {
                    this.text = text
                    gravity = Gravity.CENTER
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = rounded(if (selected) accent else surface, 9)
                    setPadding(dp(13), dp(10), dp(13), dp(10))
                    isEnabled = targetPage in 1..pageCount && targetPage != homeCategoryPage
                    alpha = if (isEnabled || selected) 1f else 0.38f
                    setOnClickListener {
                        if (targetPage in 1..pageCount && targetPage != homeCategoryPage) {
                            homeCategoryPage = targetPage
                            renderHomeCategoryPage()
                            catalogueScroll?.post { catalogueScroll?.scrollTo(0, 0) }
                        }
                    }
                }
                pagination.addView(item, LinearLayout.LayoutParams(-2, dp(40)).apply {
                    leftMargin = dp(3); rightMargin = dp(3)
                })
            }
            addPageButton("‹", homeCategoryPage - 1)
            (1..pageCount).forEach { number ->
                addPageButton(number.toString(), number, selected = number == homeCategoryPage)
            }
            addPageButton("›", homeCategoryPage + 1)
            contentHost.addView(pagination, bottom(10))
            contentHost.addView(label("Page $homeCategoryPage of $pageCount • প্রতি পেজে ৬টি ক্যাটাগরি", 11, muted, false).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(14))
            })
        }
    }

    private fun addProviderShelf(shelf: SanyProviderShelf) {
        if (!isHomePage || shelf.items.isEmpty()) return

        val heading = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(7))
        }
        heading.addView(label(shelf.shelfName, 17, fg, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label("SEE ALL  ›", 11, accent, true).apply {
            setPadding(dp(10), dp(8), dp(8), dp(8))
            isFocusable = true
            setOnClickListener { openProviderShelfPage(shelf) }
        })
        heading.addView(label("${shelf.items.size}", 10, muted, true))
        contentHost.addView(heading)

        // Compact category preview: only a few cards are rendered on Home.
        // The complete merged category opens on a numbered, paginated page.
        val screenWidthDp = context.resources.displayMetrics.widthPixels /
            context.resources.displayMetrics.density
        val sidePadding = 0
        val cardWidth = (screenWidthDp / 3f).toInt().coerceAtLeast(88)
        val posterHeight = (cardWidth * 1.42f).toInt()
        val scroller = FastAnimeHorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(sidePadding), 0, dp(sidePadding), dp(12))
        }
        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        scroller.addView(rail, ViewGroup.LayoutParams(-2, -2))

        shelf.items.take(6).forEachIndexed { index, entry ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                background = rounded(surface, 12)
                setPadding(dp(2), dp(3), dp(2), dp(6))
                setOnClickListener {
                    onCloudStreamResult(entry.sourceProvider.ifBlank { shelf.providerName }, entry.url, entry.title)
                }
            }
            val posterFrame = FrameLayout(context).apply {
                background = rounded(surface, 9)
                clipToOutline = true
            }
            applyProviderNowPlayingHighlight(card, posterFrame, entry, shelf.providerName)
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
            }
            posterFrame.addView(poster, FrameLayout.LayoutParams(-1, -1))
            if (entry.posterUrl.startsWith("http")) loadImage(entry.posterUrl, poster)
            card.addView(posterFrame, LinearLayout.LayoutParams(-1, dp(posterHeight)))
            card.addView(label(entry.title, 11, fg, true).apply {
                maxLines = 2
                gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(6), dp(2), 0)
            }, LinearLayout.LayoutParams(-1, -2))
            rail.addView(card, LinearLayout.LayoutParams(dp(cardWidth), -2).apply {
                if (index > 0) leftMargin = dp(2)
            })
        }
        contentHost.addView(scroller, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openProviderShelfPage(shelf: SanyProviderShelf, page: Int = 1) {
        pushCurrentPage()
        isHomePage = false
        isShelfCategoryPage = true
        isAnimeSearchPage = false
        renderProviderShelfPage(shelf, page)
    }

    private fun renderProviderShelfPage(shelf: SanyProviderShelf, page: Int) {
        body.removeAllViews()
        val pageSize = 18
        val pageCount = ((shelf.items.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val safePage = page.coerceIn(1, pageCount)
        body.addView(row().apply {
            addView(button("← BACK") { restorePreviousPage() })
            addView(label(shelf.shelfName, 17, fg, true), LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(10)
            })
        }, bottom(12))
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), 0, dp(3), dp(8))
        }
        val screenWidthDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        val cardWidth = ((screenWidthDp - 12f) / 2f).toInt().coerceAtLeast(140)
        val posterHeight = (cardWidth * 1.42f).toInt()
        shelf.items.drop((safePage - 1) * pageSize).take(pageSize).chunked(2).forEach { entries ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            entries.forEach { entry ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(2), dp(3), dp(2), dp(7))
                    setOnClickListener {
                        onCloudStreamResult(entry.sourceProvider.ifBlank { shelf.providerName }, entry.url, entry.title)
                    }
                }
                val posterFrame = FrameLayout(context).apply { background = rounded(surface, 9); clipToOutline = true }
                applyProviderNowPlayingHighlight(card, posterFrame, entry, shelf.providerName)
                val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(surface) }
                posterFrame.addView(poster, FrameLayout.LayoutParams(-1, -1))
                if (entry.posterUrl.startsWith("http")) loadImage(entry.posterUrl, poster)
                card.addView(posterFrame, LinearLayout.LayoutParams(-1, dp(posterHeight)))
                card.addView(label(entry.title, 11, fg, true).apply {
                    maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(5), dp(2), 0)
                })
                line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    leftMargin = dp(2); rightMargin = dp(2); bottomMargin = dp(8)
                })
            }
            while (line.childCount < 2) line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(grid, LinearLayout.LayoutParams(-1, -2))
        if (pageCount > 1) {
            val pager = row().apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(20)) }
            pager.addView(button("‹") { renderProviderShelfPage(shelf, safePage - 1) }.apply { isEnabled = safePage > 1 })
            val first = maxOf(1, safePage - 2)
            val last = minOf(pageCount, first + 4)
            for (number in first..last) {
                pager.addView(button(number.toString()) { renderProviderShelfPage(shelf, number) }.apply {
                    alpha = if (number == safePage) 1f else 0.72f
                }, LinearLayout.LayoutParams(dp(42), dp(40)).apply { leftMargin = dp(3); rightMargin = dp(3) })
            }
            pager.addView(button("›") { renderProviderShelfPage(shelf, safePage + 1) }.apply { isEnabled = safePage < pageCount })
            body.addView(pager, LinearLayout.LayoutParams(-1, -2))
        }
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
        val screenWidthDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        val heroHeight = (screenWidthDp / 1.65f).toInt().coerceIn(dp(190), dp(250))
        contentHost.addView(hero, LinearLayout.LayoutParams(-1, heroHeight).apply {
            bottomMargin = dp(18)
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
        // Keep the dropdown affordance, and make the action explicit with SEE MORE.
        val arrow = label("SEE MORE  ⌄", 12, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(8), dp(8))
            isFocusable = true
            contentDescription = "See more $title"
            setOnClickListener { openShelfPage(title, cards, key) }
        }
        heading.addView(arrow)
        contentHost.addView(heading, bottom(8))

        val scroller = FastAnimeHorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isNestedScrollingEnabled = false
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
    private fun openAllAnimeCatalogue() {
        if (!isHomePage) return
        pushCurrentPage()
        isShelfCategoryPage = true
        isAnimeSearchPage = false
        loadFiltered("POPULARITY_DESC", null, preserveCurrentPage = true)
        catalogueScroll?.post { catalogueScroll?.scrollTo(0, 0) }
    }

    private fun openAnimeSearch() {
        pushCurrentPage()
        isHomePage = false
        isShelfCategoryPage = true
        isAnimeSearchPage = true
        animeSearchRequestVersion++
        body.removeAllViews()
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("← BACK") { restorePreviousPage() })
        header.addView(label("SEARCH ANIME", 19, fg, true), LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(12)
        })
        body.addView(header, bottom(12))
        val input = EditText(context).apply {
            hint = "Anime name লিখে Search করো…"
            setSingleLine(true)
            textSize = 15f
            setTextColor(fg)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            background = rounded(surface, 12)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        body.addView(input, LinearLayout.LayoutParams(-1, dp(50)).apply {
            bottomMargin = dp(12)
        })
        val resultsHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        animeSearchResultsHost = resultsHost
        body.addView(resultsHost, LinearLayout.LayoutParams(-1, -2))
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                animeSearchDebounce?.let { ui.removeCallbacks(it) }
                val value = text?.toString().orEmpty()
                val task = Runnable { searchAnimeLive(value) }
                animeSearchDebounce = task
                ui.postDelayed(task, 180L)
            }
            override fun afterTextChanged(editable: android.text.Editable?) = Unit
        })
        input.requestFocus()
        input.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        resultsHost.addView(label("এখানে Anime-এর নাম লিখলেই ফলাফল দেখাবে।", 13, muted, false))
    }

    private fun searchAnimeLive(query: String) {
        val version = ++animeSearchRequestVersion
        val host = animeSearchResultsHost ?: return
        host.removeAllViews()
        if (query.isBlank()) {
            host.addView(label("এখানে Anime-এর নাম লিখলেই ফলাফল দেখাবে।", 13, muted, false))
            return
        }
        host.addView(label("Searching all Anime sources…", 13, muted, false))
        request("$api/search?q=" + enc(query.trim()) + "&hideAdult=true") { data ->
            if (!isAnimeSearchPage || version != animeSearchRequestVersion) return@request
            val arr = data?.optJSONArray("results")
                ?: data?.optJSONObject("data")?.optJSONArray("results")
                ?: data?.optJSONArray("data")
            val nativeResults = items(arr).filterNot { it.optBoolean("isAdult", false) }
            searchLoadedCloudStreamProviders(query.trim(), version, nativeResults)
        }
    }

    /**
     * Search AniLight's native catalogue and every loaded CloudStream Anime MainAPI,
     * then render one grouped result list with provider-specific source actions.
     */
    private fun searchLoadedCloudStreamProviders(
        query: String,
        requestVersion: Int,
        nativeResults: List<JSONObject>
    ) {
        thread(name = "SANY-Anime-UnifiedSearch") {
            val normalized = Regex("[^\\p{L}\\p{N}]")
            val grouped = linkedMapOf<String, MutableList<UnifiedAnimeSource>>()
            nativeResults.forEach { item ->
                val titleObj = item.optJSONObject("title")
                val title = titleObj?.optString("english").orEmpty()
                    .ifBlank { titleObj?.optString("romaji").orEmpty() }
                    .ifBlank { item.optString("name") }
                val slugValue = item.optString("slug")
                val key = normalized.replace(title.trim().lowercase(java.util.Locale.ROOT), "")
                if (key.isNotBlank() && slugValue.isNotBlank()) {
                    val banner = item.optString("bannerImage").takeIf { it.startsWith("http") }
                        ?: item.optJSONObject("coverImage")?.optString("extraLarge").orEmpty()
                    grouped.getOrPut(key) { mutableListOf() }.add(
                        UnifiedAnimeSource("AniLight", slugValue, title, banner, item)
                    )
                }
            }

            runCatching {
                kotlinx.coroutines.runBlocking {
                    // Search providers concurrently so one slow source does not block the rest.
                    coroutineScope {
                        val providerResults = SanyCloudStreamPluginRuntime.providers().map { provider ->
                            async(kotlinx.coroutines.Dispatchers.IO) {
                                provider to runCatching {
                                    withTimeoutOrNull(12_000L) {
                                        SanyCloudStreamPluginRuntime.search(provider.name, query)
                                    } ?: emptyList<com.lagradost.cloudstream3.SearchResponse>()
                                }
                            }
                        }.awaitAll()
                        providerResults.forEach { (provider, result) ->
                            val entries = result.getOrElse { error ->
                                android.util.Log.w("SANY-AnimeSearch", "${provider.name}: ${error.message}")
                                emptyList<com.lagradost.cloudstream3.SearchResponse>()
                            }
                            entries.forEach entryLoop@ { entry ->
                                val title = entry.name.trim()
                                if (title.isBlank()) return@entryLoop
                                val key = normalized.replace(title.lowercase(java.util.Locale.ROOT), "")
                                if (key.isBlank()) return@entryLoop
                                val sources = grouped.getOrPut(key) { mutableListOf() }
                                if (sources.none { it.providerName.equals(provider.name, true) && it.url == entry.url }) {
                                    sources.add(UnifiedAnimeSource(provider.name, entry.url, title, entry.posterUrl.orEmpty()))
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                android.util.Log.w("SANY-AnimeSearch", "CloudStream provider search unavailable", it)
            }

            ui.post {
                if (!isAnimeSearchPage || requestVersion != animeSearchRequestVersion) return@post
                val host = animeSearchResultsHost ?: return@post
                host.removeAllViews()
                val titleCount = grouped.size
                host.addView(label("ALL 6 PROVIDERS • $titleCount TITLES", 13, accent, true).apply {
                    setPadding(0, dp(14), 0, dp(8))
                })
                if (grouped.isEmpty()) {
                    host.addView(label("কোনো Provider-এ Anime পাওয়া যায়নি।", 12, muted, false))
                    return@post
                }
                grouped.values.forEach { sources ->
                    val displayTitle = sources.first().title
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = rounded(surface, 10)
                    }
                    val bannerUrl = sources.firstOrNull { it.bannerUrl.startsWith("http") }?.bannerUrl.orEmpty()
                    if (bannerUrl.isNotBlank()) {
                        val banner = ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(surface)
                        }
                        card.addView(banner, LinearLayout.LayoutParams(-1, dp(150)).apply { bottomMargin = dp(8) })
                        loadImage(bannerUrl, banner)
                    }
                    card.addView(label(displayTitle, 14, fg, true), bottom(6))
                    val providerNames = sources.map { it.providerName }.distinct()
                    card.addView(label("${providerNames.size}টি Provider Source", 11, muted, false), bottom(6))
                    val sourceRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    sources.forEach { source ->
                        sourceRow.addView(button(source.providerName) {
                            val nativeItem = source.nativeItem
                            if (nativeItem != null) openAnime(nativeItem)
                            else onCloudStreamResult(source.providerName, source.url, source.title)
                        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                            rightMargin = dp(5)
                        })
                    }
                    card.addView(sourceRow)
                    host.addView(card, bottom(7))
                }
            }
        }
    }

    private fun openShelfPage(title: String, cards: List<JSONObject>, key: String) {
        if (!isHomePage || cards.isEmpty()) return
        if (key.startsWith("genre:")) {
            pushCurrentPage()
            isShelfCategoryPage = true
            loadFiltered("POPULARITY_DESC", key.removePrefix("genre:"), preserveCurrentPage = true)
            return
        }
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

    private fun loadFiltered(sort: String, genre: String?, page: Int = 1, keepHomeChrome: Boolean = false, preserveCurrentPage: Boolean = false) {
        if (page == 1 && !preserveCurrentPage) pushCurrentPage()
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
            showPaginationFooter(isLoading = true)
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
        val target = if (isAnimeSearchPage) (animeSearchResultsHost ?: body) else if (isHomePage) contentHost else body
        if (append) {
            if (target.childCount > 0 && target.getChildAt(target.childCount - 1).tag == "anilight_load_more") {
                target.removeViewAt(target.childCount - 1)
            }
        } else {
            target.removeAllViews()
            if (!isAnimeSearchPage) {
                val head = row()
                head.addView(label(title.uppercase(), 20, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
                if (isShelfCategoryPage && title == "Filtered anime") {
                    head.addView(button("⌕") {
                        openAnimeSearch()
                    }.apply { textSize = 22f; contentDescription = "Search Anime" })
                }
                head.addView(button(if (isShelfCategoryPage) "← BACK" else "HOME") {
                    if (isShelfCategoryPage) restorePreviousPage() else goHome()
                })
                target.addView(head, bottom(14))
            }
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
            }
            // Attach the completed row once; adding the same View repeatedly
            // throws IllegalStateException and closes the app for 1–2 leftovers.
            target.addView(gridRow, LinearLayout.LayoutParams(-1, -2))
        }

        // Keep a visible footer at the end of the catalogue. It changes to an
        // indeterminate spinner while the next page is being fetched.
        if (showingFilterResults && filterHasNext) showPaginationFooter(isLoading = autoPageLoading)
    }

    private fun showPaginationFooter(isLoading: Boolean) {
        val target = if (isHomePage) contentHost else body
        val existing = (0 until target.childCount)
            .map { target.getChildAt(it) }
            .firstOrNull { it.tag == "anilight_load_more" }
        if (existing != null) target.removeView(existing)

        val footer = LinearLayout(context).apply {
            tag = "anilight_load_more"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(18), dp(12), dp(22))
        }
        if (isLoading) {
            footer.addView(android.widget.ProgressBar(context).apply {
                isIndeterminate = true
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                rightMargin = dp(10)
            })
            footer.addView(label("Loading more anime…", 13, muted, true))
        } else {
            footer.addView(label("＋", 19, accent, true), LinearLayout.LayoutParams(-2, -2).apply {
                rightMargin = dp(8)
            })
            footer.addView(label("Scroll down to load more", 13, muted, false))
        }
        target.addView(footer, bottom(8))
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
        activeWatch = watch
        body.removeAllViews()
        val head = row()
        head.addView(button("‹ BACK") { if (!restorePreviousPage()) home() })
        head.addView(label("ANIME DETAILS", 13, accent, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(head, bottom(12))
        val cover = detail.optJSONObject("coverImage")
        val posterUrl = cover?.optString("large").orEmpty().ifBlank { cover?.optString("medium").orEmpty() }
        activeAnimePosterUrl = posterUrl
        val info = row()
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(surface)
        }
        info.addView(poster, LinearLayout.LayoutParams(dp(96), dp(136)).apply { rightMargin = dp(10) })
        if (posterUrl.startsWith("http", true)) loadImage(posterUrl, poster)
        val meta = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        meta.addView(label(animeTitle, 17, fg, true))
        meta.addView(label("${detail.optString("format")} • ${detail.optString("status")}", 11, muted, false))
        meta.addView(label("Episodes: ${detail.optInt("episodes", watch?.optJSONArray("episodes")?.length() ?: 0)}", 12, muted, false))
        meta.addView(label("Score: ${detail.optDouble("averageScore", 0.0)}", 12, muted, false))
        info.addView(meta, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(info, bottom(12))
        val plot = android.text.Html.fromHtml(detail.optString("description"), android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
        if (plot.isNotBlank()) body.addView(label(plot, 13, muted, false), bottom(12))
        val eps = watch?.optJSONArray("episodes")
        // Hide the SUB/DUB selector above the episode list. Show each episode
        // when at least one audio mode is available; playback chooses a source automatically.
        body.addView(label("EPISODES", 16, fg, true), bottom(8))
        val available = (0 until (eps?.length() ?: 0)).mapNotNull { eps?.optJSONObject(it) }
            .sortedBy { it.optInt("number", Int.MAX_VALUE) }
        val episodeRows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        available.forEach { ep ->
            val number = ep.optInt("number", 1)
            val title = ep.optString("title").ifBlank { "Episode $number" }
            val isNowPlaying = isCurrentPlayingEpisode(number)
            val card = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = if (isNowPlaying) GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF126BDF.toInt(), 0xEE0845A4.toInt(), 0xDD071F52.toInt())
                ).apply {
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(3), 0xFF35B8FF.toInt())
                } else rounded(surface, 13)
                if (isNowPlaying) {
                    elevation = dp(9).toFloat()
                    foreground = GradientDrawable().apply {
                        cornerRadius = dp(13).toFloat()
                        setStroke(dp(1), 0x99BCEBFF.toInt())
                    }
                    contentDescription = "Currently playing Episode $number"
                }
                setPadding(dp(9), dp(9), dp(10), dp(9))
                isFocusable = true
                setOnClickListener { resolveEpisode(number) }
            }
            val thumbUrl = listOf("thumbnail", "thumbnailUrl", "image", "imageUrl", "img", "still", "screenshot")
                .map { ep.optString(it) }
                .firstOrNull { it.startsWith("http", true) }
                ?: ep.optJSONObject("thumbnail")?.optString("url")?.takeIf { it.startsWith("http", true) }
                ?: null
            val imageFrame = FrameLayout(context).apply {
                background = rounded(0xFF292D35.toInt(), 9)
                clipToOutline = true
            }
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF292D35.toInt())
            }
            imageFrame.addView(thumb, FrameLayout.LayoutParams(-1, -1))
            if (!thumbUrl.isNullOrBlank()) {
                loadImage(thumbUrl, thumb)
            } else {
                val episodePlaceholder = TextView(context).apply {
                    text = "EP $number"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(0xFFE4EAF4.toInt())
                }
                imageFrame.addView(episodePlaceholder, FrameLayout.LayoutParams(-1, -1))
            }
            val play = TextView(context).apply {
                text = "▶"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(0x99000000.toInt(), 30)
            }
            imageFrame.addView(play, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            card.addView(imageFrame, LinearLayout.LayoutParams(dp(126), dp(78)))
            val details = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), 0, 0, 0)
            }
            details.addView(label(
                if (isNowPlaying) "▶ NOW PLAYING • EPISODE $number" else "EPISODE $number",
                10, if (isNowPlaying) 0xFF9CDEFF.toInt() else accent, true
            ), bottom(4))
            details.addView(label(title, 13, if (isNowPlaying) Color.WHITE else fg, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, bottom(3))
            val rating = ep.optString("rating").ifBlank { ep.optString("score") }
            if (rating.isNotBlank()) details.addView(label("★ $rating", 11, muted, false))
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            episodeRows.addView(card, bottom(9))
        }
        body.addView(episodeRows)
        if (available.isEmpty()) body.addView(label("এই ভাষায় episode পাওয়া যায়নি।", 13, muted, false))
    }

    /** Side panel used only by the Anime Series fullscreen player. */
    fun createPlayerEpisodePanel(): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
            setPadding(dp(12), dp(12), dp(10), dp(12))
        }
        panel.addView(label("Season 1", 17, fg, true), bottom(10))
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        val eps = activeWatch?.optJSONArray("episodes")
        val items = (0 until (eps?.length() ?: 0)).mapNotNull { eps?.optJSONObject(it) }
            .filter { it.optBoolean(language, false) }
        items.forEach { ep ->
            val number = ep.optInt("number", 1)
            val title = ep.optString("title").ifBlank { "Episode $number" }
            val isNowPlaying = isCurrentPlayingEpisode(number)
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = if (isNowPlaying) GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF126BDF.toInt(), 0xEE0845A4.toInt(), 0xDD071F52.toInt())
                ).apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(3), 0xFF35B8FF.toInt())
                } else rounded(0xFF1B1B1B.toInt(), 10)
                if (isNowPlaying) {
                    elevation = dp(8).toFloat()
                    contentDescription = "Currently playing Episode $number"
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isFocusable = true
                setOnClickListener { resolveEpisode(number) }
            }
            val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
            val thumbUrl = listOf("thumbnail", "thumbnailUrl", "image", "imageUrl", "img")
                .map { ep.optString(it) }.firstOrNull { it.startsWith("http", true) } ?: activeAnimePosterUrl.takeIf { it.startsWith("http", true) }
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF303030.toInt())
            }
            line.addView(thumb, LinearLayout.LayoutParams(dp(104), dp(62)).apply { rightMargin = dp(8) })
            if (!thumbUrl.isNullOrBlank()) loadImage(thumbUrl, thumb)
            val titleView = label(
                if (isNowPlaying) "▶ NOW PLAYING\n$number. $title" else "$number. $title",
                13, if (isNowPlaying) 0xFFBCEBFF.toInt() else fg, true
            )
            line.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(line)
            val rating = ep.optString("rating").ifBlank { ep.optString("score") }
            val runtime = ep.optString("duration").ifBlank { ep.optString("length") }
            val meta = listOf(rating.takeIf { it.isNotBlank() }?.let { "Rated: $it" },
                runtime.takeIf { it.isNotBlank() }).filterNotNull().joinToString("   ")
            if (meta.isNotBlank()) card.addView(label(meta, 11, muted, false), bottom(4))
            val description = android.text.Html.fromHtml(
                ep.optString("description"), android.text.Html.FROM_HTML_MODE_COMPACT
            ).toString().trim()
            if (description.isNotBlank()) card.addView(label(description, 11, muted, false).apply {
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            list.addView(card, bottom(9))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    /** Plays the next available episode in the currently selected SUB/DUB language. */
    fun playNextEpisode() {
        val eps = activeWatch?.optJSONArray("episodes") ?: run {
            toast("Episode list পাওয়া যায়নি")
            return
        }
        // Follow the numeric episode order, regardless of API array order or
        // whether the next episode has the same SUB/DUB mode as the current one.
        val next = (0 until eps.length()).mapNotNull { eps.optJSONObject(it) }
            .filter {
                (it.optBoolean("sub", false) || it.optBoolean("dub", false)) &&
                    it.optInt("number", 0) > currentEpisodeNumber
            }
            .minByOrNull { it.optInt("number", Int.MAX_VALUE) }
        if (next == null) {
            toast("এটাই শেষ Episode")
            return
        }
        resolveEpisode(next.optInt("number"))
    }

    private fun resolveEpisode(episode: Int) {
        // Ignore rapid/repeated taps while the same source request or picker is active.
        if (episodeSourceResolving || activeSourceDialog?.isShowing == true) return
        episodeSourceResolving = true
        // Resolve the source list without covering the player. The in-player
        // loading overlay starts only after the user chooses a source.
        // Loading is displayed on the video surface by MainActivity; never
        // replace the Anime page with a separate full-screen loading page.
        thread(name = "AniLight-Resolve") {
            try {
                data class ResolvedSource(
                    val language: String,
                    val quality: String,
                    val url: String,
                    val mimeType: String?,
                    val server: String
                )
                val sources = mutableListOf<ResolvedSource>()
                val subtitlesByLanguage = linkedMapOf<String, LinkedHashSet<String>>()
                // Resolve load-balancer tokens concurrently, but keep the pool bounded
                // so a large episode response cannot overwhelm low-memory phones.
                val linkPool = Executors.newFixedThreadPool(8)
                val linkCompletions = java.util.concurrent.ExecutorCompletionService<ResolvedSource?>(linkPool)
                var linkTaskCount = 0
                fun add(data: JSONObject?, languageCode: String, checkHls: Boolean) {
                    val subtitles = subtitlesByLanguage.getOrPut(languageCode) { linkedSetOf() }
                    val tracks = data?.optJSONArray("tracks")
                    for (i in 0 until (tracks?.length() ?: 0)) {
                        val track = tracks?.optJSONObject(i) ?: continue
                        val kind = track.optString("kind").lowercase()
                        if (kind.isNotBlank() && kind != "captions" && kind != "subtitle" && kind != "subtitles") continue
                        track.optString("url").takeIf { it.isNotBlank() }
                            ?.let { subtitles.add(captionsUrl(it)) }
                    }
                    val arr = data?.optJSONArray("sources")
                    for (i in 0 until (arr?.length() ?: 0)) {
                        val entry = arr?.optJSONObject(i) ?: continue
                        val raw = entry.opt("url")
                        val tokens = when (raw) {
                            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf { value -> value.isNotBlank() } }
                            is String -> listOf(raw).filter { it.isNotBlank() }
                            else -> emptyList()
                        }
                        for (token in tokens) {
                            val quality = entry.optString("quality").ifBlank {
                                entry.optString("server").ifBlank { if (checkHls) "HLS" else "Auto" }
                            }
                            linkTaskCount++
                            linkCompletions.submit {
                                val resolved = resolveToken(token) ?: return@submit null
                                // AniLight load-balancer URLs are often extensionless.
                                // Use advertised quality and URL hints instead of a slow probe.
                                val cleanUrl = resolved.substringBefore("?").substringBefore("#").lowercase()
                                val hls = quality.contains("hls", ignoreCase = true) ||
                                    cleanUrl.endsWith(".m3u8") || cleanUrl.endsWith(".m3u") ||
                                    cleanUrl.contains("/hls/")
                                ResolvedSource(
                                    languageCode,
                                    quality,
                                    resolved,
                                    if (hls) "application/x-mpegURL" else null,
                                    if (checkHls) "Backup" else "Main"
                                )
                            }
                        }
                    }
                }

                // Complete the documented session handshake once, then request SUB/DUB
                // primary + backup metadata concurrently instead of serial network waits.
                getSession() ?: throw IllegalStateException("Playback session unavailable")
                val requests = listOf("sub", "dub").flatMap { languageCode ->
                    val base = "$api/sources?id=$animeId&episode=$episode&language=$languageCode"
                    listOf(
                        Triple(languageCode, false, base),
                        Triple(languageCode, true, "$base&backup=true")
                    )
                }
                val sourcePool = Executors.newFixedThreadPool(4)
                val sourceCompletions = java.util.concurrent.ExecutorCompletionService<Triple<String, Boolean, JSONObject?>>(sourcePool)
                val startedAt = System.nanoTime()
                val metadataDeadline = startedAt + java.util.concurrent.TimeUnit.SECONDS.toNanos(4)
                val linkDeadline = startedAt + java.util.concurrent.TimeUnit.SECONDS.toNanos(7)
                try {
                    requests.forEach { request ->
                        sourceCompletions.submit {
                            Triple(request.first, request.second, sourceBlocking(request.third))
                        }
                    }

                    // Pipeline metadata and redirects together: as soon as one SUB/DUB
                    // endpoint responds, enqueue its token resolutions and collect any
                    // completed links without waiting for the other metadata endpoints.
                    var metadataCompleted = 0
                    var linkCompleted = 0
                    while (System.nanoTime() < linkDeadline &&
                        ((metadataCompleted < requests.size && System.nanoTime() < metadataDeadline) ||
                            linkCompleted < linkTaskCount)) {
                        val now = System.nanoTime()
                        if (metadataCompleted < requests.size && now < metadataDeadline) {
                            val metadataFuture = sourceCompletions.poll(
                                minOf(
                                    java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50),
                                    metadataDeadline - now
                                ).coerceAtLeast(1L),
                                java.util.concurrent.TimeUnit.NANOSECONDS
                            )
                            if (metadataFuture != null) {
                                runCatching { metadataFuture.get() }.getOrNull()?.let { (languageCode, isBackup, data) ->
                                    add(data, languageCode, isBackup)
                                }
                                metadataCompleted++
                            }
                        }

                        if (linkCompleted < linkTaskCount) {
                            val linkFuture = linkCompletions.poll(
                                40L,
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )
                            if (linkFuture != null) {
                                runCatching { linkFuture.get() }.getOrNull()?.let { sources.add(it) }
                                linkCompleted++
                            }
                        }
                    }
                } finally {
                    sourcePool.shutdownNow()
                    linkPool.shutdownNow()
                }
                val unique = sources.distinctBy { it.language + "|" + it.url }
                // Auto-pick a source: prefer a dubbed stream when available,
                // then the highest advertised rendition. Media3 selects audio
                // tracks in Bengali > Hindi > English order within that stream.
                val preferred = unique.sortedWith(
                    compareBy<ResolvedSource> {
                        when {
                            it.language.equals("dub", true) -> 0
                            it.language.equals("sub", true) -> 1
                            else -> 2
                        }
                    }.thenBy { if (it.mimeType?.contains("mpegurl", true) == true) 0 else 1 }
                    .thenByDescending {
                        Regex("(\\d{3,4})").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }
                )
                ui.post {
                    onPlaybackLoading("", false)
                    if (preferred.isEmpty()) {
                        episodeSourceResolving = false
                        toast("কোনো playable source পাওয়া যায়নি। Main server বা backup server-এ এই Episode-এর link নাও থাকতে পারে।")
                        showDetailsAgain()
                        return@post
                    }

                    // Choosing a source starts playback immediately. There is no
                    // second PLAY confirmation step.
                    val sourceLabels = preferred.map { source ->
                        val languageLabel = when (source.language.lowercase()) {
                            "dub" -> "DUB"
                            "sub" -> "SUB"
                            else -> source.language.uppercase()
                        }
                        "$languageLabel • ${source.quality} • ${source.server}"
                    }.toTypedArray()
                    lateinit var dialog: AlertDialog
                    dialog = AlertDialog.Builder(context)
                        .setTitle("$animeTitle • Episode $episode — Sources")
                        .setItems(sourceLabels) { _, which ->
                            val selected = preferred.getOrNull(which) ?: return@setItems
                            episodeSourceResolving = false
                            activeSourceDialog = null
                            currentEpisodeNumber = episode
                            language = selected.language
                            saveWatchLater(anime ?: JSONObject(), episode, selected.language)
                            val selectedSubtitles = subtitlesByLanguage[selected.language]
                                .orEmpty()
                                .mapIndexed { subtitleIndex, subtitleUrl ->
                                    SanySubtitleTrack(
                                        url = subtitleUrl,
                                        language = selected.language,
                                        mimeType = "text/vtt",
                                        label = "${selected.language.uppercase()} Subtitle ${subtitleIndex + 1}"
                                    )
                                }
                            onPlaybackLoading("$animeTitle • Episode $episode", true)
                            onPlay(
                                SanyPlaybackRequest(
                                    title = "$animeTitle • Episode $episode",
                                    url = selected.url,
                                    provider = "AniLight",
                                    streamMimeType = selected.mimeType,
                                    quality = selected.quality,
                                    audioLanguage = "bn,ben,hi,hin,en,eng",
                                    headers = playbackHeaders(),
                                    subtitles = selectedSubtitles
                                )
                            )
                            dialog.dismiss()
                            showDetailsAgain()
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            episodeSourceResolving = false
                            activeSourceDialog = null
                            showDetailsAgain()
                        }
                        .create()
                    dialog.setOnDismissListener {
                        if (activeSourceDialog === dialog) activeSourceDialog = null
                        episodeSourceResolving = false
                    }
                    activeSourceDialog = dialog
                    dialog.show()
                }
            } catch (e: Exception) {
                ui.post {
                    episodeSourceResolving = false
                    activeSourceDialog = null
                    onPlaybackLoading("", false)
                    toast(e.message ?: "Stream resolve failed")
                    showDetailsAgain()
                }
            }
        }
    }

    // Highlight CloudStream Anime catalogue cards using the shared SANY playback identity.
    // Match both provider and catalogue URL/title so similarly named entries from other providers stay untouched.
    private fun applyProviderNowPlayingHighlight(
        card: View,
        posterFrame: FrameLayout,
        entry: SanyProviderItem,
        fallbackProvider: String
    ) {
        val prefs = context.getSharedPreferences("sany_iptv", Context.MODE_PRIVATE)
        if (prefs.getString("sany_last_played_section", "") != "Series") return
        val playedProvider = prefs.getString("sany_last_played_provider", "").orEmpty()
        val playedTitle = prefs.getString("sany_last_played_title", "").orEmpty()
        val playedUrl = prefs.getString("sany_last_played_item_url", "").orEmpty()
        val itemProvider = entry.sourceProvider.ifBlank { fallbackProvider }
        val matches = playedProvider.equals(itemProvider, true) && (
            playedUrl.isNotBlank() && playedUrl == entry.url ||
                playedTitle.isNotBlank() && playedTitle.equals(entry.title, true)
            )
        if (!matches) return
        card.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(3), accent)
        }
        card.elevation = dp(8).toFloat()
        val badge = label("▶ PLAYING", 9, Color.WHITE, true).apply {
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(accent, 0xFF0875F5.toInt())
            ).apply { cornerRadius = dp(16).toFloat() }
        }
        posterFrame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(5), dp(5), 0, 0)
        })
    }

    // Match the persisted currently-playing episode to the active anime slug.
    // This keeps the selection highlight stable when the details or episode panel is rebuilt.
    private fun isCurrentPlayingEpisode(episode: Int): Boolean {
        val key = context.getSharedPreferences("anilight_watch_later", Context.MODE_PRIVATE)
            .getString("current_playing_key", "").orEmpty()
        return key.startsWith("$slug|$episode|")
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
        val c = open(
            url,
            mapOf("Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, */*"),
            true
        ).apply {
            connectTimeout = 7000
            readTimeout = 9000
        }
        return try {
            if (c.responseCode !in 200..299) return false
            val contentType = c.contentType.orEmpty().lowercase()
            if (contentType.contains("mpegurl") || contentType.contains("vnd.apple")) return true
            c.inputStream.bufferedReader().use { reader ->
                val prefix = CharArray(256)
                val count = reader.read(prefix)
                count > 0 && String(prefix, 0, count).trimStart().startsWith("#EXTM3U")
            }
        } catch (_: Exception) {
            false
        } finally {
            c.disconnect()
        }
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
        posterMemoryCache.get(url)?.let { cached ->
            target.tag = url
            target.setImageBitmap(cached)
            return
        }
        target.tag = url
        if (!posterRequests.add(url)) return
        posterExecutor.execute {
            var bitmap: Bitmap? = null
            // Once a decoded bitmap is placed in the shared LRU cache, the cache and
            // ImageViews may still be drawing it. Never recycle it from this task.
            var bitmapTransferredToCache = false
            try {
                val directory = File(context.cacheDir, "anilight_posters").apply { mkdirs() }
                val file = File(directory, url.hashCode().toString() + ".img")
                if (!file.exists() || file.length() == 0L) {
                    val temp = File(directory, file.name + ".tmp")
                    try {
                        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 3500
                            readTimeout = 7000
                            instanceFollowRedirects = true
                            useCaches = true
                            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
                            setRequestProperty("Referer", "$site/")
                        }
                        try {
                            connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                        } finally { connection.disconnect() }
                        if (temp.length() > 0L && !temp.renameTo(file)) {
                            temp.copyTo(file, overwrite = true)
                            temp.delete()
                        }
                    } finally { if (temp.exists()) temp.delete() }
                }
                if (file.exists() && file.length() > 0L) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val maxDimension = 720
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                }
                bitmap?.let { decoded ->
                    posterMemoryCache.put(url, decoded)
                    bitmapTransferredToCache = true
                    ui.post {
                        if (target.tag == url && !decoded.isRecycled) {
                            target.setImageBitmap(decoded)
                        }
                    }
                }
                // Run disk-cache maintenance occasionally, not after every poster.
                val now = System.currentTimeMillis()
                if (now - posterCacheLastCleanupAt > 60_000L) synchronized(this@AniLightNativeScreen) {
                    if (now - posterCacheLastCleanupAt > 60_000L) {
                        posterCacheLastCleanupAt = now
                        val files = directory.listFiles()?.filter { it.isFile && it.extension == "img" }.orEmpty()
                        if (files.size > 240) {
                            files.sortedBy { it.lastModified() }
                                .take(files.size - 200)
                                .forEach { it.delete() }
                        }
                    }
                }
            } catch (_: Exception) {
                // Recycle only while this worker still exclusively owns the bitmap.
                // Recycling after cache insertion can crash ImageView drawing with
                // "Canvas: trying to use a recycled bitmap".
                if (!bitmapTransferredToCache) bitmap?.takeUnless { it.isRecycled }?.recycle()
            } finally {
                posterRequests.remove(url)
            }
        }
    }
    private fun items(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
    private fun loading(message: String) { body.removeAllViews(); body.addView(label(message, 14, muted, false).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(70), dp(10), dp(70)) }) }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun bottom(n: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(n) }
    private fun button(value: String, action: () -> Unit) = TextView(context).apply { text = value; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); isFocusable = true; setPadding(dp(12), dp(9), dp(12), dp(9)); background = rounded(accent, 10); setOnClickListener { action() } }
    private fun label(value: String, size: Int, color: Int, bold: Boolean) = TextView(context).apply { text = value; textSize = size.toFloat(); setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD }
    // Translucent Liquid Glass surfaces for Anime cards; keep badges/buttons solid.
    private fun rounded(color: Int, radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (color == surface) {
            if (light) intArrayOf(0xEFFFFFFF.toInt(), 0xDDE6F1FF.toInt(), 0xF7FFFFFF.toInt())
            else intArrayOf(0xAA49688F.toInt(), 0xB0121D30.toInt(), 0xAA202F49.toInt())
        } else intArrayOf(color, color)
    ).apply {
        cornerRadius = dp(radius).toFloat()
        if (color == surface) setStroke(dp(1),
            if (light) 0xDFFFFFFF.toInt() else 0xBDA9CFFF.toInt())
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun openSite() { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(site))) } }
    // Network/source failures are recorded for diagnostics, not shown as intrusive
    // Android toast banners over the episode list or player.
    private fun toast(s: String) {
        android.util.Log.w("SANY-AniLight", s)
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    companion object {
        @Volatile private var sharedHomepageCache: JSONObject? = null
        @Volatile private var sharedHomepageCheckedAt: Long = 0L
    }
}
