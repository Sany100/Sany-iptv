package com.sany.iptv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var urlInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var channelList: ListView
    private lateinit var groupSpinner: Spinner
    private lateinit var status: TextView
    private var channels = listOf<Channel>()
    private var visibleChannels = listOf<Channel>()
    private val favorites = mutableSetOf<String>()
    private val prefs by lazy { getSharedPreferences("sany_iptv", MODE_PRIVATE) }
    private val pickFile = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        buildUi()
        prefs.getString("playlist_url", null)?.takeIf { it.isNotBlank() }?.let {
            urlInput.setText(it)
            loadFromUrl(it)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 8)
            setBackgroundColor(Color.rgb(8, 13, 24))
        }
        root.addView(TextView(this).apply {
            text = "SANY IPTV"
            textSize = 24f
            setTextColor(Color.rgb(32, 214, 161))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 12)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        urlInput = EditText(this).apply {
            hint = "Paste M3U / M3U8 playlist URL"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(24, 33, 49))
            setPadding(12, 4, 12, 4)
        }
        root.addView(urlInput, LinearLayout.LayoutParams(-1, 48))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("LOAD URL") { loadFromUrl(urlInput.text.toString().trim()) }, LinearLayout.LayoutParams(0, 46, 1f))
        actions.addView(actionButton("OPEN FILE") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, pickFile)
        }, LinearLayout.LayoutParams(0, 46, 1f))
        root.addView(actions)
        searchInput = EditText(this).apply {
            hint = "Search channels"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(24, 33, 49))
            setPadding(12, 4, 12, 4)
        }
        root.addView(searchInput, LinearLayout.LayoutParams(-1, 46))
        searchInput.addTextChangedListener(SimpleTextWatcher { filterChannels() })
        groupSpinner = Spinner(this)
        root.addView(groupSpinner, LinearLayout.LayoutParams(-1, 42))
        status = TextView(this).apply {
            text = "Add a playlist URL or open an M3U file"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(4, 5, 4, 5)
        }
        root.addView(status)
        playerView = PlayerView(this).apply {
            useController = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, 210))
        channelList = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(40, 52, 70))
            dividerHeight = 1
            setBackgroundColor(Color.rgb(13, 20, 33))
        }
        root.addView(channelList, LinearLayout.LayoutParams(-1, 0, 1f))
        channelList.setOnItemClickListener { _, _, position, _ -> visibleChannels.getOrNull(position)?.let { play(it) } }
        channelList.setOnItemLongClickListener { _, _, position, _ ->
            visibleChannels.getOrNull(position)?.let { channel ->
                if (!favorites.add(channel.url)) favorites.remove(channel.url)
                prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                filterChannels()
                Toast.makeText(this, if (channel.url in favorites) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
            }
            true
        }
        setContentView(root)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(18, 125, 103))
        setOnClickListener { action() }
    }

    private fun loadFromUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "Enter a playlist URL", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Loading playlist…"
        thread {
            try {
                val content = URL(url).openConnection().apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                }.getInputStream().bufferedReader().use { it.readText() }
                runOnUiThread {
                    urlInput.setText(url)
                    prefs.edit().putString("playlist_url", url).apply()
                    setPlaylist(content)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Load failed: " + (e.message ?: "network error") }
            }
        }
    }

    private fun setPlaylist(content: String) {
        channels = M3uParser.parse(content)
        val groups = listOf("All", "★ Favorites") + channels.map { it.group }.distinct().sorted()
        groupSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groups)
        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = filterChannels()
        }
        status.text = "Loaded " + channels.size + " channels • Long-press to favorite"
        filterChannels()
    }

    private fun filterChannels() {
        if (!::groupSpinner.isInitialized || !::channelList.isInitialized) return
        val group = groupSpinner.selectedItem?.toString() ?: "All"
        val query = searchInput.text.toString().trim().lowercase()
        visibleChannels = channels.filter { channel ->
            val groupMatch = when (group) {
                "All" -> true
                "★ Favorites" -> channel.url in favorites
                else -> channel.group == group
            }
            groupMatch && (query.isBlank() || channel.name.lowercase().contains(query) || channel.group.lowercase().contains(query))
        }
        channelList.adapter = object : ArrayAdapter<Channel>(this, android.R.layout.simple_list_item_2, android.R.id.text1, visibleChannels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val title = view.findViewById<TextView>(android.R.id.text1)
                val subtitle = view.findViewById<TextView>(android.R.id.text2)
                val item = getItem(position)!!
                title.text = (if (item.url in favorites) "★ " else "") + item.name
                title.setTextColor(Color.WHITE)
                subtitle.text = item.group
                subtitle.setTextColor(Color.LTGRAY)
                view.setBackgroundColor(Color.rgb(13, 20, 33))
                return view
            }
        }
    }

    private fun play(channel: Channel) {
        try {
            if (player == null) player = ExoPlayer.Builder(this).build().also { playerView.player = it }
            player?.setMediaItem(MediaItem.fromUri(channel.url))
            player?.prepare()
            player?.play()
            status.text = "Now playing: " + channel.name
            prefs.edit().putString("last_channel", channel.url).apply()
        } catch (e: Exception) {
            status.text = "Playback error: " + e.message
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFile && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    setPlaylist(content)
                } catch (e: Exception) {
                    status.text = "File error: " + e.message
                }
            }
        }
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

private class SimpleTextWatcher(val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
