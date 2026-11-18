package com.aplusconnect.companion.ui.ime

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The whole keyboard: suggestions, clipboard strip, and whichever panel is
 * showing.
 *
 * Its height comes from the screen width the way every keyboard does it, so
 * the same code fills a phone or a small tablet without hard-coded pixels.
 */
class KeyboardRootView(
    context: Context,
    val prefs: KeyboardPrefs,
) : LinearLayout(context) {

    enum class Mode { KEYS, EMOJI, SETTINGS }

    val suggestions = SuggestionsBarView(context)
    val clipboard = ClipboardStripView(context)
    val keys = KeysView(context)
    val emoji = EmojiPanelView(context)
    val settings = SettingsPanelView(context)

    private val content = FrameLayout(context)

    var mode: Mode = Mode.KEYS
        private set

    var theme: KeyboardTheme = prefs.theme
        set(value) {
            field = value
            suggestions.theme = value
            clipboard.theme = value
            keys.theme = value
            emoji.theme = value
            settings.theme = value
            setBackgroundColor(value.background)
            invalidate()
        }

    /** Rows in the letter layout, so the clipboard strip and keys add up. */
    private var keyRows = 4

    init {
        orientation = VERTICAL

        addView(
            suggestions,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(SUGGESTIONS_HEIGHT_DP).toInt()),
        )
        addView(clipboard, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        clipboard.onResize = { rows ->
            prefs.clipboardRows = rows
            refreshHeights()
        }

        suggestions.onSettings = { show(Mode.SETTINGS) }

        settings.prefs = prefs
        settings.theme = theme

        applyPrefs()
        show(Mode.KEYS)
    }

    fun dp(value: Float): Float = value * resources.displayMetrics.density

    fun setKeys(rows: List<KeyRow>, shift: ShiftState) {
        keyRows = rows.size
        keys.rows = rows
        keys.shift = shift
        requestLayout()
    }

    fun setSuggestions(items: List<String>, canUndo: Boolean) {
        suggestions.suggestions = items
        suggestions.canUndo = canUndo
    }

    fun setClipboardEntries(items: List<String>) {
        clipboard.entries = items
    }

    fun setRecentEmoji(items: List<String>) {
        emoji.recent = items
    }

    fun applyPrefs() {
        theme = prefs.theme
        keys.haptics = prefs.haptics
        keys.sound = prefs.sound
        clipboard.rows = prefs.clipboardRows
        refreshHeights()
    }

    /** The clipboard strip is as tall as the user dragged it, or gone. */
    private fun refreshHeights() {
        clipboard.visibility = if (prefs.clipboardStrip) VISIBLE else GONE
        clipboard.layoutParams = (clipboard.layoutParams as LayoutParams).apply {
            height = if (prefs.clipboardStrip) {
                dp(CLIPBOARD_HEADER_DP + prefs.clipboardRows * CLIPBOARD_ROW_DP).toInt()
            } else {
                0
            }
        }
        requestLayout()
    }

    fun show(next: Mode) {
        mode = next
        content.removeAllViews()
        val child = when (next) {
            Mode.KEYS -> keys
            Mode.EMOJI -> emoji
            Mode.SETTINGS -> settings
        }
        content.addView(
            child,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val keyHeight = (width / 10f) * KEY_HEIGHT_RATIO
        val contentHeight = (keyRows * keyHeight).toInt()
        val total = dp(SUGGESTIONS_HEIGHT_DP).toInt() +
            clipboard.layoutParams.height +
            contentHeight +
            paddingTop +
            paddingBottom

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY),
        )

        android.util.Log.i(
            "AplusConnect",
            "keyboard geometry: width=$width wanted=$total rows=$keyRows " +
                "allowed=${MeasureSpec.getSize(heightMeasureSpec)} mode=${MeasureSpec.getMode(heightMeasureSpec)}",
        )
    }

    private companion object {
        const val SUGGESTIONS_HEIGHT_DP = 46f
        const val CLIPBOARD_HEADER_DP = 24f
        const val CLIPBOARD_ROW_DP = 36f

        /** Key height as a fraction of key width. */
        const val KEY_HEIGHT_RATIO = 1.32f
    }
}