package org.starfall.generative.shared.storage.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
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
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.model.normalizeAgentSkillName
import org.starfall.generative.shared.files.AgentSkillsStore
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.storage.ImmediateState
import org.starfall.generative.shared.theme.CustomPalette

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_preferences",
    produceMigrations = { context -> listOf(
        SharedPreferencesMigration(context, "notification_permission"),
        SharedPreferencesMigration(context, "app_locale")
    ) }
)

@Serializable
enum class WordWrapMode(val value: String) {
    OFF("off"), VIEWPORT("on"), COLUMN("wordWrapColumn"), BOUNDED("bounded");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: OFF
    }
}

@Serializable
enum class MessageFontFamily(val value: String) {
    DEFAULT("default"), SANS_SERIF("sans_serif"), SERIF("serif"), MONOSPACE("monospace"), CURSIVE("cursive");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

@Serializable
enum class CodeBlockDisplayMode(val value: String) {
    SHOW_ALL("show_all"), EXTENDABLE("extendable"), SHOW_IN_DRAWER("show_in_drawer");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: EXTENDABLE
    }
}

@Serializable
enum class ToolCallStrategy(val value: String) {
    SEQUENTIAL("sequential"), PARALLEL("parallel");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: SEQUENTIAL
    }
}

@Serializable
enum class ScreenWakelockMode(val value: String) {
    NONE("none"), ALL("all"), EXCEPT_TOOL_CALLS("except_tool_calls");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: EXCEPT_TOOL_CALLS
    }
}

const val DEFAULT_WORD_WRAP_COLUMN = 80
const val DEFAULT_MESSAGE_FONT_SIZE = 16
const val DEFAULT_CODE_BLOCK_MAX_LINES = 15
const val DEFAULT_AUTO_SUMMARY_PERCENT = 99
const val MIN_AUTO_SUMMARY_PERCENT = 50
const val MAX_AUTO_SUMMARY_PERCENT = 100
const val DEFAULT_TOOL_ROUND_LIMIT = 12
const val MAX_TOOL_ROUND_LIMIT = 1000
const val DEFAULT_TOOL_CALL_CONCURRENT_LIMIT = 2
const val MAX_TOOL_CALL_CONCURRENT_LIMIT = 16

@Serializable
data class ChatRelations(
    val sidebar: SidebarOrganization = SidebarOrganization(),
    /** Null means the app has not recorded a restoration choice; an empty string explicitly clears it. */
    val lastActiveConversationId: String? = null,
    val mcpPresetsInitialized: Boolean = false,
    val contentApiPresetInitialized: Boolean = false
)

/** A selected model is identified together with the provider that owns it. */
@Serializable
data class SelectedModel(
    val providerId: String = "",
    val modelId: String = ""
)

@Serializable
data class AppPreferences(
    val chatRelations: ChatRelations = ChatRelations(),
    val selectedModel: SelectedModel = SelectedModel(),
    val selectedSpeechServiceId: String? = null,
    val customizationEnabled: Boolean = true,
    val themeMode: String = "SYSTEM", // SYSTEM, LIGHT, DARK
    val useAmoled: Boolean = false,
    val useDynamicColor: Boolean = true,
    val colorSchemeName: String = "SLATE", // ORANGE, VIOLET, SLATE, CUSTOM
    val customPalette: CustomPalette = CustomPalette(),
    val itemTransparency: Float = 0.5f,
    val inputAreaTransparency: Float = 0.6f,
    val inputAreaBlur: Float = 2f,
    val instructionLibrary: InstructionLibrary = InstructionLibrary(),
    val agentSkills: AgentSkills = AgentSkills(),
    val continueLastConversation: Boolean = false,
    val persistSelectedModel: Boolean = true,
    val autoScroll: Boolean = false,
    val ttsReadCodeBlocks: Boolean = false,
    val enableVibration: Boolean = false,
    val hideStatusBar: Boolean = false,
    val hideNavigationBar: Boolean = false,
    val debugMode: Boolean = false,
    /** Kinds of log the floating debug panel records while [debugMode] is on. */
    val debugLogCategories: Set<DebugLogCategory> = DebugLogCategory.all,
    val debugLogLevel: DebugLogLevel = DebugLogLevel.WARN,
    val selectedLanguage: String = "en",
    /** On: follow the Android system language. Off: use [selectedLanguage]. */
    val useSystemLanguage: Boolean = true,
    /** App-owned image used for the Features entry points and the Features screen header. */
    val featuresLogoImage: String? = null,
    val userProfile: UserProfile = UserProfile(),
    val showProfilesAsGrid: Boolean = true,
    val showProvidersAsGrid: Boolean = false,
    val showMcpAsGrid: Boolean = false,
    val showSpeechAsGrid: Boolean = false,
    val providersCollapsedSections: Set<String> = emptySet(),
    val modelPickerCollapsedGroups: Set<String> = emptySet(),
    val modelPickerCollapsedProviders: Set<String> = emptySet(),
    val latexMode: String = "AUTO", // OFF, AUTO
    val wordWrapMode: WordWrapMode = WordWrapMode.OFF,
    val wordWrapColumn: Int = DEFAULT_WORD_WRAP_COLUMN,
    val codePreviewEnabled: Boolean = true,
    val codeBlockDisplayMode: CodeBlockDisplayMode = CodeBlockDisplayMode.EXTENDABLE,
    val codeBlockMaxLines: Int = DEFAULT_CODE_BLOCK_MAX_LINES,
    val codeBlockIncludeUndefined: Boolean = false,
    val messageFontSize: Int = DEFAULT_MESSAGE_FONT_SIZE,
    /** Off: chat text uses the default size, which scales with the Android system font size. */
    val messageFontSizeCustom: Boolean = false,
    val messageFontFamily: MessageFontFamily = MessageFontFamily.DEFAULT,
    val toolRoundLimitEnabled: Boolean = false,
    val toolRoundLimit: Int = DEFAULT_TOOL_ROUND_LIMIT,
    val toolCallStrategy: ToolCallStrategy = ToolCallStrategy.SEQUENTIAL,
    val toolCallConcurrentLimit: Int = DEFAULT_TOOL_CALL_CONCURRENT_LIMIT,
    val mcpAttachments: McpAttachmentSettings = McpAttachmentSettings(),
    val screenWakelockMode: ScreenWakelockMode = ScreenWakelockMode.EXCEPT_TOOL_CALLS,
    /** Summarize automatically once estimated context usage reaches [autoSummaryPercent]. */
    val autoSummaryEnabled: Boolean = true,
    /** Show the "Summarize" suggestion card when the context is nearly full. */
    val summarySuggestionEnabled: Boolean = true,
    val autoSummaryPercent: Int = DEFAULT_AUTO_SUMMARY_PERCENT
) {
    /** Size in sp used for chat text. sp already follows the system font scale. */
    val effectiveMessageFontSize: Int get() = if (messageFontSizeCustom) messageFontSize else DEFAULT_MESSAGE_FONT_SIZE
    /** Null means tool rounds are unlimited. */
    val effectiveToolRoundLimit: Int? get() = toolRoundLimit.takeIf { toolRoundLimitEnabled }
    /** The instruction library as [conversation] sees it; without its own instruction selection it uses the default. */
    fun instructionLibraryFor(conversation: Conversation?): InstructionLibrary =
        instructionLibraryForSelection(conversation?.selectedInstructionIds)
    fun instructionLibraryForSelection(selectedInstructionIds: Set<String>?): InstructionLibrary =
        instructionLibrary.forChat(selectedInstructionIds)
    fun systemPromptFor(conversation: Conversation?, at: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String =
        instructionLibraryFor(conversation).systemPrompt(at)
    fun instructionRoleMessagesFor(conversation: Conversation?, at: java.time.ZonedDateTime = java.time.ZonedDateTime.now()) =
        instructionLibraryFor(conversation).roleMessages(at)
}

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

    private object PreferenceKeys {
        val CHAT_RELATIONS = stringPreferencesKey("chat_relations")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val SELECTED_SPEECH_SERVICE_ID = stringPreferencesKey("selected_speech_service_id")
        val CUSTOMIZATION_ENABLED = booleanPreferencesKey("customization_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_AMOLED = booleanPreferencesKey("use_amoled")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val COLOR_SCHEME_NAME = stringPreferencesKey("color_scheme_name")
        val CUSTOM_PALETTE = stringPreferencesKey("custom_color_palette")
        val INPUT_AREA_TRANSPARENCY = floatPreferencesKey("input_area_transparency")
        val INPUT_AREA_BLUR = floatPreferencesKey("input_area_blur")
        val ITEM_TRANSPARENCY = floatPreferencesKey("item_transparency")
        val INSTRUCTION_LIBRARY = stringPreferencesKey("prompt_library")
        val AGENT_SKILLS = stringPreferencesKey("agent_skills")
        val CONTINUE_LAST_CONVERSATION = booleanPreferencesKey("continue_last_conversation")
        val PERSIST_SELECTED_MODEL = booleanPreferencesKey("persist_chat_selection")
        val AUTO_SCROLL = booleanPreferencesKey("auto_scroll")
        val TTS_READ_CODE_BLOCKS = booleanPreferencesKey("tts_read_code_blocks")
        val ENABLE_VIBRATION = booleanPreferencesKey("enable_vibration")
        val HIDE_STATUS_BAR = booleanPreferencesKey("hide_status_bar")
        val HIDE_NAVIGATION_BAR = booleanPreferencesKey("hide_navigation_bar")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val DEBUG_LOG_CATEGORIES = stringSetPreferencesKey("debug_log_categories")
        val DEBUG_LOG_LEVEL = stringPreferencesKey("debug_log_level")
        val SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
        val USE_SYSTEM_LANGUAGE = booleanPreferencesKey("use_system_language")
        val FEATURES_LOGO_IMAGE = stringPreferencesKey("features_logo_image")
        val USER_PROFILE = stringPreferencesKey("user_profile")
        val SHOW_PROFILES_AS_GRID = booleanPreferencesKey("show_profiles_as_grid")
        val SHOW_PROVIDERS_AS_GRID = booleanPreferencesKey("show_providers_as_grid")
        val SHOW_MCP_AS_GRID = booleanPreferencesKey("show_mcp_as_grid")
        val SHOW_SPEECH_AS_GRID = booleanPreferencesKey("show_speech_as_grid")
        val PROVIDERS_COLLAPSED_SECTIONS = stringSetPreferencesKey("providers_collapsed_sections")
        val MODEL_PICKER_COLLAPSED_GROUPS = stringSetPreferencesKey("model_picker_collapsed_groups")
        val MODEL_PICKER_COLLAPSED_PROVIDERS = stringSetPreferencesKey("model_picker_collapsed_providers")
        val LATEX_MODE = stringPreferencesKey("latex_mode")
        val WORD_WRAP_MODE = stringPreferencesKey("word_wrap_mode")
        val WORD_WRAP_COLUMN = intPreferencesKey("word_wrap_column")
        val CODE_PREVIEW_ENABLED = booleanPreferencesKey("code_preview_enabled")
        val CODE_BLOCK_DISPLAY_MODE = stringPreferencesKey("code_block_display_mode")
        val CODE_BLOCK_MAX_LINES = intPreferencesKey("code_block_max_lines")
        val CODE_BLOCK_INCLUDE_UNDEFINED = booleanPreferencesKey("code_block_include_undefined")
        val MESSAGE_FONT_SIZE = intPreferencesKey("message_font_size")
        val MESSAGE_FONT_SIZE_CUSTOM = booleanPreferencesKey("message_font_size_custom")
        val MESSAGE_FONT_FAMILY = stringPreferencesKey("message_font_family")
        val TOOL_ROUND_LIMIT_ENABLED = booleanPreferencesKey("tool_round_limit_enabled")
        val TOOL_ROUND_LIMIT = intPreferencesKey("tool_round_limit")
        val TOOL_CALL_STRATEGY = stringPreferencesKey("tool_call_strategy")
        val TOOL_CALL_CONCURRENT_LIMIT = intPreferencesKey("tool_call_concurrent_limit")
        val MCP_ATTACHMENTS = stringPreferencesKey("mcp_attachments")
        val SCREEN_WAKELOCK_MODE = stringPreferencesKey("screen_wakelock_mode")
        val AUTO_SUMMARY_ENABLED = booleanPreferencesKey("auto_summary_enabled")
        val SUMMARY_SUGGESTION_ENABLED = booleanPreferencesKey("summary_suggestion_enabled")
        val AUTO_SUMMARY_PERCENT = intPreferencesKey("auto_summary_percent")
    }

    /** Keys read only to migrate installations that still use the former flat preference schema. */
    private object LegacyPreferenceKeys {
        val SIDEBAR = stringPreferencesKey("sidebar_organization")
        val SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
        val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val DEFAULT_SYSTEM_PROMPT = stringPreferencesKey("default_system_prompt")
        val LAST_ACTIVE_CONVERSATION_ID = stringPreferencesKey("last_active_conversation_id")
        val MCP_PRESETS_INITIALIZED = booleanPreferencesKey("mcp_presets_initialized")
        val CONTENT_API_PRESET_INITIALIZED = booleanPreferencesKey("content_api_preset_initialized")
    }

    private val schemaMigrationMutex = Mutex()
    @Volatile private var legacySchemaMigrated = false

    private val storedPreferences: Flow<AppPreferences> = context.dataStore.data
        .onStart {
            migrateLegacyLocalePreferences()
            migrateLegacyPreferenceSchema()
            migrateLegacyAgentSkills()
        }
        .map { preferences -> decodePreferences(preferences) }

    /** Convert the one-time SharedPreferencesMigration keys into canonical DataStore keys. */
    private suspend fun migrateLegacyLocalePreferences() {
        context.dataStore.edit { migrateLegacyLocaleKeys(it) }
    }

    /** Disabled customization ignores appearance images but keeps the profile photo available. */
    private fun decodePreferences(
        preferences: Preferences,
        includeCustomization: Boolean = preferences[PreferenceKeys.CUSTOMIZATION_ENABLED] ?: true
    ): AppPreferences {
        val defaults = AppPreferences()
        val storedThemeMode = if (includeCustomization) preferences[PreferenceKeys.THEME_MODE] ?: "SYSTEM" else defaults.themeMode
        val legacyAmoled = storedThemeMode == "AMOLED"
        return AppPreferences(
            chatRelations = decodeChatRelations(preferences),
            selectedModel = decodeSelectedModel(preferences),
            selectedSpeechServiceId = preferences[PreferenceKeys.SELECTED_SPEECH_SERVICE_ID],
            customizationEnabled = preferences[PreferenceKeys.CUSTOMIZATION_ENABLED] ?: true,
            themeMode = if (legacyAmoled) "DARK" else storedThemeMode,
            useAmoled = if (includeCustomization) preferences[PreferenceKeys.USE_AMOLED] ?: legacyAmoled else defaults.useAmoled,
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
            agentSkills = agentSkillsFromFiles(),
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

    /** Full-fidelity export retains saved appearance even while rendering is disabled. */
    suspend fun backupSnapshot(): AppPreferences =
        decodePreferences(context.dataStore.data.first(), includeCustomization = true)

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

            val relations = preferences[PreferenceKeys.CHAT_RELATIONS]?.let { raw ->
                runCatching { Json.decodeFromString<ChatRelations>(raw) }.getOrNull()
            } ?: ChatRelations(
                sidebar = decodeSidebar(preferences[LegacyPreferenceKeys.SIDEBAR]),
                lastActiveConversationId = preferences[LegacyPreferenceKeys.LAST_ACTIVE_CONVERSATION_ID],
                mcpPresetsInitialized = preferences[LegacyPreferenceKeys.MCP_PRESETS_INITIALIZED] ?: false,
                contentApiPresetInitialized = preferences[LegacyPreferenceKeys.CONTENT_API_PRESET_INITIALIZED] ?: false
            )
            preferences[PreferenceKeys.CHAT_RELATIONS] = Json.encodeToString(relations)

            val selectedModel = preferences[PreferenceKeys.SELECTED_MODEL]?.let { raw ->
                runCatching { Json.decodeFromString<SelectedModel>(raw) }.getOrNull()
            } ?: SelectedModel(
                providerId = preferences[LegacyPreferenceKeys.SELECTED_PROVIDER_ID] ?: "",
                modelId = preferences[LegacyPreferenceKeys.SELECTED_MODEL_ID] ?: ""
            )
            preferences[PreferenceKeys.SELECTED_MODEL] = Json.encodeToString(selectedModel)

            val library = preferences[PreferenceKeys.INSTRUCTION_LIBRARY]?.let { raw ->
                runCatching { Json.decodeFromString<InstructionLibrary>(raw) }.getOrNull()
            } ?: InstructionLibrary.fromLegacy(preferences[LegacyPreferenceKeys.DEFAULT_SYSTEM_PROMPT] ?: "")
            preferences[PreferenceKeys.INSTRUCTION_LIBRARY] = Json.encodeToString(library)

            val rawSkills = preferences[PreferenceKeys.AGENT_SKILLS]
            val skills = rawSkills?.let { raw ->
                runCatching { Json.decodeFromString<AgentSkills>(raw) }.getOrNull()
                    ?: runCatching { AgentSkills(Json.decodeFromString<List<AgentSkill>>(raw)) }.getOrNull()
            } ?: AgentSkills()
            preferences[PreferenceKeys.AGENT_SKILLS] = Json.encodeToString(skills)

            removeLegacyChatRelationKeys(preferences)
            preferences.remove(LegacyPreferenceKeys.SELECTED_PROVIDER_ID)
            preferences.remove(LegacyPreferenceKeys.SELECTED_MODEL_ID)
            preferences.remove(LegacyPreferenceKeys.DEFAULT_SYSTEM_PROMPT)
        }
        legacySchemaMigrated = true
    }

    private fun decodeChatRelations(preferences: Preferences): ChatRelations {
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

    private fun decodeSelectedModel(preferences: Preferences): SelectedModel =
        preferences[PreferenceKeys.SELECTED_MODEL]?.let { raw ->
            runCatching { Json.decodeFromString<SelectedModel>(raw) }.getOrNull()
        } ?: SelectedModel(
            providerId = preferences[LegacyPreferenceKeys.SELECTED_PROVIDER_ID] ?: "",
            modelId = preferences[LegacyPreferenceKeys.SELECTED_MODEL_ID] ?: ""
        )

    private fun decodeInstructionLibrary(preferences: Preferences): InstructionLibrary =
        preferences[PreferenceKeys.INSTRUCTION_LIBRARY]?.let { raw ->
            runCatching { Json.decodeFromString<InstructionLibrary>(raw) }.getOrNull()
        } ?: InstructionLibrary.fromLegacy(preferences[LegacyPreferenceKeys.DEFAULT_SYSTEM_PROMPT] ?: "")

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

    private fun decodeSidebar(raw: String?): SidebarOrganization = raw?.let {
        runCatching { Json.decodeFromString<SidebarOrganization>(it) }.getOrNull()
    } ?: SidebarOrganization()

    suspend fun updateSidebar(transform: (SidebarOrganization) -> SidebarOrganization) {
        updateChatRelations { relations -> relations.copy(sidebar = transform(relations.sidebar)) }
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
        state.mutate({ it.copy(useAmoled = useAmoled) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.USE_AMOLED] = useAmoled
            }
        }
    }
    suspend fun setItemTransparency(value: Float) {
        val adjusted = value.coerceIn(0f, 1f)
        state.mutate({ it.copy(itemTransparency = adjusted) }) {
            context.dataStore.edit { it[PreferenceKeys.ITEM_TRANSPARENCY] = adjusted }
        }
    }

    suspend fun setInputAreaTransparency(value: Float) {
        val adjusted = value.coerceIn(0f, 1f)
        state.mutate({ it.copy(inputAreaTransparency = adjusted) }) {
            context.dataStore.edit { it[PreferenceKeys.INPUT_AREA_TRANSPARENCY] = adjusted }
        }
    }

    suspend fun setInputAreaBlur(value: Float) {
        val adjusted = value.coerceIn(0f, 32f)
        state.mutate({ it.copy(inputAreaBlur = adjusted) }) {
            context.dataStore.edit { it[PreferenceKeys.INPUT_AREA_BLUR] = adjusted }
        }
    }

    suspend fun setCustomizationEnabled(enabled: Boolean) {
        state.mutate({ current ->
            if (enabled) current.copy(customizationEnabled = true)
            else AppPreferences().let { defaults ->
                current.copy(
                    customizationEnabled = false,
                    themeMode = defaults.themeMode,
                    useAmoled = defaults.useAmoled,
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
        state.mutate({ it.copy(useDynamicColor = useDynamic) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.USE_DYNAMIC_COLOR] = useDynamic
            }
        }
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
        state.mutate({ it.copy(colorSchemeName = scheme) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.COLOR_SCHEME_NAME] = scheme
            }
        }
    }

    suspend fun setContinueLastConversation(enable: Boolean) {
        state.mutate({ it.copy(continueLastConversation = enable) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.CONTINUE_LAST_CONVERSATION] = enable
            }
        }
    }

    suspend fun setLastActiveConversationId(conversationId: String?) {
        // Store an empty string for an explicit clear, distinct from never having recorded a choice.
        updateChatRelations { it.copy(lastActiveConversationId = conversationId.orEmpty()) }
    }

    suspend fun setPersistSelectedModel(enable: Boolean) {
        state.mutate({ it.copy(persistSelectedModel = enable) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.PERSIST_SELECTED_MODEL] = enable
            }
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
        val cleaned = skills
            .filterNot { it.id == DEFAULT_AGENT_SKILL_ID ||
                normalizeAgentSkillName(it.name) == DEFAULT_AGENT_SKILL_ID }
            .mapNotNull { skill ->
                val name = normalizeAgentSkillName(skill.name)
                val content = skill.content.trim()
                if (content.isBlank()) return@mapNotNull null
                skill.copy(
                    id = name,
                    name = name,
                    content = content,
                    description = skill.description.trim().ifBlank {
                        "Reusable skill for $name."
                    }.take(AgentSkillsStore.MAX_DESCRIPTION_LENGTH)
                )
            }
            .distinctBy { it.name }
        val group = skillsWithBundledDefault(cleaned)
        state.mutate({ it.copy(agentSkills = group) }) {
            agentSkillsStore.replaceAll(cleaned)
            context.dataStore.edit { it.remove(PreferenceKeys.AGENT_SKILLS) }
        }
    }

    suspend fun setTtsReadCodeBlocks(enable: Boolean) {
        state.mutate({ it.copy(ttsReadCodeBlocks = enable) }) {
            context.dataStore.edit { it[PreferenceKeys.TTS_READ_CODE_BLOCKS] = enable }
        }
    }

    suspend fun setAutoScroll(enable: Boolean) {
        state.mutate({ it.copy(autoScroll = enable) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.AUTO_SCROLL] = enable
            }
        }
    }

    suspend fun setEnableVibration(enable: Boolean) {
        state.mutate({ it.copy(enableVibration = enable) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.ENABLE_VIBRATION] = enable
            }
        }
    }

    suspend fun setHideStatusBar(hide: Boolean) {
        state.mutate({ it.copy(hideStatusBar = hide) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.HIDE_STATUS_BAR] = hide
            }
        }
    }

    suspend fun setHideNavigationBar(hide: Boolean) {
        state.mutate({ it.copy(hideNavigationBar = hide) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.HIDE_NAVIGATION_BAR] = hide
            }
        }
    }

    suspend fun setDebugMode(debug: Boolean) {
        state.mutate({ it.copy(debugMode = debug) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.DEBUG_MODE] = debug
            }
        }
    }

    suspend fun setDebugLogLevel(level: DebugLogLevel) {
        state.mutate({ it.copy(debugLogLevel = level) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.DEBUG_LOG_LEVEL] = level.name
            }
        }
    }

    suspend fun setDebugLogCategories(categories: Set<DebugLogCategory>) {
        state.mutate({ it.copy(debugLogCategories = categories) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.DEBUG_LOG_CATEGORIES] = categories.map { it.value }.toSet()
            }
        }
    }

    suspend fun setSelectedLanguage(lang: String) {
        state.mutate({ it.copy(selectedLanguage = lang) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SELECTED_LANGUAGE] = lang
            }
        }
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
        state.mutate({ it.copy(showProfilesAsGrid = isGrid) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SHOW_PROFILES_AS_GRID] = isGrid
            }
        }
    }

    suspend fun setShowProvidersAsGrid(isGrid: Boolean) {
        state.mutate({ it.copy(showProvidersAsGrid = isGrid) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SHOW_PROVIDERS_AS_GRID] = isGrid
            }
        }
    }

    suspend fun setShowMcpAsGrid(isGrid: Boolean) {
        state.mutate({ it.copy(showMcpAsGrid = isGrid) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SHOW_MCP_AS_GRID] = isGrid
            }
        }
    }

    suspend fun setShowSpeechAsGrid(isGrid: Boolean) {
        state.mutate({ it.copy(showSpeechAsGrid = isGrid) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SHOW_SPEECH_AS_GRID] = isGrid
            }
        }
    }

    suspend fun setProvidersCollapsedSections(ids: Set<String>) {
        state.mutate({ it.copy(providersCollapsedSections = ids) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.PROVIDERS_COLLAPSED_SECTIONS] = ids
            }
        }
    }

    suspend fun setModelPickerCollapsedGroups(ids: Set<String>) {
        state.mutate({ it.copy(modelPickerCollapsedGroups = ids) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.MODEL_PICKER_COLLAPSED_GROUPS] = ids
            }
        }
    }

    suspend fun setModelPickerCollapsedProviders(ids: Set<String>) {
        state.mutate({ it.copy(modelPickerCollapsedProviders = ids) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.MODEL_PICKER_COLLAPSED_PROVIDERS] = ids
            }
        }
    }

    suspend fun setMcpPresetsInitialized(initialized: Boolean) {
        updateChatRelations { it.copy(mcpPresetsInitialized = initialized) }
    }

    suspend fun setContentApiPresetInitialized(initialized: Boolean) {
        updateChatRelations { it.copy(contentApiPresetInitialized = initialized) }
    }

    suspend fun setLatexMode(mode: String) {
        state.mutate({ it.copy(latexMode = mode) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.LATEX_MODE] = mode
            }
        }
    }
    suspend fun setWordWrapMode(mode: WordWrapMode) {
        state.mutate({ it.copy(wordWrapMode = mode) }) {
            context.dataStore.edit { it[PreferenceKeys.WORD_WRAP_MODE] = mode.value }
        }
    }

    suspend fun setWordWrapColumn(column: Int) {
        require(column > 0)
        state.mutate({ it.copy(wordWrapColumn = column) }) {
            context.dataStore.edit { it[PreferenceKeys.WORD_WRAP_COLUMN] = column }
        }
    }

    suspend fun setCodePreviewEnabled(enabled: Boolean) {
        state.mutate({ it.copy(codePreviewEnabled = enabled) }) {
            context.dataStore.edit { it[PreferenceKeys.CODE_PREVIEW_ENABLED] = enabled }
        }
    }

    suspend fun setCodeBlockDisplayMode(mode: CodeBlockDisplayMode) {
        state.mutate({ it.copy(codeBlockDisplayMode = mode) }) {
            context.dataStore.edit { it[PreferenceKeys.CODE_BLOCK_DISPLAY_MODE] = mode.value }
        }
    }

    suspend fun setCodeBlockMaxLines(lines: Int) {
        require(lines in 1..10_000)
        state.mutate({ it.copy(codeBlockMaxLines = lines) }) {
            context.dataStore.edit { it[PreferenceKeys.CODE_BLOCK_MAX_LINES] = lines }
        }
    }

    suspend fun setCodeBlockIncludeUndefined(enabled: Boolean) {
        state.mutate({ it.copy(codeBlockIncludeUndefined = enabled) }) {
            context.dataStore.edit { it[PreferenceKeys.CODE_BLOCK_INCLUDE_UNDEFINED] = enabled }
        }
    }

    suspend fun setMessageFontSize(size: Int) {
        require(size in 12..24)
        state.mutate({ it.copy(messageFontSize = size) }) {
            context.dataStore.edit { it[PreferenceKeys.MESSAGE_FONT_SIZE] = size }
        }
    }

    suspend fun setMessageFontSizeCustom(enabled: Boolean) {
        state.mutate({ it.copy(messageFontSizeCustom = enabled) }) {
            context.dataStore.edit { it[PreferenceKeys.MESSAGE_FONT_SIZE_CUSTOM] = enabled }
        }
    }

    suspend fun setToolRoundLimitEnabled(enabled: Boolean) {
        state.mutate({ it.copy(toolRoundLimitEnabled = enabled) }) {
            context.dataStore.edit { it[PreferenceKeys.TOOL_ROUND_LIMIT_ENABLED] = enabled }
        }
    }

    suspend fun setToolRoundLimit(limit: Int) {
        require(limit in 1..MAX_TOOL_ROUND_LIMIT)
        state.mutate({ it.copy(toolRoundLimit = limit) }) {
            context.dataStore.edit { it[PreferenceKeys.TOOL_ROUND_LIMIT] = limit }
        }
    }

    suspend fun setToolCallStrategy(strategy: ToolCallStrategy) {
        state.mutate({ it.copy(toolCallStrategy = strategy) }) {
            context.dataStore.edit { it[PreferenceKeys.TOOL_CALL_STRATEGY] = strategy.value }
        }
    }

    suspend fun setToolCallConcurrentLimit(limit: Int) {
        require(limit in 1..MAX_TOOL_CALL_CONCURRENT_LIMIT)
        state.mutate({ it.copy(toolCallConcurrentLimit = limit) }) {
            context.dataStore.edit { it[PreferenceKeys.TOOL_CALL_CONCURRENT_LIMIT] = limit }
        }
    }

    suspend fun setMcpAttachments(settings: McpAttachmentSettings) {
        val normalized = settings.normalized()
        state.mutate({ it.copy(mcpAttachments = normalized) }) {
            context.dataStore.edit { it[PreferenceKeys.MCP_ATTACHMENTS] = McpAttachmentSettings.encode(normalized) }
        }
    }

    suspend fun setAutoSummaryEnabled(enabled: Boolean) {
        state.mutate({ it.copy(autoSummaryEnabled = enabled) }) {
            context.dataStore.edit { it[PreferenceKeys.AUTO_SUMMARY_ENABLED] = enabled }
        }
    }

    suspend fun setSummarySuggestionEnabled(enabled: Boolean) {
        state.mutate({ it.copy(summarySuggestionEnabled = enabled) }) {
            context.dataStore.edit { it[PreferenceKeys.SUMMARY_SUGGESTION_ENABLED] = enabled }
        }
    }

    suspend fun setAutoSummaryPercent(percent: Int) {
        require(percent in MIN_AUTO_SUMMARY_PERCENT..MAX_AUTO_SUMMARY_PERCENT)
        state.mutate({ it.copy(autoSummaryPercent = percent) }) {
            context.dataStore.edit { it[PreferenceKeys.AUTO_SUMMARY_PERCENT] = percent }
        }
    }

    suspend fun setScreenWakelockMode(mode: ScreenWakelockMode) {
        state.mutate({ it.copy(screenWakelockMode = mode) }) {
            context.dataStore.edit { it[PreferenceKeys.SCREEN_WAKELOCK_MODE] = mode.value }
        }
    }

    suspend fun setMessageFontFamily(family: MessageFontFamily) {
        state.mutate({ it.copy(messageFontFamily = family) }) {
            context.dataStore.edit { it[PreferenceKeys.MESSAGE_FONT_FAMILY] = family.value }
        }
    }

    /** Replaces the complete preference snapshot during a full application restore. */
    suspend fun replace(preferences: AppPreferences) {
        val restoredSkills = preferences.agentSkills.skills
            .filterNot { it.id == DEFAULT_AGENT_SKILL_ID ||
                normalizeAgentSkillName(it.name) == DEFAULT_AGENT_SKILL_ID }
            .map { skill ->
                val name = normalizeAgentSkillName(skill.name)
                skill.copy(
                    id = name,
                    name = name,
                    content = skill.content.trim(),
                    description = skill.description.trim().ifBlank { "Reusable skill for $name." }
                        .take(AgentSkillsStore.MAX_DESCRIPTION_LENGTH)
                )
            }
            .filter { it.content.isNotBlank() }
            .distinctBy { it.name }
        val restoredSkillGroup = skillsWithBundledDefault(restoredSkills)
        state.mutate({ preferences.copy(agentSkills = restoredSkillGroup) }) {
            agentSkillsStore.replaceAll(restoredSkills)
            context.dataStore.edit { stored ->
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
        }
    }

}
