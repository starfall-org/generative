package org.starfall.generative.shared.ui.text
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import kotlin.math.roundToInt
import org.starfall.generative.R

private val LocalSelectionClipboardCapture = staticCompositionLocalOf<SelectionClipboard?> { null }
private val LocalPlatformSelectionToolbar = staticCompositionLocalOf<PlatformSelectionToolbar?> { null }
internal val LocalReadOnlySelectionStates = staticCompositionLocalOf<MutableSet<SelectionState>?> { null }

/** Dismiss read-only selection on outside touches while Android hosts the floating toolbar. */
@Composable
fun PlatformTextSelection(content: @Composable () -> Unit) {
    val view = LocalView.current
    // Keep the platform clipboard in the ambient for the whole screen. Editable text fields rely on
    // AndroidClipboard's native handle for selection, cut/paste and IME operations.
    val clipboard = LocalClipboard.current
    val selectionClipboard = remember(clipboard) { SelectionClipboard(clipboard) }
    val toolbar = remember(view, selectionClipboard) { PlatformSelectionToolbar(view, selectionClipboard) }
    val selectionStates = remember(toolbar) { mutableSetOf<SelectionState>() }
    val focusManager = LocalFocusManager.current
    DisposableEffect(toolbar) { onDispose { toolbar.hide() } }
    CompositionLocalProvider(
        LocalSelectionClipboardCapture provides selectionClipboard,
        LocalPlatformSelectionToolbar provides toolbar,
        LocalReadOnlySelectionStates provides selectionStates,
    ) {
        Box(Modifier.fillMaxSize().pointerInput(toolbar, focusManager, selectionStates) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // The newer Compose toolbar does not call LocalTextToolbar.showMenu.
                if (toolbar.hasReadOnlySelection || selectionStates.any { it.selectedTexts.isNotEmpty() }) {
                    // SelectionContainer releases its highlight and handles focus loss.
                    // Observe before children so this same touch still activates its target.
                    // Handles and Android's floating toolbar use separate popup windows.
                    focusManager.clearFocus(force = true)
                    toolbar.hide()
                }
            }
        }) {
            content()
        }
    }
}

/**
 * Applies the clipboard-capturing toolbar only to read-only message content. Text fields elsewhere
 * keep Compose's native AndroidClipboard, which must not be wrapped by [SelectionClipboard].
 */
@Composable
internal fun PlatformTextSelectionRegion(content: @Composable () -> Unit) {
    val clipboard = LocalSelectionClipboardCapture.current
    val toolbar = LocalPlatformSelectionToolbar.current
    if (clipboard == null || toolbar == null) {
        content()
    } else {
        CompositionLocalProvider(
            LocalClipboard provides clipboard,
            LocalTextToolbar provides toolbar,
            content = content
        )
    }
}

internal class SelectionClipboard(private val delegate: Clipboard) : Clipboard {
    private var capturing = false
    private var selection: AnnotatedString? = null

    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (capturing) {
            selection = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                ?.toString()?.let(::AnnotatedString)
        } else {
            delegate.setClipEntry(clipEntry)
        }
    }

    fun selectedText(copy: () -> Unit): String? {
        selection = null
        capturing = true
        try { copy() } finally { capturing = false }
        return selection?.text
    }
}

internal class PlatformSelectionToolbar(
    private val view: View,
    private val clipboard: SelectionClipboard
) : TextToolbar {
    private var mode: ActionMode? = null
    private var rect = Rect.Zero
    private var copy: (() -> Unit)? = null
    private var paste: (() -> Unit)? = null
    private var cut: (() -> Unit)? = null
    private var selectAll: (() -> Unit)? = null
    override val status: TextToolbarStatus
        get() = if (mode == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    // Android can dismiss the action mode while Compose still owns a selection.
    var hasReadOnlySelection: Boolean = false
        private set

    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        this.rect = rect
        copy = onCopyRequested; paste = onPasteRequested; cut = onCutRequested; selectAll = onSelectAllRequested
        hasReadOnlySelection = copy != null && paste == null && cut == null
        if (mode == null) mode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        else { mode?.invalidate(); mode?.invalidateContentRect() }
    }

    override fun hide() {
        hasReadOnlySelection = false
        mode?.finish()
        mode = null
    }

    private val callback = object : ActionMode.Callback2() {
        private val processActions = mutableMapOf<Int, ComponentName>()
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = populate(menu)
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = populate(menu)
        private fun populate(menu: Menu): Boolean {
            menu.clear()
            processActions.clear()
            fun action(id: Int, title: String, enabled: Boolean) {
                if (enabled) menu.add(Menu.NONE, id, Menu.NONE, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            action(android.R.id.cut, view.context.getString(android.R.string.cut), cut != null)
            action(android.R.id.copy, view.context.getString(android.R.string.copy), copy != null)
            action(android.R.id.paste, view.context.getString(android.R.string.paste), paste != null)
            action(android.R.id.selectAll, view.context.getString(android.R.string.selectAll), selectAll != null)
            action(SHARE, view.context.getString(R.string.common_share), copy != null)
            if (copy != null) {
                val manager = view.context.packageManager
                manager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
                    .filter { resolve ->
                        val activity = resolve.activityInfo
                        activity.packageName == view.context.packageName ||
                            (activity.exported && (activity.permission == null ||
                                view.context.checkSelfPermission(activity.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED))
                    }.forEachIndexed { index, resolve ->
                        val id = PROCESS_BASE + index
                        processActions[id] = ComponentName(resolve.activityInfo.packageName, resolve.activityInfo.name)
                        menu.add(Menu.NONE, id, Menu.NONE, resolve.loadLabel(manager)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                    }
            }
            return menu.size() > 0
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = when (item.itemId) {
                android.R.id.copy -> copy
                android.R.id.cut -> cut
                android.R.id.paste -> paste
                android.R.id.selectAll -> selectAll
                else -> null
            }
            if (action != null) {
                action()
                if (item.itemId != android.R.id.selectAll) mode.finish()
                return true
            }
            val component = processActions[item.itemId]
            if (item.itemId == SHARE || component != null) {
                val text = copy?.let(clipboard::selectedText) ?: return false
                val intent = if (component != null) {
                    Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(component)
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                } else Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), view.context.getString(R.string.common_share_text))
                runCatching { view.context.startActivity(intent) }
                mode.finish()
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) { if (this@PlatformSelectionToolbar.mode === mode) this@PlatformSelectionToolbar.mode = null }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: AndroidRect) {
            outRect.set(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        }
    }

    companion object { private const val SHARE = 1001; private const val PROCESS_BASE = 2000 }
}
