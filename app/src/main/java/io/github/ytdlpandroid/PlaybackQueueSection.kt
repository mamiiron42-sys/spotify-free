package io.github.ytdlpandroid

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * The "Queue" section of the queue screen: what is playing now and what is next, with
 * reorder, remove, and clear-upcoming.
 *
 * Extracted from MainActivity because this is a self-contained screen section that only
 * needs three things from its host: the bound playback service, the latest snapshot, and a
 * way to report messages. Everything else it uses is either a UiKit Context helper or its
 * own state, which keeps the boundary small and testable.
 *
 * Rows live in a `RecyclerView` rather than a `LinearLayout` so an `ItemTouchHelper` can
 * take over the gestures: long-press to drag a row into a new position, swipe to remove it.
 * The up/down chevrons stay on every row as the accessible alternative, and every mutation
 * still goes through the service, so the stored order and the visible order are the same
 * thing and the service stays the source of truth.
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

    /**
     * A queue entry plus whether it is the track playing now. Carrying the highlight in
     * the row means DiffUtil only rebinds the rows whose highlight changed, instead of the
     * whole list, every time playback moves on to the next track.
     */
    private data class QueueRow(
        val track: PlaybackTrack,
        val isCurrent: Boolean,
    )

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var clearButton: Button
    private val queueAdapter = QueueAdapter()

    /** Last signature drawn, so a position tick cannot force a re-render. */
    private var renderedSignature: String? = null

    /**
     * True from the start of a drag or swipe until it settles. While it is set the adapter
     * is deliberately ahead of the service - the rows have already moved under the user's
     * finger - so a snapshot refresh must not re-bind the list underneath them.
     */
    private var interactingWithRow = false

    /** Refresh that arrived while [interactingWithRow] was set, replayed once it settles. */
    private var refreshPending = false

    /** Adapter position the drag started from, reported to the service when the drag ends. */
    private var dragFromIndex = RecyclerView.NO_POSITION

    /** Queue URI of the dragged row, which is what survives the reordering mid-drag. */
    private var draggedUri: String? = null

    /** Adds the section to [root]. */
    fun attachTo(root: LinearLayout) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                sectionTitleView(context.getString(R.string.playback_queue_title)),
                matchWidth(),
            )
        }
        emptyView = emptyMessageView(context.getString(R.string.playback_queue_empty)).apply {
            visibility = View.GONE
        }
        container.addView(emptyView, matchWidth())
        recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            // Lives inside the queue screen's ScrollView, which owns scrolling; same setup
            // as the search results list.
            isNestedScrollingEnabled = false
            itemAnimator = DefaultItemAnimator()
            adapter = queueAdapter
        }
        ItemTouchHelper(QueueTouchCallback()).attachToRecyclerView(recycler)
        container.addView(recycler, matchWidth())
        clearButton = Button(context).apply {
            text = context.getString(R.string.clear_upcoming)
            context.styleActionButton(this, false)
            setOnClickListener {
                host.playbackService?.clearUpcoming()
                render()
            }
        }
        container.addView(clearButton, matchWidth().apply { topMargin = context.dp(10) })
        root.addView(container, matchWidth().apply { bottomMargin = context.dp(8) })
        render()
    }

    /**
     * Renders the list only when the queue or the current track changed. The snapshot fires
     * every second with a position update, and re-rendering on each one would reset scroll
     * position and fight the user's drag.
     */
    fun refreshIfChanged() {
        if (!::recycler.isInitialized) return
        if (signature() == renderedSignature) return
        if (interactingWithRow) {
            // A drag or swipe is in flight: replay this once the row settles.
            refreshPending = true
            return
        }
        render()
    }

    fun render() {
        if (!::recycler.isInitialized) return
        val snapshot = host.playbackSnapshot
        val queue = host.playbackService?.getQueue().orEmpty()
        val currentIndex = snapshot?.index ?: -1
        if (queue.isEmpty()) {
            submitQueue(emptyList())
            emptyView.visibility = View.VISIBLE
            recycler.visibility = View.GONE
            clearButton.visibility = View.GONE
            renderedSignature = signature()
            return
        }
        emptyView.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        clearButton.visibility = View.VISIBLE
        submitQueue(queue.mapIndexed { index, track -> QueueRow(track, index == currentIndex) })
        // Keep the signature in step with what was just drawn, so the snapshot a mutation
        // publishes does not immediately re-render and reset scroll position.
        renderedSignature = signature()
    }

    /** Submits [rows] to the adapter, which diffs them instead of rebuilding the list. */
    private fun submitQueue(rows: List<QueueRow>) {
        queueAdapter.submitList(rows)
    }

    /** The queue URI shown at [index] in the rows, or null when there is no such row. */
    private fun uriAt(index: Int): String? =
        queueAdapter.currentList.getOrNull(index)?.track?.uri

    private fun playAt(index: Int) {
        if (index == RecyclerView.NO_POSITION) return
        host.playbackService?.playQueueIndex(index)
    }

    /**
     * Moves a row with the up/down chevrons. The queue is reordered locally first so the row
     * lands where the user tapped it; the service then publishes the same order, so the
     * refresh its snapshot triggers differs to nothing.
     */
    private fun reorder(fromIndex: Int, toIndex: Int) {
        if (fromIndex == RecyclerView.NO_POSITION) return
        val rows = queueAdapter.currentList
        if (fromIndex !in rows.indices || toIndex !in rows.indices) return
        val service = host.playbackService
        if (service == null || !service.moveQueueItem(fromIndex, toIndex)) {
            if (service != null) host.showError(context.getString(R.string.playback_queue_move_failed))
            // Rejected: put the stored order back on screen.
            render()
            return
        }
        submitQueue(rows.toMutableList().apply { add(toIndex, removeAt(fromIndex)) })
    }

    /**
     * Closes out a drag. [dragFromIndex] is where the dragged row started and [draggedUri]
     * identifies it, so its ending position is read from the rows the user sees. Both are
     * current list positions and the service owns the same order, so asking it for the same
     * move keeps stored and visible order aligned while the current track's highlight
     * follows its row.
     */
    private fun finishDrag() {
        val fromIndex = dragFromIndex
        val uri = draggedUri
        dragFromIndex = RecyclerView.NO_POSITION
        draggedUri = null
        if (fromIndex == RecyclerView.NO_POSITION || uri == null) return
        val toIndex = queueAdapter.currentList.indexOfFirst { it.track.uri == uri }
        // Dropped back where it started, or gone: nothing to ask the service for.
        if (toIndex == RecyclerView.NO_POSITION || toIndex == fromIndex) return
        val service = host.playbackService
        if (service == null || !service.moveQueueItem(fromIndex, toIndex)) {
            if (service != null) host.showError(context.getString(R.string.playback_queue_move_failed))
            render()
            return
        }
        // The rows already show the dragged order; commit that list so the UI does not fight
        // the order the service just stored.
        submitQueue(queueAdapter.currentList.toList())
    }

    /**
     * Removes the row at [index]. The row leaves immediately, matching the swipe, and the
     * service's snapshot re-renders the stored order - the same order - over it. A rejected
     * removal restores the row from the stored order instead of showing an error over a gap.
     */
    private fun removeAt(index: Int) {
        if (index == RecyclerView.NO_POSITION) return
        val rows = queueAdapter.currentList.toMutableList()
        if (index !in rows.indices) return
        rows.removeAt(index)
        submitQueue(rows)
        val service = host.playbackService
        if (service == null) {
            render()
            return
        }
        if (!service.removeFromQueue(index)) {
            render()
            host.showError(context.getString(R.string.playback_queue_remove_failed))
            return
        }
        host.showSnackbar(context.getString(R.string.removed_from_queue))
    }

    /**
     * Returns from a drag or a swipe. The deferred refresh is re-checked rather than drawn
     * straight away: the signature is still the one the rows were drawn from, so nothing has
     * to be rebuilt yet, and the mutation the gesture just published re-renders on its own.
     */
    private fun settleRowInteraction() {
        dragFromIndex = RecyclerView.NO_POSITION
        draggedUri = null
        interactingWithRow = false
        if (!refreshPending) return
        refreshPending = false
        refreshIfChanged()
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

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    /** Builds one row: title/artist, reorder chevrons, and the remove button. */
    private fun buildQueueRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        }
        val title = TextView(context).apply {
            tag = TAG_TITLE
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        }
        val artist = TextView(context).apply {
            tag = TAG_ARTIST
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
        }
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(title)
        labels.addView(artist)
        row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = context.dp(4)
        })
        row.addView(
            context.iconButton(
                iconRes = R.drawable.ic_chevron_down,
                description = context.getString(R.string.move_up),
                tint = COLOR_MUTED,
                sizeDp = 44,
                insetDp = 11,
            ) {}.apply { tag = TAG_MOVE_UP },
            LinearLayout.LayoutParams(context.dp(44), context.dp(44)),
        )
        // Same chevron, flipped so it points down.
        row.addView(
            context.iconButton(
                iconRes = R.drawable.ic_chevron_down,
                description = context.getString(R.string.move_down),
                tint = COLOR_MUTED,
                sizeDp = 44,
                insetDp = 11,
            ) {}.apply {
                tag = TAG_MOVE_DOWN
                rotation = 180f
            },
            LinearLayout.LayoutParams(context.dp(44), context.dp(44)),
        )
        row.addView(
            context.iconButton(
                iconRes = R.drawable.ic_close,
                description = "",
                tint = COLOR_MUTED,
                sizeDp = 44,
                insetDp = 11,
            ) {}.apply { tag = TAG_REMOVE },
            LinearLayout.LayoutParams(context.dp(44), context.dp(44)),
        )
        return row
    }

    private inner class QueueAdapter :
        ListAdapter<QueueRow, RowHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(buildQueueRow())

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    /**
     * One queue row. The click handlers read the adapter position when they run rather than
     * capturing it, so a row that moved still acts on where it is now.
     */
    private inner class RowHolder(row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val title = row.findViewWithTag<TextView>(TAG_TITLE)
        private val artist = row.findViewWithTag<TextView>(TAG_ARTIST)
        private val moveUp = row.findViewWithTag<ImageView>(TAG_MOVE_UP)
        private val moveDown = row.findViewWithTag<ImageView>(TAG_MOVE_DOWN)
        private val remove = row.findViewWithTag<ImageView>(TAG_REMOVE)

        init {
            row.setOnClickListener { playAt(bindingAdapterPosition) }
            moveUp.setOnClickListener { reorder(bindingAdapterPosition, bindingAdapterPosition - 1) }
            moveDown.setOnClickListener { reorder(bindingAdapterPosition, bindingAdapterPosition + 1) }
            remove.setOnClickListener { removeAt(bindingAdapterPosition) }
        }

        fun bind(item: QueueRow) {
            val isCurrent = item.isCurrent
            itemView.background = if (isCurrent) roundedBackground(COLOR_CARD, context.dp(8)) else null
            itemView.contentDescription = item.track.title
            title.text = item.track.title
            title.setTextColor(if (isCurrent) COLOR_ACCENT else COLOR_TEXT)
            artist.text = item.track.artist.ifBlank { formatMillis(item.track.durationMs) }
            moveUp.visibility = if (bindingAdapterPosition > 0) View.VISIBLE else View.GONE
            moveDown.visibility =
                if (bindingAdapterPosition < queueAdapter.itemCount - 1) View.VISIBLE else View.GONE
            remove.contentDescription =
                context.getString(R.string.remove_from_queue, item.track.title)
        }
    }

    /**
     * Drag up/down to reorder, swipe either way to remove. Long-press starts the drag, which
     * is the gesture Android users expect from a reorderable list.
     */
    private inner class QueueTouchCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        ItemTouchHelper.START or ItemTouchHelper.END,
    ) {
        override fun isLongPressDragEnabled() = true

        override fun isItemViewSwipeEnabled() = true

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                return false
            }
            val rows = queueAdapter.currentList
            if (from !in rows.indices || to !in rows.indices) return false
            // Move the rows under the dragged card now; the service hears about the move
            // once, when the drag settles.
            submitQueue(rows.toMutableList().apply { add(to, removeAt(from)) })
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            removeAt(viewHolder.bindingAdapterPosition)
            // A completed swipe does not always come back through clearView.
            settleRowInteraction()
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            when (actionState) {
                ItemTouchHelper.ACTION_STATE_DRAG -> {
                    dragFromIndex = viewHolder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                    draggedUri = viewHolder?.let { uriAt(dragFromIndex) }
                    interactingWithRow = true
                    viewHolder?.itemView?.elevation = context.dp(6).toFloat()
                }

                ItemTouchHelper.ACTION_STATE_SWIPE -> interactingWithRow = true
                else -> {
                    // ItemTouchHelper ends every gesture with onSelectedChanged(idle), and this
                    // runs while the adapter still shows the dragged rows, so a drag is applied
                    // here. A swipe is closed later, by onSwiped, so it settles there.
                    if (interactingWithRow && dragFromIndex != RecyclerView.NO_POSITION) {
                        finishDrag()
                    }
                    if (dragFromIndex == RecyclerView.NO_POSITION) settleRowInteraction()
                }
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.elevation = 0f
            // Fallback for a gesture that never reported an end: the rows the user sees are
            // still the dragged ones, so the move is made from here instead.
            if (interactingWithRow && dragFromIndex != RecyclerView.NO_POSITION) finishDrag()
            settleRowInteraction()
        }
    }

    private companion object {
        const val TAG_TITLE = "queue-row-title"
        const val TAG_ARTIST = "queue-row-artist"
        const val TAG_MOVE_UP = "queue-row-move-up"
        const val TAG_MOVE_DOWN = "queue-row-move-down"
        const val TAG_REMOVE = "queue-row-remove"

        /**
         * Identity is the track's URI and contents are the whole row, so only rows that
         * actually changed are rebound.
         */
        val DIFF = object : DiffUtil.ItemCallback<QueueRow>() {
            override fun areItemsTheSame(oldItem: QueueRow, newItem: QueueRow): Boolean =
                oldItem.track.uri == newItem.track.uri

            override fun areContentsTheSame(oldItem: QueueRow, newItem: QueueRow): Boolean =
                oldItem == newItem
        }
    }
}
