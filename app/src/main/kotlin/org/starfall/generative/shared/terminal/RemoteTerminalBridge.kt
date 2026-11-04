package org.starfall.generative.shared.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.starfall.generative.shared.model.remote.RemoteTerminalSession
import org.starfall.generative.shared.model.remote.TerminalInputMode

/**
 * Adapter between a remote byte stream and Termux's maintained ANSI terminal renderer. A tiny
 * sleeping local session is used only to give TerminalView its emulator lifecycle; remote output
 * is rendered here, while input is either buffered locally or routed to the JSch channel by mode.
 */
internal class RemoteTerminalBridge(
    private val context: android.content.Context,
    initialInputMode: TerminalInputMode,
    private val hostWindow: Window? = null
) :
    TerminalSessionClient,
    TerminalViewClient {
    var session: TerminalSession? = null
    var view: TerminalView? = null
    var inputView: RemoteTerminalView? = null
    private val input = RemoteTerminalInput(
        initialInputMode = initialInputMode,
        sendRemote = ::sendRemote,
        snapToBottom = ::snapToBottom,
        isCursorKeysApplicationMode = { view?.mEmulator?.isCursorKeysApplicationMode == true }
    )
    private var remote: RemoteTerminalSession? = null
    private var closed = false
    private var emulatorReady = false
    // Everything the remote side printed (most recent part), replayed into a fresh emulator when the
    // terminal screen is reopened, so a session keeps its screen after the user leaves and returns.
    private val history = java.util.ArrayDeque<ByteArray>()
    private var historySize = 0

    /** Called when the user pinches: true = bigger text, false = smaller text. */
    var onZoomStep: (Boolean) -> Unit = {}
    /**
     * When on, taps and drags inside full-screen apps are sent to the server as mouse reports
     * (tap = left click, drag = wheel) even if the app never asked for mouse tracking.
     */
    var forceMouse by mutableStateOf(false)
    var imeEnabled by mutableStateOf(false)
        private set
    var ctrlActive: Boolean
        get() = input.ctrlActive
        set(value) { input.ctrlActive = value }
    var altActive: Boolean
        get() = input.altActive
        set(value) { input.altActive = value }
    var shiftActive: Boolean
        get() = input.shiftActive
        set(value) { input.shiftActive = value }
    val inputMode: TerminalInputMode get() = input.inputMode

    val localInputForIme: String get() = input.localInput
    val localCursorForIme: Int get() = input.localCursorPosition
    fun updateLocalImeDraft(value: String, cursor: Int) = input.updateLocalDraftFromIme(value, cursor)
    fun sendImeText(value: String) = input.sendImeText(value)
    fun sendImeKey(keyCode: Int, event: KeyEvent): Boolean {
        val handled = input.sendKey(keyCode, event)
        syncLocalImeDraft()
        return handled
    }

    private fun syncLocalImeDraft() {
        if (inputMode.usesLocalInput) inputView?.syncLocalImeDraft(input.localInput, input.localCursorPosition)
    }

    fun updateImeEnabled(enabled: Boolean) {
        // Composition is a Local Input feature only; Remote always uses Termux raw keys.
        val localImeEnabled = enabled && inputMode.usesLocalInput
        if (imeEnabled == localImeEnabled) return
        inputView?.finishImeComposition()
        imeEnabled = localImeEnabled
        inputView?.let { host ->
            val focused = host.hasFocus()
            host.updateInputMode()
            if (focused) {
                host.inputTarget.requestFocus()
                context.getSystemService(InputMethodManager::class.java)?.restartInput(host.inputTarget)
            }
        }
    }

    fun setInputMode(mode: TerminalInputMode) {
        inputView?.finishImeComposition()
        val oldMode = inputMode
        input.changeInputMode(mode)
        if (mode != oldMode) {
            if (!mode.usesLocalInput && imeEnabled) {
                // Disable composition before the view can create an IME connection in Remote mode.
                updateImeEnabled(false)
            } else {
                inputView?.restartImeConnection()
            }
        }
    }

    private var themeBackground: Int? = null
    private var themeForeground: Int? = null

    /**
     * Applies the app theme to the renderer's default terminal colors. The colors are remembered
     * because the emulator does not exist yet when the view is created (it is built on the first
     * layout) and [onEmulatorSet] resets its palette, so they are re-applied there.
     */
    fun setThemeColors(background: Int, foreground: Int) {
        themeBackground = background
        themeForeground = foreground
        applyThemeColors()
        view?.setBackgroundColor(background)
        view?.invalidate()
    }

    private fun applyThemeColors() {
        val background = themeBackground ?: return
        val foreground = themeForeground ?: return
        session?.emulator?.mColors?.mCurrentColors?.let { colors ->
            // The stock ANSI palette is made for dark backgrounds (white/bright text disappears on a
            // light one), so pick the palette that matches the theme.
            val palette = if (ColorUtils.calculateLuminance(background) > 0.5) LIGHT_PALETTE else DARK_PALETTE
            palette.forEachIndexed { index, color -> colors[index] = color }
            colors[DEFAULT_FOREGROUND_COLOR] = foreground
            colors[DEFAULT_BACKGROUND_COLOR] = background
            colors[DEFAULT_CURSOR_COLOR] = foreground
        }
    }

    fun localDraftForDisplay(): String? = input.localDraftForDisplay()

    /**
     * Creates the silent local session TerminalView needs for its emulator lifecycle. Called again
     * whenever the screen is reopened: the new emulator is refilled from [history].
     */
    fun createSession(filesDir: java.io.File): TerminalSession {
        session?.finishIfRunning()
        emulatorReady = false
        // The renderer receives remote bytes through append() and sends input through sendRemote().
        val created = TerminalSession(
            // The array is the complete argv (no program name is prepended), so argv[0] must come
            // first; without it sh took "-c" as its name and tried to run the command as a script
            // file ("exec /system/bin/cat >/dev/null: No such file or directory", code 127).
            // stty -echo stops the pty from echoing the emulator's own query replies back on screen.
            "/system/bin/sh", filesDir.absolutePath,
            arrayOf("sh", "-c", "/system/bin/stty -echo 2>/dev/null; exec /system/bin/cat >/dev/null"),
            emptyArray(), 2_000, this
        )
        session = created
        return created
    }

    /** The screen was destroyed (user left); the remote session keeps running and recording. */
    fun detachView(released: RemoteTerminalView) {
        if (inputView !== released) return
        released.finishImeComposition()
        view = null
        inputView = null
        emulatorReady = false
    }

    fun attachRemote(terminal: RemoteTerminalSession) {
        remote = terminal
        closed = false
        lostReported.set(false)
        // close() shuts the sender down, but this bridge is reused when the user retries after a lost connection.
        if (sender.isShutdown) sender = newSender()
        reportSize()
    }

    /** Sends the emulator's current size to the remote pty, off the main thread. */
    private fun reportSize() {
        val terminal = remote ?: return
        val emulator = session?.emulator ?: return
        val columns = emulator.mColumns
        val rows = emulator.mRows
        Thread { terminal.resize(columns, rows) }.apply { isDaemon = true }.start()
    }

    fun append(bytes: ByteArray) {
        if (closed) return
        if (bytes.isEmpty()) return
        input.clearSubmittedInput()
        record(bytes)
        if (emulatorReady) writeToEmulator(bytes)
    }

    private fun writeToEmulator(bytes: ByteArray) {
        val emulator = session?.emulator ?: return
        val terminalView = view
        // TerminalView.onScreenUpdated() snaps back to the bottom on every update, so while a command
        // kept printing (build, tail -f, ...) the user could never scroll up. When the user has
        // scrolled back, keep the same lines on screen instead: shift the top row by the number of
        // rows that scrolled into the history and just redraw.
        val scrolledBack = terminalView != null && terminalView.topRow < 0 && !emulator.isAlternateBufferActive
        // A malformed sequence must not throw out of the reader and freeze this session's screen.
        try {
            emulator.append(bytes, bytes.size)
        } catch (_: RuntimeException) {
            return
        }
        if (scrolledBack) {
            val visibleView = checkNotNull(terminalView)
            val transcript = emulator.screen.activeTranscriptRows
            visibleView.topRow = (visibleView.topRow - emulator.scrollCounter).coerceIn(-transcript, 0)
            emulator.clearScrollCounter()
            visibleView.invalidate()
        } else {
            terminalView?.onScreenUpdated()
        }
    }

    private fun record(bytes: ByteArray) {
        history.addLast(bytes)
        historySize += bytes.size
        while (historySize > MAX_HISTORY_BYTES && history.size > 1) historySize -= history.removeFirst().size
    }

    /** Jumps back to the live screen when the user types, like any terminal does. */
    private fun snapToBottom() {
        val terminalView = view ?: return
        if (terminalView.topRow != 0) {
            terminalView.topRow = 0
            terminalView.invalidate()
        }
    }

    fun close() {
        closed = true
        remote = null
        session?.finishIfRunning()
        session = null
        view = null
        inputView = null
        sender.shutdown()
    }

    // Socket writes throw NetworkOnMainThreadException on Android's main thread, and keys/paste
    // arrive on the main thread. They used to be swallowed by runCatching, so nothing typed or
    // pasted ever reached the server. A single-thread executor keeps the writes ordered.
    @Volatile private var sender = newSender()

    private fun newSender() = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "remote-terminal-input").apply { isDaemon = true }
    }

    /**
     * Called once, on the main thread, when writing to the remote shell proves the channel is gone
     * (the view would otherwise keep showing a terminal that silently swallows every key).
     */
    var onRemoteLost: (() -> Unit)? = null
    private val lostReported = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun reportRemoteLost() {
        if (closed || !lostReported.compareAndSet(false, true)) return
        mainHandler.post { if (!closed) onRemoteLost?.invoke() }
    }

    private fun sendRemote(bytes: ByteArray) {
        if (closed) return
        val terminal = remote ?: return
        val output = terminal.output
        try {
            sender.execute {
                try {
                    output.write(bytes)
                    output.flush()
                } catch (_: Throwable) {
                    // A failed write used to be swallowed, leaving a dead terminal on screen.
                    reportRemoteLost()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Sends an xterm SGR (1006) mouse report; [column] and [row] are 1-based cells. */
    fun sendMouseReport(button: Int, column: Int, row: Int, pressed: Boolean) {
        sendRemote("\u001b[<$button;$column;$row${if (pressed) 'M' else 'm'}".toByteArray(Charsets.US_ASCII))
    }

    private fun sendText(value: String) {
        inputView?.finishImeComposition()
        input.sendText(value)
        syncLocalImeDraft()
    }

    fun sendChar(char: Char, forceRemote: Boolean = false) {
        inputView?.finishImeComposition()
        input.sendChar(char, forceRemote)
        syncLocalImeDraft()
    }

    /** Sends an escape sequence, adding xterm modifier parameters / application cursor mode. */
    fun sendSpecial(
        sequence: String,
        ctrl: Boolean = ctrlActive,
        alt: Boolean = altActive,
        forceRemote: Boolean = false
    ) {
        inputView?.finishImeComposition()
        input.sendSpecial(sequence, ctrl, alt, forceRemote)
        syncLocalImeDraft()
    }

    private companion object {
        const val DEFAULT_FOREGROUND_COLOR = 256
        const val DEFAULT_BACKGROUND_COLOR = 257
        const val DEFAULT_CURSOR_COLOR = 258
        const val MAX_HISTORY_BYTES = 512 * 1024
        val DARK_PALETTE = intArrayOf(
            0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
            0xFF6495ED.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
        )
        val LIGHT_PALETTE = intArrayOf(
            0xFF1F2328.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(), 0xFF9A6700.toInt(),
            0xFF1565C0.toInt(), 0xFF8E24AA.toInt(), 0xFF00796B.toInt(), 0xFF57606A.toInt(),
            0xFF6E7781.toInt(), 0xFFE53935.toInt(), 0xFF388E3C.toInt(), 0xFFB7791F.toInt(),
            0xFF1E88E5.toInt(), 0xFFAB47BC.toInt(), 0xFF00897B.toInt(), 0xFF24292F.toInt()
        )
    }

    // TerminalSessionClient
    override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        (context.getSystemService(ClipboardManager::class.java))?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(::sendText)
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) { applyThemeColors(); view?.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit

    // TerminalViewClient
    // Pinch changes the font size (like Termux) instead of scaling the view.
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            onZoomStep(scale > 1f)
            return 1.0f
        }
        return scale
    }

    private fun focusAndShowKeyboard() {
        val terminalView = inputView?.inputTarget ?: view ?: return
        terminalView.post {
            if (closed) return@post
            terminalView.requestFocus()
            hostWindow?.let { window ->
                WindowCompat.getInsetsController(window, terminalView).show(WindowInsetsCompat.Type.ime())
            }
            val imm = context.getSystemService(InputMethodManager::class.java)
            // showSoftInput fails while the view or its window has not gained focus yet (just after the
            // screen opens or a session switch); retry once instead of leaving the terminal without a keyboard.
            if (!imm.showSoftInput(terminalView, 0)) {
                terminalView.postDelayed({
                    if (!closed && (inputView?.inputTarget ?: view) === terminalView) {
                        terminalView.requestFocus()
                        imm.showSoftInput(terminalView, 0)
                    }
                }, 120)
            }
        }
    }

    /**
     * Moves input focus to the terminal view so key events and the IME reach this session.
     * Switching sessions must re-focus: the previously active view kept the focus (and the IME
     * connection), which is why typed text used to land in a hidden session.
     */
    fun focusTerminalView(showKeyboard: Boolean = false) {
        val terminalView = inputView?.inputTarget ?: view ?: return
        terminalView.post {
            if (closed) return@post
            terminalView.requestFocus()
            if (showKeyboard) focusAndShowKeyboard()
        }
    }

    fun hideKeyboard() {
        val terminalView = inputView?.inputTarget ?: view ?: return
        val window = hostWindow
        if (window != null) {
            WindowCompat.getInsetsController(window, terminalView).hide(WindowInsetsCompat.Type.ime())
        } else {
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(terminalView.windowToken, 0)
        }
    }

    fun toggleKeyboard() {
        val terminalView = view ?: return
        val visible = ViewCompat.getRootWindowInsets(terminalView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) hideKeyboard() else focusAndShowKeyboard()
    }

    override fun onSingleTapUp(e: MotionEvent) {
        if (!forceMouse) focusAndShowKeyboard()
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean =
        inputView?.sendKeyToIme(e) ?: input.sendKey(keyCode, e)
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = true
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = ctrlActive
    override fun readAltKey(): Boolean = altActive
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        input.sendCodePoint(codePoint, ctrlDown, altActive)
        input.clearModifiers()
        return true
    }
    // TerminalView calls this on EVERY size change (e.g. when the keyboard shows or hides). Reset
    // the local renderer once, but don't focus the view or open the IME during terminal setup.
    override fun onEmulatorSet() {
        val first = !emulatorReady
        emulatorReady = true
        if (first) {
            session?.reset()
            // reset() restores the stock white-on-black colors: re-apply the theme before replaying output.
            applyThemeColors()
            history.forEach(::writeToEmulator)
        }
        // TerminalView.onDraw() draws a hard-coded black canvas without an emulator.
        // Reveal it only when it can render with the configured theme colors.
        view?.let { terminalView ->
            if (terminalView.mEmulator != null && terminalView.visibility != View.VISIBLE) {
                terminalView.visibility = View.VISIBLE
            }
        }
        reportSize()
    }
}
