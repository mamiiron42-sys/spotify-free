package io.github.ytdlpandroid

/**
 * A track captured from another app's media session, i.e. what YouTube or YouTube Music
 * was playing on this device. These are *not* local files: the id is the YouTube video id,
 * so a captured entry can be offered as a download but never played from disk.
 */
data class NowPlayingEntry(
    val id: Long = 0L,
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val sourcePackage: String,
    val playedAt: Long = System.currentTimeMillis(),
) {
    /** Watch URL, used both as the download source and as a stable playback-free identifier. */
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
}

/** Settings permission and capture state for [NowPlayingListenerService]. */
data class NowListeningState(
    val granted: Boolean = false,
    val capturing: Boolean = false,
    val entry: NowPlayingEntry? = null,
)
