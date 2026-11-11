package org.starfall.generative.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.starfall.generative.features.machines.shared.ui.RemoteMachineFilesScreen
import org.starfall.generative.features.storage.ui.StorageExplorerScreen
import org.starfall.generative.integrations.remote.RemoteMachineServiceOwner
import org.starfall.generative.shared.ui.preview.CodePreviewScreen
import org.starfall.generative.shared.ui.preview.codePreviewFile
import org.starfall.generative.shared.ui.preview.createCodePreviewFile

internal object BrowserRoutes {
    const val EXPLORER = "explorer?machineId={machineId}"
    const val CODE_PREVIEW = "code_preview/{document}"
    fun explorer(machineId: String? = null): String =
        if (machineId == null) "explorer" else "explorer?machineId=${Uri.encode(machineId)}"
    fun codePreview(document: String): String = "code_preview/${Uri.encode(document)}"
}

internal fun RetainedNavGraphBuilder.browserScreens(navController: NavHostController) {
    composable(
        BrowserRoutes.EXPLORER,
        arguments = listOf(navArgument("machineId") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        })
    ) { entry ->
        ExplorerDestination(entry.arguments?.getString("machineId")) { navController.popBackStack() }
    }
    composable(BrowserRoutes.CODE_PREVIEW) { entry ->
        CodePreviewDestination(entry, navController)
    }
}

internal fun openCodePreview(context: Context, navController: NavHostController, language: String, code: String) {
    val file = createCodePreviewFile(context, language, code)
    try {
        navController.navigate(BrowserRoutes.codePreview(file.name))
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}

@Composable
internal fun ExplorerDestination(machineId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val openExternal: (Uri, String) -> Unit = { uri, mime ->
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (machineId == null) {
            StorageExplorerScreen(onClose = onBack, onOpenExternal = openExternal)
        } else {
            RemoteMachineFilesScreen(
                service = (context.applicationContext as RemoteMachineServiceOwner).remoteMachineService,
                machineId = machineId,
                onClose = onBack,
                onOpenExternal = openExternal
            )
        }
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.hostActivity()
    else -> null
}

@Composable
internal fun CodePreviewDestination(entry: NavBackStackEntry, navController: NavHostController) {
    val context = LocalContext.current
    val activity = remember(context) { context.hostActivity() }
    val name = entry.arguments?.getString("document").orEmpty()
    val file = remember(name) { codePreviewFile(context, name) }
    val document = remember(file) { file?.let { runCatching { it.readText() }.getOrNull() } }
    DisposableEffect(entry, file) {
        onDispose {
            // Keep the file across Activity recreation while its destination remains on the stack.
            val remaining = runCatching { navController.getBackStackEntry(BrowserRoutes.codePreview(name)) }.getOrNull()
            if (remaining?.id != entry.id || activity?.isFinishing == true) file?.delete()
        }
    }
    CodePreviewScreen(document = document, onBack = { navController.popBackStack() })
}
