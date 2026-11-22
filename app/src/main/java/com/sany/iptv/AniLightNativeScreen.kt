package com.sany.iptv

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import java.net.URLEncoder
import kotlin.concurrent.thread

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
    private val bg = if (light) 0xFFF2F6FC.toInt() else Color.BLACK
    private val fg = if (light) 0xFF141B26.toInt() else Color.WHITE
    private val muted = if (light) 0xFF5A6576.toInt() else 0xFF94A0B4.toInt()
    private val surface = if (light) Color.WHITE else 0xFF16191F.toInt()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private var slug = ""
    private var animeId = 0
    private var animeTitle = ""
    private var language = "sub"
    private var anime: JSONObject? = null
    private var isHomePage = true
    private var filterPage = 1
    private var filterSort = "POPULARITY_DESC"
    private var filterGenre: String? = null
    private var filterHasNext = false
    private var showingFilterResults = false
    @Volatile private var session: String? = null
    @Volatile private var sessionExpiry = 0L

    /** Returns true when Back was consumed inside the AniLight screen. */
    fun handleBack(): Boolean {
        if (isHomePage) return false
        home()
        return true
    }

    fun create(): View {
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(1), dp(8), dp(1), dp(24)); setBackgroundColor(bg) }
        val scroll = ScrollView(context).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }
        loading("Connecting to AniLight…")
        thread(name = "AniLight-Domain") {
            refreshDomainBlocking()
            ui.post { home() }
        }
        return scroll
    }

    private fun home() {
        isHomePage = true
        body.removeAllViews()
        val head = row()
        head.addView(label("ANILIGHT", 22, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(button("OPEN SITE") { openSite() })
        body.addView(head, bottom(12))
        val search = row()
        val input = EditText(context).apply {
            hint = "Search anime"; setSingleLine(true); textSize = 14f; setTextColor(fg); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT; background = rounded(surface, 12); setPadding(dp(12), 0, dp(12), 0)
        }
        search.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        search.addView(button("SEARCH") { searchAnime(input.text.toString().trim()) })
        body.addView(search, bottom(8))
        body.addView(button("FILTER ANIME") { showFilterDialog() }, bottom(10))

        // Match the full AniLight Cloudstream catalogue: homepage feeds plus every supported genre.
        body.addView(label("ANIME CATEGORIES", 12, muted, true), bottom(6))
        val categoryRail = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val categoryItems = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        categoryRail.addView(categoryItems)
        val catalogueCategories = listOf(
            "Trending Now" to "trending",
            "Most Popular" to "popular",
            "This Season" to "seasonal",
            "Previous Season" to "previousSeason",
            "Popular Movies" to "movies",
            "Top Rated" to "topRated",
            "Recently Added" to "recent",
            "Upcoming" to "upcoming"
        )
        val genreCategories = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror",
            "Mecha", "Mystery", "Psychological", "Romance", "Sci-Fi",
            "Slice of Life", "Sports", "Supernatural", "Thriller"
        ).map { it to "genre:$it" }

        // Show every AniLight genre as a first-class item in the main category rail.
        (catalogueCategories + genreCategories).forEach { (name, key) ->
            categoryItems.addView(button(name) { category(key) }, LinearLayout.LayoutParams(-2, dp(40)).apply {
                rightMargin = dp(6)
            })
        }
        body.addView(categoryRail, bottom(14))
        category("trending", homeLanding = true)
    }

    private fun category(key: String, homeLanding: Boolean = false) {
        if (key.startsWith("genre:")) {
            val selectedGenre = key.removePrefix("genre:")
            loadFiltered("POPULARITY_DESC", selectedGenre)
            return
        }
        showingFilterResults = false
        filterHasNext = false
        loading("Loading $key…")
        val cached = homepageCache
        if (cached != null && System.currentTimeMillis() - homepageCheckedAt < 5 * 60_000L) {
            isHomePage = homeLanding
            showHomeCategory(key, cached)
            return
        }
        request("$api/homepage") { data ->
            if (data != null) {
                homepageCache = data
                homepageCheckedAt = System.currentTimeMillis()
            }
            isHomePage = homeLanding
            showHomeCategory(key, data ?: homepageCache)
        }
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
        isHomePage = false
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

    private fun loadFiltered(sort: String, genre: String?, page: Int = 1) {
        isHomePage = false
        showingFilterResults = true
        if (page == 1) {
            filterSort = sort
            filterGenre = genre
            filterPage = 1
            filterHasNext = false
            loading("Applying anime filters…")
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
            showCards("Filtered anime", results, append = page > 1)
        }
    }

    private fun searchAnime(q: String) {
        showingFilterResults = false
        filterHasNext = false
        if (q.isBlank()) { category("trending"); return }
        isHomePage = false
        loading("Searching…")
        request("$api/search?q=${enc(q)}&hideAdult=true") { data ->
            val arr = data?.optJSONArray("results") ?: data?.optJSONArray("data")
            showCards("Search results", items(arr).filterNot { it.optBoolean("isAdult", false) })
        }
    }

    private fun showCards(title: String, cards: List<JSONObject>, append: Boolean = false) {
        if (append) {
            if (body.childCount > 0 && body.getChildAt(body.childCount - 1).tag == "anilight_load_more") {
                body.removeViewAt(body.childCount - 1)
            }
        } else {
            body.removeAllViews()
            val head = row()
            head.addView(label(title.uppercase(), 20, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(button("HOME") { home() })
            body.addView(head, bottom(14))
        }
        if (cards.isEmpty() && !append) {
            body.addView(label("কোনো anime পাওয়া যায়নি। API unavailable হলে পরে আবার চেষ্টা করো।", 14, muted, false))
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
            body.addView(hero, LinearLayout.LayoutParams(-1, dp(245)).apply { bottomMargin = dp(14) })
        }

        val sectionHead = label(if (title.equals("trending", true)) "TRENDING NOW" else title.uppercase(), 16, fg, true)
        body.addView(sectionHead, bottom(10))
        val strip = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val rail = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(rail)
        val visibleCards = if (!append && cards.isNotEmpty()) cards.drop(1) else cards
        visibleCards.forEach { item ->
            val cover = item.optJSONObject("coverImage")
            val posterUrl = cover?.optString("extraLarge").orEmpty()
                .ifBlank { cover?.optString("large").orEmpty().ifBlank { cover?.optString("medium").orEmpty() } }
            val titleObj = item.optJSONObject("title")
            val name = titleObj?.optString("english").orEmpty()
                .ifBlank { titleObj?.optString("romaji").orEmpty().ifBlank { "Anime" } }
            val year = item.optInt("seasonYear").takeIf { it > 0 }?.toString().orEmpty()
            val score = item.optDouble("averageScore", 0.0).takeIf { it > 0 }?.toString()?.let { "★ $it" }.orEmpty()

            val card = FrameLayout(context).apply {
                background = rounded(surface, 15)
                clipToOutline = true
                isFocusable = true
                setOnClickListener { openAnime(item) }
            }
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
            }
            card.addView(poster, FrameLayout.LayoutParams(-1, -1))
            if (posterUrl.startsWith("http")) loadImage(posterUrl, poster)

            val bottomShade = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00101018, 0xD9101018.toInt())
                )
            }
            card.addView(bottomShade, FrameLayout.LayoutParams(-1, dp(62), Gravity.BOTTOM))

            val glassInfo = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(5), dp(6), dp(5))
                background = GradientDrawable().apply {
                    setColor(0xAA111624.toInt())
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), 0x55FFFFFF)
                }
                elevation = dp(3).toFloat()
                addView(label(name, 10, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(label(listOf(year, score).filter { it.isNotBlank() }.joinToString(" • "), 9, 0xFFD8DCE8.toInt(), false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            card.addView(glassInfo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(dp(4), 0, dp(4), dp(4))
            })
            rail.addView(card, LinearLayout.LayoutParams(dp(158), dp(230)).apply {
                rightMargin = dp(7)
            })
        }
        body.addView(strip, bottom(16))

        if (showingFilterResults && filterHasNext) {
            body.addView(button("LOAD MORE") { loadFiltered(filterSort, filterGenre, filterPage + 1) }.apply {
                tag = "anilight_load_more"
            }, bottom(10))
        }
    }

    private fun openAnime(item: JSONObject) {
        isHomePage = false
        slug = item.optString("slug")
        language = "sub"
        if (slug.isBlank()) { toast("Anime slug পাওয়া যায়নি"); return }
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
        head.addView(button("‹ BACK") { home() })
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
                add(own, false); add(backup, true)
                val unique = sources.distinctBy { it.second }
                ui.post {
                    if (unique.isEmpty()) { toast("Stream পাওয়া যায়নি। AniLight API/source unavailable হতে পারে।"); showDetailsAgain(); return@post }
                    AlertDialog.Builder(context).setTitle("$animeTitle — Episode $episode")
                        .setItems(unique.map { "${it.first} • ${language.uppercase()}" }.toTypedArray()) { _, which ->
                            onPlay("$animeTitle • Episode $episode", unique[which].second, subtitles.firstOrNull())
                        }.setNegativeButton("CANCEL", null).show()
                    showDetailsAgain()
                }
            } catch (e: Exception) { ui.post { toast(e.message ?: "Stream resolve failed"); showDetailsAgain() } }
        }
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
        thread(name = "AniLight-Poster") { try { val bitmap = URL(url).openStream().use { BitmapFactory.decodeStream(it) }; if (bitmap != null) ui.post { target.setImageBitmap(bitmap) } } catch (_: Exception) { } }
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
}
