package io.github.ytdlpandroid

/**
 * The queries a user has searched for recently.
 *
 * Kept separate from the UI and from storage so the rules - most recent first, no
 * duplicates, case-insensitive and whitespace-trimmed identity, a hard cap on size - can be
 * tested without Android. A persisted list would otherwise be the only place those rules
 * existed, which is how a recent-searches list ends up growing forever or showing the same
 * query twice.
 */
internal object RecentSearches {

    /** How many queries are remembered. Long enough to be useful, short enough to scan. */
    const val LIMIT = 12

    /**
     * Puts [query] at the front of [existing], dropping any previous entry for the same query
     * and trimming the tail to [LIMIT].
     *
     * A blank query is ignored rather than stored: it is a cleared field, not something the
     * user meant to search for.
     */
    fun remember(existing: List<String>, query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return existing.take(LIMIT)
        val key = trimmed.lowercase()
        return (listOf(trimmed) + existing.filterNot { it.trim().lowercase() == key })
            .take(LIMIT)
    }

    /** The queries worth showing, most recent first, with duplicates and blanks removed. */
    fun normalize(existing: List<String>): List<String> =
        existing.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(LIMIT)
}
