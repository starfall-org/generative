package org.starfall.generative.features.chatoptions.ui

import androidx.compose.ui.platform.LocalLayoutDirection
import org.starfall.generative.shared.ui.layout.AppSwipeTabs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import org.starfall.generative.features.chatoptions.logic.readAgentSkillDocument
import org.starfall.generative.features.chatoptions.logic.writeAgentSkillDocument
import org.starfall.generative.integrations.github.downloadGitHubSkill
import org.starfall.generative.integrations.github.parseGitHubSkillUrl
import org.starfall.generative.shared.model.parseAgentSkillDocument
import org.starfall.generative.shared.model.agentSkillDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.SavedInstruction
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.defaultAgentSkills
import org.starfall.generative.shared.model.isValidAgentSkillName
import java.util.UUID
import kotlinx.coroutines.launch
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.screens.ListScreenTemplate
import org.starfall.generative.shared.ui.screens.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.screens.ScreenDestination
import org.starfall.generative.shared.subfeatures.modelpicker.ui.DefaultModelSelector
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ModelPickerSheet
import org.starfall.generative.shared.model.preferences.AppPreferences

private sealed interface ChatOptionsEditor {
    data class InstructionEditor(val instructionId: String?) : ChatOptionsEditor
    data class InstructionPreview(val instructionId: String) : ChatOptionsEditor
    data class SkillEditor(val skillId: String?) : ChatOptionsEditor
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatOptionsScreen(
    providers: List<ModelProvider>,
    settings: ToolSettings,
    mcpServers: List<McpInfo> = emptyList(),
    onSetWebSearchProvider: (String?) -> Unit = {},
    onSetWebSearchEnabled: (Boolean) -> Unit = {},
    speechServices: List<SpeechService> = emptyList(),
    selectedSpeechServiceId: String? = null,
    onSetTtsProvider: (String) -> Unit = {},
    appPreferences: AppPreferences = AppPreferences(),
    onAutoSummaryEnabledChange: (Boolean) -> Unit = {},
    onSummarySuggestionEnabledChange: (Boolean) -> Unit = {},
    onAutoSummaryPercentChange: (Int) -> Unit = {},
    providerGroups: List<ProviderGroup> = emptyList(),
    collapsedGroupIds: Set<String> = emptySet(),
    collapsedProviderIds: Set<String> = emptySet(),
    onCollapsedGroupIdsChange: (Set<String>) -> Unit = {},
    onCollapsedProviderIdsChange: (Set<String>) -> Unit = {},
    onEditProvider: (providerId: String) -> Unit = {},
    onEditModel: ((providerId: String, modelId: String) -> Unit)? = null,
    instructionLibrary: InstructionLibrary = InstructionLibrary(),
    onInstructionLibraryChange: (InstructionLibrary) -> Unit = {},
    agentSkills: List<AgentSkill> = defaultAgentSkills(),
    onAgentSkillsChange: (List<AgentSkill>) -> Unit = {},
    initialTab: String = "helpers",
    initialInstructionId: String = "",
    onSave: (String, SystemToolConfig) -> Unit,
    onBack: () -> Unit
) {
    var choosing by remember { mutableStateOf<String?>(null) }
    var helperSettings by rememberSaveable { mutableStateOf<String?>(null) }
    var isGrid by rememberSaveable { mutableStateOf(false) }
    val initialPage = when (initialTab) {
        "instructions" -> 1
        "skills" -> 2
        else -> 0
    }
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    var lastRequestedTab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) {
        if (lastRequestedTab != initialTab) {
            lastRequestedTab = initialTab
            val requestedPage = when (initialTab) {
                "instructions" -> 1
                "skills" -> 2
                else -> 0
            }
            pager.scrollToPage(requestedPage)
        }
    }
    var creatingInstruction by rememberSaveable(initialInstructionId) { mutableStateOf(false) }
    var editingInstructionId by rememberSaveable(initialInstructionId) {
        mutableStateOf(initialInstructionId.takeIf(String::isNotBlank))
    }
    var previewingInstructionId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingSkill by rememberSaveable { mutableStateOf(false) }
    var editingSkillId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSkillImport by rememberSaveable { mutableStateOf(false) }
    var skillImportBusy by remember { mutableStateOf(false) }
    var skillImportError by remember { mutableStateOf<String?>(null) }
    var exportingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    var library by remember(instructionLibrary) { mutableStateOf(instructionLibrary) }
    fun updateLibrary(next: InstructionLibrary) {
        library = next
        onInstructionLibraryChange(next)
    }
    fun saveInstructionDraft(draft: SavedInstruction): SavedInstruction? {
        if (draft.name.isBlank() || draft.content.isBlank()) return null
        val instruction = if (draft.id.isBlank()) {
            draft.copy(id = UUID.randomUUID().toString())
        } else {
            draft
        }
        val exists = library.instructions.any { it.id == instruction.id }
        updateLibrary(
            library.copy(
                instructions = if (exists) library.instructions.map { if (it.id == instruction.id) instruction else it }
                else library.instructions + instruction,
                selectedIds = library.selectedIds
            )
        )
        return instruction
    }

    var skills by remember(agentSkills) { mutableStateOf(agentSkills) }
    fun saveSkillDraft(draft: AgentSkill): AgentSkill? {
        val name = draft.name.trim()
        if (!isValidAgentSkillName(name) || draft.description.isBlank() || draft.content.isBlank()) return null
        // The folder name must always follow the frontmatter's name. When renamed,
        // replace the old entry by its former id instead of duplicating a skill.
        if (skills.any { it.name == name && it.id != draft.id }) return null
        val skill = draft.copy(id = name, name = name,
            description = draft.description.trim(), content = draft.content.trim())
        val exists = skills.any { it.id == draft.id }
        val next = if (exists) skills.map { if (it.id == draft.id) skill else it } else skills + skill
        skills = next
        onAgentSkillsChange(next)
        return skill
    }

    fun acceptImportedSkill(document: String) {
        val imported = parseAgentSkillDocument(document, preserveEmptyExtras = true)
        when {
            imported == null -> skillImportError = localizedResources.getString(R.string.agent_skill_import_invalid)
            skills.any { it.name == imported.name } ->
                skillImportError = localizedResources.getString(R.string.agent_skill_import_duplicate)
            else -> {
                val next = skills + imported
                skills = next
                onAgentSkillsChange(next)
                showSkillImport = false
                skillImportError = null
            }
        }
    }
    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            skillImportBusy = true
            coroutineScope.launch {
                runCatching { readAgentSkillDocument(context, uri) }
                    .onSuccess(::acceptImportedSkill)
                    .onFailure { skillImportError = localizedResources.getString(R.string.agent_skill_import_failed,
                        it.message.orEmpty()) }
                skillImportBusy = false
            }
        }
    }
    val exportFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val skill = exportingSkill
        exportingSkill = null
        if (uri != null && skill != null) {
            coroutineScope.launch {
                runCatching { writeAgentSkillDocument(context, uri, agentSkillDocument(skill)) }
                    .onFailure { Toast.makeText(context, R.string.agent_skill_export_failed, Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun leaveSkillEditor(draft: AgentSkill? = null) {
        draft?.let(::saveSkillDraft)
        creatingSkill = false
        editingSkillId = null
    }

    fun leaveInstructionEditor(draft: SavedInstruction? = null) {
        draft?.let(::saveInstructionDraft)
        creatingInstruction = false
        editingInstructionId = null
        previewingInstructionId = null
        coroutineScope.launch { pager.animateScrollToPage(1) }
    }

    fun openInstructionPreview(draft: SavedInstruction) {
        val saved = saveInstructionDraft(draft) ?: return
        creatingInstruction = false
        editingInstructionId = null
        previewingInstructionId = saved.id
    }

    val editorPage: ChatOptionsEditor? = when {
        creatingInstruction || editingInstructionId != null -> ChatOptionsEditor.InstructionEditor(editingInstructionId)
        previewingInstructionId != null -> ChatOptionsEditor.InstructionPreview(previewingInstructionId!!)
        creatingSkill || editingSkillId != null -> ChatOptionsEditor.SkillEditor(editingSkillId)
        else -> null
    }

    ScreenDestination(
        page = editorPage,
        onClosed = {
            when (editorPage) {
                is ChatOptionsEditor.InstructionEditor -> leaveInstructionEditor()
                is ChatOptionsEditor.SkillEditor -> leaveSkillEditor()
                else -> previewingInstructionId = null
            }
        }
    ) { editorPage ->
        when (editorPage) {
            is ChatOptionsEditor.InstructionEditor -> {
                InstructionEditor(
                    instruction = editorPage.instructionId?.let { id -> library.instructions.firstOrNull { it.id == id } },
                    onPreview = ::openInstructionPreview,
                    onBack = ::leaveInstructionEditor
                )
            }
            is ChatOptionsEditor.InstructionPreview -> {
                val instruction = library.instructions.firstOrNull { it.id == editorPage.instructionId }
                if (instruction == null) {
                    LaunchedEffect(editorPage.instructionId) { previewingInstructionId = null }
                } else {
                    InstructionPreview(
                        instruction = instruction,
                        onEdit = {
                            previewingInstructionId = null
                            editingInstructionId = instruction.id
                        },
                        onBack = { previewingInstructionId = null }
                    )
                }
            }
            is ChatOptionsEditor.SkillEditor -> {
                SkillEditor(
                    skill = editorPage.skillId?.let { id -> skills.firstOrNull { it.id == id } },
                    onBack = ::leaveSkillEditor
                )
            }
            null -> {
            BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
            ListScreenTemplate(
                title = stringResource(R.string.chat_configuration),
                onBack = onBack,
                isGrid = if (pager.currentPage in 1..2) isGrid else null,
                onGridChange = { isGrid = it },
                horizontalPadding = 0.dp,
                topPadding = 0.dp,
                bottomPadding = 0.dp,
                primaryActions = {
                    if (pager.currentPage in 1..2) {
                        IconButton(onClick = {
                            if (pager.currentPage == 1) creatingInstruction = true else creatingSkill = true
                        }) {
                            SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
                        }
                    }
                    if (pager.currentPage == 2) {
                        IconButton(onClick = {
                            skillImportError = null
                            showSkillImport = true
                        }) {
                            SharedIcon(Icons.Outlined.UploadFile,
                                stringResource(R.string.agent_skill_import))
                        }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(
                        start = padding.calculateStartPadding(LocalLayoutDirection.current),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(LocalLayoutDirection.current)
                    )
                ) {
                    AppSwipeTabs(
                        tabs = listOf(
                            stringResource(R.string.chat_options_tab_options),
                            stringResource(R.string.common_instructions),
                            stringResource(R.string.chat_options_tab_skills)
                        ),
                        pagerState = pager,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) { page ->
                        when (page) {
                            0 -> LazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("chat-options-list"),
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + padding.calculateBottomPadding()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                item {
                                    Text(
                                        stringResource(R.string.tool_settings_conversation_helpers_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                items(listOf("title_generation", "chat_summary")) { name ->
                                    val config = settings.system[name] ?: SystemToolConfig()
                                    val provider = providers.find { it.id == config.providerId }
                                    val model = provider?.config?.modelConfigs?.get(config.modelId)
                                    val title = stringResource(
                                        if (name == "title_generation") R.string.tool_title_generation_title
                                        else R.string.tool_chat_summary_title
                                    )
                                    val description = stringResource(
                                        if (name == "title_generation") R.string.tool_title_generation_description
                                        else R.string.tool_chat_summary_description
                                    )

                                    SettingsCard {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            EntityIcon(
                                                image = null,
                                                modifier = Modifier.size(42.dp),
                                                fallback = if (name == "title_generation") Icons.Outlined.DocumentSparkle
                                                    else Icons.Outlined.Summarize
                                            )
                                            Spacer(Modifier.width(14.dp))
                                            Column(
                                                Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                                Text(
                                                    description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(onClick = { helperSettings = name }) {
                                                SharedIcon(
                                                    Icons.Outlined.Settings,
                                                    stringResource(R.string.tool_settings_accessibility, title)
                                                )
                                            }
                                        }
                                        DefaultModelSelector(
                                            provider = provider,
                                            modelId = config.modelId,
                                            model = model,
                                            onClick = { choosing = name }
                                        )
                                    }
                                }
                                item {
                                    WebSearchProviderSettings(
                                        servers = mcpServers,
                                        settings = settings,
                                        onProviderChange = onSetWebSearchProvider,
                                        onEnabledChange = onSetWebSearchEnabled
                                    )
                                }
                                item {
                                    TtsProviderSettings(
                                        speechServices = speechServices,
                                        providers = providers,
                                        selectedSpeechServiceId = selectedSpeechServiceId,
                                        onProviderChange = onSetTtsProvider
                                    )
                                }
                            }
                            1 -> InstructionsTab(
                                bottomPadding = padding.calculateBottomPadding(),
                                library = library,
                                isGrid = isGrid,
                                onEditInstruction = { editingInstructionId = it.id },
                                onPreviewInstruction = { previewingInstructionId = it.id },
                                onReorderInstructions = { ids ->
                                    val byId = library.instructions.associateBy { it.id }
                                    updateLibrary(library.copy(instructions = ids.mapNotNull(byId::get)))
                                },
                                onDeleteInstruction = { instruction ->
                                    updateLibrary(
                                        library.copy(
                                            instructions = library.instructions.filterNot { it.id == instruction.id },
                                            selectedIds = library.selectedIds - instruction.id
                                        )
                                     )
                                 }
                             )
                            2 -> SkillsTab(
                                bottomPadding = padding.calculateBottomPadding(),
                                skills = skills,
                                isGrid = isGrid,
                                onEditSkill = { editingSkillId = it.id },
                                onReorderSkills = { ids ->
                                    val byId = skills.associateBy { it.id }
                                    val reordered = ids.mapNotNull(byId::get)
                                    skills = reordered
                                    onAgentSkillsChange(reordered)
                                },
                                onDeleteSkill = { skill ->
                                    val next = skills.filterNot { it.id == skill.id }
                                    skills = next
                                    onAgentSkillsChange(next)
                                },
                                onExportSkill = { skill ->
                                    exportingSkill = skill
                                    exportFileLauncher.launch("${skill.name}-SKILL.md")
                                }
                            )
                        }
                    }
                }
            }
            }
        }
    }

    if (showSkillImport) {
        SkillImportDialog(
            busy = skillImportBusy,
            error = skillImportError,
            onChooseFile = {
                skillImportError = null
                importFileLauncher.launch(arrayOf("text/markdown", "text/plain", "text/x-markdown", "*/*"))
            },
            onImportGithub = { url ->
                if (parseGitHubSkillUrl(url) == null) {
                    skillImportError = localizedResources.getString(R.string.agent_skill_github_invalid)
                } else {
                    skillImportBusy = true
                    skillImportError = null
                    coroutineScope.launch {
                        runCatching { downloadGitHubSkill(url) }
                            .onSuccess(::acceptImportedSkill)
                            .onFailure {
                                skillImportError = localizedResources.getString(R.string.agent_skill_import_failed,
                                    it.message.orEmpty())
                            }
                        skillImportBusy = false
                    }
                }
            },
            onDismiss = { showSkillImport = false; skillImportError = null }
        )
    }

    helperSettings?.let { name ->
        HelperSettingsSheet(
            name = name,
            config = settings.system[name] ?: SystemToolConfig(),
            appPreferences = appPreferences,
            onSave = { config ->
                onSave(name, config)
                helperSettings = null
            },
            onAutoSummaryEnabledChange = onAutoSummaryEnabledChange,
            onSummarySuggestionEnabledChange = onSummarySuggestionEnabledChange,
            onAutoSummaryPercentChange = onAutoSummaryPercentChange,
            onDismiss = { helperSettings = null }
        )
    }

    choosing?.let { name ->
        val current = settings.system[name] ?: SystemToolConfig()
        ModelPickerSheet(
            providers = providers,
            providerGroups = providerGroups,
            collapsedGroupIdsState = collapsedGroupIds,
            collapsedProviderIdsState = collapsedProviderIds,
            onCollapsedGroupIdsChange = onCollapsedGroupIdsChange,
            onCollapsedProviderIdsChange = onCollapsedProviderIdsChange,
            selectedProviderId = current.providerId,
            selectedModelId = current.modelId,
            conversationReasoningEffort = null,
            onSetReasoningEffort = {},
            showReasoningEffort = false,
            modelFilter = { provider, id, config ->
                config.modelType == ModelType.TEXT_GENERATION &&
                    provider.config.modelConfigs.containsKey(id) &&
                    provider.config.modelIds?.contains(id) != false
            },
            onSelectModel = { providerId, modelId ->
                onSave(name, current.copy(providerId = providerId, modelId = modelId))
            },
            onEditProvider = onEditProvider,
            onEditModel = onEditModel,
            onDismiss = { choosing = null }
        )
    }
}
