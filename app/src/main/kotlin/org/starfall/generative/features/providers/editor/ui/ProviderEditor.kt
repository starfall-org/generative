package org.starfall.generative.features.providers.editor.ui

import org.starfall.generative.shared.ui.layout.AppSwipeTabs
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.LoadingIndicator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import org.starfall.generative.features.providers.shared.ui.AnthropicPromptCacheSettings
import org.starfall.generative.features.providers.shared.ui.AntigravityVersionFields
import org.starfall.generative.features.providers.shared.ui.AntigravityProjectIdField
import org.starfall.generative.features.providers.shared.ui.GeminiCliProjectIdField
import org.starfall.generative.features.providers.shared.ui.CodexClientVersionField
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
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.DEFAULT_CODEX_CLIENT_VERSION
import org.starfall.generative.shared.model.provider.DEFAULT_ANTIGRAVITY_VERSION
import org.starfall.generative.shared.model.provider.isValidAntigravityChangelist
import org.starfall.generative.shared.model.provider.isValidClientVersion
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.provider.ProviderOAuthAccount
import org.starfall.generative.shared.model.provider.oauthAccountsWithCurrent
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.model.provider.recordOAuthAccount
import org.starfall.generative.shared.model.provider.removeOAuthAccount
import org.starfall.generative.shared.networking.providerBase
import org.starfall.generative.shared.storage.preferences.ModelConfigurationMemory
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.IconPickerRow
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.text.OutlinedTextField
import org.starfall.generative.shared.ui.layout.RetainedScreenContent
import org.starfall.generative.shared.ui.lists.moved
import org.starfall.generative.shared.ui.icons.providerInitials
import org.starfall.generative.shared.ui.auth.rememberOAuthStart

// Chat Completions remains endpoint-agnostic in the shared API model and provider links.
// Only the editor offers OpenAI starter values, as it already does for Responses.
private val ModelApiType.editorDefaultName: String
    get() = if (this == ModelApiType.OPENAI) "OpenAI" else defaultName

private val ModelApiType.editorDefaultBaseUrl: String
    get() = if (this == ModelApiType.OPENAI) ModelApiType.OPENAI_RESPONSES.defaultBaseUrl else defaultBaseUrl

/** Replace only the previous type's defaults; preserve user-entered names and endpoints. */
private fun ModelProvider.withEditorApiType(newType: ModelApiType): ModelProvider = copy(
    type = newType,
    name = if (name == type.defaultName || name == type.editorDefaultName) newType.editorDefaultName else name,
    baseUrl = if (baseUrl.trimEnd('/') == type.defaultBaseUrl ||
        baseUrl.trimEnd('/') == type.editorDefaultBaseUrl
    ) newType.editorDefaultBaseUrl else baseUrl
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderEditor(
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
    customVideoMediaProfiles: Map<String, MediaRequestProfile> = emptyMap(),
    onSaveIcon: (ModelProvider) -> Unit = {},
    onSaveModelIcon: (String, String, String?) -> Unit = { _, _, _ -> }
) {
    val scope = rememberCoroutineScope()
    var name by remember {
        mutableStateOf(if (isNew && initialProvider.type == ModelApiType.OPENAI && initialProvider.name.isBlank())
            ModelApiType.OPENAI.editorDefaultName else initialProvider.name)
    }
    var providerIcon by remember { mutableStateOf(initialProvider.icon) }
    var iconImporting by remember { mutableStateOf(false) }
    // Lives with the screen, not the pager page, so a logo fetch survives tab switches and stops when the screen closes.
    val faviconFetch = org.starfall.generative.shared.ui.dialogs.rememberFaviconFetchState()
    var type by remember { mutableStateOf(initialProvider.type) }
    var baseUrl by remember {
        mutableStateOf(if (isNew && initialProvider.type == ModelApiType.OPENAI && initialProvider.baseUrl.isBlank())
            ModelApiType.OPENAI.editorDefaultBaseUrl else initialProvider.baseUrl)
    }
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
    var geminiCliProjectIds by remember(initialProvider.id) { mutableStateOf(initialProvider.config.geminiCliProjectIds) }
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
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val tests = remember(scope, initialProvider.id) { ModelConnectionTests(scope) { provider, id ->
        currentTest?.invoke(provider, id) ?: Result.failure(IllegalStateException(localizedResources.getString(R.string.connection_testing_unavailable)))
    } }
    val memory = remember(context) { ModelConfigurationMemory(context) }
    LaunchedEffect(initialProvider.id) { initialProvider.config.modelConfigs.forEach { (id, config) -> memory.remember(id, config, onlyIfMissing = true) } }
    var supportStream by remember { mutableStateOf(initialProvider.config.supportStream) }
    var anthropicPromptCache by remember(initialProvider.id) { mutableStateOf(initialProvider.config.anthropicPromptCache) }
    var codexClientVersion by remember(initialProvider.id) {
        mutableStateOf(initialProvider.config.codexClientVersion ?: DEFAULT_CODEX_CLIENT_VERSION)
    }
    var antigravityVersion by remember(initialProvider.id) {
        mutableStateOf(initialProvider.config.antigravityVersion ?: DEFAULT_ANTIGRAVITY_VERSION)
    }
    var antigravityChangelist by remember(initialProvider.id) {
        mutableStateOf(initialProvider.config.antigravityChangelist.orEmpty())
    }
    var antigravityProjectIds by remember(initialProvider.id) {
        mutableStateOf(initialProvider.config.antigravityProjectIds)
    }
    var showEndpoints by rememberSaveable { mutableStateOf(false) }
    var endpoints by remember(initialProvider.id) { mutableStateOf(initialProvider.config.customEndpoints.let {
        if ("catalog" !in it && !initialProvider.config.customListModelsUrl.isNullOrBlank()) it + ("catalog" to initialProvider.config.customListModelsUrl.orEmpty()) else it
    }) }
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
    // Probes belong to this screen rather than the dialog, so the top-bar action can keep showing
    // running state while the dialog is closed and testing still stops when the editor is left.
    val testScope = order.filter { models[it]?.modelType == ModelType.TEXT_GENERATION }
    val testingConnection = tests.batchRunning.value || testScope.any { tests.running[it] == true }
    val activeHeaders = headerRows.filterNot { (key, value) -> key.isBlank() && value.isBlank() }
    val headersValid = activeHeaders.all { (key, value) -> key.isNotBlank() && key.none { it <= ' ' || it == ':' || it.code >= 127 } && value.none { it == '\r' || it == '\n' } } &&
        activeHeaders.map { it.first.lowercase() }.distinct().size == activeHeaders.size
    val parsedHeaders = if (headersValid) activeHeaders.associate { it.first.trim() to it.second } else null
    val urlValid = type.isAccountProvider || baseUrl.isBlank() ||
        runCatching { providerBase(initialProvider.copy(type = type, baseUrl = baseUrl)) }.isSuccess
    val authValid = apiKey.isBlank() || authMethod !in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM) || authName.isBlank() || authName.none { it <= ' ' || it == ':' || it.code >= 127 }
    val codexClientVersionValid = type != ModelApiType.OPENAI_CODEX || isValidClientVersion(codexClientVersion)
    val antigravityVersionValid = type != ModelApiType.ANTIGRAVITY || isValidClientVersion(antigravityVersion)
    val antigravityChangelistValid = type != ModelApiType.ANTIGRAVITY || isValidAntigravityChangelist(antigravityChangelist)
    val endpointsValid = endpointFields(type).all { (key, _) -> endpoints[key].isNullOrBlank() || validEndpoint(endpoints.getValue(key), key) }
    val valid = urlValid && authValid && headersValid && endpointsValid && codexClientVersionValid &&
        antigravityVersionValid && antigravityChangelistValid
    fun requestConfig(
        modelOrder: List<String> = order,
        modelValues: Map<String, ModelConfiguration> = models
    ) = initialProvider.config.copy(customEndpoints = endpoints.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }, customListModelsUrl = null, supportStream = supportStream, anthropicPromptCache = anthropicPromptCache, headers = parsedHeaders ?: initialProvider.config.headers,
        multipleApiKeys = apiKeys.enabled, apiKeys = apiKeys.entries, oauthAccounts = oauthAccounts,
        codexClientVersion = if (type == ModelApiType.OPENAI_CODEX) codexClientVersion.trim().ifEmpty { null } else null,
        antigravityVersion = if (type == ModelApiType.ANTIGRAVITY) antigravityVersion.trim().ifEmpty { null } else null,
        antigravityChangelist = if (type == ModelApiType.ANTIGRAVITY) antigravityChangelist.trim().ifEmpty { null } else null,
        antigravityProjectIds = if (type == ModelApiType.ANTIGRAVITY) antigravityProjectIds else emptyMap(),
        geminiCliProjectIds = if (type == ModelApiType.GEMINI_CLI) geminiCliProjectIds else emptyMap(),
        modelConfigs = modelOrder.associateWith { modelValues.getValue(it) }, modelIds = modelOrder)
    fun draft(
        modelOrder: List<String> = order,
        modelValues: Map<String, ModelConfiguration> = models
    ): ModelProvider {
        if (apiKeys.enabled && authMethod !in listOf(AuthMethod.OAUTH, AuthMethod.NONE, AuthMethod.OTHER)) apiKeys.ensureCurrent(::keyIdentity)
        return initialProvider.copy(name = name.trim().ifEmpty { type.defaultName }, icon = providerIcon, type = type,
            baseUrl = if (type.isAccountProvider) type.defaultBaseUrl else baseUrl.trim(),
            auth = authorization(), config = requestConfig(modelOrder, modelValues))
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
                ?: Result.failure(IllegalStateException(localizedResources.getString(R.string.provider_oauth_unavailable)))
            result.onSuccess { signedIn ->
                val authorized = signedIn.recordOAuthAccount(label)
                providerPersisted = true; oauthAuthorized = true; oauthIdentity = authorized.auth.key
                oauthMarker = authorized.auth.value.orEmpty(); oauthAccountId = authorized.auth.oauthAccountId
                oauthAccounts = authorized.config.oauthAccounts; baseUrl = authorized.baseUrl
                if (authorized.type == ModelApiType.ANTIGRAVITY) antigravityProjectIds = authorized.config.antigravityProjectIds
                if (authorized.type == ModelApiType.GEMINI_CLI) geminiCliProjectIds = authorized.config.geminiCliProjectIds
            }.onFailure { oauthAuthError = it.message ?: localizedResources.getString(R.string.provider_oauth_failed) }
            return result
        } catch (e: CancellationException) {
            oauthAuthError = localizedResources.getString(R.string.oauth_sign_in_cancelled)
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
                ?: Result.failure(IllegalStateException(localizedResources.getString(R.string.provider_oauth_removal_unavailable)))
            result.onSuccess { cleared ->
                oauthAccounts = cleared.config.oauthAccounts
                antigravityProjectIds = cleared.config.antigravityProjectIds
                geminiCliProjectIds = cleared.config.geminiCliProjectIds
                val auth = if ((originalAuth.oauthAccountId ?: initialProvider.id) != accountId) originalAuth else cleared.auth
                oauthAccountId = auth.oauthAccountId; oauthIdentity = auth.key; oauthMarker = auth.value.orEmpty()
                oauthAuthorized = !auth.value.isNullOrBlank()
            }.onFailure { oauthAuthError = it.message ?: localizedResources.getString(R.string.provider_oauth_remove_failed) }
            return result
        } finally { oauthClearing = false; oauthAuthorizing = false }
    }
    fun updateModels(updated: Map<String, ModelConfiguration>) {
        val existing = order.toSet()
        val nextOrder = order.filter { it in updated } + updated.keys.filterNot { it in existing }
        order = nextOrder
        models = updated
        val savedModels = nextOrder.associateWith { updated.getValue(it) }
        if (providerPersisted) {
            onSaveModels(initialProvider.id, savedModels)
        } else if (updated.isNotEmpty()) {
            // A new provider does not exist in storage until its first model is added. Persist the
            // complete draft now so subsequent model edits can use the normal model-only writes.
            onSave(draft(nextOrder, updated))
            providerPersisted = true
        }
    }
    BackHandler(enabled = LocalScreenTransitionActive.current && editingModel == null, onBack = onDismiss)
    RetainedScreenContent(editor = editingModel) { modelId ->
        if (modelId != null) key(modelId) {
            var iconCreatedModelId by remember { mutableStateOf<String?>(null) }
            ModelEditor(initialProvider.copy(type = type, config = requestConfig()), modelId, models.keys,
                customImageMediaProfiles = customImageMediaProfiles,
                customVideoMediaProfiles = customVideoMediaProfiles,
                onSave = { id, config ->
                    memory.remember(id, config)
                    updateModels((models - modelId - setOfNotNull(iconCreatedModelId)) + (id to config))
                    editingModel = null
                },
                onSaveIcon = { id, icon ->
                    if (modelId.isBlank()) iconCreatedModelId = id
                    val updated = (models[id] ?: ModelConfiguration()).copy(icon = icon)
                    models = models + (id to updated)
                    if (id !in order) order = order + id
                    if (providerPersisted) onSaveModelIcon(initialProvider.id, id, icon)
                    else {
                        onSaveIcon(draft())
                        providerPersisted = true
                    }
                },
                onBack = { editingModel = null })
        } else {
            Scaffold(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,modifier = Modifier.fillMaxSize(), topBar = {
                TopAppBar(
                    colors = edgeToEdgeTopAppBarColors(),
            title = {
                    Text(name.trim().ifEmpty { type.defaultName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                    navigationIcon = { IconButton(onClick = onDismiss) { SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                    actions = {
                        if (pager.currentPage == 0) Button(enabled = valid && !oauthAuthorizing && !iconImporting,
                            onClick = { onSave(draft()); providerPersisted = true }, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.common_save)) }
                        else {
                            IconButton(onClick = { showTestDialog = true },
                                enabled = testingConnection || (onTestConnection != null && (type.isAccountProvider || baseUrl.isNotBlank()) && valid && models.isNotEmpty())) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (testingConnection && !showTestDialog) LoadingIndicator(Modifier.size(36.dp))
                                    SharedIcon(Icons.Outlined.NetworkCheck, stringResource(R.string.test_connection))
                                }
                            }
                            IconButton(onClick = { editingModel = "" }) { SharedIcon(Icons.Default.Add, stringResource(R.string.common_add)) }
                            IconButton(onClick = { showCatalog = true }) { SharedIcon(Icons.Outlined.FormatListBulleted, stringResource(R.string.open_model_catalog)) }
                        }
                    })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    AppSwipeTabs(
                        tabs = listOf(stringResource(R.string.common_configuration), stringResource(R.string.provider_models)),
                        pagerState = pager,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) { page ->
                        if (page == 0) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconPickerRow(providerIcon, {
                                providerIcon = it
                                onSaveIcon(draft())
                                providerPersisted = true
                            }, text = providerInitials(name), onBusyChange = { iconImporting = it }, matchName = name, faviconBaseUrl = baseUrl, fetchState = faviconFetch)
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text(stringResource(R.string.common_name)) },
                                placeholder = { Text(type.defaultName) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            ProviderApiTypeSelector(type) { next ->
                                if (next != type) {
                                    val updated = initialProvider.copy(name = name, type = type, baseUrl = baseUrl)
                                        .withEditorApiType(next)
                                    name = updated.name
                                    baseUrl = updated.baseUrl
                                    type = updated.type
                                    val auth = next.defaultAuthorization()
                                    if (authMethod == AuthMethod.BEARER_TOKEN) {
                                        apiKey = apiKey.replace(Regex("^\\s*Bearer\\s+", RegexOption.IGNORE_CASE), "")
                                    }
                                    authMethod = auth.method
                                    authName = auth.key.orEmpty()
                                    oauthAuthorized = false
                                    oauthIdentity = null
                                    oauthMarker = ""
                                    oauthAccountId = null
                                    oauthAccounts = emptyList()
                                    antigravityProjectIds = emptyMap()
                                    geminiCliProjectIds = emptyMap()
                                    oauthAuthError = null
                                }
                            }
                            if (!type.isAccountProvider) ProviderBaseUrlField(type, baseUrl, urlValid) { baseUrl = it }
                            if (!type.isAccountProvider && baseUrl.trim().startsWith("http://", true)) Text(stringResource(R.string.http_unencrypted_provider_warning),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (type == ModelApiType.OPENAI_CODEX) CodexClientVersionField(
                                codexClientVersion, codexClientVersionValid) { codexClientVersion = it }
                            if (type == ModelApiType.ANTIGRAVITY) AntigravityVersionFields(
                                antigravityVersion, antigravityVersionValid, { antigravityVersion = it },
                                antigravityChangelist, antigravityChangelistValid, { antigravityChangelist = it })
                            if (authMethod in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM)) OutlinedTextField(authName, { authName = it },
                                label = { Text(stringResource(if (authMethod == AuthMethod.BEARER_TOKEN) R.string.header_key else R.string.query_parameter_name)) },
                                isError = !authValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (!type.isAccountProvider || authMethod != AuthMethod.OAUTH) ProviderAuthField(authMethod, apiKey, !type.isAccountProvider,
                                onMethodChange = { authMethod = it; authName = if (it == AuthMethod.QUERY_PARAM) "key" else "Authorization"; oauthAuthError = null },
                                onValueChange = { apiKey = it }, onFocusLost = { if (authMethod == AuthMethod.BEARER_TOKEN) apiKey = bearerHeaderValue(authName.trim().ifBlank { "Authorization" }, apiKey) })
                            if (authMethod !in listOf(AuthMethod.OAUTH, AuthMethod.NONE, AuthMethod.OTHER)) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .toggleable(value = apiKeys.enabled, role = Role.Checkbox, onValueChange = {
                                            apiKeys.enabled = it
                                            if (it) apiKeys.ensureCurrent(::keyIdentity)
                                        }),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = apiKeys.enabled, onCheckedChange = null)
                                    Text(stringResource(R.string.provider_multiple_api_keys), Modifier.padding(start = 8.dp))
                                }
                                if (apiKeys.enabled) OutlinedButton(onClick = {
                                    apiKeys.ensureCurrent(::keyIdentity); showApiKeys = true
                                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.provider_manage_api_keys)) }
                            }
                            if (authMethod == AuthMethod.OAUTH) OAuthAccountCard(providerType = type, signedIn = oauthAuthorized, identity = oauthIdentity,
                                signingIn = oauthAuthorizing && !oauthClearing, signingOut = oauthClearing, canSignIn = onAuthorizeProvider != null,
                                canSignOut = onClearOAuthCredentials != null, canCancelSignIn = onCancelProviderAuthorization != null, error = oauthAuthError,
                                onSignIn = rememberOAuthStart { scope.launch {
                                    try { authorizeOAuth() } catch (_: CancellationException) { }
                                } }, onCancelSignIn = {
                                    activeOAuthRequest?.let { onCancelProviderAuthorization?.invoke(it) }
                                }, onSignOut = { scope.launch { removeOAuthAccount(oauthAccountId ?: initialProvider.id) } })
                            if (type == ModelApiType.ANTIGRAVITY && authMethod == AuthMethod.OAUTH && oauthAuthorized) {
                                val projectAccountId = oauthAccountId?.takeIf(String::isNotBlank) ?: initialProvider.id
                                AntigravityProjectIdField(antigravityProjectIds[projectAccountId].orEmpty()) { value ->
                                    antigravityProjectIds = if (value.isBlank()) antigravityProjectIds - projectAccountId
                                    else antigravityProjectIds + (projectAccountId to value)
                                }
                            }
                            if (type == ModelApiType.GEMINI_CLI && authMethod == AuthMethod.OAUTH) {
                                val projectAccountId = oauthAccountId?.takeIf(String::isNotBlank) ?: initialProvider.id
                                GeminiCliProjectIdField(geminiCliProjectIds[projectAccountId].orEmpty()) { value ->
                                    geminiCliProjectIds = if (value.isBlank()) geminiCliProjectIds - projectAccountId
                                    else geminiCliProjectIds + (projectAccountId to value.trim())
                                }
                            }
                            if (authMethod == AuthMethod.OAUTH) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .toggleable(value = apiKeys.enabled, role = Role.Checkbox,
                                            enabled = !oauthAuthorizing, onValueChange = { apiKeys.enabled = it }),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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
                            if (type == ModelApiType.ANTHROPIC) {
                                AnthropicPromptCacheSettings(anthropicPromptCache) { anthropicPromptCache = it }
                                HorizontalDivider()
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showHeaders = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.headers)) }
                                if (endpointFields(type).isNotEmpty()) OutlinedButton(onClick = { showEndpoints = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.provider_custom)) }
                            }
                            if (!endpointsValid && !showEndpoints) Text(stringResource(R.string.provider_endpoints_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (!headersValid && !showHeaders) Text(stringResource(R.string.provider_headers_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        } else ProviderModelsPane(order, models, modelList, onMove = { from, to -> order = order.moved(from, to) },
                            onDrop = { if (providerPersisted) onReorderModels(initialProvider.id, order) }, onEdit = { editingModel = it },
                            onDelete = { updateModels(models - it) }, onOpenCatalog = { showCatalog = true })
                    }
                }
            }
            if (showOAuthAccounts) ProviderOAuthAccountsDialog(
                providerType = type, accounts = oauthAccounts, selectedId = (oauthAccountId ?: initialProvider.id).takeIf { oauthAuthorized },
                canAuthorize = onAuthorizeProvider != null, canDelete = onClearOAuthCredentials != null,
                onSelect = { selectOAuthAccount(it); showOAuthAccounts = false },
                onSaveLabel = { id, label -> oauthAccounts = oauthAccounts.map { if (it.id == id) it.copy(label = label.trim()) else it } },
                onAuthorize = { id, label -> authorizeOAuth(id ?: "${initialProvider.id}:${java.util.UUID.randomUUID()}", label) },
                onCancelAuthorization = { activeOAuthRequest?.let { onCancelProviderAuthorization?.invoke(it) } },
                onDelete = { removeOAuthAccount(it) }, onDismiss = { showOAuthAccounts = false }
            )
            if (showEndpoints) ProviderEndpointsDialog(type, baseUrl, endpoints, { endpoints = it }, onDismiss = { showEndpoints = false })
            if (showHeaders) ProviderHeadersDialog(headerRows, headersValid, onDismiss = { showHeaders = false })
            if (showApiKeys) ProviderApiKeysDialog(apiKeys, ::keyIdentity, onDismiss = { showApiKeys = false })
            if (showTestDialog) ModelConnectionDialog(draft().copy(name = name.trim().ifEmpty { localizedResources.getString(R.string.provider_fallback_name) }), tests, onTestConnection != null,
                onRemoveModels = { updateModels(models - it) }, onDismiss = { showTestDialog = false })
            if (showCatalog) ModelCatalogSheet(
                provider = initialProvider.copy(name = name, type = type, baseUrl = baseUrl.trim(),
                    auth = authorization(), config = requestConfig()),
                selectedModels = models.keys,
                onFetchModels = onFetchModels,
                onCatalogLoaded = { discovered ->
                    catalog = discovered.associateBy { it.id }
                    updateModels(models.mapValues { (id, config) ->
                        val metadata = catalog[id]
                        config.copy(modelJson = metadata?.metadata ?: config.modelJson,
                            contextWindowTokens = metadata?.contextWindowTokens ?: config.contextWindowTokens,
                            displayName = config.displayName.ifBlank { metadata?.displayName.orEmpty() })
                    })
                },
                onToggle = { id ->
                    updateModels(if (id in models) models - id
                        else models + (id to memory.configurationFor(catalog[id] ?: DiscoveredModel(id))))
                },
                onSetSelection = { ids, selected ->
                    updateModels(if (selected) models + ids.associateWith {
                        models[it] ?: memory.configurationFor(catalog[it] ?: DiscoveredModel(it))
                    } else models - ids.toSet())
                },
                onDismiss = { showCatalog = false }
            )
        }
    }
}
