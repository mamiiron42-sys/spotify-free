package io.github.ytdlpandroid

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * A single row of the library track grid.
 *
 * Messages and group headings are modelled as rows rather than imperatively added views so
 * the whole screen is a pure function of `LibraryViewModel` state and `DiffUtil` can skip
 * the cards that a filter change did not touch.
 */
sealed interface LibraryRow {
    data class Message(val text: String) : LibraryRow

    data class Heading(val text: String) : LibraryRow

    /**
     * @param groupKey the on-screen group this card belongs to, or `null` in flat mode. The
     *   play queue is resolved from this at click time rather than carried in the row, so a
     *   group-mode change cannot leave a card bound to a stale queue and so diffing stays O(1).
     */
    data class Track(
        val track: AudioTrack,
        val groupKey: String?,
    ) : LibraryRow
}

/**
 * Adapter for the library track grid (Requirement 5.3).
 *
 * Holds no Activity reference (Requirement 5.11); every interaction is routed through
 * [Callbacks], which `MainActivity` implements.
 */
internal class LibraryAdapter(
    private val callbacks: Callbacks,
) : ListAdapter<LibraryRow, RecyclerView.ViewHolder>(DIFF) {

    interface Callbacks {
        fun onLibraryTrackPlay(track: AudioTrack, groupKey: String?)
        fun onLibraryTrackDetails(track: AudioTrack)
        fun onLibraryTrackMenu(track: AudioTrack, anchor: View)
    }

    /**
     * Cell width in pixels, used to size artwork. Mirrors the width the previous
     * `GridLayout` computed per card (`homeGridCardWidth`) so the grid renders identically.
     */
    var cellWidthPx: Int = 0

    fun spanSizeFor(position: Int, spanCount: Int): Int =
        if (getItemViewType(position) == TYPE_TRACK) 1 else spanCount

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is LibraryRow.Track -> TYPE_TRACK
        is LibraryRow.Message -> TYPE_MESSAGE
        is LibraryRow.Heading -> TYPE_HEADING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return when (viewType) {
            TYPE_MESSAGE -> MessageHolder(
                TextView(context).apply {
                    textSize = 14f
                    setTextColor(COLOR_MUTED)
                    setPadding(context.dp(4), context.dp(10), context.dp(4), context.dp(10))
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                },
            )

            TYPE_HEADING -> HeadingHolder(
                TextView(context).apply {
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(COLOR_TEXT)
                    setPadding(0, context.dp(4), 0, context.dp(8))
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                        topMargin = context.dp(10)
                    }
                },
            )

            else -> TrackHolder(buildTrackCard(context), callbacks)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is LibraryRow.Track -> (holder as TrackHolder).bind(row, cellWidthPx)
            is LibraryRow.Message -> (holder as MessageHolder).bind(row.text)
            is LibraryRow.Heading -> (holder as HeadingHolder).bind(row.text)
        }
    }

    private fun buildTrackCard(context: android.content.Context): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, context.dp(14))
            setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(8))
            isClickable = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(8))
            }
        }
        card.addView(
            ImageView(context).apply {
                tag = TAG_ARTWORK
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBackground(COLOR_SURFACE, context.dp(8))
                setImageResource(android.R.drawable.ic_media_play)
            },
            LinearLayout.LayoutParams(MATCH, context.dp(48)),
        )
        card.addView(
            TextView(context).apply {
                tag = TAG_TITLE
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
                setPadding(context.dp(2), context.dp(8), context.dp(2), 0)
            },
            LinearLayout.LayoutParams(MATCH, context.dp(45)),
        )
        val artistLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artistLine.addView(
            TextView(context).apply {
                tag = TAG_ARTIST
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_MUTED)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        artistLine.addView(
            with(context) {
                iconButton(
                    iconRes = R.drawable.ic_more_vert,
                    description = getString(R.string.more_options),
                    tint = COLOR_MUTED,
                    sizeDp = 48,
                )
            }.apply { tag = TAG_MENU },
            LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                marginStart = context.dp(3)
            },
        )
        card.addView(artistLine, LinearLayout.LayoutParams(MATCH, WRAP))
        return card
    }

    private class TrackHolder(
        private val card: LinearLayout,
        private val callbacks: Callbacks,
    ) : RecyclerView.ViewHolder(card) {
        private val context = card.context
        private val artwork = card.findViewWithTag<ImageView>(TAG_ARTWORK)
        private val title = card.findViewWithTag<TextView>(TAG_TITLE)
        private val artist = card.findViewWithTag<TextView>(TAG_ARTIST)
        private val menuButton = card.findViewWithTag<ImageView>(TAG_MENU)

        fun bind(row: LibraryRow.Track, cellWidthPx: Int) {
            val track = row.track
            if (cellWidthPx > 0) {
                artwork.layoutParams = artwork.layoutParams.apply {
                    height = maxOf(cellWidthPx - context.dp(14), context.dp(48))
                }
            }
            card.contentDescription = context.getString(
                R.string.home_track_accessibility,
                track.title,
                track.artist,
            )
            artwork.contentDescription = context.getString(
                R.string.track_artwork_description,
                track.title,
                track.artist,
            )
            val bitmap = track.artwork
            if (bitmap != null) {
                artwork.setImageBitmap(bitmap)
            } else {
                artwork.setImageResource(android.R.drawable.ic_media_play)
                artwork.loadTrustedThumbnail(track.thumbnailUrl)
            }
            title.text = track.title
            artist.text = track.artist
            menuButton.contentDescription =
                context.getString(R.string.track_actions_accessibility, track.title)
            menuButton.setOnClickListener { callbacks.onLibraryTrackMenu(track, it) }
            card.setOnClickListener {
                it.animateStateChange()
                callbacks.onLibraryTrackPlay(track, row.groupKey)
            }
            card.setOnLongClickListener {
                callbacks.onLibraryTrackDetails(track)
                true
            }
        }
    }

    private class MessageHolder(
        private val textView: TextView,
    ) : RecyclerView.ViewHolder(textView) {
        fun bind(text: String) {
            textView.text = text
        }
    }

    private class HeadingHolder(
        private val textView: TextView,
    ) : RecyclerView.ViewHolder(textView) {
        fun bind(text: String) {
            textView.text = text
        }
    }

    private companion object {
        const val TYPE_TRACK = 0
        const val TYPE_MESSAGE = 1
        const val TYPE_HEADING = 2

        const val TAG_ARTWORK = "library-artwork"
        const val TAG_TITLE = "library-title"
        const val TAG_ARTIST = "library-artist"
        const val TAG_MENU = "library-menu"

        val DIFF = object : DiffUtil.ItemCallback<LibraryRow>() {
            override fun areItemsTheSame(oldItem: LibraryRow, newItem: LibraryRow): Boolean =
                when {
                    oldItem is LibraryRow.Track && newItem is LibraryRow.Track ->
                        oldItem.track.uri.toString() == newItem.track.uri.toString()

                    oldItem is LibraryRow.Message && newItem is LibraryRow.Message ->
                        oldItem.text == newItem.text

                    oldItem is LibraryRow.Heading && newItem is LibraryRow.Heading ->
                        oldItem.text == newItem.text

                    else -> false
                }

            // AudioTrack is a data class whose Bitmap is compared by identity, so a full
            // library refresh (which decodes fresh bitmaps) rebinds every card — exactly
            // what the old removeAllViews renderer did. Filter/sort changes reuse the same
            // AudioTrack instances and therefore still diff cheaply.
            override fun areContentsTheSame(oldItem: LibraryRow, newItem: LibraryRow): Boolean =
                oldItem == newItem
        }
    }
}
