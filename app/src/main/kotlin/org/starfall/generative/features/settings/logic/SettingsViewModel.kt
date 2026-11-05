package org.starfall.generative.features.settings.logic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.state.LocalWriteErrors
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.database.preferences.InstructionSelectionMode
import org.starfall.generative.shared.ui.theme.CustomPalette
import org.starfall.generative.database.preferences.UserProfile
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.database.preferences.CodeBlockDisplayMode
import org.starfall.generative.database.preferences.MessageFontFamily
import org.starfall.generative.database.preferences.ScreenWakelockMode
import org.starfall.generative.database.preferences.McpAttachmentSettings
import org.starfall.generative.database.preferences.ToolCallStrategy
import org.starfall.generative.database.preferences.WordWrapMode

class SettingsViewModel(
    private val repository: AppPreferencesRepository
) : ViewModel() {
    val preferences = repository.appPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    fun updateSidebar(transform: (SidebarOrganization) -> SidebarOrganization) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.updateSidebar(transform) }
    }

    fun setCustomizationEnabled(enabled: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setCustomizationEnabled(enabled) }
    }

    fun setThemeMode(value: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setThemeMode(value) }
    }
    fun setUseAmoled(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setUseAmoled(value) }
    }
    fun setInputAreaTransparency(value: Float) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setInputAreaTransparency(value) }
    }
    fun setInputAreaBlur(value: Float) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setInputAreaBlur(value) }
    }
    fun setItemTransparency(value: Float) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setItemTransparency(value) }
    }
    fun setUseDynamicColor(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setUseDynamicColor(value) }
    }
    fun setFeaturesLogoImage(value: String?) {
        updateUserProfile { it.copy(photo = value) }
    }
    fun updateUserProfile(transform: (UserProfile) -> UserProfile) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.updateUserProfile(transform) }
    }
    fun setColorSchemeName(value: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setColorSchemeName(value) }
    }
    fun setCustomPalette(value: CustomPalette) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setCustomPalette(value) }
    }
    fun setInstructionSelectionMode(value: InstructionSelectionMode) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setInstructionSelectionMode(value) }
    }

    fun setContinueLastConversation(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setContinueLastConversation(value) }
    }
    fun setAutoScroll(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setAutoScroll(value) }
    }
    fun setTtsReadCodeBlocks(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setTtsReadCodeBlocks(value) }
    }
    fun setProvidersCollapsedSections(value: Set<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setProvidersCollapsedSections(value) }
    }
    fun setModelPickerCollapsedGroups(value: Set<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setModelPickerCollapsedGroups(value) }
    }
    fun setModelPickerCollapsedProviders(value: Set<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setModelPickerCollapsedProviders(value) }
    }
    fun setEnableVibration(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setEnableVibration(value) }
    }
    fun setHideStatusBar(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setHideStatusBar(value) }
    }
    fun setDebugMode(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setDebugMode(value) }
    }
    fun setDebugLogLevel(value: DebugLogLevel) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setDebugLogLevel(value) }
    }
    fun setDebugLogCategories(value: Set<DebugLogCategory>) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setDebugLogCategories(value) }
    }
    fun setLatexMode(value: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setLatexMode(value) }
    }
    fun setWordWrapMode(value: WordWrapMode) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setWordWrapMode(value) }
    }
    fun setWordWrapColumn(value: Int) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setWordWrapColumn(value) }
    }
    fun setCodePreviewEnabled(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setCodePreviewEnabled(value) }
    }
    fun setCodeBlockDisplayMode(value: CodeBlockDisplayMode) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setCodeBlockDisplayMode(value) }
    }
    fun setCodeBlockMaxLines(value: Int) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setCodeBlockMaxLines(value) }
    }
    fun setCodeBlockIncludeUndefined(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setCodeBlockIncludeUndefined(value) }
    }
    fun setMessageFontSize(value: Int) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setMessageFontSize(value) }
    }
    fun setMessageFontSizeCustom(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setMessageFontSizeCustom(value) }
    }
    fun setToolRoundLimitEnabled(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setToolRoundLimitEnabled(value) }
    }
    fun setToolRoundLimit(value: Int) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setToolRoundLimit(value) }
    }
    fun setToolCallStrategy(value: ToolCallStrategy) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setToolCallStrategy(value) }
    }
    fun setToolCallConcurrentLimit(value: Int) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setToolCallConcurrentLimit(value) }
    }
    fun setMcpAttachments(value: McpAttachmentSettings) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setMcpAttachments(value) }
    }
    fun setAutoSummaryEnabled(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setAutoSummaryEnabled(value) }
    }
    fun setSummarySuggestionEnabled(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setSummarySuggestionEnabled(value) }
    }
    fun setAutoSummaryPercent(value: Int) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setAutoSummaryPercent(value) }
    }
    fun setScreenWakelockMode(value: ScreenWakelockMode) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setScreenWakelockMode(value) }
    }
    fun setAppLanguage(useSystem: Boolean, tag: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setAppLanguage(useSystem, tag) }
    }
    fun setMessageFontFamily(value: MessageFontFamily) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setMessageFontFamily(value) }
    }

}
