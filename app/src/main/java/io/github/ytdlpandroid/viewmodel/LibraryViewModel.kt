package io.github.ytdlpandroid.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.AudioTrack
import io.github.ytdlpandroid.repository.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing the music library.
 * Handles loading, filtering, sorting, and grouping of audio tracks.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
) : ViewModel() {

    companion object {
        // Group mode constants
        const val GROUP_NONE = 0
        const val GROUP_ARTIST = 1
        const val GROUP_ALBUM = 2

        // Sort mode constants
        const val SORT_TITLE = 0
        const val SORT_ARTIST = 1
        const val SORT_ALBUM = 2
        const val SORT_DATE_ADDED = 3
    }

    // Raw track list from repository
    private val _tracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val tracks: StateFlow<List<AudioTrack>> = _tracks.asStateFlow()

    // Group mode: GROUP_NONE, GROUP_ARTIST, GROUP_ALBUM
    private val _groupMode = MutableStateFlow(GROUP_NONE)
    val groupMode: StateFlow<Int> = _groupMode.asStateFlow()

    // Sort mode: SORT_TITLE, SORT_ARTIST, SORT_ALBUM, SORT_DATE_ADDED
    private val _sortMode = MutableStateFlow(SORT_TITLE)
    val sortMode: StateFlow<Int> = _sortMode.asStateFlow()

    // Free-text filter typed in the library search bar
    private val _filterQuery = MutableStateFlow("")
    val filterQuery: StateFlow<String> = _filterQuery.asStateFlow()

    // Derived: tracks after applying filter and sort
    private val _filteredTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val filteredTracks: StateFlow<List<AudioTrack>> = _filteredTracks.asStateFlow()

    // Currently selected track for the details panel
    private val _selectedTrack = MutableStateFlow<AudioTrack?>(null)
    val selectedTrack: StateFlow<AudioTrack?> = _selectedTrack.asStateFlow()

    // Loading / error state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _libraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = _libraryError.asStateFlow()

    // Accumulated per-track/load errors from the last library scan
    private val _libraryErrors = MutableStateFlow<List<String>>(emptyList())
    val libraryErrors: StateFlow<List<String>> = _libraryErrors.asStateFlow()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Pushes a freshly scanned library into observable state.
     *
     * `MainActivity.refreshLibrary()` — not [refreshLibrary] — is the authoritative loader:
     * `TrackRepository.loadLibraryTracks()` filters on `IS_MUSIC`, and omits cached-metadata
     * tracks (the only source when READ_AUDIO_LIBRARY is absent), imported URIs, artwork
     * bytes and the accumulated error list, so its results must never replace this push.
     */
    fun updateLibrary(tracks: List<AudioTrack>, errors: List<String>) {
        _tracks.value = tracks
        _libraryErrors.value = errors
        applyFiltersAndSort()
    }

    /**
     * Sets the group mode and re-derives [filteredTracks].
     */
    fun setGroupMode(mode: Int) {
        _groupMode.value = mode
        applyFiltersAndSort()
    }

    /**
     * Sets the sort mode and re-derives [filteredTracks].
     */
    fun setSortMode(mode: Int) {
        _sortMode.value = mode
        applyFiltersAndSort()
    }

    /**
     * Updates the free-text filter and re-derives [filteredTracks].
     */
    fun setFilterQuery(q: String) {
        _filterQuery.value = q
        applyFiltersAndSort()
    }

    /**
     * Sets the track shown in the details panel.
     */
    fun selectTrack(track: AudioTrack?) {
        _selectedTrack.value = track
    }

    /**
     * Returns a [Flow] that emits the artwork bitmap for [track], or null on failure.
     * Runs on [Dispatchers.IO] to keep IO off the main thread.
     */
    fun loadTrackArtwork(track: AudioTrack): Flow<Bitmap?> = flow {
        emit(trackRepository.loadArtwork(track.uri))
    }.flowOn(Dispatchers.IO)


    /**
     * Reloads all tracks from [TrackRepository] and re-applies the current
     * filter/sort state.
     */
    fun refreshLibrary() {
        viewModelScope.launch {
            _isLoading.value = true
            _libraryError.value = null
            try {
                val loaded = trackRepository.loadLibraryTracks()
                _tracks.value = loaded
                applyFiltersAndSort()
            } catch (e: Exception) {
                _libraryError.value = "Failed to load library: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _libraryError.value = null
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Re-computes [filteredTracks] from the current [tracks], [filterQuery], and
     * [sortMode].  Called whenever any of those inputs change.
     */
    private fun applyFiltersAndSort() {
        val query = _filterQuery.value.trim()

        val filtered = if (query.isEmpty()) {
            _tracks.value
        } else {
            _tracks.value.filter { track ->
                track.title.contains(query, ignoreCase = true) ||
                    track.artist.contains(query, ignoreCase = true) ||
                    track.album.contains(query, ignoreCase = true)
            }
        }

        _filteredTracks.value = when (_sortMode.value) {
            SORT_ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            SORT_ALBUM -> filtered.sortedBy { it.album.lowercase() }
            SORT_DATE_ADDED -> filtered.sortedByDescending { it.dateAdded }
            else -> filtered.sortedBy { it.title.lowercase() } // SORT_TITLE default
        }
    }
}
