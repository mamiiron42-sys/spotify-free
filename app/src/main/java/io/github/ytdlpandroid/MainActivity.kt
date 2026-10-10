package io.github.ytdlpandroid

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.content.ContentValues
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.animation.ValueAnimator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import io.github.ytdlpandroid.viewmodel.DownloadViewModel
import io.github.ytdlpandroid.viewmodel.LibraryViewModel
import io.github.ytdlpandroid.viewmodel.PlaylistViewModel
import io.github.ytdlpandroid.viewmodel.PlayerViewModel
import io.github.ytdlpandroid.viewmodel.SearchDownloadState
import io.github.ytdlpandroid.viewmodel.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

// AudioTrack is defined in AudioTrack.kt (shared with TrackRepository)

data class PlaylistImportItem(
    val uri: String,
    val metadata: SavedTrackMetadata? = null,
)

/**
 * Everything the search tab needs to render, derived from `SearchViewModel` in one pass.
 */
private data class SearchScreen(
    val rows: List<SearchRow>,
    val isSearching: Boolean,
    val showTitle: Boolean,
)

/**
 * Everything the queue tab needs to render, derived from `DownloadViewModel` in one pass.
 */
private data class QueueScreen(
    val jobs: List<DownloadJob>,
    val concurrency: Int,
    val wifiOnly: Boolean,
)

/**
 * Everything the library tab needs to render, derived from `LibraryViewModel` in one pass.
 */
private data class LibraryScreen(
    val rows: List<LibraryRow>,
    val trackCount: Int,
    val showPlayAll: Boolean,
    val groupMode: Int,
    val sortMode: Int,
)

/**
 * Everything the playlists tab needs to render, derived from `PlaylistViewModel` in one pass.
 */
private data class PlaylistScreen(
    val playlists: List<SavedPlaylist>,
    val error: String?,
)

@AndroidEntryPoint
class MainActivity :
    ComponentActivity(),
    DownloadService.Listener,
    SearchResultAdapter.Callbacks,
    DownloadJobAdapter.Callbacks,
    LibraryAdapter.Callbacks,
    PlaylistAdapter.Callbacks {
    private val searchViewModel: SearchViewModel by viewModels()
    private val downloadViewModel: DownloadViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    private val playlistViewModel: PlaylistViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

    private lateinit var content: FrameLayout
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var searchResults: RecyclerView
    private val searchAdapter = SearchResultAdapter(this)
    private val jobAdapter = DownloadJobAdapter(this)
    private val libraryAdapter = LibraryAdapter(this)
    private val playlistAdapter = PlaylistAdapter(this)
    private lateinit var searchResultsTitle: TextView
    private lateinit var searchScrollView: ScrollView
    private lateinit var homeCategories: LinearLayout
    private lateinit var homeSubcategories: LinearLayout
    private lateinit var homeLibraryContent: LinearLayout
    private lateinit var homeMusicGrid: GridLayout
    private lateinit var downloadSettingsContent: LinearLayout
    private lateinit var downloadSettingsToggle: TextView
    private var homeCategory = HOME_CATEGORY_MUSIC
    private var homeSubcategory = 0
    private var selectedHomeGroup: String? = null
    private lateinit var formatSpinner: Spinner
    private lateinit var concurrencySpinner: Spinner
    private lateinit var wifiOnlyToggle: CheckBox
    private lateinit var queueSummary: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var queueList: RecyclerView
    private lateinit var queueEmpty: TextView
    private lateinit var libraryList: RecyclerView
    private lateinit var downloadHistoryList: GridLayout
    private lateinit var libraryRecentHistory: GridLayout
    private lateinit var libraryFilterButton: Button
    private lateinit var librarySortButton: ImageView
    private lateinit var libraryTrackHeading: TextView
    private lateinit var libraryPlayAllButton: Button
    private lateinit var librarySearch: EditText
    /** Container whose single child is rebuilt when the library section chip changes. */
    private lateinit var libraryContent: LinearLayout
    private var libraryLikedSongsCard: View? = null
    private var libraryLikedSongsSubtitle: TextView? = null
    private var librarySection = LIBRARY_SECTION_SONGS
    private lateinit var librarySectionChips: List<TextView>
    private lateinit var playlistList: RecyclerView
    private lateinit var playlistSearch: EditText
    private lateinit var playlistRoot: LinearLayout
    private var selectedPlaylistId: Long? = null
    /** Tab to return to when the playlist page is closed (e.g. Home when opened from a Home card). */
    private var playlistReturnTab: Int? = null
    private val playlistDetailBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closePlaylistDetail()
        }
    }
    private lateinit var playerArtwork: ImageView
    private lateinit var playerTitle: TextView
    private lateinit var playerArtist: TextView
    private lateinit var playerPosition: TextView
    private lateinit var playerDuration: TextView
    private lateinit var playerSeek: SeekBar
    private lateinit var playButton: ImageView
    private lateinit var shuffleButton: ImageView
    private lateinit var repeatButton: ImageView
    private lateinit var playerStatus: TextView
    private lateinit var speedSpinner: Spinner
    private lateinit var sleepTimerButton: Button
    private lateinit var equalizerToggle: CheckBox
    private lateinit var equalizerLevelSeek: SeekBar
    private lateinit var nowPlayingBar: LinearLayout
    private lateinit var nowPlayingArtwork: ImageView
    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingArtist: TextView
    private lateinit var nowPlayingButton: ImageView
    private lateinit var nowPlayingNextButton: ImageView
    private lateinit var nowPlayingLikeButton: ImageView
    private lateinit var nowPlayingProgress: ProgressBar
    private lateinit var playerLikeButton: ImageView
    private lateinit var playerHeaderTitle: TextView
    private lateinit var playerHeaderSubtitle: TextView
    private var searchBrowseSection: View? = null
    private var previousTabBeforePlayer = R.string.nav_home
    private var downloadService: DownloadService? = null
    private var playbackService: PlaybackService? = null
    private var bound = false
    private var playbackBound = false
    private var destroyed = false
    private var previousJobStates = emptyMap<Long, String>()
    private var concurrencySettingReady = false
    private var wifiSettingReady = false
    private var currentTab = R.string.nav_home
    private var visibleTabIndex = -1
    private val recentHistoryEntries = mutableListOf<PlaybackHistoryEntry>()
    private var recentHistoryCursor: PlaybackHistoryEntry? = null
    private var recentHistoryGeneration = 0
    private var recentHistoryLoading = false
    private var recentHistoryHasMore = true
    private var recentHistoryLoadFailed = false
    private var seeking = false
    private var syncingPlaybackControls = false
    private var currentPlaybackTrack: AudioTrack? = null
    private var playbackSnapshot: PlaybackSnapshot? = null
    private var lastArtworkAnimationUri: String? = null
    private val playlists by lazy { PlaylistStore(this) }
    private val importedUris: Set<String>
        get() =
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).getStringSet(KEY_IMPORTED, emptySet())
                .orEmpty().toSet()
    private val formatModes by lazy { resources.getStringArray(R.array.format_modes) }
    private val formatKeys = arrayOf("best", "1080p", "720p", "480p", "audio", "audio_mp3")
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            downloadService = (binder as DownloadService.LocalBinder).service
            downloadService?.setListener(this@MainActivity)
            searchViewModel.setDownloadService(downloadService)
            downloadViewModel.setDownloadService(downloadService)
            downloadService?.resumeQueuedJobs()
            concurrencySettingReady = true
            wifiSettingReady = true
            // The ViewModel only re-emits when a setting actually differs from its default,
            // so push the freshly-read values into the widgets explicitly.
            syncQueueSettings(
                downloadViewModel.concurrentDownloads.value,
                downloadViewModel.wifiOnly.value,
            )
            refreshHistory()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
            searchViewModel.setDownloadService(null)
            downloadViewModel.setDownloadService(null)
        }
    }

    private fun addHistoryEmptyMessage(message: String) {
        downloadHistoryList.addView(
            emptyMessage(message),
            GridLayout.LayoutParams().apply {
                width = MATCH
                height = WRAP
                columnSpec = GridLayout.spec(0, downloadHistoryList.columnCount)
            },
        )
    }

    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playbackService = (binder as PlaybackService.LocalBinder).service
            playbackService?.setListener(playbackListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }

    private val playbackListener = object : PlaybackService.Listener {
        override fun onPlaybackChanged(snapshot: PlaybackSnapshot) {
            if (destroyed) return
            runOnUiThread {
                if (!destroyed) renderPlaybackSnapshot(snapshot)
            }
        }

        override fun onPlaybackHistoryChanged() {
            if (!destroyed) runOnUiThread {
                if (!destroyed) refreshPlaybackHistory()
            }
        }

        override fun onPlaybackHistoryError() {
            if (!destroyed) runOnUiThread {
                if (!destroyed) {
                    Toast.makeText(this@MainActivity, R.string.playback_history_save_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, playlistDetailBackCallback)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        window.decorView.systemUiVisibility = 0
        buildScreen()
        restoreSettings()
        observeSearch()
        observeDownloads()
        observeLibrary()
        observePlaylists()
        bound = bindService(Intent(this, DownloadService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        playbackBound =
            bindService(Intent(this, PlaybackService::class.java), playbackConnection, Context.BIND_AUTO_CREATE)
        refreshHistory()
        refreshLibrary()
        refreshPlaybackHistory()
        refreshPlaylists()
        requestMissingPermissions()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) if (::statusText.isInitialized) statusText.text = getString(R.string.notifications_disabled)
        if (requestCode == REQUEST_AUDIO_LIBRARY) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                refreshLibrary()
                requestMissingPermissions()
            } else {
                renderHomeLibrary()
            }
        }
    }

    private fun requestMissingPermissions() {
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(audioPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(audioPermission), REQUEST_AUDIO_LIBRARY)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun hasAudioLibraryPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun launchAudioImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_AUDIO)
    }

    private fun buildScreen() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        content = FrameLayout(this)
        screen.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val homeScreen = newScrollScreen()
        buildHomeScreen(homeScreen)
        content.addView(homeScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val searchScreen = newScrollScreen()
        buildSearchScreen(searchScreen)
        content.addView(searchScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val queueScreen = newScrollScreen()
        buildQueueScreen(queueScreen)
        content.addView(queueScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val libraryScreen = newScrollScreen()
        buildLibraryScreen(libraryScreen)
        content.addView(libraryScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val playlistsScreen = newScrollScreen()
        buildPlaylistsScreen(playlistsScreen)
        content.addView(playlistsScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val playerScreen = newScrollScreen()
        buildPlayerScreen(playerScreen)
        content.addView(playerScreen, FrameLayout.LayoutParams(MATCH, MATCH))

        nowPlayingBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_PLAYER_BAR, dp(8))
            elevation = dp(6).toFloat()
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { showTab(R.string.nav_player) }
        }
        val nowPlayingContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        nowPlayingArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(COLOR_SURFACE, dp(6))
            clipToOutline = true
            setImageResource(android.R.drawable.ic_media_play)
        }
        nowPlayingContent.addView(nowPlayingArtwork, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginEnd = dp(10)
        })
        val nowPlayingLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nowPlayingTitle = TextView(this).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        }
        nowPlayingArtist = TextView(this).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
        }
        nowPlayingLabels.addView(nowPlayingTitle)
        nowPlayingLabels.addView(nowPlayingArtist)
        nowPlayingContent.addView(nowPlayingLabels, LinearLayout.LayoutParams(0, WRAP, 1f))

        nowPlayingLikeButton = iconButton(
            iconRes = R.drawable.ic_heart_outline,
            description = getString(R.string.now_playing_like),
            tint = COLOR_MUTED,
            sizeDp = 44,
        ) {
            currentPlaybackTrack?.let { track ->
                lifecycleScope.launch {
                    val likedPlaylist = playlistViewModel.likedPlaylistNamed(getString(R.string.liked_songs))
                    toggleTrackLike(track, likedPlaylist)
                }
            }
        }
        nowPlayingContent.addView(
            nowPlayingLikeButton,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(2) },
        )

        nowPlayingNextButton = iconButton(
            iconRes = R.drawable.ic_skip_next,
            description = getString(R.string.next),
            tint = COLOR_MUTED,
            sizeDp = 44,
        ) { playNext() }
        nowPlayingContent.addView(
            nowPlayingNextButton,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(2) },
        )

        nowPlayingButton = iconButton(
            iconRes = R.drawable.ic_play,
            description = getString(R.string.play),
            tint = COLOR_TEXT,
            sizeDp = 44,
        ) { togglePlayback() }
        nowPlayingContent.addView(
            nowPlayingButton,
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        nowPlayingBar.addView(nowPlayingContent, matchWidth())

        nowPlayingProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(50, 50, 50))
        }
        nowPlayingBar.addView(nowPlayingProgress, LinearLayout.LayoutParams(MATCH, dp(2)))

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        listOf(
            R.string.nav_home to homeScreen,
            R.string.nav_search to searchScreen,
            R.string.nav_library to libraryScreen,
        ).forEach { (label, view) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                tag = label
                contentDescription = getString(label)
                background = null
                setOnClickListener {
                    animateSuccess(this)
                    showTab(label)
                }
            }
            val icon = iconView(
                iconRes = if (label == currentTab) navIconFilled(label) else navIconOutline(label),
                sizeDp = 25,
                tint = if (label == currentTab) COLOR_TEXT else COLOR_MUTED,
            )
            val title = TextView(this).apply {
                text = getString(label)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(3), 0, 0)
            }
            item.addView(icon)
            item.addView(title)
            // Spotify marks the active tab with a small green dot rather than a filled pill.
            val indicator = View(this).apply {
                background = roundedBackground(COLOR_ACCENT, dp(10))
                visibility = if (label == currentTab) View.VISIBLE else View.GONE
            }
            indicator.tag = "nav-indicator"
            item.addView(indicator, LinearLayout.LayoutParams(dp(4), dp(4)).apply {
                topMargin = dp(4)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            navigation.addView(item, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        screen.addView(nowPlayingBar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        screen.addView(navigation, matchWidth())
        setContentView(screen)
        showTab(currentTab)
    }

    private fun newScrollScreen() = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        val maxWidth = minOf(
            dp(MAX_CONTENT_WIDTH_DP),
            (windowWidthPx() - dp(24)).coerceAtLeast(0),
        )
        addView(FrameLayout(this@MainActivity).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val horizontalPadding = minOf(dp(18), maxWidth / 12)
                setPadding(horizontalPadding, dp(22), horizontalPadding, dp(26))
                setBackgroundColor(COLOR_BACKGROUND)
                tag = "tab-content"
            }, FrameLayout.LayoutParams(maxWidth, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    private fun scrollContent(scroll: ScrollView) =
        (scroll.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout

    private fun navIconFilled(label: Int): Int = when (label) {
        R.string.nav_search -> R.drawable.ic_search_filled
        R.string.nav_library -> R.drawable.ic_library_filled
        else -> R.drawable.ic_home_filled
    }

    private fun navIconOutline(label: Int): Int = when (label) {
        R.string.nav_search -> R.drawable.ic_search_outline
        R.string.nav_library -> R.drawable.ic_library_outline
        else -> R.drawable.ic_home_outline
    }

    private fun searchGridColumnCount(): Int {
        val availableWidthDp = (resources.configuration.screenWidthDp - 24)
            .coerceAtMost(MAX_CONTENT_WIDTH_DP)
            .coerceAtLeast(0)
        return when {
            availableWidthDp >= 680 -> 4
            availableWidthDp >= 470 -> 3
            else -> 2
        }
    }

    private fun windowWidthPx(): Int =
        resources.configuration.screenWidthDp.takeIf { it > 0 }?.let(::dp)
            ?: resources.displayMetrics.widthPixels

    /**
     * Per-cell width of the search grid, used to size card artwork. Mirrors the width the
     * previous `GridLayout` computed per card so the grid renders identically.
     */
    private fun searchCellWidthPx(): Int {
        val columns = searchGridColumnCount()
        val containerWidth = minOf(
            dp(MAX_CONTENT_WIDTH_DP),
            (windowWidthPx() - dp(24)).coerceAtLeast(0),
        )
        val contentWidth = (containerWidth - dp(36) - dp(8) * (columns - 1))
            .coerceAtLeast(dp(columns * 68))
        return contentWidth / columns
    }

    private fun showSearchResultMenu(item: MusicSearchResult, anchor: View) {
        val track = item.toAudioTrack()
        val likedName = getString(R.string.liked_songs)
        lifecycleScope.launch {
            val likedPlaylist = try {
                playlistViewModel.likedPlaylistNamed(likedName)
            } catch (error: Exception) {
                if (!destroyed) showError(getString(R.string.playlist_error, error.localizedMessage ?: ""))
                return@launch
            }
            // The anchor is a RecyclerView child; by the time the query returns it may have been
            // recycled or detached, which would make the PopupMenu crash or anchor to nothing.
            if (destroyed || !anchor.isAttachedToWindow) return@launch
            val isLiked = likedPlaylist?.tracks?.contains(item.url) == true
            android.widget.PopupMenu(this@MainActivity, anchor).apply {
                menu.add(0, 0, 0, R.string.add_to_playlist)
                menu.add(0, 1, 1, if (isLiked) R.string.remove_from_liked_songs else R.string.like)
                menu.add(0, 2, 2, R.string.share)
                menu.add(0, 3, 3, R.string.find_similar_music)
                menu.add(0, 4, 4, R.string.find_similar_artists)
                menu.add(0, 5, 5, R.string.download_track)
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        0 -> choosePlaylistForTrack(track) { saveSearchResultMetadata(item) }
                        1 -> toggleTrackLike(track, likedPlaylist) { saveSearchResultMetadata(item) }
                        2 -> shareSearchResult(item)
                        3 -> searchMusic("${item.title} ${item.uploader}")
                        4 -> searchMusic("${item.uploader} similar artists")
                        5 -> queueSearchResultDownload(item)
                    }
                    true
                }
                show()
            }
        }
    }

    private fun MusicSearchResult.toAudioTrack() = AudioTrack(
        uri = Uri.parse(url),
        title = title,
        artist = uploader,
        album = "",
        durationMs = (durationSeconds ?: 0) * 1000L,
        artwork = null,
        thumbnailUrl = thumbnailUrl,
    )

    private fun saveSearchResultMetadata(item: MusicSearchResult) {
        searchViewModel.saveSearchResultMetadata(item)
    }

    private fun resolvePlaylistTrack(raw: String): AudioTrack? {
        val canonical = MediaUris.canonicalAudio(raw)
        libraryViewModel.tracks.value.firstOrNull {
            val uri = it.uri.toString()
            uri == raw || uri == canonical
        }?.let { return it }
        val uri = Uri.parse(raw)
        if (uri.scheme !in setOf("http", "https")) return null
        val metadata = playlists.getTrackMetadata(raw) ?: return null
        return AudioTrack(
            uri = uri,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            durationMs = metadata.durationMs ?: 0L,
            artwork = null,
            thumbnailUrl = metadata.artworkPath,
        )
    }

    private fun shareSearchResult(item: MusicSearchResult) {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, item.title)
                putExtra(Intent.EXTRA_TEXT, item.url)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share_track)))
        } catch (error: Exception) {
            showError(getString(R.string.file_action_error, error.localizedMessage ?: error.javaClass.simpleName))
        }
    }

    /**
     * Requirement 17.1: the format the user picked in the download spinner must reach the
     * service, so it is read here rather than defaulted inside the ViewModel.
     */
    private fun queueSearchResultDownload(item: MusicSearchResult) {
        val inLibrary = searchViewModel.isInLibrary(item.url)
        val removed = searchViewModel.wasDownloadedButRemoved(item.url)
        if (inLibrary || removed) {
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage(
                    getString(if (inLibrary) R.string.already_downloaded_confirm else R.string.downloaded_then_removed_confirm)
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.download_again) { _, _ ->
                    saveSettings()
                    searchViewModel.queueDownload(item, formatKeys[formatSpinner.selectedItemPosition])
                }
                .show()
            return
        }
        saveSettings()
        searchViewModel.queueDownload(item, formatKeys[formatSpinner.selectedItemPosition])
    }

    override fun onSearchResultMenu(item: MusicSearchResult, anchor: View) =
        showSearchResultMenu(item, anchor)

    override fun onSearchResultDownload(item: MusicSearchResult) =
        queueSearchResultDownload(item)

    override fun onSearchDownloadResume(jobId: Long) {
        downloadService?.resumeJob(jobId)
    }

    override fun onSearchRetryPage() {
        searchViewModel.clearError()
        searchViewModel.loadNextPage()
    }

    private fun showHomeCollection() {
        homeLibraryContent.visibility = View.VISIBLE
        animateHomeContent()
    }

    private fun clearOnlineSearch() {
        searchInput.text.clear()
        searchViewModel.clearSearch()
        searchBrowseSection?.visibility = View.VISIBLE
        if (::searchResultsTitle.isInitialized) searchResultsTitle.visibility = View.GONE
    }

    private fun updateHomeCategoryStyles() {
        for (index in 0 until homeCategories.childCount) {
            val chip = homeCategories.getChildAt(index) as TextView
            val selected = chip.tag == homeCategory
            chip.isSelected = selected
            stylePillChip(chip, selected)
        }
        for (index in 0 until homeSubcategories.childCount) {
            val chip = homeSubcategories.getChildAt(index) as TextView
            val selected = chip.tag == homeSubcategory
            chip.isSelected = selected
            stylePillChip(chip, selected)
        }
    }

    private fun homeCategoryColor(category: Int): Int = when (category) {
        HOME_CATEGORY_ARTISTS -> COLOR_ACCENT_SECONDARY
        HOME_CATEGORY_ALBUMS -> COLOR_ACCENT_WARM
        HOME_CATEGORY_PLAYLISTS -> COLOR_ACCENT_BLUE
        HOME_CATEGORY_DISCOVER -> COLOR_ACCENT_PINK
        else -> COLOR_ACCENT
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
            homeSubcategories.addView(TextView(this).apply {
                text = getString(label)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                textSize = 14f
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                tag = index
                contentDescription = getString(R.string.home_category_accessibility, getString(label))
                setOnClickListener {
                    homeSubcategory = index
                    animateSuccess(this)
                    updateHomeCategoryStyles()
                    if (homeCategory == HOME_CATEGORY_DISCOVER) {
                        showTab(R.string.nav_search)
                        searchInput.setText(getString(label))
                        searchMusic(getString(label))
                    } else {
                        showHomeCollection()
                        renderHomeLibrary()
                    }
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
    }

    private fun homeGridCardWidth(columns: Int): Int {
        val containerWidth = minOf(
            dp(MAX_CONTENT_WIDTH_DP),
            (windowWidthPx() - dp(24)).coerceAtLeast(0),
        )
        return (containerWidth - dp(36) - dp(8) * (columns - 1))
            .coerceAtLeast(dp(columns * 68)) / columns
    }

    private fun addHomeTrackCard(
        track: AudioTrack,
        queue: List<AudioTrack>,
        index: Int,
        targetGrid: GridLayout = homeMusicGrid,
        playedAt: Long? = null,
        historyEntryId: Long? = null,
    ) {
        val columns = searchGridColumnCount()
        val cellIndex = targetGrid.childCount
        val cardWidth = homeGridCardWidth(columns)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, dp(14))
            setPadding(dp(7), dp(7), dp(7), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.home_track_accessibility, track.title, track.artist)
            setOnClickListener {
                animateSuccess(this)
                playTrackList(queue, index)
            }
            setOnLongClickListener {
                showTrackDetails(track)
                true
            }
        }
        card.addView(
            artworkView(track, cardWidth - dp(14)),
            LinearLayout.LayoutParams(MATCH, cardWidth - dp(14)),
        )
        card.addView(TextView(this).apply {
            text = track.title
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(dp(2), dp(8), dp(2), 0)
        }, LinearLayout.LayoutParams(MATCH, dp(45)))
        if (playedAt != null) {
            card.addView(TextView(this).apply {
                text = getString(
                    R.string.recent_played_at,
                    DateUtils.getRelativeTimeSpanString(playedAt),
                )
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_MUTED)
                setPadding(dp(2), dp(2), dp(2), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val artistLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artistLine.addView(TextView(this).apply {
            text = track.artist
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val overflowButton = iconButton(
            iconRes = R.drawable.ic_more_vert,
            description = getString(R.string.track_actions_accessibility, track.title),
            tint = COLOR_MUTED,
            sizeDp = 48,
        ) { showTrackMenu(track, card, historyEntryId) }
        artistLine.addView(overflowButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(3) })
        card.addView(artistLine, matchWidth())
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }
        targetGrid.addView(card, params)
        animateHomeCard(card, cellIndex)
    }

    private fun showTrackMenu(track: AudioTrack, anchor: View, historyEntryId: Long? = null) {
        val likedName = getString(R.string.liked_songs)
        lifecycleScope.launch {
            val likedPlaylist = try {
                playlistViewModel.likedPlaylistNamed(likedName)
            } catch (error: Exception) {
                if (!destroyed) showError(getString(R.string.playlist_error, error.localizedMessage ?: ""))
                return@launch
            }
            // The anchor is a RecyclerView child; by the time the query returns it may have been
            // recycled or detached, which would make the PopupMenu crash or anchor to nothing.
            if (destroyed || !anchor.isAttachedToWindow) return@launch
            val isLiked = likedPlaylist?.tracks?.contains(track.uri.toString()) == true
            android.widget.PopupMenu(this@MainActivity, anchor).apply {
                menu.add(0, 0, 0, R.string.add_to_playlist)
                menu.add(0, 1, 1, if (isLiked) R.string.remove_from_liked_songs else R.string.like)
                menu.add(0, 2, 2, R.string.share)
                menu.add(0, 3, 3, R.string.find_similar_music)
                menu.add(0, 4, 4, R.string.find_similar_artists)
                if (historyEntryId != null) menu.add(0, 5, 5, R.string.remove_from_play_history)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        0 -> choosePlaylistForTrack(track)
                        1 -> toggleTrackLike(track, likedPlaylist)
                        2 -> shareDownloadedFile(
                            track.uri,
                            contentResolver.getType(track.uri) ?: "audio/*",
                            track.title,
                        )

                        3 -> searchMusic("${track.title} ${track.artist}")
                        4 -> searchMusic("${track.artist} similar artists")
                        5 -> historyEntryId?.let(::removePlaybackHistoryEntry)
                    }
                    true
                }
                show()
            }
        }
    }

    private fun toggleTrackLike(
        track: AudioTrack,
        likedPlaylist: SavedPlaylist?,
        prepareForSave: () -> Unit = {},
    ) {
        try {
            if (likedPlaylist != null && track.uri.toString() in likedPlaylist.tracks) {
                check(playlists.removeTrack(likedPlaylist.id, track.uri.toString())) {
                    getString(R.string.playlist_track_failed)
                }
                Toast.makeText(this, getString(R.string.removed_from_liked_songs), Toast.LENGTH_SHORT).show()
            } else {
                prepareForSave()
                val playlistId = likedPlaylist?.id ?: playlists.createPlaylist(getString(R.string.liked_songs))
                check(playlists.addTrack(playlistId, track.uri.toString())) {
                    getString(R.string.playlist_track_failed)
                }
                Toast.makeText(this, getString(R.string.added_to_liked_songs), Toast.LENGTH_SHORT).show()
            }
            refreshPlaylists()
            updatePlaybackLikeState()
        } catch (error: Exception) {
            showError(error.localizedMessage ?: getString(R.string.playlist_track_failed))
        }
    }

    private fun updatePlaybackLikeState() {
        val track = currentPlaybackTrack ?: return
        lifecycleScope.launch {
            val likedPlaylist = runCatching {
                playlistViewModel.likedPlaylistNamed(getString(R.string.liked_songs))
            }.getOrNull()
            val isLiked = likedPlaylist?.tracks?.contains(track.uri.toString()) == true
            val likeDescription = getString(
                if (isLiked) R.string.now_playing_unlike else R.string.now_playing_like,
            )
            if (::playerLikeButton.isInitialized) {
                playerLikeButton.setImageResource(
                    if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline,
                )
                playerLikeButton.tint(if (isLiked) COLOR_ACCENT else COLOR_MUTED)
                playerLikeButton.contentDescription = likeDescription
            }
            if (::nowPlayingLikeButton.isInitialized) {
                nowPlayingLikeButton.setImageResource(
                    if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline,
                )
                nowPlayingLikeButton.tint(if (isLiked) COLOR_ACCENT else COLOR_MUTED)
                nowPlayingLikeButton.contentDescription = likeDescription
            }
        }
    }

    private fun addHomeGroupCard(
        name: String,
        tracks: List<AudioTrack>,
        cellIndex: Int,
    ) {
        val columns = searchGridColumnCount()
        val cardWidth = homeGridCardWidth(columns)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, dp(14))
            setPadding(dp(7), dp(7), dp(7), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                R.string.home_group_accessibility,
                name,
                getString(R.string.home_artist_count, tracks.size),
            )
            setOnClickListener {
                animateSuccess(this)
                selectedHomeGroup = name
                renderHomeLibrary()
            }
        }
        card.addView(
            artworkView(tracks.first(), cardWidth - dp(14)),
            LinearLayout.LayoutParams(MATCH, cardWidth - dp(14)),
        )
        card.addView(TextView(this).apply {
            text = name
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }, LinearLayout.LayoutParams(MATCH, dp(46)))
        card.addView(TextView(this).apply {
            text = getString(R.string.home_artist_count, tracks.size)
            textSize = 11f
            setTextColor(COLOR_MUTED)
            setPadding(dp(2), 0, dp(2), dp(3))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }
        homeMusicGrid.addView(card, params)
        animateHomeCard(card, cellIndex)
    }

    private fun renderHomeRecentHistory() {
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle(getString(R.string.recent_history_title)), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (recentHistoryEntries.isNotEmpty()) addView(Button(this@MainActivity).apply {
                text = getString(R.string.clear_play_history)
                styleActionButton(this, false)
                setOnClickListener { confirmClearPlaybackHistory() }
            }, LinearLayout.LayoutParams(WRAP, dp(44)))
        }
        homeLibraryContent.addView(heading, matchWidth())
        if (recentHistoryEntries.isEmpty()) {
            val message = when {
                recentHistoryLoadFailed -> getString(R.string.recent_history_load_error)
                recentHistoryLoading -> getString(R.string.recent_history_loading)
                else -> getString(R.string.recent_history_empty)
            }
            homeLibraryContent.addView(emptyMessage(message), matchWidth())
            if (recentHistoryLoadFailed) {
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.action_retry)
                    styleActionButton(this, false)
                    setOnClickListener { loadMorePlaybackHistory() }
                }, matchWidth())
            }
            return
        }
        val tracks = recentHistoryEntries.map(::toAudioTrack)
        homeMusicGrid = newHomeMusicGrid()
        recentHistoryEntries.forEachIndexed { index, entry ->
            addHomeTrackCard(
                track = tracks[index],
                queue = tracks,
                index = index,
                playedAt = entry.playedAt,
                historyEntryId = entry.id,
            )
        }
        homeLibraryContent.addView(homeMusicGrid, matchWidth())
        if (recentHistoryLoadFailed) {
            homeLibraryContent.addView(emptyMessage(getString(R.string.recent_history_load_error)), matchWidth())
            homeLibraryContent.addView(Button(this).apply {
                text = getString(R.string.action_retry)
                styleActionButton(this, false)
                setOnClickListener { loadMorePlaybackHistory() }
            }, matchWidth())
        } else if (recentHistoryHasMore || recentHistoryLoading) {
            homeLibraryContent.addView(Button(this).apply {
                text = getString(if (recentHistoryLoading) R.string.recent_history_loading else R.string.load_older)
                styleActionButton(this, false)
                isEnabled = !recentHistoryLoading
                setOnClickListener { loadMorePlaybackHistory() }
            }, matchWidth().apply { topMargin = dp(8) })
        }
    }

    private fun addHomePlaylistCard(playlist: SavedPlaylist, cellIndex: Int) {
        val columns = searchGridColumnCount()
        val cardWidth = homeGridCardWidth(columns)
        val coverTrack = playlist.tracks.firstNotNullOfOrNull { uri ->
            resolvePlaylistTrack(uri)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, dp(14))
            setPadding(dp(7), dp(7), dp(7), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                R.string.home_playlist_accessibility,
                playlist.name,
                getString(R.string.home_artist_count, playlist.tracks.size),
            )
            setOnClickListener {
                animateSuccess(this)
                openPlaylist(playlist.id)
            }
        }
        card.addView(
            coverTrack?.let { artworkView(it, cardWidth - dp(14)) } ?: ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(android.R.drawable.ic_media_play)
                background = roundedBackground(COLOR_SURFACE, dp(9))
                contentDescription = getString(R.string.track_artwork)
            },
            LinearLayout.LayoutParams(MATCH, cardWidth - dp(14)),
        )
        card.addView(TextView(this).apply {
            text = playlist.name
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }, LinearLayout.LayoutParams(MATCH, dp(46)))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(TextView(this).apply {
            text = getString(R.string.home_artist_count, playlist.tracks.size)
            textSize = 11f
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(iconButton(
            iconRes = R.drawable.ic_play,
            description = getString(R.string.play_track_named, playlist.name),
            fill = COLOR_ACCENT,
            tint = Color.BLACK,
            sizeDp = 48,
            insetDp = 13,
        ) { loadPlaylistAndPlay(playlist.id) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(3) })
        card.addView(actions)
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }
        homeMusicGrid.addView(card, params)
        animateHomeCard(card, cellIndex)
    }

    private fun renderHomeLibrary() {
        if (!::homeLibraryContent.isInitialized) return
        updateHomeCategoryStyles()
        homeLibraryContent.removeAllViews()
        animateHomeContent()
        if (homeCategory == HOME_CATEGORY_DISCOVER) {
            homeLibraryContent.addView(
                sectionTitle(getString(R.string.home_discover_prompt)),
                matchWidth(),
            )
            homeLibraryContent.addView(TextView(this).apply {
                text = getString(R.string.home_discover_hint)
                textSize = 14f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(6), 0, dp(12))
            }, matchWidth())
            homeSubcategoryLabels(HOME_CATEGORY_DISCOVER).forEachIndexed { index, label ->
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.home_discover_search, getString(label))
                    styleActionButton(this, index == homeSubcategory)
                    setOnClickListener {
                        homeSubcategory = index
                        updateHomeCategoryStyles()
                        showTab(R.string.nav_search)
                        searchInput.setText(getString(label))
                        searchMusic(getString(label))
                    }
                }, matchWidth().apply { bottomMargin = dp(8) })
            }
            return
        }
        if (homeCategory == HOME_CATEGORY_RECENT_PLAYED) {
            renderHomeRecentHistory()
            return
        }
        if (homeCategory == HOME_CATEGORY_PLAYLISTS) {
            if (homeSubcategory == 1) {
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.home_playlists_create_action)
                    styleActionButton(this, true)
                    setOnClickListener { promptCreatePlaylist() }
                }, matchWidth().apply { bottomMargin = dp(8) })
            } else if (homeSubcategory == 2) {
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.import_playlist_m3u)
                    styleActionButton(this, true)
                    setOnClickListener { launchPlaylistImport() }
                }, matchWidth().apply { bottomMargin = dp(8) })
            }
            val saved = try {
                playlists.getPlaylists()
            } catch (error: Exception) {
                homeLibraryContent.addView(
                    emptyMessage(getString(R.string.playlist_error, error.localizedMessage ?: "")),
                )
                return
            }
            if (saved.isEmpty()) {
                homeLibraryContent.addView(
                    emptyMessage(
                        getString(
                            if (homeSubcategory == 1) R.string.home_playlists_create_empty
                            else if (homeSubcategory == 2) R.string.home_playlists_import_empty
                            else R.string.home_playlists_empty,
                        )
                    )
                )
                return
            }
            homeLibraryContent.addView(
                sectionTitle(getString(R.string.home_category_playlists)),
                matchWidth(),
            )
            homeMusicGrid = newHomeMusicGrid()
            saved.forEachIndexed { index, playlist -> addHomePlaylistCard(playlist, index) }
            homeLibraryContent.addView(homeMusicGrid, matchWidth())
            return
        }
        val libraryTracks = libraryViewModel.tracks.value
        if (libraryTracks.isEmpty()) {
            homeLibraryContent.addView(buildHomeEmptyState(), matchWidth())
            return
        }
        if (homeCategory == HOME_CATEGORY_ARTISTS || homeCategory == HOME_CATEGORY_ALBUMS) {
            val categoryName = getString(
                if (homeCategory == HOME_CATEGORY_ARTISTS) R.string.home_category_artists
                else R.string.home_category_albums,
            )
            val grouped = libraryTracks.groupBy {
                if (homeCategory == HOME_CATEGORY_ARTISTS) {
                    it.artist.ifBlank { getString(R.string.unknown_artist) }
                } else {
                    it.album.ifBlank { getString(R.string.unknown_album) }
                }
            }
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
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.home_group_back, categoryName.lowercase())
                    styleActionButton(this, false)
                    setOnClickListener {
                        selectedHomeGroup = null
                        renderHomeLibrary()
                    }
                }, matchWidth())
                homeLibraryContent.addView(sectionTitle(selectedGroup), sectionSpacing())
                homeMusicGrid = newHomeMusicGrid()
                val members = grouped[selectedGroup].orEmpty()
                members.forEachIndexed { index, track -> addHomeTrackCard(track, members, index) }
                homeLibraryContent.addView(homeMusicGrid, matchWidth())
                return
            }
            homeLibraryContent.addView(sectionTitle(categoryName), matchWidth())
            homeMusicGrid = newHomeMusicGrid()
            sortedGroups.forEachIndexed { index, (name, tracks) ->
                addHomeGroupCard(name, tracks, index)
            }
            homeLibraryContent.addView(homeMusicGrid, matchWidth())
            return
        }

        val ordered = when (homeSubcategory) {
            1 -> libraryTracks.sortedByDescending { it.dateAdded }
            2 -> libraryTracks.sortedByDescending { it.durationMs }
            else -> libraryTracks
        }
        val tracks = if (homeSubcategory == 1) ordered.take(40) else ordered
        val heading = getString(
            when {
                homeSubcategory == 1 -> R.string.home_recent_heading
                homeSubcategory == 2 -> R.string.home_music_longest
                else -> R.string.home_collection_heading
            },
        )
        if (homeSubcategory == 0 && tracks.size >= 2) {
            homeLibraryContent.addView(renderSpotifyQuickAccess(tracks), matchWidth().apply {
                bottomMargin = dp(14)
            })
        }
        homeLibraryContent.addView(
            homeSectionHeading(
                "$heading · ${getString(R.string.home_artist_count, tracks.size)}",
                getString(R.string.play),
                onAction = { if (tracks.isNotEmpty()) playTrackList(tracks, 0) },
            ),
            sectionSpacing(),
        )
        homeMusicGrid = newHomeMusicGrid()
        tracks.forEachIndexed { index, track -> addHomeTrackCard(track, tracks, index) }
        homeLibraryContent.addView(homeMusicGrid, matchWidth())
    }

    private fun renderSpotifyQuickAccess(tracks: List<AudioTrack>): View {
        val grid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val topItems = tracks.take(6)
        val containerWidth = minOf(dp(MAX_CONTENT_WIDTH_DP), (windowWidthPx() - dp(24)).coerceAtLeast(0))
        val cardWidth = (containerWidth - dp(8)) / 2
        topItems.forEachIndexed { index, track ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground(COLOR_SURFACE, dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.home_track_accessibility, track.title, track.artist)
                setOnClickListener {
                    animateSuccess(this)
                    playTrackList(tracks, index)
                }
            }
            card.addView(
                artworkView(track, dp(56)).apply {
                    (background as? GradientDrawable)?.cornerRadius = dp(6).toFloat()
                },
                LinearLayout.LayoutParams(dp(56), dp(56)),
            )
            val titleView = TextView(this).apply {
                text = track.title
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
            }
            card.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 2),
                GridLayout.spec(index % 2),
            ).apply {
                width = cardWidth
                height = dp(56)
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            grid.addView(card, params)
        }
        return grid
    }

    private fun buildHomeEmptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_CARD, dp(22))
        setPadding(dp(18), dp(18), dp(18), dp(18))

        val artAndCopy = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artAndCopy.addView(iconView(R.drawable.ic_note, 36, COLOR_BACKGROUND).apply {
            background = roundedBackground(COLOR_ACCENT_SECONDARY, dp(20))
            contentDescription = getString(R.string.music_collection_art)
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        val message = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.home_empty_title)
                textSize = 19f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.home_import_prompt)
                textSize = 14f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(5), 0, 0)
            })
        }
        artAndCopy.addView(message, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(artAndCopy, matchWidth())

        addView(Button(this@MainActivity).apply {
            text = getString(R.string.import_audio)
            styleActionButton(this, true)
            setOnClickListener {
                animateSuccess(this)
                launchAudioImport()
            }
        }, matchWidth().apply { topMargin = dp(16) })
    }

    private fun newHomeMusicGrid() = GridLayout(this).apply {
        columnCount = searchGridColumnCount()
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
    }

    private fun animateHomeCard(card: View, index: Int) {
        if (!animationsEnabled()) return
        card.alpha = 0f
        card.translationY = dp(10).toFloat()
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index * 28L).coerceAtMost(224L))
            .setDuration(SUCCESS_ANIMATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.15f))
            .start()
    }

    private fun homeSectionHeading(
        title: String,
        actionText: String,
        onAction: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(sectionTitle(title), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(Button(this@MainActivity).apply {
            text = actionText
            minWidth = dp(48)
            styleActionButton(this, false)
            setOnClickListener { onAction() }
        }, LinearLayout.LayoutParams(WRAP, dp(48)))
    }

    private fun buildHomeScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greetingText = when (hour) {
            in 5..11 -> getString(R.string.home_greeting_morning)
            in 12..17 -> getString(R.string.home_greeting_afternoon)
            else -> getString(R.string.home_greeting_evening)
        }
        root.addView(TextView(this).apply {
            text = greetingText
            textSize = 26f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(buildDiscoveryHero(), matchWidth().apply { bottomMargin = dp(12) })
        val categoryItems = listOf(
            HOME_CATEGORY_MUSIC to R.string.home_category_all,
            HOME_CATEGORY_RECENT_PLAYED to R.string.home_category_recent_played,
            HOME_CATEGORY_ARTISTS to R.string.home_category_artists,
            HOME_CATEGORY_ALBUMS to R.string.home_category_albums,
            HOME_CATEGORY_PLAYLISTS to R.string.home_category_playlists,
            HOME_CATEGORY_DISCOVER to R.string.home_category_discover,
        )
        homeCategories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoryItems.forEach { (category, title) ->
            homeCategories.addView(TextView(this).apply {
                text = getString(title)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isClickable = true
                isFocusable = true
                minHeight = dp(48)
                contentDescription = getString(R.string.home_category_accessibility, getString(title))
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
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        val categoryScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(homeCategories)
        }
        root.addView(categoryScroll, matchWidth().apply { topMargin = dp(14) })

        homeSubcategories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val subcategoryScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(homeSubcategories)
        }
        root.addView(subcategoryScroll, matchWidth().apply { topMargin = dp(8) })
        renderHomeSubcategories()

        homeLibraryContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(homeLibraryContent, matchWidth().apply { topMargin = dp(8) })

        downloadSettingsToggle = TextView(this).apply {
            text = getString(R.string.download_settings_summary)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = roundedBackground(COLOR_SURFACE, dp(16))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.download_settings_accessibility)
            setOnClickListener {
                val expanded = downloadSettingsContent.visibility != View.VISIBLE
                downloadSettingsContent.visibility = if (expanded) View.VISIBLE else View.GONE
                text = getString(
                    if (expanded) R.string.download_settings_expanded else R.string.download_settings_summary,
                )
                contentDescription = getString(
                    if (expanded) R.string.download_settings_collapse
                    else R.string.download_settings_accessibility,
                )
                animateSuccess(this)
            }
        }
        root.addView(downloadSettingsToggle, sectionSpacing())
        formatSpinner = Spinner(this).apply {
            background = roundedBackground(COLOR_SURFACE, dp(12))
            adapter =
                object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, formatModes) {
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                        (super.getView(position, convertView, parent) as TextView).apply {
                            setTextColor(COLOR_TEXT); textSize = 14f; setPadding(dp(14), dp(12), dp(14), dp(12))
                        }
                }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
        }
        statusText = TextView(this).apply {
            text = getString(R.string.ready_to_find_music); textSize = 13f
            setTextColor(COLOR_MUTED); setPadding(0, dp(6), 0, 0)
        }
        downloadSettingsContent = sectionCard().apply {
            addView(formatSpinner, matchWidth())
            addView(progressBar, matchWidth().apply { topMargin = dp(8) })
            addView(statusText, matchWidth())
            visibility = View.GONE
        }
        root.addView(downloadSettingsContent, matchWidth().apply { topMargin = dp(6) })
        renderHomeLibrary()
    }

    private fun buildSearchBrowseAll(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(sectionTitle(getString(R.string.search_browse_all)).apply {
            textSize = 20f
            setPadding(0, dp(14), 0, dp(8))
        }, matchWidth())

        val grid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val genres = listOf(
            Triple("Pop", Color.rgb(235, 30, 50), "♫"),
            Triple("Hip-Hop", Color.rgb(186, 93, 7), "⚡"),
            Triple("Rock", Color.rgb(233, 20, 41), "🎸"),
            Triple("Chill", Color.rgb(45, 70, 185), "☕"),
            Triple("Workout", Color.rgb(115, 88, 255), "🔥"),
            Triple("Focus", Color.rgb(30, 50, 100), "🎧"),
            Triple("Dance", Color.rgb(13, 115, 236), "🪩"),
            Triple("Indie", Color.rgb(96, 129, 8), "🌿"),
        )
        val containerWidth = minOf(dp(MAX_CONTENT_WIDTH_DP), (windowWidthPx() - dp(24)).coerceAtLeast(0))
        val cardWidth = (containerWidth - dp(10)) / 2
        genres.forEachIndexed { index, (name, color, icon) ->
            val card = FrameLayout(this).apply {
                background = roundedBackground(color, dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    searchInput.setText(name)
                    searchMusic(name)
                }
            }
            val title = TextView(this).apply {
                text = name
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), 0)
            }
            val iconView = TextView(this).apply {
                text = icon
                textSize = 24f
                setTextColor(Color.argb(170, 255, 255, 255))
            }
            card.addView(title, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.TOP))
            card.addView(iconView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(10)
                bottomMargin = dp(8)
            })
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 2),
                GridLayout.spec(index % 2),
            ).apply {
                width = cardWidth
                height = dp(84)
                setMargins(dp(2), dp(3), dp(2), dp(4))
            }
            grid.addView(card, params)
        }
        container.addView(grid, matchWidth())
        return container
    }

    private fun buildSearchScreen(scroll: ScrollView) {
        searchScrollView = scroll
        val root = scrollContent(scroll)
        root.addView(sectionTitle(getString(R.string.nav_search)).apply {
            textSize = 30f
            setPadding(0, 0, 0, dp(14))
        }, matchWidth())
        searchInput = EditText(this).apply {
            hint = getString(R.string.search_hint_spotify)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(8), 0, dp(10), 0)
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchMusic(); true
                } else false
            }
        }
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_SURFACE, dp(24))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val searchIcon = iconView(R.drawable.ic_search_outline, 22, COLOR_MUTED).apply {
            setPadding(dp(8), 0, 0, 0)
        }
        searchBox.addView(searchIcon, LinearLayout.LayoutParams(WRAP, MATCH))
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        val clearSearchButton = iconButton(
            iconRes = R.drawable.ic_close,
            description = getString(R.string.clear_search),
            tint = COLOR_MUTED,
            sizeDp = 44,
        ) { clearOnlineSearch() }
        clearSearchButton.visibility = View.GONE
        searchBox.addView(clearSearchButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearSearchButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                searchBrowseSection?.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        searchButton = Button(this).apply {
            text = getString(R.string.search_music)
            styleActionButton(this, true)
            setOnClickListener { searchMusic() }
        }
        searchBox.addView(searchButton, LinearLayout.LayoutParams(WRAP, dp(44)))
        root.addView(searchBox, matchWidth())

        val browseSection = buildSearchBrowseAll()
        searchBrowseSection = browseSection
        root.addView(browseSection, matchWidth().apply { topMargin = dp(8) })

        searchResultsTitle = sectionTitle(getString(R.string.search_results)).apply {
            visibility = View.GONE
        }
        root.addView(searchResultsTitle, sectionSpacing())
        searchResults = RecyclerView(this).apply {
            adapter = searchAdapter
            layoutManager = GridLayoutManager(this@MainActivity, searchGridColumnCount()).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        searchAdapter.spanSizeFor(position, spanCount)
                }
            }
            // Lives inside a ScrollView, which owns scrolling and drives pagination.
            isNestedScrollingEnabled = false
            itemAnimator = DefaultItemAnimator()
            searchAdapter.cellWidthPx = searchCellWidthPx()
        }
        root.addView(searchResults, matchWidth())
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> maybeLoadMoreSearchResults() }
    }

    private fun buildDiscoveryHero(): View = FrameLayout(this).apply {
        val compact = resources.configuration.screenWidthDp < 380
        val artworkSize = dp(if (compact) 78 else 112)
        val heroGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(COLOR_HERO_START, COLOR_HERO_MIDDLE, COLOR_HERO_END),
        ).apply { cornerRadius = dp(24).toFloat() }
        background = heroGradient
        setPadding(dp(if (compact) 16 else 20), dp(18), dp(14), dp(18))
        minimumHeight = dp(if (compact) 138 else 150)
        elevation = dp(2).toFloat()

        val copy = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(TextView(this@MainActivity).apply {
            text = getString(R.string.home_hero_title)
            textSize = if (compact) 20f else 22f
            maxLines = 2
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_HERO_TEXT)
            setPadding(0, dp(4), 0, dp(2))
        })
        copy.addView(TextView(this@MainActivity).apply {
            text = getString(R.string.home_hero_subtitle)
            textSize = if (compact) 12f else 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_HERO_TEXT.withAlpha(220))
        })
        copy.addView(Button(this@MainActivity).apply {
            text = getString(R.string.home_hero_action)
            textSize = 13f
            isAllCaps = false
            setTextColor(COLOR_BACKGROUND)
            background = roundedBackground(Color.WHITE, dp(14))
            minHeight = dp(44)
            minWidth = dp(if (compact) 128 else 142)
            setPadding(dp(12), 0, dp(12), 0)
            contentDescription = getString(R.string.home_hero_action_accessibility)
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
        }, LinearLayout.LayoutParams(WRAP, dp(46)).apply {
            topMargin = dp(if (compact) 8 else 12)
        })

        addView(copy, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            rightMargin = artworkSize + dp(12)
        })
        addView(iconView(R.drawable.ic_note, if (compact) 62 else 88, Color.WHITE).apply {
            background = roundedBackground(Color.argb(45, 255, 255, 255), artworkSize)
            contentDescription = getString(R.string.home_hero_art_description)
        }, FrameLayout.LayoutParams(artworkSize, artworkSize, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun buildQueueScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        root.addView(sectionTitle(getString(R.string.queue_recent_jobs)))
        val settingsPanel = sectionCard()
        settingsPanel.addView(TextView(this).apply {
            text = getString(R.string.concurrent_downloads_setting)
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setPadding(0, 0, 0, dp(4))
        }, matchWidth())
        concurrencySpinner = Spinner(this).apply {
            background = roundedBackground(COLOR_SURFACE, dp(12))
            adapter = object : ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                (1..MAX_CONCURRENT_DOWNLOADS).map { getString(R.string.concurrent_downloads_value, it) },
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(COLOR_TEXT)
                        textSize = 14f
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    // Spinner fires this asynchronously after a programmatic setSelection, so
                    // compare against the ViewModel rather than guarding with a timing flag.
                    if (!concurrencySettingReady) return
                    val requested = position + 1
                    if (requested == downloadViewModel.concurrentDownloads.value) return
                    downloadViewModel.setConcurrentDownloads(requested)
                }
            }
        }
        settingsPanel.addView(concurrencySpinner, matchWidth())
        wifiOnlyToggle = CheckBox(this).apply {
            text = getString(R.string.wifi_only_downloads)
            setTextColor(COLOR_TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            setOnCheckedChangeListener { _, checked ->
                if (!wifiSettingReady) return@setOnCheckedChangeListener
                if (checked == downloadViewModel.wifiOnly.value) return@setOnCheckedChangeListener
                downloadViewModel.setWifiOnly(checked)
            }
        }
        settingsPanel.addView(wifiOnlyToggle, matchWidth())
        queueSummary = emptyMessage(getString(R.string.queue_empty))
        settingsPanel.addView(queueSummary, matchWidth())
        root.addView(settingsPanel, matchWidth().apply { bottomMargin = dp(12) })
        root.addView(Button(this).apply {
            text = getString(R.string.retry_all_failed)
            styleActionButton(this, true)
            setOnClickListener { retryAllFailedJobs() }
        }, matchWidth().apply { topMargin = dp(8) })
        root.addView(Button(this).apply {
            text = getString(R.string.clear_finished); styleActionButton(this, false)
            setOnClickListener { clearFinishedJobs() }
        }, matchWidth())
        queueEmpty = emptyMessage(getString(R.string.queue_empty))
        root.addView(queueEmpty, matchWidth())
        queueList = RecyclerView(this).apply {
            adapter = jobAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            isNestedScrollingEnabled = false
            // Progress ticks arrive several times a second; a cross-fade on each one is
            // visible flicker. State changes get an explicit pop from the adapter instead.
            itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
        }
        root.addView(queueList, matchWidth())
        renderQueue(currentQueueScreen())
    }

    private fun buildLibraryScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            sectionTitle(getString(R.string.library_title)),
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        librarySortButton = iconButton(
            iconRes = R.drawable.ic_chevron_down,
            description = getString(R.string.library_sort_menu),
            tint = COLOR_TEXT,
            sizeDp = 40,
            insetDp = 9,
        ) { showLibrarySortMenu(librarySortButton) }
        headerRow.addView(librarySortButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(headerRow, matchWidth())

        root.addView(buildLibraryLikedSongsCard(), matchWidth().apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })

        val libraryActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        libraryActions.addView(Button(this).apply {
            text = getString(R.string.nav_playlists)
            styleActionButton(this, false)
            setOnClickListener { showTab(R.string.nav_playlists) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        libraryActions.addView(Button(this).apply {
            text = getString(R.string.import_audio)
            styleActionButton(this, true)
            setOnClickListener { launchAudioImport() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        root.addView(libraryActions, matchWidth().apply { topMargin = dp(8) })

        librarySearch = EditText(this).apply {
            hint = getString(R.string.library_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, dp(14))
            minHeight = dp(48)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                libraryViewModel.setFilterQuery(s?.toString().orEmpty())
                if (librarySection == LIBRARY_SECTION_PLAYLISTS) rebuildLibraryContent()
            }

                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(librarySearch, matchWidth().apply { topMargin = dp(14) })

        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        librarySectionChips = listOf(
            library_section_chip(R.string.library_filter_tracks, LIBRARY_SECTION_SONGS),
            library_section_chip(R.string.library_filter_playlists, LIBRARY_SECTION_PLAYLISTS),
        )
        librarySectionChips.forEachIndexed { index, chip ->
            chipsRow.addView(chip, LinearLayout.LayoutParams(WRAP, dp(36)).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        root.addView(chipsRow, matchWidth().apply { topMargin = dp(10) })

        libraryContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(libraryContent, matchWidth())
        rebuildLibraryContent()
    }

    private fun library_section_chip(labelRes: Int, section: Int) = TextView(this).apply {
        text = getString(labelRes)
        contentDescription = getString(labelRes)
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
            stylePillChip(chip, chip.tag == librarySection)
        }
    }

    /**
     * Playlists rendered inline as Spotify-style rounded rows.
     *
     * Uses plain views rather than [playlistList]'s adapter so both playlist
     * surfaces can be alive at once without sharing a RecyclerView adapter.
     */
    private fun buildLibraryPlaylistsContent(root: LinearLayout) {
        val query = librarySearch.text.toString().trim()
        val saved = playlistViewModel.playlists.value
            .filter { it.name.contains(query, ignoreCase = true) }
        if (saved.isEmpty()) {
            root.addView(
                emptyMessage(
                    if (query.isEmpty()) getString(R.string.playlists_empty)
                    else getString(R.string.playlists_no_matches),
                ),
                matchWidth().apply { topMargin = dp(24) },
            )
            return
        }
        saved.forEach { playlist ->
            val tracks = playlist.tracks
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground(COLOR_SURFACE, dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = getString(
                    R.string.playlist_row_accessibility,
                    playlist.name,
                    tracks.size,
                )
                setOnClickListener {
                    animateSuccess(this)
                    openPlaylist(playlist.id)
                }
            }
            val isLikedSongs = playlist.name.equals(
                getString(R.string.liked_songs),
                ignoreCase = true,
            )
            val cover = FrameLayout(this).apply {
                clipToOutline = true
                background = if (isLikedSongs) {
                    likedSongsGradient()
                } else {
                    roundedBackground(COLOR_CARD, dp(4))
                }
            }
            val size = dp(56)
            if (isLikedSongs) {
                cover.addView(
                    iconView(R.drawable.ic_heart_filled, 22, Color.WHITE),
                    FrameLayout.LayoutParams(MATCH, MATCH),
                )
            } else {
                val resolved = tracks.firstNotNullOfOrNull { uri -> resolvePlaylistTrack(uri) }
                val artwork = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(android.R.drawable.ic_media_play)
                }
                resolved?.artwork?.let { artwork.setImageBitmap(it) }
                cover.addView(artwork, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            row.addView(cover, LinearLayout.LayoutParams(size, size))
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(
                TextView(this).apply {
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
                TextView(this).apply {
                    text = if (isLikedSongs) {
                        getString(R.string.library_liked_songs_subtitle, tracks.size)
                    } else {
                        getString(R.string.playlist_row_accessibility, playlist.name, tracks.size)
                    }
                    textSize = 11f
                    setTextColor(COLOR_MUTED)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = dp(12)
            })
            val playlistOverflow = iconButton(
                iconRes = R.drawable.ic_more_vert,
                description = getString(R.string.playlist_menu_accessibility, playlist.name),
                tint = COLOR_MUTED,
                sizeDp = 40,
                insetDp = 9,
            ) { showPlaylistMenu(playlist, row) }
            row.addView(playlistOverflow, LinearLayout.LayoutParams(dp(40), dp(40)))
            root.addView(row, matchWidth().apply {
                topMargin = dp(8)
                bottomMargin = dp(1)
            })
        }
    }

    /** Spotify pinned "Liked Songs" card shown at the top of the library. */
    private fun buildLibraryLikedSongsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = likedSongsGradient(dp(8).toFloat())
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(12), dp(10), dp(12))
            contentDescription = getString(R.string.liked_songs)
            setOnClickListener {
                animateSuccess(this)
                showTab(R.string.nav_playlists)
            }
        }
        val glyph = iconView(R.drawable.ic_heart_filled, 26, Color.WHITE)
        card.addView(glyph, LinearLayout.LayoutParams(dp(44), dp(44)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(
            TextView(this).apply {
                text = getString(R.string.liked_songs)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        libraryLikedSongsSubtitle = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.argb(220, 255, 255, 255))
        }
        labels.addView(libraryLikedSongsSubtitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
        val playButton = iconButton(
            iconRes = R.drawable.ic_play,
            description = getString(R.string.play),
            fill = Color.WHITE,
            tint = Color.BLACK,
            sizeDp = 40,
            insetDp = 11,
        ) {
            lifecycleScope.launch {
                val liked = playlistViewModel.likedPlaylistNamed(getString(R.string.liked_songs))
                val tracks = liked?.tracks?.mapNotNull { resolvePlaylistTrack(it) }.orEmpty()
                if (tracks.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.liked_songs_empty),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    playTrackList(tracks, 0)
                }
            }
        }
        card.addView(playButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        libraryLikedSongsCard = card
        updateLikedSongsCard()
        return card
    }

    /** Keeps the pinned card's song count in step with the playlist store. */
    private fun updateLikedSongsCard() {
        val card = libraryLikedSongsCard ?: return
        if (card.visibility != View.VISIBLE) return
        val liked = playlistViewModel.playlists.value.firstOrNull {
            it.name.equals(getString(R.string.liked_songs), ignoreCase = true)
        }
        libraryLikedSongsSubtitle?.text =
            getString(R.string.library_liked_songs_subtitle, liked?.tracks?.size ?: 0)
    }

    /** Songs section: group/sort controls, track grid, recent history and downloads. */
    private fun buildLibrarySongsContent(root: LinearLayout) {
        val libraryViewControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        libraryFilterButton = Button(this).apply {
            styleLibraryDropdown(this)
            setOnClickListener { showLibraryGroupMenu(this) }
        }
        libraryViewControls.addView(libraryFilterButton, matchWidth())
        root.addView(libraryViewControls, matchWidth().apply { topMargin = dp(12) })
        updateLibraryControlLabels(libraryViewModel.groupMode.value, libraryViewModel.sortMode.value)

        val trackHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        libraryTrackHeading = sectionTitle(getString(R.string.library_tracks))
        trackHeading.addView(libraryTrackHeading, LinearLayout.LayoutParams(0, WRAP, 1f))
        libraryPlayAllButton = Button(this).apply {
            text = getString(R.string.play_all)
            styleActionButton(this, false)
            minHeight = dp(40)
            visibility = View.GONE
            setOnClickListener {
                val matches = libraryViewModel.filteredTracks.value
                if (matches.isNotEmpty()) playTrackList(matches, 0)
            }
        }
        trackHeading.addView(libraryPlayAllButton, LinearLayout.LayoutParams(WRAP, dp(40)))
        root.addView(trackHeading, sectionSpacing())

        libraryList = RecyclerView(this).apply {
            adapter = libraryAdapter
            layoutManager = GridLayoutManager(this@MainActivity, searchGridColumnCount()).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        libraryAdapter.spanSizeFor(position, spanCount)
                }
            }
            // Lives inside a ScrollView, which owns scrolling.
            isNestedScrollingEnabled = false
            itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
            libraryAdapter.cellWidthPx = searchCellWidthPx()
        }
        root.addView(libraryList, matchWidth().apply { topMargin = dp(4) })
        val recentHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recentHeading.addView(
            sectionTitle(getString(R.string.recent_history_title)),
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        recentHeading.addView(Button(this).apply {
            text = getString(R.string.see_all)
            styleActionButton(this, false)
            minHeight = dp(40)
            setOnClickListener { showRecentHistoryCollection() }
        }, LinearLayout.LayoutParams(WRAP, dp(40)))
        root.addView(recentHeading, sectionSpacing())
        libraryRecentHistory = newHomeMusicGrid()
        root.addView(libraryRecentHistory, matchWidth())
        root.addView(sectionTitle(getString(R.string.downloads)), sectionSpacing())
        downloadHistoryList = newHomeMusicGrid()
        root.addView(downloadHistoryList, matchWidth())
    }

    private fun styleLibraryDropdown(button: Button) {
        button.isAllCaps = false
        button.textSize = 12f
        button.gravity = Gravity.CENTER
        button.maxLines = 1
        button.ellipsize = android.text.TextUtils.TruncateAt.END
        button.setTextColor(COLOR_TEXT)
        button.background = roundedBackground(COLOR_SURFACE, dp(20))
        button.setPadding(dp(10), 0, dp(10), 0)
        button.minWidth = 0
        button.minHeight = dp(40)
        button.stateListAnimator = null
    }

    /** Circular transparent icon button, matching Spotify's header affordances. */

    private fun updateLibraryControlLabels(groupMode: Int, sortMode: Int) {
        if (!::libraryFilterButton.isInitialized || !::librarySortButton.isInitialized) return
        val filterLabel = when (groupMode) {
            LibraryViewModel.GROUP_ARTIST -> getString(R.string.library_group_artist)
            LibraryViewModel.GROUP_ALBUM -> getString(R.string.library_group_album)
            else -> getString(R.string.library_filter_all)
        }
        val sortLabel = getString(
            when (sortMode) {
                LibraryViewModel.SORT_ARTIST -> R.string.library_sort_artist
                LibraryViewModel.SORT_ALBUM -> R.string.library_sort_album
                LibraryViewModel.SORT_DATE_ADDED -> R.string.library_sort_recent
                else -> R.string.library_sort_title
            },
        )
        libraryFilterButton.text = getString(R.string.library_filter_button, filterLabel)
        // The sort control is an icon button, so the active sort lives in its
        // description rather than replacing the glyph.
        librarySortButton.contentDescription = getString(R.string.library_sort_button, sortLabel)
    }

    private fun showLibraryGroupMenu(anchor: View) {
        val options = listOf(
            LibraryViewModel.GROUP_NONE to R.string.library_filter_all,
            LibraryViewModel.GROUP_ARTIST to R.string.library_group_artist,
            LibraryViewModel.GROUP_ALBUM to R.string.library_group_album,
        )
        android.widget.PopupMenu(this, anchor).apply {
            options.forEach { (mode, label) -> menu.add(0, mode, mode, label) }
            setOnMenuItemClickListener { item ->
                libraryViewModel.setGroupMode(item.itemId)
                true
            }
            show()
        }
    }

    private fun showLibrarySortMenu(anchor: View) {
        val options = listOf(
            LibraryViewModel.SORT_TITLE to R.string.library_sort_title,
            LibraryViewModel.SORT_ARTIST to R.string.library_sort_artist,
            LibraryViewModel.SORT_ALBUM to R.string.library_sort_album,
            LibraryViewModel.SORT_DATE_ADDED to R.string.library_sort_recent,
        )
        android.widget.PopupMenu(this, anchor).apply {
            options.forEach { (mode, label) -> menu.add(0, mode, mode, label) }
            setOnMenuItemClickListener { item ->
                libraryViewModel.setSortMode(item.itemId)
                true
            }
            show()
        }
    }

    /**
     * Requirement 5.3: the library grid is a pure function of `LibraryViewModel` state, so
     * `DiffUtil` decides which cards actually changed when the filter or sort mode moves.
     */
    private fun observeLibrary() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    libraryViewModel.filteredTracks,
                    libraryViewModel.tracks,
                    libraryViewModel.libraryErrors,
                    libraryViewModel.groupMode,
                    libraryViewModel.sortMode,
                ) { matches, allTracks, errors, groupMode, sortMode ->
                    LibraryScreen(
                        rows = libraryRows(matches, allTracks, errors, groupMode),
                        trackCount = matches.size,
                        showPlayAll = matches.isNotEmpty(),
                        groupMode = groupMode,
                        sortMode = sortMode,
                    )
                }.collect(::renderLibrary)
            }
        }
    }

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
            rows += LibraryRow.Message(getString(R.string.duplicate_tracks_notice, duplicateGroups.size))
        }
        if (matches.isEmpty()) {
            rows += LibraryRow.Message(
                getString(
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
        LibraryViewModel.GROUP_ARTIST -> track.artist.ifBlank { getString(R.string.unknown_artist) }
        LibraryViewModel.GROUP_ALBUM -> track.album.ifBlank { getString(R.string.unknown_album) }
        else -> ""
    }

    private fun renderLibrary(screen: LibraryScreen) {
        libraryAdapter.cellWidthPx = searchCellWidthPx()
        libraryAdapter.submitList(screen.rows)
        libraryTrackHeading.text = getString(R.string.library_track_count, screen.trackCount)
        libraryPlayAllButton.visibility = if (screen.showPlayAll) View.VISIBLE else View.GONE
        updateLibraryControlLabels(screen.groupMode, screen.sortMode)
    }

    // ── LibraryAdapter.Callbacks ──────────────────────────────────────────────

    override fun onLibraryTrackPlay(track: AudioTrack, groupKey: String?) {
        val matches = libraryViewModel.filteredTracks.value
        val playList = if (groupKey == null) {
            matches
        } else {
            val groupMode = libraryViewModel.groupMode.value
            matches.filter { libraryGroupName(it, groupMode) == groupKey }
        }
        val index = playList.indexOfFirst { it.uri.toString() == track.uri.toString() }
        if (index >= 0) playTrackList(playList, index)
    }

    override fun onLibraryTrackDetails(track: AudioTrack) = showTrackDetails(track)

    override fun onLibraryTrackMenu(track: AudioTrack, anchor: View) =
        showTrackMenu(track, anchor)

    // ── PlaylistAdapter.Callbacks ─────────────────────────────────────────────

    override fun onPlaylistOpen(playlist: SavedPlaylist) = openPlaylist(playlist.id)

    override fun onPlaylistMenu(playlist: SavedPlaylist, anchor: View) =
        showPlaylistMenu(playlist, anchor)

    override fun onPlaylistClearSearch() {
        if (::playlistSearch.isInitialized) playlistSearch.text.clear()
    }

    private fun buildPlaylistsScreen(scroll: ScrollView) {
        playlistRoot = scrollContent(scroll)
        buildPlaylistListPage()
    }

    private fun buildPlaylistListPage() {
        val root = playlistRoot
        root.removeAllViews()
        root.setBackgroundColor(COLOR_BACKGROUND)
        root.addView(sectionTitle(getString(R.string.playlists_title)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = getString(R.string.create_playlist)
            styleActionButton(this, true)
            setOnClickListener { promptCreatePlaylist() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(Button(this).apply {
            text = getString(R.string.import_playlist_m3u)
            styleActionButton(this, false)
            setOnClickListener { launchPlaylistImport() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(actions, matchWidth())

        playlistSearch = EditText(this).apply {
            hint = getString(R.string.playlist_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, dp(14))
            minHeight = dp(48)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderPlaylists(currentPlaylistScreen())
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(playlistSearch, matchWidth().apply { topMargin = dp(12) })

        playlistList = RecyclerView(this).apply {
            adapter = playlistAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            isNestedScrollingEnabled = false
            itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
        }
        root.addView(playlistList, matchWidth().apply { topMargin = dp(8) })
        renderPlaylists(currentPlaylistScreen())
    }

    private fun launchPlaylistImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/x-mpegurl"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_M3U)
    }

    private fun buildPlayerScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        // Spotify's now-playing screen fades from an art-derived tint down to the base black.
        root.background = verticalGradient(intArrayOf(COLOR_HERO_MIDDLE, COLOR_BACKGROUND))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            iconButton(
                iconRes = R.drawable.ic_chevron_down,
                description = getString(R.string.now_playing_collapse),
                tint = COLOR_TEXT,
                sizeDp = 44,
            ) { showTab(previousTabBeforePlayer) },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        val headerCenter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        playerHeaderSubtitle = TextView(this).apply {
            text = getString(R.string.now_playing_from_library)
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        playerHeaderTitle = TextView(this).apply {
            text = getString(R.string.library_title)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        headerCenter.addView(playerHeaderSubtitle)
        headerCenter.addView(playerHeaderTitle)
        header.addView(headerCenter, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(
            iconButton(
                iconRes = R.drawable.ic_more_vert,
                description = getString(R.string.track_details),
                tint = COLOR_MUTED,
                sizeDp = 44,
            ) { currentPlaybackTrack?.let(::showTrackDetails) },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        root.addView(header, matchWidth().apply { bottomMargin = dp(8) })

        playerArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(COLOR_SURFACE, dp(8))
            clipToOutline = true
            elevation = dp(16).toFloat()
            setImageResource(R.drawable.ic_album)
            imageTintList = android.content.res.ColorStateList.valueOf(COLOR_SURFACE)
            contentDescription = getString(R.string.track_artwork)
        }
        val artworkSize = minOf(dp(340), resources.displayMetrics.widthPixels - dp(64))
        root.addView(playerArtwork, LinearLayout.LayoutParams(artworkSize, artworkSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
            bottomMargin = dp(24)
        })

        val trackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val trackLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playerTitle = TextView(this).apply {
            text = getString(R.string.nothing_playing)
            textSize = 23f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        }
        playerArtist = TextView(this).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
        }
        trackLabels.addView(playerTitle)
        trackLabels.addView(playerArtist)
        trackRow.addView(trackLabels, LinearLayout.LayoutParams(0, WRAP, 1f))

        playerLikeButton = iconButton(
            iconRes = R.drawable.ic_heart_outline,
            description = getString(R.string.now_playing_like),
            tint = COLOR_MUTED,
            sizeDp = 44,
        ) {
            currentPlaybackTrack?.let { track ->
                lifecycleScope.launch {
                    val likedPlaylist = playlistViewModel.likedPlaylistNamed(getString(R.string.liked_songs))
                    toggleTrackLike(track, likedPlaylist)
                }
            }
        }
        trackRow.addView(playerLikeButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(trackRow, matchWidth())

        playerSeek = SeekBar(this).apply {
            contentDescription = getString(R.string.seek_playback)
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_TEXT)
            thumbTintList = android.content.res.ColorStateList.valueOf(COLOR_TEXT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    seeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    seeking = false
                    playbackService?.seekTo(seekBar?.progress ?: 0)
                }
            })
        }
        root.addView(playerSeek, matchWidth().apply { topMargin = dp(18) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playerPosition = TextView(this).apply {
            text = "0:00"; textSize = 11f; setTextColor(COLOR_MUTED); letterSpacing = 0.04f
        }
        playerDuration = TextView(this).apply {
            text = "0:00"; textSize = 11f; gravity = Gravity.END; setTextColor(COLOR_MUTED); letterSpacing = 0.04f
        }
        times.addView(playerPosition, LinearLayout.LayoutParams(0, WRAP, 1f))
        times.addView(playerDuration, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(times)

        val mainControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shuffleButton = iconButton(
            iconRes = R.drawable.ic_shuffle,
            description = getString(R.string.shuffle),
            tint = COLOR_MUTED,
        ) {
            val state = playbackSnapshot
            playbackService?.setShuffle(!(state?.shuffle ?: false))
        }
        mainControls.addView(shuffleButton, LinearLayout.LayoutParams(0, dp(48), 1f))

        mainControls.addView(
            iconButton(
                iconRes = R.drawable.ic_skip_previous,
                description = getString(R.string.previous),
                tint = COLOR_TEXT,
            ) { playPrevious() },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )

        // Spotify's primary transport: white disc, black glyph.
        playButton = iconButton(
            iconRes = R.drawable.ic_play,
            description = getString(R.string.play),
            fill = Color.WHITE,
            tint = Color.BLACK,
            sizeDp = 64,
            insetDp = 18,
        ) { togglePlayback() }
        mainControls.addView(playButton, LinearLayout.LayoutParams(dp(64), dp(64)))

        mainControls.addView(
            iconButton(
                iconRes = R.drawable.ic_skip_next,
                description = getString(R.string.next),
                tint = COLOR_TEXT,
            ) { playNext() },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )

        repeatButton = iconButton(
            iconRes = R.drawable.ic_repeat,
            description = getString(R.string.repeat),
            tint = COLOR_MUTED,
        ) {
            val mode = playbackSnapshot?.repeatMode ?: 0
            playbackService?.setRepeatMode((mode + 1) % 3)
        }
        mainControls.addView(repeatButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(mainControls, matchWidth().apply { topMargin = dp(12) })

        root.addView(sectionTitle(getString(R.string.playback_options)), sectionSpacing())
        val optionsCard = sectionCard()
        speedSpinner = Spinner(this).apply {
            contentDescription = getString(R.string.playback_speed_label)
            background = roundedBackground(COLOR_SURFACE, dp(12))
            adapter = styledSpinnerAdapter(
                PLAYBACK_SPEEDS.map { getString(R.string.playback_speed, formatPlaybackSpeed(it)) },
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (!syncingPlaybackControls) playbackService?.setPlaybackSpeed(PLAYBACK_SPEEDS[position])
                }
            }
        }
        optionsCard.addView(speedSpinner, matchWidth())
        sleepTimerButton = Button(this).apply {
            text = getString(R.string.sleep_timer)
            styleActionButton(this, false)
            setOnClickListener { showSleepTimerOptions() }
        }
        optionsCard.addView(sleepTimerButton, matchWidth().apply { topMargin = dp(8) })
        equalizerToggle = CheckBox(this).apply {
            text = getString(R.string.equalizer)
            minHeight = dp(48)
            setTextColor(COLOR_TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            setOnCheckedChangeListener { _, checked ->
                if (!syncingPlaybackControls) playbackService?.setEqualizerEnabled(checked)
            }
        }
        optionsCard.addView(equalizerToggle, matchWidth())
        equalizerLevelSeek = SeekBar(this).apply {
            contentDescription = getString(R.string.equalizer_level)
            max = 100
            progress = DEFAULT_EQUALIZER_LEVEL
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) playbackService?.setEqualizerLevel(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        optionsCard.addView(equalizerLevelSeek, matchWidth())
        playerStatus = emptyMessage(getString(R.string.player_empty))
        optionsCard.addView(playerStatus, matchWidth())
        root.addView(optionsCard, matchWidth())
        root.addView(sectionTitle(getString(R.string.library_tracks)), sectionSpacing())
        val playNow = Button(this).apply {
            text = getString(R.string.play_library); styleActionButton(this, false)
            setOnClickListener { playTrackList(libraryViewModel.tracks.value, 0) }
        }
        root.addView(playNow, matchWidth())
        root.addView(Button(this).apply {
            text = getString(R.string.queue_recent_jobs)
            styleActionButton(this, false)
            setOnClickListener { showTab(R.string.nav_queue) }
        }, matchWidth().apply { topMargin = dp(8) })
    }

    private fun playerControlButton(
        label: String,
        accessibilityLabel: String,
        action: () -> Unit,
    ) = Button(this).apply {
        text = label
        contentDescription = accessibilityLabel
        styleActionButton(this, false)
        setOnClickListener {
            animateSuccess(this)
            action()
        }
    }

    private fun showTab(label: Int) {
        currentTab = label
        val index = when (label) {
            R.string.nav_home -> 0
            R.string.nav_search -> 1
            R.string.nav_queue -> 2
            R.string.nav_library -> 3
            R.string.nav_playlists -> 4
            else -> 5
        }
        for (screenIndex in 0 until content.childCount) {
            val screen = content.getChildAt(screenIndex)
            if (screenIndex == index) {
                screen.animate().cancel()
                screen.visibility = View.VISIBLE
                if (animationsEnabled() && visibleTabIndex != -1 && visibleTabIndex != index) {
                    screen.alpha = 0f
                    screen.translationY = dp(8).toFloat()
                    screen.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(TAB_TRANSITION_MS)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                        .start()
                } else {
                    screen.alpha = 1f
                    screen.translationY = 0f
                }
            } else {
                screen.animate().cancel()
                screen.visibility = View.GONE
                screen.alpha = 1f
                screen.translationY = 0f
            }
        }
        visibleTabIndex = index
        if (label != R.string.nav_player) previousTabBeforePlayer = label
        val navigation = (content.parent as LinearLayout).getChildAt(2) as LinearLayout
        for (i in 0 until navigation.childCount) {
            val item = navigation.getChildAt(i) as LinearLayout
            val itemLabel = item.tag as? Int ?: continue
            val selected = itemLabel == label
            item.isSelected = selected
            item.background = null
            for (child in 0 until item.childCount) {
                when (val childView = item.getChildAt(child)) {
                    is TextView -> childView.setTextColor(if (selected) COLOR_TEXT else COLOR_MUTED)
                    is ImageView -> {
                        childView.setImageResource(
                            if (selected) navIconFilled(itemLabel) else navIconOutline(itemLabel),
                        )
                        childView.imageTintList =
                            android.content.res.ColorStateList.valueOf(if (selected) COLOR_TEXT else COLOR_MUTED)
                    }

                    is View -> {
                        if (childView.tag == "nav-indicator") {
                            childView.visibility = if (selected) View.VISIBLE else View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value; textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(COLOR_TEXT); setPadding(0, dp(4), 0, dp(8))
    }

    private fun restoreSettings() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        formatSpinner.setSelection(preferences.getInt(KEY_FORMAT, formatModes.lastIndex).coerceIn(formatModes.indices))
    }

    private fun saveSettings() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putInt(KEY_FORMAT, formatSpinner.selectedItemPosition).apply()
    }

    private fun searchMusic(queryOverride: String? = null) {
        val query = (queryOverride ?: searchInput.text.toString()).trim()
        showTab(R.string.nav_search)
        if (query.isNotEmpty()) {
            searchInput.setText(query)
            searchBrowseSection?.visibility = View.GONE
        }
        searchViewModel.search(query)
    }

    /**
     * Requirement 5.3 / 7.2: the grid is a pure function of `SearchViewModel` state, so
     * `DiffUtil` decides which rows actually changed and `MainActivity` never iterates the
     * raw job list to work out an indicator state.
     */
    private fun observeSearch() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    searchViewModel.searchResults,
                    searchViewModel.isSearching,
                    searchViewModel.isLoadingMore,
                    searchViewModel.searchError,
                    combine(
                        searchViewModel.downloadStatusByUrl,
                        searchViewModel.libraryVideoIds,
                    ) { byUrl, libraryIds -> byUrl to libraryIds },
                ) { results, isSearching, isLoadingMore, error, (statusByUrl, libraryIds) ->
                    val rows = ArrayList<SearchRow>(results.size + 2)
                    results.forEach {
                        rows += SearchRow.Result(it, searchViewModel.downloadStateFor(it.url, statusByUrl, libraryIds))
                    }
                    when {
                        results.isNotEmpty() && error != null ->
                            // A failed page with results already on screen: keep the list and
                            // offer a retry rather than dead-ending it.
                            rows += SearchRow.Message(error, retryable = true)

                        results.isEmpty() -> rows += SearchRow.Message(
                            text = when {
                                isSearching -> getString(R.string.searching)
                                error != null -> error
                                else -> getString(R.string.search_prompt)
                            },
                            retryable = false,
                        )
                    }
                    if (isLoadingMore) rows += SearchRow.LoadingFooter
                    SearchScreen(
                        rows = rows,
                        isSearching = isSearching,
                        showTitle = results.isNotEmpty() || error != null || isSearching,
                    )
                }.collect(::renderSearch)
            }
        }
    }

    private fun renderSearch(screen: SearchScreen) {
        searchAdapter.cellWidthPx = searchCellWidthPx()
        searchAdapter.submitList(screen.rows) {
            // A completed page changes the ScrollView's content height but does not itself
            // produce a scroll callback. Re-check after RecyclerView has committed and laid out
            // the appended rows, so paging can continue whenever the viewport is still near end.
            searchResults.post { maybeLoadMoreSearchResults() }
        }
        searchResultsTitle.visibility = if (screen.showTitle) View.VISIBLE else View.GONE
        searchBrowseSection?.visibility = if (screen.showTitle || searchInput.text.isNotEmpty()) View.GONE else View.VISIBLE
        searchButton.isEnabled = !screen.isSearching
        searchButton.alpha = if (screen.isSearching) 0.65f else 1f
    }

    private fun maybeLoadMoreSearchResults() {
        if (!::searchScrollView.isInitialized ||
            searchViewModel.searchQuery.value.isEmpty() ||
            !searchViewModel.hasMoreResults.value ||
            searchViewModel.isLoadingMore.value ||
            searchViewModel.isSearching.value
        ) return

        val contentHeight = searchScrollView.getChildAt(0)?.height ?: return
        // Start fetching about two screens early so results are ready before the user reaches
        // the end, like YouTube's endless feed.
        val prefetchDistance = maxOf(dp(600), searchScrollView.height * 2)
        if (searchScrollView.scrollY + searchScrollView.height >= contentHeight - prefetchDistance) {
            searchViewModel.loadNextPage()
        }
    }

    private fun enqueueUrls(urls: List<String>, showStatus: Boolean = true) {
        val validUrls = urls.distinctBy { it.lowercase() }
        if (validUrls.isEmpty()) return
        saveSettings()
        val serviceIntent = Intent(this, DownloadService::class.java)
            .setAction(DownloadService.ACTION_ENQUEUE)
            .putExtra(DownloadService.EXTRA_URLS, org.json.JSONArray(validUrls).toString())
            .putExtra(DownloadService.EXTRA_FORMAT, formatKeys[formatSpinner.selectedItemPosition])
        startForegroundService(serviceIntent)
        if (showStatus) statusText.text = getString(R.string.downloads_queued, validUrls.size)
        animateSuccess(progressBar)
    }

    override fun onDownloadState(
        message: String,
        active: Boolean,
        paused: Boolean,
        canPause: Boolean,
        canCancel: Boolean,
        percent: Int?
    ) {
        runOnUiThread {
            progressBar.animate().cancel()
            if (active) {
                val wasHidden = progressBar.visibility != View.VISIBLE
                progressBar.visibility = View.VISIBLE
                if (wasHidden && animationsEnabled()) {
                    progressBar.alpha = 0f
                    progressBar.animate().alpha(1f).setDuration(TAB_TRANSITION_MS).start()
                } else {
                    progressBar.alpha = 1f
                }
            } else if (progressBar.visibility == View.VISIBLE && animationsEnabled()) {
                progressBar.animate()
                    .alpha(0f)
                    .setDuration(TAB_TRANSITION_MS)
                    .withEndAction {
                        progressBar.visibility = View.GONE
                        progressBar.alpha = 1f
                    }
                    .start()
            } else {
                progressBar.visibility = View.GONE
                progressBar.alpha = 1f
            }
            progressBar.isIndeterminate = active && percent == null
            if (percent != null) {
                val boundedPercent = percent.coerceIn(0, 100)
                if (animationsEnabled()) progressBar.setProgress(boundedPercent, true)
                else progressBar.progress = boundedPercent
            }
        }
    }

    override fun onQueueChanged(jobs: List<DownloadJob>) {
        runOnUiThread {
            val completed = setOf(
                DownloadQueueStore.STATE_COMPLETE, DownloadQueueStore.STATE_PARTIAL,
                DownloadQueueStore.STATE_FAILED, DownloadQueueStore.STATE_CANCELED
            )
            val newlyDone = jobs.any { it.state in completed && previousJobStates[it.id] != it.state }
            previousJobStates = jobs.associate { it.id to it.state }
            searchViewModel.updateDownloadJobs(jobs)
            downloadViewModel.updateJobs(jobs)
            if (newlyDone) refreshHistory()
        }
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    downloadViewModel.downloadJobs,
                    downloadViewModel.concurrentDownloads,
                    downloadViewModel.wifiOnly,
                ) { jobs, concurrency, wifiOnly -> QueueScreen(jobs, concurrency, wifiOnly) }
                    .collect(::renderQueue)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadViewModel.downloadError.collect { message ->
                    if (message != null) {
                        showError(message)
                        downloadViewModel.clearError()
                    }
                }
            }
        }
    }

    private fun currentQueueScreen() = QueueScreen(
        jobs = downloadViewModel.downloadJobs.value,
        concurrency = downloadViewModel.concurrentDownloads.value,
        wifiOnly = downloadViewModel.wifiOnly.value,
    )

    private fun renderQueue(screen: QueueScreen) {
        jobAdapter.submitList(screen.jobs)
        if (::queueEmpty.isInitialized) {
            queueEmpty.visibility = if (screen.jobs.isEmpty()) View.VISIBLE else View.GONE
        }
        if (::queueSummary.isInitialized) {
            queueSummary.text = getString(
                R.string.queue_activity,
                screen.jobs.count { it.state == DownloadQueueStore.STATE_RUNNING },
                screen.concurrency,
                screen.jobs.count { it.state == DownloadQueueStore.STATE_QUEUED },
            )
        }
        syncQueueSettings(screen.concurrency, screen.wifiOnly)
    }

    /**
     * Pushes ViewModel setting values into the widgets, touching them only when they differ
     * so the widgets' own listeners are not re-triggered.
     */
    private fun syncQueueSettings(concurrency: Int, wifiOnly: Boolean) {
        if (!::concurrencySpinner.isInitialized) return
        val position = (concurrency - 1).coerceIn(0, MAX_CONCURRENT_DOWNLOADS - 1)
        if (concurrencySpinner.selectedItemPosition != position) {
            concurrencySpinner.setSelection(position)
        }
        if (wifiOnlyToggle.isChecked != wifiOnly) wifiOnlyToggle.isChecked = wifiOnly
    }

    // ── DownloadJobAdapter.Callbacks ──────────────────────────────────────────

    override fun onJobPause(jobId: Long) = downloadViewModel.pauseJob(jobId)
    override fun onJobResume(jobId: Long) = downloadViewModel.resumeJob(jobId)
    override fun onJobCancel(jobId: Long) = downloadViewModel.cancelJob(jobId)
    override fun onJobRetry(jobId: Long) = downloadViewModel.retryJob(jobId)
    override fun onJobDetails(job: DownloadJob) = showDownloadJobDetails(job)

    override fun onJobMoveToFront(jobId: Long) {
        if (!downloadViewModel.moveJobToFront(jobId)) showError(getString(R.string.move_job_failed))
    }

    private fun showDownloadJobDetails(job: DownloadJob) {
        val message = job.message.trim().ifEmpty { getString(R.string.download_details_unavailable) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.download_details_title, job.id, getString(jobStateLabel(job.state))))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun retryAllFailedJobs() {
        val retryable = downloadViewModel.downloadJobs.value.filter {
            it.state == DownloadQueueStore.STATE_FAILED ||
                    it.state == DownloadQueueStore.STATE_PARTIAL ||
                    it.state == DownloadQueueStore.STATE_CANCELED
        }
        retryable.forEach { downloadViewModel.retryJob(it.id) }
        Toast.makeText(this, getString(R.string.retry_all_started, retryable.size), Toast.LENGTH_SHORT).show()
    }

    private fun clearFinishedJobs() {
        // A negative count means the service was not connected; the ViewModel has already
        // published the reason on its error flow.
        val removed = downloadViewModel.clearFinishedJobs()
        if (removed < 0) return
        Toast.makeText(this, getString(R.string.removed_finished_jobs, removed), Toast.LENGTH_SHORT).show()
    }

    private fun queueActionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        styleActionButton(this, false)
        setOnClickListener { action() }
    }

    private fun refreshPlaybackHistory() {
        recentHistoryGeneration++
        recentHistoryEntries.clear()
        recentHistoryCursor = null
        recentHistoryHasMore = true
        recentHistoryLoading = false
        recentHistoryLoadFailed = false
        loadMorePlaybackHistory()
        refreshPlaybackHistoryViews()
    }

    private fun loadMorePlaybackHistory() {
        if (recentHistoryLoading || !recentHistoryHasMore) return
        recentHistoryLoading = true
        recentHistoryLoadFailed = false
        val generation = recentHistoryGeneration
        val cursor = recentHistoryCursor
        refreshPlaybackHistoryViews()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                Result.success(
                    playlists.getPlaybackHistory(
                        limit = RECENT_HISTORY_PAGE_SIZE + 1,
                        beforeId = cursor?.id,
                    ),
                )
            } catch (error: Exception) {
                android.util.Log.e("MainActivity", "Could not load playback history", error)
                Result.failure(error)
            }
            runOnUiThread {
                if (destroyed || generation != recentHistoryGeneration) return@runOnUiThread
                recentHistoryLoading = false
                result.fold(
                    onSuccess = { page ->
                        val visiblePage = page.take(RECENT_HISTORY_PAGE_SIZE)
                        recentHistoryEntries.addAll(visiblePage)
                        recentHistoryCursor = visiblePage.lastOrNull() ?: recentHistoryCursor
                        recentHistoryHasMore = page.size > RECENT_HISTORY_PAGE_SIZE
                    },
                    onFailure = { recentHistoryLoadFailed = true },
                )
                refreshPlaybackHistoryViews()
            }
        }
    }

    private fun refreshPlaybackHistoryViews() {
        if (::libraryRecentHistory.isInitialized) renderLibraryRecentHistory()
        if (::homeLibraryContent.isInitialized && homeCategory == HOME_CATEGORY_RECENT_PLAYED) {
            renderHomeLibrary()
        }
    }

    private fun renderLibraryRecentHistory() {
        libraryRecentHistory.removeAllViews()
        if (recentHistoryEntries.isEmpty()) {
            val message = when {
                recentHistoryLoadFailed -> getString(R.string.recent_history_load_error)
                recentHistoryLoading -> getString(R.string.recent_history_loading)
                else -> getString(R.string.recent_history_empty)
            }
            libraryRecentHistory.addView(
                emptyMessage(message),
                GridLayout.LayoutParams().apply {
                    width = MATCH
                    height = WRAP
                    columnSpec = GridLayout.spec(0, libraryRecentHistory.columnCount)
                },
            )
            return
        }
        val queue = recentHistoryEntries.take(RECENT_HISTORY_PAGE_SIZE).map(::toAudioTrack)
        recentHistoryEntries.take(RECENT_HISTORY_PREVIEW_COUNT).forEachIndexed { index, entry ->
            addHomeTrackCard(
                track = queue[index],
                queue = queue,
                index = index,
                targetGrid = libraryRecentHistory,
                playedAt = entry.playedAt,
                historyEntryId = entry.id,
            )
        }
    }

    private fun toAudioTrack(entry: PlaybackHistoryEntry): AudioTrack =
        libraryViewModel.tracks.value.firstOrNull { it.uri.toString() == entry.trackUri } ?: AudioTrack(
            uri = Uri.parse(entry.trackUri),
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            durationMs = entry.durationMs,
            artwork = null,
            dateAdded = entry.playedAt,
        )

    private fun showRecentHistoryCollection() {
        homeCategory = HOME_CATEGORY_RECENT_PLAYED
        homeSubcategory = 0
        selectedHomeGroup = null
        renderHomeSubcategories()
        showHomeCollection()
        renderHomeLibrary()
        showTab(R.string.nav_home)
    }

    private fun removePlaybackHistoryEntry(id: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                playlists.deletePlaybackHistoryEntry(id)
            } catch (error: Exception) {
                android.util.Log.e("MainActivity", "Could not remove playback history entry", error)
                false
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (result) refreshPlaybackHistory()
                else showError(getString(R.string.play_history_remove_failed))
            }
        }
    }

    private fun confirmClearPlaybackHistory() {
        if (recentHistoryEntries.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.clear_play_history_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_play_history) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = try {
                        playlists.clearPlaybackHistory()
                        true
                    } catch (error: Exception) {
                        android.util.Log.e("MainActivity", "Could not clear playback history", error)
                        false
                    }
                    runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        if (result) refreshPlaybackHistory()
                        else showError(getString(R.string.play_history_clear_failed))
                    }
                }
            }
            .show()
    }

    private fun refreshHistory() {
        refreshLibrary()
        refreshDownloadedFiles()
    }

    private fun refreshDownloadedFiles() {
        if (!::downloadHistoryList.isInitialized) return
        lifecycleScope.launch(Dispatchers.IO) {
            val items = mutableListOf<Triple<String, Long, Pair<String, Uri>>>()
            var failure: Exception? = null
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE,
            )
            try {
                val cursor = contentResolver.query(
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
                            ?: getString(R.string.unknown_track)
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
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                downloadHistoryList.removeAllViews()
                if (failure != null) {
                    addHistoryEmptyMessage(getString(R.string.history_unavailable))
                } else if (items.isEmpty()) {
                    addHistoryEmptyMessage(getString(R.string.history_empty))
                } else {
                    items.forEach { (name, date, media) ->
                        addHistoryItem(name, date, media.first, media.second)
                    }
                }
            }
        }
    }

    private fun refreshLibrary() {
        if (!::libraryList.isInitialized) return
        lifecycleScope.launch(Dispatchers.IO) {
            val tracks = mutableListOf<AudioTrack>()
            val errors = mutableListOf<String>()
            val cachedUris = mutableSetOf<String>()
            val videoIds = mutableSetOf<String>()
            // One query up front, then map lookups: querying per MediaStore row here was an
            // N+1 against track_metadata for libraries with thousands of rows.
            val cachedMetadata = HashMap<String, SavedTrackMetadata>()
            try {
                val allMetadata = playlists.getAllTrackMetadata()
                allMetadata.forEach { saved ->
                    cachedMetadata.putIfAbsent(MediaUris.canonicalAudio(saved.uri), saved)
                }
                allMetadata.forEach { saved ->
                    val rawUri = Uri.parse(saved.uri)
                    val mime = runCatching { contentResolver.getType(rawUri) }.getOrNull()
                    if (!isAudioUri(rawUri, mime)) return@forEach
                    // Downloads/<id> and audio/media/<id> are the same file; key on the audio URI.
                    val uri = MediaUris.canonicalAudio(rawUri)
                    if (!cachedUris.add(uri.toString())) return@forEach
                    val name = displayName(uri)
                    VideoIds.fromFileName(name)?.let(videoIds::add)
                    val artBytes = saved.artworkPath?.let { path ->
                        runCatching { File(path).takeIf(File::isFile)?.readBytes() }.getOrNull()
                    }
                    tracks.add(
                        AudioTrack(
                            uri = uri,
                            title = saved.title.ifBlank { name.substringBeforeLast('.', name) },
                            artist = saved.artist.ifBlank { getString(R.string.unknown_artist) },
                            album = saved.album.ifBlank { getString(R.string.unknown_album) },
                            durationMs = saved.durationMs ?: 0L,
                            artwork = artBytes?.let(::decodeArtwork),
                            dateAdded = 0L,
                        )
                    )
                }
            } catch (error: Exception) {
                errors.add(
                    getString(
                        R.string.cached_metadata_error,
                        error.localizedMessage ?: error.javaClass.simpleName
                    )
                )
            }
            if (hasAudioLibraryPermission()) try {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.DATE_ADDED
                )
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE))
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                        val added = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
                        val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                        if (!isAudioUri(uri, mime)) continue
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                            ?: getString(R.string.unknown_track)
                        VideoIds.fromFileName(name)?.let(videoIds::add)
                        if (uri.toString() in cachedUris) continue
                        try {
                            tracks.add(readTrack(uri, name, added, cachedMetadata[uri.toString()]))
                        } catch (error: Exception) {
                            errors.add(
                                getString(
                                    R.string.track_read_error,
                                    error.localizedMessage ?: error.javaClass.simpleName
                                )
                            )
                        }
                    }
                } ?: errors.add(getString(R.string.library_unavailable))
            } catch (error: Exception) {
                errors.add(getString(R.string.library_load_error, error.localizedMessage ?: error.javaClass.simpleName))
            }
            importedUris.forEach { raw ->
                try {
                    val parsed = Uri.parse(raw)
                    if (!isAudioUri(parsed, contentResolver.getType(parsed))) return@forEach
                    val uri = MediaUris.canonicalAudio(parsed)
                    if (uri.toString() in cachedUris) return@forEach
                    val name = displayName(uri)
                    VideoIds.fromFileName(name)?.let(videoIds::add)
                    tracks.add(readTrack(uri, name, cached = cachedMetadata[uri.toString()]))
                } catch (error: Exception) {
                    errors.add(
                        getString(
                            R.string.track_read_error,
                            error.localizedMessage ?: error.javaClass.simpleName
                        )
                    )
                }
            }
            val unique = tracks.distinctBy { it.uri.toString() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                libraryViewModel.updateLibrary(unique, errors.distinct())
                searchViewModel.setLibraryVideoIds(videoIds)
                renderHomeLibrary()
                refreshPlaybackHistoryViews()
                refreshPlaylists()
            }
        }
    }

    private fun readTrack(
        uri: Uri,
        fallback: String,
        dateAdded: Long = 0L,
        cached: SavedTrackMetadata?,
    ): AudioTrack {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(this, uri)
            fun text(key: Int) = metadata.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val title = cached?.title?.takeIf { it.isNotBlank() }
                ?: text(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: fallback.substringBeforeLast('.', fallback)
            val artist = cached?.artist?.takeIf { it.isNotBlank() }
                ?: text(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: getString(R.string.unknown_artist)
            val album = cached?.album?.takeIf { it.isNotBlank() }
                ?: text(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: getString(R.string.unknown_album)
            val duration = cached?.durationMs
                ?: text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val embeddedArt = metadata.embeddedPicture
            val artworkBytes = cached?.artworkPath?.let { path ->
                runCatching { File(path).takeIf(File::isFile)?.readBytes() }.getOrNull()
            } ?: embeddedArt
            if (embeddedArt != null || cached == null) {
                val path = embeddedArt?.let { saveArtwork(uri, it) } ?: cached?.artworkPath
                runCatching { playlists.saveTrackMetadata(uri.toString(), title, artist, album, duration, path) }
            }
            AudioTrack(
                uri = uri,
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                artwork = artworkBytes?.let(::decodeArtwork),
                dateAdded = dateAdded,
            )
        } catch (error: Exception) {
            if (cached == null) throw error
            val artwork = cached.artworkPath?.let { path ->
                runCatching { File(path).takeIf(File::isFile)?.readBytes() }.getOrNull()
            }?.let(::decodeArtwork)
            AudioTrack(
                uri = uri,
                title = cached.title.ifBlank { fallback.substringBeforeLast('.', fallback) },
                artist = cached.artist.ifBlank { getString(R.string.unknown_artist) },
                album = cached.album.ifBlank { getString(R.string.unknown_album) },
                durationMs = cached.durationMs ?: 0L,
                artwork = artwork,
                dateAdded = dateAdded,
            )
        } finally {
            metadata.release()
        }
    }

    private fun saveArtwork(uri: Uri, bytes: ByteArray): String? {
        return try {
            val directory = File(filesDir, "track-artwork")
            if (!directory.exists() && !directory.mkdirs()) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            val target = File(directory, "$digest.jpg")
            FileOutputStream(target).use { it.write(bytes) }
            target.absolutePath
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / MAX_TRACK_ART_PIXELS).coerceAtLeast(1)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        })
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: getString(R.string.unknown_track)
        } catch (_: SecurityException) {
            uri.lastPathSegment ?: getString(R.string.unknown_track)
        }
    }

    private fun isAudioUri(uri: Uri, mimeType: String?): Boolean {
        if (mimeType != null) return mimeType.startsWith("audio/")
        val name = displayName(uri)
        return name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
    }

    private fun addHistoryItem(name: String, dateSeconds: Long, mime: String, uri: Uri) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, dp(12))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.history_item, name, DateUtils.getRelativeTimeSpanString(dateSeconds * 1000))
            textSize = 14f
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener { openDownloadedFile(uri, mime) }
        }, matchWidth())
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            queueActionButton(getString(R.string.rename)) { promptRenameDownload(uri, name) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(
            queueActionButton(getString(R.string.share)) { shareDownloadedFile(uri, mime, name) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(
            queueActionButton(getString(R.string.delete)) { confirmDeleteDownload(uri, name) },
            LinearLayout.LayoutParams(0, dp(42), 1f)
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
                width = homeGridCardWidth(columns)
                height = WRAP
                setMargins(dp(4), dp(4), dp(4), dp(8))
            },
        )
        animateHomeCard(card, position)
    }

    private fun openDownloadedFile(uri: Uri, mime: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: android.content.ActivityNotFoundException) {
            showError(getString(R.string.no_file_handler))
        } catch (error: SecurityException) {
            showError(getString(R.string.file_action_error, error.localizedMessage ?: "permission denied"))
        }
    }

    private fun promptRenameDownload(uri: Uri, oldName: String) {
        val input = EditText(this).apply { setText(oldName); selectAll() }
        AlertDialog.Builder(this).setTitle(getString(R.string.rename_download)).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName.contains('/') || newName.contains('\\')) {
                    showError(getString(R.string.invalid_download_name))
                    return@setPositiveButton
                }
                try {
                    val changed = contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) },
                        null,
                        null,
                    )
                    if (changed <= 0) showError(getString(R.string.file_action_failed))
                    refreshHistory()
                } catch (error: Exception) {
                    showError(
                        getString(
                            R.string.file_action_error,
                            error.localizedMessage ?: error.javaClass.simpleName
                        )
                    )
                }
            }.show()
    }

    private fun confirmDeleteDownload(uri: Uri, name: String) {
        AlertDialog.Builder(this).setMessage(getString(R.string.delete_download_confirm, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    if (contentResolver.delete(uri, null, null) <= 0) showError(getString(R.string.file_action_failed))
                    refreshHistory()
                } catch (error: Exception) {
                    showError(
                        getString(
                            R.string.file_action_error,
                            error.localizedMessage ?: error.javaClass.simpleName
                        )
                    )
                }
            }.show()
    }

    private fun shareDownloadedFile(uri: Uri, mime: String, name: String) {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share_download)))
        } catch (error: Exception) {
            showError(getString(R.string.file_action_error, error.localizedMessage ?: error.javaClass.simpleName))
        }
    }

    private fun trackRow(
        track: AudioTrack,
        withPlaylistAction: Boolean,
        playQueue: List<AudioTrack> = libraryViewModel.tracks.value,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_CARD, dp(12)); setPadding(dp(10), dp(8), dp(8), dp(8))
        }
        row.addView(artworkView(track, dp(54)), LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(10) })
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = track.title; textSize = 15f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        })
        labels.addView(TextView(this).apply {
            text = "${track.artist} · ${track.album} · ${formatTime(track.durationMs)}"
            textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED); setPadding(0, dp(3), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.setOnClickListener { playTrackList(playQueue, playQueue.indexOfFirst { it.uri == track.uri }) }
        if (withPlaylistAction) row.addView(iconButton(
            iconRes = R.drawable.ic_add,
            description = getString(R.string.add_to_playlist),
            tint = COLOR_MUTED,
            sizeDp = 42,
            insetDp = 10,
        ) { choosePlaylistForTrack(track) }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) })
        row.setOnLongClickListener {
            AlertDialog.Builder(this).setTitle(track.title).setItems(
                arrayOf(
                    getString(R.string.track_details), getString(R.string.play_now),
                )
            ) { _, which ->
                if (which == 0) showTrackDetails(track) else
                    playTrackList(playQueue, playQueue.indexOfFirst { it.uri == track.uri })
            }.show()
            true
        }
        return row
    }

    private fun artworkView(track: AudioTrack, size: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundedBackground(COLOR_SURFACE, dp(8))
        contentDescription = getString(
            R.string.track_artwork_description,
            track.title,
            track.artist,
        )
        val bitmap = track.artwork
        if (bitmap != null) setImageBitmap(bitmap)
        else {
            setImageResource(android.R.drawable.ic_media_play)
            loadTrustedThumbnail(track.thumbnailUrl)
        }
    }

    private fun showTrackDetails(track: AudioTrack) {
        val art = artworkView(track, dp(220))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(art, LinearLayout.LayoutParams(MATCH, dp(220)).apply { bottomMargin = dp(12) })
            addView(TextView(this@MainActivity).apply {
                text = getString(
                    R.string.track_detail_text,
                    track.title,
                    track.artist,
                    track.album,
                    formatTime(track.durationMs)
                )
                setTextColor(COLOR_TEXT); textSize = 15f
            })
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.track_details)).setView(body)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun promptCreatePlaylist(onCreated: (Long) -> Unit = {}) {
        val input = EditText(this).apply {
            hint = getString(R.string.playlist_name)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.create_playlist))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.create, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = getString(R.string.playlist_name_required)
                    return@setOnClickListener
                }
                try {
                    val duplicate = playlists.getPlaylists().any { it.name.equals(name, ignoreCase = true) }
                    if (duplicate) {
                        input.error = getString(R.string.playlist_name_exists)
                        return@setOnClickListener
                    }
                    val id = playlists.createPlaylist(name)
                    dialog.dismiss()
                    refreshPlaylists()
                    onCreated(id)
                } catch (error: Exception) {
                    showError(getString(R.string.playlist_error, error.localizedMessage ?: ""))
                }
            }
        }
        dialog.show()
    }

    private fun promptRenamePlaylist(playlist: SavedPlaylist) {
        val input = EditText(this).apply {
            hint = getString(R.string.playlist_name)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(playlist.name)
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_playlist))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = getString(R.string.playlist_name_required)
                    return@setOnClickListener
                }
                if (name == playlist.name) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val duplicate = playlistViewModel.playlists.value.any {
                    it.id != playlist.id && it.name.equals(name, ignoreCase = true)
                }
                if (duplicate || name.equals(getString(R.string.liked_songs), ignoreCase = true)) {
                    input.error = getString(R.string.playlist_name_exists)
                    return@setOnClickListener
                }
                playlistViewModel.renamePlaylist(playlist, name)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun refreshPlaylists() {
        playlistViewModel.refreshPlaylists()
    }

    private fun observePlaylists() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    playlistViewModel.playlists,
                    playlistViewModel.playlistError,
                ) { playlists, error -> PlaylistScreen(playlists, error) }
                    .collect(::renderPlaylists)
            }
        }
    }

    private fun currentPlaylistScreen() = PlaylistScreen(
        playlists = playlistViewModel.playlists.value,
        error = playlistViewModel.playlistError.value,
    )

    private fun playlistRows(
        saved: List<SavedPlaylist>,
        error: String?,
        query: String,
    ): List<PlaylistRow> {
        if (error != null) {
            return listOf(PlaylistRow.Message(getString(R.string.playlist_error, error)))
        }
        val filtered = saved.filter { it.name.contains(query, ignoreCase = true) }
        if (filtered.isEmpty()) {
            val rows = ArrayList<PlaylistRow>()
            rows += PlaylistRow.Message(
                getString(
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
                cover = playlist.tracks.firstNotNullOfOrNull { uri -> resolvePlaylistTrack(uri) },
            )
        }
    }

    private fun renderPlaylists(screen: PlaylistScreen) {
        updateLikedSongsCard()
        if (librarySection == LIBRARY_SECTION_PLAYLISTS && ::libraryContent.isInitialized) {
            rebuildLibraryContent()
        }
        if (!::playlistRoot.isInitialized) {
            if (homeCategory == HOME_CATEGORY_PLAYLISTS) renderHomeLibrary()
            return
        }
        val selectedId = selectedPlaylistId
        if (selectedId != null) {
            val selected = screen.playlists.firstOrNull { it.id == selectedId }
            if (selected == null) {
                selectedPlaylistId = null
                playlistDetailBackCallback.isEnabled = false
                buildPlaylistListPage()
            } else {
                renderPlaylistDetail(selected)
            }
        } else if (::playlistList.isInitialized) {
            val query = if (::playlistSearch.isInitialized) playlistSearch.text.toString().trim() else ""
            playlistAdapter.submitList(playlistRows(screen.playlists, screen.error, query))
        }
        if (homeCategory == HOME_CATEGORY_PLAYLISTS) renderHomeLibrary()
    }

    private fun showPlaylistMenu(playlist: SavedPlaylist, anchor: View) {
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, R.string.play)
            menu.add(0, 1, 1, R.string.add_tracks)
            menu.add(0, 2, 2, R.string.share_playlist)
            // Liked Songs is found by name, so renaming it would orphan the like button.
            if (!playlist.name.equals(getString(R.string.liked_songs), ignoreCase = true)) {
                menu.add(0, 4, 3, R.string.rename_playlist)
            }
            menu.add(0, 3, 4, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> loadPlaylistAndPlay(playlist.id)
                    1 -> chooseTracksForPlaylist(playlist.id)
                    2 -> sharePlaylistM3u(playlist)
                    3 -> confirmDeletePlaylist(playlist.id, playlist.name)
                    4 -> promptRenamePlaylist(playlist)
                }
                true
            }
            show()
        }
    }

    private fun choosePlaylistForTrack(track: AudioTrack, prepareForSave: () -> Unit = {}) {
        val saved = try {
            playlists.getPlaylists()
        } catch (error: Exception) {
            showError(getString(R.string.playlist_error, error.localizedMessage ?: ""))
            return
        }
        val search = EditText(this).apply {
            hint = getString(R.string.playlist_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, dp(12))
            minHeight = dp(48)
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val resultScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(results)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, matchWidth())
            addView(resultScroll, LinearLayout.LayoutParams(MATCH, dp(300)).apply {
                topMargin = dp(8)
            })
        }
        lateinit var dialog: AlertDialog
        fun render(query: String) {
            results.removeAllViews()
            val matching = saved.filter { it.name.contains(query.trim(), ignoreCase = true) }
            if (matching.isEmpty()) {
                results.addView(
                    emptyMessage(
                        if (saved.isEmpty()) getString(R.string.playlists_empty)
                        else getString(R.string.playlists_no_matches),
                    )
                )
                return
            }
            matching.forEach { playlist ->
                results.addView(Button(this).apply {
                    text = getString(R.string.playlist_picker_item, playlist.name, playlist.tracks.size)
                    styleActionButton(this, false)
                    setOnClickListener {
                        addTrackToPlaylist(playlist.id, playlist.name, track, prepareForSave)
                        dialog.dismiss()
                    }
                }, matchWidth().apply { bottomMargin = dp(6) })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        render("")
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_to_playlist))
            .setView(body)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.create_playlist, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                promptCreatePlaylist { playlistId ->
                    val created = playlists.getPlaylists().firstOrNull { it.id == playlistId }
                    if (created == null) showError(getString(R.string.playlist_track_failed))
                    else addTrackToPlaylist(playlistId, created.name, track, prepareForSave)
                }
            }
        }
        dialog.show()
    }

    private fun addTrackToPlaylist(
        playlistId: Long,
        playlistName: String,
        track: AudioTrack,
        prepareForSave: () -> Unit = {},
    ) {
        try {
            prepareForSave()
            if (playlists.addTrack(playlistId, track.uri.toString())) {
                Toast.makeText(
                    this,
                    getString(R.string.track_added_to_playlist, playlistName),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(this, getString(R.string.playlist_track_already_added), Toast.LENGTH_SHORT).show()
            }
            refreshPlaylists()
            playlistList.findViewWithTag<View>("playlist-$playlistId")?.let(::animateSuccess)
        } catch (error: Exception) {
            showError(error.localizedMessage ?: getString(R.string.playlist_track_failed))
        }
    }

    private fun chooseTracksForPlaylist(id: Long) {
        val libraryTracks = libraryViewModel.tracks.value
        if (libraryTracks.isEmpty()) {
            Toast.makeText(this, getString(R.string.library_empty), Toast.LENGTH_SHORT).show(); return
        }
        val selectedUris = linkedSetOf<String>()
        val selectedCount = TextView(this).apply {
            text = getString(R.string.selected_tracks, 0)
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(8), 0, dp(4))
        }
        val search = EditText(this).apply {
            hint = getString(R.string.library_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, dp(12))
            minHeight = dp(48)
        }
        val trackRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val trackScroll = ScrollView(this).apply { addView(trackRows) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, matchWidth())
            addView(selectedCount, matchWidth())
            addView(trackScroll, LinearLayout.LayoutParams(MATCH, dp(320)))
        }
        var addButton: Button? = null
        fun renderTrackChoices(query: String) {
            trackRows.removeAllViews()
            val matches = libraryTracks.filter { track ->
                query.isBlank() || track.title.contains(query, true) ||
                        track.artist.contains(query, true) || track.album.contains(query, true)
            }
            if (matches.isEmpty()) {
                trackRows.addView(emptyMessage(getString(R.string.library_no_filter_matches)))
                return
            }
            matches.forEach { track ->
                val uri = track.uri.toString()
                val checkBox = CheckBox(this).apply {
                    text = "${track.title} — ${track.artist}"
                    isChecked = uri in selectedUris
                    minHeight = dp(48)
                    setTextColor(COLOR_TEXT)
                    buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedUris.add(uri) else selectedUris.remove(uri)
                        selectedCount.text = getString(R.string.selected_tracks, selectedUris.size)
                        addButton?.isEnabled = selectedUris.isNotEmpty()
                    }
                }
                trackRows.addView(checkBox, matchWidth())
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderTrackChoices(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        renderTrackChoices("")
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.select_tracks))
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add_tracks) { _, _ ->
                try {
                    val addedCount = playlists.addTracks(id, selectedUris.toList())
                    refreshPlaylists()
                    playlistList.findViewWithTag<View>("playlist-$id")?.let(::animateSuccess)
                    if (addedCount > 0) {
                        Toast.makeText(
                            this,
                            getString(R.string.tracks_added_to_playlist, addedCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (error: Exception) {
                    showError(error.localizedMessage ?: getString(R.string.playlist_track_failed))
                }
            }.show()
        addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        addButton?.isEnabled = selectedUris.isNotEmpty()
    }

    private fun openPlaylist(id: Long) {
        // The ViewModel list loads asynchronously; fall back to the store so a tap never no-ops.
        val playlist = playlistViewModel.playlists.value.firstOrNull { it.id == id }
            ?: runCatching { playlists.getPlaylists().firstOrNull { it.id == id } }.getOrNull()
        if (playlist == null) {
            refreshPlaylists()
            return
        }
        if (currentTab != R.string.nav_playlists) playlistReturnTab = currentTab
        selectedPlaylistId = id
        playlistDetailBackCallback.isEnabled = true
        showTab(R.string.nav_playlists)
        renderPlaylistDetail(playlist)
        (playlistRoot.parent?.parent as? ScrollView)?.scrollTo(0, 0)
    }

    private fun closePlaylistDetail() {
        if (selectedPlaylistId == null) return
        selectedPlaylistId = null
        playlistDetailBackCallback.isEnabled = false
        buildPlaylistListPage()
        renderPlaylists(currentPlaylistScreen())
        val returnTab = playlistReturnTab
        playlistReturnTab = null
        if (returnTab != null && currentTab == R.string.nav_playlists) showTab(returnTab)
    }

    /** Spotify-style playlist page: tinted header with cover, big play/shuffle, flat track list. */
    private fun renderPlaylistDetail(playlist: SavedPlaylist) {
        if (!::playlistRoot.isInitialized || selectedPlaylistId != playlist.id) return
        val root = playlistRoot
        root.removeAllViews()

        val available = playlist.tracks.mapIndexedNotNull { index, raw ->
            resolvePlaylistTrack(raw)?.let { Triple(index, raw, it) }
        }
        val resolvedTracks = available.map { it.third }
        val localTracks = resolvedTracks.filter { it.uri.scheme != "http" && it.uri.scheme != "https" }

        val tint = playlistTintColor(resolvedTracks)
        root.background = android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.ColorDrawable(COLOR_BACKGROUND),
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(tint, COLOR_BACKGROUND),
                ),
            ),
        ).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, dp(480))
        }

        // Top bar: round back button.
        root.addView(
            iconButton(
                iconRes = R.drawable.ic_arrow_back,
                description = getString(R.string.playlist_back),
                fill = Color.argb(110, 0, 0, 0),
                tint = COLOR_TEXT,
                sizeDp = 40,
                insetDp = 9,
            ) { closePlaylistDetail() },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )

        // Cover art, centred.
        val coverSize = minOf(dp(240), (windowWidthPx() * 0.6f).toInt())
        root.addView(
            playlistCoverView(resolvedTracks, coverSize),
            LinearLayout.LayoutParams(coverSize, coverSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
                bottomMargin = dp(18)
            },
        )

        root.addView(TextView(this).apply {
            text = getString(R.string.playlist_label)
            textSize = 12f
            setTextColor(COLOR_MUTED)
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = playlist.name
            textSize = 28f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(2), 0, dp(4))
        }, matchWidth())
        val totalMs = resolvedTracks.sumOf { it.durationMs.coerceAtLeast(0L) }
        root.addView(TextView(this).apply {
            text = if (totalMs > 0L) {
                getString(R.string.playlist_summary, playlist.tracks.size, formatPlaylistDuration(totalMs))
            } else {
                getString(R.string.playlist_track_count, playlist.tracks.size)
            }
            textSize = 13f
            setTextColor(COLOR_MUTED)
        }, matchWidth())

        // Action row: add, more | shuffle, play.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            iconButton(
                iconRes = R.drawable.ic_add,
                description = getString(R.string.add_tracks),
                fill = Color.TRANSPARENT,
                tint = COLOR_MUTED,
                sizeDp = 40,
                insetDp = 10,
            ) { chooseTracksForPlaylist(playlist.id) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) },
        )
        actions.addView(
            iconButton(
                iconRes = R.drawable.ic_more_vert,
                description = getString(R.string.playlist_actions_accessibility, playlist.name),
                tint = COLOR_MUTED,
                sizeDp = 40,
                insetDp = 10,
            ) {},
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        // PopupMenu needs the view as its anchor, so wire it after creation.
        actions.getChildAt(1).setOnClickListener { showPlaylistMenu(playlist, it) }
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(
            iconButton(
                iconRes = R.drawable.ic_shuffle,
                description = getString(R.string.playlist_shuffle_play),
                tint = COLOR_TEXT,
                sizeDp = 48,
                insetDp = 12,
            ) {
                if (localTracks.isEmpty()) loadPlaylistAndPlay(playlist.id)
                else {
                    playTrackList(localTracks, localTracks.indices.random())
                    playbackService?.setShuffle(true)
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) },
        )
        actions.addView(
            iconButton(
                iconRes = R.drawable.ic_play,
                description = getString(R.string.playlist_play_accessibility, playlist.name),
                fill = COLOR_ACCENT,
                tint = COLOR_BACKGROUND,
                sizeDp = 58,
                insetDp = 17,
            ) {
                playbackService?.setShuffle(false)
                loadPlaylistAndPlay(playlist.id)
            },
            LinearLayout.LayoutParams(dp(58), dp(58)),
        )
        root.addView(actions, matchWidth().apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
        })

        if (available.isEmpty()) {
            root.addView(emptyMessage(getString(R.string.playlist_no_available_tracks)), matchWidth())
            root.addView(Button(this).apply {
                text = getString(R.string.add_tracks)
                styleActionButton(this, true)
                setOnClickListener { chooseTracksForPlaylist(playlist.id) }
            }, LinearLayout.LayoutParams(WRAP, dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            })
            return
        }

        available.forEach { (originalIndex, raw, track) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
                isFocusable = true
                background = android.util.TypedValue().let { value ->
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                    getDrawable(value.resourceId)
                }
                setOnLongClickListener {
                    showTrackDetails(track)
                    true
                }
                setOnClickListener {
                    if (track.uri.scheme == "http" || track.uri.scheme == "https") {
                        enqueueUrls(listOf(raw), showStatus = false)
                    } else {
                        val localIndex = localTracks.indexOf(track)
                        if (localIndex >= 0) playTrackList(localTracks, localIndex)
                    }
                }
            }
            row.addView(artworkView(track, dp(52)), LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginEnd = dp(12)
            })
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = track.title
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_TEXT)
            })
            labels.addView(TextView(this).apply {
                text = if (track.durationMs > 0L) "${track.artist} · ${formatTime(track.durationMs)}" else track.artist
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
            val more = iconButton(
                iconRes = R.drawable.ic_more_vert,
                description = getString(R.string.playlist_track_actions_accessibility, track.title),
                tint = COLOR_MUTED,
                sizeDp = 40,
                insetDp = 10,
            ) {}
            more.setOnClickListener { anchor ->
                android.widget.PopupMenu(this, anchor).apply {
                    if (originalIndex > 0) menu.add(0, 0, 0, R.string.move_up)
                    if (originalIndex < playlist.tracks.lastIndex) menu.add(0, 1, 1, R.string.move_down)
                    menu.add(0, 2, 2, R.string.add_to_playlist)
                    menu.add(0, 3, 3, R.string.track_details)
                    menu.add(0, 4, 4, R.string.remove_from_this_playlist)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            0 -> movePlaylistTrack(playlist.id, originalIndex, originalIndex - 1)
                            1 -> movePlaylistTrack(playlist.id, originalIndex, originalIndex + 1)
                            2 -> choosePlaylistForTrack(track)
                            3 -> showTrackDetails(track)
                            4 -> removeFromPlaylist(playlist.id, raw)
                        }
                        true
                    }
                    show()
                }
            }
            row.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
            root.addView(row, matchWidth())
        }
    }

    private fun circleButton(
        label: String,
        description: String,
        fill: Int,
        textColor: Int,
        textSizeSp: Float,
        stroke: Int? = null,
        onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        textSize = textSizeSp
        setTextColor(textColor)
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        includeFontPadding = false
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != null) setStroke(dp(2), stroke)
        }
        setOnClickListener { onClick() }
    }

    /** 2x2 mosaic when there are four distinct covers (like Spotify), else the first cover. */
    private fun playlistCoverView(tracks: List<AudioTrack>, size: Int): View {
        val withArt = tracks.filter { it.artwork != null || it.thumbnailUrl != null }
            .distinctBy { it.artwork?.generationId ?: it.thumbnailUrl }
        val frame = FrameLayout(this).apply {
            background = roundedBackground(COLOR_SURFACE, dp(6))
            clipToOutline = true
            elevation = dp(10).toFloat()
        }
        when {
            withArt.size >= 4 -> {
                val half = size / 2
                val grid = GridLayout(this).apply { columnCount = 2; rowCount = 2 }
                withArt.take(4).forEach { track ->
                    grid.addView(artworkView(track, half).apply { background = null }, GridLayout.LayoutParams().apply {
                        width = half
                        height = half
                    })
                }
                frame.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            withArt.isNotEmpty() -> frame.addView(
                artworkView(withArt.first(), size).apply { background = null },
                FrameLayout.LayoutParams(MATCH, MATCH),
            )
            else -> frame.addView(
                iconView(R.drawable.ic_note, 56, COLOR_MUTED),
                FrameLayout.LayoutParams(MATCH, MATCH),
            )
        }
        return frame
    }

    /** Average colour of the first cover, darkened so white text stays readable. */
    private fun playlistTintColor(tracks: List<AudioTrack>): Int {
        val bitmap = tracks.firstNotNullOfOrNull { it.artwork } ?: return COLOR_HERO_START
        val average = runCatching {
            Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)
        }.getOrNull() ?: return COLOR_HERO_START
        fun mix(channel: (Int) -> Int) =
            (channel(average) * 0.6f + channel(COLOR_BACKGROUND) * 0.4f).toInt()
        return Color.rgb(mix(Color::red), mix(Color::green), mix(Color::blue))
    }

    private fun formatPlaylistDuration(totalMs: Long): String {
        val totalMinutes = (totalMs / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) getString(R.string.duration_hours_minutes, hours.toInt(), minutes.toInt())
        else getString(R.string.duration_minutes, minutes.toInt())
    }

    private fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int) {
        try {
            val moved = playlists.moveTrack(playlistId, fromIndex, toIndex)
            if (!moved) showError(getString(R.string.playlist_track_failed))
            refreshPlaylists()
        } catch (error: Exception) {
            showError(error.localizedMessage ?: getString(R.string.playlist_track_failed))
        }
    }

    private fun sharePlaylistM3u(playlist: SavedPlaylist) {
        val entries = playlist.tracks.mapNotNull { raw ->
            val track = resolvePlaylistTrack(raw) ?: return@mapNotNull null
            val label = listOf(track.artist, track.title)
                .filter(String::isNotBlank)
                .joinToString(" - ")
            "#EXTINF:${(track.durationMs / 1000).coerceAtLeast(-1)},$label\n$raw"
        }
        if (entries.isEmpty()) {
            showError(getString(R.string.playlist_no_available_tracks))
            return
        }
        val m3u = (listOf("#EXTM3U") + entries).joinToString("\n")
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "audio/x-mpegurl"
                putExtra(Intent.EXTRA_SUBJECT, "${playlist.name}.m3u")
                putExtra(Intent.EXTRA_TEXT, m3u)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share_playlist)))
        } catch (error: Exception) {
            showError(getString(R.string.playlist_error, error.localizedMessage ?: ""))
        }
    }

    private fun importPlaylistM3u(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val text = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (total <= MAX_M3U_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_M3U_BYTES + 1 - total))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                    }
                    check(total <= MAX_M3U_BYTES) { getString(R.string.playlist_file_too_large) }
                    output.toString(Charsets.UTF_8.name())
                } ?: error(getString(R.string.playlist_import_failed))
                val available = libraryViewModel.tracks.value.associateBy { it.uri.toString() }
                val matches = linkedMapOf<String, PlaylistImportItem>()
                var pendingTitle: String? = null
                var pendingDurationMs: Long? = null
                text.lineSequence().map(String::trim).forEach { line ->
                    when {
                        line.startsWith("#EXTINF:", ignoreCase = true) -> {
                            val details = line.substringAfter(':')
                            val durationSeconds = details.substringBefore(',').toLongOrNull()
                            pendingDurationMs = durationSeconds?.takeIf { it >= 0 }?.times(1000L)
                            pendingTitle = details.substringAfter(',', "").trim().takeIf(String::isNotEmpty)
                        }

                        line.isEmpty() || line.startsWith("#") -> Unit
                        available.containsKey(line) -> {
                            matches.putIfAbsent(line, PlaylistImportItem(line))
                            pendingTitle = null
                            pendingDurationMs = null
                        }

                        else -> {
                            val remoteUri = Uri.parse(line)
                            if (remoteUri.scheme in setOf("http", "https") && !remoteUri.host.isNullOrBlank()) {
                                val displayTitle = pendingTitle.orEmpty()
                                val separator = displayTitle.indexOf(" - ")
                                val artist = if (separator > 0) displayTitle.substring(0, separator) else ""
                                val title = if (separator > 0) displayTitle.substring(separator + 3) else displayTitle
                                val metadata = SavedTrackMetadata(
                                    uri = line,
                                    title = title.ifBlank { remoteUri.lastPathSegment ?: line },
                                    artist = artist,
                                    album = "",
                                    durationMs = pendingDurationMs,
                                    artworkPath = null,
                                )
                                matches.putIfAbsent(line, PlaylistImportItem(line, metadata))
                            }
                            pendingTitle = null
                            pendingDurationMs = null
                        }
                    }
                }
                if (matches.isEmpty()) error(getString(R.string.playlist_no_matching_tracks))
                Result.success(matches.values.toList())
            } catch (error: Exception) {
                Result.failure(error)
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { tracks ->
                        val input = EditText(this@MainActivity).apply { hint = getString(R.string.playlist_name) }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.playlist_import_name))
                            .setView(input)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.create) { _, _ ->
                                val name = input.text.toString().trim()
                                try {
                                    require(name.isNotEmpty()) { getString(R.string.playlist_name_required) }
                                    val id = playlists.createPlaylist(name)
                                    tracks.forEach { track ->
                                        track.metadata?.let { metadata ->
                                            playlists.saveTrackMetadata(
                                                trackUri = track.uri,
                                                title = metadata.title,
                                                artist = metadata.artist,
                                                album = metadata.album,
                                                durationMs = metadata.durationMs,
                                                artworkPath = metadata.artworkPath,
                                            )
                                        }
                                    }
                                    check(playlists.addTracks(id, tracks.map { it.uri }) == tracks.size) {
                                        getString(R.string.playlist_track_failed)
                                    }
                                    refreshPlaylists()
                                } catch (error: Exception) {
                                    showError(error.localizedMessage ?: getString(R.string.playlist_import_failed))
                                }
                            }.show()
                    },
                    onFailure = { error ->
                        showError(error.localizedMessage ?: getString(R.string.playlist_import_failed))
                    },
                )
            }
        }
    }

    private fun loadPlaylistAndPlay(id: Long) {
        val list = playlists.getPlaylists().firstOrNull { it.id == id } ?: return
        val resolved = list.tracks.mapNotNull(::resolvePlaylistTrack)
        val tracks = resolved.filter { it.uri.scheme != "http" && it.uri.scheme != "https" }
        if (tracks.isNotEmpty()) playTrackList(tracks, 0)
        else {
            val remoteUrls = resolved.map { it.uri.toString() }
            if (remoteUrls.isNotEmpty()) enqueueUrls(remoteUrls, showStatus = false)
            else Toast.makeText(this, getString(R.string.playlist_no_available_tracks), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeletePlaylist(id: Long, name: String) {
        AlertDialog.Builder(this).setMessage(getString(R.string.delete_playlist_confirm, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    playlists.deletePlaylist(id); refreshPlaylists()
                } catch (error: Exception) {
                    showError(error.localizedMessage ?: getString(R.string.playlist_error, ""))
                }
            }.show()
    }

    private fun removeFromPlaylist(id: Long, uri: String) {
        try {
            playlists.removeTrack(id, uri); refreshPlaylists()
        } catch (error: Exception) {
            showError(error.localizedMessage ?: getString(R.string.playlist_track_failed))
        }
    }

    private fun playTrackList(tracks: List<AudioTrack>, index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) {
            playerStatus.text = getString(R.string.player_empty); showTab(R.string.nav_player); return
        }
        val service = playbackService
        if (service == null) {
            playerStatus.text = getString(R.string.player_service_unavailable)
            return
        }
        service.playQueue(
            tracks.map {
                PlaybackTrack(
                    uri = it.uri.toString(),
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    durationMs = it.durationMs,
                    artwork = it.artwork,
                )
            },
            index,
        )
        showTab(R.string.nav_player)
    }

    private fun togglePlayback() {
        if (playbackService == null) {
            val libraryTracks = libraryViewModel.tracks.value
            if (libraryTracks.isNotEmpty()) playTrackList(libraryTracks, 0)
            else playerStatus.text = getString(R.string.library_empty)
            return
        }
        playbackService?.togglePlayback()
    }

    private fun playPrevious() = playbackService?.playPrevious() ?: run {
        playerStatus.text = getString(R.string.player_service_unavailable)
    }

    private fun playNext() = playbackService?.playNext() ?: run {
        playerStatus.text = getString(R.string.player_service_unavailable)
    }

    private fun renderPlaybackSnapshot(snapshot: PlaybackSnapshot) {
        playbackSnapshot = snapshot
        val track = snapshot.track
        if (track == null) {
            lastArtworkAnimationUri = null
            currentPlaybackTrack = null
            playerTitle.text = getString(R.string.nothing_playing)
            playerArtist.text = ""
            playerArtwork.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingBar.animate().cancel()
            nowPlayingBar.visibility = View.GONE
        } else {
            val trackChanged = lastArtworkAnimationUri != track.uri
            lastArtworkAnimationUri = track.uri
            val showPlayerBar = nowPlayingBar.visibility != View.VISIBLE
            currentPlaybackTrack = AudioTrack(
                uri = Uri.parse(track.uri),
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                artwork = track.artwork,
            )
            playerTitle.text = track.title
            playerArtist.text = "${track.artist} · ${track.album}"
            playerArtwork.contentDescription = getString(
                R.string.track_artwork_description,
                track.title,
                track.artist,
            )
            nowPlayingBar.contentDescription = getString(
                R.string.now_playing_accessibility,
                track.title,
                track.artist,
            )
            playerArtwork.setImageResource(android.R.drawable.ic_media_play)
            track.artwork?.let { playerArtwork.setImageBitmap(it) }
            if (trackChanged && animationsEnabled()) {
                playerArtwork.animate().cancel()
                playerArtwork.alpha = 0.72f
                playerArtwork.scaleX = 0.96f
                playerArtwork.scaleY = 0.96f
                playerArtwork.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(SUCCESS_ANIMATION_MS)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.08f))
                    .start()
            }
            nowPlayingTitle.text = track.title
            nowPlayingArtist.text = track.artist
            nowPlayingArtwork.setImageResource(android.R.drawable.ic_media_play)
            track.artwork?.let { nowPlayingArtwork.setImageBitmap(it) }
            nowPlayingBar.visibility = View.VISIBLE
            if (showPlayerBar && animationsEnabled()) {
                nowPlayingBar.alpha = 0f
                nowPlayingBar.translationY = dp(8).toFloat()
                nowPlayingBar.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(TAB_TRANSITION_MS)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                    .start()
            } else {
                nowPlayingBar.alpha = 1f
                nowPlayingBar.translationY = 0f
            }
            if (::playerHeaderSubtitle.isInitialized) {
                playerHeaderSubtitle.text = getString(R.string.now_playing_from_library)
            }
            if (::playerHeaderTitle.isInitialized) {
                playerHeaderTitle.text = track.album.ifBlank { getString(R.string.library_title) }
            }
        }
        nowPlayingButton.setImageResource(
            if (snapshot.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        nowPlayingButton.contentDescription = getString(
            if (snapshot.isPlaying) R.string.pause_playback else R.string.play,
        )
        if (::nowPlayingProgress.isInitialized) {
            nowPlayingProgress.max = snapshot.durationMs.coerceAtLeast(1)
            nowPlayingProgress.progress = snapshot.positionMs.coerceIn(0, nowPlayingProgress.max)
        }
        playerSeek.max = snapshot.durationMs.coerceAtLeast(1)
        if (!seeking) playerSeek.progress = snapshot.positionMs.coerceIn(0, playerSeek.max)
        playerPosition.text = formatTime(snapshot.positionMs.toLong())
        playerDuration.text = formatTime(snapshot.durationMs.toLong())
        playerSeek.isEnabled = snapshot.isPrepared
        playButton.setImageResource(
            if (snapshot.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        playButton.contentDescription = getString(
            if (snapshot.isPlaying) R.string.pause_playback else R.string.play,
        )
        playerStatus.text = snapshot.message ?: when {
            snapshot.isPlaying -> getString(R.string.player_playing)
            track != null && !snapshot.isPrepared -> getString(R.string.player_loading)
            track != null -> getString(R.string.player_paused)
            else -> getString(R.string.player_empty)
        }
        shuffleButton.tint(if (snapshot.shuffle) COLOR_ACCENT else COLOR_MUTED)
        shuffleButton.contentDescription = getString(
            if (snapshot.shuffle) R.string.shuffle_enabled else R.string.shuffle,
        )
        repeatButton.tint(if (snapshot.repeatMode != 0) COLOR_ACCENT else COLOR_MUTED)
        repeatButton.contentDescription = getString(
            when (snapshot.repeatMode) {
                1 -> R.string.repeat_all
                2 -> R.string.repeat_one
                else -> R.string.repeat
            },
        )
        updatePlaybackLikeState()
        syncingPlaybackControls = true
        speedSpinner.setSelection(
            PLAYBACK_SPEEDS.indices.minByOrNull { kotlin.math.abs(PLAYBACK_SPEEDS[it] - snapshot.speed) }
                ?: DEFAULT_SPEED_INDEX,
        )
        equalizerToggle.isChecked = snapshot.equalizerEnabled
        equalizerLevelSeek.progress = snapshot.equalizerLevel
        equalizerLevelSeek.isEnabled = snapshot.equalizerEnabled
        sleepTimerButton.text = snapshot.sleepMinutesRemaining?.let {
            getString(R.string.sleep_timer_remaining, it)
        } ?: getString(R.string.sleep_timer)
        syncingPlaybackControls = false
    }

    private fun showSleepTimerOptions() {
        val service = playbackService
        if (service == null) {
            showError(getString(R.string.player_service_unavailable))
            return
        }
        val minutes = listOf(null, 15, 30, 60, 120, 240)
        val labels = minutes.map { value ->
            if (value == null) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_minutes, value)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sleep_timer))
            .setItems(labels) { _, which -> service.setSleepTimerMinutes(minutes[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importResult(data: Intent?) {
        if (data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        data.data?.let { uris.add(it) }
        var retained = importedUris.toMutableSet()
        var failed = 0
        uris.distinct().forEach { uri ->
            try {
                if (contentResolver.getType(uri)?.startsWith("audio/") != true) {
                    failed++
                    return@forEach
                }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                retained.add(uri.toString())
            } catch (_: SecurityException) {
                failed++
            }
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putStringSet(KEY_IMPORTED, retained).apply()
        if (failed > 0) Toast.makeText(this, getString(R.string.import_permission_warning, failed), Toast.LENGTH_LONG)
            .show()
        refreshLibrary()
    }

    @Deprecated("Deprecated in Android API; required for ACTION_OPEN_DOCUMENT callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_AUDIO && resultCode == RESULT_OK) importResult(data)
        if (requestCode == REQUEST_IMPORT_M3U && resultCode == RESULT_OK) {
            data?.data?.let(::importPlaylistM3u)
        }
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun emptyMessage(message: String) = TextView(this).apply {
        text = message; textSize = 14f; setTextColor(COLOR_MUTED); setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    override fun onDestroy() {
        destroyed = true
        downloadService?.setListener(null)
        if (bound) unbindService(serviceConnection)
        playbackService?.setListener(null)
        if (playbackBound) unbindService(playbackConnection)
        super.onDestroy()
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0) / 1000)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun String.normalizedForDuplicate(): String =
        lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    private fun themeColor(attribute: Int) =
        if (attribute == android.R.attr.textColorSecondary) COLOR_MUTED else COLOR_TEXT

    private fun styledSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items,
        ) {
            private fun styleItem(view: View): View = (view as? TextView)?.apply {
                setTextColor(COLOR_TEXT)
                textSize = 15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minHeight = dp(48)
                setBackgroundColor(COLOR_SURFACE)
            } ?: view

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                styleItem(super.getView(position, convertView, parent))

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                styleItem(super.getDropDownView(position, convertView, parent))
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun sectionCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_CARD, dp(16))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        elevation = dp(2).toFloat()
    }

    private fun animateSuccess(view: View) = view.animateStateChange()

    private fun animateHomeContent() {
        if (!animationsEnabled() || !::homeLibraryContent.isInitialized) return
        homeLibraryContent.animate().cancel()
        homeLibraryContent.alpha = 0f
        homeLibraryContent.translationY = dp(7).toFloat()
        homeLibraryContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(TAB_TRANSITION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.3f))
            .start()
    }

    private fun sectionSpacing() = matchWidth().apply { topMargin = dp(20) }
    private fun matchWidth() = LinearLayout.LayoutParams(MATCH, WRAP)

    companion object {
        private const val REQUEST_NOTIFICATIONS = 10
        private const val REQUEST_AUDIO_LIBRARY = 13
        private const val REQUEST_IMPORT_AUDIO = 11
        private const val REQUEST_IMPORT_M3U = 12
        private const val PREFERENCES = "downloader_preferences"
        private const val KEY_FORMAT = "format_mode"
        private const val KEY_IMPORTED = "imported_audio_uris"
        private const val MAX_TRACK_ART_PIXELS = 640
        private const val MAX_M3U_BYTES = 1024 * 1024
        private const val MAX_CONCURRENT_DOWNLOADS = 10
        private const val MAX_CONTENT_WIDTH_DP = 760
        private const val LIBRARY_SECTION_SONGS = 0
        private const val LIBRARY_SECTION_PLAYLISTS = 1
        private const val TAB_TRANSITION_MS = 180L
        private const val RECENT_HISTORY_PAGE_SIZE = 32
        private const val RECENT_HISTORY_PREVIEW_COUNT = 4
        private const val HOME_CATEGORY_MUSIC = 0
        private const val HOME_CATEGORY_ARTISTS = 1
        private const val HOME_CATEGORY_ALBUMS = 2
        private const val HOME_CATEGORY_PLAYLISTS = 3
        private const val HOME_CATEGORY_DISCOVER = 4
        private const val HOME_CATEGORY_RECENT_PLAYED = 5
        private const val DEFAULT_EQUALIZER_LEVEL = 50
        private const val DEFAULT_SPEED_INDEX = 2
        private val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        /** "1", "1.25", "0.5" — drops a trailing ".0" so the label reads like a speed chip. */
        private fun formatPlaybackSpeed(speed: Float): String {
            val text = String.format(java.util.Locale.US, "%.2f", speed)
                .trimEnd('0')
                .trimEnd('.')
            return text.ifEmpty { "0" }
        }

        private val AUDIO_EXTENSIONS =
            setOf("aac", "aif", "aiff", "alac", "amr", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "wma")
    }
}
