package org.starfall.generative.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import org.starfall.generative.features.mcp.editor.ui.ServerEditor
import org.starfall.generative.features.mcp.list.ui.ServerListScreen
import org.starfall.generative.features.providers.editor.ui.ProviderEditor
import org.starfall.generative.features.providers.list.ui.ProvidersScreen
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.features.serverconnection.ui.OpenCodeAuthorizationUi
import org.starfall.generative.features.serverconnection.ui.OpenCodeProviderCredentialNotices
import org.starfall.generative.features.serverconnection.ui.ServerCatalogContent
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.state.preferences.ModelConfigurationMemoryScope
import org.starfall.generative.shared.ui.screens.ScreenDestination
import java.security.MessageDigest

@Composable
internal fun ServerCatalogDestination(
    service: ServerConnections,
    connectionId: String,
    providers: Boolean,
    onBack: () -> Unit,
    directory: String? = null,
) = ServerCatalogContent(service, connectionId, providers, directory) { catalog ->
    var grid by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val editorContext = remember(context, connectionId) { ServerCatalogEditorContext(context, connectionId) }

    CompositionLocalProvider(LocalContext provides editorContext) {
        ScreenDestination(catalog.providerPage, onClosed = catalog::closeProvider) { page ->
            if (page != null) {
                val provider = page.provider
                val isNew = page.isNew
                val authorization = page.authorization
                val authUi = remember(authorization) { authorization?.let(::OpenCodeAuthorizationUi) }
                var dismissAfterDelete by remember(page) { mutableStateOf(false) }
                ProviderEditor(
                    initialProvider = provider, isNew = isNew,
                    onDismiss = {
                        if (dismissAfterDelete) dismissAfterDelete = false
                        else if (!catalog.busy) catalog.closeProvider()
                    },
                    onSave = { draft ->
                        catalog.saveProvider(page, draft) { authUi?.formAnswers(AuthMethod.PLATFORM_DEFAULT) }
                    },
                    onSaveIcon = catalog::rememberProviderDraft,
                    onSaveModels = { id, models ->
                        catalog.updateProviderDraft(page, id) { current ->
                            current.copy(config = current.config.copy(modelIds = models.keys.toList(), modelConfigs = models))
                        }
                    },
                    onReorderModels = { id, order ->
                        catalog.updateProviderDraft(page, id) { current ->
                            current.copy(config = current.config.copy(modelIds = order))
                        }
                    },
                    onAuthorizeProvider = authorization?.takeIf { it.canAuthorize }?.let { connector ->
                        { draft ->
                            val fields = requireNotNull(authUi)
                            catalog.authorizeProvider(
                                connector, draft, { fields.method(AuthMethod.OAUTH) }, { fields.formAnswers(AuthMethod.OAUTH) },
                            ) { challenge -> fields.presentChallenge(challenge, uriHandler::openUri) }
                        }
                    },
                    onCancelProviderAuthorization = authorization?.takeIf { it.canAuthorize }?.let {
                        { _ -> catalog.cancelProviderAuthorization() }
                    },
                    onDelete = if (isNew) null else { id ->
                        // The shared editor dismisses after confirmation. Here the result decides.
                        dismissAfterDelete = true
                        catalog.deleteProvider(id)
                    },
                    authorizationReady = { kind -> authUi?.isReady(kind) ?: true },
                    connectionDetails = { kind, hasApiKey ->
                        if (catalog.openCode) {
                            OpenCodeProviderCredentialNotices(
                                insecure = catalog.insecure,
                                writesApiKey = authorization == null && hasApiKey,
                            )
                        }
                        authUi?.Form(kind)
                    },
                    pendingAuthorizationContent = { authUi?.PendingCode(uriHandler::openUri) },
                )
            }
        }
    }

    val mcpSettings = catalog.mcpSettings
    ScreenDestination(catalog.mcpPage, onClosed = catalog::closeMcp) { page ->
        if (page != null) {
            val server = page.server
            val isNew = page.isNew
            ServerEditor(
                initialServer = server, isNew = isNew,
                onRefreshTools = { Result.success(it.cachedTools.orEmpty()) },
                onAuthorizeOAuth = { Result.failure(IllegalStateException()) },
                onClearOAuth = { it.copy(auth = it.auth.copy(oauthAuthorized = false)) },
                cachedTools = server.cachedTools, cachedError = null, cachedLoading = false, toolSettings = mcpSettings,
                onSetToolEnabled = { _, _, _ -> }, onSetToolParameter = { _, _, _, _ -> },
                onDismiss = { if (!catalog.busy) catalog.closeMcp() },
                onSave = { edited -> catalog.saveMcp(page, edited) },
            )
        }
    }

    if (providers) {
        ProvidersScreen(
            providers = catalog.providerRows, isGridView = grid, onToggleGridView = { grid = it },
            onOpenProviderEditor = catalog::openProvider,
            onDeleteProvider = catalog::deleteProvider,
            onReorderProviders = catalog::reorderProviders,
            onReorderRootItems = { catalog.reorderProviders(it.map { item -> item.id }) },
            onBack = onBack
        )
    } else {
        ServerListScreen(
            canManageServers = catalog.openCode,
            mcpServers = catalog.mcpRows,
            isGridView = grid, onToggleGridView = { grid = it },
            toolsCache = emptyMap(), toolErrors = emptyMap(), toolsLoading = emptySet(), toolSettings = mcpSettings,
            onSetToolEnabled = { _, _, _ -> },
            onSetMcpEnabled = catalog::setMcpConnected,
            onDeleteMcpServer = catalog::deleteMcp,
            onReorderMcpServers = catalog::reorderMcp,
            onOpenEditor = catalog::openMcp,
            onBack = onBack
        )
    }
}

private class ServerCatalogEditorContext(context: Context, connectionId: String) :
    ContextWrapper(context), ModelConfigurationMemoryScope {
    override val modelConfigurationNamespace = "server-model-configuration-" +
        MessageDigest.getInstance("SHA-256").digest(connectionId.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
