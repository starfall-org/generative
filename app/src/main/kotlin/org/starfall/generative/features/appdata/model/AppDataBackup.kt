package org.starfall.generative.features.appdata.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.shared.model.icons.IconStoreState
import org.starfall.generative.shared.model.ChatProfile
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.RemoteMachineState
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.database.preferences.AppPreferences

/** Complete application snapshot stored in the manifest of a ZIP backup. */
@Serializable
data class AppDataBackup(
    val formatVersion: Int = 3,
    val exportedAt: String,
    val type: String = "full_backup",
    val providers: List<ModelProvider> = emptyList(),
    val providerGroups: List<ProviderGroup> = emptyList(),
    val mcpServers: List<McpInfo> = emptyList(),
    val speechServices: List<SpeechService> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val storedFileNames: List<String> = emptyList(),
    val uploadFileNames: List<String> = emptyList(),
    /** Output ownership and imported attachment copies must survive a portable restore. */
    val generatedMediaNames: List<String> = emptyList(),
    val importedMediaNames: Map<String, List<String>> = emptyMap(),
    val profiles: List<ChatProfile> = emptyList(),
    @SerialName("appState")
    val appPreferences: AppPreferences = AppPreferences(),
    val toolSettings: ToolSettings = ToolSettings(),
    val remoteMachines: RemoteMachineState = RemoteMachineState(),
    val remoteTerminalPreferences: RemoteTerminalPreferencesState = RemoteTerminalPreferencesState(),
    val icons: IconStoreState = IconStoreState(),
    val accountCredentials: Map<String, Map<String, JsonObject>> = emptyMap(),
    val mcpOAuthTokens: Map<String, JsonObject> = emptyMap(),
    val modelConfigurationMemory: Map<String, JsonObject> = emptyMap()
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 3
    }
}
