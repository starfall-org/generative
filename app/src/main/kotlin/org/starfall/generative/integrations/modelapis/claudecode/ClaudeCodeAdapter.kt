package org.starfall.generative.integrations.modelapis.claudecode

import android.content.Context
import kotlinx.serialization.json.*
import org.starfall.generative.integrations.modelapis.account.OAuthAccountAdapter
import org.starfall.generative.integrations.modelapis.anthropic.CLAUDE_CODE_IDENTITY
import org.starfall.generative.integrations.modelapis.anthropic.CLAUDE_CODE_USER_AGENT
import org.starfall.generative.integrations.modelapis.anthropic.aliasClaudeCodeTools
import org.starfall.generative.integrations.modelapis.anthropic.claudeCodeBetaHeader
import org.starfall.generative.integrations.modelapis.anthropic.relocateClaudeCodeSystem
import org.starfall.generative.integrations.modelapis.anthropic.restoreClaudeCodeToolNames
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.*
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.language.*
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.helpers.networking.text

internal const val CLAUDE_CODE_OAUTH_SCOPE =
    "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"

internal class ClaudeCodeAdapter(
    context: Context,
    attachments: AttachmentResolver,
    tokenEndpoint: String = "https://platform.claude.com/v1/oauth/token"
) : OAuthAccountAdapter(
    context, attachments, ModelApiType.CLAUDE_CODE,
    "9d1c250a-e61b-44d9-88ed-5944d1962f5e", "https://claude.ai/oauth/authorize",
    tokenEndpoint, CLAUDE_CODE_OAUTH_SCOPE,
    54545, "/callback", jsonTokens = true
) {
    override fun sdkSystemPrompt(systemPrompt: String): String = CLAUDE_CODE_IDENTITY

    override fun sdkCallerSystemPrompt(systemPrompt: String): String? = systemPrompt.trim()
        .takeIf { it.isNotEmpty() && it != CLAUDE_CODE_IDENTITY }

    override suspend fun prepareAuthenticatedProvider(provider: ModelProvider): ModelProvider {
        val token = ensureToken(provider)
        return provider.copy(type = ModelApiType.ANTHROPIC,
            auth = Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer ${token.accessToken}"),
            config = provider.config.copy(headers = provider.config.headers + mapOf(
                "User-Agent" to CLAUDE_CODE_USER_AGENT, "x-app" to "cli",
                "anthropic-version" to "2023-06-01",
                "anthropic-dangerous-direct-browser-access" to "true",
                "anthropic-beta" to claudeCodeBetaHeader())))
    }

    override fun prepareRequestProvider(sourceProvider: ModelProvider, wireProvider: ModelProvider,
        body: JsonObject): ModelProvider = wireProvider.copy(config = wireProvider.config.copy(
        headers = wireProvider.config.headers + ("anthropic-beta" to claudeCodeBetaHeader(body))))

    override suspend fun fetchModels(provider: ModelProvider): List<String> {
        val wire = prepareAuthenticatedProvider(provider)
        return (http.json(http.request(wire.baseUrl.trimEnd('/') + "/models", wire).get().build())["data"] as? JsonArray)
            .orEmpty().mapNotNull { it.jsonObject.text("id").takeIf(String::isNotBlank) }
    }

    override fun normalizeToolRequest(sourceProvider: ModelProvider, wireProvider: ModelProvider,
        body: JsonObject, systemPrompt: String): JsonObject =
        relocateClaudeCodeSystem(aliasClaudeCodeTools(body), systemPrompt)

    override fun restoreToolResponse(sourceProvider: ModelProvider, wireProvider: ModelProvider,
        requestBody: JsonObject, responseBody: JsonObject): JsonObject =
        restoreClaudeCodeToolNames(requestBody, responseBody)
}
