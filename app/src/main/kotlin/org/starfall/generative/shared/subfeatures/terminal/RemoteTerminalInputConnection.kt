package org.starfall.generative.shared.subfeatures.terminal

import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/** Local-only IME editor. Keeps committed text editable so keyboards can revise syllables
 * and select Pinyin candidates without duplicating characters in the terminal draft. */
internal class RemoteTerminalInputConnection(
    private val view: View,
    private val onEdit: (String, Int) -> Unit,
    private val onKey: (Int, KeyEvent) -> Boolean,
    initialText: String = "",
    initialCursor: Int = initialText.length
) : BaseInputConnection(view, true) {
    private var syncingExternal = false

    init {
        val buffer = editable
        buffer?.append(initialText)
        if (buffer != null) Selection.setSelection(buffer, initialCursor.coerceIn(0, buffer.length))
        publish()
    }

    private fun publish() {
        if (syncingExternal) return
        val buffer = editable ?: return
        val text = buffer.toString()
        onEdit(text, Selection.getSelectionEnd(buffer).coerceIn(0, text.length))
        notifyImeSelection()
    }

    private fun notifyImeSelection() {
        val buffer = editable ?: return
        // A custom editor must report selection/composition changes back to the keyboard.
        // Clearing the buffer alone leaves its previous word active in the candidate strip.
        view.context.getSystemService(InputMethodManager::class.java)?.updateSelection(
            view,
            Selection.getSelectionStart(buffer),
            Selection.getSelectionEnd(buffer),
            getComposingSpanStart(buffer),
            getComposingSpanEnd(buffer)
        )
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val result = super.setComposingText(text, newCursorPosition)
        publish()
        return result
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val result = super.setComposingRegion(start, end)
        publish()
        return result
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text?.toString() == "\n" || text?.toString() == "\r") {
            submit()
            return true
        }
        val result = super.commitText(text, newCursorPosition)
        publish()
        return result
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val result = super.setSelection(start, end)
        publish()
        return result
    }

    override fun finishComposingText(): Boolean {
        val result = super.finishComposingText()
        publish()
        return result
    }

    /** Mirrors external local changes (extra keys, history navigation and paste) into the IME. */
    fun syncLocalDraft(text: String, cursor: Int) {
        val buffer = editable ?: return
        val position = cursor.coerceIn(0, text.length)
        if (buffer.toString() == text && Selection.getSelectionEnd(buffer) == position) return
        syncingExternal = true
        try {
            removeComposingSpans(buffer)
            buffer.replace(0, buffer.length, text)
            Selection.setSelection(buffer, position)
        } finally {
            syncingExternal = false
        }
        notifyImeSelection()
    }

    private fun submit() {
        finishComposingText()
        onKey(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        val buffer = editable ?: return
        buffer.clear()
        Selection.setSelection(buffer, 0)
        publish()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        deleteAroundCursor(beforeLength, afterLength, inCodePoints = false)

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteAroundCursor(beforeLength, afterLength, inCodePoints = true)

    private fun deleteAroundCursor(before: Int, after: Int, inCodePoints: Boolean): Boolean {
        if (before < 0 || after < 0) return false
        val buffer = editable ?: return false
        val start = Selection.getSelectionStart(buffer).coerceIn(0, buffer.length)
        val end = Selection.getSelectionEnd(buffer).coerceIn(start, buffer.length)
        val beforeAvailable = if (inCodePoints) Character.codePointCount(buffer, 0, start) else start
        val afterAvailable = if (inCodePoints) Character.codePointCount(buffer, end, buffer.length) else buffer.length - end
        val removeBefore = minOf(before, beforeAvailable)
        val removeAfter = minOf(after, afterAvailable)
        var left = if (inCodePoints) Character.offsetByCodePoints(buffer, start, -removeBefore) else start - removeBefore
        var right = if (inCodePoints) Character.offsetByCodePoints(buffer, end, removeAfter) else end + removeAfter
        if (left > 0 && left < buffer.length && Character.isLowSurrogate(buffer[left]) &&
            Character.isHighSurrogate(buffer[left - 1])) left--
        if (right > 0 && right < buffer.length && Character.isHighSurrogate(buffer[right - 1]) &&
            Character.isLowSurrogate(buffer[right])) right++
        buffer.delete(end, right)
        buffer.delete(left, start)
        Selection.setSelection(buffer, left)
        publish()
        repeat(before - removeBefore) { sendDelete(KeyEvent.KEYCODE_DEL) }
        repeat(after - removeAfter) { sendDelete(KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    private fun sendDelete(keyCode: Int) {
        onKey(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> { submit(); true }
            KeyEvent.KEYCODE_DEL -> deleteSurroundingText(1, 0)
            KeyEvent.KEYCODE_FORWARD_DEL -> deleteSurroundingText(0, 1)
            KeyEvent.KEYCODE_SPACE -> {
                if (event.isCtrlPressed || event.isAltPressed) {
                    finishComposingText()
                    onKey(event.keyCode, event)
                } else {
                    // Some keyboards dispatch the space as a hardware key rather than commitText.
                    commitText(" ", 1)
                }
            }
            else -> {
                finishComposingText()
                onKey(event.keyCode, event)
            }
        }
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        if (actionCode == EditorInfo.IME_ACTION_NONE) return false
        submit()
        return true
    }
}
