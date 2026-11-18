package com.aplusconnect.companion.ui.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * The key grid.
 *
 * It draws whatever rows it is handed and reports taps; every decision about
 * what a key means (shift, composing text, editor action) belongs to the
 * service. Slide your finger to a different key and that one is typed, like
 * every other keyboard.
 */
class KeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var theme: KeyboardTheme = KeyboardTheme.GRAPHITE
        set(value) {
            field = value
            invalidate()
        }

    var rows: List<KeyRow> = emptyList()
        set(value) {
            field = value
            pressed = -1
            recompute()
            invalidate()
        }

    var shift: ShiftState = ShiftState.OFF
        set(value) {
            field = value
            invalidate()
        }

    var haptics: Boolean = true
    var sound: Boolean = false

    var onKey: ((Key) -> Unit)? = null

    private data class Slot(val key: Key, val bounds: RectF)

    private var slots: List<Slot> = emptyList()
    private var pressed = -1

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val repeatDelete = object : Runnable {
        override fun run() {
            onKey?.invoke(Key("⌫", KeyKind.BACKSPACE))
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recompute()
    }

    private fun recompute() {
        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        if (widthPx <= 0f || heightPx <= 0f || rows.isEmpty()) {
            slots = emptyList()
            return
        }

        val unit = widthPx / 10f
        val rowHeight = heightPx / rows.size
        val gap = min(unit * 0.08f, rowHeight * 0.10f)
        val computed = ArrayList<Slot>()

        rows.forEachIndexed { rowIndex, row ->
            if (row.keys.isEmpty()) return@forEachIndexed
            val inset = row.sideInset * unit
            val available = widthPx - inset * 2f
            val totalWeight = row.keys.fold(0f) { sum, key -> sum + key.weight }
            var x = inset
            val top = rowIndex * rowHeight

            for (key in row.keys) {
                val keyWidth = available * (key.weight / totalWeight)
                computed += Slot(
                    key,
                    RectF(
                        x + gap / 2f,
                        top + gap / 2f,
                        x + keyWidth - gap / 2f,
                        top + rowHeight - gap / 2f,
                    ),
                )
                x += keyWidth
            }
        }

        slots = computed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rowHeight = if (rows.isEmpty()) 0f else height.toFloat() / rows.size

        slots.forEachIndexed { index, slot ->
            val key = slot.key
            val isPressed = index == pressed
            val bounds = slot.bounds
            val radius = min(bounds.width(), bounds.height()) * 0.16f

            val base = when {
                key.kind == KeyKind.ENTER -> theme.accent
                key.isFunction -> theme.function
                else -> theme.key
            }
            fillPaint.color = if (isPressed) {
                when {
                    key.kind == KeyKind.ENTER -> theme.accent
                    key.isFunction -> theme.functionPressed
                    else -> theme.keyPressed
                }
            } else {
                base
            }
            canvas.drawRoundRect(bounds, radius, radius, fillPaint)

            if (isPressed && key.kind == KeyKind.ENTER) {
                fillPaint.color = PRESSED_OVERLAY
                canvas.drawRoundRect(bounds, radius, radius, fillPaint)
            }

            labelPaint.color = if (key.kind == KeyKind.ENTER) theme.accentLabel else theme.labelColor
            labelPaint.textSize = textSizeFor(key, rowHeight)
            labelPaint.isFakeBoldText = key.isFunction && key.kind != KeyKind.SPACE
            canvas.drawText(
                key.label,
                bounds.centerX(),
                bounds.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                labelPaint,
            )
        }
    }

    private fun textSizeFor(key: Key, rowHeight: Float): Float = when (key.kind) {
        KeyKind.CHAR -> (rowHeight * 0.44f).coerceIn(13f, 30f)
        KeyKind.SHIFT, KeyKind.BACKSPACE, KeyKind.ENTER -> (rowHeight * 0.40f).coerceIn(14f, 26f)
        KeyKind.EMOJI -> (rowHeight * 0.34f).coerceIn(13f, 24f)
        else -> (rowHeight * 0.26f).coerceIn(11f, 17f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = indexAt(event.x, event.y)
                if (index < 0) return true
                pressed = index
                feedback(slots[index].key)
                if (slots[index].key.kind == KeyKind.BACKSPACE) {
                    onKey?.invoke(slots[index].key)
                    handler.postDelayed(repeatDelete, REPEAT_DELAY_MS)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = indexAt(event.x, event.y)
                if (index != pressed) {
                    pressed = index
                    if (index >= 0) feedback(slots[index].key)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeatDelete)
                val index = pressed
                pressed = -1
                invalidate()
                if (index >= 0) {
                    val key = slots[index].key
                    // A backspace already fired on press, and repeats; it must
                    // not also fire again when the finger lifts.
                    if (key.kind != KeyKind.BACKSPACE) onKey?.invoke(key)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeatDelete)
                pressed = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun indexAt(x: Float, y: Float): Int =
        slots.indexOfFirst { it.bounds.contains(x, y) }

    private fun feedback(key: Key) {
        if (haptics) {
            performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
        if (sound) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, SOUND_VOLUME)
        }
    }

    fun cancelRepeat() {
        handler.removeCallbacks(repeatDelete)
    }

    private companion object {
        const val REPEAT_DELAY_MS = 380L
        const val REPEAT_MS = 60L
        const val SOUND_VOLUME = 0.35f
        const val PRESSED_OVERLAY = 0x33FFFFFF
    }
}