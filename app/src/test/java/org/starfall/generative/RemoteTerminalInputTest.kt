package org.starfall.generative

import android.content.Context
import android.app.Activity
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowInputMethodManager
import org.starfall.generative.shared.model.remote.TerminalInputMode
import org.starfall.generative.shared.model.remote.RemoteTerminalSession
import org.starfall.generative.database.preferences.remoteTerminalImePreferenceKey
import org.starfall.generative.database.preferences.remoteTerminalInputModePreferenceKey
import org.starfall.generative.database.preferences.remoteTerminalInputPreferences
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalBridge
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalInput
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalInputConnection
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class RemoteTerminalInputTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class Terminal(mode: TerminalInputMode, context: Context) {
        val output = ByteArrayOutputStream()
        val input = RemoteTerminalInput(mode, output::write, {}, { false })
        val connection = RemoteTerminalInputConnection(
            View(context), input::updateLocalDraftFromIme, input::sendKey
        )
        fun text() = output.toString("UTF-8")
    }

    @Test
    fun localImeComposesVietnameseUntilEnter() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        terminal.connection.setComposingText("tieng", 1)
        terminal.connection.setComposingText("tiếng", 1)
        assertEquals("", terminal.text())
        assertEquals("tiếng", terminal.input.localInput)

        terminal.connection.commitText("tiếng Việt 🎉", 1)
        terminal.connection.finishComposingText()
        assertEquals("", terminal.text())
        assertEquals("tiếng Việt 🎉", terminal.input.localInput)
        terminal.connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals("tiếng Việt 🎉\r", terminal.text())
        assertEquals("", terminal.input.localInput)
    }

    @Test
    fun localImeCanCommitChinesePinyinCandidate() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        terminal.connection.setComposingText("nihao", 1)
        terminal.connection.commitText("你好", 1)
        assertEquals("你好", terminal.input.localInput)
        assertEquals("", terminal.text())
        terminal.connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals("你好\r", terminal.text())
    }

    @Test
    fun enterFinishesCompositionBeforeSubmittingTheCommand() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        terminal.connection.setComposingText("echo chào", 1)
        terminal.connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("echo chào\r", terminal.text())
    }

    @Implements(InputMethodManager::class)
    class ImeSelectionShadow : ShadowInputMethodManager() {
        var selection: List<Int>? = null

        @Implementation
        protected fun updateSelection(
            view: View, start: Int, end: Int, composingStart: Int, composingEnd: Int
        ) {
            selection = listOf(start, end, composingStart, composingEnd)
        }
    }

    @Test
    @Config(shadows = [ImeSelectionShadow::class])
    fun everyEnterRouteNotifiesImeThatThePreviousWordIsFinished() {
        val ime = Shadow.extract<ImeSelectionShadow>(context.getSystemService(InputMethodManager::class.java))
        val submitActions: List<(RemoteTerminalInputConnection) -> Unit> = listOf(
            { it.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) },
            { it.performEditorAction(EditorInfo.IME_ACTION_SEND) },
            { it.commitText("\n", 1) }
        )
        submitActions.forEach { submit ->
            val terminal = Terminal(TerminalInputMode.LOCAL, context)
            terminal.connection.setComposingText("chào", 1)
            assertEquals(listOf(4, 4, 0, 4), ime.selection)

            submit(terminal.connection)
            assertEquals("chào\r", terminal.text())
            assertEquals(listOf(0, 0, -1, -1), ime.selection)

            terminal.connection.setComposingText("mới", 1)
            submit(terminal.connection)
            assertEquals("chào\rmới\r", terminal.text())
        }
    }

    @Test
    @Config(shadows = [ImeSelectionShadow::class])
    fun externalDraftChangesClearThePreviousImeComposition() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        val ime = Shadow.extract<ImeSelectionShadow>(context.getSystemService(InputMethodManager::class.java))
        terminal.connection.setComposingText("chào", 1)
        terminal.connection.syncLocalDraft("", 0)
        assertEquals(listOf(0, 0, -1, -1), ime.selection)
    }

    @Test
    fun localImeBackspaceDoesNotSplitEmoji() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        terminal.connection.setComposingText("á🎉", 1)
        terminal.connection.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("á", terminal.input.localInput)
        assertEquals("", terminal.text())
        terminal.connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals("á\r", terminal.text())
    }

    @Test
    fun remoteModeRejectsImeEvenWhenExplicitlyEnabled() {
        val bridge = RemoteTerminalBridge(context, TerminalInputMode.REMOTE)
        val view = RemoteTerminalView(context, bridge)
        view.terminalView.setTerminalViewClient(bridge)
        bridge.view = view.terminalView
        bridge.inputView = view

        bridge.updateImeEnabled(true)
        assertFalse(bridge.imeEnabled)
        assertFalse(view.onCheckIsTextEditor())
        assertSame(view.terminalView, view.inputTarget)
        assertNull(view.onCreateInputConnection(EditorInfo()))
        bridge.close()
    }

    @Test
    fun switchingLocalImeToRemoteDisablesCompositionAndRestoresRawTerminalInput() {
        val bridge = RemoteTerminalBridge(context, TerminalInputMode.LOCAL)
        val view = RemoteTerminalView(context, bridge)
        view.terminalView.setTerminalViewClient(bridge)
        bridge.view = view.terminalView
        bridge.inputView = view
        bridge.updateImeEnabled(true)
        assertTrue(bridge.imeEnabled)
        assertTrue(view.onCheckIsTextEditor())
        assertSame(view.terminalView, view.inputTarget)
        val composing = EditorInfo()
        assertNotNull(view.onCreateInputConnection(composing))
        assertEquals(InputType.TYPE_CLASS_TEXT, composing.inputType and InputType.TYPE_MASK_CLASS)

        bridge.setInputMode(TerminalInputMode.REMOTE)
        assertFalse(bridge.imeEnabled)
        assertFalse(view.onCheckIsTextEditor())
        assertSame(view.terminalView, view.inputTarget)
        assertNull(view.onCreateInputConnection(EditorInfo()))
        bridge.updateImeEnabled(true)
        assertFalse(bridge.imeEnabled)

        bridge.setInputMode(TerminalInputMode.LOCAL)
        bridge.updateImeEnabled(true)
        assertTrue(bridge.imeEnabled)
        assertSame(view.terminalView, view.inputTarget)
        bridge.close()
    }

    @Test
    fun localEditorAndTerminalStayFocusableWhileLocalInputIsVisible() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bridge = RemoteTerminalBridge(activity, TerminalInputMode.REMOTE)
        val view = RemoteTerminalView(activity, bridge)
        bridge.inputView = view
        bridge.view = view.terminalView
        view.terminalView.setTerminalViewClient(bridge)
        view.terminalView.visibility = View.VISIBLE
        activity.setContentView(view)
        view.terminalView.requestFocus()
        assertTrue(view.terminalView.isFocused)
        val focusTargets = mutableListOf<View?>()
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused -> focusTargets.add(focused) }

        repeat(3) {
            bridge.setInputMode(TerminalInputMode.LOCAL)
            bridge.updateImeEnabled(true)
            assertTrue(view.terminalView.isFocused)
            assertTrue(view.terminalView.isFocusableInTouchMode)
            view.requestFocus()
            assertTrue(view.isFocused)
            assertTrue(bridge.localInputFocused)
            assertSame(view, view.inputTarget)
            val connection = view.onCreateInputConnection(EditorInfo())!!
            connection.setSelection(0, bridge.localInputForIme.length)
            connection.setComposingText("chào", 1)
            val tap = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
            try {
                bridge.onSingleTapUp(tap)
            } finally {
                tap.recycle()
            }
            assertTrue(view.terminalView.isFocused)
            assertFalse(bridge.localInputFocused)
            assertEquals(TerminalInputMode.LOCAL, bridge.inputMode)
            assertEquals("chào", bridge.localInputForIme)
            assertEquals("chào", bridge.localDraftForDisplay())
            // Changing the local editor's IME option must not steal focus back from the terminal.
            bridge.updateImeEnabled(false)
            assertTrue(view.terminalView.isFocused)
            view.requestFocus()
            assertNotNull(view.onCreateInputConnection(EditorInfo()))
            bridge.setInputMode(TerminalInputMode.REMOTE)
            assertTrue(view.terminalView.isFocused)
            assertEquals("chào", bridge.localInputForIme)
        }

        assertEquals(12, focusTargets.size)
        assertTrue(focusTargets.all { it === view || it === view.terminalView })
        bridge.close()
        activity.finish()
    }

    @Test
    fun remoteKeyEventsBypassTheVisibleLocalDraft() {
        val bridge = RemoteTerminalBridge(context, TerminalInputMode.LOCAL)
        val view = RemoteTerminalView(context, bridge)
        bridge.inputView = view
        view.terminalView.setTerminalViewClient(bridge)
        bridge.updateImeEnabled(true)
        bridge.updateLocalImeDraft("echo chào", 4)
        val writes = CountDownLatch(2)
        val output = object : ByteArrayOutputStream() {
            override fun flush() { writes.countDown() }
        }
        bridge.attachRemote(object : RemoteTerminalSession {
            override val id = "focus-routing"
            override val input = ByteArrayInputStream(byteArrayOf())
            override val output = output
            override val isOpen = true
            override val exitStatus = 0
            override fun resize(columns: Int, rows: Int) = Unit
            override fun close() = Unit
        })
        try {
            val session = bridge.createSession(context.filesDir)
            bridge.onCodePoint('r'.code, false, session)
            bridge.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER), session)
            assertTrue(writes.await(3, TimeUnit.SECONDS))
            assertEquals("r\r", output.toString("UTF-8"))
            assertEquals("echo chào", bridge.localInputForIme)
            assertEquals(4, bridge.localCursorForIme)
            assertEquals(TerminalInputMode.LOCAL, bridge.inputMode)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun enablingLocalImeDoesNotFocusAnInactiveTerminal() {
        val bridge = RemoteTerminalBridge(context, TerminalInputMode.REMOTE)
        val view = RemoteTerminalView(context, bridge)
        bridge.inputView = view
        bridge.setInputMode(TerminalInputMode.LOCAL)
        bridge.updateImeEnabled(true)
        assertFalse(view.hasFocus())
        bridge.close()
    }

    @Test
    fun localImeCanReplaceAlreadyCommittedVietnameseSyllablesWithoutDuplication() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        terminal.connection.commitText("cha", 1)
        terminal.connection.deleteSurroundingText(1, 0)
        terminal.connection.setComposingText("ào", 1)
        terminal.connection.commitText("ào", 1)
        assertEquals("", terminal.text())
        assertEquals("chào", terminal.input.localInput)
        terminal.connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals("chào\r", terminal.text())
    }

    @Test
    fun localImeKeepsSpacesEditableUntilEnter() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        terminal.connection.commitText("xin ", 1)
        terminal.connection.commitText("chào", 1)
        assertEquals("", terminal.text())
        assertEquals("xin chào", terminal.input.localInput)
        terminal.connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals("xin chào\r", terminal.text())
    }

    @Test
    fun localImeSynchronizesExternalCursorChanges() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        terminal.connection.commitText("abcd", 1)
        terminal.input.sendSpecial("\u001b[D")
        terminal.connection.syncLocalDraft(terminal.input.localInput, terminal.input.localCursorPosition)
        terminal.connection.commitText("X", 1)
        assertEquals("abcXd", terminal.input.localInput)
        terminal.connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals("abcXd\r", terminal.text())
    }

    @Test
    fun shiftModifierChangesNextCharacterAndModifiedArrows() {
        val terminal = Terminal(TerminalInputMode.REMOTE, context)
        terminal.input.shiftActive = true
        terminal.input.sendChar('a')
        assertEquals("A", terminal.text())
        terminal.input.shiftActive = true
        terminal.input.sendSpecial("\u001b[A")
        assertEquals("A\u001b[1;2A", terminal.text())
        assertFalse(terminal.input.shiftActive)
    }

    @Test
    fun extraKeysAlwaysReachRemoteWithoutEditingTheLocalDraft() {
        val keys = listOf(
            "\u001b", "\t", "\u001b[D", "\u001b[A", "\u001b[B", "\u001b[C",
            "\u001b[5~", "\u001b[6~", "\u001b[H", "\u001b[F"
        )
        for (mode in TerminalInputMode.entries) {
            val terminal = Terminal(mode, context)
            if (mode.usesLocalInput) terminal.input.sendImeText("pending command")
            val cursor = terminal.input.localCursorPosition

            keys.forEach { terminal.input.sendSpecial(it, forceRemote = true) }
            terminal.input.sendChar('/', forceRemote = true)

            assertEquals(keys.joinToString("") + "/", terminal.text())
            assertEquals(if (mode.usesLocalInput) "pending command" else "", terminal.input.localInput)
            assertEquals(cursor, terminal.input.localCursorPosition)
        }
    }

    @Test
    fun extraCharacterPreservesModifiersAndNormalLocalEditing() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        terminal.input.sendImeText("draft")
        terminal.input.ctrlActive = true
        terminal.input.sendChar('/', forceRemote = true)
        assertEquals("\u001f", terminal.text()) // Ctrl+/ (unit separator)
        assertFalse(terminal.input.ctrlActive)

        terminal.input.altActive = true
        terminal.input.sendChar('/', forceRemote = true)
        assertEquals("\u001f\u001b/", terminal.text())
        assertFalse(terminal.input.altActive)

        terminal.input.shiftActive = true
        terminal.input.sendChar('/', forceRemote = true)
        assertEquals("\u001f\u001b/?", terminal.text())
        assertFalse(terminal.input.shiftActive)
        assertEquals("draft", terminal.input.localInput)

        terminal.input.sendChar('!')
        assertEquals("draft!", terminal.input.localInput)
        terminal.input.sendSpecial("\r")
        assertEquals("\u001f\u001b/?draft!\r", terminal.text())
    }

    @Test
    fun modeChangeKeepsUnsentLocalDraftWithoutSendingOrShowingItInRemoteMode() {
        val terminal = Terminal(TerminalInputMode.LOCAL, context)
        terminal.input.sendImeText("echo chào")
        terminal.input.sendSpecial("\u001b[D")
        val originalCursor = terminal.input.localCursorPosition

        terminal.input.changeInputMode(TerminalInputMode.REMOTE)
        assertEquals(TerminalInputMode.REMOTE, terminal.input.inputMode)
        assertEquals("echo chào", terminal.input.localInput)
        assertEquals(originalCursor, terminal.input.localCursorPosition)
        assertNull(terminal.input.localDraftForDisplay())
        assertEquals("", terminal.text()) // Switching must not execute a draft.

        terminal.input.sendImeText("ls\n")
        assertEquals("ls\r", terminal.text())
        terminal.input.changeInputMode(TerminalInputMode.LOCAL)
        assertEquals("echo chào", terminal.input.localInput)
        assertEquals(originalCursor, terminal.input.localCursorPosition)
        assertTrue(terminal.input.localDraftForDisplay()!!.contains("echo chà"))
        terminal.input.sendSpecial("\r")
        assertEquals("ls\recho chào\r", terminal.text())
    }

    @Test
    fun switchingFromComposingLocalImeRestoresDraftWhenSwitchingBack() {
        val bridge = RemoteTerminalBridge(context, TerminalInputMode.LOCAL)
        val view = RemoteTerminalView(context, bridge)
        view.terminalView.setTerminalViewClient(bridge)
        bridge.view = view.terminalView
        bridge.inputView = view
        bridge.updateImeEnabled(true)
        val connection = view.onCreateInputConnection(EditorInfo())!!
        connection.setComposingText("chào thế giới", 1)
        assertEquals("chào thế giới", bridge.localInputForIme)

        bridge.setInputMode(TerminalInputMode.REMOTE)
        assertFalse(bridge.imeEnabled)
        assertFalse(view.onCheckIsTextEditor())
        assertNull(bridge.localDraftForDisplay())
        assertEquals("chào thế giới", bridge.localInputForIme)
        bridge.setInputMode(TerminalInputMode.LOCAL)
        bridge.updateImeEnabled(true)
        assertTrue(bridge.imeEnabled)
        assertNotNull(bridge.localDraftForDisplay())
        val restored = view.onCreateInputConnection(EditorInfo())!!
        restored.commitText("!", 1)
        assertEquals("chào thế giới!", bridge.localInputForIme)
        bridge.close()
    }

    @Test
    fun preferencesKeepLocalImeChoiceButNeverActivateImeInRemoteMode() {
        val modeKey = stringPreferencesKey(remoteTerminalInputModePreferenceKey("machine"))
        val imeKey = booleanPreferencesKey(remoteTerminalImePreferenceKey("machine"))
        val preferences = mutablePreferencesOf(modeKey to "IME", imeKey to false)
        assertEquals(TerminalInputMode.LOCAL, preferences.remoteTerminalInputPreferences("machine").mode)
        assertTrue(preferences.remoteTerminalInputPreferences("machine").imeEnabled)
        preferences[modeKey] = "LOCAL"
        preferences[imeKey] = false
        assertFalse(preferences.remoteTerminalInputPreferences("machine").imeEnabled)
        preferences[modeKey] = "REMOTE"
        preferences[imeKey] = true
        assertEquals(TerminalInputMode.REMOTE, preferences.remoteTerminalInputPreferences("machine").mode)
        assertFalse(preferences.remoteTerminalInputPreferences("machine").imeEnabled)
        // The Local IME preference survives the Remote mode change.
        assertEquals(true, preferences[imeKey])
        preferences[modeKey] = "LOCAL"
        assertTrue(preferences.remoteTerminalInputPreferences("machine").imeEnabled)
    }
}
