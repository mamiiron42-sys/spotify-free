package io.github.ytdlpandroid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.text.TextUtils
import android.widget.ArrayAdapter
import android.widget.TextView

/**
 * Programmatic-UI primitives shared by `MainActivity` and the list adapters.
 *
 * These are top-level and package-internal rather than `MainActivity` members so
 * that adapters can style their item views without holding an Activity reference,
 * which Requirement 5.11 forbids.
 */

internal const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
internal const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
internal const val BUTTON_PRESS_MS = 110L
internal const val SUCCESS_ANIMATION_MS = 260L

// Spotify Signature Palette
internal val COLOR_BACKGROUND = Color.rgb(18, 18, 18) // #121212 Spotify Black
internal val COLOR_NAVIGATION = Color.rgb(18, 18, 18) // #121212
internal val COLOR_SURFACE = Color.rgb(36, 36, 36) // #242424 Spotify elevated dark surface
internal val COLOR_CARD = Color.rgb(24, 24, 24) // #181818 Spotify card background
internal val COLOR_PLAYER_BAR = Color.rgb(33, 33, 33) // #212121 Spotify miniplayer background
internal val COLOR_TEXT = Color.rgb(255, 255, 255) // #FFFFFF Pure White
internal val COLOR_MUTED = Color.rgb(179, 179, 179) // #B3B3B3 Spotify light gray
internal val COLOR_ACCENT = Color.rgb(29, 185, 84) // #1DB954 Spotify Signature Green
internal val COLOR_ACCENT_BRIGHT = Color.rgb(30, 215, 96) // #1ED760
internal val COLOR_ACCENT_SECONDARY = Color.rgb(80, 56, 160) // #5038A0 Liked Songs Purple
internal val COLOR_ACCENT_WARM = Color.rgb(255, 100, 55)
internal val COLOR_ACCENT_BLUE = Color.rgb(80, 155, 245)
internal val COLOR_ACCENT_PINK = Color.rgb(235, 30, 50)
internal val COLOR_HERO_START = Color.rgb(29, 185, 84)
internal val COLOR_HERO_MIDDLE = Color.rgb(18, 90, 45)
internal val COLOR_HERO_END = Color.rgb(18, 18, 18)
internal val COLOR_HERO_TEXT = Color.WHITE

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius.toFloat()
}

internal fun likedSongsGradient(radiusPx: Float = 0f) = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(Color.rgb(80, 56, 160), Color.rgb(139, 92, 246)),
).apply { cornerRadius = radiusPx }

    fun Context.windowWidthPx(): Int =
        resources.configuration.screenWidthDp.takeIf { it > 0 }?.let(::dp)
            ?: resources.displayMetrics.widthPixels

    fun Context.sectionTitle(value: String) = TextView(this).apply {
        text = value; textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(COLOR_TEXT); setPadding(0, dp(4), 0, dp(8))
    }

    fun Context.sectionCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_CARD, dp(16))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        elevation = dp(2).toFloat()
    }

    fun Context.emptyMessage(message: String) = TextView(this).apply {
        text = message; textSize = 14f; setTextColor(COLOR_MUTED); setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    fun Context.formatTime(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0) / 1000)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    fun Context.styledSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items,
        ) {
            private fun styleItem(view: View): View = (view as? TextView)?.apply {
                setTextColor(COLOR_TEXT)
                textSize = 15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minHeight = dp(48)
                setBackgroundColor(COLOR_SURFACE)
            } ?: view

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                styleItem(super.getView(position, convertView, parent))

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                styleItem(super.getDropDownView(position, convertView, parent))
        }

    fun Context.marqueeLabel(
        sizeSp: Float,
        color: Int = COLOR_TEXT,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        textSize = sizeSp
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        isSelected = true
        marqueeRepeatLimit = -1
        setHorizontallyScrolling(true)
        isFocusable = true
        setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

internal fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

/** Squircle-free Spotify look: flat cards, generous radii, no shadows on small surfaces. */
internal fun verticalGradient(colors: IntArray, cornerRadiusPx: Float = 0f) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    colors,
).apply { cornerRadius = cornerRadiusPx }

/**
 * Tintable vector icon at [sizeDp]. Icons are white in the drawables so a single
 * `imageTintList` call produces the active/inactive nav or control colour.
 */
internal fun Context.iconView(
    iconRes: Int,
    sizeDp: Int,
    tint: Int = COLOR_TEXT,
): ImageView = ImageView(this).apply {
    setImageResource(iconRes)
    imageTintList = android.content.res.ColorStateList.valueOf(tint)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/**
 * Circular icon button used for player transport controls and row actions.
 * Returns an [ImageView] so callers swap the icon and tint when state changes.
 */
internal fun Context.iconButton(
    iconRes: Int,
    description: String,
    fill: Int = Color.TRANSPARENT,
    tint: Int = COLOR_TEXT,
    sizeDp: Int = 48,
    insetDp: Int = 11,
    onClick: () -> Unit = {},
): ImageView = ImageView(this).apply {
    setImageResource(iconRes)
    imageTintList = android.content.res.ColorStateList.valueOf(tint)
    contentDescription = description
    scaleType = ImageView.ScaleType.FIT_CENTER
    val inset = dp(insetDp)
    setPadding(inset, inset, inset, inset)
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }
    isClickable = true
    isFocusable = true
    stateListAnimator = null
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Re-tints an icon view in place, e.g. when a control becomes active. */
internal fun ImageView.tint(color: Int) {
    imageTintList = android.content.res.ColorStateList.valueOf(color)
}

internal fun Context.styleActionButton(button: Button, primary: Boolean) {
    button.isAllCaps = false
    button.textSize = 13f
    button.setTypeface(null, android.graphics.Typeface.BOLD)
    button.setTextColor(if (primary) Color.BLACK else COLOR_TEXT)
    button.background = roundedBackground(if (primary) COLOR_ACCENT else COLOR_SURFACE, dp(24))
    button.setPadding(dp(16), 0, dp(16), 0)
    button.minHeight = dp(44)
    button.minWidth = 0
    button.stateListAnimator = null
    button.setOnTouchListener { view, event ->
        if (animationsEnabled()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(BUTTON_PRESS_MS)
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(BUTTON_PRESS_MS)
                    .start()
            }
        }
        false
    }
}

internal fun Context.stylePillChip(chip: TextView, selected: Boolean) {
    chip.isAllCaps = false
    chip.textSize = 13f
    chip.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    chip.setTextColor(if (selected) Color.BLACK else COLOR_TEXT)
    chip.background = roundedBackground(if (selected) COLOR_ACCENT else COLOR_SURFACE, dp(20))
    chip.setPadding(dp(16), dp(8), dp(16), dp(8))
}

/** Overshoot pop that draws the eye to a row whose state just changed. */
internal fun View.animateStateChange() {
    if (!animationsEnabled()) return
    animate().cancel()
    alpha = 0.75f
    scaleX = 0.97f
    scaleY = 0.97f
    animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(SUCCESS_ANIMATION_MS)
        .setInterpolator(OvershootInterpolator(1.15f))
        .start()
}
