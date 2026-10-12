package io.github.ytdlpandroid

import java.io.File

/**
 * Source helpers shared by the structural tests.
 *
 * These tests read the app's own source instead of driving Android, because the bug classes
 * they guard - a host/section recursion and a table missing from a creation path - are
 * failures of wiring rather than behavior, and both otherwise surface only as a crash you
 * find by installing. Parsing here keeps that check in the JVM test task, where a build
 * fails instead of a launch.
 */
internal object SourceFixtures {

    /** Resolves a main-source-set path, overridable so the tests are not cwd-dependent. */
    fun source(relative: String): File =
        File(System.getProperty("screenSourceRoot") ?: "src/main/java", relative)

    /** The brace-matched body starting at [open], never truncated by a nested brace. */
    fun balancedBlock(source: String, open: Int): String {
        if (open < 0 || open >= source.length || source[open] != '{') return ""
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        return ""
    }

    /** The body of [member] such as "override fun onCreate(", matched anywhere in [source]. */
    fun bodyOf(source: String, member: String): String {
        val declaration = source.indexOf(member)
        if (declaration < 0) return ""
        return balancedBlock(source, source.indexOf('{', declaration))
    }

    /**
     * Every `fun <name>(...)` body in [source] that calls `[receiver]<something>(`. Used to
     * ask "does this function reach back into its host or a section", which is what makes the
     * cycle check transitive.
     */
    fun methodsCalling(source: String, receiver: String): Set<String> {
        val result = mutableSetOf<String>()
        Regex("""fun\s+(\w+)\s*\(""").findAll(source).forEach { match ->
            val body = balancedBlock(source, source.indexOf('{', match.range.last))
            if (body.contains(Regex("""\Q$receiver\E\w+\s*\("""))) {
                result += match.groupValues[1]
            }
        }
        return result
    }
}
