package com.aplusconnect.companion.ui.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.aplusconnect.companion.service.CompanionService
import java.util.Locale

/**
 * The companion app's input method, and therefore the app's clipboard
 * privilege.
 *
 * Android grants background clipboard access to the *default* keyboard and to
 * nothing else (ClipboardService.isDefaultIme reads
 * Settings.Secure.DEFAULT_INPUT_METHOD). That is why the app ships a keyboard
 * at all: an accessibility service would grant the same access and make the
 * phone refuse contactless payments.
 *
 * Because the role cannot be handed to another keyboard without losing the
 * privilege, this has to be a keyboard people actually type on. It aims for the
 * layout and the feel of Gboard, and adds the one thing Gboard cannot have: the
 * computer's clipboard, one tap above the keys.
 */
class AplusImeService : InputMethodService() {

    private enum class Page { LETTERS, SYMBOLS, SYMBOLS_MORE, NUMBERS }

    private lateinit var prefs: KeyboardPrefs
    private lateinit var dictionary: Dictionary

    private var keyboard: KeyboardRootView? = null
    private var page = Page.LETTERS
    private var shift = ShiftState.OFF

    /** The word being typed, which the platform shows as composing text. */
    private var composing = ""

    /** What was inserted recently, so undo can take it back. */
    private val undoStack = ArrayDeque<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val clipboardTick = object : Runnable {
        override fun run() {
            refreshClipboard()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardPrefs(this)
        dictionary = Dictionary(this, prefs)
    }

    override fun onCreateInputView(): View {
        val root = KeyboardRootView(this, prefs)
        keyboard = root

        root.keys.onKey = { handleKey(it) }
        root.suggestions.onUndo = { undo() }
        root.suggestions.onSuggestion = { pickSuggestion(it) }
        root.clipboard.onPaste = { paste(it) }
        root.emoji.onEmoji = { emoji -> if (emoji == " ") commitSpace() else commitEmoji(emoji) }
        root.emoji.onBackspace = { backspace() }
        root.emoji.onLetters = { root.show(KeyboardRootView.Mode.KEYS) }
        root.emoji.recent = prefs.recentEmoji

        root.settings.listener = object : SettingsPanelView.Listener {
            override fun onTheme(theme: KeyboardTheme) {
                prefs.theme = theme
                root.applyPrefs()
                root.settings.invalidate()
            }

            override fun onToggle(id: String, value: Boolean) {
                when (id) {
                    "number_row" -> prefs.numberRow = value
                    "clipboard_strip" -> prefs.clipboardStrip = value
                    "haptics" -> prefs.haptics = value
                    "sound" -> prefs.sound = value
                    "auto_capitalise" -> prefs.autoCapitalise = value
                }
                root.applyPrefs()
                root.settings.invalidate()
                if (id == "number_row") updateLayout()
            }

            override fun onClipboardRows(rows: Int) {
                prefs.clipboardRows = rows
                root.applyPrefs()
                root.settings.invalidate()
            }

            override fun onClearLearned() = prefs.forgetLearned()

            override fun onSwitchKeyboard() = switchToAnotherKeyboard()

            override fun onDone() = root.show(KeyboardRootView.Mode.KEYS)
        }

        // The system lays the keyboard window out over the navigation bar, so
        // the last row would sit underneath it. The bar's inset is the keyboard's
        // bottom padding.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            view.setPadding(0, 0, 0, bottom)
            view.requestLayout()
            insets
        }

        updateLayout()
        return root
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        page = pageFor(info)
        composing = ""
        undoStack.clear()

        // A field that is about to receive its first word starts capitalised.
        shift = if (prefs.autoCapitalise && atSentenceStart()) ShiftState.ON else ShiftState.OFF

        keyboard?.show(KeyboardRootView.Mode.KEYS)
        updateLayout()
        updateSuggestions()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshClipboard()
        handler.removeCallbacks(clipboardTick)
        handler.postDelayed(clipboardTick, TICK_MS)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(clipboardTick)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        commitWord()
        undoStack.clear()
        super.onFinishInput()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // ---------------------------------------------------------------- layout

    private fun pageFor(info: EditorInfo?): Page {
        val type = info?.inputType ?: InputType.TYPE_CLASS_TEXT
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            -> Page.NUMBERS

            else -> Page.LETTERS
        }
    }

    private fun updateLayout() {
        val root = keyboard ?: return
        val rows = when (page) {
            Page.LETTERS -> KeyboardLayout.letters(shift, prefs.numberRow)
            Page.SYMBOLS -> KeyboardLayout.symbols(0)
            Page.SYMBOLS_MORE -> KeyboardLayout.symbols(1)
            Page.NUMBERS -> KeyboardLayout.numbers()
        }
        root.setKeys(rows, shift)
    }

    private fun setShift(state: ShiftState) {
        if (shift == state) return
        shift = state
        updateLayout()
        updateSuggestions()
    }

    // ------------------------------------------------------------------ keys

    private fun handleKey(key: Key) {
        when (key.kind) {
            KeyKind.CHAR -> typeText(key.output)
            KeyKind.SHIFT -> setShift(
                when (shift) {
                    ShiftState.OFF -> ShiftState.ON
                    ShiftState.ON -> ShiftState.LOCK
                    ShiftState.LOCK -> ShiftState.OFF
                },
            )

            KeyKind.BACKSPACE -> {
                keyboard?.keys?.cancelRepeat()
                backspace()
            }

            KeyKind.SPACE -> commitSpace()
            KeyKind.ENTER -> pressEnter()
            KeyKind.TO_LETTERS -> {
                page = Page.LETTERS
                updateLayout()
            }

            KeyKind.TO_SYMBOLS -> {
                page = when (page) {
                    Page.LETTERS, Page.NUMBERS -> Page.SYMBOLS
                    Page.SYMBOLS -> Page.SYMBOLS_MORE
                    Page.SYMBOLS_MORE -> Page.SYMBOLS
                }
                updateLayout()
            }

            KeyKind.TO_NUMBERS -> {
                page = Page.NUMBERS
                updateLayout()
            }

            KeyKind.EMOJI -> keyboard?.show(KeyboardRootView.Mode.EMOJI)
            KeyKind.SETTINGS -> keyboard?.show(KeyboardRootView.Mode.SETTINGS)
        }
    }

    private fun typeText(text: String) {
        val connection = currentInputConnection ?: return
        val cased = if (shift == ShiftState.OFF) text else text.uppercase(Locale.getDefault())
        val isLetter = cased.length == 1 && cased[0].isLetter()
        val apostrophe = cased == "'" && composing.isNotEmpty()

        if (isLetter || apostrophe) {
            composing += cased
            connection.setComposingText(composing, 1)
            if (shift == ShiftState.ON) setShift(ShiftState.OFF)
            updateSuggestions()
            return
        }

        commitWord()
        connection.commitText(cased, 1)
        pushUndo(cased)

        if (prefs.autoCapitalise && cased in SENTENCE_ENDS) setShift(ShiftState.ON) else updateSuggestions()
    }

    private fun commitSpace() {
        val connection = currentInputConnection ?: return
        commitWord()
        connection.commitText(" ", 1)
        pushUndo(" ")
        updateSuggestions()
    }

    private fun pressEnter() {
        val connection = currentInputConnection ?: return
        commitWord()

        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val suppressed = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

        if (!suppressed && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.performEditorAction(action)
        } else {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            pushUndo("\n")
        }
        updateSuggestions()
    }

    private fun backspace() {
        val connection = currentInputConnection ?: return

        if (composing.isNotEmpty()) {
            composing = composing.dropLast(1)
            if (composing.isEmpty()) {
                connection.finishComposingText()
            } else {
                connection.setComposingText(composing, 1)
            }
            updateSuggestions()
            return
        }

        connection.deleteSurroundingText(1, 0)
        forgetLastChar()
        updateSuggestions()
    }

    private fun undo() {
        val connection = currentInputConnection ?: return

        if (composing.isNotEmpty()) {
            connection.finishComposingText()
            connection.deleteSurroundingText(composing.length, 0)
            composing = ""
        } else {
            val last = undoStack.removeLastOrNull() ?: return
            connection.deleteSurroundingText(last.length, 0)
        }
        updateSuggestions()
    }

    private fun pickSuggestion(word: String) {
        val connection = currentInputConnection ?: return
        val cased = if (shift == ShiftState.OFF) {
            word
        } else {
            word.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        }

        val text = "$cased "
        connection.commitText(text, 1)
        dictionary.learn(word)
        pushUndo(text)

        composing = ""
        setShift(ShiftState.OFF)
        updateSuggestions()
    }

    private fun paste(text: String) {
        val connection = currentInputConnection ?: return
        commitWord()
        connection.commitText(text, 1)
        pushUndo(text)
        updateSuggestions()
    }

    private fun commitEmoji(emoji: String) {
        val connection = currentInputConnection ?: return
        commitWord()
        connection.commitText(emoji, 1)
        pushUndo(emoji)

        prefs.recentEmoji = listOf(emoji) + prefs.recentEmoji
        keyboard?.setRecentEmoji(prefs.recentEmoji)
        updateSuggestions()
    }

    /** Ends the current word: the platform stops treating it as composing. */
    private fun commitWord() {
        if (composing.isEmpty()) return
        currentInputConnection?.finishComposingText()
        dictionary.learn(composing)
        pushUndo(composing)
        composing = ""
    }

    private fun pushUndo(text: String) {
        if (text.isEmpty()) return
        undoStack.addLast(text)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
    }

    /** A manual delete trims the same characters off the undo history. */
    private fun forgetLastChar() {
        val last = undoStack.removeLastOrNull() ?: return
        if (last.length > 1) undoStack.addLast(last.dropLast(1))
    }

    private fun updateSuggestions() {
        val root = keyboard ?: return
        val words = if (composing.isEmpty()) emptyList() else dictionary.suggest(composing)
        val cased = words.map { word ->
            if (shift == ShiftState.OFF) word else word.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        }
        root.setSuggestions(cased, composing.isNotEmpty() || undoStack.isNotEmpty())
    }

    private fun atSentenceStart(): Boolean {
        val before = currentInputConnection?.getTextBeforeCursor(4, 0)?.toString() ?: return true
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return true
        return trimmed.last().toString() in SENTENCE_ENDS
    }

    // ------------------------------------------------------------- clipboard

    private fun refreshClipboard() {
        val root = keyboard ?: return

        val items = LinkedHashSet<String>()
        CompanionService.Status.lastPcClipboard
            ?.takeIf { it.isNotBlank() }
            ?.let { items += tidy(it) }
        readOwnClipboard()
            ?.takeIf { it.isNotBlank() }
            ?.let { items += tidy(it) }
        CompanionService.Status.pcClipboardHistory.forEach {
            if (it.isNotBlank()) items += tidy(it)
        }

        root.setClipboardEntries(items.filter { it.isNotEmpty() }.take(CLIPBOARD_LIMIT))
    }

    private fun tidy(text: String): String =
        text.replace('\n', ' ').replace('\t', ' ').trim()

    /** The phone's own clipboard - the default keyboard may read it. */
    private fun readOwnClipboard(): String? = try {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            null
        } else {
            clip.getItemAt(0).coerceToText(this).toString()
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the clipboard: ${e.message}")
        null
    }

    /**
     * Lets the user pick a different keyboard for typing.
     *
     * What this costs: picking another keyboard makes it the default, so the
     * app loses the clipboard privilege until the user picks A+ Connect again.
     */
    private fun switchToAnotherKeyboard() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private companion object {
        const val TAG = "AplusConnect"
        const val TICK_MS = 2_000L
        const val UNDO_LIMIT = 80
        const val CLIPBOARD_LIMIT = 12
        val SENTENCE_ENDS = setOf(".", "!", "?", "\n")
    }
}