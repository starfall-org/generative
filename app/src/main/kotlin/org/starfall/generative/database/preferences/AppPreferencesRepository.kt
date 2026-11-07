package org.starfall.generative.database.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.AgentSkills
import org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.model.normalizeAgentSkillName
import org.starfall.generative.integrations.files.AgentSkillsStore
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.state.ImmediateState
import org.starfall.generative.shared.ui.theme.CustomPalette

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_preferences",
    produceMigrations = { context -> listOf(
        SharedPreferencesMigration(context, "notification_permission"),
        SharedPreferencesMigration(context, "app_locale")
    ) }
)

/** Legacy locale keys are copied only when canonical DataStore values are absent. */
internal fun migrateLegacyLocaleKeys(stored: MutablePreferences) {
    val legacySystem = booleanPreferencesKey("use_system")
    val legacyTag = stringPreferencesKey("tag")
    if (!stored.contains(legacySystem) && !stored.contains(legacyTag)) return
    val currentSystem = booleanPreferencesKey("use_system_language")
    val currentTag = stringPreferencesKey("selected_language")
    if (!stored.contains(currentSystem)) {
        stored[legacySystem]?.let { stored[currentSystem] = it }
    }
    if (!stored.contains(currentTag)) {
        stored[legacyTag]?.let { stored[currentTag] = it }
    }
    stored.remove(legacySystem)
    stored.remove(legacyTag)
}

class AppPreferencesRepository(private val context: Context) {

    private val agentSkillsStore = AgentSkillsStore(context)
    // Bundled SKILL.md is the single source of truth; never write or remove it in user storage.
    private val bundledCreateSkill = agentSkillsStore.readBundledCreateSkill(context)

    private fun skillsWithBundledDefault(userSkills: List<AgentSkill>): AgentSkills =
        AgentSkills(listOf(bundledCreateSkill) + userSkills.filterNot {
            it.id == DEFAULT_AGENT_SKILL_ID || it.name == DEFAULT_AGENT_SKILL_ID
        })

    private val schemaMigrationMutex = Mutex()
    @Volatile private var legacySchemaMigrated = false

    private val storedPreferences: Flow<AppPreferences> = context.dataStore.data
        .onStart {
            migrateLegacyLocalePreferences()
            migrateLegacyPreferenceSchema()
            migrateLegacyAgentSkills()
        }
        .map { preferences -> decodePreferences(preferences, agentSkillsFromFiles()) }

    /** Convert the one-time SharedPreferencesMigration keys into canonical DataStore keys. */
    private suspend fun migrateLegacyLocalePreferences() {
        context.dataStore.edit { migrateLegacyLocaleKeys(it) }
    }

    /** Full-fidelity export retains saved appearance even while rendering is disabled. */
    suspend fun backupSnapshot(): AppPreferences =
        decodePreferences(context.dataStore.data.first(), agentSkillsFromFiles(), includeCustomization = true)

    /** Startup cleanup must retain the user's stored logo even if the UI hides it. */
    suspend fun storedFeaturesLogoImage(): String? {
        val stored = context.dataStore.data.first()
        return stored[PreferenceKeys.FEATURES_LOGO_IMAGE]
            ?: stored[PreferenceKeys.USER_PROFILE]?.let { raw ->
                runCatching { Json.decodeFromString<UserProfile>(raw).photo }.getOrNull()
            }
    }

    /** Collapses the previous flat DataStore keys into the current grouped schema once. */
    private suspend fun migrateLegacyPreferenceSchema() = schemaMigrationMutex.withLock {
        if (legacySchemaMigrated) return@withLock
        context.dataStore.edit { preferences ->
            val hasLegacySkillList = preferences[PreferenceKeys.AGENT_SKILLS]?.let { raw ->
                runCatching { Json.decodeFromString<AgentSkills>(raw) }.isFailure &&
                    runCatching { Json.decodeFromString<List<AgentSkill>>(raw) }.isSuccess
            } ?: false
            val hasLegacyData = preferences.contains(LegacyPreferenceKeys.SIDEBAR) ||
                preferences.contains(LegacyPreferenceKeys.SELECTED_PROFILE_ID) ||
                preferences.contains(LegacyPreferenceKeys.SELECTED_PROVIDER_ID) ||
                preferences.contains(LegacyPreferenceKeys.SELECTED_MODEL_ID) ||
                preferences.contains(LegacyPreferenceKeys.DEFAULT_SYSTEM_PROMPT) ||
                preferences.contains(LegacyPreferenceKeys.LAST_ACTIVE_CONVERSATION_ID) ||
                preferences.contains(LegacyPreferenceKeys.MCP_PRESETS_INITIALIZED) ||
                preferences.contains(LegacyPreferenceKeys.CONTENT_API_PRESET_INITIALIZED) ||
                hasLegacySkillList
            if (!hasLegacyData) return@edit

            val relations = decodeChatRelations(preferences)
            preferences[PreferenceKeys.CHAT_RELATIONS] = Json.encodeToString(relations)

            val selectedModel = decodeSelectedModel(preferences)
            preferences[PreferenceKeys.SELECTED_MODEL] = Json.encodeToString(selectedModel)

            val library = decodeInstructionLibrary(preferences)
            preferences[PreferenceKeys.INSTRUCTION_LIBRARY] = Json.encodeToString(library)

            val skills = decodeAgentSkills(preferences[PreferenceKeys.AGENT_SKILLS])
            preferences[PreferenceKeys.AGENT_SKILLS] = Json.encodeToString(skills)

            removeLegacyChatRelationKeys(preferences)
            preferences.remove(LegacyPreferenceKeys.SELECTED_PROVIDER_ID)
            preferences.remove(LegacyPreferenceKeys.SELECTED_MODEL_ID)
            preferences.remove(LegacyPreferenceKeys.DEFAULT_SYSTEM_PROMPT)
        }
        legacySchemaMigrated = true
    }

    private fun agentSkillsFromFiles(): AgentSkills {
        val stored = agentSkillsStore.readBlocking()
            .filterNot { it.id == DEFAULT_AGENT_SKILL_ID }
        return skillsWithBundledDefault(stored)
    }

    /** Moves the former DataStore skill list to standard agent-skills directories once. */
    private suspend fun migrateLegacyAgentSkills() {
        val raw = context.dataStore.data.first()[PreferenceKeys.AGENT_SKILLS] ?: return
        val legacy = decodeAgentSkills(raw)
        val migrated = legacy.skills
            .filterNot { it.id == DEFAULT_AGENT_SKILL_ID ||
                normalizeAgentSkillName(it.name) == DEFAULT_AGENT_SKILL_ID }
            .map { skill ->
                val name = normalizeAgentSkillName(skill.name)
                skill.copy(
                    id = name,
                    name = name,
                    description = skill.description.ifBlank {
                        "Reusable skill for ${skill.name.ifBlank { name }}."
                    }.take(AgentSkillsStore.MAX_DESCRIPTION_LENGTH)
                )
            }
        // File-backed skills take precedence if an earlier version stored the same name
        // in both places. Never discard folders migrated from the old files directory.
        val alreadySaved = agentSkillsStore.readBlocking()
        val savedNames = alreadySaved.mapTo(mutableSetOf()) { it.name }
        agentSkillsStore.replaceAll(alreadySaved + migrated.filter { it.name !in savedNames })
        context.dataStore.edit { it.remove(PreferenceKeys.AGENT_SKILLS) }
    }

    private fun decodeAgentSkills(raw: String?): AgentSkills = raw?.let { value ->
        runCatching { Json.decodeFromString<AgentSkills>(value) }.getOrNull()
            ?: runCatching { AgentSkills(Json.decodeFromString<List<AgentSkill>>(value)) }.getOrNull()
    } ?: AgentSkills()

    private fun removeLegacyChatRelationKeys(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.remove(LegacyPreferenceKeys.SIDEBAR)
        preferences.remove(LegacyPreferenceKeys.LAST_ACTIVE_CONVERSATION_ID)
        preferences.remove(LegacyPreferenceKeys.MCP_PRESETS_INITIALIZED)
        preferences.remove(LegacyPreferenceKeys.CONTENT_API_PRESET_INITIALIZED)
        preferences.remove(LegacyPreferenceKeys.SELECTED_PROFILE_ID)
    }

    private suspend fun updateChatRelations(transform: (ChatRelations) -> ChatRelations) {
        state.mutate({ it.copy(chatRelations = transform(it.chatRelations)) }) {
            context.dataStore.edit { preferences ->
                val updated = transform(decodeChatRelations(preferences))
                preferences[PreferenceKeys.CHAT_RELATIONS] = Json.encodeToString(updated)
                removeLegacyChatRelationKeys(preferences)
            }
        }
    }

    private val state = ImmediateState(storedPreferences)
    val appPreferencesFlow: Flow<AppPreferences> = state.flow

    suspend fun updateSidebar(transform: (SidebarOrganization) -> SidebarOrganization) {
        updateChatRelations { relations ->
            val sidebar = transform(relations.sidebar)
            val validFolders = sidebar.folders.mapTo(mutableSetOf()) { it.id }
            relations.copy(
                sidebar = sidebar,
                folderInstructionSelections = relations.folderInstructionSelections.filterKeys {
                    it == UNFILED_INSTRUCTION_SCOPE || it in validFolders
                }
            )
        }
    }

    /** Keep optimistic RAM updates and ordered DataStore writes paired for scalar settings. */
    private suspend fun <T> updatePreference(
        key: Preferences.Key<T>,
        value: T,
        transform: (AppPreferences) -> AppPreferences
    ) {
        state.mutate(transform) {
            context.dataStore.edit { it[key] = value }
        }
    }

    suspend fun setSelectedModel(providerId: String, modelId: String) {
        val selected = SelectedModel(providerId = providerId, modelId = modelId)
        state.mutate({ it.copy(selectedModel = selected) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SELECTED_MODEL] = Json.encodeToString(selected)
                preferences.remove(LegacyPreferenceKeys.SELECTED_PROVIDER_ID)
                preferences.remove(LegacyPreferenceKeys.SELECTED_MODEL_ID)
                preferences.remove(LegacyPreferenceKeys.SELECTED_PROFILE_ID)
            }
        }
    }

    suspend fun setSelectedSpeechServiceId(serviceId: String?) {
        state.mutate({ it.copy(selectedSpeechServiceId = serviceId) }) {
            context.dataStore.edit { preferences ->
                if (serviceId.isNullOrBlank()) preferences.remove(PreferenceKeys.SELECTED_SPEECH_SERVICE_ID)
                else preferences[PreferenceKeys.SELECTED_SPEECH_SERVICE_ID] = serviceId
            }
        }
    }

    suspend fun setThemeMode(mode: String) {
        state.mutate({ it.copy(themeMode = if (mode == "AMOLED") "DARK" else mode, useAmoled = it.useAmoled || mode == "AMOLED") }) {
            context.dataStore.edit { preferences ->
                if (preferences[PreferenceKeys.THEME_MODE] == "AMOLED" &&
                    preferences[PreferenceKeys.USE_AMOLED] == null
                ) {
                    preferences[PreferenceKeys.USE_AMOLED] = true
                }
                preferences[PreferenceKeys.THEME_MODE] = if (mode == "AMOLED") "DARK" else mode
                if (mode == "AMOLED") preferences[PreferenceKeys.USE_AMOLED] = true
            }
        }
    }

    suspend fun setUseAmoled(useAmoled: Boolean) {
        updatePreference(PreferenceKeys.USE_AMOLED, useAmoled) { it.copy(useAmoled = useAmoled) }
    }
    suspend fun setItemTransparency(value: Float) {
        val adjusted = value.coerceIn(0f, 1f)
        updatePreference(PreferenceKeys.ITEM_TRANSPARENCY, adjusted) { it.copy(itemTransparency = adjusted) }
    }

    suspend fun setInputAreaTransparency(value: Float) {
        val adjusted = value.coerceIn(0f, 1f)
        updatePreference(PreferenceKeys.INPUT_AREA_TRANSPARENCY, adjusted) { it.copy(inputAreaTransparency = adjusted) }
    }

    suspend fun setInputAreaBlur(value: Float) {
        val adjusted = value.coerceIn(0f, 32f)
        updatePreference(PreferenceKeys.INPUT_AREA_BLUR, adjusted) { it.copy(inputAreaBlur = adjusted) }
    }

    suspend fun setCustomizationEnabled(enabled: Boolean) {
        state.mutate({ current ->
            if (enabled) current.copy(customizationEnabled = true)
            else AppPreferences().let { defaults ->
                current.copy(
                    customizationEnabled = false,
                    useDynamicColor = defaults.useDynamicColor,
                    colorSchemeName = defaults.colorSchemeName,
                    customPalette = defaults.customPalette,
                    itemTransparency = defaults.itemTransparency,
                    inputAreaTransparency = defaults.inputAreaTransparency,
                    inputAreaBlur = defaults.inputAreaBlur,
                    featuresLogoImage = current.userProfile.photo,
                    userProfile = defaults.userProfile.copy(photo = current.userProfile.photo)
                )
            }
        }) {
            context.dataStore.edit { it[PreferenceKeys.CUSTOMIZATION_ENABLED] = enabled }
        }
    }

    suspend fun setUseDynamicColor(useDynamic: Boolean) {
        updatePreference(PreferenceKeys.USE_DYNAMIC_COLOR, useDynamic) { it.copy(useDynamicColor = useDynamic) }
    }

    suspend fun updateUserProfile(transform: (UserProfile) -> UserProfile) {
        val current = state.value?.userProfile ?: AppPreferences().userProfile
        val updated = transform(current)
        state.mutate({ it.copy(userProfile = updated, featuresLogoImage = updated.photo) }) {
            context.dataStore.edit { preferences ->
                val persisted = preferences[PreferenceKeys.USER_PROFILE]?.let { raw ->
                    runCatching { Json.decodeFromString<UserProfile>(raw) }.getOrNull()
                } ?: current
                val merged = transform(persisted)
                preferences[PreferenceKeys.USER_PROFILE] = Json.encodeToString(merged)
                if (merged.photo.isNullOrBlank()) preferences.remove(PreferenceKeys.FEATURES_LOGO_IMAGE)
                else preferences[PreferenceKeys.FEATURES_LOGO_IMAGE] = merged.photo
            }
        }
    }

    suspend fun setFeaturesLogoImage(image: String?) = updateUserProfile { it.copy(photo = image) }

    /** Persist the entire palette and activate it atomically; cancelling the editor writes nothing. */
    suspend fun setCustomPalette(palette: CustomPalette) {
        state.mutate({ it.copy(customPalette = palette, colorSchemeName = "CUSTOM", useDynamicColor = false) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.CUSTOM_PALETTE] = Json.encodeToString(palette)
                preferences[PreferenceKeys.COLOR_SCHEME_NAME] = "CUSTOM"
                preferences[PreferenceKeys.USE_DYNAMIC_COLOR] = false
            }
        }
    }

    suspend fun setColorSchemeName(scheme: String) {
        updatePreference(PreferenceKeys.COLOR_SCHEME_NAME, scheme) { it.copy(colorSchemeName = scheme) }
    }

    suspend fun setContinueLastConversation(enable: Boolean) {
        updatePreference(PreferenceKeys.CONTINUE_LAST_CONVERSATION, enable) { it.copy(continueLastConversation = enable) }
    }

    suspend fun setLastActiveConversationId(conversationId: String?) {
        // Store an empty string for an explicit clear, distinct from never having recorded a choice.
        updateChatRelations { it.copy(lastActiveConversationId = conversationId.orEmpty()) }
    }

    suspend fun setPersistSelectedModel(enable: Boolean) {
        updatePreference(PreferenceKeys.PERSIST_SELECTED_MODEL, enable) { it.copy(persistSelectedModel = enable) }
    }

    suspend fun setInstructionSelectionMode(mode: InstructionSelectionMode) {
        updatePreference(PreferenceKeys.INSTRUCTION_SELECTION_MODE, mode.value) { it.copy(instructionSelectionMode = mode) }
    }

    suspend fun setFolderInstructionSelection(folderKey: String, selectedIds: Set<String>) {
        updateChatRelations { relations ->
            relations.copy(folderInstructionSelections = relations.folderInstructionSelections + (folderKey to selectedIds))
        }
    }

    suspend fun setInstructionLibrary(library: InstructionLibrary) {
        val instructions = library.instructions.distinctBy { it.id }
        val cleaned = library.copy(
            instructions = instructions,
            selectedIds = library.selectedIds.intersect(instructions.map { it.id }.toSet())
        )
        state.mutate({ it.copy(instructionLibrary = cleaned) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.INSTRUCTION_LIBRARY] = Json.encodeToString(cleaned)
                preferences.remove(LegacyPreferenceKeys.DEFAULT_SYSTEM_PROMPT)
            }
        }
    }

    suspend fun setAgentSkills(skills: List<AgentSkill>) {
        val cleaned = normalizeStoredAgentSkills(skills)
        val group = skillsWithBundledDefault(cleaned)
        state.mutate({ it.copy(agentSkills = group) }) {
            agentSkillsStore.replaceAll(cleaned)
            context.dataStore.edit { it.remove(PreferenceKeys.AGENT_SKILLS) }
        }
    }

    suspend fun setTtsReadCodeBlocks(enable: Boolean) {
        updatePreference(PreferenceKeys.TTS_READ_CODE_BLOCKS, enable) { it.copy(ttsReadCodeBlocks = enable) }
    }

    suspend fun setAutoScroll(enable: Boolean) {
        updatePreference(PreferenceKeys.AUTO_SCROLL, enable) { it.copy(autoScroll = enable) }
    }

    suspend fun setEnableVibration(enable: Boolean) {
        updatePreference(PreferenceKeys.ENABLE_VIBRATION, enable) { it.copy(enableVibration = enable) }
    }

    suspend fun setHideStatusBar(hide: Boolean) {
        updatePreference(PreferenceKeys.HIDE_STATUS_BAR, hide) { it.copy(hideStatusBar = hide) }
    }

    suspend fun setHideNavigationBar(hide: Boolean) {
        updatePreference(PreferenceKeys.HIDE_NAVIGATION_BAR, hide) { it.copy(hideNavigationBar = hide) }
    }

    suspend fun setDebugMode(debug: Boolean) {
        updatePreference(PreferenceKeys.DEBUG_MODE, debug) { it.copy(debugMode = debug) }
    }

    suspend fun setDebugLogLevel(level: DebugLogLevel) {
        updatePreference(PreferenceKeys.DEBUG_LOG_LEVEL, level.name) { it.copy(debugLogLevel = level) }
    }

    suspend fun setDebugLogCategories(categories: Set<DebugLogCategory>) {
        updatePreference(PreferenceKeys.DEBUG_LOG_CATEGORIES, categories.map { it.value }.toSet()) { it.copy(debugLogCategories = categories) }
    }

    suspend fun setSelectedLanguage(lang: String) {
        updatePreference(PreferenceKeys.SELECTED_LANGUAGE, lang) { it.copy(selectedLanguage = lang) }
    }

    /** Update locale preferences together; Compose applies the change without recreating Activity. */
    suspend fun setAppLanguage(useSystem: Boolean, tag: String) {
        state.mutate({ it.copy(useSystemLanguage = useSystem, selectedLanguage = tag) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.USE_SYSTEM_LANGUAGE] = useSystem
                preferences[PreferenceKeys.SELECTED_LANGUAGE] = tag
            }
        }
    }

    suspend fun setShowProfilesAsGrid(isGrid: Boolean) {
        updatePreference(PreferenceKeys.SHOW_PROFILES_AS_GRID, isGrid) { it.copy(showProfilesAsGrid = isGrid) }
    }

    suspend fun setShowProvidersAsGrid(isGrid: Boolean) {
        updatePreference(PreferenceKeys.SHOW_PROVIDERS_AS_GRID, isGrid) { it.copy(showProvidersAsGrid = isGrid) }
    }

    suspend fun setShowMcpAsGrid(isGrid: Boolean) {
        updatePreference(PreferenceKeys.SHOW_MCP_AS_GRID, isGrid) { it.copy(showMcpAsGrid = isGrid) }
    }

    suspend fun setShowSpeechAsGrid(isGrid: Boolean) {
        updatePreference(PreferenceKeys.SHOW_SPEECH_AS_GRID, isGrid) { it.copy(showSpeechAsGrid = isGrid) }
    }

    suspend fun setProvidersCollapsedSections(ids: Set<String>) {
        updatePreference(PreferenceKeys.PROVIDERS_COLLAPSED_SECTIONS, ids) { it.copy(providersCollapsedSections = ids) }
    }

    suspend fun setModelPickerCollapsedGroups(ids: Set<String>) {
        updatePreference(PreferenceKeys.MODEL_PICKER_COLLAPSED_GROUPS, ids) { it.copy(modelPickerCollapsedGroups = ids) }
    }

    suspend fun setModelPickerCollapsedProviders(ids: Set<String>) {
        updatePreference(PreferenceKeys.MODEL_PICKER_COLLAPSED_PROVIDERS, ids) { it.copy(modelPickerCollapsedProviders = ids) }
    }

    suspend fun setMcpPresetsInitialized(initialized: Boolean) {
        updateChatRelations { it.copy(mcpPresetsInitialized = initialized) }
    }

    suspend fun setContentApiPresetInitialized(initialized: Boolean) {
        updateChatRelations { it.copy(contentApiPresetInitialized = initialized) }
    }

    suspend fun setLatexMode(mode: String) {
        updatePreference(PreferenceKeys.LATEX_MODE, mode) { it.copy(latexMode = mode) }
    }
    suspend fun setWordWrapMode(mode: WordWrapMode) {
        updatePreference(PreferenceKeys.WORD_WRAP_MODE, mode.value) { it.copy(wordWrapMode = mode) }
    }

    suspend fun setWordWrapColumn(column: Int) {
        require(column > 0)
        updatePreference(PreferenceKeys.WORD_WRAP_COLUMN, column) { it.copy(wordWrapColumn = column) }
    }

    suspend fun setCodePreviewEnabled(enabled: Boolean) {
        updatePreference(PreferenceKeys.CODE_PREVIEW_ENABLED, enabled) { it.copy(codePreviewEnabled = enabled) }
    }

    suspend fun setCodeBlockDisplayMode(mode: CodeBlockDisplayMode) {
        updatePreference(PreferenceKeys.CODE_BLOCK_DISPLAY_MODE, mode.value) { it.copy(codeBlockDisplayMode = mode) }
    }

    suspend fun setCodeBlockMaxLines(lines: Int) {
        require(lines in 1..10_000)
        updatePreference(PreferenceKeys.CODE_BLOCK_MAX_LINES, lines) { it.copy(codeBlockMaxLines = lines) }
    }

    suspend fun setCodeBlockIncludeUndefined(enabled: Boolean) {
        updatePreference(PreferenceKeys.CODE_BLOCK_INCLUDE_UNDEFINED, enabled) { it.copy(codeBlockIncludeUndefined = enabled) }
    }

    suspend fun setMessageFontSize(size: Int) {
        require(size in 12..24)
        updatePreference(PreferenceKeys.MESSAGE_FONT_SIZE, size) { it.copy(messageFontSize = size) }
    }

    suspend fun setMessageFontSizeCustom(enabled: Boolean) {
        updatePreference(PreferenceKeys.MESSAGE_FONT_SIZE_CUSTOM, enabled) { it.copy(messageFontSizeCustom = enabled) }
    }

    suspend fun setToolRoundLimitEnabled(enabled: Boolean) {
        updatePreference(PreferenceKeys.TOOL_ROUND_LIMIT_ENABLED, enabled) { it.copy(toolRoundLimitEnabled = enabled) }
    }

    suspend fun setToolRoundLimit(limit: Int) {
        require(limit in 1..MAX_TOOL_ROUND_LIMIT)
        updatePreference(PreferenceKeys.TOOL_ROUND_LIMIT, limit) { it.copy(toolRoundLimit = limit) }
    }

    suspend fun setToolCallStrategy(strategy: ToolCallStrategy) {
        updatePreference(PreferenceKeys.TOOL_CALL_STRATEGY, strategy.value) { it.copy(toolCallStrategy = strategy) }
    }

    suspend fun setToolCallConcurrentLimit(limit: Int) {
        require(limit in 1..MAX_TOOL_CALL_CONCURRENT_LIMIT)
        updatePreference(PreferenceKeys.TOOL_CALL_CONCURRENT_LIMIT, limit) { it.copy(toolCallConcurrentLimit = limit) }
    }

    suspend fun setMcpAttachments(settings: McpAttachmentSettings) {
        val normalized = settings.normalized()
        updatePreference(PreferenceKeys.MCP_ATTACHMENTS, McpAttachmentSettings.encode(normalized)) { it.copy(mcpAttachments = normalized) }
    }

    suspend fun setAutoSummaryEnabled(enabled: Boolean) {
        updatePreference(PreferenceKeys.AUTO_SUMMARY_ENABLED, enabled) { it.copy(autoSummaryEnabled = enabled) }
    }

    suspend fun setSummarySuggestionEnabled(enabled: Boolean) {
        updatePreference(PreferenceKeys.SUMMARY_SUGGESTION_ENABLED, enabled) { it.copy(summarySuggestionEnabled = enabled) }
    }

    suspend fun setAutoSummaryPercent(percent: Int) {
        require(percent in MIN_AUTO_SUMMARY_PERCENT..MAX_AUTO_SUMMARY_PERCENT)
        updatePreference(PreferenceKeys.AUTO_SUMMARY_PERCENT, percent) { it.copy(autoSummaryPercent = percent) }
    }

    suspend fun setScreenWakelockMode(mode: ScreenWakelockMode) {
        updatePreference(PreferenceKeys.SCREEN_WAKELOCK_MODE, mode.value) { it.copy(screenWakelockMode = mode) }
    }

    suspend fun setMessageFontFamily(family: MessageFontFamily) {
        updatePreference(PreferenceKeys.MESSAGE_FONT_FAMILY, family.value) { it.copy(messageFontFamily = family) }
    }

    /** Replaces the complete preference snapshot during a full application restore. */
    suspend fun replace(preferences: AppPreferences) {
        val restoredSkills = normalizeStoredAgentSkills(preferences.agentSkills.skills)
        val restoredSkillGroup = skillsWithBundledDefault(restoredSkills)
        state.mutate({ preferences.copy(agentSkills = restoredSkillGroup) }) {
            agentSkillsStore.replaceAll(restoredSkills)
            context.dataStore.edit { stored ->
                restorePreferences(stored, preferences)
            }
        }
    }

}
