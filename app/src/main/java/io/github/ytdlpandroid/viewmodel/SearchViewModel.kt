package io.github.ytdlpandroid.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ytdlpandroid.DownloadJob
import io.github.ytdlpandroid.DownloadQueueStore
import io.github.ytdlpandroid.DownloadService
import io.github.ytdlpandroid.MusicSearchResult
import io.github.ytdlpandroid.R
import io.github.ytdlpandroid.VideoIds
import io.github.ytdlpandroid.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

/**
 * ViewModel for managing music search functionality.
 * Handles search queries, results pagination, and download indicators.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    
    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _searchResults = MutableStateFlow<List<MusicSearchResult>>(emptyList())
    val searchResults: StateFlow<List<MusicSearchResult>> = _searchResults.asStateFlow()
    
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()
    
    private val _hasMoreResults = MutableStateFlow(false)
    val hasMoreResults: StateFlow<Boolean> = _hasMoreResults.asStateFlow()
    
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()
    
    // Pagination state
    private var searchOffset = 0
    private var searchRequestId = 0
    
    // Download state for search results
    private val _downloadJobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val downloadJobs: StateFlow<List<DownloadJob>> = _downloadJobs.asStateFlow()
    
    /**
     * Pre-computed URL → download state, derived from [downloadJobs] in a single
     * pass so the UI performs at most one lookup per indicator.
     */
    private val _downloadStatusByUrl = MutableStateFlow<Map<String, SearchDownloadState>>(emptyMap())
    val downloadStatusByUrl: StateFlow<Map<String, SearchDownloadState>> = _downloadStatusByUrl.asStateFlow()
    
    /** YouTube ids of files currently in the library, pushed by the library loader. */
    private val _libraryVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val libraryVideoIds: StateFlow<Set<String>> = _libraryVideoIds.asStateFlow()

    private var libraryLoaded = false

    fun setLibraryVideoIds(ids: Set<String>) {
        libraryLoaded = true
        _libraryVideoIds.value = ids
    }

    /** True when the result's file is in the library right now. */
    fun isInLibrary(url: String): Boolean =
        VideoIds.fromUrl(url)?.let { it in _libraryVideoIds.value } == true

    /** True when an earlier download finished but its file is no longer in the library. */
    fun wasDownloadedButRemoved(url: String): Boolean =
        libraryLoaded && !isInLibrary(url) &&
            _downloadStatusByUrl.value[url]?.status == SearchDownloadStatus.COMPLETE

    /**
     * Queue state for a result, upgraded to COMPLETE when the file is already in the library
     * (covers downloads whose queue entries were cleared, or that predate the queue).
     */
    fun downloadStateFor(
        url: String,
        byUrl: Map<String, SearchDownloadState>,
        libraryIds: Set<String>,
    ): SearchDownloadState? {
        val job = byUrl[url]
        val inLibrary = VideoIds.fromUrl(url)?.let { it in libraryIds } == true
        if (!inLibrary) return job
        return when (job?.status) {
            null, SearchDownloadStatus.NONE, SearchDownloadStatus.RETRYABLE ->
                SearchDownloadState(SearchDownloadStatus.COMPLETE, job?.jobId)
            else -> job
        }
    }

    private var downloadService: DownloadService? = null
    
    /**
     * Sets the download service reference for queueing downloads.
     */
    fun setDownloadService(service: DownloadService?) {
        downloadService = service
    }
    
    /**
     * Updates the download jobs list from the service and re-derives
     * [downloadStatusByUrl] in a single pass over [jobs].
     */
    fun updateDownloadJobs(jobs: List<DownloadJob>) {
        _downloadJobs.value = jobs
        _downloadStatusByUrl.value = deriveDownloadStatus(jobs)
    }
    
    private fun deriveDownloadStatus(jobs: List<DownloadJob>): Map<String, SearchDownloadState> {
        val byUrl = HashMap<String, SearchDownloadState>()
        jobs.forEach { job ->
            val urls = runCatching { JSONArray(job.urlsJson) }.getOrNull() ?: return@forEach
            val status = when (job.state) {
                DownloadQueueStore.STATE_RUNNING -> SearchDownloadStatus.DOWNLOADING
                DownloadQueueStore.STATE_QUEUED -> SearchDownloadStatus.QUEUED
                DownloadQueueStore.STATE_PAUSED -> SearchDownloadStatus.PAUSED
                DownloadQueueStore.STATE_COMPLETE -> SearchDownloadStatus.COMPLETE
                DownloadQueueStore.STATE_PARTIAL,
                DownloadQueueStore.STATE_FAILED,
                DownloadQueueStore.STATE_CANCELED -> SearchDownloadStatus.RETRYABLE
                else -> SearchDownloadStatus.NONE
            }
            val state = SearchDownloadState(status, job.id)
            for (index in 0 until urls.length()) {
                val url = urls.optString(index)
                if (url.isNotEmpty()) byUrl[url] = state
            }
        }
        return byUrl
    }
    
    /**
     * Performs a music search with the given query.
     */
    fun search(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            _searchResults.value = emptyList()
            _hasMoreResults.value = false
            _searchError.value = context.getString(R.string.search_query_required)
            return
        }
        
        _searchQuery.value = trimmedQuery
        searchOffset = 0
        // Invalidates any in-flight callback and releases the load guard so a new
        // search is not swallowed by the previous one.
        searchRequestId++
        _isSearching.value = false
        _isLoadingMore.value = false
        _searchResults.value = emptyList()
        _hasMoreResults.value = true
        _searchError.value = null
        
        loadNextPage(initial = true)
    }
    
    /**
     * Loads the next page of search results.
     */
    fun loadNextPage(initial: Boolean = false) {
        if (_isSearching.value || _isLoadingMore.value) return
        if (!initial && !_hasMoreResults.value) return
        
        val service = downloadService
        if (service == null) {
            if (initial) {
                _searchError.value = context.getString(R.string.search_service_unavailable)
                _hasMoreResults.value = false
            }
            return
        }
        
        val requestId = searchRequestId
        val offset = searchOffset
        
        if (initial) {
            _isSearching.value = true
        } else {
            _isLoadingMore.value = true
        }
        
        try {
            // DownloadService dispatches to its own executor and posts the callback
            // back to the main looper, so this call does not block the caller.
            service.searchMusic(_searchQuery.value, offset, SEARCH_PAGE_SIZE) { result ->
                if (requestId != searchRequestId) return@searchMusic
                
                if (initial) {
                    _isSearching.value = false
                } else {
                    _isLoadingMore.value = false
                }
                
                result.fold(
                    onSuccess = { items ->
                        val existing = if (initial) emptyList() else _searchResults.value
                        val seen = existing.mapTo(HashSet()) { it.url }
                        _searchResults.value = existing + items.filter { seen.add(it.url) }
                        // The offset indexes the raw YouTube result list, which the Python side
                        // trims (channels/playlists are dropped), so always advance a full page
                        // and only stop once a page comes back empty.
                        searchOffset += SEARCH_PAGE_SIZE
                        _hasMoreResults.value = items.isNotEmpty()
                        
                        if (items.isEmpty() && _searchResults.value.isEmpty()) {
                            _searchError.value = context.getString(R.string.search_no_results)
                        }
                    },
                    onFailure = { error ->
                        _searchError.value = searchFailureMessage(error.message)
                        // Keep paging alive when results are already on screen so the
                        // UI can offer a retry instead of dead-ending the list.
                        _hasMoreResults.value = _searchResults.value.isNotEmpty()
                    },
                )
            }
        } catch (e: Exception) {
            _isSearching.value = false
            _isLoadingMore.value = false
            _searchError.value = searchFailureMessage(e.message)
        }
    }
    
    private fun searchFailureMessage(detail: String?): String = context.getString(
        R.string.search_failed,
        detail ?: context.getString(R.string.unknown_error),
    )
    
    /**
     * Clears the current search results.
     */
    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchError.value = null
        _hasMoreResults.value = false
        _isSearching.value = false
        _isLoadingMore.value = false
        searchOffset = 0
        searchRequestId++
    }
    
    /**
     * Saves metadata for a search result track.
     */
    fun saveSearchResultMetadata(item: MusicSearchResult) {
        viewModelScope.launch {
            try {
                playlistRepository.saveTrackMetadata(
                    trackUri = item.url,
                    title = item.title,
                    artist = item.uploader,
                    album = "",
                    durationMs = item.durationSeconds?.times(1000L),
                    artworkPath = item.thumbnailUrl,
                )
            } catch (e: Exception) {
                _searchError.value = "Failed to save metadata: ${e.message}"
            }
        }
    }
    
    /**
     * Queues a download for a search result using the caller's selected [format].
     */
    fun queueDownload(item: MusicSearchResult, format: String) {
        val service = downloadService ?: run {
            _searchError.value = context.getString(R.string.search_service_unavailable)
            return
        }
        
        try {
            service.enqueueUrls(
                urlsJson = JSONArray(listOf(item.url)).toString(),
                format = format,
                allowPlaylist = false,
            )
        } catch (e: Exception) {
            _searchError.value = context.getString(
                R.string.download_error,
                e.localizedMessage ?: e.javaClass.simpleName,
            )
        }
    }
    
    /**
     * Resumes a paused download job.
     */
    fun resumeJob(jobId: Long) {
        downloadService?.resumeJob(jobId)
    }
    
    /**
     * Retries a failed download job.
     */
    fun retryJob(jobId: Long) {
        downloadService?.retryJob(jobId)
    }
    
    /**
     * Cancels a download job.
     */
    fun cancelJob(jobId: Long) {
        downloadService?.cancelJob(jobId)
    }
    
    /**
     * Clears the error message.
     */
    fun clearError() {
        _searchError.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        downloadService = null
    }
    
    private companion object {
        const val SEARCH_PAGE_SIZE = 20
    }
}

/**
 * Requirement 7.1 names only DOWNLOADING, QUEUED and NONE, but the search
 * indicator renders six visually distinct states. Collapsing to three would
 * drop the complete/paused/retryable affordances that Requirement 17 requires
 * be preserved, so the fuller set is modelled here.
 */
enum class SearchDownloadStatus { NONE, QUEUED, DOWNLOADING, PAUSED, COMPLETE, RETRYABLE }

data class SearchDownloadState(
    val status: SearchDownloadStatus,
    val jobId: Long?,
)
