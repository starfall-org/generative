package org.starfall.generative.features.providers.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.features.providers.shared.ui.ModelConnectionDialog
import org.starfall.generative.features.providers.shared.logic.ModelConnectionTests
import org.starfall.generative.features.providers.shared.ui.OAuthAccountCard
import org.starfall.generative.features.providers.shared.ui.ProviderApiKeysDialog
import org.starfall.generative.features.providers.shared.model.ProviderApiKeysState
import org.starfall.generative.features.providers.shared.ui.ProviderAuthField
import org.starfall.generative.features.providers.shared.ui.ProviderBaseUrlField
import org.starfall.generative.features.providers.shared.ui.ProviderHeadersDialog
import org.starfall.generative.features.providers.shared.ui.ProviderModelsPane
import org.starfall.generative.features.providers.shared.ui.ProviderOAuthAccountsDialog
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.bearerHeaderValue
import org.starfall.generative.shared.model.auth.defaultAuthorization
import org.starfall.generative.shared.model.auth.editMethod
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.provider.ProviderOAuthAccount
import org.starfall.generative.shared.model.provider.oauthAccountsWithCurrent
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.model.provider.recordOAuthAccount
import org.starfall.generative.shared.model.provider.removeOAuthAccount
import org.starfall.generative.shared.model.provider.withModelApiType
import org.starfall.generative.shared.networking.providerBase
import org.starfall.generative.shared.storage.preferences.ModelConfigurationMemory
import org.starfall.generative.shared.ui.icons.FluentIcon
import org.starfall.generative.shared.ui.icons.IconPickerRow
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.menus.RoundedDropdownMenuItem
import org.starfall.generative.shared.ui.text.SelectableOutlinedTextField
import org.starfall.generative.shared.ui.text.HeaderNameField
import org.starfall.generative.shared.ui.layout.SlideScreenContent
import org.starfall.generative.shared.ui.lists.moved
import org.starfall.generative.shared.ui.icons.providerInitials
import org.starfall.generative.shared.ui.auth.rememberOAuthStart

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderEditorScreen(
    initialProvider: ModelProvider, isNew: Boolean,
    initialEditingModelId: String? = null,
    onAuthorizeProvider: (suspend (ModelProvider) -> Result<ModelProvider>)? = null,
    onCancelProviderAuthorization: ((ModelProvider) -> Unit)? = null,
    onClearOAuthCredentials: (suspend (ModelProvider) -> Result<ModelProvider>)? = null,
    onTestConnection: (suspend (ModelProvider, String) -> Result<String>)? = null,
    onFetchModels: (suspend (ModelProvider) -> List<DiscoveredModel>)? = null,
    onSaveModels: (String, Map<String, ModelConfiguration>) -> Unit, onReorderModels: (String, List<String>) -> Unit,
    onDismiss: () -> Unit, onSave: (ModelProvider) -> Unit,
    customImageMediaProfiles: Map<String, MediaRequestProfile> = emptyMap(),
    customVideoMediaProfiles: Map<String, MediaRequestProfile> = emptyMap()
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialProvider.name) }
    var providerIcon by remember { mutableStateOf(initialProvider.icon) }
    var iconImporting by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(initialProvider.type) }
    var baseUrl by remember { mutableStateOf(initialProvider.baseUrl) }
    var authMethod by remember { mutableStateOf(if (initialProvider.type.isAccountProvider) AuthMethod.OAUTH else initialProvider.auth.editMethod()) }
    var authName by remember { mutableStateOf(initialProvider.auth.key.orEmpty().takeIf {
        initialProvider.auth.method in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER, AuthMethod.QUERY_PARAM)
    }.orEmpty().ifBlank { if (initialProvider.auth.method == AuthMethod.QUERY_PARAM) "key" else "Authorization" }) }
    val apiKeys = remember(initialProvider.id) { ProviderApiKeysState(initialProvider.auth.token,
        initialProvider.config.multipleApiKeys, initialProvider.config.apiKeys) }
    var apiKey by apiKeys::currentKey
    var showApiKeys by remember { mutableStateOf(false) }
    fun keyIdentity(value: String) = when {
        authMethod == AuthMethod.BEARER_TOKEN -> bearerHeaderValue(authName.trim().ifBlank { "Authorization" }, value)
        authMethod == AuthMethod.PLATFORM_DEFAULT && type !in listOf(ModelApiType.GOOGLE, ModelApiType.ANTHROPIC) -> bearerHeaderValue("Authorization", value)
        else -> value.trim()
    }

    var oauthAccounts by remember(initialProvider.id) { mutableStateOf(initialProvider.oauthAccountsWithCurrent()) }
    var oauthAccountId by remember(initialProvider.id) { mutableStateOf(initialProvider.auth.oauthAccountId) }
    var showOAuthAccounts by remember { mutableStateOf(false) }
    var oauthAuthorized by remember(initialProvider.id) { mutableStateOf(initialProvider.auth.method == AuthMethod.OAUTH && initialProvider.auth.value?.isNotBlank() == true) }
    var oauthIdentity by remember(initialProvider.id) { mutableStateOf(initialProvider.auth.key?.takeIf { oauthAuthorized }) }
    var oauthMarker by remember(initialProvider.id) { mutableStateOf(initialProvider.auth.value.orEmpty().takeIf { oauthAuthorized }.orEmpty()) }
    var providerPersisted by remember(initialProvider.id, isNew) { mutableStateOf(!isNew) }
    var oauthAuthorizing by remember { mutableStateOf(false) }
    var oauthClearing by remember { mutableStateOf(false) }
    var oauthAuthError by remember { mutableStateOf<String?>(null) }
    var activeOAuthRequest by remember { mutableStateOf<ModelProvider?>(null) }
    fun authorization() = if (authMethod == AuthMethod.OAUTH) Authorization(AuthMethod.OAUTH, key = oauthIdentity, value = if (oauthAuthorized) oauthMarker else "", oauthAccountId = oauthAccountId)
        else Authorization(authMethod, if (authMethod in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM)) authName.trim() else null,
            if (authMethod == AuthMethod.BEARER_TOKEN) bearerHeaderValue(authName.ifBlank { "Authorization" }, apiKey) else apiKey.trim())
    var showTestDialog by remember { mutableStateOf(false) }
    val currentTest by rememberUpdatedState(onTestConnection)
    val tests = remember(scope, initialProvider.id) { ModelConnectionTests(scope) { provider, id ->
        currentTest?.invoke(provider, id) ?: Result.failure(IllegalStateException("Connection testing is unavailable."))
    } }
    val context = LocalContext.current
    val memory = remember(context) { ModelConfigurationMemory(context) }
    LaunchedEffect(initialProvider.id) { initialProvider.config.modelConfigs.forEach { (id, config) -> memory.remember(id, config, onlyIfMissing = true) } }
    var typeExpanded by remember { mutableStateOf(false) }
    var supportStream by remember { mutableStateOf(initialProvider.config.supportStream) }
    var showHeaders by rememberSaveable { mutableStateOf(false) }
    val headerRows = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(initialProvider.config.headers.entries.map { it.key to it.value }) } }
    val pager = rememberPagerState(pageCount = { 2 })
    // This state outlives the nested model editor, retaining its scroll position.
    val modelList = rememberSaveable(initialProvider.id, saver = LazyListState.Saver) { LazyListState() }
    var models by remember { mutableStateOf(initialProvider.config.modelIds?.associateWith { initialProvider.config.modelConfigs[it] ?: ModelConfiguration() }
        ?: initialProvider.config.modelConfigs) }
    var order by remember { mutableStateOf(models.keys.toList()) }
    var editingModel by remember(initialProvider.id, initialEditingModelId) { mutableStateOf(initialEditingModelId) }
    var showCatalog by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<Map<String, DiscoveredModel>>(emptyMap()) }
    val activeHeaders = headerRows.filterNot { (key, value) -> key.isBlank() && value.isBlank() }
    val headersValid = activeHeaders.all { (key, value) -> key.isNotBlank() && key.none { it <= ' ' || it == ':' || it.code >= 127 } && value.none { it == '\r' || it == '\n' } } &&
        activeHeaders.map { it.first.lowercase() }.distinct().size == activeHeaders.size
    val parsedHeaders = if (headersValid) activeHeaders.associate { it.first.trim() to it.second } else null
    val urlValid = type.isAccountProvider || baseUrl.isBlank() || runCatching { providerBase(initialProvider.copy(baseUrl = baseUrl)) }.isSuccess
    val authValid = apiKey.isBlank() || authMethod !in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM) || authName.isBlank() || authName.none { it <= ' ' || it == ':' || it.code >= 127 }
    val valid = urlValid && authValid && headersValid
    fun requestConfig() = initialProvider.config.copy(supportStream = supportStream, headers = parsedHeaders ?: initialProvider.config.headers,
        multipleApiKeys = apiKeys.enabled, apiKeys = apiKeys.entries, oauthAccounts = oauthAccounts,
        modelConfigs = order.associateWith { models.getValue(it) }, modelIds = order)
    fun draft(): ModelProvider {
        if (apiKeys.enabled && authMethod !in listOf(AuthMethod.OAUTH, AuthMethod.NONE, AuthMethod.OTHER)) apiKeys.ensureCurrent(::keyIdentity)
        return initialProvider.copy(name = name.trim().ifEmpty { type.defaultName }, icon = providerIcon, type = type,
            baseUrl = if (type.isAccountProvider) type.defaultBaseUrl else baseUrl.trim(), auth = authorization(), config = requestConfig())
    }
    fun selectOAuthAccount(account: ProviderOAuthAccount) {
        oauthAccountId = account.id; oauthIdentity = account.identity; oauthMarker = account.marker
        oauthAuthorized = true; oauthAuthError = null
    }
    suspend fun authorizeOAuth(accountId: String? = oauthAccountId, label: String? = null): Result<ModelProvider> {
        oauthAuthorizing = true; oauthAuthError = null
        var request = draft().copy(auth = Authorization(AuthMethod.OAUTH, oauthIdentity, oauthMarker, accountId))
        if (label != null) {
            // Carry the draft label into the successful sign-in write alongside the token reference.
            val entry = ProviderOAuthAccount(request.oauthCredentialId, label.trim(), oauthIdentity, oauthMarker)
            request = request.copy(config = request.config.copy(oauthAccounts =
                request.config.oauthAccounts.filterNot { it.id == entry.id } + entry))
        }
        activeOAuthRequest = request
        try {
            val result = onAuthorizeProvider?.invoke(request)
                ?: Result.failure(IllegalStateException("OAuth is unavailable for this provider."))
            result.onSuccess { signedIn ->
                val authorized = signedIn.recordOAuthAccount(label)
                providerPersisted = true; oauthAuthorized = true; oauthIdentity = authorized.auth.key
                oauthMarker = authorized.auth.value.orEmpty(); oauthAccountId = authorized.auth.oauthAccountId
                oauthAccounts = authorized.config.oauthAccounts; baseUrl = authorized.baseUrl
            }.onFailure { oauthAuthError = it.message ?: "OAuth authorization failed." }
            return result
        } catch (e: CancellationException) {
            oauthAuthError = context.getString(R.string.oauth_sign_in_cancelled)
            throw e
        } finally { activeOAuthRequest = null; oauthAuthorizing = false }
    }
    suspend fun removeOAuthAccount(accountId: String): Result<ModelProvider> {
        val originalAuth = authorization()
        val account = oauthAccounts.firstOrNull { it.id == accountId }
        oauthAuthorizing = true; oauthClearing = true; oauthAuthError = null
        try {
            val request = draft().copy(auth = account?.authorization() ?: originalAuth)
            val result = onClearOAuthCredentials?.invoke(request)
                ?: Result.failure(IllegalStateException("OAuth credential removal is unavailable."))
            result.onSuccess { cleared ->
                oauthAccounts = cleared.config.oauthAccounts
                val auth = if ((originalAuth.oauthAccountId ?: initialProvider.id) != accountId) originalAuth else cleared.auth
                oauthAccountId = auth.oauthAccountId; oauthIdentity = auth.key; oauthMarker = auth.value.orEmpty()
                oauthAuthorized = !auth.value.isNullOrBlank()
            }.onFailure { oauthAuthError = it.message ?: "Could not remove OAuth credentials." }
            return result
        } finally { oauthClearing = false; oauthAuthorizing = false }
    }
    fun updateModels(updated: Map<String, ModelConfiguration>) {
        val existing = order.toSet()
        order = order.filter { it in updated } + updated.keys.filterNot { it in existing }
        models = updated
        if (providerPersisted) onSaveModels(initialProvider.id, order.associateWith { updated.getValue(it) })
    }
    BackHandler(enabled = LocalScreenTransitionActive.current && editingModel == null, onBack = onDismiss)
    SlideScreenContent(editor = editingModel, label = "Model editor") { modelId ->
        if (modelId != null) key(modelId) {
            ModelEditorScreen(initialProvider.copy(type = type, config = requestConfig()), modelId, models.keys,
                customImageMediaProfiles = customImageMediaProfiles,
                customVideoMediaProfiles = customVideoMediaProfiles,
                onSave = { id, config -> memory.remember(id, config); updateModels((models - modelId) + (id to config)); editingModel = null },
                onBack = { editingModel = null })
        } else {
            Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
                TopAppBar(title = { HeaderNameField(name, { name = it }, placeholder = type.defaultName) },
                    navigationIcon = { IconButton(onClick = onDismiss) { FluentIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                    actions = {
                        if (pager.currentPage == 0) Button(enabled = valid && !oauthAuthorizing && !iconImporting,
                            onClick = { onSave(draft()) }, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.common_save)) }
                        else {
                            val testingConnection = tests.batchRunning.value || tests.running.values.any { it }
                            IconButton(onClick = { showTestDialog = true },
                                enabled = testingConnection || (onTestConnection != null && (type.isAccountProvider || baseUrl.isNotBlank()) && valid && models.isNotEmpty())) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (testingConnection && !showTestDialog) CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 2.dp)
                                    FluentIcon(Icons.Outlined.NetworkCheck, stringResource(R.string.test_connection_lower))
                                }
                            }
                            IconButton(onClick = { editingModel = "" }) { FluentIcon(Icons.Default.Add, stringResource(R.string.add_model_manually)) }
                            IconButton(onClick = { showCatalog = true }) { FluentIcon(Icons.Outlined.FormatListBulleted, stringResource(R.string.open_model_catalog)) }
                        }
                    })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                        Tab(pager.currentPage == 0, { scope.launch { pager.animateScrollToPage(0) } }, text = { Text(stringResource(R.string.provider_configuration)) })
                        Tab(pager.currentPage == 1, { scope.launch { pager.animateScrollToPage(1) } }, text = { Text(stringResource(R.string.provider_models)) })
                    }
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                        if (page == 0) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconPickerRow(providerIcon, { providerIcon = it }, text = providerInitials(name), onBusyChange = { iconImporting = it }, matchName = name, faviconBaseUrl = baseUrl)
                            ExposedDropdownMenuBox(typeExpanded, { typeExpanded = !typeExpanded }, modifier = Modifier.fillMaxWidth()) {
                                SelectableOutlinedTextField(type.displayName, {}, readOnly = true, label = { Text(stringResource(R.string.common_type)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth())
                                ExposedDropdownMenu(typeExpanded, { typeExpanded = false }) {
                                    ModelApiType.entries.forEach { next -> RoundedDropdownMenuItem(text = { Text(next.displayName) }, onClick = {
                                        if (next != type) {
                                            val updated = initialProvider.copy(name = name, type = type, baseUrl = baseUrl).withModelApiType(next)
                                            name = updated.name; baseUrl = updated.baseUrl; type = updated.type
                                            val auth = next.defaultAuthorization()
                                            // Keep the entered API key; only drop the "Bearer " prefix the bearer field adds,
                                            // since the new type may send the key in a different form.
                                            if (authMethod == AuthMethod.BEARER_TOKEN) apiKey = apiKey.replace(Regex("^\\s*Bearer\\s+", RegexOption.IGNORE_CASE), "")
                                            authMethod = auth.method; authName = auth.key.orEmpty()
                                            oauthAuthorized = false; oauthIdentity = null; oauthMarker = ""; oauthAccountId = null; oauthAccounts = emptyList(); oauthAuthError = null
                                        }
                                        typeExpanded = false
                                    }) }
                                }
                            }
                            if (!type.isAccountProvider) ProviderBaseUrlField(type, baseUrl, urlValid) { baseUrl = it }
                            if (!type.isAccountProvider && baseUrl.trim().startsWith("http://", true)) Text(stringResource(R.string.http_unencrypted_provider_warning),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (authMethod in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM)) SelectableOutlinedTextField(authName, { authName = it },
                                label = { Text(if (authMethod == AuthMethod.BEARER_TOKEN) "Header key" else "Query parameter name") },
                                isError = !authValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (!type.isAccountProvider || authMethod != AuthMethod.OAUTH) ProviderAuthField(authMethod, apiKey, !type.isAccountProvider,
                                onMethodChange = { authMethod = it; authName = if (it == AuthMethod.QUERY_PARAM) "key" else "Authorization"; oauthAuthError = null },
                                onValueChange = { apiKey = it }, onFocusLost = { if (authMethod == AuthMethod.BEARER_TOKEN) apiKey = bearerHeaderValue(authName.trim().ifBlank { "Authorization" }, apiKey) })
                            if (authMethod !in listOf(AuthMethod.OAUTH, AuthMethod.NONE, AuthMethod.OTHER)) {
                                Row(Modifier.fillMaxWidth().toggleable(value = apiKeys.enabled, role = Role.Checkbox, onValueChange = {
                                    apiKeys.enabled = it
                                    if (it) apiKeys.ensureCurrent(::keyIdentity)
                                }), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = apiKeys.enabled, onCheckedChange = null)
                                    Text(stringResource(R.string.provider_multiple_api_keys), Modifier.padding(start = 8.dp))
                                }
                                if (apiKeys.enabled) OutlinedButton(onClick = {
                                    apiKeys.ensureCurrent(::keyIdentity); showApiKeys = true
                                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.provider_manage_api_keys)) }
                            }
                            if (authMethod == AuthMethod.OAUTH) OAuthAccountCard(signedIn = oauthAuthorized, identity = oauthIdentity,
                                signingIn = oauthAuthorizing && !oauthClearing, signingOut = oauthClearing, canSignIn = onAuthorizeProvider != null,
                                canSignOut = onClearOAuthCredentials != null, canCancelSignIn = onCancelProviderAuthorization != null, error = oauthAuthError,
                                onSignIn = rememberOAuthStart { scope.launch {
                                    try { authorizeOAuth() } catch (_: CancellationException) { }
                                } }, onCancelSignIn = {
                                    activeOAuthRequest?.let { onCancelProviderAuthorization?.invoke(it) }
                                }, onSignOut = { scope.launch { removeOAuthAccount(oauthAccountId ?: initialProvider.id) } })
                            if (authMethod == AuthMethod.OAUTH) {
                                Row(Modifier.fillMaxWidth().toggleable(value = apiKeys.enabled, role = Role.Checkbox,
                                    enabled = !oauthAuthorizing, onValueChange = { apiKeys.enabled = it }),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = apiKeys.enabled, onCheckedChange = null, enabled = !oauthAuthorizing)
                                    Text(stringResource(R.string.provider_multiple_accounts), Modifier.padding(start = 8.dp))
                                }
                                if (apiKeys.enabled) OutlinedButton(onClick = { showOAuthAccounts = true },
                                    enabled = !oauthAuthorizing, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.provider_manage_accounts))
                                }
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.stream), Modifier.weight(1f).padding(end = 12.dp))
                                Switch(supportStream, onCheckedChange = { supportStream = it })
                            }
                            HorizontalDivider()
                            OutlinedButton(onClick = { showHeaders = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.headers))
                            }
                            if (!headersValid && !showHeaders) Text("Header names and values must be valid and header names must be unique.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        } else ProviderModelsPane(order, models, modelList, onMove = { from, to -> order = order.moved(from, to) },
                            onDrop = { if (providerPersisted) onReorderModels(initialProvider.id, order) }, onEdit = { editingModel = it },
                            onDelete = { updateModels(models - it) }, onOpenCatalog = { showCatalog = true })
                    }
                }
            }
            if (showOAuthAccounts) ProviderOAuthAccountsDialog(
                accounts = oauthAccounts, selectedId = (oauthAccountId ?: initialProvider.id).takeIf { oauthAuthorized },
                canAuthorize = onAuthorizeProvider != null, canDelete = onClearOAuthCredentials != null,
                onSelect = { selectOAuthAccount(it); showOAuthAccounts = false },
                onSaveLabel = { id, label -> oauthAccounts = oauthAccounts.map { if (it.id == id) it.copy(label = label.trim()) else it } },
                onAuthorize = { id, label -> authorizeOAuth(id ?: "${initialProvider.id}:${java.util.UUID.randomUUID()}", label) },
                onCancelAuthorization = { activeOAuthRequest?.let { onCancelProviderAuthorization?.invoke(it) } },
                onDelete = { removeOAuthAccount(it) }, onDismiss = { showOAuthAccounts = false }
            )
            if (showHeaders) ProviderHeadersDialog(headerRows, headersValid, onDismiss = { showHeaders = false })
            if (showApiKeys) ProviderApiKeysDialog(apiKeys, ::keyIdentity, onDismiss = { showApiKeys = false })
            if (showTestDialog) ModelConnectionDialog(draft().copy(name = name.trim().ifEmpty { "Provider" }), tests, onTestConnection != null,
                onRemoveModels = { updateModels(models - it) }, onDismiss = { showTestDialog = false })
            if (showCatalog) ProviderModelCatalogSheet(initialProvider.copy(name = name, type = type, baseUrl = baseUrl.trim(), auth = authorization(), config = requestConfig()),
                models.keys, onFetchModels, onCatalogLoaded = { discovered ->
                    catalog = discovered.associateBy { it.id }
                    updateModels(models.mapValues { (id, config) -> val metadata = catalog[id]
                        config.copy(modelJson = metadata?.metadata ?: config.modelJson,
                            contextWindowTokens = metadata?.contextWindowTokens ?: config.contextWindowTokens,
                            displayName = config.displayName.ifBlank { metadata?.displayName.orEmpty() }) })
                }, onToggle = { id -> updateModels(if (id in models) models - id else models + (id to memory.configurationFor(catalog[id] ?: DiscoveredModel(id)))) },
                onSetSelection = { ids, selected -> updateModels(if (selected) models + ids.associateWith { models[it] ?: memory.configurationFor(catalog[it] ?: DiscoveredModel(it)) } else models - ids.toSet()) },
                onDismiss = { showCatalog = false })
        }
    }
}
