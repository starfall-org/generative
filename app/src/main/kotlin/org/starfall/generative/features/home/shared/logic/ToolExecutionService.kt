package org.starfall.generative.features.home.shared.logic

import kotlinx.serialization.json.JsonObject
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.mcp.McpSession
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.ToolDefinition

/** Owns calls into external MCP and Remote Machine services for the chat tool loop. */
internal class ToolExecutionService(
    private val mcpService: McpService,
    private val remoteMachineService: RemoteMachineService?
) {
    fun configuredMachines(): List<RemoteMachine> = remoteMachineService?.state?.value?.machines.orEmpty()

    suspend fun connect(server: McpInfo): McpSession = mcpService.session(server)

    suspend fun discoverTools(session: McpSession): List<ToolDefinition> = session.tools()

    suspend fun callMcpTool(
        session: McpSession,
        name: String,
        arguments: JsonObject
    ): JsonObject = session.call(name, arguments)

    suspend fun executeRemoteTool(
        machine: RemoteMachine,
        name: String,
        arguments: JsonObject,
        workspace: String? = null
    ): JsonObject = remoteMachineService?.executeTool(machine, name, arguments, workspace)
        ?: error("Remote Machine service is unavailable")

    fun disconnectRemoteMachines() {
        remoteMachineService?.disconnectAllWhenIdle()
    }
}
