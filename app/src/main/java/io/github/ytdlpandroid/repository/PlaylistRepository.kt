package io.github.ytdlpandroid.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ytdlpandroid.MediaUris
import io.github.ytdlpandroid.NowPlayingEntry
import io.github.ytdlpandroid.PlaybackHistoryEntry
import io.github.ytdlpandroid.PlaylistStore
import io.github.ytdlpandroid.SavedPlaylist
import io.github.ytdlpandroid.SavedTrackMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Repository for managing playlists and playback history.
 * Wraps PlaylistStore with coroutine support for async operations.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    
    private val playlistStore by lazy { PlaylistStore(context) }
    
    /**
     * Retrieves all playlists from the database.
     */
    suspend fun getPlaylists(): List<SavedPlaylist> = withContext(Dispatchers.IO) {
        playlistStore.getPlaylists()
    }

    /**
     * Retrieves a single playlist by case-insensitive name, including its tracks.
     */
    suspend fun findPlaylistByName(name: String): SavedPlaylist? = withContext(Dispatchers.IO) {
        playlistStore.findPlaylistByName(name)
    }

    /**
     * Creates a new playlist with the given name.
     */
    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        playlistStore.createPlaylist(name)
    }

    /**
     * Renames a playlist, returning false when it no longer exists.
     */
    suspend fun renamePlaylist(playlistId: Long, name: String): Boolean = withContext(Dispatchers.IO) {
        playlistStore.renamePlaylist(playlistId, name)
    }
    
    /**
     * Adds a track to a playlist.
     */
    suspend fun addTrack(playlistId: Long, trackUri: String): Boolean = withContext(Dispatchers.IO) {
        playlistStore.addTrack(playlistId, trackUri)
    }

    /**
     * Adds many tracks to a playlist inside a single transaction, returning how many were added.
     */
    suspend fun addTracks(playlistId: Long, trackUris: List<String>): Int = withContext(Dispatchers.IO) {
        playlistStore.addTracks(playlistId, trackUris)
    }
    
    /**
     * Removes a track from a playlist.
     */
    suspend fun removeTrack(playlistId: Long, trackUri: String): Boolean = withContext(Dispatchers.IO) {
        playlistStore.removeTrack(playlistId, trackUri)
    }
    
    /**
     * Moves a track to a new position within a playlist.
     */
    suspend fun moveTrack(playlistId: Long, fromIndex: Int, toIndex: Int): Boolean = withContext(Dispatchers.IO) {
        playlistStore.moveTrack(playlistId, fromIndex, toIndex)
    }
    
    /**
     * Deletes a playlist and all its tracks.
     */
    suspend fun deletePlaylist(playlistId: Long): Boolean = withContext(Dispatchers.IO) {
        playlistStore.deletePlaylist(playlistId)
    }
    
    /**
     * Retrieves metadata for a specific track.
     */
    suspend fun getTrackMetadata(trackUri: String): SavedTrackMetadata? = withContext(Dispatchers.IO) {
        playlistStore.getTrackMetadata(trackUri)
    }
    
    /**
     * Retrieves metadata for all tracks.
     */
    suspend fun getAllTrackMetadata(): List<SavedTrackMetadata> = withContext(Dispatchers.IO) {
        playlistStore.getAllTrackMetadata()
    }
    
    /**
     * Saves or updates track metadata.
     */
    suspend fun saveTrackMetadata(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
        artworkPath: String?,
    ) = withContext(Dispatchers.IO) {
        playlistStore.saveTrackMetadata(trackUri, title, artist, album, durationMs, artworkPath)
    }
    
    /**
     * Records a playback history entry.
     */
    suspend fun recordPlayback(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        playedAt: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        playlistStore.recordPlayback(trackUri, title, artist, album, durationMs, playedAt)
    }
    
    /**
     * Retrieves playback history with pagination.
     */
    suspend fun getPlaybackHistory(limit: Int, beforeId: Long? = null): List<PlaybackHistoryEntry> =
        withContext(Dispatchers.IO) {
            playlistStore.getPlaybackHistory(limit, beforeId)
        }
    
    /**
     * Deletes a specific playback history entry.
     */
    suspend fun deletePlaybackHistoryEntry(id: Long): Boolean = withContext(Dispatchers.IO) {
        playlistStore.deletePlaybackHistoryEntry(id)
    }

    /**
     * Records a track captured from another app's media session (e.g. YouTube), keyed by
     * video id so replays refresh the timestamp instead of duplicating rows.
     */
    suspend fun recordNowPlaying(entry: NowPlayingEntry): Long = withContext(Dispatchers.IO) {
        playlistStore.recordNowPlaying(entry)
    }

    suspend fun getNowPlayingHistory(limit: Int): List<NowPlayingEntry> = withContext(Dispatchers.IO) {
        playlistStore.getNowPlayingHistory(limit)
    }

    suspend fun deleteNowPlayingEntry(id: Long): Boolean = withContext(Dispatchers.IO) {
        playlistStore.deleteNowPlayingEntry(id)
    }

    suspend fun clearNowPlayingHistory(): Int = withContext(Dispatchers.IO) {
        playlistStore.clearNowPlayingHistory()
    }

    suspend fun videoIsInNowPlayingHistory(videoId: String): Boolean = withContext(Dispatchers.IO) {
        playlistStore.videoIsInNowPlayingHistory(videoId)
    }
    
    /**
     * Clears all playback history.
     */
    suspend fun clearPlaybackHistory(): Int = withContext(Dispatchers.IO) {
        playlistStore.clearPlaybackHistory()
    }

    // -----------------------------------------------------------------------
    // M3U import / export
    // -----------------------------------------------------------------------

    /**
     * Imports the M3U playlist referenced by [uri].
     *
     * The document is read through the content resolver and parsed for the
     * `#EXTM3U` header, `#EXTINF:<seconds>,<title>` lines, `#EXTGRP:` groups,
     * `#` comments and blank lines, with CRLF line endings and a UTF-8 BOM
     * handled. Every entry is resolved against the local library — the
     * MediaStore audio collection plus the track metadata and playlist
     * membership this app already stored — by URI, file name, file name without
     * its extension, or title/artist/duration. Remote `http`/`https` entries
     * and entries with no local match are skipped instead of failing the import.
     *
     * The resolved tracks are added to a playlist named after the file: the
     * playlist is created on a first import and reused afterwards, and
     * duplicates are ignored by the store. Failures are reported as
     * [M3UImportOutcome.Failed] instead of being thrown, while coroutine
     * cancellation still propagates to the caller.
     */
    suspend fun importM3U(uri: Uri): M3UImportOutcome = withContext(Dispatchers.IO) {
        try {
            val scheme = uri.scheme?.lowercase()
            if (scheme !in SUPPORTED_URI_SCHEMES) {
                return@withContext M3UImportOutcome.Failed(UNSUPPORTED_SCHEME_MESSAGE)
            }
            val read = readM3uBytes(uri)
            if (read.tooLarge) {
                return@withContext M3UImportOutcome.Failed(FILE_TOO_LARGE_MESSAGE)
            }
            val bytes = read.bytes
                ?: return@withContext M3UImportOutcome.Failed(FILE_UNREADABLE_MESSAGE)
            val entries = parseM3u(String(bytes, Charsets.UTF_8).removePrefix(BOM))
            if (entries.isEmpty()) {
                return@withContext M3UImportOutcome.Failed(EMPTY_FILE_MESSAGE)
            }
            val resolved = resolveM3uEntries(entries, libraryTracks())
            if (resolved.uris.isEmpty()) {
                return@withContext M3UImportOutcome.Failed(NO_MATCHING_TRACKS_MESSAGE)
            }
            val playlistName = playlistNameFromUri(uri)
            val playlistId = findPlaylistByName(playlistName)?.id
                ?: createPlaylist(playlistName)
            val added = addTracks(playlistId, resolved.uris)
            M3UImportOutcome.Imported(
                playlistName = playlistName,
                addedTracks = added,
                skippedEntries = resolved.skipped,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            M3UImportOutcome.Failed(error.message ?: IMPORT_FAILED_MESSAGE)
        }
    }

    /**
     * Writes [playlist] to [uri] as an M3U document.
     *
     * The playlist is re-read from the store first so a stale snapshot cannot
     * export tracks that were removed meanwhile, and each membership is written
     * as `#EXTINF:<duration>,<artist> - <title>` followed by the track's file
     * path resolved through MediaStore (or OpenableColumns for providers that
     * are not MediaStore), falling back to the stored URI when no path is
     * available. Failures are reported as [M3UExportOutcome.Failed] instead of
     * being thrown, while coroutine cancellation still propagates to the caller.
     */
    suspend fun exportM3U(playlist: SavedPlaylist, uri: Uri): M3UExportOutcome =
        withContext(Dispatchers.IO) {
            try {
                val scheme = uri.scheme?.lowercase()
                if (scheme !in SUPPORTED_URI_SCHEMES) {
                    return@withContext M3UExportOutcome.Failed(UNSUPPORTED_SCHEME_MESSAGE)
                }
                val stored = findPlaylistByName(playlist.name)
                    ?: getPlaylists().firstOrNull { it.id == playlist.id }
                    ?: return@withContext M3UExportOutcome.Failed(MISSING_PLAYLIST_MESSAGE)
                val metadataByUri = stored.tracks.mapNotNull { trackUri ->
                    getTrackMetadata(trackUri)?.let { trackUri to it }
                }.toMap()
                val document = buildString {
                    append(M3U_HEADER).append('\n')
                    stored.tracks.forEach { trackUri ->
                        currentCoroutineContext().ensureActive()
                        val metadata = metadataByUri[trackUri]
                        val title = metadata?.title?.trim()?.takeIf(String::isNotEmpty)
                            ?: displayNameOf(trackUri)
                            ?: trackUri
                        val label = listOf(metadata?.artist?.trim()?.takeIf(String::isNotEmpty), title)
                            .filterNotNull()
                            .joinToString(TITLE_SEPARATOR)
                        val durationSeconds = metadata?.durationMs
                            ?.takeIf { it > 0 }
                            ?.let { it / 1000 }
                            ?: 0L
                        append(EXTINF_PREFIX).append(durationSeconds).append(',')
                        append(label).append('\n')
                        append(trackPathForUri(trackUri)).append('\n')
                    }
                }
                val output = context.contentResolver.openOutputStream(uri)
                    ?: return@withContext M3UExportOutcome.Failed(FILE_UNWRITABLE_MESSAGE)
                output.use { stream ->
                    stream.write(document.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                M3UExportOutcome.Exported(
                    playlistName = stored.name,
                    trackCount = stored.tracks.size,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                M3UExportOutcome.Failed(error.message ?: EXPORT_FAILED_MESSAGE)
            }
        }

    // -----------------------------------------------------------------------
    // M3U helpers
    // -----------------------------------------------------------------------

    /** A track M3U entries can be matched against, sourced from MediaStore or the app's own rows. */
    private class LibraryTrack(
        val uri: String,
        val fileName: String?,
        val baseFileName: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationSeconds: Int?,
    )

    /** One parsed M3U entry: its path/URI plus the metadata the preceding directives carried. */
    internal class M3uEntry(
        val raw: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationSeconds: Int?,
    )

    /** Matched track URIs for an import, plus how many entries could not be resolved. */
    internal class M3uResolution(val uris: List<String>, val skipped: Int)

    /** Bytes read from the document, with a size flag so the caller can choose its message. */
    private class M3uReadResult(val bytes: ByteArray?, val tooLarge: Boolean)

    /** One MediaStore audio row, used to resolve filesystem paths for export. */
    private class MediaStoreRow(
        val dataPath: String?,
        val displayName: String?,
        val relativePath: String?,
    )

    /**
     * Reads the document at [uri] into memory, capped at [MAX_M3U_BYTES]; a
     * null [M3uReadResult.bytes] with `tooLarge == false` means the stream could
     * not be opened at all.
     */
    private fun readM3uBytes(uri: Uri): M3uReadResult = try {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return M3uReadResult(bytes = null, tooLarge = false)
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (total <= MAX_M3U_BYTES) {
                val count = input.read(buffer, 0, buffer.size)
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
            val tooLarge = total > MAX_M3U_BYTES
            M3uReadResult(
                bytes = if (tooLarge) null else output.toByteArray(),
                tooLarge = tooLarge,
            )
        }
    } catch (error: Exception) {
        M3uReadResult(bytes = null, tooLarge = false)
    }

    /** Splits M3U text into entries, carrying `#EXTINF`/`#EXTGRP` state to the following path. */

    /**
     * Every local track an import can match against. The MediaStore audio
     * library comes first (it owns the on-disk file names), then the metadata
     * rows and playlist memberships the app stored, which also cover tracks the
     * scanner has not picked up yet.
     */
    private suspend fun libraryTracks(): List<LibraryTrack> {
        val pool = LinkedHashMap<String, LibraryTrack>()
        // Same filter as TrackRepository.loadLibraryTracks, so "the library" means
        // the same set of files to both.
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
            ),
            "${MediaStore.Audio.Media.IS_MUSIC} = 1",
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val uri = "${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/${cursor.getLong(idColumn)}"
                val fileName = cursor.getString(displayNameColumn)?.trim()?.takeIf(String::isNotEmpty)
                val durationMs = if (cursor.isNull(durationColumn)) null else cursor.getLong(durationColumn)
                pool[uri] = LibraryTrack(
                    uri = uri,
                    fileName = fileName?.lowercase(),
                    baseFileName = fileName?.substringBeforeLast('.')?.lowercase()?.takeIf(String::isNotEmpty),
                    title = cursor.getString(titleColumn)?.trim()?.takeIf(String::isNotEmpty),
                    artist = cursor.getString(artistColumn)?.trim()?.takeIf(String::isNotEmpty),
                    album = cursor.getString(albumColumn)?.trim()?.takeIf(String::isNotEmpty),
                    durationSeconds = durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
                )
            }
        }
        unionStoredTracks(pool)
        return pool.values.toList()
    }

    /** Merges the metadata rows and playlist memberships the app stored into [pool]. */
    private suspend fun unionStoredTracks(pool: LinkedHashMap<String, LibraryTrack>) {
        getAllTrackMetadata().forEach { metadata ->
            if (!entryIsLocal(metadata.uri)) return@forEach
            val canonical = MediaUris.canonicalAudio(metadata.uri)
            val stored = pool[canonical]
            pool[canonical] = LibraryTrack(
                uri = canonical,
                fileName = stored?.fileName ?: fileNameOf(canonical),
                baseFileName = stored?.baseFileName ?: baseFileNameOf(canonical),
                title = metadata.title.trim().takeIf(String::isNotEmpty) ?: stored?.title,
                artist = metadata.artist.trim().takeIf(String::isNotEmpty) ?: stored?.artist,
                album = metadata.album.trim().takeIf(String::isNotEmpty) ?: stored?.album,
                durationSeconds = stored?.durationSeconds
                    ?: metadata.durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
            )
        }
        getPlaylists().forEach { playlist ->
            playlist.tracks.forEach { raw ->
                if (!entryIsLocal(raw)) return@forEach
                val canonical = MediaUris.canonicalAudio(raw)
                if (canonical !in pool) {
                    pool[canonical] = LibraryTrack(
                        uri = canonical,
                        fileName = fileNameOf(canonical),
                        baseFileName = baseFileNameOf(canonical),
                        title = null,
                        artist = null,
                        album = null,
                        durationSeconds = null,
                    )
                }
            }
        }
    }

    /** Matches every parsed entry against [tracks], skipping the ones that cannot be resolved. */
    private suspend fun resolveM3uEntries(entries: List<M3uEntry>, tracks: List<LibraryTrack>): M3uResolution {
        val byUri = tracks.associateBy { it.uri }
        val byFileName = tracks.filter { it.fileName != null }.groupBy { it.fileName!! }
        val byBaseFileName = tracks.filter { it.baseFileName != null }.groupBy { it.baseFileName!! }
        val byTitle = tracks.filter { it.title != null }.groupBy { normalizeMatchKey(it.title!!) }
        val resolved = LinkedHashSet<String>()
        var skipped = 0
        entries.forEach { entry ->
            currentCoroutineContext().ensureActive()
            val uri = findTrackUri(entry, byUri, byFileName, byBaseFileName, byTitle)
            if (uri == null) skipped++ else resolved += uri
        }
        return M3uResolution(resolved.toList(), skipped)
    }

    /**
     * Resolves one entry to a stored track URI: the exact URI first (with
     * MediaStore aliases normalized), then the file name, then the file name
     * without its extension, then the `#EXTINF` title. Ambiguous candidates are
     * narrowed by artist or duration. Returns null when the entry is remote or
     * nothing matches.
     */
    private fun findTrackUri(
        entry: M3uEntry,
        byUri: Map<String, LibraryTrack>,
        byFileName: Map<String, List<LibraryTrack>>,
        byBaseFileName: Map<String, List<LibraryTrack>>,
        byTitle: Map<String, List<LibraryTrack>>,
    ): String? {
        // Only local content/file references can be resolved; web URLs need a download, not a match.
        val parsed = runCatching { Uri.parse(entry.raw) }.getOrNull()
        val parsedScheme = parsed?.scheme?.lowercase()
        if (parsed != null && parsedScheme != null && parsedScheme !in SUPPORTED_URI_SCHEMES) return null

        val raw = entry.raw
        byUri[raw]?.let { return it.uri }
        val canonical = MediaUris.canonicalAudio(raw)
        byUri[canonical]?.let { return it.uri }

        val fileName = fileNameOf(raw)
        if (fileName != null) {
            pickTrack(byFileName[fileName], entry)?.let { return it }
            val baseFileName = fileName.substringBeforeLast('.').takeIf(String::isNotEmpty)
            if (baseFileName != null) pickTrack(byBaseFileName[baseFileName], entry)?.let { return it }
        }
        val titleKey = entry.title?.let(::normalizeMatchKey)
        if (titleKey != null) pickTrack(byTitle[titleKey], entry)?.let { return it }
        return null
    }

    /**
     * Picks the single candidate, or the one whose artist or duration matches
     * the entry's `#EXTINF` data. Ambiguous entries with no matching metadata
     * resolve to null so the caller can skip them.
     */
    private fun pickTrack(candidates: List<LibraryTrack>?, entry: M3uEntry): String? {
        if (candidates.isNullOrEmpty()) return null
        if (candidates.size == 1) return candidates.first().uri
        val artistKey = entry.artist?.let(::normalizeMatchKey)
        if (artistKey != null) {
            candidates.firstOrNull { track ->
                track.artist != null && normalizeMatchKey(track.artist) == artistKey
            }?.let { return it.uri }
        }
        val duration = entry.durationSeconds
        if (duration != null) {
            candidates.firstOrNull { track ->
                track.durationSeconds != null &&
                    abs(track.durationSeconds - duration) <= DURATION_TOLERANCE_SECONDS
            }?.let { return it.uri }
        }
        return null
    }

    /** True when [raw] references local storage, i.e. a content/file URI or a bare path. */
    private fun entryIsLocal(raw: String): Boolean {
        if (raw.isBlank()) return false
        val scheme = runCatching { Uri.parse(raw).scheme }.getOrNull()?.lowercase()
        return scheme == null || scheme in SUPPORTED_URI_SCHEMES
    }

    /** Lowercased file name of a path or `file://` URI, for filename matching. */
    private fun fileNameOf(raw: String): String? {
        val path = FILE_SCHEME_PREFIX.replace(raw.trim().trimEnd('/'), "")
        val segment = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return segment.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    /** Lowercased file name without its extension, or null when there is no extension. */
    private fun baseFileNameOf(raw: String): String? {
        val fileName = fileNameOf(raw) ?: return null
        val base = fileName.substringBeforeLast('.')
        return base.takeIf { it.isNotEmpty() }
    }

    private fun normalizeMatchKey(value: String): String = value.trim().lowercase()

    /** Playlist name for an import: the document's file name without its extension. */
    private fun playlistNameFromUri(uri: Uri): String {
        val segment = uri.lastPathSegment?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
            ?: return DEFAULT_PLAYLIST_NAME
        val decoded = runCatching { Uri.decode(segment) }.getOrDefault(segment).trim()
        val base = when {
            decoded.endsWith(M3U_EXTENSION, ignoreCase = true) ||
                decoded.endsWith(M3U8_EXTENSION, ignoreCase = true) -> decoded.substringBeforeLast('.').trim()
            else -> decoded
        }
        return base.ifEmpty { DEFAULT_PLAYLIST_NAME }
    }

    /** Filesystem path for a stored track URI, or the URI itself when none can be resolved. */
    private fun trackPathForUri(trackUri: String): String {
        val uri = runCatching { Uri.parse(trackUri) }.getOrNull() ?: return trackUri
        return when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.takeIf(String::isNotEmpty) ?: trackUri
            "content" -> contentPathForUri(uri) ?: trackUri
            else -> trackUri
        }
    }

    /** Path of a `content://` track, via MediaStore when the authority is `media`. */
    private fun contentPathForUri(uri: Uri): String? {
        val id = uri.lastPathSegment
        if (uri.authority == "media" && id != null && id.all(Char::isDigit)) {
            val row = mediaStoreRow(id)
            if (row != null) {
                when {
                    row.dataPath != null -> return row.dataPath
                    row.displayName != null && row.relativePath != null ->
                        return "${row.relativePath.trimEnd('/')}/${row.displayName}"
                    row.displayName != null -> return row.displayName
                }
            }
        }
        return openableDisplayName(uri)
    }

    /** Display name of a stored track, used as the fallback `#EXTINF` title. */
    private fun displayNameOf(trackUri: String): String? {
        val uri = runCatching { Uri.parse(trackUri) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "file" -> uri.lastPathSegment?.takeIf(String::isNotEmpty)
            "content" -> {
                val id = uri.lastPathSegment
                if (uri.authority == "media" && id != null && id.all(Char::isDigit)) {
                    mediaStoreRow(id)?.displayName?.let { return it }
                }
                openableDisplayName(uri)
            }
            else -> null
        }
    }

    private fun openableDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.takeIf(String::isNotEmpty) else null
            }

    /**
     * Reads the MediaStore audio row for [id]. `DATA` is deprecated but still
     * populated for readable media, so it is preferred; `RELATIVE_PATH` plus
     * `DISPLAY_NAME` is the fallback for scoped storage.
     */
    private fun mediaStoreRow(id: String): MediaStoreRow? =
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
            ),
            "${MediaStore.Audio.Media._ID} = ?",
            arrayOf(id),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val dataIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val data = if (dataIndex >= 0 && !cursor.isNull(dataIndex)) {
                cursor.getString(dataIndex)?.trim()?.takeIf(String::isNotEmpty)
            } else {
                null
            }
            val nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                cursor.getString(nameIndex)?.trim()?.takeIf(String::isNotEmpty)
            } else {
                null
            }
            val relativeIndex = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val relative = if (relativeIndex >= 0 && !cursor.isNull(relativeIndex)) {
                cursor.getString(relativeIndex)?.trim()?.takeIf(String::isNotEmpty)
            } else {
                null
            }
            MediaStoreRow(dataPath = data, displayName = name, relativePath = relative)
        }

    companion object {
        internal fun parseM3u(text: String): List<M3uEntry> {
            val entries = mutableListOf<M3uEntry>()
            var title: String? = null
            var durationSeconds: Int? = null
            var group: String? = null
            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.isEmpty() -> Unit

                    line.startsWith(EXTINF_PREFIX, ignoreCase = true) -> {
                        val details = line.substringAfter(':', "")
                        durationSeconds = details.substringBefore(',').trim().toIntOrNull()
                            ?.takeIf { it >= 0 }
                        title = details.substringAfter(',', "").trim().takeIf(String::isNotEmpty)
                    }

                    line.startsWith(EXTGRP_PREFIX, ignoreCase = true) ->
                        group = line.substringAfter(':', "").trim().takeIf(String::isNotEmpty)

                    line.startsWith("#") -> Unit

                    else -> {
                        val display = title?.let { DASH_RUN_REGEX.replace(it, " - ") }
                        val separator = display?.indexOf(TITLE_SEPARATOR) ?: -1
                        val artist = display
                            ?.takeIf { separator > 0 }
                            ?.substring(0, separator)
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                        val entryTitle = if (display != null && separator > 0) {
                            display.substring(separator + TITLE_SEPARATOR.length).trim()
                        } else {
                            display?.trim()
                        }
                        entries += M3uEntry(
                            raw = line,
                            title = entryTitle?.takeIf(String::isNotEmpty),
                            artist = artist,
                            album = group,
                            durationSeconds = durationSeconds,
                        )
                        title = null
                        durationSeconds = null
                        group = null
                    }
                }
            }
            return entries
        }
        /** Import is capped like MainActivity's importer so a huge file cannot exhaust memory. */
        private const val MAX_M3U_BYTES = 1024 * 1024

        /** Only local documents can be imported or exported. */
        private val SUPPORTED_URI_SCHEMES = setOf("content", "file")

        private const val BOM = "\uFEFF"
        private const val M3U_HEADER = "#EXTM3U"
        private const val EXTINF_PREFIX = "#EXTINF:"
        private const val EXTGRP_PREFIX = "#EXTGRP:"
        private const val TITLE_SEPARATOR = " - "
        private const val FILE_SCHEME_PATTERN = "^file://"
        private const val M3U_EXTENSION = ".m3u"
        private const val M3U8_EXTENSION = ".m3u8"
        private const val DEFAULT_PLAYLIST_NAME = "Imported playlist"
        private const val DURATION_TOLERANCE_SECONDS = 1

        private val FILE_SCHEME_PREFIX = FILE_SCHEME_PATTERN.toRegex(RegexOption.IGNORE_CASE)
        private val DASH_RUN_REGEX = "\\s+-\\s+".toRegex()

        private const val UNSUPPORTED_SCHEME_MESSAGE =
            "Only playlists from content:// or file:// locations can be imported or exported."
        private const val FILE_TOO_LARGE_MESSAGE = "Playlist file is larger than 1 MB."
        private const val FILE_UNREADABLE_MESSAGE = "Could not read this playlist file."
        private const val FILE_UNWRITABLE_MESSAGE = "Could not write this playlist file."
        private const val EMPTY_FILE_MESSAGE = "This playlist file has no entries."
        private const val NO_MATCHING_TRACKS_MESSAGE = "No playlist entries match local tracks."
        private const val IMPORT_FAILED_MESSAGE = "Could not import this playlist."
        private const val MISSING_PLAYLIST_MESSAGE = "That playlist no longer exists."
        private const val EXPORT_FAILED_MESSAGE = "Could not export this playlist."
    }
}

/** Outcome of [PlaylistRepository.importM3U]. */
sealed interface M3UImportOutcome {

    /** [playlistName] now holds [addedTracks] resolved tracks; [skippedEntries] entries had no local match. */
    data class Imported(
        val playlistName: String,
        val addedTracks: Int,
        val skippedEntries: Int,
    ) : M3UImportOutcome

    /** The import failed; [message] is safe to show to the user. */
    data class Failed(val message: String) : M3UImportOutcome
}

/** Outcome of [PlaylistRepository.exportM3U]. */
sealed interface M3UExportOutcome {

    /** [playlistName] was written with [trackCount] entries. */
    data class Exported(
        val playlistName: String,
        val trackCount: Int,
    ) : M3UExportOutcome

    /** The export failed; [message] is safe to show to the user. */
    data class Failed(val message: String) : M3UExportOutcome
}
