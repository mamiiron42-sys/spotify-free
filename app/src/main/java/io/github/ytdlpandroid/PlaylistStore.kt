package io.github.ytdlpandroid

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedPlaylist(
    val id: Long,
    val name: String,
    val tracks: List<String>,
)

data class SavedTrackMetadata(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
    val artworkPath: String?,
)

data class PlaybackHistoryEntry(
    val id: Long,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val playedAt: Long,
)

class PlaylistStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val appContext: Context = context.applicationContext

    private fun canonical(trackUri: String): String = MediaUris.canonicalAudio(appContext, trackUri)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE playlist_tracks (
                playlist_id INTEGER NOT NULL,
                track_uri TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, track_uri),
                FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX playlist_tracks_order ON playlist_tracks(playlist_id, position, added_at, track_uri)")
        db.execSQL(
            """CREATE TABLE track_metadata (
                uri TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER,
                artwork_path TEXT
            )""".trimIndent(),
        )
        createPlaybackHistorySchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion <= newVersion) { "Playlist database downgrade is not supported" }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE playlist_tracks ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            val orderedMemberships = mutableListOf<Pair<Long, String>>()
            db.query(
                "playlist_tracks",
                arrayOf("playlist_id", "track_uri"),
                null,
                null,
                null,
                null,
                "playlist_id ASC, added_at ASC, rowid ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    orderedMemberships.add(cursor.getLong(0) to cursor.getString(1))
                }
            }
            val positions = mutableMapOf<Long, Long>()
            orderedMemberships.forEach { (playlistId, trackUri) ->
                val position = positions[playlistId] ?: 0L
                db.execSQL(
                    "UPDATE playlist_tracks SET position = ? WHERE playlist_id = ? AND track_uri = ?",
                    arrayOf<Any>(position, playlistId, trackUri),
                )
                positions[playlistId] = position + 1L
            }
            db.execSQL("DROP INDEX IF EXISTS playlist_tracks_order")
            db.execSQL("CREATE INDEX playlist_tracks_order ON playlist_tracks(playlist_id, position, added_at, track_uri)")
        }
        if (oldVersion < 3) createPlaybackHistorySchema(db)
        if (oldVersion < 12) mergeMediaStoreAliases(db)
        if (oldVersion < 13) createNowPlayingHistorySchema(db)
        if (oldVersion < 1 || newVersion > 13) {
            error("No playlist database migration exists from version $oldVersion")
        }
    }

    /**
     * Rewrites Downloads-collection aliases (`.../downloads/<id>`) to their Audio-collection URI
     * so the same file is no longer stored, listed, or added to playlists twice.
     */
    private fun mergeMediaStoreAliases(db: SQLiteDatabase) {
        // track_metadata: move alias rows onto the canonical key, keeping an existing canonical row.
        val metadataUris = db.query("track_metadata", arrayOf("uri"), null, null, null, null, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val metadataSet = metadataUris.toMutableSet()
        metadataUris.forEach { raw ->
            val target = canonical(raw)
            if (target == raw) return@forEach
            if (target in metadataSet) {
                db.delete("track_metadata", "uri = ?", arrayOf(raw))
            } else {
                db.update("track_metadata", ContentValues().apply { put("uri", target) }, "uri = ?", arrayOf(raw))
                metadataSet.add(target)
            }
            metadataSet.remove(raw)
        }

        // playlist_tracks: rewrite per playlist, dropping entries that collapse onto an earlier one.
        val playlistIds = db.query("playlists", arrayOf("id"), null, null, null, null, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
        playlistIds.forEach { playlistId ->
            val tracks = playlistTracks(db, playlistId)
            val seen = mutableSetOf<String>()
            var changed = false
            tracks.forEach { raw ->
                val target = canonical(raw)
                if (target in seen) {
                    db.delete("playlist_tracks", "playlist_id = ? AND track_uri = ?", arrayOf(playlistId.toString(), raw))
                    changed = true
                } else {
                    seen.add(target)
                    if (target != raw) {
                        db.update(
                            "playlist_tracks",
                            ContentValues().apply { put("track_uri", target) },
                            "playlist_id = ? AND track_uri = ?",
                            arrayOf(playlistId.toString(), raw),
                        )
                        changed = true
                    }
                }
            }
            if (changed) rewritePositions(db, playlistId, playlistTracks(db, playlistId))
        }

        // playback_history has no uniqueness constraint; just normalise.
        val historyUris = db.rawQuery("SELECT DISTINCT track_uri FROM playback_history", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        historyUris.forEach { raw ->
            val target = canonical(raw)
            if (target != raw) {
                db.update("playback_history", ContentValues().apply { put("track_uri", target) }, "track_uri = ?", arrayOf(raw))
            }
        }
    }

    private fun createPlaybackHistorySchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playback_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_uri TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                played_at INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    /**
     * History of what other apps played, captured from their media sessions. Kept apart from
     * [playback_history] because these rows have no local file: they offer a download, not
     * playback, and must never be treated as library tracks.
     */
    private fun createNowPlayingHistorySchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE now_playing_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                video_id TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                artwork_url TEXT,
                source_package TEXT NOT NULL,
                played_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX now_playing_history_played_at ON now_playing_history(played_at DESC)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    @Synchronized
    fun getPlaylists(): List<SavedPlaylist> {
        // Single JOIN query instead of N+1 separate queries.
        val playlistOrder = linkedMapOf<Long, String>()   // id → name
        val trackMap = linkedMapOf<Long, MutableList<String>>() // id → [uri, …]

        readableDatabase.rawQuery(
            """
            SELECT p.id, p.name, pt.track_uri
            FROM playlists p
            LEFT JOIN playlist_tracks pt ON p.id = pt.playlist_id
            ORDER BY p.id ASC, pt.position ASC, pt.added_at ASC, pt.track_uri ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1)
                if (!playlistOrder.containsKey(id)) {
                    playlistOrder[id] = name
                    trackMap[id] = mutableListOf()
                }
                if (!cursor.isNull(2)) {
                    trackMap[id]!!.add(cursor.getString(2))
                }
            }
        }

        return playlistOrder.map { (id, name) ->
            SavedPlaylist(id, name, trackMap[id] ?: emptyList())
        }
    }

    @Synchronized
    fun findPlaylistByName(name: String): SavedPlaylist? {
        // Targeted lookup for the "is this track liked?" menu check: it only needs ONE playlist,
        // so avoid getPlaylists()' full JOIN over every playlist and all of its tracks. Read-only,
        // so no schema/version change is involved.
        val db = readableDatabase
        val header = db.query(
            "playlists",
            arrayOf("id", "name"),
            "name = ? COLLATE NOCASE",
            arrayOf(name),
            null,
            null,
            "id ASC",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
        } ?: return null
        return SavedPlaylist(header.first, header.second, playlistTracks(db, header.first))
    }

    @Synchronized
    fun createPlaylist(name: String): Long {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Playlist name cannot be empty" }
        return writableDatabase.insertOrThrow(
            "playlists",
            null,
            ContentValues().apply { put("name", normalizedName) },
        )
    }

    @Synchronized
    fun renamePlaylist(playlistId: Long, name: String): Boolean {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Playlist name cannot be empty" }
        return writableDatabase.update(
            "playlists",
            ContentValues().apply { put("name", normalizedName) },
            "id = ?",
            arrayOf(playlistId.toString()),
        ) > 0
    }

    @Synchronized
    fun addTrack(playlistId: Long, rawTrackUri: String): Boolean {
        if (rawTrackUri.isBlank()) return false
        val trackUri = canonical(rawTrackUri)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val playlistExists = db.query(
                "playlists",
                arrayOf("id"),
                "id = ?",
                arrayOf(playlistId.toString()),
                null,
                null,
                null,
            ).use { it.moveToFirst() }
            if (!playlistExists) return false
            val nextPosition = db.rawQuery(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = ?",
                arrayOf(playlistId.toString()),
            ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
            val inserted = db.insertWithOnConflict(
                "playlist_tracks",
                null,
                ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("track_uri", trackUri)
                    put("added_at", System.currentTimeMillis())
                    put("position", nextPosition)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Inserts many tracks into one playlist inside a SINGLE transaction, returning how many were
     * newly added. Per-URI this is byte-for-byte the logic of [addTrack]: blank URIs are skipped,
     * duplicates are ignored via the (playlist_id, track_uri) primary key + CONFLICT_IGNORE, and
     * each new row gets the next sequential position. Callers that used to loop over [addTrack]
     * paid one transaction per track; this collapses that to one.
     */
    @Synchronized
    fun addTracks(playlistId: Long, trackUris: List<String>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val playlistExists = db.query(
                "playlists",
                arrayOf("id"),
                "id = ?",
                arrayOf(playlistId.toString()),
                null,
                null,
                null,
            ).use { it.moveToFirst() }
            if (!playlistExists) return 0
            var inserted = 0
            trackUris.forEach { rawTrackUri ->
                if (rawTrackUri.isBlank()) return@forEach
                val trackUri = canonical(rawTrackUri)
                val nextPosition = db.rawQuery(
                    "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = ?",
                    arrayOf(playlistId.toString()),
                ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
                val added = db.insertWithOnConflict(
                    "playlist_tracks",
                    null,
                    ContentValues().apply {
                        put("playlist_id", playlistId)
                        put("track_uri", trackUri)
                        put("added_at", System.currentTimeMillis())
                        put("position", nextPosition)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                ) != -1L
                if (added) inserted++
            }
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun removeTrack(playlistId: Long, trackUri: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val removed = db.delete(
                "playlist_tracks",
                "playlist_id = ? AND track_uri IN (?, ?)",
                arrayOf(playlistId.toString(), trackUri, canonical(trackUri)),
            ) > 0
            if (!removed) return false
            rewritePositions(db, playlistId, playlistTracks(db, playlistId))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun moveTrack(playlistId: Long, fromIndex: Int, toIndex: Int): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val playlistExists = db.query(
                "playlists",
                arrayOf("id"),
                "id = ?",
                arrayOf(playlistId.toString()),
                null,
                null,
                null,
            ).use { it.moveToFirst() }
            if (!playlistExists) return false
            val tracks = playlistTracks(db, playlistId)
            if (fromIndex !in tracks.indices || toIndex !in tracks.indices) return false
            if (fromIndex != toIndex) {
                val moved = tracks.removeAt(fromIndex)
                tracks.add(toIndex, moved)
                rewritePositions(db, playlistId, tracks)
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deletePlaylist(playlistId: Long): Boolean =
        writableDatabase.delete("playlists", "id = ?", arrayOf(playlistId.toString())) > 0

    @Synchronized
    fun getTrackMetadata(trackUri: String): SavedTrackMetadata? =
        readableDatabase.query(
            "track_metadata",
            arrayOf("uri", "title", "artist", "album", "duration_ms", "artwork_path"),
            "uri = ?",
            arrayOf(canonical(trackUri)),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.toTrackMetadata()
        }

    @Synchronized
    fun getAllTrackMetadata(): List<SavedTrackMetadata> =
        readableDatabase.query(
            "track_metadata",
            arrayOf("uri", "title", "artist", "album", "duration_ms", "artwork_path"),
            null,
            null,
            null,
            null,
            "uri ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toTrackMetadata())
            }
        }

    @Synchronized
    fun saveTrackMetadata(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
        artworkPath: String?,
    ) {
        require(trackUri.isNotBlank()) { "Track URI cannot be empty" }
        val values = ContentValues().apply {
            put("uri", canonical(trackUri))
            put("title", title)
            put("artist", artist)
            put("album", album)
            if (durationMs == null) putNull("duration_ms") else put("duration_ms", durationMs)
            if (artworkPath == null) putNull("artwork_path") else put("artwork_path", artworkPath)
        }
        check(writableDatabase.insertWithOnConflict(
            "track_metadata",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L) { "Could not save track metadata" }
    }

    @Synchronized
    fun recordPlayback(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        playedAt: Long = System.currentTimeMillis(),
    ): Long {
        require(trackUri.isNotBlank()) { "Track URI cannot be empty" }
        return writableDatabase.insertOrThrow(
            "playback_history",
            null,
            ContentValues().apply {
                put("track_uri", canonical(trackUri))
                put("title", title)
                put("artist", artist)
                put("album", album)
                put("duration_ms", durationMs.coerceAtLeast(0L))
                put("played_at", playedAt)
            },
        )
    }

    @Synchronized
    fun getPlaybackHistory(
        limit: Int,
        beforeId: Long? = null,
    ): List<PlaybackHistoryEntry> {
        require(limit in 1..200) { "Playback history page size must be between 1 and 200" }
        val selection = if (beforeId == null) null else "id < ?"
        val selectionArgs = beforeId?.let { arrayOf(it.toString()) }
        return readableDatabase.query(
            "playback_history",
            arrayOf("id", "track_uri", "title", "artist", "album", "duration_ms", "played_at"),
            selection,
            selectionArgs,
            null,
            null,
            "id DESC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PlaybackHistoryEntry(
                            id = cursor.getLong(0),
                            trackUri = cursor.getString(1),
                            title = cursor.getString(2),
                            artist = cursor.getString(3),
                            album = cursor.getString(4),
                            durationMs = cursor.getLong(5),
                            playedAt = cursor.getLong(6),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun deletePlaybackHistoryEntry(id: Long): Boolean =
        writableDatabase.delete("playback_history", "id = ?", arrayOf(id.toString())) > 0

    @Synchronized
    fun clearPlaybackHistory(): Int =
        writableDatabase.delete("playback_history", null, null)

    private fun android.database.Cursor.toTrackMetadata() = SavedTrackMetadata(
        uri = getString(0),
        title = getString(1),
        artist = getString(2),
        album = getString(3),
        durationMs = if (isNull(4)) null else getLong(4),
        artworkPath = if (isNull(5)) null else getString(5),
    )

    private fun playlistTracks(db: SQLiteDatabase, playlistId: Long): MutableList<String> =
        db.query(
            "playlist_tracks",
            arrayOf("track_uri"),
            "playlist_id = ?",
            arrayOf(playlistId.toString()),
            null,
            null,
            "position ASC, added_at ASC, track_uri ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }.toMutableList()
        }

    /**
     * Inserts a captured play, or moves an existing video to the top by refreshing its
     * timestamp. Returns the new row id, or -1 if the entry had no usable video id.
     */
    @Synchronized
    fun recordNowPlaying(entry: NowPlayingEntry): Long {
        if (entry.videoId.isBlank()) return -1L
        val playedAt = if (entry.playedAt > 0L) entry.playedAt else System.currentTimeMillis()
        val values = ContentValues().apply {
            put("video_id", entry.videoId)
            put("title", entry.title)
            put("artist", entry.artist)
            put("album", entry.album)
            put("duration_ms", entry.durationMs.coerceAtLeast(0L))
            put("artwork_url", entry.artworkUrl)
            put("source_package", entry.sourcePackage)
            put("played_at", playedAt)
        }
        // video_id is UNIQUE, so a replay replaces the old row and keeps one entry per video.
        return writableDatabase.replace("now_playing_history", null, values)
    }

    @Synchronized
    fun getNowPlayingHistory(limit: Int): List<NowPlayingEntry> {
        require(limit in 1..500) { "Now playing history page size must be between 1 and 500" }
        return readableDatabase.query(
            "now_playing_history",
            arrayOf(
                "id", "video_id", "title", "artist", "album", "duration_ms",
                "artwork_url", "source_package", "played_at",
            ),
            null,
            null,
            null,
            null,
            "played_at DESC, id DESC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        NowPlayingEntry(
                            id = cursor.getLong(0),
                            videoId = cursor.getString(1),
                            title = cursor.getString(2),
                            artist = cursor.getString(3),
                            album = cursor.getString(4),
                            durationMs = cursor.getLong(5),
                            artworkUrl = if (cursor.isNull(6)) null else cursor.getString(6),
                            sourcePackage = cursor.getString(7),
                            playedAt = cursor.getLong(8),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun deleteNowPlayingEntry(id: Long): Boolean =
        writableDatabase.delete("now_playing_history", "id = ?", arrayOf(id.toString())) > 0

    @Synchronized
    fun clearNowPlayingHistory(): Int = writableDatabase.delete("now_playing_history", null, null)

    @Synchronized
    fun videoIsInNowPlayingHistory(videoId: String): Boolean {
        if (videoId.isBlank()) return false
        return readableDatabase.query(
            "now_playing_history",
            arrayOf("id"),
            "video_id = ?",
            arrayOf(videoId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun rewritePositions(db: SQLiteDatabase, playlistId: Long, tracks: List<String>) {
        tracks.forEachIndexed { index, trackUri ->
            val values = ContentValues().apply { put("position", index.toLong()) }
            check(
                db.update(
                    "playlist_tracks",
                    values,
                    "playlist_id = ? AND track_uri = ?",
                    arrayOf(playlistId.toString(), trackUri),
                ) == 1,
            ) { "Could not update playlist track position" }
        }
    }

    companion object {
        private const val DATABASE_NAME = "playlists.db"
        private const val DATABASE_VERSION = 13
    }
}
