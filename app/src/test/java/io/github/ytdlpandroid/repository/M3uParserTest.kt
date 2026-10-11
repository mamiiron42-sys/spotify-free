package io.github.ytdlpandroid.repository

import io.github.ytdlpandroid.repository.PlaylistRepository.M3uEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the M3U parsing layer, which had no tests. The parser is a pure function over
 * strings, so the file-read and store-write parts are the only pieces not exercised here.
 */
class M3uParserTest {

    @Test
    fun `parses extinf and path pairs`() {
        val text = """
            #EXTM3U
            #EXTINF:213,Artist - Title
            /storage/music/one.mp3
        """.trimIndent()

        val entries = PlaylistRepository.parseM3u(text)

        assertEquals(1, entries.size)
        assertEquals("Title", entries[0].title)
        assertEquals("Artist", entries[0].artist)
        assertEquals(213, entries[0].durationSeconds)
        assertEquals("/storage/music/one.mp3", entries[0].raw)
    }

    @Test
    fun `handles crlf and trailing whitespace`() {
        val text = "#EXTM3U\r\n#EXTINF:100,Somebody - Song\r\n/storage/music/two.mp3\r\n"

        val entries = PlaylistRepository.parseM3u(text)

        assertEquals(1, entries.size)
        assertEquals("Song", entries[0].title)
        assertEquals("Somebody", entries[0].artist)
    }

    @Test
    fun `keeps unknown directives and comments out of the result`() {
        val text = """
            #EXTM3U
            # a comment
            #PLAYLIST:My mix
            #EXTINF:50,A - B
            /a/b.mp3
            #EXTINF:60,C - D
            /c/d.mp3
        """.trimIndent()

        val entries = PlaylistRepository.parseM3u(text)

        assertEquals(2, entries.size)
    }

    @Test
    fun `extgrp becomes the album`() {
        val text = """
            #EXTINF:42,A - B
            #EXTGRP:Greatest Hits
            /a/b.mp3
        """.trimIndent()

        val entries = PlaylistRepository.parseM3u(text)

        assertEquals("Greatest Hits", entries[0].album)
    }

    @Test
    fun `negative or unparsable durations are ignored`() {
        val text = """
            #EXTINF:-5,A - B
            /a/b.mp3
            #EXTINF:oops,C - D
            /c/d.mp3
        """.trimIndent()

        val entries = PlaylistRepository.parseM3u(text)

        assertNull(entries[0].durationSeconds)
        assertNull(entries[1].durationSeconds)
    }

    @Test
    fun `titles without a separator keep the whole string as the title`() {
        val text = """
            #EXTINF:10,Just A Title
            /a/b.mp3
        """.trimIndent()

        val entries = PlaylistRepository.parseM3u(text)

        assertEquals("Just A Title", entries[0].title)
        assertNull(entries[0].artist)
    }

    @Test
    fun `remote entries survive parsing`() {
        val text = """
            #EXTINF:30,A - B
            https://example.com/b.mp3
        """.trimIndent()

        val entries = PlaylistRepository.parseM3u(text)

        assertEquals("https://example.com/b.mp3", entries[0].raw)
    }

    @Test
    fun `an empty document yields no entries`() {
        assertEquals(0, PlaylistRepository.parseM3u("").size)
        assertEquals(0, PlaylistRepository.parseM3u("#EXTM3U\n").size)
    }
}
