package io.github.ytdlpandroid

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ytdlpandroid.viewmodel.LibraryViewModel

/**
 * The two constants the home screen and the activity both need, kept here rather than in
 * `UiKit` because they are only about this app's screens: the widest the content column may
 * get, so every tab derives its grid from the same width, and the tab transition duration
 * the home rows animate with.
 */
internal const val MAX_CONTENT_WIDTH_DP = 760
internal const val TAB_TRANSITION_MS = 180L

/**
 * The home tab: the greeting, the discovery hero, the embedded "From YouTube" capture
 * section, the category and subcategory chips, and the collection rendered underneath them.
 *
 * Extracted from MainActivity, which keeps the ViewModels, the playlist store, the
 * recent-played paging it shares with the library tab, the track-grid metrics shared with
 * the library and search tabs, and the download-settings card. The screen owns its whole
 * view tree plus the view state of the category chips, and reaches its host only for what
 * is not home-scoped.
 */
internal class HomeScreen(
    private val context: Context,
    private val host: Host,
) {
    /** The narrow surface the home screen consumes from its host. */
    interface Host {
        val libraryViewModel: LibraryViewModel

        /** Shared with the activity, which owns the store and its lifetime. */
        val playlists: PlaylistStore

        /** Resolves a stored playlist URI through the library and the metadata cache. */
        fun resolvePlaylistTrack(raw: String): AudioTrack?

        fun playTrackList(tracks: List<AudioTrack>, index: Int)
        fun loadPlaylistAndPlay(id: Long)

        /** Opens a playlist in the playlists screen. */
        fun openPlaylist(id: Long)

        /** [historyEntryId] is non-null for a recent-played card, which can be removed. */
        fun showTrackMenu(track: AudioTrack, anchor: View, historyEntryId: Long?)
        fun showTrackDetails(track: AudioTrack)

        fun showTab(label: Int)

        /** Runs a search for [query], as the Discover category does. */
        fun search(query: String)

        fun promptCreatePlaylist()
        fun launchPlaylistImport()

        /** Starts ACTION_OPEN_DOCUMENT for an audio import; the host owns the request code. */
        fun launchAudioImport()

        /** The download-settings disclosure below the home list; the host owns the card. */
        fun isDownloadSettingsExpanded(): Boolean
        fun setDownloadSettingsExpanded(expanded: Boolean)

        /** The shared recent-played paging, read-only: the host owns the pages and the lifetime. */
        fun recentHistory(): RecentHistoryPreview
        fun recentHistoryTrack(entry: PlaybackHistoryEntry): AudioTrack

        /** True while another page of the shared history is still unread. */
        fun recentHistoryHasMore(): Boolean
        fun clearPlaybackHistory()
        fun loadMorePlaybackHistory()

        /** Track-grid metrics shared with the library tab. */
        fun gridColumnCount(): Int
        fun animateCard(card: View, index: Int)

        /** The "From YouTube" capture section; the host owns the section and its position. */
        fun attachNowPlayingSection(root: LinearLayout)
    }

    private lateinit var homeCategories: LinearLayout
    private lateinit var homeSubcategories: LinearLayout
    private lateinit var homeLibraryContent: LinearLayout
    private lateinit var homeMusicGrid: GridLayout
    private lateinit var downloadSettingsToggle: TextView
    private var homeCategory = HOME_CATEGORY_MUSIC
    private var homeSubcategory = 0
    private var selectedHomeGroup: String? = null

    /** Builds the screen into [scroll]; call once, from the host's screen construction. */
    fun attachTo(scroll: ScrollView) {
        val root = scrollContentOf(scroll)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greetingText = when (hour) {
            in 5..11 -> context.getString(R.string.home_greeting_morning)
            in 12..17 -> context.getString(R.string.home_greeting_afternoon)
            else -> context.getString(R.string.home_greeting_evening)
        }
        root.addView(TextView(context).apply {
            text = greetingText
            textSize = 26f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 0, context.dp(10))
        })
        root.addView(buildDiscoveryHero(), matchWidth().apply { bottomMargin = context.dp(12) })
        host.attachNowPlayingSection(root)
        val categoryItems = listOf(
            HOME_CATEGORY_MUSIC to R.string.home_category_all,
            HOME_CATEGORY_RECENT_PLAYED to R.string.home_category_recent_played,
            HOME_CATEGORY_ARTISTS to R.string.home_category_artists,
            HOME_CATEGORY_ALBUMS to R.string.home_category_albums,
            HOME_CATEGORY_PLAYLISTS to R.string.home_category_playlists,
            HOME_CATEGORY_DISCOVER to R.string.home_category_discover,
        )
        homeCategories = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        categoryItems.forEach { (category, title) ->
            homeCategories.addView(TextView(context).apply {
                text = context.getString(title)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(context.dp(14), context.dp(9), context.dp(14), context.dp(9))
                isClickable = true
                isFocusable = true
                minHeight = context.dp(48)
                contentDescription =
                    context.getString(R.string.home_category_accessibility, context.getString(title))
                if (category == HOME_CATEGORY_DISCOVER) {
                    setTextColor(COLOR_ACCENT_WARM)
                }
                setOnClickListener {
                    homeCategory = category
                    homeSubcategory = 0
                    selectedHomeGroup = null
                    animateSuccess(this)
                    renderHomeSubcategories()
                    showHomeCollection()
                    renderHomeLibrary()
                }
                tag = category
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = context.dp(8) })
        }
        val categoryScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(homeCategories)
        }
        root.addView(categoryScroll, matchWidth().apply { topMargin = context.dp(14) })

        homeSubcategories = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val subcategoryScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(homeSubcategories)
        }
        root.addView(subcategoryScroll, matchWidth().apply { topMargin = context.dp(8) })
        renderHomeSubcategories()

        homeLibraryContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(homeLibraryContent, matchWidth().apply { topMargin = context.dp(8) })

        downloadSettingsToggle = TextView(context).apply {
            text = context.getString(R.string.download_settings_summary)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = context.dp(52)
            setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(4))
            background = roundedBackground(COLOR_SURFACE, context.dp(16))
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.download_settings_accessibility)
            setOnClickListener {
                val expanded = !host.isDownloadSettingsExpanded()
                host.setDownloadSettingsExpanded(expanded)
                text = context.getString(
                    if (expanded) R.string.download_settings_expanded else R.string.download_settings_summary,
                )
                contentDescription = context.getString(
                    if (expanded) R.string.download_settings_collapse
                    else R.string.download_settings_accessibility,
                )
                animateSuccess(this)
            }
        }
        root.addView(downloadSettingsToggle, sectionSpacing())
        renderHomeLibrary()
    }

    /** Redraws the collection from the current category, subcategory, and group state. */
    fun render() = renderHomeLibrary()

    /** True while the home list shows the playlists category, which mirrors playlist data. */
    fun isShowingPlaylists(): Boolean = homeCategory == HOME_CATEGORY_PLAYLISTS

    /** Opens the full recent-played collection, as the library preview's "See all" does. */
    fun showRecentHistoryCollection() {
        homeCategory = HOME_CATEGORY_RECENT_PLAYED
        homeSubcategory = 0
        selectedHomeGroup = null
        renderHomeSubcategories()
        showHomeCollection()
        renderHomeLibrary()
    }

    /**
     * Re-renders the home list only while it shows the recent-played collection, so a page
     * of the shared history never rebuilds another category's rows.
     */
    fun renderRecentHistory() {
        if (!::homeLibraryContent.isInitialized) return
        if (homeCategory != HOME_CATEGORY_RECENT_PLAYED) return
        renderHomeLibrary()
    }

    /** The home track grid; the library tab builds its grids from the same factory. */
    fun newTrackGrid(): GridLayout = newHomeMusicGrid()

    /** Per-cell card width of the home grid, shared with the library tab. */
    fun gridCardWidth(columns: Int): Int = homeGridCardWidth(columns)

    /** Home track card, also used by the library tab's recent-played and downloads grids. */
    fun addTrackCard(
        track: AudioTrack,
        queue: List<AudioTrack>,
        index: Int,
        targetGrid: GridLayout,
        playedAt: Long?,
        historyEntryId: Long?,
    ) = addHomeTrackCard(track, queue, index, targetGrid, playedAt, historyEntryId)

    private fun showHomeCollection() {
        homeLibraryContent.visibility = View.VISIBLE
        animateHomeContent()
    }

    private fun updateHomeCategoryStyles() {
        for (index in 0 until homeCategories.childCount) {
            val chip = homeCategories.getChildAt(index) as TextView
            val selected = chip.tag == homeCategory
            chip.isSelected = selected
            context.stylePillChip(chip, selected)
        }
        for (index in 0 until homeSubcategories.childCount) {
            val chip = homeSubcategories.getChildAt(index) as TextView
            val selected = chip.tag == homeSubcategory
            chip.isSelected = selected
            context.stylePillChip(chip, selected)
        }
    }

    private fun homeSubcategoryLabels(category: Int): List<Int> = when (category) {
        HOME_CATEGORY_RECENT_PLAYED -> emptyList()
        HOME_CATEGORY_MUSIC -> listOf(
            R.string.home_music_all,
            R.string.home_music_recent,
            R.string.home_music_longest,
        )

        HOME_CATEGORY_ARTISTS -> listOf(
            R.string.home_artists_az,
            R.string.home_artists_top,
            R.string.home_artists_recent,
        )

        HOME_CATEGORY_ALBUMS -> listOf(
            R.string.home_albums_az,
            R.string.home_albums_top,
            R.string.home_albums_recent,
        )

        HOME_CATEGORY_PLAYLISTS -> listOf(
            R.string.home_playlists_mine,
            R.string.home_playlists_create,
            R.string.home_playlists_import,
        )

        else -> listOf(
            R.string.mood_pop,
            R.string.mood_chill,
            R.string.mood_focus,
        )
    }

    private fun renderHomeSubcategories() {
        if (!::homeSubcategories.isInitialized) return
        homeSubcategories.removeAllViews()
        homeSubcategoryLabels(homeCategory).forEachIndexed { index, label ->
            homeSubcategories.addView(TextView(context).apply {
                text = context.getString(label)
                gravity = Gravity.CENTER
                setPadding(context.dp(14), context.dp(9), context.dp(14), context.dp(9))
                textSize = 14f
                minHeight = context.dp(48)
                isClickable = true
                isFocusable = true
                tag = index
                contentDescription =
                    context.getString(R.string.home_category_accessibility, context.getString(label))
                setOnClickListener {
                    homeSubcategory = index
                    animateSuccess(this)
                    updateHomeCategoryStyles()
                    if (homeCategory == HOME_CATEGORY_DISCOVER) {
                        host.showTab(R.string.nav_search)
                        host.search(context.getString(label))
                    } else {
                        showHomeCollection()
                        renderHomeLibrary()
                    }
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = context.dp(8) })
        }
    }

    private fun homeGridCardWidth(columns: Int): Int {
        val containerWidth = minOf(
            context.dp(MAX_CONTENT_WIDTH_DP),
            (context.windowWidthPx() - context.dp(24)).coerceAtLeast(0),
        )
        return (containerWidth - context.dp(36) - context.dp(8) * (columns - 1))
            .coerceAtLeast(context.dp(columns * 68)) / columns
    }

    private fun addHomeTrackCard(
        track: AudioTrack,
        queue: List<AudioTrack>,
        index: Int,
        targetGrid: GridLayout = homeMusicGrid,
        playedAt: Long? = null,
        historyEntryId: Long? = null,
    ) {
        val columns = host.gridColumnCount()
        val cellIndex = targetGrid.childCount
        val cardWidth = homeGridCardWidth(columns)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, context.dp(14))
            setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(8))
            isClickable = true
            isFocusable = true
            contentDescription =
                context.getString(R.string.home_track_accessibility, track.title, track.artist)
            setOnClickListener {
                animateSuccess(this)
                host.playTrackList(queue, index)
            }
            setOnLongClickListener {
                host.showTrackDetails(track)
                true
            }
        }
        card.addView(
            context.artworkView(track, cardWidth - context.dp(14)),
            LinearLayout.LayoutParams(MATCH, cardWidth - context.dp(14)),
        )
        card.addView(TextView(context).apply {
            text = track.title
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(context.dp(2), context.dp(8), context.dp(2), 0)
        }, LinearLayout.LayoutParams(MATCH, context.dp(45)))
        if (playedAt != null) {
            card.addView(TextView(context).apply {
                text = context.getString(
                    R.string.recent_played_at,
                    DateUtils.getRelativeTimeSpanString(playedAt),
                )
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_MUTED)
                setPadding(context.dp(2), context.dp(2), context.dp(2), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val artistLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artistLine.addView(TextView(context).apply {
            text = track.artist
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val overflowButton = context.iconButton(
            iconRes = R.drawable.ic_more_vert,
            description = context.getString(R.string.track_actions_accessibility, track.title),
            tint = COLOR_MUTED,
            sizeDp = 48,
        ) { host.showTrackMenu(track, card, historyEntryId) }
        artistLine.addView(
            overflowButton,
            LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { marginStart = context.dp(3) },
        )
        card.addView(artistLine, matchWidth())
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(8))
        }
        targetGrid.addView(card, params)
        host.animateCard(card, cellIndex)
    }

    private fun addHomeGroupCard(
        name: String,
        tracks: List<AudioTrack>,
        cellIndex: Int,
    ) {
        val columns = host.gridColumnCount()
        val cardWidth = homeGridCardWidth(columns)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, context.dp(14))
            setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(
                R.string.home_group_accessibility,
                name,
                context.getString(R.string.home_artist_count, tracks.size),
            )
            setOnClickListener {
                animateSuccess(this)
                selectedHomeGroup = name
                renderHomeLibrary()
            }
        }
        card.addView(
            context.artworkView(tracks.first(), cardWidth - context.dp(14)),
            LinearLayout.LayoutParams(MATCH, cardWidth - context.dp(14)),
        )
        card.addView(TextView(context).apply {
            text = name
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(context.dp(2), context.dp(8), context.dp(2), context.dp(2))
        }, LinearLayout.LayoutParams(MATCH, context.dp(46)))
        card.addView(TextView(context).apply {
            text = context.getString(R.string.home_artist_count, tracks.size)
            textSize = 11f
            setTextColor(COLOR_MUTED)
            setPadding(context.dp(2), 0, context.dp(2), context.dp(3))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(8))
        }
        homeMusicGrid.addView(card, params)
        host.animateCard(card, cellIndex)
    }

    private fun renderHomeRecentHistory() {
        val preview = host.recentHistory()
        val hasMore = host.recentHistoryHasMore()
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                context.sectionTitle(context.getString(R.string.recent_history_title)),
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            if (preview.entries.isNotEmpty()) addView(Button(context).apply {
                text = context.getString(R.string.clear_play_history)
                context.styleActionButton(this, false)
                setOnClickListener { host.clearPlaybackHistory() }
            }, LinearLayout.LayoutParams(WRAP, context.dp(44)))
        }
        homeLibraryContent.addView(heading, matchWidth())
        if (preview.entries.isEmpty()) {
            val message = when {
                preview.loadFailed -> context.getString(R.string.recent_history_load_error)
                preview.loading -> context.getString(R.string.recent_history_loading)
                else -> context.getString(R.string.recent_history_empty)
            }
            homeLibraryContent.addView(context.emptyMessage(message), matchWidth())
            if (preview.loadFailed) {
                homeLibraryContent.addView(Button(context).apply {
                    text = context.getString(R.string.action_retry)
                    context.styleActionButton(this, false)
                    setOnClickListener { host.loadMorePlaybackHistory() }
                }, matchWidth())
            }
            return
        }
        val tracks = preview.entries.map(host::recentHistoryTrack)
        homeMusicGrid = newHomeMusicGrid()
        preview.entries.forEachIndexed { index, entry ->
            addHomeTrackCard(
                track = tracks[index],
                queue = tracks,
                index = index,
                playedAt = entry.playedAt,
                historyEntryId = entry.id,
            )
        }
        homeLibraryContent.addView(homeMusicGrid, matchWidth())
        if (preview.loadFailed) {
            homeLibraryContent.addView(
                context.emptyMessage(context.getString(R.string.recent_history_load_error)),
                matchWidth(),
            )
            homeLibraryContent.addView(Button(context).apply {
                text = context.getString(R.string.action_retry)
                context.styleActionButton(this, false)
                setOnClickListener { host.loadMorePlaybackHistory() }
            }, matchWidth())
        } else if (hasMore || preview.loading) {
            homeLibraryContent.addView(Button(context).apply {
                text = context.getString(
                    if (preview.loading) R.string.recent_history_loading else R.string.load_older,
                )
                context.styleActionButton(this, false)
                isEnabled = !preview.loading
                setOnClickListener { host.loadMorePlaybackHistory() }
            }, matchWidth().apply { topMargin = context.dp(8) })
        }
    }

    private fun addHomePlaylistCard(playlist: SavedPlaylist, cellIndex: Int) {
        val columns = host.gridColumnCount()
        val cardWidth = homeGridCardWidth(columns)
        val coverTrack = playlist.tracks.firstNotNullOfOrNull { uri ->
            host.resolvePlaylistTrack(uri)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, context.dp(14))
            setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(
                R.string.home_playlist_accessibility,
                playlist.name,
                context.getString(R.string.home_artist_count, playlist.tracks.size),
            )
            setOnClickListener {
                animateSuccess(this)
                host.openPlaylist(playlist.id)
            }
        }
        card.addView(
            coverTrack?.let { context.artworkView(it, cardWidth - context.dp(14)) }
                ?: ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(android.R.drawable.ic_media_play)
                    background = roundedBackground(COLOR_SURFACE, context.dp(9))
                    contentDescription = context.getString(R.string.track_artwork)
                },
            LinearLayout.LayoutParams(MATCH, cardWidth - context.dp(14)),
        )
        card.addView(TextView(context).apply {
            text = playlist.name
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(context.dp(2), context.dp(8), context.dp(2), context.dp(2))
        }, LinearLayout.LayoutParams(MATCH, context.dp(46)))
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(TextView(context).apply {
            text = context.getString(R.string.home_artist_count, playlist.tracks.size)
            textSize = 11f
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(
            context.iconButton(
                iconRes = R.drawable.ic_play,
                description = context.getString(R.string.play_track_named, playlist.name),
                fill = COLOR_ACCENT,
                tint = Color.BLACK,
                sizeDp = 48,
                insetDp = 13,
            ) { host.loadPlaylistAndPlay(playlist.id) },
            LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                marginStart = context.dp(3)
            },
        )
        card.addView(actions)
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(8))
        }
        homeMusicGrid.addView(card, params)
        host.animateCard(card, cellIndex)
    }

    private fun renderHomeLibrary() {
        if (!::homeLibraryContent.isInitialized) return
        updateHomeCategoryStyles()
        homeLibraryContent.removeAllViews()
        animateHomeContent()
        if (homeCategory == HOME_CATEGORY_DISCOVER) {
            homeLibraryContent.addView(
                context.sectionTitle(context.getString(R.string.home_discover_prompt)),
                matchWidth(),
            )
            homeLibraryContent.addView(TextView(context).apply {
                text = context.getString(R.string.home_discover_hint)
                textSize = 14f
                setTextColor(COLOR_MUTED)
                setPadding(0, context.dp(6), 0, context.dp(12))
            }, matchWidth())
            homeSubcategoryLabels(HOME_CATEGORY_DISCOVER).forEachIndexed { index, label ->
                homeLibraryContent.addView(Button(context).apply {
                    text = context.getString(R.string.home_discover_search, context.getString(label))
                    context.styleActionButton(this, index == homeSubcategory)
                    setOnClickListener {
                        homeSubcategory = index
                        updateHomeCategoryStyles()
                        host.showTab(R.string.nav_search)
                        host.search(context.getString(label))
                    }
                }, matchWidth().apply { bottomMargin = context.dp(8) })
            }
            return
        }
        if (homeCategory == HOME_CATEGORY_RECENT_PLAYED) {
            renderHomeRecentHistory()
            return
        }
        if (homeCategory == HOME_CATEGORY_PLAYLISTS) {
            if (homeSubcategory == 1) {
                homeLibraryContent.addView(Button(context).apply {
                    text = context.getString(R.string.home_playlists_create_action)
                    context.styleActionButton(this, true)
                    setOnClickListener { host.promptCreatePlaylist() }
                }, matchWidth().apply { bottomMargin = context.dp(8) })
            } else if (homeSubcategory == 2) {
                homeLibraryContent.addView(Button(context).apply {
                    text = context.getString(R.string.import_playlist_m3u)
                    context.styleActionButton(this, true)
                    setOnClickListener { host.launchPlaylistImport() }
                }, matchWidth().apply { bottomMargin = context.dp(8) })
            }
            val saved = try {
                host.playlists.getPlaylists()
            } catch (error: Exception) {
                homeLibraryContent.addView(
                    context.emptyMessage(
                        context.getString(R.string.playlist_error, error.localizedMessage ?: ""),
                    ),
                )
                return
            }
            if (saved.isEmpty()) {
                homeLibraryContent.addView(
                    context.emptyMessage(
                        context.getString(
                            if (homeSubcategory == 1) R.string.home_playlists_create_empty
                            else if (homeSubcategory == 2) R.string.home_playlists_import_empty
                            else R.string.home_playlists_empty,
                        )
                    )
                )
                return
            }
            homeLibraryContent.addView(
                context.sectionTitle(context.getString(R.string.home_category_playlists)),
                matchWidth(),
            )
            homeMusicGrid = newHomeMusicGrid()
            saved.forEachIndexed { index, playlist -> addHomePlaylistCard(playlist, index) }
            homeLibraryContent.addView(homeMusicGrid, matchWidth())
            return
        }
        if (homeCategory == HOME_CATEGORY_MUSIC) {
            renderMusicShelves()
            return
        }
        val libraryTracks = host.libraryViewModel.tracks.value
        if (libraryTracks.isEmpty()) {
            homeLibraryContent.addView(buildHomeEmptyState(), matchWidth())
            return
        }
        if (homeCategory == HOME_CATEGORY_ARTISTS || homeCategory == HOME_CATEGORY_ALBUMS) {
            val categoryName = context.getString(
                if (homeCategory == HOME_CATEGORY_ARTISTS) R.string.home_category_artists
                else R.string.home_category_albums,
            )
            val grouped = groupLibraryTracks(homeCategory == HOME_CATEGORY_ARTISTS, libraryTracks)
            val sortedGroups = when (homeSubcategory) {
                1 -> grouped.entries.sortedWith(
                    compareByDescending<Map.Entry<String, List<AudioTrack>>> { it.value.size }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
                )

                2 -> grouped.entries.sortedByDescending { entry ->
                    entry.value.maxOfOrNull { it.dateAdded } ?: 0L
                }

                else -> grouped.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            }
            val selectedGroup = selectedHomeGroup
            if (selectedGroup != null) {
                homeLibraryContent.addView(Button(context).apply {
                    text = context.getString(R.string.home_group_back, categoryName.lowercase())
                    context.styleActionButton(this, false)
                    setOnClickListener {
                        selectedHomeGroup = null
                        renderHomeLibrary()
                    }
                }, matchWidth())
                homeLibraryContent.addView(
                    context.sectionTitle(selectedGroup),
                    sectionSpacing(),
                )
                homeMusicGrid = newHomeMusicGrid()
                val members = grouped[selectedGroup].orEmpty()
                members.forEachIndexed { index, track -> addHomeTrackCard(track, members, index) }
                homeLibraryContent.addView(homeMusicGrid, matchWidth())
                return
            }
            homeLibraryContent.addView(context.sectionTitle(categoryName), matchWidth())
            homeMusicGrid = newHomeMusicGrid()
            sortedGroups.forEachIndexed { index, (name, tracks) ->
                addHomeGroupCard(name, tracks, index)
            }
            homeLibraryContent.addView(homeMusicGrid, matchWidth())
            return
        }
    }

    /** Groups the library by [byArtist] or by album, as the Artists/Albums categories do. */
    private fun groupLibraryTracks(
        byArtist: Boolean,
        libraryTracks: List<AudioTrack>,
    ): Map<String, List<AudioTrack>> = libraryTracks.groupBy {
        if (byArtist) it.artist.ifBlank { context.getString(R.string.unknown_artist) }
        else it.album.ifBlank { context.getString(R.string.unknown_album) }
    }

    /**
     * The "All" category: Spotify-style shelves. Every section heading is followed by a
     * horizontal row of fixed-width cards, so the shelves read top to bottom instead of one
     * flat grid. A shelf whose source list is empty is skipped entirely, and a library with no
     * history, playlists, or tracks keeps the home empty state.
     */
    private fun renderMusicShelves() {
        val build = runCatching { buildMusicShelves() }
        build.onFailure { error ->
            // The home screen must never crash the app; degrade to the empty state instead.
            android.util.Log.e("HomeScreen", "Home shelves failed to render", error)
            if (homeLibraryContent.childCount == 0) {
                homeLibraryContent.addView(buildHomeEmptyState(), matchWidth())
            }
        }
        build.getOrNull()?.forEach { (title, cards) -> addShelf(title, cards) }
    }

    private fun buildMusicShelves(): List<Pair<String, List<ShelfCardSpec>>> {
        val libraryTracks = host.libraryViewModel.tracks.value
        val recentPreview = host.recentHistory()
        val recentTracks = recentPreview.entries.map(host::recentHistoryTrack)
        val recentCards = recentTracks.mapIndexed { index, track ->
            trackShelfCard(track, recentTracks, index)
        }
        val savedPlaylists = try {
            host.playlists.getPlaylists()
        } catch (error: Exception) {
            emptyList()
        }
        val playlistCards = savedPlaylists.map(::playlistShelfCard)
        val albumComparator =
            Comparator<Map.Entry<String, List<AudioTrack>>> { left, right ->
                String.CASE_INSENSITIVE_ORDER.compare(left.key, right.key)
            }
        val artistCards = groupLibraryTracks(true, libraryTracks)
            .entries
            .sortedWith(albumComparator)
            .map { (name, tracks) -> groupShelfCard(name, tracks, HOME_CATEGORY_ARTISTS) }
        val albumCards = groupLibraryTracks(false, libraryTracks)
            .entries
            .sortedWith(albumComparator)
            .map { (name, tracks) -> groupShelfCard(name, tracks, HOME_CATEGORY_ALBUMS) }
        val recentlyAdded = libraryTracks
            .sortedByDescending { it.dateAdded }
            .take(SHELF_CARD_LIMIT)
        val recentlyAddedCards = recentlyAdded.mapIndexed { index, track ->
            trackShelfCard(track, recentlyAdded, index)
        }
        return listOf(
            context.getString(R.string.recent_history_title) to recentCards,
            context.getString(R.string.playlists_title) to playlistCards,
            context.getString(R.string.home_category_artists) to artistCards,
            context.getString(R.string.home_category_albums) to albumCards,
            context.getString(R.string.home_recent_heading) to recentlyAddedCards,
        ).also { shelves ->
            if (shelves.none { it.second.isNotEmpty() }) {
                homeLibraryContent.addView(buildHomeEmptyState(), matchWidth())
            }
        }
    }


    /** Placeholder used when a card has no artwork to show, so it is never a blank space. */
    private fun placeholderShelfArtwork(): View = context.iconView(
        R.drawable.ic_note,
        56,
        COLOR_MUTED,
    ).apply {
        background = roundedBackground(COLOR_SURFACE, context.dp(8))
        clipToOutline = true
    }

    /** One card inside a home shelf: its artwork, both text lines, description, and click. */
    private class ShelfCardSpec(
        val artwork: View,
        val title: String,
        val subtitle: String,
        val description: String,
        val onClick: () -> Unit,
    )

    /** Builds a shelf: the bold heading above a horizontal row of [cards]. */
    private fun addShelf(title: String, cards: List<ShelfCardSpec>) {
        if (cards.isEmpty()) return
        val shelf = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        shelf.addView(
            context.sectionTitle(title),
            matchWidth().apply { topMargin = context.dp(8) },
        )
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        cards.forEach { card ->
            // A malformed card would otherwise crash the whole home screen, so drop it.
            runCatching { row.addView(buildShelfCard(card)) }
        }
        if (row.childCount == 0) return

        shelf.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, matchWidth())
        homeLibraryContent.addView(shelf, sectionSpacing())
    }

    private fun buildShelfCard(card: ShelfCardSpec): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = card.description
        setOnClickListener {
            animateSuccess(this)
            card.onClick()
        }
        layoutParams = LinearLayout.LayoutParams(context.dp(148), WRAP).apply {
            marginEnd = context.dp(12)
        }
        addView(
            card.artwork,
            LinearLayout.LayoutParams(context.dp(148), context.dp(148)),
        )
        addView(TextView(context).apply {
            text = card.title
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(context.dp(2), context.dp(8), context.dp(2), 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(TextView(context).apply {
            text = card.subtitle
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
            setPadding(context.dp(2), context.dp(2), context.dp(2), 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun trackShelfCard(
        track: AudioTrack,
        queue: List<AudioTrack>,
        index: Int,
    ): ShelfCardSpec = ShelfCardSpec(
        artwork = context.artworkView(track, context.dp(148)).apply { clipToOutline = true },
        title = track.title,
        subtitle = track.artist,
        description = context.getString(
            R.string.home_track_accessibility,
            track.title,
            track.artist,
        ),
        onClick = { host.playTrackList(queue, index) },
    )

    private fun playlistShelfCard(playlist: SavedPlaylist): ShelfCardSpec {
        val tracks = playlist.tracks.mapNotNull { uri -> host.resolvePlaylistTrack(uri) }
        val count = context.getString(R.string.home_artist_count, playlist.tracks.size)
        return ShelfCardSpec(
            artwork = context.playlistCoverView(tracks, context.dp(148)),
            title = playlist.name,
            subtitle = count,
            description = context.getString(
                R.string.home_playlist_accessibility,
                playlist.name,
                count,
            ),
            onClick = { host.openPlaylist(playlist.id) },
        )
    }

    private fun groupShelfCard(
        name: String,
        tracks: List<AudioTrack>,
        category: Int,
    ): ShelfCardSpec {
        val count = context.getString(R.string.home_artist_count, tracks.size)
        val artwork = tracks.firstOrNull()?.let { track ->
            context.artworkView(track, context.dp(148)).apply { clipToOutline = true }
        } ?: placeholderShelfArtwork()
        return ShelfCardSpec(
            artwork = artwork,
            title = name,
            subtitle = count,
            description = context.getString(R.string.home_group_accessibility, name, count),
            onClick = { openHomeGroup(category, name) },
        )
    }

    /** Opens the group's collection in its own category, as its group cards do. */
    private fun openHomeGroup(category: Int, name: String) {
        homeCategory = category
        homeSubcategory = 0
        selectedHomeGroup = name
        renderHomeSubcategories()
        updateHomeCategoryStyles()
        showHomeCollection()
        renderHomeLibrary()
    }

    private fun buildHomeEmptyState(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_CARD, context.dp(22))
        setPadding(context.dp(18), context.dp(18), context.dp(18), context.dp(18))

        val artAndCopy = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artAndCopy.addView(
            context.iconView(R.drawable.ic_note, 36, COLOR_BACKGROUND).apply {
                background = roundedBackground(COLOR_ACCENT_SECONDARY, context.dp(20))
                contentDescription = context.getString(R.string.music_collection_art)
            },
            LinearLayout.LayoutParams(context.dp(72), context.dp(72)).apply {
                marginEnd = context.dp(16)
            },
        )
        val message = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.home_empty_title)
                textSize = 19f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.home_import_prompt)
                textSize = 14f
                setTextColor(COLOR_MUTED)
                setPadding(0, context.dp(5), 0, 0)
            })
        }
        artAndCopy.addView(message, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(artAndCopy, matchWidth())

        addView(Button(context).apply {
            text = context.getString(R.string.import_audio)
            context.styleActionButton(this, true)
            setOnClickListener {
                animateSuccess(this)
                host.launchAudioImport()
            }
        }, matchWidth().apply { topMargin = context.dp(16) })
    }

    private fun newHomeMusicGrid() = GridLayout(context).apply {
        columnCount = host.gridColumnCount()
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
    }

    private fun buildDiscoveryHero(): View = FrameLayout(context).apply {
        val compact = context.resources.configuration.screenWidthDp < 380
        val artworkSize = context.dp(if (compact) 78 else 112)
        val heroGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(COLOR_HERO_START, COLOR_HERO_MIDDLE, COLOR_HERO_END),
        ).apply { cornerRadius = context.dp(24).toFloat() }
        background = heroGradient
        setPadding(
            context.dp(if (compact) 16 else 20),
            context.dp(18),
            context.dp(14),
            context.dp(18),
        )
        minimumHeight = context.dp(if (compact) 138 else 150)
        elevation = context.dp(2).toFloat()

        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(TextView(context).apply {
            text = context.getString(R.string.home_hero_title)
            textSize = if (compact) 20f else 22f
            maxLines = 2
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_HERO_TEXT)
            setPadding(0, context.dp(4), 0, context.dp(2))
        })
        copy.addView(TextView(context).apply {
            text = context.getString(R.string.home_hero_subtitle)
            textSize = if (compact) 12f else 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_HERO_TEXT.withAlpha(220))
        })
        copy.addView(Button(context).apply {
            text = context.getString(R.string.home_hero_action)
            textSize = 13f
            isAllCaps = false
            setTextColor(COLOR_BACKGROUND)
            background = roundedBackground(Color.WHITE, context.dp(14))
            minHeight = context.dp(44)
            minWidth = context.dp(if (compact) 128 else 142)
            setPadding(context.dp(12), 0, context.dp(12), 0)
            contentDescription = context.getString(R.string.home_hero_action_accessibility)
            setOnClickListener {
                homeCategory = HOME_CATEGORY_DISCOVER
                homeSubcategory = 0
                selectedHomeGroup = null
                renderHomeSubcategories()
                updateHomeCategoryStyles()
                showHomeCollection()
                renderHomeLibrary()
                animateSuccess(this)
            }
        }, LinearLayout.LayoutParams(WRAP, context.dp(46)).apply {
            topMargin = context.dp(if (compact) 8 else 12)
        })

        addView(
            copy,
            FrameLayout.LayoutParams(
                MATCH,
                WRAP,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ).apply { rightMargin = artworkSize + context.dp(12) },
        )
        addView(
            context.iconView(R.drawable.ic_note, if (compact) 62 else 88, Color.WHITE).apply {
                background = roundedBackground(Color.argb(45, 255, 255, 255), artworkSize)
                contentDescription = context.getString(R.string.home_hero_art_description)
            },
            FrameLayout.LayoutParams(artworkSize, artworkSize, Gravity.END or Gravity.CENTER_VERTICAL),
        )
    }

    private fun animateHomeContent() {
        if (!animationsEnabled() || !::homeLibraryContent.isInitialized) return
        homeLibraryContent.animate().cancel()
        homeLibraryContent.alpha = 0f
        homeLibraryContent.translationY = context.dp(7).toFloat()
        homeLibraryContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(TAB_TRANSITION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.3f))
            .start()
    }

    private fun animateSuccess(view: View) = view.animateStateChange()

    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    private fun matchWidth() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun sectionSpacing() = matchWidth().apply { topMargin = context.dp(20) }

    private fun scrollContentOf(scroll: ScrollView): LinearLayout =
        (scroll.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout

    private companion object {
        const val HOME_CATEGORY_MUSIC = 0
        const val HOME_CATEGORY_ARTISTS = 1
        const val HOME_CATEGORY_ALBUMS = 2
        const val HOME_CATEGORY_PLAYLISTS = 3
        const val HOME_CATEGORY_DISCOVER = 4
        const val HOME_CATEGORY_RECENT_PLAYED = 5

        /** How many cards a home shelf shows at most, keeping rows reachable. */
        const val SHELF_CARD_LIMIT = 12
    }
}
