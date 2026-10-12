package io.github.ytdlpandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSearchesTest {

    @Test
    fun `a new query goes to the front`() {
        val result = RecentSearches.remember(listOf("older", "oldest"), "newest")
        assertEquals(listOf("newest", "older", "oldest"), result)
    }

    @Test
    fun `a repeated query moves to the front instead of duplicating`() {
        val result = RecentSearches.remember(listOf("a", "b", "c"), "a")
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `repeats are matched ignoring case and surrounding whitespace`() {
        val result = RecentSearches.remember(listOf("Bohemian Rhapsody", "other"), "  BOHEMIAN rhapsody ")
        assertEquals(listOf("BOHEMIAN rhapsody", "other"), result)
    }

    @Test
    fun `the list is capped`() {
        val existing = (1..20).map { "query $it" }
        val result = RecentSearches.remember(existing, "one more")
        assertEquals(RecentSearches.LIMIT, result.size)
        assertEquals("one more", result.first())
        // The newest of the stored entries survive; the oldest fall off the end.
        assertTrue(result.contains("query 1"))
        assertTrue(result.contains("query 11"))
        assertTrue(result.none { it == "query 12" || it == "query 20" })
    }

    @Test
    fun `a blank query changes nothing`() {
        val existing = listOf("keep me")
        assertEquals(existing, RecentSearches.remember(existing, "   "))
        assertEquals(existing, RecentSearches.remember(existing, ""))
    }

    @Test
    fun `normalize drops blanks and case-insensitive duplicates`() {
        val messy = listOf("  Song ", "song", "", "  ", "OTHER", "other")
        assertEquals(listOf("Song", "OTHER"), RecentSearches.normalize(messy))
    }
}
