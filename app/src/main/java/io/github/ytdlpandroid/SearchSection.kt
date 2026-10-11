package io.github.ytdlpandroid

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The search screen: the query field, the result list, and the scroll-driven paging that
 * keeps filling it.
 *
 * Extracted from MainActivity, which still constructs the screens and holds the ViewModels,
 * so the section talks to its host for the actual query and the state that drives rendering.
 * It keeps its own views and its paging bookkeeping rather than reaching back into the
 * activity, which is what let the scrolling logic stay where it is understood.
 */
internal class SearchSection(
    private val context: Context,
    private val host: Host,
) {
    /** The narrow surface the search screen consumes from its host. */
    interface Host {
        /** Runs a search for [query]; the host owns the ViewModel and the service. */
        fun search(query: String)

        /** Discards results and returns the screen to its prompt state. */
        fun clearSearch()

        /** True while a request is in flight, used to disable the search button. */
        fun isSearching(): Boolean

        /** Loads the next page; returns false when there is nothing more to fetch. */
        fun loadNextPage(): Boolean

        /** Live paging state, read at the moment of each scroll check. */
        fun hasMoreResults(): Boolean
        fun isLoadingMore(): Boolean
        fun hasActiveQuery(): Boolean

        fun showResultMenu(result: MusicSearchResult, anchor: View)
        fun downloadResult(result: MusicSearchResult)

        /** Resumes an interrupted download for [jobId]. */
        fun resumeDownload(jobId: Long)

        fun retryPage()
    }

    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var searchButton: android.widget.Button
    private lateinit var resultsTitle: TextView
    private lateinit var results: RecyclerView
    private val adapter = SearchResultAdapter(
        object : SearchResultAdapter.Callbacks {
            override fun onSearchResultMenu(result: MusicSearchResult, anchor: View) =
                host.showResultMenu(result, anchor)

            override fun onSearchResultDownload(result: MusicSearchResult) =
                host.downloadResult(result)

            override fun onSearchDownloadResume(jobId: Long) = host.resumeDownload(jobId)

            override fun onSearchRetryPage() = host.retryPage()
        },
    )

    /** Builds the screen into [scroll]; call once, from the host's screen construction. */
    fun attachTo(scroll: ScrollView) {
        scrollView = scroll
        val root = scrollContentOf(scroll)
        root.addView(
            sectionTitleView(context.getString(R.string.nav_search), textSizeSp = 30f).apply {
                setPadding(0, 0, 0, context.dp(14))
            },
            matchWidth(),
        )
        input = EditText(context).apply {
            hint = context.getString(R.string.search_hint_spotify)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(context.dp(8), 0, context.dp(10), 0)
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    search()
                    true
                } else {
                    false
                }
            }
        }
        val searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_SURFACE, context.dp(24))
            setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4))
        }
        val searchIcon = context.iconView(R.drawable.ic_search_outline, 22, COLOR_MUTED).apply {
            setPadding(context.dp(8), 0, 0, 0)
        }
        searchBox.addView(searchIcon, LinearLayout.LayoutParams(WRAP, MATCH))
        searchBox.addView(input, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        val clearButton = context.iconButton(
            iconRes = R.drawable.ic_close,
            description = context.getString(R.string.clear_search),
            tint = COLOR_MUTED,
            sizeDp = 44,
        ) { clear() }
        clearButton.visibility = View.GONE
        searchBox.addView(clearButton, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))

        // No search-as-you-type: a keystroke starting a fresh yt-dlp extraction made search
        // crawl and return matches for queries nobody finished typing. Enter or the button.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        root.addView(searchBox, matchWidth())

        searchButton = android.widget.Button(context).apply {
            text = context.getString(R.string.search_music)
            context.styleActionButton(this, true)
            setOnClickListener { search() }
        }
        root.addView(searchButton, matchWidth().apply {
            topMargin = context.dp(10)
            bottomMargin = context.dp(2)
        })

        resultsTitle = sectionTitleView(context.getString(R.string.search_results)).apply {
            visibility = View.GONE
        }
        root.addView(resultsTitle, sectionSpacing())
        results = RecyclerView(context).apply {
            // Qualified for the same reason as the cell width below.
            adapter = this@SearchSection.adapter
            layoutManager = GridLayoutManager(context, searchGridColumnCount()).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        this@SearchSection.adapter.spanSizeFor(position, spanCount)
                }
            }
            // Lives inside a ScrollView, which owns scrolling and drives pagination.
            isNestedScrollingEnabled = false
            itemAnimator = DefaultItemAnimator()
            // Qualified: inside this apply block, a bare "adapter" resolves to the
            // RecyclerView's own adapter property.
            this@SearchSection.adapter.cellWidthPx = searchCellWidthPx()
        }
        root.addView(results, matchWidth())
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> maybeLoadMoreSearchResults() }
    }

    /** Redraws the list for a screen state. */
    fun render(screen: SearchScreen) {
        adapter.cellWidthPx = searchCellWidthPx()
        adapter.submitList(screen.rows) {
            // A completed page changes the ScrollView's content height but does not itself
            // produce a scroll callback. Re-check after RecyclerView has committed and laid out
            // the appended rows, so paging can continue whenever the viewport is still near end.
            if (screen.hasMoreResults) results.post { maybeLoadMoreSearchResults() }
        }
        resultsTitle.visibility = if (screen.showTitle) View.VISIBLE else View.GONE
        val resultCount = screen.rows.count { it is SearchRow.Result }
        resultsTitle.text = if (resultCount > 0) {
            context.getString(R.string.search_results_count, resultCount)
        } else {
            context.getString(R.string.search_results)
        }
        searchButton.isEnabled = !screen.isSearching
        searchButton.alpha = if (screen.isSearching) 0.65f else 1f
    }

    /** Fills the field and runs the query, as the home screen's shortcuts do. */
    fun search(query: String) {
        input.setText(query)
        search()
    }

    /** The text currently in the field, for the host's explicit-search path. */
    fun currentQuery(): String = if (::input.isInitialized) input.text.toString() else ""

    fun search() {
        val query = input.text.toString()
        host.search(query)
    }

    fun clear() {
        input.text.clear()
        host.clearSearch()
    }

    /**
     * Fetches the next page once the viewport is within [prefetchDistance] of the end. Called
     * on scroll and after every committed page, because appending rows grows the content
     * without producing a scroll event.
     */
    fun maybeLoadMoreSearchResults() {
        if (!host.hasActiveQuery() ||
            !host.hasMoreResults() ||
            host.isLoadingMore() ||
            host.isSearching()
        ) return

        val contentHeight = scrollView.getChildAt(0)?.height ?: return
        // Start fetching about two screens early so results are ready before the user reaches
        // the end, like YouTube's endless feed.
        val prefetchDistance = maxOf(context.dp(600), scrollView.height * 2)
        if (scrollView.scrollY + scrollView.height >= contentHeight - prefetchDistance) {
            host.loadNextPage()
        }
    }

    private fun sectionTitleView(value: String, textSizeSp: Float = 20f) = TextView(context).apply {
        text = value
        textSize = textSizeSp
        setTypeface(null, Typeface.BOLD)
        setTextColor(COLOR_TEXT)
        setPadding(0, context.dp(4), 0, context.dp(8))
    }

    private fun sectionSpacing() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun scrollContentOf(scroll: ScrollView): LinearLayout =
        (scroll.getChildAt(0) as android.widget.FrameLayout).getChildAt(0) as LinearLayout

    private fun searchGridColumnCount(): Int {
        val widthDp = context.resources.configuration.screenWidthDp
        val columns = when {
            widthDp >= 900 -> 5
            widthDp >= 600 -> 4
            else -> 2
        }
        return columns.coerceAtLeast(1)
    }

    private fun searchCellWidthPx(): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val available = minOf(
            context.dp(MAX_CONTENT_WIDTH_DP),
            (screenWidth - context.dp(24)).coerceAtLeast(0),
        )
        val spacing = context.dp(CELL_SPACING_DP)
        val cell = (available - spacing * (searchGridColumnCount() - 1)) / searchGridColumnCount()
        return cell.coerceAtLeast(1)
    }

    private companion object {
        const val MAX_CONTENT_WIDTH_DP = 760
        const val CELL_SPACING_DP = 8
    }
}
