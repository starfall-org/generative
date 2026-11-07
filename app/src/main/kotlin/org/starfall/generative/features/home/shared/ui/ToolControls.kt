package org.starfall.generative.features.home.shared.ui

import androidx.compose.runtime.staticCompositionLocalOf
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.provider.ModelProvider

data class ToolControls(
    val servers: List<McpInfo> = emptyList(),
    val machines: List<RemoteMachine> = emptyList(),
    val settings: ToolSettings = ToolSettings(),
    val providers: List<ModelProvider> = emptyList(),
    val setSystem: (String,SystemToolConfig)->Unit = {_,_->},
    val setMcp: (String,Boolean)->Unit = {_,_->},
    val setMachines: (Boolean)->Unit = {},
    val setMachine: (String, Boolean)->Unit = {_,_->},
    val selectMachine: (String?) -> Unit = {},
    val setMachineTool: (String, String, Boolean)->Unit = {_,_,_->},
    val setSendFile: (Boolean)->Unit = {},
    val setWebSearch: (Boolean)->Unit = {},
    val setSearchTools: (Boolean)->Unit = {},
    val setAgentSkillsSearch: (Boolean)->Unit = {},
    val setServerside: (RemoteMachine, Boolean) -> Unit = { _, _ -> },
    val serversidePending: Set<String> = emptySet()
)
val LocalToolControls = staticCompositionLocalOf { ToolControls() }
