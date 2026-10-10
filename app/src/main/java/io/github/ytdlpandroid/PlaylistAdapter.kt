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
 * A single row of the Playlists page.
 *
 * Messages and the "clear search" affordance are modelled as rows rather than imperatively added
 * views so the whole screen is a pure function of `PlaylistViewModel` state and `DiffUtil` can skip
 * the cards that a refresh did not touch.
 */
sealed interface PlaylistRow {
    /** Empty-state / no-match / error message. Spans the full grid width. */
    data class Message(val text: String) : PlaylistRow

    /** "Clear search" button shown under a no-match message when a filter is active. Full width. */
    data object ClearSearch : PlaylistRow

    /**
     * @param cover the resolved artwork track for the card thumbnail, or `null` when none of the
     *   playlist's URIs resolve. Resolved by `MainActivity` (which owns the library/metadata lookup)
     *   so the adapter needs no Activity or store reference (Requirement 5.11).
     */
    data class Playlist(
        val playlist: SavedPlaylist,
        val cover: AudioTrack?,
    ) : PlaylistRow
}

/**
 * Adapter for the single-column Playlists page (Requirement 5.3).
 *
 * Holds no Activity reference (Requirement 5.11); every interaction is routed through [Callbacks],
 * which `MainActivity` implements.
 */
internal class PlaylistAdapter(
    private val callbacks: Callbacks,
) : ListAdapter<PlaylistRow, RecyclerView.ViewHolder>(DIFF) {

    interface Callbacks {
        fun onPlaylistOpen(playlist: SavedPlaylist)
        fun onPlaylistMenu(playlist: SavedPlaylist, anchor: View)
        fun onPlaylistClearSearch()
    }


    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is PlaylistRow.Playlist -> TYPE_PLAYLIST
        is PlaylistRow.Message -> TYPE_MESSAGE
        PlaylistRow.ClearSearch -> TYPE_CLEAR_SEARCH
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

            TYPE_CLEAR_SEARCH -> ClearSearchHolder(
                Button(context).apply {
                    text = context.getString(R.string.clear_search)
                    context.styleActionButton(this, false)
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                },
                callbacks,
            )

            else -> PlaylistHolder(buildPlaylistCard(context), callbacks)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is PlaylistRow.Playlist -> (holder as PlaylistHolder).bind(row)
            is PlaylistRow.Message -> (holder as MessageHolder).bind(row.text)
            PlaylistRow.ClearSearch -> Unit
        }
    }

    private fun buildPlaylistCard(context: android.content.Context): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, context.dp(14))
            setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(8))
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            ImageView(context).apply {
                tag = TAG_ARTWORK
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBackground(COLOR_SURFACE, context.dp(8))
                setImageResource(android.R.drawable.ic_media_play)
            },
            LinearLayout.LayoutParams(context.dp(56), context.dp(56)).apply {
                marginEnd = context.dp(10)
            },
        )
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(
            TextView(context).apply {
                tag = TAG_NAME
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            },
        )
        labels.addView(
            TextView(context).apply {
                tag = TAG_COUNT
                textSize = 12f
                setTextColor(COLOR_MUTED)
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(3) },
        )
        header.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(
            with(context) {
                iconButton(
                    iconRes = R.drawable.ic_more_vert,
                    description = getString(R.string.more_options),
                    tint = COLOR_MUTED,
                    sizeDp = 44,
                )
            }.apply { tag = TAG_MENU },
            LinearLayout.LayoutParams(context.dp(44), context.dp(44)).apply {
                marginStart = context.dp(4)
            },
        )
        card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        return card
    }

    private class PlaylistHolder(
        private val card: LinearLayout,
        private val callbacks: Callbacks,
    ) : RecyclerView.ViewHolder(card) {
        private val context = card.context
        private val artwork = card.findViewWithTag<ImageView>(TAG_ARTWORK)
        private val name = card.findViewWithTag<TextView>(TAG_NAME)
        private val count = card.findViewWithTag<TextView>(TAG_COUNT)
        private val menuButton = card.findViewWithTag<ImageView>(TAG_MENU)

        fun bind(row: PlaylistRow.Playlist) {
            val playlist = row.playlist
            // Tag preserved so MainActivity's post-mutation success animation can still find the
            // card via `playlistList.findViewWithTag("playlist-$id")`.
            card.tag = "playlist-${playlist.id}"
            card.contentDescription = context.getString(
                R.string.playlist_card_accessibility,
                playlist.name,
                playlist.tracks.size,
            )
            val isLikedSongs = playlist.name.equals(context.getString(R.string.liked_songs), ignoreCase = true)
            val cover = row.cover
            if (isLikedSongs) {
                artwork.setImageDrawable(null)
                artwork.background = likedSongsGradient(context.dp(8).toFloat())
                artwork.contentDescription = playlist.name
            } else if (cover != null) {
                artwork.background = roundedBackground(COLOR_SURFACE, context.dp(8))
                val bitmap = cover.artwork
                if (bitmap != null) {
                    artwork.setImageBitmap(bitmap)
                } else {
                    artwork.setImageResource(android.R.drawable.ic_media_play)
                    artwork.loadTrustedThumbnail(cover.thumbnailUrl)
                }
                artwork.contentDescription = context.getString(
                    R.string.track_artwork_description,
                    cover.title,
                    cover.artist,
                )
            } else {
                artwork.background = roundedBackground(COLOR_SURFACE, context.dp(8))
                artwork.setImageResource(android.R.drawable.ic_media_play)
                artwork.contentDescription = null
            }
            name.text = playlist.name
            count.text = context.getString(R.string.playlist_track_count, playlist.tracks.size)
            menuButton.contentDescription =
                context.getString(R.string.playlist_actions_accessibility, playlist.name)
            menuButton.setOnClickListener { callbacks.onPlaylistMenu(playlist, it) }
            card.setOnClickListener { callbacks.onPlaylistOpen(playlist) }
        }
    }

    private class MessageHolder(
        private val textView: TextView,
    ) : RecyclerView.ViewHolder(textView) {
        fun bind(text: String) {
            textView.text = text
        }
    }

    private class ClearSearchHolder(
        button: Button,
        callbacks: Callbacks,
    ) : RecyclerView.ViewHolder(button) {
        init {
            button.setOnClickListener { callbacks.onPlaylistClearSearch() }
        }
    }

    private companion object {
        const val TYPE_PLAYLIST = 0
        const val TYPE_MESSAGE = 1
        const val TYPE_CLEAR_SEARCH = 2

        const val TAG_ARTWORK = "playlist-artwork"
        const val TAG_NAME = "playlist-name"
        const val TAG_COUNT = "playlist-count"
        const val TAG_MENU = "playlist-menu"

        val DIFF = object : DiffUtil.ItemCallback<PlaylistRow>() {
            override fun areItemsTheSame(oldItem: PlaylistRow, newItem: PlaylistRow): Boolean =
                when {
                    oldItem is PlaylistRow.Playlist && newItem is PlaylistRow.Playlist ->
                        oldItem.playlist.id == newItem.playlist.id

                    oldItem is PlaylistRow.Message && newItem is PlaylistRow.Message ->
                        oldItem.text == newItem.text

                    oldItem is PlaylistRow.ClearSearch && newItem is PlaylistRow.ClearSearch -> true
                    else -> false
                }

            // SavedPlaylist is a pure data class (id, name, List<String>) so it diffs cheaply by
            // value. The resolved `cover` AudioTrack carries a Bitmap compared by identity, so a
            // refresh that re-resolves covers rebinds those cards — the same net effect as the old
            // removeAllViews renderer had.
            override fun areContentsTheSame(oldItem: PlaylistRow, newItem: PlaylistRow): Boolean =
                oldItem == newItem
        }
    }
}
