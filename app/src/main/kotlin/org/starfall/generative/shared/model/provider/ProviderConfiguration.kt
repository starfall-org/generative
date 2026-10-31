package org.starfall.generative.shared.model.provider

import org.starfall.generative.shared.model.language.ModelConfiguration

import kotlinx.serialization.Serializable

@Serializable
data class ProviderConfiguration(
    val httpProxy: Map<String, String> = emptyMap(),
    val socksProxy: Map<String, String> = emptyMap(),
    val supportStream: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val responsesApi: Boolean = false,
    val customListModelsUrl: String? = null,
    val customEndpoints: Map<String, String> = emptyMap(),
    val maxTokens: Int = 4000,
    // Model IDs are scoped to this provider, including custom gateway models.
    val modelConfigs: Map<String, ModelConfiguration> = emptyMap(),
    // null preserves legacy discovery; an empty list explicitly selects no models.
    val modelIds: List<String>? = null,
    val multipleApiKeys: Boolean = false,
    val apiKeys: List<ProviderApiKey> = emptyList(),
    val oauthAccounts: List<ProviderOAuthAccount> = emptyList(),
    // null/blank uses the current Codex default; ignored by other provider types.
    val codexClientVersion: String? = null,
    // Antigravity's build identity; blank selects the verified fallback or omits an unknown CL.
    val antigravityVersion: String? = null,
    val antigravityChangelist: String? = null,
    // Optional per-credential project overrides; blank/absent entries use discovery/onboarding.
    val antigravityProjectIds: Map<String, String> = emptyMap(),
    /** Optional project ID override per Gemini CLI OAuth credential. */
    val geminiCliProjectIds: Map<String, String> = emptyMap(),
    // Only used by Anthropic Messages providers; on by default.
    val anthropicPromptCache: AnthropicPromptCache = AnthropicPromptCache()
)
