package io.github.ytdlpandroid

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "From YouTube" section: tracks captured from other apps' media sessions, each with a
 * download and a remove affordance.
 *
 * Extracted from MainActivity. It owns its own entry list and view tree, and reaches the host
 * through [Host] for three things only: whether notification access is granted, the list
 * source, and a way to enqueue downloads. Rendering stays synchronous so a snapshot or a
 * refresh can never leave a half-drawn list.
 */
internal class NowPlayingSection(
    private val context: Context,
    private val host: Host,
) {
    /** Everything this section needs from its host. */
    interface Host {
        /** Reads the granted flag on demand so a settings change is picked up on resume. */
        fun isCaptureGranted(): Boolean

        suspend fun loadCapturedEntries(): List<NowPlayingEntry>
        suspend fun removeCapturedEntry(id: Long): Boolean
        suspend fun clearCapturedHistory(): Boolean
        suspend fun saveResolvedEntry(entry: NowPlayingEntry)

        /** Opens the system screen where notification access is granted. */
        fun openListenerSettings()

        /** Searches for one track and reports a resolved video id, or null on failure. */
        fun resolveVideoId(title: String, artist: String, onResolved: (String?) -> Unit)

        /** Enqueues a watch URL for download. */
        fun enqueueDownload(watchUrl: String)

        fun showSnackbar(message: String)
        fun showError(message: String)

        /** Runs [block] on a background dispatcher; the host owns cancellation and lifetime. */
        fun runBackground(block: suspend () -> Unit)

        /** Runs [block] on the main thread, dropping it when the host is gone. */
        fun runOnMain(block: () -> Unit)
    }

    private val entries = mutableListOf<NowPlayingEntry>()
    private lateinit var content: LinearLayout

    /** Serialises background loads so an older response cannot overwrite a newer one. */
    private var loadGeneration = 0

    /** True while an id resolution is in flight, so one query runs at a time. */
    private var resolving = false

    /** Adds the section to [root]. */
    fun attachTo(root: LinearLayout) {
        root.addView(
            sectionTitleView(context.getString(R.string.now_playing_section)),
            matchWidth(),
        )
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dp(6))
        }
        root.addView(content, matchWidth().apply { bottomMargin = context.dp(10) })
        render()
    }

    /** Reloads the captured list from the host, then redraws. */
    fun refresh() {
        loadGeneration++
        val generation = loadGeneration
        host.runBackground {
            val loaded = host.loadCapturedEntries()
            host.runOnMain {
                if (generation != loadGeneration) return@runOnMain
                entries.clear()
                entries.addAll(loaded)
                render()
            }
        }
    }

    fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        when {
            !host.isCaptureGranted() -> content.addView(enableCard(), matchWidth())

            entries.isEmpty() -> content.addView(
                emptyMessageView(context.getString(R.string.now_playing_empty)),
                matchWidth(),
            )

            else -> {
                entries.forEach { addCard(it) }
                content.addView(
                    Button(context).apply {
                        text = context.getString(R.string.now_playing_clear)
                        context.styleActionButton(this, false)
                        setOnClickListener { confirmClearHistory() }
                    },
                    matchWidth().apply { topMargin = context.dp(8) },
                )
                resolvePendingVideoIds()
            }
        }
    }

    private fun enableCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_SURFACE, context.dp(12))
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
        }
        card.addView(TextView(context).apply {
            text = context.getString(R.string.now_playing_enable_title)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 0, context.dp(4))
        })
        card.addView(TextView(context).apply {
            text = context.getString(R.string.now_playing_enable_body)
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(0, 0, 0, context.dp(10))
        })
        card.addView(
            Button(context).apply {
                text = context.getString(R.string.now_playing_enable_action)
                context.styleActionButton(this, true)
                setOnClickListener { host.openListenerSettings() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(44),
            ),
        )
        return card
    }

    private fun addCard(entry: NowPlayingEntry) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_SURFACE, context.dp(8))
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        }
        val artwork = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(COLOR_BACKGROUND, context.dp(6))
            contentDescription = context.getString(R.string.track_artwork)
        }
        // Only a resolved video id yields an i.ytimg.com thumbnail, which is also the only
        // remote host the image loader trusts; unresolved entries keep the placeholder.
        val resolvedId = entry.videoId.takeIf { !it.startsWith(PENDING_KEY_PREFIX) }
        artwork.loadTrustedThumbnail(resolvedId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" })
        row.addView(artwork, LinearLayout.LayoutParams(context.dp(52), context.dp(52)))

        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(context).apply {
            text = entry.title
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        })
        labels.addView(TextView(context).apply {
            text = entry.artist.ifBlank { context.getString(R.string.now_playing_source_other) }
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(12)
        })
        row.addView(
            context.iconButton(
                iconRes = R.drawable.ic_download,
                description = context.getString(R.string.now_playing_download),
                tint = COLOR_ACCENT,
                sizeDp = 44,
                insetDp = 11,
            ) { download(entry) },
            LinearLayout.LayoutParams(context.dp(44), context.dp(44)),
        )
        row.addView(
            context.iconButton(
                iconRes = R.drawable.ic_close,
                description = context.getString(R.string.now_playing_clear),
                tint = COLOR_MUTED,
                sizeDp = 44,
                insetDp = 11,
            ) { remove(entry) },
            LinearLayout.LayoutParams(context.dp(44), context.dp(44)).apply {
                marginStart = context.dp(2)
            },
        )
        content.addView(row, matchWidth().apply { topMargin = context.dp(6) })
    }

    /**
     * Captured entries are stored under a title-derived key because the media session does not
     * expose the video id. Resolving one real id via a single-result search turns the entry
     * into a downloadable link and unlocks artwork.
     */
    private fun resolvePendingVideoIds() {
        if (resolving) return
        val pending = entries.firstOrNull { it.videoId.startsWith(PENDING_KEY_PREFIX) } ?: return
        resolving = true
        host.resolveVideoId(pending.title, pending.artist) { videoId ->
            resolving = false
            if (videoId == null) {
                render()
                return@resolveVideoId
            }
            val resolved = pending.copy(videoId = videoId)
            host.runBackground { host.saveResolvedEntry(resolved) }
            val index = entries.indexOf(pending)
            if (index >= 0) entries[index] = resolved
            render()
        }
    }

    private fun download(entry: NowPlayingEntry) {
        if (!entry.videoId.startsWith(PENDING_KEY_PREFIX)) {
            host.enqueueDownload(entry.watchUrl)
            host.showError(context.getString(R.string.now_playing_download_queued, entry.title))
            return
        }
        // Not resolved yet: find the video id now, then enqueue the real watch URL.
        host.resolveVideoId(entry.title, entry.artist) { videoId ->
            if (videoId == null) {
                host.showError(
                    context.getString(R.string.now_playing_search_failed, entry.title),
                )
            } else {
                host.enqueueDownload("https://www.youtube.com/watch?v=$videoId")
                host.showError(
                    context.getString(R.string.now_playing_download_queued, entry.title),
                )
            }
        }
    }

    private fun remove(entry: NowPlayingEntry) {
        host.runBackground {
            val removed = host.removeCapturedEntry(entry.id)
            host.runOnMain {
                if (removed) {
                    entries.remove(entry)
                    render()
                } else {
                    host.showError(context.getString(R.string.now_playing_remove_failed))
                }
            }
        }
    }

    private fun confirmClearHistory() {
        if (entries.isEmpty()) return
        android.app.AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.now_playing_clear_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.now_playing_clear) { _, _ ->
                host.runBackground {
                    val cleared = host.clearCapturedHistory()
                    host.runOnMain {
                        if (cleared) {
                            entries.clear()
                            render()
                        } else {
                            host.showError(context.getString(R.string.now_playing_clear_failed))
                        }
                    }
                }
            }
            .show()
    }

    private fun sectionTitleView(value: String) = TextView(context).apply {
        text = value
        textSize = 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(COLOR_TEXT)
        setPadding(0, context.dp(4), 0, context.dp(8))
    }

    private fun emptyMessageView(message: String) = TextView(context).apply {
        text = message
        textSize = 14f
        setTextColor(COLOR_MUTED)
        setPadding(context.dp(4), context.dp(10), context.dp(4), context.dp(10))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        /** Marks an entry captured before its video id was resolved. */
        const val PENDING_KEY_PREFIX = "key:"
    }
}
