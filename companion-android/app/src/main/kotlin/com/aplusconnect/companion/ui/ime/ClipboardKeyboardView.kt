package com.aplusconnect.companion.ui.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The companion keyboard: a list of clipboard entries you can paste, and a
 * small row of keys.
 *
 * It is deliberately not a full keyboard. Holding the default-IME role is what
 * Android grants clipboard access on - without an accessibility service, which
 * is what makes the phone block contactless payments. A user who wants to type
 * taps "Switch keyboard" once and keeps their usual keyboard; the clipboard
 * sync keeps working in the background the whole time.
 */
class ClipboardKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onPaste(text: String)
        fun onKey(key: Key)
    }

    enum class Kind { PASTE, SWITCH_KEYBOARD, SPACE, ENTER, BACKSPACE }

    data class Key(val label: String, val kind: Kind, val text: String = "")

    var listener: Listener? = null

    /** The entries shown in the list: newest first. */
    var entries: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private var rows: List<Pair<String, RectF>> = emptyList()
    private var barButtons: List<Pair<Key, RectF>> = emptyList()
    private var pressedText: String? = null
    private var pressedBar: Key? = null

    private val backspaceHandler = Handler(Looper.getMainLooper())
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            listener?.onKey(Key("", Kind.BACKSPACE))
            backspaceHandler.postDelayed(this, BACKSPACE_REPEAT_MS)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * KEYBOARD_HEIGHT_RATIO).roundToInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val barHeight = height * BAR_HEIGHT_RATIO
        val listHeight = height - barHeight

        rows = emptyList()
        val visible = entries.take(MAX_VISIBLE_ENTRIES)

        if (visible.isEmpty()) {
            paint.color = EMPTY_FILL
            canvas.drawRoundRect(
                RectF(0f, 0f, width, listHeight - 8f),
                12f, 12f, paint,
            )
            textPaint.color = EMPTY_TEXT
            textPaint.textSize = listHeight * 0.18f
            canvas.drawText(
                "Nothing to paste yet - copy something on the computer",
                width / 2f,
                listHeight * 0.5f,
                textPaint,
            )
        } else {
            val rowHeight = max(MIN_ROW_HEIGHT, listHeight / visible.size)
            visible.forEachIndexed { index, entry ->
                val top = index * rowHeight
                val bounds = RectF(8f, top + 3f, width - 8f, top + rowHeight - 3f)
                rows = rows + Pair(entry, bounds)

                paint.color = if (entry == pressedText) ITEM_PRESSED else ITEM_FILL
                canvas.drawRoundRect(bounds, 10f, 10f, paint)

                textPaint.color = ITEM_TEXT
                textPaint.textSize = (rowHeight * 0.42f).coerceAtMost(MAX_TEXT)
                canvas.drawText(
                    entry.replace('\n', ' '),
                    bounds.left + 16f,
                    bounds.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f,
                    textPaint,
                )
            }
        }

        // The bottom bar.
        barButtons = emptyList()
        val barTop = listHeight
        val small = width * 0.19f
        val buttons = listOf(
            Key("ABC", Kind.SWITCH_KEYBOARD) to RectF(0f, barTop, small, height),
            Key("space", Kind.SPACE) to RectF(small, barTop, width - small * 2, height),
            Key("↵", Kind.ENTER) to RectF(width - small * 2, barTop, width - small, height),
            Key("⌫", Kind.BACKSPACE) to RectF(width - small, barTop, width.toFloat(), height),
        )
        for ((key, bounds) in buttons) {
            barButtons = barButtons + Pair(key, bounds)
            paint.color = if (key == pressedBar) ITEM_PRESSED else BAR_FILL
            canvas.drawRoundRect(
                RectF(bounds.left + 4f, bounds.top + 4f, bounds.right - 4f, bounds.bottom - 4f),
                10f, 10f, paint,
            )
            textPaint.color = ITEM_TEXT
            textPaint.textSize = (bounds.height() * 0.30f).coerceAtMost(MAX_TEXT)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                key.label,
                bounds.centerX(),
                bounds.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f,
                textPaint,
            )
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bar = barButtons.firstOrNull { it.second.contains(event.x, event.y) }
                if (bar != null) {
                    pressedBar = bar.first
                    pressedText = null
                    when (bar.first.kind) {
                        Kind.BACKSPACE -> {
                            listener?.onKey(bar.first)
                            backspaceHandler.postDelayed(backspaceRepeat, BACKSPACE_DELAY_MS)
                        }
                        Kind.SPACE -> listener?.onKey(bar.first)
                        Kind.ENTER -> listener?.onKey(bar.first)
                        Kind.SWITCH_KEYBOARD -> listener?.onKey(bar.first)
                        Kind.PASTE -> Unit
                    }
                    invalidate()
                    return true
                }

                val row = rows.firstOrNull { it.second.contains(event.x, event.y) }
                if (row != null) {
                    pressedText = row.first
                    pressedBar = null
                    invalidate()
                    return true
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val row = rows.firstOrNull { it.second.contains(event.x, event.y) }
                val key = barButtons.firstOrNull { it.second.contains(event.x, event.y) }?.first
                if (row?.first != pressedText || key != pressedBar) {
                    pressedText = row?.first
                    pressedBar = key
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                backspaceHandler.removeCallbacks(backspaceRepeat)
                if (pressedText != null) listener?.onPaste(pressedText!!)
                pressedText = null
                pressedBar = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val KEYBOARD_HEIGHT_RATIO = 0.55f
        private const val BAR_HEIGHT_RATIO = 0.22f
        private const val MIN_ROW_HEIGHT = 96f
        private const val MAX_VISIBLE_ENTRIES = 6
        private const val MAX_TEXT = 40f

        private const val ITEM_FILL = 0xFF222932.toInt()
        private const val ITEM_PRESSED = 0xFF2E3B49.toInt()
        private const val ITEM_TEXT = 0xFFE4ECF5.toInt()
        private const val BAR_FILL = 0xFF1A1F26.toInt()
        private const val EMPTY_FILL = 0xFF1A1F26.toInt()
        private const val EMPTY_TEXT = 0xFF7C8794.toInt()

        private const val BACKSPACE_DELAY_MS = 400L
        private const val BACKSPACE_REPEAT_MS = 70L
    }
}
