package io.github.ytdlpandroid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against the host-and-section recursion that crashed the app.
 *
 * The bug: `SearchSection.clear()` emptied its field and called `host.clearSearch()`, whose
 * implementation called `searchSection.clear()` back. Neither side had a terminating
 * condition, so tapping the X button overflowed the stack. The pattern is general to every
 * extracted screen, so it is checked structurally here rather than trusted to review: a Host
 * method may not call a section method that calls back into the host.
 */
class HostCycleTest {

    @Test
    fun `no host method calls a section method that calls back into the host`() {
        val activity = SourceFixtures.source("io/github/ytdlpandroid/MainActivity.kt")
        assertTrue("MainActivity.kt not found", activity.isFile)
        val activitySource = activity.readText()

        val hostBlocks = Regex("""object\s*:\s*(\w+)\.Host\s*\{""").findAll(activitySource)
        assertTrue("no Host implementations found in MainActivity.kt", hostBlocks.any())

        val violations = mutableListOf<String>()
        hostBlocks.forEach { hostMatch ->
            val hostType = hostMatch.groupValues[1]
            val hostBody = SourceFixtures.balancedBlock(activitySource, hostMatch.range.last)

            // The section instance this host belongs to, e.g. searchSection.
            val instance = hostType.replaceFirstChar { it.lowercaseChar() }

            // Methods of that section which call back into the host. Reading the section's
            // own file is what makes the check transitive rather than only two calls deep.
            val sectionFile = SourceFixtures.source("io/github/ytdlpandroid/$hostType.kt")
            if (!sectionFile.isFile) return@forEach
            val sectionCallsBack = SourceFixtures.methodsCalling(sectionFile.readText(), "host.")

            SourceFixtures.methodsCalling(hostBody, "$instance.")
                .filter { it in sectionCallsBack }
                .forEach { method ->
                    violations += "$hostType.Host calls $instance.$method(), " +
                        "which calls back into $hostType.Host: infinite recursion"
                }
        }

        assertTrue(
            "Host/section recursion found:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
