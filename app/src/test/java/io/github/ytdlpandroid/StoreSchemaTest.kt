package io.github.ytdlpandroid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against the missing-table crash.
 *
 * The bug: `createNowPlayingHistorySchema` was called from `onUpgrade` alone, so a fresh
 * install built a database with no `now_playing_history` table and the first load of the
 * capture shelf threw, every launch, in a loop. Every schema the store owns has to be
 * created in both paths - `onCreate` for a new database and `onUpgrade` for an existing one.
 */
class StoreSchemaTest {

    @Test
    fun `every schema the store owns is created on create and on upgrade`() {
        val store = SourceFixtures.source("io/github/ytdlpandroid/PlaylistStore.kt")
        assertTrue("PlaylistStore.kt not found", store.isFile)
        val source = store.readText()

        val onCreate = SourceFixtures.bodyOf(source, "override fun onCreate(")
        val onUpgrade = SourceFixtures.bodyOf(source, "override fun onUpgrade(")
        assertTrue("onCreate not found", onCreate.isNotBlank())
        assertTrue("onUpgrade not found", onUpgrade.isNotBlank())

        // Only table creators. A `merge*` migration is a one-way step for existing databases
        // and belongs in onUpgrade alone, so including it here would be a false alarm.
        val schemaFunctions = Regex("""private fun (create\w+Schema)\(""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("no schema functions found", schemaFunctions.isNotEmpty())

        val missing = mutableListOf<String>()
        schemaFunctions.forEach { function ->
            val missingFrom = buildList {
                if (!onCreate.contains("$function(db)")) add("onCreate")
                if (!onUpgrade.contains("$function(db)")) add("onUpgrade")
            }
            if (missingFrom.isNotEmpty()) {
                missing += "$function is not called from ${missingFrom.joinToString(" or ")}"
            }
        }

        assertTrue(
            "A table missing from a creation path crashes on first use:\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }
}
