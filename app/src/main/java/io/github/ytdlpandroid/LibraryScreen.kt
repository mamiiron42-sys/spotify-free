package io.github.ytdlpandroid

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ytdlpandroid.viewmodel.LibraryViewModel
import io.github.ytdlpandroid.viewmodel.PlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Everything the library tab needs to render, derived from `LibraryViewModel` in one pass.
 */
internal data class LibraryScreenState(
    val rows: List<LibraryRow>,
    val trackCount: Int,
    val showPlayAll: Boolean,
    val groupMode: Int,
    val sortMode: Int,
)

/**
 * The shared recent-played list as the library preview needs it. The host owns the paging
 * and the lifetime and shares the same entries with the home tab.
 */
internal data class RecentHistoryPreview(
    val entries: List<PlaybackHistoryEntry>,
    val loading: Boolean,
    val loadFailed: Boolean,
    val pageSize: Int,
    val previewCount: Int,
)

/**
 * The library screen: the pinned Liked Songs row, the embedded "From YouTube" capture
 * section, the tracks/playlists filter chips, the track grid, and the recent-played and
 * downloads grids underneath it.
 *
 * Extracted from MainActivity, which keeps the ViewModels, the store, the track grid
 * metrics shared with the home tab, the recent-played paging (shared with home), and the
 * document pickers. The section owns its whole view tree plus the view state of the pinned
 * row and the section chips, and reaches its host only for what is not library-scoped.
 */
internal class LibraryScreen(
    private val context: Context,
    private val host: Host,
) : LibraryAdapter.Callbacks {
    /** The narrow surface the library screen consumes from its host. */
    interface Host {
        val libraryViewModel: LibraryViewModel
        val playlistViewModel: PlaylistViewModel
        val lifecycleScope: LifecycleCoroutineScope

        /** Resolves a stored playlist URI through the library and the metadata cache. */
        fun resolvePlaylistTrack(raw: String): AudioTrack?

        fun showError(message: String)
        fun showTab(label: Int)
        fun playTrackList(tracks: List<AudioTrack>, index: Int)
        fun showTrackMenu(track: AudioTrack, anchor: View)
        fun showTrackDetails(track: AudioTrack)
        fun shareDownloadedFile(uri: Uri, mime: String, name: String)

        /** Reloads the library and the downloads listing after a rename or a delete. */
        fun refreshHistory()

        /** Starts ACTION_OPEN_DOCUMENT for an audio import; the host owns the request code. */
        fun launchAudioImport()

        /** The playlists screen, for the inline playlist rows and the pinned row. */
        fun openPlaylist(id: Long)
        fun showPlaylistMenu(playlist: SavedPlaylist, anchor: View)

        /** Opens the home tab's full recent-played collection. */
        fun showRecentHistoryCollection()

        /** The "From YouTube" capture section; the host owns the section and its lifetime. */
        fun attachNowPlayingSection(root: LinearLayout)

        /** Track-grid plumbing shared with the home and search tabs. */
        fun newTrackGrid(): GridLayout
        fun gridColumnCount(): Int
        fun gridCellWidthPx(): Int
        fun gridCardWidth(columns: Int): Int
        fun animateTrackCard(card: View, index: Int)
        fun addTrackCard(
            track: AudioTrack,
            queue: List<AudioTrack>,
            index: Int,
            targetGrid: GridLayout,
            playedAt: Long?,
            historyEntryId: Long?,
        )

        fun recentHistory(): RecentHistoryPreview
        fun recentHistoryTrack(entry: PlaybackHistoryEntry): AudioTrack

        /** Runs [block] on the main thread, dropping it when the host is gone. */
        fun runOnMain(block: () -> Unit)
    }

    private val libraryAdapter = LibraryAdapter(this)

    private lateinit var libraryRoot: LinearLayout
    private lateinit var libraryList: RecyclerView
    private lateinit var libraryRecentHistory: GridLayout
    private lateinit var downloadHistoryList: GridLayout
    private lateinit var libraryTrackHeading: TextView
    private lateinit var libraryPlayAllButton: Button
    private lateinit var librarySearch: EditText
    /** Container whose single child is rebuilt when the library section chip changes. */
    private lateinit var libraryContent: LinearLayout
    private var libraryLikedSongsCard: View? = null
    private var libraryLikedSongsSubtitle: TextView? = null
    private var librarySection = LIBRARY_SECTION_SONGS
    private lateinit var librarySectionChips: List<TextView>
    private lateinit var librarySortButton: ImageView

    /** Builds the screen into [scroll]; call once, from the host's screen construction. */
    fun attachTo(scroll: ScrollView) {
        libraryRoot = scrollContentOf(scroll)
        val root = libraryRoot
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            context.sectionTitle(context.getString(R.string.library_title)),
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        librarySortButton = context.iconButton(
            iconRes = R.drawable.ic_chevron_down,
            description = context.getString(R.string.library_sort_menu),
            tint = COLOR_TEXT,
            sizeDp = 40,
            insetDp = 9,
        ) { showLibrarySortMenu(librarySortButton) }
        headerRow.addView(librarySortButton, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        root.addView(headerRow, matchWidth())

        root.addView(buildLibraryLikedSongsCard(), matchWidth().apply {
            topMargin = context.dp(4)
            bottomMargin = context.dp(8)
        })
        host.attachNowPlayingSection(root)

        val libraryActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        libraryActions.addView(Button(context).apply {
            text = context.getString(R.string.nav_playlists)
            context.styleActionButton(this, false)
            setOnClickListener { host.showTab(R.string.nav_playlists) }
        }, LinearLayout.LayoutParams(0, context.dp(44), 1f).apply { marginEnd = context.dp(6) })
        libraryActions.addView(Button(context).apply {
            text = context.getString(R.string.import_audio)
            context.styleActionButton(this, true)
            setOnClickListener { host.launchAudioImport() }
        }, LinearLayout.LayoutParams(0, context.dp(44), 1f).apply { marginStart = context.dp(6) })
        root.addView(libraryActions, matchWidth().apply { topMargin = context.dp(8) })

        librarySearch = EditText(context).apply {
            hint = context.getString(R.string.library_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(context.dp(14), 0, context.dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, context.dp(14))
            minHeight = context.dp(48)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    host.libraryViewModel.setFilterQuery(s?.toString().orEmpty())
                    if (librarySection == LIBRARY_SECTION_PLAYLISTS) rebuildLibraryContent()
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(librarySearch, matchWidth().apply { topMargin = context.dp(14) })

        val chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        librarySectionChips = listOf(
            librarySectionChip(R.string.library_filter_tracks, LIBRARY_SECTION_SONGS),
            librarySectionChip(R.string.library_filter_playlists, LIBRARY_SECTION_PLAYLISTS),
        )
        librarySectionChips.forEachIndexed { index, chip ->
            chipsRow.addView(chip, LinearLayout.LayoutParams(WRAP, context.dp(36)).apply {
                if (index > 0) marginStart = context.dp(8)
            })
        }
        root.addView(chipsRow, matchWidth().apply { topMargin = context.dp(10) })

        libraryContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(libraryContent, matchWidth())
        rebuildLibraryContent()
    }

    /** True once [attachTo] has run, so the host can skip refreshes before the tree exists. */
    fun isAttached(): Boolean = ::libraryRoot.isInitialized

    /** Projects ViewModel state into the grid's render input. */
    fun stateFor(
        matches: List<AudioTrack>,
        allTracks: List<AudioTrack>,
        errors: List<String>,
        groupMode: Int,
        sortMode: Int,
    ): LibraryScreenState = LibraryScreenState(
        rows = libraryRows(matches, allTracks, errors, groupMode),
        trackCount = matches.size,
        showPlayAll = matches.isNotEmpty(),
        groupMode = groupMode,
        sortMode = sortMode,
    )

    /** Redraws the grid from a render state. */
    fun render(screen: LibraryScreenState) {
        libraryAdapter.cellWidthPx = host.gridCellWidthPx()
        libraryAdapter.submitList(screen.rows)
        libraryTrackHeading.text = context.getString(R.string.library_track_count, screen.trackCount)
        libraryPlayAllButton.visibility = if (screen.showPlayAll) View.VISIBLE else View.GONE
        updateLibraryControlLabels(screen.groupMode, screen.sortMode)
    }

    /** Redraws the recent-played preview from the shared, host-paged history list. */
    fun renderRecentHistory() {
        if (!::libraryRecentHistory.isInitialized) return
        libraryRecentHistory.removeAllViews()
        val preview = host.recentHistory()
        if (preview.entries.isEmpty()) {
            val message = when {
                preview.loadFailed -> context.getString(R.string.recent_history_load_error)
                preview.loading -> context.getString(R.string.recent_history_loading)
                else -> context.getString(R.string.recent_history_empty)
            }
            libraryRecentHistory.addView(
                context.emptyMessage(message),
                GridLayout.LayoutParams().apply {
                    width = MATCH
                    height = WRAP
                    columnSpec = GridLayout.spec(0, libraryRecentHistory.columnCount)
                },
            )
            return
        }
        val queue = preview.entries.take(preview.pageSize).map(host::recentHistoryTrack)
        preview.entries.take(preview.previewCount).forEachIndexed { index, entry ->
            host.addTrackCard(
                track = queue[index],
                queue = queue,
                index = index,
                targetGrid = libraryRecentHistory,
                playedAt = entry.playedAt,
                historyEntryId = entry.id,
            )
        }
    }

    /** Reloads the Downloads/yt-dlp listing and redraws the downloads grid. */
    fun refreshDownloads() {
        if (!::downloadHistoryList.isInitialized) return
        host.lifecycleScope.launch(Dispatchers.IO) {
            val items = mutableListOf<Triple<String, Long, Pair<String, Uri>>>()
            var failure: Exception? = null
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE,
            )
            try {
                val cursor = context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf("${Environment.DIRECTORY_DOWNLOADS}/yt-dlp/"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                ) ?: error("Downloads provider returned no cursor")
                cursor.use {
                    while (it.moveToNext()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val name = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                            ?: context.getString(R.string.unknown_track)
                        val date = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                        val mime = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                            ?: "application/octet-stream"
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        items.add(Triple(name, date, mime to uri))
                    }
                }
            } catch (caught: Exception) {
                failure = caught
            }
            host.runOnMain {
                downloadHistoryList.removeAllViews()
                if (failure != null) {
                    addHistoryEmptyMessage(context.getString(R.string.history_unavailable))
                } else if (items.isEmpty()) {
                    addHistoryEmptyMessage(context.getString(R.string.history_empty))
                } else {
                    items.forEach { (name, date, media) ->
                        addHistoryItem(name, date, media.first, media.second)
                    }
                }
            }
        }
    }

    /** Keeps the pinned Liked Songs row in step with the playlist store. */
    fun refreshPlaylistConsumers() {
        updateLikedSongsCard()
        if (librarySection == LIBRARY_SECTION_PLAYLISTS && ::libraryContent.isInitialized) {
            rebuildLibraryContent()
        }
    }

    /** Keeps the pinned card's song count in step with the playlist store. */
    private fun updateLikedSongsCard() {
        val card = libraryLikedSongsCard ?: return
        if (card.visibility != View.VISIBLE) return
        val liked = host.playlistViewModel.playlists.value.firstOrNull {
            it.name.equals(context.getString(R.string.liked_songs), ignoreCase = true)
        }
        libraryLikedSongsSubtitle?.text =
            context.getString(R.string.library_liked_songs_subtitle, liked?.tracks?.size ?: 0)
    }

    private fun librarySectionChip(labelRes: Int, section: Int) = TextView(context).apply {
        text = context.getString(labelRes)
        contentDescription = context.getString(labelRes)
        tag = section
        setOnClickListener {
            if (librarySection != section) {
                librarySection = section
                rebuildLibraryContent()
                animateSuccess(this)
            }
        }
    }

    /** Rebuilds the section body and re-applies chip selection styling. */
    private fun rebuildLibraryContent() {
        libraryContent.removeAllViews()
        if (librarySection == LIBRARY_SECTION_PLAYLISTS) {
            buildLibraryPlaylistsContent(libraryContent)
        } else {
            buildLibrarySongsContent(libraryContent)
        }
        for (chip in librarySectionChips) {
            context.stylePillChip(chip, chip.tag == librarySection)
        }
    }

    /**
     * Playlists rendered inline as Spotify-style rounded rows.
     *
     * Uses plain views rather than the playlists screen's adapter so both playlist
     * surfaces can be alive at once without sharing a RecyclerView adapter.
     */
    private fun buildLibraryPlaylistsContent(root: LinearLayout) {
        val query = librarySearch.text.toString().trim()
        val saved = host.playlistViewModel.playlists.value
            .filter { it.name.contains(query, ignoreCase = true) }
        if (saved.isEmpty()) {
            root.addView(
                context.emptyMessage(
                    if (query.isEmpty()) context.getString(R.string.playlists_empty)
                    else context.getString(R.string.playlists_no_matches),
                ),
                matchWidth().apply { topMargin = context.dp(24) },
            )
            return
        }
        saved.forEach { playlist ->
            val tracks = playlist.tracks
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground(COLOR_SURFACE, context.dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(
                    R.string.playlist_row_accessibility,
                    playlist.name,
                    tracks.size,
                )
                setOnClickListener {
                    animateSuccess(this)
                    host.openPlaylist(playlist.id)
                }
            }
            val isLikedSongs = playlist.name.equals(
                context.getString(R.string.liked_songs),
                ignoreCase = true,
            )
            val cover = FrameLayout(context).apply {
                clipToOutline = true
                background = if (isLikedSongs) {
                    likedSongsGradient()
                } else {
                    roundedBackground(COLOR_CARD, context.dp(4))
                }
            }
            val size = context.dp(56)
            if (isLikedSongs) {
                cover.addView(
                    context.iconView(R.drawable.ic_heart_filled, 22, Color.WHITE),
                    FrameLayout.LayoutParams(MATCH, MATCH),
                )
            } else {
                val resolved = tracks.firstNotNullOfOrNull { uri -> host.resolvePlaylistTrack(uri) }
                val artwork = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(android.R.drawable.ic_media_play)
                }
                resolved?.artwork?.let { artwork.setImageBitmap(it) }
                cover.addView(artwork, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            row.addView(cover, LinearLayout.LayoutParams(size, size))
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(
                TextView(context).apply {
                    text = playlist.name
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(COLOR_TEXT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            labels.addView(
                TextView(context).apply {
                    text = if (isLikedSongs) {
                        context.getString(R.string.library_liked_songs_subtitle, tracks.size)
                    } else {
                        context.getString(R.string.playlist_row_accessibility, playlist.name, tracks.size)
                    }
                    textSize = 11f
                    setTextColor(COLOR_MUTED)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = context.dp(12)
            })
            val playlistOverflow = context.iconButton(
                iconRes = R.drawable.ic_more_vert,
                description = context.getString(R.string.playlist_menu_accessibility, playlist.name),
                tint = COLOR_MUTED,
                sizeDp = 40,
                insetDp = 9,
            ) { host.showPlaylistMenu(playlist, row) }
            row.addView(playlistOverflow, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
            root.addView(row, matchWidth().apply {
                topMargin = context.dp(8)
                bottomMargin = context.dp(1)
            })
        }
    }

    /**
     * Pinned Liked Songs row. Deliberately the same shape as every other playlist row: a
     * small purple tile, two lines of text, and a play affordance. It used to be a
     * full-width gradient card, which dominated the library for no information gain.
     */
    private fun buildLibraryLikedSongsCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_SURFACE, context.dp(8))
            isClickable = true
            isFocusable = true
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
            contentDescription = context.getString(R.string.liked_songs)
            setOnClickListener {
                animateSuccess(this)
                host.showTab(R.string.nav_playlists)
            }
        }
        val tile = FrameLayout(context).apply {
            background = roundedBackground(COLOR_ACCENT_SECONDARY, context.dp(4))
        }
        tile.addView(
            context.iconView(R.drawable.ic_heart_filled, 22, Color.WHITE),
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        card.addView(tile, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(
            TextView(context).apply {
                text = context.getString(R.string.liked_songs)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        libraryLikedSongsSubtitle = TextView(context).apply {
            textSize = 12f
            setTextColor(COLOR_MUTED)
        }
        labels.addView(libraryLikedSongsSubtitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
        val playButton = context.iconButton(
            iconRes = R.drawable.ic_play,
            description = context.getString(R.string.play),
            fill = Color.WHITE,
            tint = Color.BLACK,
            sizeDp = 40,
            insetDp = 11,
        ) {
            host.lifecycleScope.launch {
                val liked = host.playlistViewModel.likedPlaylistNamed(context.getString(R.string.liked_songs))
                val tracks = liked?.tracks?.mapNotNull { host.resolvePlaylistTrack(it) }.orEmpty()
                if (tracks.isEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.liked_songs_empty),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    host.playTrackList(tracks, 0)
                }
            }
        }
        card.addView(playButton, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        libraryLikedSongsCard = card
        updateLikedSongsCard()
        return card
    }

    /** Songs section: group/sort controls, track grid, recent history and downloads. */
    private fun buildLibrarySongsContent(root: LinearLayout) {
        val trackHeading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        libraryTrackHeading = context.sectionTitle(context.getString(R.string.library_tracks))
        trackHeading.addView(libraryTrackHeading, LinearLayout.LayoutParams(0, WRAP, 1f))
        libraryPlayAllButton = Button(context).apply {
            text = context.getString(R.string.play_all)
            context.styleActionButton(this, false)
            minHeight = context.dp(40)
            visibility = View.GONE
            setOnClickListener {
                val matches = host.libraryViewModel.filteredTracks.value
                if (matches.isNotEmpty()) host.playTrackList(matches, 0)
            }
        }
        trackHeading.addView(libraryPlayAllButton, LinearLayout.LayoutParams(WRAP, context.dp(40)))
        root.addView(trackHeading, sectionSpacing())

        libraryList = RecyclerView(context).apply {
            adapter = libraryAdapter
            layoutManager = GridLayoutManager(context, host.gridColumnCount()).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        libraryAdapter.spanSizeFor(position, spanCount)
                }
            }
            // Lives inside a ScrollView, which owns scrolling.
            isNestedScrollingEnabled = false
            itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
            libraryAdapter.cellWidthPx = host.gridCellWidthPx()
        }
        root.addView(libraryList, matchWidth().apply { topMargin = context.dp(4) })
        val recentHeading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recentHeading.addView(
            context.sectionTitle(context.getString(R.string.recent_history_title)),
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        recentHeading.addView(Button(context).apply {
            text = context.getString(R.string.see_all)
            context.styleActionButton(this, false)
            minHeight = context.dp(40)
            setOnClickListener { host.showRecentHistoryCollection() }
        }, LinearLayout.LayoutParams(WRAP, context.dp(40)))
        root.addView(recentHeading, sectionSpacing())
        libraryRecentHistory = host.newTrackGrid()
        root.addView(libraryRecentHistory, matchWidth())
        root.addView(context.sectionTitle(context.getString(R.string.downloads)), sectionSpacing())
        downloadHistoryList = host.newTrackGrid()
        root.addView(downloadHistoryList, matchWidth())
    }

    /**
     * The library controls are now one icon button, so the active sort lives in its
     * content description rather than replacing the glyph.
     */
    private fun updateLibraryControlLabels(groupMode: Int, sortMode: Int) {
        if (!::librarySortButton.isInitialized) return
        val sortLabel = context.getString(
            when (sortMode) {
                LibraryViewModel.SORT_ARTIST -> R.string.library_sort_artist
                LibraryViewModel.SORT_ALBUM -> R.string.library_sort_album
                LibraryViewModel.SORT_DATE_ADDED -> R.string.library_sort_recent
                else -> R.string.library_sort_title
            },
        )
        val groupLabel = context.getString(
            when (groupMode) {
                LibraryViewModel.GROUP_ARTIST -> R.string.library_group_artist
                LibraryViewModel.GROUP_ALBUM -> R.string.library_group_album
                else -> R.string.library_group_all
            },
        )
        librarySortButton.contentDescription =
            context.getString(R.string.library_sort_menu_button, sortLabel, groupLabel)
    }

    /**
     * Single menu driving both sort and grouping. The old layout also had a full-width
     * "View: ..." pill that duplicated the sort control, which is what the library screens
     * read as an oversized box.
     */
    private fun showLibrarySortMenu(anchor: View) {
        val popup = PopupMenu(context, anchor)
        val sortSub = popup.menu.addSubMenu(
            Menu.NONE,
            LIBRARY_MENU_SORT,
            Menu.NONE,
            context.getString(R.string.library_sort_submenu),
        )
        listOf(
            LibraryViewModel.SORT_TITLE to R.string.library_sort_title,
            LibraryViewModel.SORT_ARTIST to R.string.library_sort_artist,
            LibraryViewModel.SORT_ALBUM to R.string.library_sort_album,
            LibraryViewModel.SORT_DATE_ADDED to R.string.library_sort_recent,
        ).forEach { (mode, label) ->
            sortSub.add(LIBRARY_MENU_SORT, mode, Menu.NONE, label)
        }
        val groupSub = popup.menu.addSubMenu(
            Menu.NONE,
            LIBRARY_MENU_GROUP,
            Menu.NONE,
            context.getString(R.string.library_group_submenu),
        )
        listOf(
            LibraryViewModel.GROUP_NONE to R.string.library_group_all,
            LibraryViewModel.GROUP_ARTIST to R.string.library_group_artist,
            LibraryViewModel.GROUP_ALBUM to R.string.library_group_album,
        ).forEach { (mode, label) ->
            groupSub.add(LIBRARY_MENU_GROUP, mode, Menu.NONE, label)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.groupId) {
                LIBRARY_MENU_SORT -> host.libraryViewModel.setSortMode(item.itemId)
                LIBRARY_MENU_GROUP -> host.libraryViewModel.setGroupMode(item.itemId)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    /**
     * Requirement 5.3: the library grid is a pure function of `LibraryViewModel` state, so
     * `DiffUtil` decides which cards actually changed when the filter or sort mode moves.
     */
    private fun libraryRows(
        matches: List<AudioTrack>,
        allTracks: List<AudioTrack>,
        errors: List<String>,
        groupMode: Int,
    ): List<LibraryRow> {
        val rows = ArrayList<LibraryRow>()
        if (errors.isNotEmpty()) rows += LibraryRow.Message(errors.joinToString("\n"))
        val duplicateGroups = matches.groupBy { track ->
            "${track.artist.normalizedForDuplicate()}\u0000${track.title.normalizedForDuplicate()}"
        }.values.filter { group -> group.size > 1 }
        if (duplicateGroups.isNotEmpty()) {
            rows += LibraryRow.Message(context.getString(R.string.duplicate_tracks_notice, duplicateGroups.size))
        }
        if (matches.isEmpty()) {
            rows += LibraryRow.Message(
                context.getString(
                    if (allTracks.isEmpty()) R.string.library_empty
                    else R.string.library_no_filter_matches,
                ),
            )
            return rows
        }
        when (groupMode) {
            LibraryViewModel.GROUP_ARTIST, LibraryViewModel.GROUP_ALBUM -> {
                matches.groupBy { libraryGroupName(it, groupMode) }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .forEach { (name, members) ->
                        rows += LibraryRow.Heading("$name  ·  ${members.size}")
                        members.forEach { track -> rows += LibraryRow.Track(track, name) }
                    }
            }

            else -> matches.forEach { track -> rows += LibraryRow.Track(track, null) }
        }
        return rows
    }

    /** The group heading [track] falls under for [groupMode]. */
    private fun libraryGroupName(track: AudioTrack, groupMode: Int): String = when (groupMode) {
        LibraryViewModel.GROUP_ARTIST -> track.artist.ifBlank { context.getString(R.string.unknown_artist) }
        LibraryViewModel.GROUP_ALBUM -> track.album.ifBlank { context.getString(R.string.unknown_album) }
        else -> ""
    }

    private fun addHistoryEmptyMessage(message: String) {
        downloadHistoryList.addView(
            context.emptyMessage(message),
            GridLayout.LayoutParams().apply {
                width = MATCH
                height = WRAP
                columnSpec = GridLayout.spec(0, downloadHistoryList.columnCount)
            },
        )
    }

    private fun addHistoryItem(name: String, dateSeconds: Long, mime: String, uri: Uri) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, context.dp(12))
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        }
        card.addView(TextView(context).apply {
            text = context.getString(R.string.history_item, name, DateUtils.getRelativeTimeSpanString(dateSeconds * 1000))
            textSize = 14f
            setTextColor(COLOR_TEXT)
            setPadding(0, context.dp(4), 0, context.dp(8))
            setOnClickListener { openDownloadedFile(uri, mime) }
        }, matchWidth())
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            queueActionButton(context.getString(R.string.rename)) { promptRenameDownload(uri, name) },
            LinearLayout.LayoutParams(0, context.dp(42), 1f).apply { marginEnd = context.dp(5) })
        actions.addView(
            queueActionButton(context.getString(R.string.share)) { host.shareDownloadedFile(uri, mime, name) },
            LinearLayout.LayoutParams(0, context.dp(42), 1f).apply { marginEnd = context.dp(5) })
        actions.addView(
            queueActionButton(context.getString(R.string.delete)) { confirmDeleteDownload(uri, name) },
            LinearLayout.LayoutParams(0, context.dp(42), 1f)
        )
        card.addView(actions, matchWidth())
        val columns = downloadHistoryList.columnCount.coerceAtLeast(1)
        val position = downloadHistoryList.childCount
        downloadHistoryList.addView(
            card,
            GridLayout.LayoutParams(
                GridLayout.spec(position / columns),
                GridLayout.spec(position % columns),
            ).apply {
                width = host.gridCardWidth(columns)
                height = WRAP
                setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(8))
            },
        )
        host.animateTrackCard(card, position)
    }

    private fun queueActionButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        context.styleActionButton(this, false)
        setOnClickListener { action() }
    }

    private fun openDownloadedFile(uri: Uri, mime: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: android.content.ActivityNotFoundException) {
            host.showError(context.getString(R.string.no_file_handler))
        } catch (error: SecurityException) {
            host.showError(
                context.getString(R.string.file_action_error, error.localizedMessage ?: "permission denied"),
            )
        }
    }

    private fun promptRenameDownload(uri: Uri, oldName: String) {
        val input = EditText(context).apply { setText(oldName); selectAll() }
        AlertDialog.Builder(context).setTitle(context.getString(R.string.rename_download)).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName.contains('/') || newName.contains('\\')) {
                    host.showError(context.getString(R.string.invalid_download_name))
                    return@setPositiveButton
                }
                try {
                    val changed = context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) },
                        null,
                        null,
                    )
                    if (changed <= 0) host.showError(context.getString(R.string.file_action_failed))
                    host.refreshHistory()
                } catch (error: Exception) {
                    host.showError(
                        context.getString(
                            R.string.file_action_error,
                            error.localizedMessage ?: error.javaClass.simpleName
                        )
                    )
                }
            }.show()
    }

    private fun confirmDeleteDownload(uri: Uri, name: String) {
        AlertDialog.Builder(context).setMessage(context.getString(R.string.delete_download_confirm, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    if (context.contentResolver.delete(uri, null, null) <= 0) {
                        host.showError(context.getString(R.string.file_action_failed))
                    }
                    host.refreshHistory()
                } catch (error: Exception) {
                    host.showError(
                        context.getString(
                            R.string.file_action_error,
                            error.localizedMessage ?: error.javaClass.simpleName
                        )
                    )
                }
            }.show()
    }

    override fun onLibraryTrackPlay(track: AudioTrack, groupKey: String?) {
        val matches = host.libraryViewModel.filteredTracks.value
        val playList = if (groupKey == null) {
            matches
        } else {
            val groupMode = host.libraryViewModel.groupMode.value
            matches.filter { libraryGroupName(it, groupMode) == groupKey }
        }
        val index = playList.indexOfFirst { it.uri.toString() == track.uri.toString() }
        if (index >= 0) host.playTrackList(playList, index)
    }

    override fun onLibraryTrackDetails(track: AudioTrack) = host.showTrackDetails(track)

    override fun onLibraryTrackMenu(track: AudioTrack, anchor: View) =
        host.showTrackMenu(track, anchor)

    private fun animateSuccess(view: View) = view.animateStateChange()

    /** Folds case and punctuation so near-identical titles collide in the duplicate check. */
    private fun String.normalizedForDuplicate(): String =
        lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun matchWidth() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun sectionSpacing() = matchWidth().apply { topMargin = context.dp(20) }

    private fun scrollContentOf(scroll: ScrollView): LinearLayout =
        (scroll.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout

    private companion object {
        const val LIBRARY_SECTION_SONGS = 0
        const val LIBRARY_SECTION_PLAYLISTS = 1

        const val LIBRARY_MENU_SORT = 1

        const val LIBRARY_MENU_GROUP = 2
    }
}
