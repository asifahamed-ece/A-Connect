package com.aplusconnect.companion.ui.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

private fun View.dp(value: Float): Float = value * resources.displayMetrics.density

/** Draws a label centred in a rectangle. */
private fun Canvas.centred(text: String, bounds: RectF, paint: Paint) {
    paint.textAlign = Paint.Align.CENTER
    drawText(
        text,
        bounds.centerX(),
        bounds.centerY() - (paint.ascent() + paint.descent()) / 2f,
        paint,
    )
}

/**
 * The suggestion strip above the keys.
 *
 * Left is undo, the middle is up to three word suggestions, right is settings -
 * the same arrangement Gboard uses, because that is where the thumb expects
 * them.
 */
class SuggestionsBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var theme: KeyboardTheme = KeyboardTheme.GRAPHITE
        set(value) {
            field = value
            invalidate()
        }

    var suggestions: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var canUndo: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onSuggestion: ((String) -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private var undoBounds = RectF()
    private var settingsBounds = RectF()
    private var chipBounds: List<Pair<String, RectF>> = emptyList()
    private var pressed = ""

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fill.color = theme.bar
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        val h = height.toFloat()
        val side = dp(6f)
        val button = min(h - dp(4f), dp(40f))

        undoBounds = RectF(side, (h - button) / 2f, side + button, (h + button) / 2f)
        settingsBounds = RectF(
            width - side - button,
            (h - button) / 2f,
            width - side,
            (h + button) / 2f,
        )

        label.textSize = dp(17f)
        label.color = if (canUndo) theme.barLabel else theme.barHint
        label.alpha = if (canUndo) 255 else 110
        canvas.centred("↶", undoBounds, label)
        label.alpha = 255

        label.color = theme.barHint
        label.textSize = dp(16f)
        canvas.centred("⚙", settingsBounds, label)

        // The suggestions share what is left, and never overlap the buttons.
        val left = undoBounds.right + dp(4f)
        val right = settingsBounds.left - dp(4f)
        val available = right - left

        chipBounds = emptyList()
        if (suggestions.isEmpty() || available <= dp(40f)) return

        val gap = dp(6f)
        val cellWidth = (available - gap * (suggestions.size - 1)) / suggestions.size
        var x = left

        suggestions.forEach { suggestion ->
            val bounds = RectF(x, dp(5f), x + cellWidth, h - dp(5f))
            fill.color = if (pressed == suggestion) theme.chipPressed else theme.chip
            canvas.drawRoundRect(bounds, dp(9f), dp(9f), fill)

            label.color = if (pressed == suggestion) theme.accent else theme.barLabel
            label.textSize = dp(15f)
            label.textAlign = Paint.Align.LEFT
            canvas.save()
            canvas.clipRect(bounds.left + dp(10f), bounds.top, bounds.right - dp(10f), bounds.bottom)
            canvas.drawText(
                suggestion,
                bounds.left + dp(10f),
                bounds.centerY() - (label.ascent() + label.descent()) / 2f,
                label,
            )
            canvas.restore()
            label.textAlign = Paint.Align.CENTER

            chipBounds = chipBounds + Pair(suggestion, bounds)
            x += cellWidth + gap
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = ""
                when {
                    undoBounds.contains(event.x, event.y) -> {
                        pressed = "undo"
                        invalidate()
                    }
                    settingsBounds.contains(event.x, event.y) -> {
                        pressed = "settings"
                        invalidate()
                    }
                    else -> {
                        val chip = chipBounds.firstOrNull { it.second.contains(event.x, event.y) }
                        if (chip != null) {
                            pressed = chip.first
                            invalidate()
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val which = pressed
                pressed = ""
                invalidate()
                when (which) {
                    "undo" -> onUndo?.invoke()
                    "settings" -> onSettings?.invoke()
                    "" -> Unit
                    else -> onSuggestion?.invoke(which)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = ""
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

/**
 * The clipboard, sitting on the keyboard.
 *
 * This is the reason the app holds the default-IME role, so it is the one part
 * of the keyboard no other keyboard can offer: whatever you copied on the
 * phone or the computer is one tap away. Drag the header up or down to make it
 * taller.
 */
class ClipboardStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var theme: KeyboardTheme = KeyboardTheme.GRAPHITE
        set(value) {
            field = value
            invalidate()
        }

    var entries: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onPaste: ((String) -> Unit)? = null
    var onResize: ((Int) -> Unit)? = null
    var rows: Int = 1
        set(value) {
            field = value.coerceIn(1, 5)
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private var header = RectF()
    private var chips: List<Pair<String, RectF>> = emptyList()
    private var pressed = ""

    private var dragStartY = 0f
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        fill.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        val headerHeight = dp(20f)
        header = RectF(dp(10f), 0f, width - dp(10f), headerHeight)

        label.textSize = dp(10f)
        label.color = theme.hint
        label.textAlign = Paint.Align.LEFT
        label.isFakeBoldText = true
        canvas.drawText("CLIPBOARD", dp(10f), headerHeight * 0.72f, label)

        label.textAlign = Paint.Align.RIGHT
        label.textSize = dp(10f)
        label.letterSpacing = 0.02f
        canvas.drawText("drag to resize", width - dp(10f), headerHeight * 0.72f, label)
        label.isFakeBoldText = false
        label.letterSpacing = 0f
        label.textAlign = Paint.Align.LEFT

        // A hairline above the keys, so the strip reads as its own shelf.
        fill.color = theme.divider
        canvas.drawRect(0f, height - dp(1f), width.toFloat(), height.toFloat(), fill)

        chips = emptyList()
        if (entries.isEmpty()) {
            label.textSize = dp(12f)
            label.color = theme.hint
            canvas.drawText(
                "Copy on the phone or the computer and it appears here",
                dp(10f),
                headerHeight + dp(20f),
                label,
            )
            return
        }

        val chipHeight = dp(30f)
        val gap = dp(6f)
        val top = headerHeight + dp(4f)
        var x = dp(10f)
        var y = top
        val maxY = (rows * (chipHeight + gap)) + top - gap

        var drawn = 0
        for (entry in entries) {
            val text = entry.replace('\n', ' ').replace('\t', ' ').trim()
            if (text.isEmpty()) continue
            label.textSize = dp(13f)
            val widthOfText = min(label.measureText(text) + dp(22f), dp(260f))
            if (x + widthOfText > width - dp(10f)) {
                x = dp(10f)
                y += chipHeight + gap
            }
            if (y + chipHeight > maxY + dp(1f)) break

            val bounds = RectF(x, y, x + widthOfText, y + chipHeight)
            fill.color = if (pressed == entry) theme.chipPressed else theme.chip
            canvas.drawRoundRect(bounds, dp(9f), dp(9f), fill)

            label.color = if (pressed == entry) theme.accent else theme.barLabel
            canvas.save()
            canvas.clipRect(bounds.left + dp(10f), bounds.top, bounds.right - dp(8f), bounds.bottom)
            canvas.drawText(
                text,
                bounds.left + dp(10f),
                bounds.centerY() - (label.ascent() + label.descent()) / 2f,
                label,
            )
            canvas.restore()

            chips = chips + Pair(entry, bounds)
            x += widthOfText + gap
            drawn++
        }

        if (drawn == 0) {
            label.textSize = dp(12f)
            label.color = theme.hint
            canvas.drawText(
                "Drag up to make the clipboard taller",
                dp(10f),
                headerHeight + dp(20f),
                label,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (header.contains(event.x, event.y)) {
                    dragging = true
                    dragStartY = event.y
                    return true
                }
                val chip = chips.firstOrNull { it.second.contains(event.x, event.y) }
                pressed = chip?.first ?: ""
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val step = dp(34f)
                val moved = event.y - dragStartY
                if (moved > step) {
                    onResize?.invoke(rows - 1)
                    rows -= 1
                    dragStartY = event.y
                } else if (moved < -step) {
                    onResize?.invoke(rows + 1)
                    rows += 1
                    dragStartY = event.y
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    return true
                }
                val which = pressed
                pressed = ""
                invalidate()
                if (which.isNotEmpty()) onPaste?.invoke(which)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                pressed = ""
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

/** The emoji panel: a tab per category, and the emoji laid out in a grid. */
class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var theme: KeyboardTheme = KeyboardTheme.GRAPHITE
        set(value) {
            field = value
            invalidate()
        }

    var recent: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onEmoji: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onLetters: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private var tab = 0
    private var scroll = 0f
    private var downY = 0f
    private var dragging = false
    private var pressed = ""

    private var tabs: List<Pair<Int, RectF>> = emptyList()
    private var cells: List<Pair<String, RectF>> = emptyList()
    private var backspaceBounds = RectF()
    private var lettersBounds = RectF()
    private var spaceBounds = RectF()

    private fun pages(): List<Pair<String, List<String>>> {
        val list = mutableListOf<Pair<String, List<String>>>()
        if (recent.isNotEmpty()) list += "Recent" to recent
        EmojiData.categories.forEach { list += it.label to it.emoji }
        list += "Flags" to EmojiData.flags
        return list
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val all = pages()
        tab = tab.coerceIn(0, all.lastIndex)

        fill.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        // The tab row.
        val tabHeight = dp(38f)
        val tabWidth = (width - dp(8f)) / max(1, all.size)
        tabs = emptyList()
        label.textSize = dp(17f)
        label.textAlign = Paint.Align.CENTER

        all.forEachIndexed { index, page ->
            val bounds = RectF(
                dp(4f) + index * tabWidth,
                0f,
                dp(4f) + (index + 1) * tabWidth,
                tabHeight,
            )
            tabs = tabs + Pair(index, bounds)

            if (index == tab) {
                fill.color = theme.chip
                canvas.drawRoundRect(
                    RectF(bounds.left + dp(3f), dp(6f), bounds.right - dp(3f), tabHeight - dp(6f)),
                    dp(8f), dp(8f), fill,
                )
            }
            label.color = if (index == tab) theme.accent else theme.hint
            canvas.centred(if (page.first == "Recent") "🕘" else iconFor(page.first), bounds, label)
        }

        // The footer.
        val footerHeight = dp(42f)
        val footerTop = height - footerHeight
        val third = width / 3f
        lettersBounds = RectF(dp(4f), footerTop, third - dp(2f), height.toFloat() - dp(4f))
        spaceBounds = RectF(third + dp(2f), footerTop, third * 2 - dp(2f), height.toFloat() - dp(4f))
        backspaceBounds = RectF(third * 2 + dp(2f), footerTop, width - dp(4f), height.toFloat() - dp(4f))

        drawFooterKey(canvas, "ABC", lettersBounds)
        drawFooterKey(canvas, "space", spaceBounds)
        drawFooterKey(canvas, "⌫", backspaceBounds)

        // The grid, scrolled by dragging.
        val gridTop = tabHeight + dp(4f)
        val gridBottom = footerTop - dp(4f)
        val cellSize = (width - dp(8f)) / 8f
        val content = all[tab].second
        val contentHeight = (content.size / 8 + if (content.size % 8 == 0) 0 else 1) * cellSize
        val maxScroll = max(0f, contentHeight - (gridBottom - gridTop))
        scroll = scroll.coerceIn(0f, maxScroll)

        cells = emptyList()
        label.textSize = cellSize * 0.62f
        content.forEachIndexed { index, emoji ->
            val column = index % 8
            val row = index / 8
            val left = dp(4f) + column * cellSize
            val top = gridTop + row * cellSize - scroll
            if (top + cellSize < gridTop || top > gridBottom) return@forEachIndexed

            val bounds = RectF(left, top, left + cellSize, top + cellSize)
            if (pressed == emoji) {
                fill.color = theme.chip
                canvas.drawRoundRect(bounds, dp(8f), dp(8f), fill)
            }
            label.color = theme.labelColor
            canvas.centred(emoji, bounds, label)
            cells = cells + Pair(emoji, bounds)
        }
    }

    private fun drawFooterKey(canvas: Canvas, text: String, bounds: RectF) {
        fill.color = if (pressed == text) theme.functionPressed else theme.function
        canvas.drawRoundRect(bounds, dp(9f), dp(9f), fill)
        label.color = theme.labelColor
        label.textSize = if (text.length == 1) dp(19f) else dp(14f)
        canvas.centred(text, bounds, label)
    }

    private fun iconFor(category: String): String =
        EmojiData.categories.firstOrNull { it.label == category }?.icon ?: "•"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                dragging = false
                pressed = ""
                val hitTab = tabs.firstOrNull { it.second.contains(event.x, event.y) }
                if (hitTab != null) {
                    tab = hitTab.first
                    scroll = 0f
                    invalidate()
                    return true
                }
                if (lettersBounds.contains(event.x, event.y)) pressed = "ABC"
                else if (spaceBounds.contains(event.x, event.y)) pressed = "space"
                else if (backspaceBounds.contains(event.x, event.y)) pressed = "⌫"
                else {
                    val cell = cells.firstOrNull { it.second.contains(event.x, event.y) }
                    pressed = cell?.first ?: ""
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(event.y - downY) > dp(12f)) dragging = true
                if (dragging) {
                    scroll += downY - event.y
                    downY = event.y
                    pressed = ""
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val which = pressed
                pressed = ""
                invalidate()
                if (!dragging) {
                    when (which) {
                        "" -> Unit
                        "ABC" -> onLetters?.invoke()
                        "space" -> onEmoji?.invoke(" ")
                        "⌫" -> onBackspace?.invoke()
                        else -> onEmoji?.invoke(which)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = ""
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

/**
 * The keyboard's settings, drawn where the keys are.
 *
 * An input method cannot open a normal settings screen without leaving the
 * text field, so the settings live in the keyboard itself: theme, layouts,
 * clipboard height, feedback.
 */
class SettingsPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onTheme(theme: KeyboardTheme)
        fun onToggle(id: String, value: Boolean)
        fun onClipboardRows(rows: Int)
        fun onClearLearned()
        fun onSwitchKeyboard()
        fun onDone()
    }

    var theme: KeyboardTheme = KeyboardTheme.GRAPHITE
        set(value) {
            field = value
            invalidate()
        }

    var prefs: KeyboardPrefs? = null
        set(value) {
            field = value
            invalidate()
        }

    var listener: Listener? = null

    private data class Cell(
        val label: String,
        val id: String,
        val active: Boolean = false,
        val weight: Float = 1f,
    )

    private data class Row(val title: String?, val cells: List<Cell>, val heightDp: Float)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private var hits: List<Pair<String, RectF>> = emptyList()
    private var pressed = ""

    private fun rows(): List<Row> {
        val p = prefs
        val rows = mutableListOf<Row>()

        rows += Row(
            "THEME",
            KeyboardTheme.values().map {
                Cell(it.title, "theme:${it.id}", active = it.id == theme.id, weight = 1f)
            },
            34f,
        )

        rows += Row(
            "LAYOUT",
            listOf(
                Cell("Number row", "toggle:number_row", active = p?.numberRow == true),
                Cell("Clipboard strip", "toggle:clipboard_strip", active = p?.clipboardStrip == true),
            ),
            36f,
        )
        rows += Row(
            "CLIPBOARD HEIGHT",
            (1..5).map { Cell("$it", "rows:$it", active = p?.clipboardRows == it) },
            34f,
        )
        rows += Row(
            "FEEDBACK",
            listOf(
                Cell("Vibration", "toggle:haptics", active = p?.haptics == true),
                Cell("Sound", "toggle:sound", active = p?.sound == true),
                Cell("Auto-capitalise", "toggle:auto_capitalise", active = p?.autoCapitalise == true),
            ),
            36f,
        )
        rows += Row(
            "LEARNED WORDS",
            listOf(Cell("Clear learned words", "clear_learned")),
            36f,
        )
        rows += Row(
            null,
            listOf(
                Cell("Switch keyboard…", "switch_keyboard", weight = 2f),
                Cell("Done", "done", active = true, weight = 1f),
            ),
            38f,
        )
        return rows
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fill.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        hits = emptyList()

        label.textSize = dp(10f)
        label.isFakeBoldText = true
        label.color = theme.hint
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("KEYBOARD SETTINGS", dp(12f), dp(18f), label)
        label.isFakeBoldText = false

        var y = dp(30f)
        for (row in rows()) {
            if (row.title != null) {
                label.textSize = dp(9f)
                label.color = theme.hint
                label.textAlign = Paint.Align.LEFT
                label.isFakeBoldText = true
                canvas.drawText(row.title, dp(12f), y + dp(10f), label)
                label.isFakeBoldText = false
                y += dp(14f)
            }

            val height = dp(row.heightDp)
            val gap = dp(6f)
            val left = dp(10f)
            val totalWidth = width - dp(20f)
            val totalWeight = row.cells.fold(0f) { sum, cell -> sum + cell.weight }
            var x = left

            for (cell in row.cells) {
                val cellWidth = (totalWidth - gap * (row.cells.size - 1)) * (cell.weight / totalWeight)
                val bounds = RectF(x, y, x + cellWidth, y + height)
                val isPressed = pressed == cell.id

                fill.color = when {
                    isPressed -> theme.functionPressed
                    cell.active -> theme.accent
                    else -> theme.chip
                }
                canvas.drawRoundRect(bounds, dp(9f), dp(9f), fill)

                label.textSize = dp(12f)
                label.color = if (cell.active) theme.accentLabel else theme.labelColor
                canvas.save()
                canvas.clipRect(bounds)
                canvas.centred(cell.label, bounds, label)
                canvas.restore()

                hits = hits + Pair(cell.id, bounds)
                x += cellWidth + gap
            }
            y += height + dp(8f)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.firstOrNull { it.second.contains(event.x, event.y) }?.first ?: ""
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val id = hits.firstOrNull { it.second.contains(event.x, event.y) }?.first ?: ""
                val matched = id.isNotEmpty() && id == pressed
                pressed = ""
                invalidate()
                if (matched) dispatch(id)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = ""
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dispatch(id: String) {
        val l = listener ?: return
        val p = prefs ?: return
        when {
            id.startsWith("theme:") ->
                l.onTheme(KeyboardTheme.byId(id.removePrefix("theme:")))
            id.startsWith("rows:") ->
                l.onClipboardRows(id.removePrefix("rows:").toIntOrNull() ?: 1)
            id == "toggle:number_row" ->
                l.onToggle("number_row", !p.numberRow)
            id == "toggle:clipboard_strip" ->
                l.onToggle("clipboard_strip", !p.clipboardStrip)
            id == "toggle:haptics" ->
                l.onToggle("haptics", !p.haptics)
            id == "toggle:sound" ->
                l.onToggle("sound", !p.sound)
            id == "toggle:auto_capitalise" ->
                l.onToggle("auto_capitalise", !p.autoCapitalise)
            id == "clear_learned" -> l.onClearLearned()
            id == "switch_keyboard" -> l.onSwitchKeyboard()
            id == "done" -> l.onDone()
        }
    }
}