package org.starfall.generative.shared.subfeatures.terminal

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.termux.view.TerminalView

/** Keeps the remote terminal and the optional local editor independently focusable. */
internal class RemoteTerminalView(context: Context, private val bridge: RemoteTerminalBridge) : FrameLayout(context) {
    val terminalView = TerminalView(context, null)
    val inputTarget: View get() = if (isFocused && onCheckIsTextEditor()) this else terminalView
    private var composingConnection: RemoteTerminalInputConnection? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        setOnFocusChangeListener { _, focused ->
            if (!focused) finishImeComposition()
            bridge.localInputFocused = focused
        }
        // Termux paints solid black while its emulator is null, ignoring the View background.
        // Keep the renderer hidden until onEmulatorSet applies the current theme palette;
        // the FrameLayout behind it displays the actual screen background in the meantime.
        terminalView.visibility = View.INVISIBLE
        addView(terminalView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Termux's text-selection toolbar uses More -> View.showContextMenu(). It stores the
        // selected text until the menu closes, so add our action at precisely that point.
        terminalView.setOnCreateContextMenuListener { menu, _, _ ->
            val selectedText = terminalView.storedSelectedText
            if (!selectedText.isNullOrEmpty()) {
                menu.add(org.starfall.generative.R.string.remote_server_terminal_open_browser)
                    .setOnMenuItemClickListener {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(selectedText))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            // No app can open the selected text as a URI.
                        } catch (_: SecurityException) {
                            // The device does not permit this URI to open.
                        }
                        terminalView.unsetStoredSelectedText()
                        true
                    }
            }
        }
        updateInputMode()
    }

    fun updateInputMode() {
        // Visibility does not select an input destination or disable terminal focus.
        // Only hiding the editor while it owns focus needs a handoff to the terminal.
        if (!bridge.inputMode.usesLocalInput && isFocused) terminalView.requestFocus()
    }

    override fun onCheckIsTextEditor(): Boolean = bridge.inputMode.usesLocalInput

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        finishImeComposition()
        composingConnection = null
        if (!onCheckIsTextEditor()) return null
        if (!bridge.imeEnabled) return terminalView.onCreateInputConnection(outAttrs)
        // Single-line editor + Send action: some keyboards do not emit Enter for multiline text.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = bridge.localCursorForIme
        outAttrs.initialSelEnd = outAttrs.initialSelStart
        return RemoteTerminalInputConnection(
            this,
            onEdit = bridge::updateLocalImeDraft,
            onKey = bridge::sendImeKey,
            initialText = bridge.localInputForIme,
            initialCursor = bridge.localCursorForIme
        ).also { composingConnection = it }
    }

    fun restartImeConnection() {
        finishImeComposition()
        composingConnection = null
        if (onCheckIsTextEditor() && isFocused) {
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.restartInput(this)
        }
    }

    fun finishImeComposition() {
        composingConnection?.finishComposingText()
    }

    fun syncLocalImeDraft(text: String, cursor: Int) {
        composingConnection?.syncLocalDraft(text, cursor)
    }

    fun sendKeyToIme(event: KeyEvent): Boolean? =
        if (isFocused && bridge.imeEnabled && onCheckIsTextEditor()) composingConnection?.sendKeyEvent(event) else null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        return sendKeyToIme(event) ?: bridge.sendImeKey(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) super.onKeyUp(keyCode, event) else true
}
