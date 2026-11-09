package org.starfall.generative.features.home.shared.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.starfall.generative.shared.model.REMOTE_TOOL_OPEN_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_EXECUTE
import org.starfall.generative.shared.model.REMOTE_TOOL_SET_WORKSPACE
import org.starfall.generative.shared.model.RemoteMachine

/** Per-generation working copy of this chat's persisted remote directories. */
internal class RemoteWorkspaceTools(
    initialWorkspaces: Map<String, String>,
    private val onChanged: (String, String?) -> Unit
) {
    private val workspaces = initialWorkspaces.toMutableMap()

    fun workspace(machineId: String): String? = workspaces[machineId]

    suspend fun call(
        machine: RemoteMachine,
        name: String,
        arguments: JsonObject,
        execute: suspend (RemoteMachine, String, JsonObject, String?) -> JsonObject
    ): JsonObject {
        if (name == REMOTE_TOOL_SET_WORKSPACE) {
            val path = if ("path" in arguments) {
                val value = arguments["path"] as? JsonPrimitive
                require(value?.isString == true) { "path must be an absolute directory path" }
                requireNotNull(value.contentOrNull).also {
                    require(it.startsWith('/') && '\u0000' !in it) { "path must be an absolute directory path" }
                }
            } else null
            onChanged(machine.id, path)
            if (path == null) workspaces.remove(machine.id) else workspaces[machine.id] = path
            return buildJsonObject {
                put("status", if (path == null) "cleared" else "set")
                path?.let { put("path", it) }
            }
        }
        val path = workspace(machine.id)
        val resolvedArguments = if (name == REMOTE_TOOL_EXECUTE && path != null) {
            val command = arguments["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(command.isNotBlank()) { "The command is empty." }
            // Quote the path literally and gate the entire command, including compound commands,
            // so a missing directory never falls through to execution in the default directory.
            val quotedPath = "'" + path.replace("'", "'\"'\"'") + "'"
            JsonObject(arguments + ("command" to JsonPrimitive("cd $quotedPath && {\n$command\n}")))
        } else arguments
        return execute(machine, name, resolvedArguments, path.takeIf { name == REMOTE_TOOL_OPEN_TERMINAL })
    }
}
