package org.starfall.generative

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.home.shared.logic.RemoteWorkspaceTools
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.integrations.remote.remoteTerminalWorkspaceCommand
import org.starfall.generative.shared.model.REMOTE_TOOL_OPEN_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_EXECUTE
import org.starfall.generative.shared.model.REMOTE_TOOL_SET_WORKSPACE
import org.starfall.generative.shared.model.RemoteMachine

class RemoteWorkspaceToolsTest {
    private val machine = RemoteMachine("one", "One")
    private val empty = JsonObject(emptyMap())
    private val unused: suspend (RemoteMachine, String, JsonObject, String?) -> JsonObject = { _, _, _, _ ->
        error("Setting a workspace must not connect to a remote server")
    }
    private fun path(value: String) = JsonObject(mapOf("path" to JsonPrimitive(value)))

    @Test fun workspacesSurviveChatSerializationAndStayIsolated() = runBlocking {
        var chat = Conversation("chat", "Chat", 0, 0)
        val otherChat = RemoteWorkspaceTools(emptyMap()) { _, _ -> error("Wrong chat") }
        val tools = RemoteWorkspaceTools(chat.remoteWorkspaces) { id, path ->
            chat = chat.copy(remoteWorkspaces = if (path == null) chat.remoteWorkspaces - id
                else chat.remoteWorkspaces + (id to path))
        }
        assertEquals("set", tools.call(machine, REMOTE_TOOL_SET_WORKSPACE, path("/project"), unused)["status"]?.jsonPrimitive?.content)
        val second = machine.copy(id = "two")
        tools.call(second, REMOTE_TOOL_SET_WORKSPACE, path("/other"), unused)
        val restored = Json.decodeFromString(Conversation.serializer(), Json.encodeToString(Conversation.serializer(), chat))
        val nextTurn = RemoteWorkspaceTools(restored.remoteWorkspaces) { _, _ -> }
        assertEquals("/project", nextTurn.workspace(machine.id))
        assertEquals("/other", nextTurn.workspace(second.id))
        assertNull(otherChat.workspace(machine.id))

        assertEquals("cleared", tools.call(machine, REMOTE_TOOL_SET_WORKSPACE, empty, unused)["status"]?.jsonPrimitive?.content)
        assertNull(tools.workspace(machine.id))
        assertFalse(machine.id in chat.remoteWorkspaces)
        assertEquals("/other", chat.remoteWorkspaces[second.id])
    }

    @Test fun executeUsesLiteralDirectoryAndKeepsOtherArguments() = runBlocking {
        val root = Files.createTempDirectory("workspace-test").toFile()
        try {
            val directory = root.resolve("project ' \$(echo injected)").apply { mkdirs() }
            val tools = RemoteWorkspaceTools(mapOf(machine.id to directory.path)) { _, _ -> }
            val arguments = JsonObject(mapOf(
                "command" to JsonPrimitive("pwd"),
                "terminal_id" to JsonPrimitive("terminal"),
                "timeout_seconds" to JsonPrimitive(5)
            ))
            tools.call(machine, REMOTE_TOOL_EXECUTE, arguments) { resolvedMachine, name, resolved, workspace ->
                assertNull(workspace)
                assertEquals(machine, resolvedMachine)
                assertEquals(REMOTE_TOOL_EXECUTE, name)
                assertEquals(arguments["terminal_id"], resolved["terminal_id"])
                assertEquals(arguments["timeout_seconds"], resolved["timeout_seconds"])
                val process = ProcessBuilder("sh", "-c", resolved.getValue("command").jsonPrimitive.content).start()
                val output = process.inputStream.bufferedReader().readText().trimEnd()
                assertEquals(0, process.waitFor())
                assertEquals(directory.canonicalPath, output)
                empty
            }
            tools.call(machine, REMOTE_TOOL_SET_WORKSPACE, empty, unused)
            tools.call(machine, REMOTE_TOOL_EXECUTE, arguments) { _, _, resolved, _ ->
                assertEquals(arguments, resolved)
                empty
            }
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test fun missingDirectoryPreventsEveryPartOfCompoundCommand() = runBlocking {
        val root = Files.createTempDirectory("missing-workspace").toFile()
        try {
            val marker = root.resolve("should-not-exist")
            val tools = RemoteWorkspaceTools(mapOf(machine.id to root.resolve("missing").path)) { _, _ -> }
            tools.call(machine, REMOTE_TOOL_EXECUTE,
                JsonObject(mapOf("command" to JsonPrimitive("true; touch '$marker'")))) { _, _, resolved, _ ->
                val process = ProcessBuilder("sh", "-c", resolved.getValue("command").jsonPrimitive.content)
                    .redirectErrorStream(true).start()
                process.inputStream.bufferedReader().readText()
                assertNotEquals(0, process.waitFor())
                assertFalse(marker.exists())
                empty
            }
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test fun newAiTerminalsUseWorkspaceUntilCleared() = runBlocking {
        val root = Files.createTempDirectory("terminal-workspace").toFile()
        try {
            val directory = root.resolve("project ' $(echo injected)").apply { mkdirs() }
            val tools = RemoteWorkspaceTools(emptyMap()) { _, _ -> }
            tools.call(machine, REMOTE_TOOL_SET_WORKSPACE, path(directory.path), unused)
            tools.call(machine, REMOTE_TOOL_OPEN_TERMINAL, empty) { actualMachine, name, args, workspace ->
                assertEquals(machine, actualMachine)
                assertEquals(REMOTE_TOOL_OPEN_TERMINAL, name)
                assertEquals(empty, args)
                assertEquals(directory.path, workspace)
                val process = ProcessBuilder("sh").start()
                process.outputStream.bufferedWriter().use { input ->
                    input.write(remoteTerminalWorkspaceCommand(requireNotNull(workspace)))
                    input.write("pwd\n")
                }
                assertEquals(directory.canonicalPath, process.inputStream.bufferedReader().readText().trimEnd())
                assertEquals(0, process.waitFor())
                empty
            }
            tools.call(machine.copy(id = "other"), REMOTE_TOOL_OPEN_TERMINAL, empty) { _, _, _, workspace ->
                assertNull(workspace)
                empty
            }
            tools.call(machine, REMOTE_TOOL_SET_WORKSPACE, empty, unused)
            tools.call(machine, REMOTE_TOOL_OPEN_TERMINAL, empty) { _, _, _, workspace ->
                assertNull(workspace)
                empty
            }
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test fun missingTerminalWorkspaceExitsBeforeAcceptingCommands() {
        val root = Files.createTempDirectory("missing-terminal-workspace").toFile()
        try {
            val process = ProcessBuilder("sh").redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { input ->
                input.write(remoteTerminalWorkspaceCommand(root.resolve("missing").path))
                input.write("echo should-not-run\n")
            }
            assertFalse(process.inputStream.bufferedReader().readText().contains("should-not-run"))
            assertNotEquals(0, process.waitFor())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun invalidPathsDoNotClearExistingWorkspace() = runBlocking {
        val tools = RemoteWorkspaceTools(mapOf(machine.id to "/project")) { _, _ -> error("Invalid update") }
        for (arguments in listOf(path(""), path("relative"), path("/bad\u0000path"),
            Json.parseToJsonElement("{\"path\":null}") as JsonObject,
            Json.parseToJsonElement("{\"path\":42}") as JsonObject)) {
            try {
                tools.call(machine, REMOTE_TOOL_SET_WORKSPACE, arguments, unused)
                fail("Expected invalid path to be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals("/project", tools.workspace(machine.id))
            }
        }
    }
}
