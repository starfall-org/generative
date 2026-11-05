package org.starfall.generative.integrations.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.ParametersBuilder
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.StringReader
import java.util.UUID
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.McpProtocol
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.auth.bearerHeaderValue
import org.starfall.generative.shared.model.resolvedUrl
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.shared.model.logging.DebugLogCategory

/** MCP client backed by the official Model Context Protocol Kotlin SDK. */
class McpService(
    private val http: ToolHttp = ToolHttp(),
    private val oauth: McpOAuthService? = null,
    var serverLoader: suspend (McpInfo) -> McpInfo = { it },
    private val contentApiMcpUrl: String? = null
) {
    suspend fun listTools(info: McpInfo): List<String> = discover(info).map { it.originalName }
    suspend fun discover(info: McpInfo): List<ToolDefinition> = session(info).useSession { it.tools() }

    suspend fun session(info: McpInfo): McpSession {
        val server = if (info.credentialState == CredentialLoadState.NOT_REQUESTED) serverLoader(info) else info
        check(server.id == info.id) { "Credential loader returned a different MCP server." }
        server.requireNetworkCredentials()
        val oauthToken = if (server.auth.method == McpAuthMethod.OAUTH2) {
            oauth?.accessToken(server) ?: server.auth.token
        } else {
            server.auth.token
        }
        return McpSession(server, http, oauthToken, contentApiMcpUrl).also { it.initialize() }
    }
}

private fun McpInfo.requireNetworkCredentials() {
    check(credentialState == CredentialLoadState.LOADED || credentialState == CredentialLoadState.NOT_CONFIGURED) {
        "MCP credentials are not ready ($credentialState). Reload before use."
    }
}

suspend fun <T> McpSession.useSession(block: suspend (McpSession) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

class McpSession(
    private val info: McpInfo,
    private val http: ToolHttp,
    private val oauthToken: String = info.auth.token,
    contentApiMcpUrl: String? = null
) {
    init { info.requireNetworkCredentials() }
    private val endpoint = info.resolvedUrl(contentApiMcpUrl) ?: error("MCP URL is missing")
    private var httpClient: HttpClient? = null
    private var client: Client? = null
    private var needsReconnect = false

    suspend fun initialize() {
        needsReconnect = false
        if (info.protocol == McpProtocol.SSE) {
            connect(legacy = true)
            return
        }
        try {
            connect(legacy = false)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val transportError = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<StreamableHttpError>().firstOrNull() ?: throw error
            // MCP's backwards-compatibility guidance allows a client to try the
            // old SSE transport after a failed initialize POST. This covers
            // servers that incorrectly advertise an SSE endpoint as Streamable
            // HTTP and commonly answer the POST with HTTP 415.
            if (transportError.code !in 400..499) throw error
            close()
            connect(legacy = true)
        }
    }

    private suspend fun connect(legacy: Boolean) {
        val logId = DebugLog.beginOperation(DebugLogCategory.NETWORK, "MCP",
            "Connecting to ${info.name}", "transport=${if (legacy) "SSE" else "Streamable HTTP"}")
        val ktor = HttpClient(CIO) {
            install(SSE)
            install(org.starfall.generative.shared.subfeatures.logging.logic.DebugLogKtorPlugin)
        }
        val transport = if (legacy) {
            SseClientTransport(ktor, endpoint, requestBuilder = { configureRequest() })
        } else {
            StreamableHttpClientTransport(ktor, endpoint, requestBuilder = { configureRequest() })
        }
        val mcpClient = Client(Implementation("Generative", "1.0"))
        try {
            val negotiatedTransport = if (transport is StreamableHttpClientTransport) {
                // SDK 0.15 exposes protocolVersion but does not populate it from
                // initialize. Set it before forwarding the reply so the initialized
                // notification and subsequent requests carry the negotiated header.
                object : Transport by transport {
                    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
                        transport.onMessage { message ->
                            if (transport.protocolVersion == null && message is JSONRPCResponse) {
                                transport.protocolVersion = (message.result as? InitializeResult)?.protocolVersion
                            }
                            block(message)
                        }
                    }
                }
            } else transport
            mcpClient.connect(negotiatedTransport)
            httpClient = ktor
            client = mcpClient
            DebugLog.finishOperation(logId, "Connected to ${info.name}")
        } catch (error: Throwable) {
            DebugLog.finishOperation(logId, "MCP connection failed", failed = true,
                details = error::class.java.simpleName, error = error)
            runCatching { mcpClient.close() }
            ktor.close()
            throw error
        }
    }

    private fun HttpRequestBuilder.configureRequest() {
        info.headers.orEmpty().forEach { (name, value) ->
            headers.remove(name)
            header(name, value)
        }
        when (info.auth.method) {
            McpAuthMethod.BEARER_TOKEN, McpAuthMethod.CUSTOM_HEADER -> {
                val name = info.auth.key?.takeIf { it.isNotBlank() } ?: "Authorization"
                oauthToken.takeIf { it.isNotBlank() }?.let {
                    headers.remove(name)
                    header(name, bearerHeaderValue(name, it))
                }
            }

            McpAuthMethod.OAUTH2 -> oauthToken.takeIf { it.isNotBlank() }?.let {
                headers.remove(HttpHeaders.Authorization)
                header(HttpHeaders.Authorization, "Bearer $it")
            }

            McpAuthMethod.QUERY_PARAM -> {
                val key = info.auth.key?.takeIf { it.isNotBlank() } ?: "key"
                val value = info.auth.value.orEmpty()
                if (value.isNotBlank()) url { parameters.append(key, value) }
            }

            McpAuthMethod.NONE -> Unit
        }
    }

    suspend fun tools(): List<ToolDefinition> {
        val mcp = client ?: error("MCP session is not connected")
        val result = mutableListOf<ToolDefinition>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = if (cursor == null) {
                mcp.listTools()
            } else {
                mcp.listTools(ListToolsRequest(PaginatedRequestParams(cursor)))
            }
            page.tools.forEach { tool ->
                require(tool.name.isNotBlank()) { "MCP tool name is missing" }
                result += toolDefinition(tool)
                check(result.size <= 256) { "MCP has more than 256 tools" }
            }
            cursor = page.nextCursor
            check(cursor == null || seen.add(cursor)) { "MCP repeated a tools page" }
        } while (!cursor.isNullOrEmpty())
        DebugLog.log(DebugLogCategory.NETWORK, "MCP tools") {
            "${info.name}: discovered ${result.size} tools"
        }
        return result
    }

    private fun toolDefinition(tool: Tool): ToolDefinition {
        val inputSchema = tool.inputSchema.toJson()
        return ToolDefinition(
            mcpToolWireName(info.name, tool.name),
            tool.description.orEmpty().take(4000),
            inputSchema,
            info.id,
            tool.name
        )
    }

    suspend fun call(name: String, arguments: JsonObject): JsonObject {
        val logId = DebugLog.beginOperation(DebugLogCategory.NETWORK, "MCP tool",
            "${info.name} / $name", "argumentFields=${arguments.size}")
        try {
            if (needsReconnect) {
                close()
                initialize()
            }
            val mcp = client ?: error("MCP session is not connected")
            val result = try {
                mcp.callTool(name, arguments.mapValues { it.value.toAny() })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never replay an action which may already have produced side effects.
                needsReconnect = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<StreamableHttpError>().any { it.code == 404 }
                throw error
            }
            val raw = Json.encodeToString(CallToolResult.serializer(), result)
            val sanitized = http.files?.sanitize(StringReader(raw)) ?: raw
            val value = Json.parseToJsonElement(sanitized).jsonObject
            DebugLog.finishOperation(logId, "MCP tool completed", details = "responseCharacters=${sanitized.length}")
            return value
        } catch (error: Exception) {
            DebugLog.finishOperation(logId,
                if (error is CancellationException) "MCP tool canceled" else "MCP tool failed",
                failed = error !is CancellationException,
                details = error::class.java.simpleName,
                error = error.takeUnless { it is CancellationException })
            throw error
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        client?.let { runCatching { it.close() } }
        client = null
        httpClient?.close()
        httpClient = null
    }
}

/**
 * Function names are visible to the model. Keep them readable while staying
 * within the portable function-name grammar used by OpenAI-compatible,
 * Anthropic, and Gemini endpoints.
 */
internal fun mcpToolWireName(serverName: String, toolName: String): String {
    val server = serverName.toWireNamePart().ifBlank { "mcp" }
    val tool = toolName.toWireNamePart().ifBlank { "tool" }
    val base = "${server}_${tool}"
    if (base.length <= MCP_TOOL_NAME_MAX_LENGTH) return base

    // Derived from the visible names only: internal server ids must never reach the model.
    val suffix = UUID.nameUUIDFromBytes(("$serverName:$toolName").toByteArray())
        .toString().replace("-", "").take(MCP_TOOL_NAME_SUFFIX_LENGTH)
    return base.take(MCP_TOOL_NAME_MAX_LENGTH - suffix.length - 1) + "_" + suffix
}

private fun String.toWireNamePart(): String =
    replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_')

private const val MCP_TOOL_NAME_MAX_LENGTH = 64
private const val MCP_TOOL_NAME_SUFFIX_LENGTH = 10

private fun ToolSchema.toJson(): JsonObject = buildJsonObject {
    schema?.takeIf { it.isNotBlank() }?.let { put($$"$schema", JsonPrimitive(it)) }
    put("type", JsonPrimitive(type))
    properties?.takeIf { it.isNotEmpty() }?.let { put("properties", it) }
    required?.takeIf { it.isNotEmpty() }?.let { put("required", JsonArray(it.map(::JsonPrimitive))) }
    defs?.let { put($$"$defs", it) }
}

private fun JsonElement.toAny(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.toAny() }
    is JsonArray -> map { it.toAny() }
    is JsonPrimitive -> when {
        isString -> content
        content.equals("true", true) -> true
        content.equals("false", true) -> false
        content.toLongOrNull() != null -> content.toLong()
        content.toDoubleOrNull() != null -> content.toDouble()
        else -> content
    }
}
