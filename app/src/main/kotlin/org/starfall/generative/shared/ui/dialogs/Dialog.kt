package org.starfall.generative.shared.ui.dialogs

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import java.util.IdentityHashMap
import org.starfall.generative.shared.theme.AmoledModalScrim
import org.starfall.generative.shared.ui.background.LocalProfileModalImages
import org.starfall.generative.shared.ui.background.ProfileBackground
import org.starfall.generative.shared.theme.LocalAmoledMode
import org.starfall.generative.shared.theme.LocalDarkMode
import org.starfall.generative.shared.ui.layout.windowHeightIn
import org.starfall.generative.shared.ui.layout.modalContainerColor
import org.starfall.generative.shared.ui.layout.appearanceContainerBorder

/** One tint on the activity background, even when a dialog is opened from a sheet. */
private class DialogBackdrop(val drawable: ColorDrawable, val listener: View.OnLayoutChangeListener, var users: Int = 0)
private val dialogBackdrops = IdentityHashMap<View, DialogBackdrop>()

internal fun amoledDialogBackdropHost(view: View): View {
    var context = view.context
    while (true) {
        if (context is Activity) return context.window.decorView
        if (context !is ContextWrapper || context.baseContext === context) return view.rootView
        context = context.baseContext
    }
}

internal fun acquireAmoledDialogBackdrop(host: View): () -> Unit {
    val backdrop = dialogBackdrops[host] ?: run {
        val drawable = ColorDrawable(AmoledModalScrim.copy(alpha = 0.32f).toArgb())
        drawable.setBounds(0, 0, host.width, host.height)
        val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            drawable.setBounds(0, 0, view.width, view.height)
        }
        host.addOnLayoutChangeListener(listener)
        host.overlay.add(drawable)
        DialogBackdrop(drawable, listener).also { dialogBackdrops[host] = it }
    }
    backdrop.users++
    var released = false
    return {
        if (!released) {
            released = true
            if (--backdrop.users == 0) {
                host.overlay.remove(backdrop.drawable)
                host.removeOnLayoutChangeListener(backdrop.listener)
                dialogBackdrops.remove(host)
            }
        }
    }
}

@Composable
private fun AmoledDialogBackdrop() {
    val dark = LocalDarkMode.current
    val amoled = LocalAmoledMode.current
    // The composition can live inside a sheet/dialog window. Its root overlay would
    // tint that foreground surface too; always put the tint on the activity behind it.
    val host = amoledDialogBackdropHost(LocalView.current)
    DisposableEffect(host, dark, amoled) {
        // AMOLED uses the platform's black dim layer: empty areas stay #000000
        // while content already drawn behind the dialog is dimmed naturally.
        val release = if (dark && !amoled) acquireAmoledDialogBackdrop(host) else ({})
        onDispose { release() }
    }
}

@Composable
private fun ConfigureAmoledDialogWindow() {
    val dark = LocalDarkMode.current
    val amoled = LocalAmoledMode.current
    var view: View? = LocalView.current
    var window: android.view.Window? = null
    while (view != null && window == null) {
        if (view is DialogWindowProvider) window = view.window else view = view.parent as? View
    }
    DisposableEffect(window, dark, amoled) {
        val target = window
        val oldDimAmount = target?.attributes?.dimAmount
        // The activity backdrop supplies the theme-aware dim color. Disable the
        // platform black dim layer so dark mode does not turn the background pure black.
        if (dark && !amoled) target?.setDimAmount(0f)
        onDispose { if (dark && !amoled && oldDimAmount != null) target.setDimAmount(oldDimAmount) }
    }
}

/** Dialog with a title, scrollable content and an optional action footer.
 * Order footer actions left-to-right: other actions, Cancel, Save; or Cancel, Delete when no Save exists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    content: @Composable () -> Unit,
    buttons: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    edgeToEdge: Boolean = false,
    scrollableContent: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation
) {
    AmoledDialogBackdrop()
    androidx.compose.ui.window.Dialog(onDismissRequest, properties = properties) {
        ConfigureAmoledDialogWindow()
        val insets = if (edgeToEdge) Modifier else Modifier.safeDrawingPadding().imePadding()
        Box(insets) {
            val surfaceModifier = if (edgeToEdge) modifier.fillMaxSize()
                else modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.9f)
            Surface(
                modifier = surfaceModifier.testTag("app-dialog"),
                shape = shape,
                color = modalContainerColor(containerColor),
                tonalElevation = 0.dp,
                border = if (edgeToEdge) null else appearanceContainerBorder()
            ) {
                Box {
                ProfileBackground(
                    LocalProfileModalImages.current.dialog,
                    Modifier.matchParentSize(),
                    LocalProfileModalImages.current.dialogTransparency
                )
                val columnModifier = if (edgeToEdge) Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                    else Modifier.fillMaxWidth()
                Column(columnModifier) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp)
                            .testTag("app-dialog-title").semantics { heading() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (icon != null) CompositionLocalProvider(LocalContentColor provides iconContentColor) { icon() }
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                        }
                    }
                    val contentScroll = if (scrollableContent) Modifier.verticalScroll(rememberScrollState()) else Modifier
                    Box(
                        Modifier.weight(1f, fill = edgeToEdge).fillMaxWidth().padding(horizontal = 24.dp)
                            .then(contentScroll).testTag("app-dialog-content")
                    ) {
                        CompositionLocalProvider(LocalContentColor provides textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { content() }
                        }
                    }
                    if (buttons != null) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 24.dp)
                                .testTag("app-dialog-buttons"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) { buttons() }
                    } else {
                        Spacer(Modifier.height(24.dp))
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
    scrollableContent: Boolean = true,
    /** Confirmation (including Delete or Save) follows Cancel/Close by default. */
    confirmAtRight: Boolean = true
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        title = title,
        content = text,
        buttons = {
            if (confirmAtRight) {
                dismissButton?.invoke()
                confirmButton()
            } else {
                confirmButton()
                dismissButton?.invoke()
            }
        },
        modifier = modifier, icon = icon, scrollableContent = scrollableContent,
        shape = shape, containerColor = containerColor, iconContentColor = iconContentColor,
        titleContentColor = titleContentColor, textContentColor = textContentColor,
        tonalElevation = tonalElevation, properties = properties
    )
}
