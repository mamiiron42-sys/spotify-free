package io.github.ytdlpandroid

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "Queue" section of the queue screen: what is playing now and what is next, with
 * reorder, remove, and clear-upcoming.
 *
 * Extracted from MainActivity because this is a self-contained screen section that only
 * needs three things from its host: the bound playback service, the latest snapshot, and a
 * way to report messages. Everything else it uses is either a UiKit Context helper or its
 * own state, which keeps the boundary small and testable.
 */
internal class PlaybackQueueSection(
    private val context: Context,
    private val host: Host,
) {
    /** The narrow surface this section consumes from its host. */
    interface Host {
        val playbackService: PlaybackService?
        val playbackSnapshot: PlaybackSnapshot?

        fun showSnackbar(message: String)
        fun showError(message: String)
    }

    private lateinit var list: LinearLayout

    /** Last signature drawn, so a position tick cannot force a re-render. */
    private var renderedSignature: String? = null

    /** Adds the section to [root]. */
    fun attachTo(root: LinearLayout) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                sectionTitleView(context.getString(R.string.playback_queue_title)),
                matchWidth(),
            )
        }
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        container.addView(list, matchWidth())
        root.addView(container, matchWidth().apply { bottomMargin = context.dp(8) })
        render()
    }

    /**
     * Rebuilds the list only when the queue or the current track changed. The snapshot
     * fires every second with a position update, and re-rendering on each one would reset
     * scroll position and fight the user's reorder taps.
     */
    fun refreshIfChanged() {
        if (!::list.isInitialized) return
        if (signature() == renderedSignature) return
        render()
    }

    fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val snapshot = host.playbackSnapshot
        val queue = host.playbackService?.getQueue().orEmpty()
        if (queue.isEmpty()) {
            list.addView(
                emptyMessageView(context.getString(R.string.playback_queue_empty)),
                matchWidth(),
            )
            return
        }
        queue.forEachIndexed { index, track ->
            val isCurrent = index == snapshot?.index
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (isCurrent) background = roundedBackground(COLOR_CARD, context.dp(8))
                setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = track.title
                setOnClickListener { host.playbackService?.playQueueIndex(index) }
            }
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(context).apply {
                text = track.title
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isCurrent) COLOR_ACCENT else COLOR_TEXT)
            })
            labels.addView(TextView(context).apply {
                text = track.artist.ifBlank { formatMillis(track.durationMs) }
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(COLOR_MUTED)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(4)
            })
            if (index > 0) {
                row.addView(
                    reorderButton(index, index - 1, context.getString(R.string.move_up)),
                    LinearLayout.LayoutParams(context.dp(44), context.dp(44)),
                )
            }
            if (index < queue.lastIndex) {
                // Same chevron, flipped so it points down.
                val moveDown = reorderButton(index, index + 1, context.getString(R.string.move_down))
                moveDown.rotation = 180f
                row.addView(moveDown, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
            }
            row.addView(
                context.iconButton(
                    iconRes = R.drawable.ic_close,
                    description = context.getString(R.string.remove_from_queue, track.title),
                    tint = COLOR_MUTED,
                    sizeDp = 44,
                    insetDp = 11,
                ) { removeAt(index, track.title) },
                LinearLayout.LayoutParams(context.dp(44), context.dp(44)),
            )
            list.addView(row, matchWidth())
        }
        list.addView(
            Button(context).apply {
                text = context.getString(R.string.clear_upcoming)
                context.styleActionButton(this, false)
                setOnClickListener {
                    host.playbackService?.clearUpcoming()
                    render()
                }
            },
            matchWidth().apply { topMargin = context.dp(10) },
        )
        // Keep the signature in step with what was just drawn, so the snapshot a mutation
        // publishes does not immediately re-render and reset scroll position.
        renderedSignature = signature()
    }

    private fun reorderButton(fromIndex: Int, toIndex: Int, description: String) =
        context.iconButton(
            iconRes = R.drawable.ic_chevron_down,
            description = description,
            tint = COLOR_MUTED,
            sizeDp = 44,
            insetDp = 11,
        ) {
            val service = host.playbackService ?: return@iconButton
            if (!service.moveQueueItem(fromIndex, toIndex)) {
                host.showError(context.getString(R.string.playback_queue_move_failed))
                return@iconButton
            }
            render()
        }

    private fun removeAt(index: Int, title: String) {
        val service = host.playbackService ?: return
        val removed = service.removeFromQueue(index)
        render()
        if (!removed) {
            host.showError(context.getString(R.string.playback_queue_remove_failed))
            return
        }
        host.showSnackbar(context.getString(R.string.removed_from_queue))
    }

    /**
     * Queue contents plus the current index. Track changes leave the queue identical, so an
     * index-less signature would leave the highlighted row stale.
     */
    private fun signature(): String? {
        val service = host.playbackService ?: return null
        val uris = service.getQueue().map { it.uri }
        if (uris.isEmpty()) return ""
        return uris.joinToString("\u0000") + "\u0000#" + (host.playbackSnapshot?.index ?: -1)
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

    private fun formatMillis(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0L) / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun LinearLayout.LayoutParams.apply(block: LinearLayout.LayoutParams.() -> Unit) =
        also(block)

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
}
