package org.starfall.generative.shared.terminal

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.starfall.generative.shared.model.remote.TerminalInputMode

/** Handles terminal key encoding and the optional local command-line editor. */
internal class RemoteTerminalInput(
    initialInputMode: TerminalInputMode,
    private val sendRemote: (ByteArray) -> Unit,
    private val snapToBottom: () -> Unit,
    private val isCursorKeysApplicationMode: () -> Boolean
) {
    var ctrlActive by mutableStateOf(false)
    var altActive by mutableStateOf(false)
    var shiftActive by mutableStateOf(false)
    var inputMode by mutableStateOf(initialInputMode)
        private set
    var localInput by mutableStateOf("")
        private set
    var submittedLocalInput by mutableStateOf<String?>(null)
        private set

    private var localCursor = 0
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = 0
    private var historyDraft = ""

    val localCursorPosition: Int get() = localCursor

    /** The IME keeps the full editable line, including committed text, so Vietnamese
     * keyboards can replace previous syllables instead of appending their corrections. */
    fun updateLocalDraftFromIme(text: String, cursor: Int) {
        if (!inputMode.usesLocalInput) return
        localInput = text
        localCursor = cursor.coerceIn(0, text.length)
        historyIndex = commandHistory.size
    }

    fun changeInputMode(mode: TerminalInputMode) {
        // A local command is a draft, not a reason to lock input mode. Keep it
        // (and its cursor) untouched until the user returns to Local Input.
        inputMode = mode
        if (!mode.usesLocalInput) submittedLocalInput = null
        clearModifiers()
    }

    fun localDraftForDisplay(): String? {
        if (!inputMode.usesLocalInput) return null
        val submitted = submittedLocalInput
        if (submitted == null && localInput.isEmpty()) return null
        return buildString {
            submitted?.let(::append)
            if (localInput.isNotEmpty()) {
                if (submitted != null) append("  ·  ")
                append(localInput, 0, localCursor)
                append('▏')
                append(localInput.substring(localCursor))
            }
        }
    }

    fun clearSubmittedInput() {
        submittedLocalInput = null
    }

    fun sendText(value: String) {
        snapToBottom()
        if (inputMode.usesLocalInput) insertLocalText(value)
        else sendRemote(value.toByteArray(Charsets.UTF_8))
    }

    /** Committed IME text follows the same Unicode, modifier and Enter rules as typed keys. */
    fun sendImeText(value: String) {
        value.codePoints().forEach { codePoint ->
            sendCodePoint(codePoint, ctrlActive, altActive)
            clearModifiers()
        }
    }

    fun sendChar(char: Char, forceRemote: Boolean = false) {
        sendCodePoint(char.code, ctrlActive, altActive, forceRemote)
        clearModifiers()
    }

    fun sendSpecial(
        sequence: String,
        ctrl: Boolean = ctrlActive,
        alt: Boolean = altActive,
        forceRemote: Boolean = false,
        shift: Boolean = shiftActive
    ) {
        snapToBottom()
        if (!forceRemote && inputMode.usesLocalInput && sequence in setOf("\r", "\n")) {
            submitLocalInput()
            clearModifiers()
            return
        }
        if (!forceRemote && inputMode.usesLocalInput && !ctrl && !alt) {
            when (sequence) {
                "\r", "\n" -> submitLocalInput()
                "\u0009" -> insertLocalText("\t")
                "\u007f", "\b" -> deleteLocalPrevious()
                "\u001b[3~" -> deleteLocalNext()
                "\u001b[D" -> moveLocalCursor(-1)
                "\u001b[C" -> moveLocalCursor(1)
                "\u001b[H" -> localCursor = 0
                "\u001b[F" -> localCursor = localInput.length
                "\u001b[A" -> moveLocalHistory(-1)
                "\u001b[B" -> moveLocalHistory(1)
                "\u001b" -> clearLocalInput()
                else -> sendRemote(sequence.toByteArray(Charsets.UTF_8))
            }
            clearModifiers()
            return
        }
        val match = Regex("^\u001b\\[(\\d*)([A-Za-z~])$").matchEntire(sequence)
        val output = when {
            match != null && (ctrl || alt || shift) -> {
                val number = match.groupValues[1].ifEmpty { "1" }
                val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
                "\u001b[$number;$modifier${match.groupValues[2]}"
            }
            match != null && match.groupValues[1].isEmpty() && match.groupValues[2] in "ABCD" &&
                isCursorKeysApplicationMode() -> "\u001bO${match.groupValues[2]}"
            alt && sequence.length == 1 -> "\u001b$sequence"
            else -> sequence
        }
        sendRemote(output.toByteArray(Charsets.UTF_8))
        clearModifiers()
    }

    fun sendCodePoint(codePoint: Int, ctrl: Boolean, alt: Boolean, forceRemote: Boolean = false) {
        snapToBottom()
        val shiftedPoint = when {
            shiftActive && codePoint in 'a'.code..'z'.code -> codePoint - 32
            shiftActive && codePoint == '/'.code -> '?'.code
            else -> codePoint
        }
        if (inputMode.usesLocalInput && !forceRemote) {
            if (ctrl) {
                handleLocalControl(codePoint, alt)
                clearModifiers()
                return
            }
            when (codePoint) {
                '\n'.code, '\r'.code -> submitLocalInput()
                '\b'.code, 0x7f -> deleteLocalPrevious()
                0x1b -> clearLocalInput()
                else -> insertLocalText(String(Character.toChars(shiftedPoint)))
            }
            clearModifiers()
            return
        }
        sendRemote(codePointBytes(shiftedPoint, ctrl, alt))
    }

    fun sendKey(keyCode: Int, event: KeyEvent): Boolean {
        val sequence = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            else -> null
        }
        val ctrl = event.isCtrlPressed || ctrlActive
        val alt = event.isAltPressed || altActive
        if (sequence != null) {
            sendSpecial(sequence, ctrl, alt)
            return true
        }
        val unicode = event.unicodeChar
        if (unicode != 0) {
            sendCodePoint(unicode, ctrl, alt)
            clearModifiers()
        }
        return true
    }

    fun clearModifiers() {
        ctrlActive = false
        altActive = false
        shiftActive = false
    }

    private fun insertLocalText(value: String) {
        if (value.isEmpty()) return
        localInput = localInput.substring(0, localCursor) + value + localInput.substring(localCursor)
        localCursor += value.length
        historyIndex = commandHistory.size
    }

    private fun deleteLocalPrevious() {
        if (localCursor == 0) return
        val previous = Character.offsetByCodePoints(localInput, localCursor, -1)
        localInput = localInput.removeRange(previous, localCursor)
        localCursor = previous
        historyIndex = commandHistory.size
    }

    private fun deleteLocalNext() {
        if (localCursor >= localInput.length) return
        val next = Character.offsetByCodePoints(localInput, localCursor, 1)
        localInput = localInput.removeRange(localCursor, next)
        historyIndex = commandHistory.size
    }

    private fun moveLocalCursor(direction: Int) {
        if (direction < 0 && localCursor == 0) return
        if (direction > 0 && localCursor == localInput.length) return
        localCursor = Character.offsetByCodePoints(localInput, localCursor, direction)
            .coerceIn(0, localInput.length)
    }

    private fun moveLocalHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        if (direction < 0) {
            if (historyIndex == commandHistory.size) historyDraft = localInput
            historyIndex = (historyIndex - 1).coerceAtLeast(0)
        } else {
            historyIndex = (historyIndex + 1).coerceAtMost(commandHistory.size)
        }
        localInput = if (historyIndex == commandHistory.size) historyDraft else commandHistory[historyIndex]
        localCursor = localInput.length
    }

    private fun clearLocalInput() {
        localInput = ""
        localCursor = 0
        historyIndex = commandHistory.size
    }

    private fun submitLocalInput() {
        val line = localInput
        submittedLocalInput = line.takeIf { it.isNotEmpty() }
        if (line.isNotBlank() && commandHistory.lastOrNull() != line) commandHistory += line
        historyIndex = commandHistory.size
        historyDraft = ""
        clearLocalInput()
        sendRemote((line + "\r").toByteArray(Charsets.UTF_8))
    }

    private fun handleLocalControl(codePoint: Int, alt: Boolean) {
        if (alt) {
            insertLocalText(String(Character.toChars(codePoint)))
            return
        }
        val controlCharacter = if (codePoint in 1..26) 'a'.code + codePoint - 1 else codePoint
        when (controlCharacter.toChar().lowercaseChar()) {
            'a' -> localCursor = 0
            'e' -> localCursor = localInput.length
            'u' -> clearLocalInput()
            'w' -> {
                while (localCursor > 0 && localInput[localCursor - 1].isWhitespace()) deleteLocalPrevious()
                while (localCursor > 0 && !localInput[localCursor - 1].isWhitespace()) deleteLocalPrevious()
            }
            'c' -> {
                clearLocalInput()
                sendRemote(byteArrayOf(3))
            }
            'd' -> {
                if (localCursor < localInput.length) deleteLocalNext()
                else if (localInput.isEmpty()) sendRemote(byteArrayOf(4))
            }
            else -> sendRemote(codePointBytes(codePoint, ctrl = true, alt = false))
        }
    }

    private fun codePointBytes(codePoint: Int, ctrl: Boolean, alt: Boolean): ByteArray {
        var value = codePoint
        if (value == '\n'.code) value = '\r'.code
        if (ctrl) {
            value = when {
                value in 'a'.code..'z'.code -> value - 'a'.code + 1
                value in 'A'.code..'Z'.code -> value - 'A'.code + 1
                value == ' '.code || value == '2'.code -> 0
                value == '['.code || value == '3'.code -> 27
                value == '\\'.code || value == '4'.code -> 28
                value == ']'.code || value == '5'.code -> 29
                value == '^'.code || value == '6'.code -> 30
                value == '_'.code || value == '7'.code || value == '/'.code -> 31
                else -> value
            }
        }
        val prefix = if (alt) "\u001b" else ""
        return (prefix + String(Character.toChars(value))).toByteArray(Charsets.UTF_8)
    }
}
