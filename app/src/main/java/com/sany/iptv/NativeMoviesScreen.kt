package com.sany.iptv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Movies contains only links explicitly added by the app owner/user.
 * No external catalogue, trending feed, automatic search, or provider scraping.
 */
class NativeMoviesScreen(
    private val context: Context,
    private val seriesMode: Boolean,
    private val light: Boolean,
    private val accent: Int,
    private val onPlay: (String, String) -> Unit
) {
    private val prefs = context.getSharedPreferences("sany_iptv", Context.MODE_PRIVATE)
    private val bg = if (light) Color.rgb(242, 246, 252) else Color.BLACK
    private val card = if (light) Color.WHITE else Color.rgb(18, 21, 28)
    private val fg = if (light) Color.rgb(22, 28, 38) else Color.WHITE
    private val muted = if (light) Color.rgb(90, 101, 118) else Color.rgb(160, 170, 185)
    private lateinit var body: LinearLayout

    fun create(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(bg)
        }
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(ScrollView(context).apply {
            isFillViewport = true
            addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        renderMovies()
        return root
    }

    private fun readMovies(): MutableList<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val raw = prefs.getString("owner_added_movies", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val url = item.optString("url").trim()
                if (title.isNotBlank() && isHttpUrl(url)) result += title to url
            }
        } catch (_: Exception) { }
        return result
    }

    private fun saveMovies(items: List<Pair<String, String>>) {
        val array = JSONArray()
        items.forEach { (title, url) ->
            array.put(JSONObject().put("title", title).put("url", url))
        }
        prefs.edit().putString("owner_added_movies", array.toString()).apply()
    }

    private fun renderMovies() {
        body.removeAllViews()
        body.addView(button("+  ADD MOVIE LINK") { showAddMovieDialog() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
        val movies = readMovies()
        if (movies.isEmpty()) {
            body.addView(label("এখনো কোনো Movie link যোগ করা হয়নি।\nতুমি যে লিংক যোগ করবে, শুধু সেগুলোই এখানে দেখাবে।", 14, muted, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(36), dp(14), dp(36))
            })
            return
        }
        movies.forEachIndexed { index, (name, url) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(card)
                isFocusable = true
            }
            row.addView(label(name, 15, fg, true))
            row.addView(label(url, 10, muted, false).apply {
                maxLines = 2
                setPadding(0, dp(4), 0, dp(8))
            })
            row.addView(button("▶  PLAY") { onPlay(name, url) })
            row.setOnLongClickListener {
                AlertDialog.Builder(context)
                    .setTitle("Remove movie link?")
                    .setMessage(name)
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("REMOVE") { _, _ ->
                        val updated = readMovies().toMutableList()
                        if (index in updated.indices) {
                            updated.removeAt(index)
                            saveMovies(updated)
                            renderMovies()
                        }
                    }.show()
                true
            }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun showAddMovieDialog() {
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val titleInput = EditText(context).apply {
            hint = "Movie name"
            setSingleLine(true)
            setTextColor(fg)
        }
        val urlInput = EditText(context).apply {
            hint = "Direct movie URL (HTTP / HTTPS)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setTextColor(fg)
        }
        form.addView(titleInput)
        form.addView(urlInput)
        AlertDialog.Builder(context)
            .setTitle("Add movie link")
            .setView(form)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("ADD") { _, _ ->
                val name = titleInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isBlank() || !isHttpUrl(url)) {
                    AlertDialog.Builder(context).setMessage("Movie name এবং সঠিক HTTP/HTTPS URL দিন।")
                        .setPositiveButton("OK", null).show()
                } else {
                    val movies = readMovies()
                    if (movies.none { it.second == url }) {
                        movies.add(name to url)
                        saveMovies(movies)
                    }
                    renderMovies()
                }
            }.show()
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val parsed = java.net.URL(value)
        (parsed.protocol == "http" || parsed.protocol == "https") && parsed.host.isNotBlank()
    }.getOrDefault(false)

    private fun label(value: String, size: Int, color: Int, bold: Boolean) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(value: String, action: () -> Unit) = TextView(context).apply {
        text = value
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isFocusable = true
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = rounded(accent)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
