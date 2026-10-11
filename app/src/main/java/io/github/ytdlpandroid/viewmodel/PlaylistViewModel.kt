package io.github.ytdlpandroid.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.ytdlpandroid.SavedPlaylist
import io.github.ytdlpandroid.repository.M3UExportOutcome
import io.github.ytdlpandroid.repository.M3UImportOutcome
import io.github.ytdlpandroid.repository.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for managing playlists, liked songs, and per-playlist track operations.
 * All data access is delegated to PlaylistRepository on Dispatchers.IO (via the repository).
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    companion object {
        /** Canonical name used to identify the Liked Songs playlist. */
        const val LIKED_SONGS_NAME = "Liked Songs"

        private const val IMPORT_FAILED_MESSAGE = "Could not import this playlist."
        private const val EXPORT_FAILED_MESSAGE = "Could not export this playlist."
    }

    // --- Playlist list ---

    private val _playlists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val playlists: StateFlow<List<SavedPlaylist>> = _playlists.asStateFlow()

    // --- Liked Songs (derived: first playlist named "Liked Songs", case-insensitive) ---

    private val _likedPlaylist = MutableStateFlow<SavedPlaylist?>(null)
    val likedPlaylist: StateFlow<SavedPlaylist?> = _likedPlaylist.asStateFlow()

    // --- Selected playlist (for the detail view) ---

    private val _selectedPlaylist = MutableStateFlow<SavedPlaylist?>(null)
    val selectedPlaylist: StateFlow<SavedPlaylist?> = _selectedPlaylist.asStateFlow()

    // --- Loading / error state ---

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _playlistError = MutableStateFlow<String?>(null)
    val playlistError: StateFlow<String?> = _playlistError.asStateFlow()

    private val _playlistMessage = MutableStateFlow<String?>(null)
    val playlistMessage: StateFlow<String?> = _playlistMessage.asStateFlow()

    init {
        refreshPlaylists()
    }

    // ---------------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------------

    /**
     * Reloads all playlists from the repository and updates derived state.
     */
    fun refreshPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            _playlistError.value = null
            try {
                val loaded = playlistRepository.getPlaylists()
                _playlists.value = loaded
                _likedPlaylist.value = loaded.firstOrNull {
                    it.name.equals(LIKED_SONGS_NAME, ignoreCase = true)
                }
                // Keep selectedPlaylist in sync with refreshed data so callers see updated tracks.
                val current = _selectedPlaylist.value
                if (current != null) {
                    _selectedPlaylist.value = loaded.firstOrNull { it.id == current.id }
                }
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to load playlists"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Fetches a single playlist by case-insensitive name via a targeted query, including its
     * tracks. The card menus call this to decide "like" vs "remove from liked"; a targeted lookup
     * is used instead of the cached [likedPlaylist] snapshot because that snapshot can be stale or
     * not-yet-loaded, and a null guess would offer the wrong action (and `toggleLike` behaves
     * differently for a null playlist — it would create one). Failures are mapped onto
     * [playlistError] like the other methods here, then rethrown so the caller can keep its own
     * existing error UI rather than silently treating an error as "not liked".
     */
    suspend fun likedPlaylistNamed(name: String): SavedPlaylist? = try {
        playlistRepository.findPlaylistByName(name)
    } catch (e: Exception) {
        _playlistError.value = e.message ?: "Failed to load playlists"
        throw e
    }

    // ---------------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------------

    /**
     * Sets the currently selected playlist for the detail view.
     * Pass null to deselect.
     */
    fun selectPlaylist(playlist: SavedPlaylist?) {
        _selectedPlaylist.value = playlist
    }

    // ---------------------------------------------------------------------------
    // Create / Delete / Rename
    // ---------------------------------------------------------------------------

    /**
     * Creates a new playlist with the given name and refreshes the list.
     */
    fun createPlaylist(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            _playlistError.value = "Playlist name cannot be empty"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _playlistError.value = null
            try {
                playlistRepository.createPlaylist(trimmedName)
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to create playlist"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Deletes a playlist and refreshes the list.
     */
    fun deletePlaylist(playlist: SavedPlaylist) {
        viewModelScope.launch {
            _isLoading.value = true
            _playlistError.value = null
            try {
                playlistRepository.deletePlaylist(playlist.id)
                // Deselect if the deleted playlist was selected.
                if (_selectedPlaylist.value?.id == playlist.id) {
                    _selectedPlaylist.value = null
                }
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to delete playlist"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Renames a playlist to [newName] and refreshes the list.
     */
    fun renamePlaylist(playlist: SavedPlaylist, newName: String) {
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty()) {
            _playlistError.value = "Playlist name cannot be empty"
            return
        }
        viewModelScope.launch {
            _playlistError.value = null
            try {
                playlistRepository.renamePlaylist(playlist.id, trimmedName)
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to rename playlist"
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Track operations
    // ---------------------------------------------------------------------------

    /**
     * Adds [trackUri] to [playlist] and refreshes the list.
     */
    fun addTrackToPlaylist(playlist: SavedPlaylist, trackUri: String) {
        viewModelScope.launch {
            _playlistError.value = null
            try {
                playlistRepository.addTrack(playlist.id, trackUri)
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to add track to playlist"
            }
        }
    }

    /**
     * Removes [trackUri] from [playlist] and refreshes the list.
     */
    fun removeTrackFromPlaylist(playlist: SavedPlaylist, trackUri: String) {
        viewModelScope.launch {
            _playlistError.value = null
            try {
                playlistRepository.removeTrack(playlist.id, trackUri)
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to remove track from playlist"
            }
        }
    }


    // ---------------------------------------------------------------------------
    // Toggle like
    // ---------------------------------------------------------------------------

    /**
     * Adds [trackUri] to the Liked Songs playlist, or removes it if already present.
     * If the Liked Songs playlist does not exist yet it is created automatically.
     */
    fun toggleLike(trackUri: String) {
        viewModelScope.launch {
            _playlistError.value = null
            try {
                val currentLiked = _likedPlaylist.value
                if (currentLiked != null && trackUri in currentLiked.tracks) {
                    // Already liked — remove.
                    playlistRepository.removeTrack(currentLiked.id, trackUri)
                } else {
                    // Not liked — ensure the playlist exists, then add.
                    val playlistId = currentLiked?.id
                        ?: playlistRepository.createPlaylist(LIKED_SONGS_NAME)
                    playlistRepository.addTrack(playlistId, trackUri)
                }
                refreshPlaylists()
            } catch (e: Exception) {
                _playlistError.value = e.message ?: "Failed to toggle like"
            }
        }
    }

    // ---------------------------------------------------------------------------
    // M3U import / export
    // ---------------------------------------------------------------------------

    /**
     * Imports a playlist from the M3U file at [uri].
     *
     * The document is read on [Dispatchers.IO] by [PlaylistRepository.importM3U],
     * which resolves every entry against the local library, skips entries it
     * cannot resolve, and adds the rest to a playlist named after the file
     * (created on a first import, reused afterwards). Successful imports publish
     * a summary on [playlistMessage] and refresh the playlist list; failures are
     * mapped onto [playlistError]. Coroutine cancellation propagates; no other
     * exception escapes this function.
     */
    suspend fun importM3U(uri: Uri) {
        withContext(Dispatchers.IO) {
            _playlistError.value = null
            _playlistMessage.value = null
            val outcome = try {
                playlistRepository.importM3U(uri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                M3UImportOutcome.Failed(error.message ?: IMPORT_FAILED_MESSAGE)
            }
            when (outcome) {
                is M3UImportOutcome.Imported -> {
                    _playlistMessage.value = importSummary(outcome)
                    refreshPlaylists()
                }

                is M3UImportOutcome.Failed -> _playlistError.value = outcome.message
            }
        }
    }

    /**
     * Exports [playlist] to the M3U file at [uri].
     *
     * The playlist is re-read from the store and written on [Dispatchers.IO] by
     * [PlaylistRepository.exportM3U] as `#EXTM3U` plus one
     * `#EXTINF:<duration>,<artist> - <title>` line and one path line per track.
     * Success publishes a summary on [playlistMessage]; failures are mapped onto
     * [playlistError]. Coroutine cancellation propagates; no other exception
     * escapes this function.
     */
    suspend fun exportM3U(playlist: SavedPlaylist, uri: Uri) {
        withContext(Dispatchers.IO) {
            _playlistError.value = null
            _playlistMessage.value = null
            val outcome = try {
                playlistRepository.exportM3U(playlist, uri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                M3UExportOutcome.Failed(error.message ?: EXPORT_FAILED_MESSAGE)
            }
            when (outcome) {
                is M3UExportOutcome.Exported -> {
                    _playlistMessage.value =
                        "Exported ${outcome.trackCount} track(s) from ${outcome.playlistName}"
                }

                is M3UExportOutcome.Failed -> _playlistError.value = outcome.message
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------------

    /** Summary shown after a successful import, including any skipped entries. */
    private fun importSummary(outcome: M3UImportOutcome.Imported): String = buildString {
        append("Imported ")
        append(outcome.addedTracks)
        append(if (outcome.addedTracks == 1) " track into " else " tracks into ")
        append(outcome.playlistName)
        if (outcome.skippedEntries > 0) {
            append(" (")
            append(outcome.skippedEntries)
            append(if (outcome.skippedEntries == 1) " entry" else " entries")
            append(" skipped)")
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _playlistError.value = null
    }

    /**
     * Clears the current message.
     */
    fun clearMessage() {
        _playlistMessage.value = null
    }
}
