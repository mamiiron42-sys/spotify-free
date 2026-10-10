package io.github.ytdlpandroid

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.ytdlpandroid.viewmodel.SearchDownloadState
import io.github.ytdlpandroid.viewmodel.SearchDownloadStatus

/**
 * A single row of the search results grid.
 *
 * Status rows are modelled as rows rather than imperatively added views so the whole
 * screen is a pure function of `SearchViewModel` state and `DiffUtil` can tell a
 * prompt from a retry affordance.
 */
sealed interface SearchRow {
    data class Result(
        val item: MusicSearchResult,
        val download: SearchDownloadState?,
    ) : SearchRow

    /**
     * @param retryable renders the row as a tappable accent-coloured retry prompt.
     */
    data class Message(
        val text: String,
        val retryable: Boolean,
    ) : SearchRow

    data object LoadingFooter : SearchRow
}

/**
 * Adapter for the search results grid (Requirement 5.3).
 *
 * Holds no Activity reference (Requirement 5.11); every interaction is routed through
 * [Callbacks], which `MainActivity` implements.
 */
internal class SearchResultAdapter(
    private val callbacks: Callbacks,
) : ListAdapter<SearchRow, RecyclerView.ViewHolder>(DIFF) {

    interface Callbacks {
        fun onSearchResultMenu(item: MusicSearchResult, anchor: View)
        fun onSearchResultDownload(item: MusicSearchResult)
        fun onSearchDownloadResume(jobId: Long)
        fun onSearchRetryPage()
    }

    /**
     * Cell width in pixels, used to size artwork. Mirrors the width the previous
     * `GridLayout` computed per card so the grid renders identically.
     */
    var cellWidthPx: Int = 0

    fun spanSizeFor(position: Int, spanCount: Int): Int =
        if (getItemViewType(position) == TYPE_RESULT) 1 else spanCount

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchRow.Result -> TYPE_RESULT
        is SearchRow.Message -> TYPE_MESSAGE
        SearchRow.LoadingFooter -> TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return when (viewType) {
            TYPE_MESSAGE -> MessageHolder(
                TextView(context).apply {
                    textSize = 14f
                    setTextColor(COLOR_MUTED)
                    setPadding(context.dp(4), context.dp(10), context.dp(4), context.dp(10))
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                        topMargin = context.dp(6)
                        bottomMargin = context.dp(10)
                    }
                },
            )

            TYPE_FOOTER -> FooterHolder(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = RecyclerView.LayoutParams(MATCH, context.dp(48)).apply {
                        topMargin = context.dp(8)
                        bottomMargin = context.dp(8)
                    }
                    addView(
                        ProgressBar(context).apply {
                            isIndeterminate = true
                            indeterminateTintList =
                                android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
                        },
                        LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
                            marginEnd = context.dp(10)
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text = context.getString(R.string.loading_more_results)
                            setTextColor(COLOR_MUTED)
                            textSize = 13f
                        },
                    )
                },
            )

            else -> ResultHolder(buildResultCard(context), callbacks)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SearchRow.Result -> (holder as ResultHolder).bind(row, cellWidthPx)
            is SearchRow.Message -> (holder as MessageHolder).bind(row, callbacks)
            SearchRow.LoadingFooter -> Unit
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        // Requirement 5.7: a download-state change rebinds the action button only, so the
        // artwork is not re-requested and the card is not re-laid-out on every queue tick.
        val row = getItem(position)
        if (payloads.contains(DOWNLOAD_STATE_CHANGED) && row is SearchRow.Result) {
            (holder as ResultHolder).bindDownload(row.download)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun buildResultCard(context: android.content.Context): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, context.dp(14))
            setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(8))
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(8))
            }
        }
        val artwork = ImageView(context).apply {
            tag = TAG_ARTWORK
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(COLOR_SURFACE, context.dp(9))
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = context.getString(R.string.track_artwork)
        }
        card.addView(artwork, LinearLayout.LayoutParams(MATCH, context.dp(48)))
        card.addView(
            TextView(context).apply {
                tag = TAG_TITLE
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
                setPadding(context.dp(2), context.dp(8), context.dp(2), context.dp(2))
            },
            LinearLayout.LayoutParams(MATCH, context.dp(48)),
        )
        card.addView(
            TextView(context).apply {
                tag = TAG_UPLOADER
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_MUTED)
                setPadding(context.dp(2), 0, context.dp(2), context.dp(4))
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(View(context), LinearLayout.LayoutParams(0, context.dp(44), 1f))
        actions.addView(
            with(context) {
                iconButton(
                    iconRes = R.drawable.ic_more_vert,
                    description = getString(R.string.more_options),
                    tint = COLOR_MUTED,
                    sizeDp = 44,
                )
            }.apply { tag = TAG_MENU },
            LinearLayout.LayoutParams(context.dp(44), context.dp(44)).apply {
                marginEnd = context.dp(3)
            },
        )
        val downloadButton = with(context) {
            iconButton(
                iconRes = R.drawable.ic_download,
                description = getString(R.string.download_track),
                fill = COLOR_SURFACE,
                tint = COLOR_TEXT,
                sizeDp = 44,
            ) {}
        }
        val downloadSpinner = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleSmall,
        ).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            visibility = View.GONE
        }
        actions.addView(
            FrameLayout(context).apply {
                addView(downloadButton, FrameLayout.LayoutParams(context.dp(44), context.dp(44)))
                addView(
                    downloadSpinner,
                    FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER),
                )
            },
            LinearLayout.LayoutParams(context.dp(44), context.dp(44)),
        )
        card.addView(
            actions,
            LinearLayout.LayoutParams(MATCH, context.dp(44)).apply { topMargin = context.dp(2) },
        )
        return card
    }

    private class ResultHolder(
        private val card: LinearLayout,
        private val callbacks: Callbacks,
    ) : RecyclerView.ViewHolder(card) {
        private val context = card.context
        private val artwork = card.findViewWithTag<ImageView>(TAG_ARTWORK)
        private val title = card.findViewWithTag<TextView>(TAG_TITLE)
        private val uploader = card.findViewWithTag<TextView>(TAG_UPLOADER)
        private val menuButton = card.findViewWithTag<ImageView>(TAG_MENU)
        private val actions = card.getChildAt(card.childCount - 1) as LinearLayout
        private val downloadButton =
            (actions.getChildAt(actions.childCount - 1) as FrameLayout).getChildAt(0) as ImageView
        private val downloadSpinner =
            (actions.getChildAt(actions.childCount - 1) as FrameLayout).getChildAt(1) as ProgressBar

        private var item: MusicSearchResult? = null

        fun bind(row: SearchRow.Result, cellWidthPx: Int) {
            val result = row.item
            item = result
            if (cellWidthPx > 0) {
                artwork.layoutParams = artwork.layoutParams.apply {
                    height = maxOf(cellWidthPx - context.dp(14), context.dp(48))
                }
            }
            title.text = result.title
            uploader.text = result.uploader
            artwork.loadTrustedThumbnail(result.thumbnailUrl)
            menuButton.contentDescription =
                context.getString(R.string.track_actions_accessibility, result.title)
            menuButton.setOnClickListener { callbacks.onSearchResultMenu(result, it) }
            bindDownload(row.download)
        }

        fun bindDownload(download: SearchDownloadState?) {
            val result = item ?: return
            val status = download?.status
            val active = status == SearchDownloadStatus.DOWNLOADING ||
                status == SearchDownloadStatus.QUEUED
            downloadSpinner.visibility = if (active) View.VISIBLE else View.GONE
            downloadButton.visibility = if (active) View.GONE else View.VISIBLE
            when (status) {
                SearchDownloadStatus.COMPLETE -> {
                    downloadButton.setImageResource(R.drawable.ic_check)
                    downloadButton.tint(COLOR_ACCENT)
                    downloadButton.background = roundedBackground(Color.rgb(20, 50, 30), context.dp(22))
                    downloadButton.contentDescription = context.getString(
                        R.string.download_complete_accessibility,
                        result.title,
                    )
                    // Still tappable: MainActivity asks before downloading another copy.
                    downloadButton.isClickable = true
                    downloadButton.setOnClickListener { callbacks.onSearchResultDownload(result) }
                }

                SearchDownloadStatus.PAUSED -> {
                    downloadButton.setImageResource(R.drawable.ic_pause)
                    downloadButton.tint(COLOR_TEXT)
                    downloadButton.background = roundedBackground(COLOR_SURFACE, context.dp(22))
                    downloadButton.contentDescription = context.getString(
                        R.string.download_paused_accessibility,
                        result.title,
                    )
                    downloadButton.isClickable = true
                    val jobId = download.jobId
                    downloadButton.setOnClickListener {
                        if (jobId != null) callbacks.onSearchDownloadResume(jobId)
                    }
                }

                SearchDownloadStatus.RETRYABLE -> {
                    downloadButton.setImageResource(R.drawable.ic_repeat)
                    downloadButton.tint(COLOR_ACCENT_WARM)
                    downloadButton.background = roundedBackground(COLOR_SURFACE, context.dp(22))
                    downloadButton.contentDescription = context.getString(
                        R.string.retry_download_accessibility,
                        result.title,
                    )
                    downloadButton.isClickable = true
                    downloadButton.setOnClickListener { callbacks.onSearchResultDownload(result) }
                }

                SearchDownloadStatus.DOWNLOADING, SearchDownloadStatus.QUEUED,
                SearchDownloadStatus.NONE, null,
                -> {
                    downloadButton.setImageResource(R.drawable.ic_download)
                    downloadButton.tint(COLOR_TEXT)
                    downloadButton.background = roundedBackground(COLOR_SURFACE, context.dp(22))
                    downloadButton.contentDescription =
                        context.getString(R.string.download_track_named, result.title)
                    downloadButton.isClickable = true
                    downloadButton.setOnClickListener { callbacks.onSearchResultDownload(result) }
                }
            }
        }
    }

    private class MessageHolder(
        private val textView: TextView,
    ) : RecyclerView.ViewHolder(textView) {
        fun bind(row: SearchRow.Message, callbacks: Callbacks) {
            textView.text = row.text
            if (row.retryable) {
                textView.setTextColor(COLOR_ACCENT)
                textView.isClickable = true
                textView.setOnClickListener { callbacks.onSearchRetryPage() }
            } else {
                textView.setTextColor(COLOR_MUTED)
                textView.isClickable = false
                textView.setOnClickListener(null)
            }
        }
    }

    private class FooterHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_RESULT = 0
        const val TYPE_MESSAGE = 1
        const val TYPE_FOOTER = 2

        const val TAG_ARTWORK = "search-artwork"
        const val TAG_TITLE = "search-title"
        const val TAG_UPLOADER = "search-uploader"
        const val TAG_MENU = "search-menu"

        /** Payload marker for Requirement 5.7 partial rebinds. */
        val DOWNLOAD_STATE_CHANGED = Any()

        val DIFF = object : DiffUtil.ItemCallback<SearchRow>() {
            override fun areItemsTheSame(oldItem: SearchRow, newItem: SearchRow): Boolean =
                when {
                    oldItem is SearchRow.Result && newItem is SearchRow.Result ->
                        // Requirement 5.12: URL is the identity of a search result.
                        oldItem.item.url == newItem.item.url

                    oldItem is SearchRow.Message && newItem is SearchRow.Message ->
                        oldItem.text == newItem.text

                    oldItem is SearchRow.LoadingFooter && newItem is SearchRow.LoadingFooter -> true
                    else -> false
                }

            override fun areContentsTheSame(oldItem: SearchRow, newItem: SearchRow): Boolean =
                oldItem == newItem

            override fun getChangePayload(oldItem: SearchRow, newItem: SearchRow): Any? =
                if (oldItem is SearchRow.Result && newItem is SearchRow.Result &&
                    oldItem.item == newItem.item && oldItem.download != newItem.download
                ) {
                    DOWNLOAD_STATE_CHANGED
                } else {
                    null
                }
        }
    }
}
