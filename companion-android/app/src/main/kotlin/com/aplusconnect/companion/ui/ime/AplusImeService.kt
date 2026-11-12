package com.aplusconnect.companion.ui.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.aplusconnect.companion.service.CompanionService

/**
 * The companion app's input method.
 *
 * Holding the default-IME role is what gives the app privileged clipboard
 * access on Android: the platform lets the default keyboard read the
 * clipboard in the background, and only that. It is also the one route that
 * needs no accessibility service - and an accessibility service is what makes
 * Android block contactless payments.
 *
 * The keyboard itself is a clipboard list rather than a full keyboard: the user
 * pastes from the computer or the phone with one tap, and taps "ABC" once to
 * get their usual keyboard for typing.
 */
class AplusImeService : InputMethodService() {

    private var keyboard: ClipboardKeyboardView? = null

    override fun onCreateInputView(): View {
        val view = ClipboardKeyboardView(this)
        view.listener = object : ClipboardKeyboardView.Listener {
            override fun onPaste(text: String) = commit(text)
            override fun onKey(key: ClipboardKeyboardView.Key) = handleKey(key)
        }
        keyboard = view
        return view
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refresh()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        refresh()
    }

    /** Shows the phone's own clipboard and whatever the computer copied. */
    private fun refresh() {
        val view = keyboard ?: return

        val items = mutableListOf<String>()

        val fromPc = CompanionService.Status.lastPcClipboard
        if (!fromPc.isNullOrEmpty()) items.add(fromPc)

        val own = readOwnClipboard()
        if (!own.isNullOrEmpty() && own != fromPc) items.add(own)

        items.addAll(CompanionService.Status.pcClipboardHistory)

        view.entries = items.map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }
    }

    /** The phone's own clipboard - an input method may read it. */
    private fun readOwnClipboard(): String? = try {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(this).toString()
    } catch (e: Exception) {
        null
    }

    private fun handleKey(key: ClipboardKeyboardView.Key) {
        when (key.kind) {
            ClipboardKeyboardView.Kind.SPACE -> commit(" ")
            ClipboardKeyboardView.Kind.ENTER ->
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                )
            ClipboardKeyboardView.Kind.BACKSPACE -> currentInputConnection?.deleteSurroundingText(1, 0)
            ClipboardKeyboardView.Kind.SWITCH_KEYBOARD -> switchToAnotherKeyboard()
            ClipboardKeyboardView.Kind.PASTE -> Unit
        }
    }

    /** Hands the keyboard back to the user's usual one, for this field. */
    private fun switchToAnotherKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        // showInputMethodPicker() lets the user pick the keyboard they want
        // for this field; the default stays ours, so the clipboard keeps
        // syncing in the background.
        imm?.showInputMethodPicker()
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }
}
