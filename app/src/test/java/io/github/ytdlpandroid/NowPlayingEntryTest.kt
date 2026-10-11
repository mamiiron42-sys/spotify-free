package io.github.ytdlpandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture service stores an unresolved entry under a title-derived key, so the key has
 * to be stable across replays and distinct across different tracks sharing a title.
 */
class NowPlayingEntryTest {

    @Test
    fun `same title and artist produce the same key`() {
        assertEquals(
            NowPlayingListenerService.stableKey("Bohemian Rhapsody", "Queen"),
            NowPlayingListenerService.stableKey("Bohemian Rhapsody", "Queen"),
        )
    }

    @Test
    fun `surrounding whitespace does not change the key`() {
        assertEquals(
            NowPlayingListenerService.stableKey("Blinding Lights", "The Weeknd"),
            NowPlayingListenerService.stableKey("  Blinding Lights  ", " The Weeknd "),
        )
    }

    @Test
    fun `different tracks do not collide`() {
        val first = NowPlayingListenerService.stableKey("Halo", "Beyonce")
        val second = NowPlayingListenerService.stableKey("Halo", "Beyonce Live")
        val third = NowPlayingListenerService.stableKey("Hello", "Adele")
        assertNotEquals(first, second)
        assertNotEquals(first, third)
    }

    @Test
    fun `unresolved entries carry the pending prefix`() {
        assertTrue(
            NowPlayingListenerService.stableKey("Title", "Artist")
                .startsWith("key:"),
        )
    }

    @Test
    fun `watch url is built from the video id`() {
        val entry = NowPlayingEntry(
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            sourcePackage = "com.google.android.youtube",
        )
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", entry.watchUrl)
    }
}
