package com.sany.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.HomePageList
import kotlinx.coroutines.launch

/**
 * Provider directory and provider-specific landing page.
 * Catalogue data remains owned by each registered CloudStream MainAPI.
 */
class CloudStreamProviderHomeScreen(
    private val context: Context,
    private val providers: List<MainAPI>,
    private val backgroundColor: Int,
    private val foregroundColor: Int,
    private val accentColor: Int,
    private val onSearch: (String, String) -> Unit,
    private val onOpenItem: (String, String, String) -> Unit,
    private val onBackToAnime: () -> Unit
) {
    private var selectedProvider: String? = null
    private lateinit var root: LinearLayout

    fun create(): View {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        renderDirectory()
        return root
    }

    /** Returns true when Back was consumed by this screen. */
    fun handleBack(): Boolean {
        if (selectedProvider != null) {
            selectedProvider = null
            renderDirectory()
            return true
        }
        return false
    }

    private fun renderDirectory() {
        root.removeAllViews()
        root.addView(header("More Plugin Providers", "Choose a provider to open its own page"))
        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(28))
        }
        providers.sortedBy { it.name.lowercase() }.forEach { provider ->
            val card = TextView(context).apply {
                text = "▶   ${provider.name}\n      Open provider home"
                textSize = 16f
                setTextColor(foregroundColor)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(18), dp(20), dp(18), dp(20))
                background = surface()
                SanyLiquidGlass.elevate(this, context, 4f)
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    selectedProvider = provider.name
                    renderProviderHome(provider.name)
                }
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(footer("Back to Anime Series") { onBackToAnime() })
    }

    private fun renderProviderHome(providerName: String) {
        root.removeAllViews()
        root.addView(header(providerName, "Independent provider home"))
        val scroll = ScrollView(context)
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        val hero = TextView(context).apply {
            text = providerName
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(accentColor, 0xFF172B4D.toInt())
            ).apply { cornerRadius = dp(18).toFloat() }
        }
        page.addView(hero, LinearLayout.LayoutParams(-1, dp(132)))
        page.addView(label("Search anime on $providerName"), topMargin(18))
        val search = EditText(context).apply {
            hint = "Anime title লিখুন"
            setSingleLine(true)
            setTextColor(foregroundColor)
            setHintTextColor(0xFF8993A3.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = surface()
        }
        page.addView(search, topMargin(8))
        page.addView(button("SEARCH") {
            val query = search.text?.toString()?.trim().orEmpty()
            if (query.isNotBlank()) onSearch(providerName, query)
            else search.error = "Anime title লিখুন"
        }, topMargin(10))
        val catalogue = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(label("Provider catalogue"), topMargin(22))
        page.addView(catalogue, topMargin(8))
        loadProviderCatalogue(providerName, catalogue)
        page.addView(button("← All Providers") {
            selectedProvider = null
            renderDirectory()
        }, topMargin(20))
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(footer("Back to provider list") {
            selectedProvider = null
            renderDirectory()
        })
    }

    private fun loadProviderCatalogue(providerName: String, host: LinearLayout) {
        host.removeAllViews()
        host.addView(label("Loading $providerName homepage…", 13), topMargin(8))
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val result = runCatching { SanyCloudStreamPluginRuntime.homePage(providerName, 1) }
            root.post {
                if (selectedProvider != providerName) return@post
                host.removeAllViews()
                result.onSuccess { shelves ->
                    if (shelves.isEmpty()) {
                        host.addView(label("এই Provider-এর homepage catalogue খালি। Search ব্যবহার করুন।", 13), topMargin(8))
                    } else {
                        shelves.forEach { shelf -> renderShelf(providerName, shelf, host) }
                    }
                }.onFailure { error ->
                    host.addView(label("Homepage load failed: ${error.message ?: error.javaClass.simpleName}", 13), topMargin(8))
                }
            }
        }
    }

    private fun renderShelf(providerName: String, shelf: HomePageList, host: LinearLayout) {
        host.addView(label(shelf.name, 17, true), topMargin(18))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        shelf.list.take(20).forEach { item ->
            val card = TextView(context).apply {
                text = item.name
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(foregroundColor)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(14))
                minWidth = dp(132)
                maxWidth = dp(172)
                minHeight = dp(96)
                background = surface()
                SanyLiquidGlass.elevate(this, context, 4f)
                isFocusable = true
                isClickable = true
                setOnClickListener { onOpenItem(providerName, item.url, item.name) }
            }
            row.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
                rightMargin = dp(9)
            })
        }
        val horizontal = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        host.addView(horizontal)
    }

    private fun header(title: String, subtitle: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = glassSurface(0)
        addView(label(title, 20, true))
        addView(label(subtitle, 12), topMargin(4))
    }

    private fun label(value: String, size: Int = 14, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(foregroundColor)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(value: String, action: () -> Unit) = TextView(context).apply {
        text = value
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(accentColor)
            cornerRadius = dp(12).toFloat()
        }
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun footer(value: String, action: () -> Unit) =
        button(value, action).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

    private fun surface() = glassSurface(14)

    private fun glassSurface(radius: Int) =
        SanyLiquidGlass.background(context, radius, backgroundColor != Color.BLACK, accentColor)

    private fun topMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(value)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
