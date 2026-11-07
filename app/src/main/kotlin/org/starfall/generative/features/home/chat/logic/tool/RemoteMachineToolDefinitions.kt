package org.starfall.generative.features.home.chat.logic.tool

import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.REMOTE_TOOL_DISCONNECT
import org.starfall.generative.shared.model.REMOTE_TOOL_CLOSE_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_START_MACHINE
import org.starfall.generative.shared.model.REMOTE_TOOL_UPDATE_IP
import org.starfall.generative.shared.model.REMOTE_TOOL_LIST_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_READ_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_OPEN_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_EXECUTE
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineType
import org.starfall.generative.shared.model.ToolDefinition

/** Model-facing declaration; execution remains with the host Remote Machine service. */
internal fun remoteToolDefinition(machine: RemoteMachine, toolName: String): ToolDefinition =
    ToolDefinition(
        name = remoteToolName(machine, toolName),
        description = when (toolName) {
            REMOTE_TOOL_START_MACHINE ->
                "Start the Google Cloud Shell environment of the remote machine '${machine.name}' before using it."
            REMOTE_TOOL_DISCONNECT ->
                "Close every terminal and the SSH connection to the remote machine '${machine.name}'."
            REMOTE_TOOL_OPEN_TERMINAL ->
                "Open a new interactive terminal on '${machine.name}' and return its terminal_id."
            REMOTE_TOOL_CLOSE_TERMINAL ->
                "Close one terminal on '${machine.name}'. If terminal_id is omitted, close the newest terminal."
            REMOTE_TOOL_LIST_TERMINAL ->
                "List interactive terminals currently open on '${machine.name}'."
            REMOTE_TOOL_READ_TERMINAL ->
                "Read the latest lines of output from an existing interactive terminal on '${machine.name}'. " +
                    "Specify lines (1-1000); optionally supply terminal_id, otherwise use the newest terminal. " +
                    "This does not execute a command or consume terminal output."
            REMOTE_TOOL_UPDATE_IP ->
                "Refresh the SSH endpoint of the Google Cloud Shell remote machine '${machine.name}'. Use only after connecting to this machine reports an error."
            else ->
                "Execute a command on an already open terminal on '${machine.name}' (${machine.type.label()}). " +
                    "If terminal_id is omitted, use the newest terminal. Return concise commands and inspect the result."
        },
        schema = remoteToolSchema(toolName),
        originalName = toolName,
        remoteMachineId = machine.id
    )

/**
 * Tool names are visible to the model, so they use the machine name the user sees in the app
 * rather than its internal id, which stays in [ToolDefinition.remoteMachineId] for dispatch only.
 * Same-named machines are told apart later by withUniqueWireNames (name_2, name_3...).
 */
private fun remoteToolName(machine: RemoteMachine, tool: String): String {
    val readable = java.text.Normalizer.normalize(machine.name, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
        .replace(Regex("[^A-Za-z0-9_]+"), "_")
        .trim('_')
        .ifBlank { "unnamed" }
    val prefix = "machine_"
    val suffix = "_$tool"
    return prefix + readable.take((64 - prefix.length - suffix.length).coerceAtLeast(1)) + suffix
}

private fun RemoteMachineType.label(): String = when (this) {
    RemoteMachineType.SSH -> "SSH"
    RemoteMachineType.GCLOUD_SHELL -> "Google Cloud Shell"
}

private fun remoteToolSchema(tool: String): JsonObject = when (tool) {
    REMOTE_TOOL_START_MACHINE, REMOTE_TOOL_UPDATE_IP, REMOTE_TOOL_OPEN_TERMINAL, REMOTE_TOOL_LIST_TERMINAL, REMOTE_TOOL_DISCONNECT -> buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }
    REMOTE_TOOL_CLOSE_TERMINAL -> buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("terminal_id", buildJsonObject {
                put("type", "string")
                put("description", "Optional terminal ID. Without it, close the newest terminal.")
            })
        })
    }
    REMOTE_TOOL_READ_TERMINAL -> buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("lines", buildJsonObject {
                put("type", "integer")
                put("description", "Number of latest output lines to read, between 1 and 1000")
                put("minimum", 1)
                put("maximum", 1000)
            })
            put("terminal_id", buildJsonObject {
                put("type", "string")
                put("description", "Optional open terminal ID. Without it, use the newest terminal.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("lines"))))
    }
    REMOTE_TOOL_EXECUTE -> buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", "string")
                put("description", "The shell command to run on the remote machine")
            })
            put("timeout_seconds", buildJsonObject {
                put("type", "integer")
                put("description", "Optional timeout in seconds, from 1 to 600")
            })
            put("terminal_id", buildJsonObject {
                put("type", "string")
                put("description", "Optional open terminal ID. Without it, use the newest terminal.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("command"))))
    }
    else -> error("Unknown Remote Machine tool: $tool")
}
