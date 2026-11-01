package org.starfall.generative.features.home.chat.logic.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.starfall.generative.shared.model.McpToolParameterConfig
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.withSelectedRemoteMachine
import org.starfall.generative.shared.storage.LocalWriteErrors
import org.starfall.generative.shared.storage.preferences.ToolSettingsStore

/** Writes chat-tool preferences; the ViewModel only exposes these actions to the UI. */
internal class ChatToolSettingsActions(
    private val scope: CoroutineScope,
    private val store: ToolSettingsStore,
) {
    fun setSystemTool(name: String, config: SystemToolConfig) {
        scope.launch(LocalWriteErrors.handler) {
            store.update { it.copy(system = it.system + (name to config)) }
        }
    }

    fun setSearchToolsEnabled(enabled: Boolean) {
        scope.launch(LocalWriteErrors.handler) { store.update { it.copy(searchToolsEnabled = enabled) } }
    }

    fun setAgentSkillsSearchEnabled(enabled: Boolean) {
        scope.launch(LocalWriteErrors.handler) { store.update { it.copy(agentSkillsSearchEnabled = enabled) } }
    }

    fun setSendFileEnabled(enabled: Boolean) {
        scope.launch(LocalWriteErrors.handler) { store.update { it.copy(sendFileEnabled = enabled) } }
    }

    fun setQuickMcp(id: String, enabled: Boolean) {
        scope.launch(LocalWriteErrors.handler) {
            store.update { settings ->
                if (settings.webSearchProviderId == id) settings
                else settings.copy(quickMcp = settings.quickMcp + (id to enabled))
            }
        }
    }

    fun setWebSearchProvider(id: String?) {
        scope.launch(LocalWriteErrors.handler) {
            store.update { settings ->
                settings.copy(
                    webSearchProviderId = id?.takeIf(String::isNotBlank),
                    webSearchEnabled = settings.webSearchEnabled && id != null
                )
            }
        }
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        scope.launch(LocalWriteErrors.handler) {
            store.update { settings ->
                settings.copy(webSearchEnabled = enabled && settings.webSearchProviderId != null)
            }
        }
    }

    fun setMcpToolEnabled(serverId: String, toolName: String, enabled: Boolean) {
        scope.launch(LocalWriteErrors.handler) {
            store.update { settings ->
                val serverTools = settings.mcpTools[serverId].orEmpty() + (toolName to enabled)
                settings.copy(mcpTools = settings.mcpTools + (serverId to serverTools))
            }
        }
    }

    fun setMcpToolParameter(
        serverId: String,
        toolName: String,
        parameterName: String,
        config: McpToolParameterConfig,
    ) {
        scope.launch(LocalWriteErrors.handler) {
            store.update { settings ->
                val serverParameters = settings.mcpToolParameters[serverId].orEmpty()
                val parameters = serverParameters[toolName].orEmpty() + (parameterName to config)
                val tools = serverParameters + (toolName to parameters)
                settings.copy(mcpToolParameters = settings.mcpToolParameters + (serverId to tools))
            }
        }
    }

    fun setMachinesEnabled(enabled: Boolean) {
        scope.launch(LocalWriteErrors.handler) { store.update { it.copy(machinesEnabled = enabled) } }
    }

    fun setMachineEnabled(machineId: String, enabled: Boolean, knownMachineIds: List<String> = emptyList()) {
        scope.launch(LocalWriteErrors.handler) {
            store.update { settings ->
                val initialSwitches = if (settings.enabledMachines.isEmpty() && knownMachineIds.isNotEmpty()) {
                    knownMachineIds.associateWith { settings.machinesEnabled }
                } else settings.enabledMachines
                settings.copy(
                    enabledMachines = initialSwitches + (machineId to enabled),
                    machinesEnabled = settings.machinesEnabled || enabled
                )
            }
        }
    }

    /** Single atomic selection; Unset disables all remote tools, including legacy master settings. */
    fun selectMachine(machineId: String?, knownMachineIds: List<String>) {
        scope.launch(LocalWriteErrors.handler) {
            store.update { it.withSelectedRemoteMachine(machineId, knownMachineIds) }
        }
    }

    fun setMachineToolEnabled(machineId: String, toolName: String, enabled: Boolean) {
        scope.launch(LocalWriteErrors.handler) {
            store.update { settings ->
                val machineTools = settings.machineTools[machineId].orEmpty() + (toolName to enabled)
                settings.copy(machineTools = settings.machineTools + (machineId to machineTools))
            }
        }
    }
}
