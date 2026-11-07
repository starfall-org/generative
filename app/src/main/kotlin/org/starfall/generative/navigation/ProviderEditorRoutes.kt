package org.starfall.generative.navigation

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.starfall.generative.R
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.features.providers.editor.ui.ProviderEditor
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.provider.ModelProvider

internal data class ProviderEditorPage(val provider: ModelProvider, val isNew: Boolean)

/** The draft is read inside the destination so save/dispose updates retain their original lifecycle. */
internal fun RetainedNavGraphBuilder.providerEditorRoutes(
    navController: NavHostController,
    configurationViewModel: ConfigurationViewModel,
    toolSettings: ToolSettings,
    pendingNewProvider: () -> ProviderEditorPage?,
    onPendingNewProviderChange: (ProviderEditorPage?) -> Unit,
    onShowChat: () -> Unit
) {
    composable("providers/new") {
        val context = LocalContext.current
        val localizedResources = LocalResources.current
        val page = pendingNewProvider()
        if (page == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else {
            // Clear the pending page only once this screen has left composition. Clearing it
            // inside onDismiss made the exiting screen recompose into the `page == null`
            // branch above, whose popBackStack() ran a second time and also popped Providers.
            DisposableEffect(Unit) { onDispose { onPendingNewProviderChange(null) } }
            key(page.provider.id) {
                ProviderEditorDestination(
                    configurationViewModel = configurationViewModel,
                    toolSettings = toolSettings,
                    initialProvider = page.provider,
                    isNew = page.isNew,
                    onSave = { saved ->
                        configurationViewModel.saveProvider(saved)
                        onPendingNewProviderChange(ProviderEditorPage(saved, false))
                        Toast.makeText(
                            context,
                            localizedResources.getString(R.string.provider_saved),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDismiss = { navController.popBackStack() },
                    onAutoCreateProvider = { newProvider ->
                        configurationViewModel.saveProvider(newProvider)
                        onPendingNewProviderChange(ProviderEditorPage(newProvider, false))
                    },
                )
            }
        }
    }

    composable(
        route = "providers/edit/{providerId}?modelId={modelId}&returnToChat={returnToChat}",
        arguments = listOf(navArgument("modelId") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        }, navArgument("returnToChat") {
            type = NavType.BoolType
            defaultValue = false
        })
    ) { entry ->
        val context = LocalContext.current
        val localizedResources = LocalResources.current
        val providerId = entry.arguments?.getString("providerId").orEmpty()
        val modelId = entry.arguments?.getString("modelId")
        // Store the origin on this back-stack entry, including across recreation.
        val returnToChat = entry.arguments?.getBoolean("returnToChat") == true
        val dismissEditor: () -> Unit = {
            if (navController.currentBackStackEntry == entry && navController.popBackStack()) {
                if (returnToChat) onShowChat()
            }
        }
        TargetedEditorContent(
            recordId = providerId,
            title = stringResource(R.string.provider_editor),
            loadingMessage = stringResource(R.string.loading_provider_editor),
            onBack = dismissEditor,
            load = { configurationViewModel.loadProviderForEditor(providerId) }
        ) { provider ->
            key(provider.id) {
                ProviderEditorDestination(
                    configurationViewModel = configurationViewModel,
                    toolSettings = toolSettings,
                    initialProvider = provider,
                    isNew = false,
                    initialEditingModelId = modelId,
                    onSave = { saved ->
                        configurationViewModel.saveProvider(saved)
                        Toast.makeText(
                            context,
                            localizedResources.getString(R.string.provider_saved),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDismiss = dismissEditor,
                    onDelete = configurationViewModel::deleteProvider,
                )
            }
        }
    }
}

/** Shared host callbacks for new and existing provider routes. Route lifecycles stay in AppNavigation. */
@Composable
private fun ProviderEditorDestination(
    configurationViewModel: ConfigurationViewModel,
    toolSettings: ToolSettings,
    initialProvider: ModelProvider,
    isNew: Boolean,
    initialEditingModelId: String? = null,
    onSave: (ModelProvider) -> Unit,
    onDismiss: () -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onAutoCreateProvider: (ModelProvider) -> Unit = onSave
) {
    ProviderEditor(
        initialProvider = initialProvider,
        isNew = isNew,
        initialEditingModelId = initialEditingModelId,
        onSave = onSave,
        onDismiss = onDismiss,
        onDelete = onDelete,
        onAutoCreateProvider = onAutoCreateProvider,
        onSaveModels = configurationViewModel::saveProviderModels,
        onSaveIcon = configurationViewModel::saveProviderIcon,
        onSaveModelIcon = configurationViewModel::saveModelIcon,
        onUpdateAuxiliary = configurationViewModel::saveProviderAuxiliaryChange,
        onReorderModels = configurationViewModel::reorderProviderModels,
        onReorderModelsTracked = configurationViewModel::reorderProviderModelsTracked,
        onAuthorizeProvider = configurationViewModel::authorizeProvider,
        onCancelProviderAuthorization = configurationViewModel::cancelProviderAuthorization,
        onClearOAuthCredentials = configurationViewModel::clearOAuthCredentials,
        onTestConnection = configurationViewModel::testConnection,
        onFetchModels = configurationViewModel::fetchProviderModels,
        customImageMediaProfiles = toolSettings.system.values
            .flatMap { it.imageRequestProfiles.values }
            .associateBy { it.id },
        customVideoMediaProfiles = toolSettings.system.values
            .flatMap { it.videoRequestProfiles.values }
            .associateBy { it.id }
    )
}
