package org.starfall.generative.database.preferences

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.generative.shared.model.AgentSkills
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.ui.theme.CustomPalette

/** Disabled customization ignores appearance images but keeps the profile photo available. */
internal fun decodePreferences(
    preferences: Preferences,
    agentSkills: AgentSkills,
    includeCustomization: Boolean = preferences[PreferenceKeys.CUSTOMIZATION_ENABLED] ?: true
): AppPreferences {
    val defaults = AppPreferences()
    // Theme mode and AMOLED are general appearance settings, not Customization overrides.
    val storedThemeMode = preferences[PreferenceKeys.THEME_MODE] ?: "SYSTEM"
    val legacyAmoled = storedThemeMode == "AMOLED"
    return AppPreferences(
        chatRelations = decodeChatRelations(preferences),
        selectedModel = decodeSelectedModel(preferences),
        selectedSpeechServiceId = preferences[PreferenceKeys.SELECTED_SPEECH_SERVICE_ID],
        customizationEnabled = preferences[PreferenceKeys.CUSTOMIZATION_ENABLED] ?: true,
        themeMode = if (legacyAmoled) "DARK" else storedThemeMode,
        useAmoled = preferences[PreferenceKeys.USE_AMOLED] ?: legacyAmoled,
        useDynamicColor = if (includeCustomization) preferences[PreferenceKeys.USE_DYNAMIC_COLOR] ?: true else defaults.useDynamicColor,
        colorSchemeName = when (if (includeCustomization) preferences[PreferenceKeys.COLOR_SCHEME_NAME] else null) {
            "ORANGE", "SUNSET" -> "ORANGE"
            "VIOLET" -> "VIOLET"
            "CUSTOM" -> "CUSTOM"
            else -> "SLATE" // Old blue/green/red/monochrome choices are retired.
        },
        customPalette = (if (includeCustomization) preferences[PreferenceKeys.CUSTOM_PALETTE] else null)?.let { raw ->
            runCatching { Json.decodeFromString<CustomPalette>(raw) }.getOrNull()
        } ?: CustomPalette(),
        inputAreaTransparency = ((if (includeCustomization) preferences[PreferenceKeys.INPUT_AREA_TRANSPARENCY] else null)
            ?: defaults.inputAreaTransparency).coerceIn(0f, 1f),
        inputAreaBlur = ((if (includeCustomization) preferences[PreferenceKeys.INPUT_AREA_BLUR] else null)
            ?: defaults.inputAreaBlur).coerceIn(0f, 32f),
        itemTransparency = ((if (includeCustomization) preferences[PreferenceKeys.ITEM_TRANSPARENCY] else null)
            ?: defaults.itemTransparency).coerceIn(0f, 1f),
        instructionLibrary = decodeInstructionLibrary(preferences),
        instructionSelectionMode = InstructionSelectionMode.fromValue(preferences[PreferenceKeys.INSTRUCTION_SELECTION_MODE]),
        agentSkills = agentSkills,
        continueLastConversation = preferences[PreferenceKeys.CONTINUE_LAST_CONVERSATION] ?: false,
        persistSelectedModel = preferences[PreferenceKeys.PERSIST_SELECTED_MODEL] ?: true,
        autoScroll = preferences[PreferenceKeys.AUTO_SCROLL] ?: false,
        ttsReadCodeBlocks = preferences[PreferenceKeys.TTS_READ_CODE_BLOCKS] ?: false,
        enableVibration = preferences[PreferenceKeys.ENABLE_VIBRATION] ?: false,
        hideStatusBar = preferences[PreferenceKeys.HIDE_STATUS_BAR] ?: false,
        hideNavigationBar = preferences[PreferenceKeys.HIDE_NAVIGATION_BAR] ?: false,
        debugMode = preferences[PreferenceKeys.DEBUG_MODE] ?: false,
        debugLogCategories = DebugLogCategory.fromStored(preferences[PreferenceKeys.DEBUG_LOG_CATEGORIES]),
        debugLogLevel = DebugLogLevel.fromStored(preferences[PreferenceKeys.DEBUG_LOG_LEVEL]),
        selectedLanguage = preferences[PreferenceKeys.SELECTED_LANGUAGE] ?: "en",
        useSystemLanguage = preferences[PreferenceKeys.USE_SYSTEM_LANGUAGE] ?: true,
        featuresLogoImage = preferences[PreferenceKeys.FEATURES_LOGO_IMAGE]?.takeIf { it.isNotBlank() },
        userProfile = if (includeCustomization) preferences[PreferenceKeys.USER_PROFILE]?.let { raw ->
            runCatching { Json.decodeFromString<UserProfile>(raw) }.getOrNull()
        } ?: UserProfile(photo = preferences[PreferenceKeys.FEATURES_LOGO_IMAGE]) else defaults.userProfile.copy(
            photo = preferences[PreferenceKeys.FEATURES_LOGO_IMAGE]?.takeIf { it.isNotBlank() }
        ),
        showProfilesAsGrid = preferences[PreferenceKeys.SHOW_PROFILES_AS_GRID] ?: true,
        showProvidersAsGrid = preferences[PreferenceKeys.SHOW_PROVIDERS_AS_GRID] ?: false,
        showMcpAsGrid = preferences[PreferenceKeys.SHOW_MCP_AS_GRID] ?: false,
        showSpeechAsGrid = preferences[PreferenceKeys.SHOW_SPEECH_AS_GRID] ?: false,
        providersCollapsedSections = preferences[PreferenceKeys.PROVIDERS_COLLAPSED_SECTIONS].orEmpty(),
        modelPickerCollapsedGroups = preferences[PreferenceKeys.MODEL_PICKER_COLLAPSED_GROUPS].orEmpty(),
        modelPickerCollapsedProviders = preferences[PreferenceKeys.MODEL_PICKER_COLLAPSED_PROVIDERS].orEmpty(),
        latexMode = preferences[PreferenceKeys.LATEX_MODE]?.takeIf { it == "OFF" } ?: "AUTO", // legacy "ON" now means AUTO
        wordWrapMode = WordWrapMode.fromValue(preferences[PreferenceKeys.WORD_WRAP_MODE]),
        wordWrapColumn = preferences[PreferenceKeys.WORD_WRAP_COLUMN]?.takeIf { it > 0 } ?: DEFAULT_WORD_WRAP_COLUMN,
        codePreviewEnabled = preferences[PreferenceKeys.CODE_PREVIEW_ENABLED] ?: true,
        codeBlockDisplayMode = CodeBlockDisplayMode.fromValue(preferences[PreferenceKeys.CODE_BLOCK_DISPLAY_MODE]),
        codeBlockMaxLines = preferences[PreferenceKeys.CODE_BLOCK_MAX_LINES]?.coerceIn(1, 10_000)
            ?: DEFAULT_CODE_BLOCK_MAX_LINES,
        codeBlockIncludeUndefined = preferences[PreferenceKeys.CODE_BLOCK_INCLUDE_UNDEFINED] ?: false,
        messageFontSize = preferences[PreferenceKeys.MESSAGE_FONT_SIZE]?.coerceIn(12, 24) ?: DEFAULT_MESSAGE_FONT_SIZE,
        // Users who already picked a non-default size keep it.
        messageFontSizeCustom = preferences[PreferenceKeys.MESSAGE_FONT_SIZE_CUSTOM]
            ?: (preferences[PreferenceKeys.MESSAGE_FONT_SIZE]?.let { it != DEFAULT_MESSAGE_FONT_SIZE } ?: false),
        messageFontFamily = MessageFontFamily.fromValue(preferences[PreferenceKeys.MESSAGE_FONT_FAMILY]),
        toolRoundLimitEnabled = preferences[PreferenceKeys.TOOL_ROUND_LIMIT_ENABLED] ?: false,
        toolRoundLimit = preferences[PreferenceKeys.TOOL_ROUND_LIMIT]?.coerceIn(1, MAX_TOOL_ROUND_LIMIT) ?: DEFAULT_TOOL_ROUND_LIMIT,
        toolCallStrategy = ToolCallStrategy.fromValue(preferences[PreferenceKeys.TOOL_CALL_STRATEGY]),
        toolCallConcurrentLimit = preferences[PreferenceKeys.TOOL_CALL_CONCURRENT_LIMIT]?.coerceIn(1, MAX_TOOL_CALL_CONCURRENT_LIMIT) ?: DEFAULT_TOOL_CALL_CONCURRENT_LIMIT,
        mcpAttachments = McpAttachmentSettings.decode(preferences[PreferenceKeys.MCP_ATTACHMENTS]),
        screenWakelockMode = ScreenWakelockMode.fromValue(preferences[PreferenceKeys.SCREEN_WAKELOCK_MODE]),
        autoSummaryEnabled = preferences[PreferenceKeys.AUTO_SUMMARY_ENABLED] ?: true,
        summarySuggestionEnabled = preferences[PreferenceKeys.SUMMARY_SUGGESTION_ENABLED] ?: true,
        autoSummaryPercent = preferences[PreferenceKeys.AUTO_SUMMARY_PERCENT]
            ?.coerceIn(MIN_AUTO_SUMMARY_PERCENT, MAX_AUTO_SUMMARY_PERCENT) ?: DEFAULT_AUTO_SUMMARY_PERCENT
    )
}

internal fun decodeChatRelations(preferences: Preferences): ChatRelations {
    preferences[PreferenceKeys.CHAT_RELATIONS]?.let { raw ->
        runCatching { Json.decodeFromString<ChatRelations>(raw) }.getOrNull()?.let { return it }
    }
    return ChatRelations(
        sidebar = decodeSidebar(preferences[LegacyPreferenceKeys.SIDEBAR]),
        lastActiveConversationId = preferences[LegacyPreferenceKeys.LAST_ACTIVE_CONVERSATION_ID],
        mcpPresetsInitialized = preferences[LegacyPreferenceKeys.MCP_PRESETS_INITIALIZED] ?: false,
        contentApiPresetInitialized = preferences[LegacyPreferenceKeys.CONTENT_API_PRESET_INITIALIZED] ?: false
    )
}

internal fun decodeSelectedModel(preferences: Preferences): SelectedModel =
    preferences[PreferenceKeys.SELECTED_MODEL]?.let { raw ->
        runCatching { Json.decodeFromString<SelectedModel>(raw) }.getOrNull()
    } ?: SelectedModel(
        providerId = preferences[LegacyPreferenceKeys.SELECTED_PROVIDER_ID] ?: "",
        modelId = preferences[LegacyPreferenceKeys.SELECTED_MODEL_ID] ?: ""
    )

internal fun decodeInstructionLibrary(preferences: Preferences): InstructionLibrary =
    preferences[PreferenceKeys.INSTRUCTION_LIBRARY]?.let { raw ->
        runCatching { Json.decodeFromString<InstructionLibrary>(raw) }.getOrNull()
    } ?: InstructionLibrary.fromLegacy(preferences[LegacyPreferenceKeys.DEFAULT_SYSTEM_PROMPT] ?: "")

internal fun decodeSidebar(raw: String?): SidebarOrganization = raw?.let {
    runCatching { Json.decodeFromString<SidebarOrganization>(it) }.getOrNull()
} ?: SidebarOrganization()

/** Replace stored settings atomically, retaining the notification permission request marker. */
internal fun restorePreferences(stored: MutablePreferences, preferences: AppPreferences) {
    val notificationRequest = stored[backgroundNotificationRequestedKey]
    stored.clear()
    notificationRequest?.let { stored[backgroundNotificationRequestedKey] = it }
    stored[PreferenceKeys.CHAT_RELATIONS] = Json.encodeToString(preferences.chatRelations)
    stored[PreferenceKeys.SELECTED_MODEL] = Json.encodeToString(preferences.selectedModel)
    preferences.selectedSpeechServiceId?.let { stored[PreferenceKeys.SELECTED_SPEECH_SERVICE_ID] = it }
    stored[PreferenceKeys.CUSTOMIZATION_ENABLED] = preferences.customizationEnabled
    stored[PreferenceKeys.THEME_MODE] = preferences.themeMode
    stored[PreferenceKeys.USE_AMOLED] = preferences.useAmoled
    stored[PreferenceKeys.USE_DYNAMIC_COLOR] = preferences.useDynamicColor
    stored[PreferenceKeys.COLOR_SCHEME_NAME] = preferences.colorSchemeName
    stored[PreferenceKeys.CUSTOM_PALETTE] = Json.encodeToString(preferences.customPalette)
    stored[PreferenceKeys.INPUT_AREA_TRANSPARENCY] = preferences.inputAreaTransparency.coerceIn(0f, 1f)
    stored[PreferenceKeys.INPUT_AREA_BLUR] = preferences.inputAreaBlur.coerceIn(0f, 32f)
    stored[PreferenceKeys.ITEM_TRANSPARENCY] = preferences.itemTransparency.coerceIn(0f, 1f)
    stored[PreferenceKeys.INSTRUCTION_LIBRARY] = Json.encodeToString(preferences.instructionLibrary)
    stored[PreferenceKeys.INSTRUCTION_SELECTION_MODE] = preferences.instructionSelectionMode.value
    stored[PreferenceKeys.CONTINUE_LAST_CONVERSATION] = preferences.continueLastConversation
    stored[PreferenceKeys.PERSIST_SELECTED_MODEL] = preferences.persistSelectedModel
    stored[PreferenceKeys.AUTO_SCROLL] = preferences.autoScroll
    stored[PreferenceKeys.TTS_READ_CODE_BLOCKS] = preferences.ttsReadCodeBlocks
    stored[PreferenceKeys.ENABLE_VIBRATION] = preferences.enableVibration
    stored[PreferenceKeys.HIDE_STATUS_BAR] = preferences.hideStatusBar
    stored[PreferenceKeys.HIDE_NAVIGATION_BAR] = preferences.hideNavigationBar
    stored[PreferenceKeys.DEBUG_MODE] = preferences.debugMode
    stored[PreferenceKeys.DEBUG_LOG_CATEGORIES] = preferences.debugLogCategories.map { it.value }.toSet()
    stored[PreferenceKeys.DEBUG_LOG_LEVEL] = preferences.debugLogLevel.name
    stored[PreferenceKeys.SELECTED_LANGUAGE] = preferences.selectedLanguage
    stored[PreferenceKeys.USE_SYSTEM_LANGUAGE] = preferences.useSystemLanguage
    preferences.featuresLogoImage?.let { stored[PreferenceKeys.FEATURES_LOGO_IMAGE] = it }
    stored[PreferenceKeys.USER_PROFILE] = Json.encodeToString(preferences.userProfile)
    stored[PreferenceKeys.SHOW_PROFILES_AS_GRID] = preferences.showProfilesAsGrid
    stored[PreferenceKeys.SHOW_PROVIDERS_AS_GRID] = preferences.showProvidersAsGrid
    stored[PreferenceKeys.SHOW_MCP_AS_GRID] = preferences.showMcpAsGrid
    stored[PreferenceKeys.SHOW_SPEECH_AS_GRID] = preferences.showSpeechAsGrid
    stored[PreferenceKeys.PROVIDERS_COLLAPSED_SECTIONS] = preferences.providersCollapsedSections
    stored[PreferenceKeys.MODEL_PICKER_COLLAPSED_GROUPS] = preferences.modelPickerCollapsedGroups
    stored[PreferenceKeys.MODEL_PICKER_COLLAPSED_PROVIDERS] = preferences.modelPickerCollapsedProviders
    stored[PreferenceKeys.LATEX_MODE] = preferences.latexMode
    stored[PreferenceKeys.WORD_WRAP_MODE] = preferences.wordWrapMode.value
    stored[PreferenceKeys.WORD_WRAP_COLUMN] = preferences.wordWrapColumn
    stored[PreferenceKeys.CODE_PREVIEW_ENABLED] = preferences.codePreviewEnabled
    stored[PreferenceKeys.CODE_BLOCK_DISPLAY_MODE] = preferences.codeBlockDisplayMode.value
    stored[PreferenceKeys.CODE_BLOCK_MAX_LINES] = preferences.codeBlockMaxLines
    stored[PreferenceKeys.CODE_BLOCK_INCLUDE_UNDEFINED] = preferences.codeBlockIncludeUndefined
    stored[PreferenceKeys.MESSAGE_FONT_SIZE] = preferences.messageFontSize
    stored[PreferenceKeys.MESSAGE_FONT_SIZE_CUSTOM] = preferences.messageFontSizeCustom
    stored[PreferenceKeys.MESSAGE_FONT_FAMILY] = preferences.messageFontFamily.value
    stored[PreferenceKeys.TOOL_ROUND_LIMIT_ENABLED] = preferences.toolRoundLimitEnabled
    stored[PreferenceKeys.TOOL_ROUND_LIMIT] = preferences.toolRoundLimit
    stored[PreferenceKeys.TOOL_CALL_STRATEGY] = preferences.toolCallStrategy.value
    stored[PreferenceKeys.TOOL_CALL_CONCURRENT_LIMIT] = preferences.toolCallConcurrentLimit
    stored[PreferenceKeys.MCP_ATTACHMENTS] = McpAttachmentSettings.encode(preferences.mcpAttachments)
    stored[PreferenceKeys.SCREEN_WAKELOCK_MODE] = preferences.screenWakelockMode.value
    stored[PreferenceKeys.AUTO_SUMMARY_ENABLED] = preferences.autoSummaryEnabled
    stored[PreferenceKeys.SUMMARY_SUGGESTION_ENABLED] = preferences.summarySuggestionEnabled
    stored[PreferenceKeys.AUTO_SUMMARY_PERCENT] = preferences.autoSummaryPercent
}
