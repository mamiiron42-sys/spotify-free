package io.github.ytdlpandroid

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ytdlpandroid.viewmodel.LibraryViewModel
import io.github.ytdlpandroid.viewmodel.PlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Playlists screen: the searchable playlist list and the full-screen playlist page
 * opened from it.
 *
 * Extracted from MainActivity, which keeps the ViewModels, the playlist store, and the
 * playback/download plumbing. The section owns its whole view tree plus the detail page's
 * state, and reaches its host only for what is not playlist-scoped: resolving a stored URI
 * to a track, playback and enqueueing, the tab and back-press wiring, and the document
 * pickers, which stay in the activity because they carry request codes and a pending id.
 */
internal class PlaylistsScreen(
    private val context: Context,
    private val host: Host,
) : PlaylistAdapter.Callbacks {
    /** The narrow surface the playlists screen consumes from its host. */
    interface Host {
        val playlistViewModel: PlaylistViewModel
        val libraryViewModel: LibraryViewModel

        /** Shared with the activity, which owns the store and its lifetime. */
        val playlists: PlaylistStore
        val playbackService: PlaybackService?
        val lifecycleScope: LifecycleCoroutineScope

        /** The tab on screen, for the detail page's return-tab bookkeeping. */
        val currentTab: Int

        /** Resolves a stored playlist URI through the library and the metadata cache. */
        fun resolveTrack(raw: String): AudioTrack?

        fun showError(message: String)

        /** The optional action carries the undo affordance of a removed track. */
        fun showSnackbar(message: String, actionLabel: String?, action: (() -> Unit)?)

        fun showTab(label: Int)
        fun enqueueUrls(urls: List<String>, showStatus: Boolean)
        fun loadPlaylistAndPlay(id: Long)
        fun playTrackList(tracks: List<AudioTrack>, index: Int)
        fun choosePlaylistForTrack(track: AudioTrack)
        fun showTrackDetails(track: AudioTrack)
        fun refreshPlaylists()

        /** Runs [block] on the main thread, dropping it when the host is gone. */
        fun runOnMain(block: () -> Unit)

        /** Refreshes the library surfaces that mirror playlist data. */
        fun refreshPlaylistConsumers()

        /** True while the home tab shows the playlists category, which mirrors playlist data. */
        fun homeShowingPlaylists(): Boolean
        fun renderHomeLibrary()

        /** The activity owns the back dispatcher, so it owns the detail page's callback. */
        fun setPlaylistDetailBackEnabled(enabled: Boolean)

        /** Starts ACTION_CREATE_DOCUMENT; the activity records the pending playlist id. */
        fun requestM3uExport(fileName: String, playlistId: Long)

        /** Starts ACTION_OPEN_DOCUMENT for an M3U import. */
        fun requestM3uImport()
    }

    private val playlistAdapter = PlaylistAdapter(this)

    private lateinit var playlistRoot: LinearLayout
    private lateinit var playlistList: RecyclerView
    private lateinit var playlistSearch: EditText
    private var selectedPlaylistId: Long? = null

    /** Tab to return to when the playlist page is closed (e.g. Home when opened from a Home card). */
    private var playlistReturnTab: Int? = null

    /** Builds the screen into [scroll]; call once, from the host's screen construction. */
    fun attachTo(scroll: ScrollView) {
        playlistRoot = scrollContentOf(scroll)
        buildPlaylistListPage()
    }

    /** Redraws for a screen state, picking the list or the open detail page. */
    fun renderPlaylists(screen: PlaylistScreen) {
        host.refreshPlaylistConsumers()
        if (::playlistRoot.isInitialized) {
            val selectedId = selectedPlaylistId
            if (selectedId != null) {
                val selected = screen.playlists.firstOrNull { it.id == selectedId }
                if (selected == null) {
                    selectedPlaylistId = null
                    host.setPlaylistDetailBackEnabled(false)
                    buildPlaylistListPage()
                } else {
                    renderPlaylistDetail(selected)
                }
            } else if (::playlistList.isInitialized) {
                val query = if (::playlistSearch.isInitialized) playlistSearch.text.toString().trim() else ""
                playlistAdapter.submitList(playlistRows(screen.playlists, screen.error, query))
            }
        }
        if (host.homeShowingPlaylists()) host.renderHomeLibrary()
    }

    /** True when the detail page was open and consumed the press. */
    fun handleBackPressed(): Boolean {
        if (selectedPlaylistId == null) return false
        closePlaylistDetail()
        return true
    }

    /** Empties the filter field; the list re-renders from the text watcher. */
    fun clearSearch() {
        if (::playlistSearch.isInitialized) playlistSearch.text.clear()
    }

    /** Pops the card for [playlistId] after a track was added to it. */
    fun animatePlaylistRow(playlistId: Long) {
        playlistList.findViewWithTag<View>("playlist-$playlistId")?.let(::animateSuccess)
    }

    /** Opens the playlist page for [id], remembering the tab to return to. */
    fun openPlaylist(id: Long) {
        // The ViewModel list loads asynchronously; fall back to the store so a tap never no-ops.
        val playlist = host.playlistViewModel.playlists.value.firstOrNull { it.id == id }
            ?: runCatching { host.playlists.getPlaylists().firstOrNull { it.id == id } }.getOrNull()
        if (playlist == null) {
            host.refreshPlaylists()
            return
        }
        if (host.currentTab != R.string.nav_playlists) playlistReturnTab = host.currentTab
        selectedPlaylistId = id
        host.setPlaylistDetailBackEnabled(true)
        host.showTab(R.string.nav_playlists)
        renderPlaylistDetail(playlist)
        (playlistRoot.parent?.parent as? ScrollView)?.scrollTo(0, 0)
    }

    /** Asks the host for a name and creates the playlist, then reports the new id. */
    fun promptCreatePlaylist(onCreated: (Long) -> Unit = {}) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.playlist_name)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.create_playlist))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.create, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = context.getString(R.string.playlist_name_required)
                    return@setOnClickListener
                }
                try {
                    val duplicate = host.playlists.getPlaylists().any { it.name.equals(name, ignoreCase = true) }
                    if (duplicate) {
                        input.error = context.getString(R.string.playlist_name_exists)
                        return@setOnClickListener
                    }
                    val id = host.playlists.createPlaylist(name)
                    dialog.dismiss()
                    host.refreshPlaylists()
                    onCreated(id)
                } catch (error: Exception) {
                    host.showError(context.getString(R.string.playlist_error, error.localizedMessage ?: ""))
                }
            }
        }
        dialog.show()
    }

    /** Starts the system document picker for an M3U import. */
    fun launchPlaylistImport() {
        host.requestM3uImport()
    }

    /**
     * Imports an M3U file. Delegates to the ViewModel so entries resolve by URI, file name,
     * base name, or EXTINF title; the old inline parser only matched lines that were exactly
     * a stored URI, so a normal playlist of file paths imported nothing.
     */
    fun importPlaylistM3u(uri: Uri) {
        host.lifecycleScope.launch {
            host.playlistViewModel.importM3U(uri)
            host.refreshPlaylists()
        }
    }

    private fun buildPlaylistListPage() {
        val root = playlistRoot
        root.removeAllViews()
        root.setBackgroundColor(COLOR_BACKGROUND)
        root.addView(context.sectionTitle(context.getString(R.string.playlists_title)))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(context).apply {
            text = context.getString(R.string.create_playlist)
            context.styleActionButton(this, true)
            setOnClickListener { promptCreatePlaylist() }
        }, LinearLayout.LayoutParams(0, context.dp(48), 1f).apply { marginEnd = context.dp(6) })
        actions.addView(Button(context).apply {
            text = context.getString(R.string.import_playlist_m3u)
            context.styleActionButton(this, false)
            setOnClickListener { launchPlaylistImport() }
        }, LinearLayout.LayoutParams(0, context.dp(48), 1f).apply { marginStart = context.dp(6) })
        root.addView(actions, matchWidth())

        playlistSearch = EditText(context).apply {
            hint = context.getString(R.string.playlist_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(context.dp(14), 0, context.dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, context.dp(14))
            minHeight = context.dp(48)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderPlaylists(currentPlaylistScreen())
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(playlistSearch, matchWidth().apply { topMargin = context.dp(12) })

        playlistList = RecyclerView(context).apply {
            adapter = playlistAdapter
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false
            itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
        }
        root.addView(playlistList, matchWidth().apply { topMargin = context.dp(8) })
        renderPlaylists(currentPlaylistScreen())
    }

    private fun currentPlaylistScreen() = PlaylistScreen(
        playlists = host.playlistViewModel.playlists.value,
        error = host.playlistViewModel.playlistError.value,
    )

    private fun playlistRows(
        saved: List<SavedPlaylist>,
        error: String?,
        query: String,
    ): List<PlaylistRow> {
        if (error != null) {
            return listOf(PlaylistRow.Message(context.getString(R.string.playlist_error, error)))
        }
        val filtered = saved.filter { it.name.contains(query, ignoreCase = true) }
        if (filtered.isEmpty()) {
            val rows = ArrayList<PlaylistRow>()
            rows += PlaylistRow.Message(
                context.getString(
                    if (saved.isEmpty()) R.string.playlists_empty
                    else R.string.playlists_no_matches,
                ),
            )
            if (saved.isNotEmpty()) rows += PlaylistRow.ClearSearch
            return rows
        }
        return filtered.map { playlist ->
            PlaylistRow.Playlist(
                playlist = playlist,
                cover = playlist.tracks.firstNotNullOfOrNull { uri -> host.resolveTrack(uri) },
            )
        }
    }

    private fun closePlaylistDetail() {
        if (selectedPlaylistId == null) return
        selectedPlaylistId = null
        host.setPlaylistDetailBackEnabled(false)
        buildPlaylistListPage()
        renderPlaylists(currentPlaylistScreen())
        val returnTab = playlistReturnTab
        playlistReturnTab = null
        if (returnTab != null && host.currentTab == R.string.nav_playlists) host.showTab(returnTab)
    }

    /** Spotify-style playlist page: tinted header with cover, big play/shuffle, flat track list. */
    private fun renderPlaylistDetail(playlist: SavedPlaylist) {
        if (!::playlistRoot.isInitialized || selectedPlaylistId != playlist.id) return
        val root = playlistRoot
        root.removeAllViews()

        val available = playlist.tracks.mapIndexedNotNull { index, raw ->
            host.resolveTrack(raw)?.let { Triple(index, raw, it) }
        }
        val resolvedTracks = available.map { it.third }
        val localTracks = resolvedTracks.filter { it.uri.scheme != "http" && it.uri.scheme != "https" }

        val tint = context.playlistTintColor(resolvedTracks)
        root.background = LayerDrawable(
            arrayOf(
                ColorDrawable(COLOR_BACKGROUND),
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(tint, COLOR_BACKGROUND),
                ),
            ),
        ).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, context.dp(480))
        }

        // Top bar: round back button.
        root.addView(
            context.iconButton(
                iconRes = R.drawable.ic_arrow_back,
                description = context.getString(R.string.playlist_back),
                fill = Color.argb(110, 0, 0, 0),
                tint = COLOR_TEXT,
                sizeDp = 40,
                insetDp = 9,
            ) { closePlaylistDetail() },
            LinearLayout.LayoutParams(context.dp(40), context.dp(40)),
        )

        // Cover art, centred.
        val coverSize = minOf(context.dp(240), (context.windowWidthPx() * 0.6f).toInt())
        root.addView(
            context.playlistCoverView(resolvedTracks, coverSize),
            LinearLayout.LayoutParams(coverSize, coverSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = context.dp(4)
                bottomMargin = context.dp(18)
            },
        )

        root.addView(TextView(context).apply {
            text = context.getString(R.string.playlist_label)
            textSize = 12f
            setTextColor(COLOR_MUTED)
        }, matchWidth())
        root.addView(TextView(context).apply {
            text = playlist.name
            textSize = 28f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(0, context.dp(2), 0, context.dp(4))
        }, matchWidth())
        val totalMs = resolvedTracks.sumOf { it.durationMs.coerceAtLeast(0L) }
        root.addView(TextView(context).apply {
            text = if (totalMs > 0L) {
                context.getString(R.string.playlist_summary, playlist.tracks.size, formatPlaylistDuration(totalMs))
            } else {
                context.getString(R.string.playlist_track_count, playlist.tracks.size)
            }
            textSize = 13f
            setTextColor(COLOR_MUTED)
        }, matchWidth())

        // Action row: add, more | shuffle, play.
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            context.iconButton(
                iconRes = R.drawable.ic_add,
                description = context.getString(R.string.add_tracks),
                fill = Color.TRANSPARENT,
                tint = COLOR_MUTED,
                sizeDp = 40,
                insetDp = 10,
            ) { chooseTracksForPlaylist(playlist.id) },
            LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply { marginEnd = context.dp(12) },
        )
        actions.addView(
            context.iconButton(
                iconRes = R.drawable.ic_more_vert,
                description = context.getString(R.string.playlist_actions_accessibility, playlist.name),
                tint = COLOR_MUTED,
                sizeDp = 40,
                insetDp = 10,
            ) {},
            LinearLayout.LayoutParams(context.dp(40), context.dp(40)),
        )
        // PopupMenu needs the view as its anchor, so wire it after creation.
        actions.getChildAt(1).setOnClickListener { showPlaylistMenu(playlist, it) }
        actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(
            context.iconButton(
                iconRes = R.drawable.ic_shuffle,
                description = context.getString(R.string.playlist_shuffle_play),
                tint = COLOR_TEXT,
                sizeDp = 48,
                insetDp = 12,
            ) {
                if (localTracks.isEmpty()) host.loadPlaylistAndPlay(playlist.id)
                else {
                    host.playTrackList(localTracks, localTracks.indices.random())
                    host.playbackService?.setShuffle(true)
                }
            },
            LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { marginEnd = context.dp(10) },
        )
        actions.addView(
            context.iconButton(
                iconRes = R.drawable.ic_play,
                description = context.getString(R.string.playlist_play_accessibility, playlist.name),
                fill = COLOR_ACCENT,
                tint = COLOR_BACKGROUND,
                sizeDp = 58,
                insetDp = 17,
            ) {
                host.playbackService?.setShuffle(false)
                host.loadPlaylistAndPlay(playlist.id)
            },
            LinearLayout.LayoutParams(context.dp(58), context.dp(58)),
        )
        root.addView(actions, matchWidth().apply {
            topMargin = context.dp(10)
            bottomMargin = context.dp(12)
        })

        if (available.isEmpty()) {
            root.addView(
                context.emptyMessage(context.getString(R.string.playlist_no_available_tracks)),
                matchWidth(),
            )
            root.addView(Button(context).apply {
                text = context.getString(R.string.add_tracks)
                context.styleActionButton(this, true)
                setOnClickListener { chooseTracksForPlaylist(playlist.id) }
            }, LinearLayout.LayoutParams(WRAP, context.dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = context.dp(8)
            })
            return
        }

        available.forEach { (originalIndex, raw, track) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(6), 0, context.dp(6))
                isClickable = true
                isFocusable = true
                background = android.util.TypedValue().let { value ->
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                    context.getDrawable(value.resourceId)
                }
                setOnLongClickListener {
                    host.showTrackDetails(track)
                    true
                }
                setOnClickListener {
                    if (track.uri.scheme == "http" || track.uri.scheme == "https") {
                        host.enqueueUrls(listOf(raw), showStatus = false)
                    } else {
                        val localIndex = localTracks.indexOf(track)
                        if (localIndex >= 0) host.playTrackList(localTracks, localIndex)
                    }
                }
            }
            row.addView(
                context.artworkView(track, context.dp(52)),
                LinearLayout.LayoutParams(context.dp(52), context.dp(52)).apply {
                    marginEnd = context.dp(12)
                },
            )
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(context).apply {
                text = track.title
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_TEXT)
            })
            labels.addView(TextView(context).apply {
                text = if (track.durationMs > 0L) "${track.artist} · ${context.formatTime(track.durationMs)}" else track.artist
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_MUTED)
                setPadding(0, context.dp(2), 0, 0)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
            val more = context.iconButton(
                iconRes = R.drawable.ic_more_vert,
                description = context.getString(R.string.playlist_track_actions_accessibility, track.title),
                tint = COLOR_MUTED,
                sizeDp = 40,
                insetDp = 10,
            ) {}
            more.setOnClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    if (originalIndex > 0) menu.add(0, 0, 0, R.string.move_up)
                    if (originalIndex < playlist.tracks.lastIndex) menu.add(0, 1, 1, R.string.move_down)
                    menu.add(0, 2, 2, R.string.add_to_playlist)
                    menu.add(0, 3, 3, R.string.track_details)
                    menu.add(0, 4, 4, R.string.remove_from_this_playlist)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            0 -> movePlaylistTrack(playlist.id, originalIndex, originalIndex - 1)
                            1 -> movePlaylistTrack(playlist.id, originalIndex, originalIndex + 1)
                            2 -> host.choosePlaylistForTrack(track)
                            3 -> host.showTrackDetails(track)
                            4 -> removeFromPlaylist(playlist.id, raw)
                        }
                        true
                    }
                    show()
                }
            }
            row.addView(more, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
            root.addView(row, matchWidth())
        }
    }

    private fun formatPlaylistDuration(totalMs: Long): String {
        val totalMinutes = (totalMs / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) context.getString(R.string.duration_hours_minutes, hours.toInt(), minutes.toInt())
        else context.getString(R.string.duration_minutes, minutes.toInt())
    }

    private fun promptRenamePlaylist(playlist: SavedPlaylist) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.playlist_name)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(playlist.name)
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.rename_playlist))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = context.getString(R.string.playlist_name_required)
                    return@setOnClickListener
                }
                if (name == playlist.name) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val duplicate = host.playlistViewModel.playlists.value.any {
                    it.id != playlist.id && it.name.equals(name, ignoreCase = true)
                }
                if (duplicate || name.equals(context.getString(R.string.liked_songs), ignoreCase = true)) {
                    input.error = context.getString(R.string.playlist_name_exists)
                    return@setOnClickListener
                }
                host.playlistViewModel.renamePlaylist(playlist, name)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    fun showPlaylistMenu(playlist: SavedPlaylist, anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 0, 0, R.string.play)
            menu.add(0, 1, 1, R.string.add_tracks)
            menu.add(0, 2, 2, R.string.share_playlist)
            menu.add(0, 5, 2, R.string.export_playlist_m3u)
            // Liked Songs is found by name, so renaming it would orphan the like button.
            if (!playlist.name.equals(context.getString(R.string.liked_songs), ignoreCase = true)) {
                menu.add(0, 4, 3, R.string.rename_playlist)
            }
            menu.add(0, 3, 4, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> host.loadPlaylistAndPlay(playlist.id)
                    1 -> chooseTracksForPlaylist(playlist.id)
                    2 -> sharePlaylistM3u(playlist)
                    5 -> exportPlaylistM3u(playlist)
                    3 -> confirmDeletePlaylist(playlist.id, playlist.name)
                    4 -> promptRenamePlaylist(playlist)
                }
                true
            }
            show()
        }
    }

    private fun chooseTracksForPlaylist(id: Long) {
        val libraryTracks = host.libraryViewModel.tracks.value
        if (libraryTracks.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.library_empty), Toast.LENGTH_SHORT).show(); return
        }
        val selectedUris = linkedSetOf<String>()
        val selectedCount = TextView(context).apply {
            text = context.getString(R.string.selected_tracks, 0)
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(0, context.dp(8), 0, context.dp(4))
        }
        val search = EditText(context).apply {
            hint = context.getString(R.string.library_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(context.dp(14), 0, context.dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, context.dp(12))
            minHeight = context.dp(48)
        }
        val trackRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val trackScroll = ScrollView(context).apply { addView(trackRows) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, matchWidth())
            addView(selectedCount, matchWidth())
            addView(trackScroll, LinearLayout.LayoutParams(MATCH, context.dp(320)))
        }
        var addButton: Button? = null
        fun renderTrackChoices(query: String) {
            trackRows.removeAllViews()
            val matches = libraryTracks.filter { track ->
                query.isBlank() || track.title.contains(query, true) ||
                        track.artist.contains(query, true) || track.album.contains(query, true)
            }
            if (matches.isEmpty()) {
                trackRows.addView(context.emptyMessage(context.getString(R.string.library_no_filter_matches)))
                return
            }
            matches.forEach { track ->
                val uri = track.uri.toString()
                val checkBox = CheckBox(context).apply {
                    text = "${track.title} — ${track.artist}"
                    isChecked = uri in selectedUris
                    minHeight = context.dp(48)
                    setTextColor(COLOR_TEXT)
                    buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedUris.add(uri) else selectedUris.remove(uri)
                        selectedCount.text = context.getString(R.string.selected_tracks, selectedUris.size)
                        addButton?.isEnabled = selectedUris.isNotEmpty()
                    }
                }
                trackRows.addView(checkBox, matchWidth())
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderTrackChoices(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        renderTrackChoices("")
        val dialog = AlertDialog.Builder(context).setTitle(context.getString(R.string.select_tracks))
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add_tracks) { _, _ ->
                try {
                    val addedCount = host.playlists.addTracks(id, selectedUris.toList())
                    host.refreshPlaylists()
                    animatePlaylistRow(id)
                    if (addedCount > 0) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.tracks_added_to_playlist, addedCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (error: Exception) {
                    host.showError(error.localizedMessage ?: context.getString(R.string.playlist_track_failed))
                }
            }.show()
        addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        addButton?.isEnabled = selectedUris.isNotEmpty()
    }

    private fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int) {
        try {
            val moved = host.playlists.moveTrack(playlistId, fromIndex, toIndex)
            if (!moved) host.showError(context.getString(R.string.playlist_track_failed))
            host.refreshPlaylists()
        } catch (error: Exception) {
            host.showError(error.localizedMessage ?: context.getString(R.string.playlist_track_failed))
        }
    }

    /** Writes the playlist to an M3U file the user picks. */
    private fun exportPlaylistM3u(playlist: SavedPlaylist) {
        host.requestM3uExport("${sanitizeFileName(playlist.name)}.m3u", playlist.id)
    }

    private fun sanitizeFileName(name: String): String =
        name.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "_").ifBlank { "playlist" }

    private fun sharePlaylistM3u(playlist: SavedPlaylist) {
        val entries = playlist.tracks.mapNotNull { raw ->
            val track = host.resolveTrack(raw) ?: return@mapNotNull null
            val label = listOf(track.artist, track.title)
                .filter(String::isNotBlank)
                .joinToString(" - ")
            "#EXTINF:${(track.durationMs / 1000).coerceAtLeast(-1)},$label\n$raw"
        }
        if (entries.isEmpty()) {
            host.showError(context.getString(R.string.playlist_no_available_tracks))
            return
        }
        val m3u = (listOf("#EXTM3U") + entries).joinToString("\n")
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "audio/x-mpegurl"
                putExtra(Intent.EXTRA_SUBJECT, "${playlist.name}.m3u")
                putExtra(Intent.EXTRA_TEXT, m3u)
            }
            context.startActivity(Intent.createChooser(share, context.getString(R.string.share_playlist)))
        } catch (error: Exception) {
            host.showError(context.getString(R.string.playlist_error, error.localizedMessage ?: ""))
        }
    }

    private fun confirmDeletePlaylist(id: Long, name: String) {
        AlertDialog.Builder(context).setMessage(context.getString(R.string.delete_playlist_confirm, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    host.playlists.deletePlaylist(id); host.refreshPlaylists()
                } catch (error: Exception) {
                    host.showError(error.localizedMessage ?: context.getString(R.string.playlist_error, ""))
                }
            }.show()
    }

    private fun removeFromPlaylist(id: Long, uri: String) {
        val playlist = runCatching { host.playlists.getPlaylists().firstOrNull { it.id == id } }.getOrNull()
        val originalIndex = playlist?.tracks?.indexOf(uri) ?: -1
        try {
            val removed = host.playlists.removeTrack(id, uri)
            if (!removed) {
                host.showError(context.getString(R.string.playlist_track_failed))
                return
            }
            host.refreshPlaylists()
            // Reversible: the store keeps an explicit position, so undo restores it in place.
            host.showSnackbar(
                context.getString(R.string.removed_from_playlist),
                context.getString(R.string.undo),
            ) {
                host.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        host.playlists.addTracks(id, listOf(uri))
                        if (originalIndex >= 0) {
                            val last = host.playlists.getPlaylists().first { it.id == id }.tracks.lastIndex
                            host.playlists.moveTrack(id, last, originalIndex)
                        }
                    } catch (error: Exception) {
                        Log.e("PlaylistsScreen", "Undo failed", error)
                    }
                    host.runOnMain { host.refreshPlaylists() }
                }
            }
        } catch (error: Exception) {
            host.showError(error.localizedMessage ?: context.getString(R.string.playlist_track_failed))
        }
    }

    override fun onPlaylistOpen(playlist: SavedPlaylist) = openPlaylist(playlist.id)

    override fun onPlaylistMenu(playlist: SavedPlaylist, anchor: View) =
        showPlaylistMenu(playlist, anchor)

    override fun onPlaylistClearSearch() = clearSearch()

    private fun animateSuccess(view: View) = view.animateStateChange()

    private fun matchWidth() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun scrollContentOf(scroll: ScrollView): LinearLayout =
        (scroll.getChildAt(0) as android.widget.FrameLayout).getChildAt(0) as LinearLayout
}
