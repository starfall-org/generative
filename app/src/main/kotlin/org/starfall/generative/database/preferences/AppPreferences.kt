package org.starfall.generative.database.preferences

import kotlinx.serialization.Serializable
import org.starfall.generative.shared.model.AgentSkills
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.ui.theme.CustomPalette

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
enum class InstructionSelectionMode(val value: String) {
    PER_CHAT("per_chat"),
    GLOBAL("global"),
    PER_FOLDER("per_folder"),
    PER_CHAT_REMEMBER_GLOBAL("per_chat_remember_global"),
    PER_CHAT_OR_FOLDER("per_chat_or_folder");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: PER_CHAT_OR_FOLDER
    }
}

/** Unfiled conversations are one group only in the explicit per-folder mode. */
const val UNFILED_INSTRUCTION_SCOPE = "__unfiled__"

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
    val folderInstructionSelections: Map<String, Set<String>> = emptyMap(),
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
    val inputAreaTransparency: Float = 0.1f,
    val inputAreaBlur: Float = 2f,
    val instructionLibrary: InstructionLibrary = InstructionLibrary(),
    val instructionSelectionMode: InstructionSelectionMode = InstructionSelectionMode.PER_CHAT_OR_FOLDER,
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
    /** Resolve the active selection at request time, so moving chats between folders takes effect immediately. */
    fun instructionLibraryFor(conversation: Conversation?): InstructionLibrary =
        instructionLibraryForSelection(conversation?.selectedInstructionIds, conversation?.id)

    fun instructionFolderKey(conversationId: String?): String =
        conversationId?.let(chatRelations.sidebar.chatFolders::get) ?: UNFILED_INSTRUCTION_SCOPE

    fun usesFolderInstructions(conversationId: String?): Boolean = when (instructionSelectionMode) {
        InstructionSelectionMode.PER_FOLDER -> true
        InstructionSelectionMode.PER_CHAT_OR_FOLDER -> instructionFolderKey(conversationId) != UNFILED_INSTRUCTION_SCOPE
        else -> false
    }

    fun shouldSnapshotInstructions(conversationId: String?): Boolean = when (instructionSelectionMode) {
        InstructionSelectionMode.PER_CHAT, InstructionSelectionMode.PER_CHAT_REMEMBER_GLOBAL -> true
        InstructionSelectionMode.PER_CHAT_OR_FOLDER -> !usesFolderInstructions(conversationId)
        else -> false
    }

    fun instructionLibraryForSelection(selectedInstructionIds: Set<String>?, conversationId: String? = null): InstructionLibrary {
        val selection = when (instructionSelectionMode) {
            InstructionSelectionMode.GLOBAL -> instructionLibrary.selectedIds
            InstructionSelectionMode.PER_FOLDER ->
                chatRelations.folderInstructionSelections[instructionFolderKey(conversationId)].orEmpty()
            InstructionSelectionMode.PER_CHAT_OR_FOLDER -> if (usesFolderInstructions(conversationId)) {
                chatRelations.folderInstructionSelections[instructionFolderKey(conversationId)].orEmpty()
            } else selectedInstructionIds.orEmpty()
            InstructionSelectionMode.PER_CHAT -> selectedInstructionIds.orEmpty()
            InstructionSelectionMode.PER_CHAT_REMEMBER_GLOBAL -> selectedInstructionIds ?: instructionLibrary.selectedIds
        }
        return instructionLibrary.copy(selectedIds = selection)
    }
    fun systemPromptFor(conversation: Conversation?, at: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String =
        instructionLibraryFor(conversation).systemPrompt(at)
    fun instructionRoleMessagesFor(conversation: Conversation?, at: java.time.ZonedDateTime = java.time.ZonedDateTime.now()) =
        instructionLibraryFor(conversation).roleMessages(at)
}
