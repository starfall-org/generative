package org.starfall.generative.features.serverconnection.logic

import kotlinx.serialization.json.JsonObject
import org.starfall.generative.integrations.opencode.OpenCodeCatalogEntry
import org.starfall.generative.integrations.opencode.openCodeMcpImportConfig
import org.starfall.generative.integrations.opencode.openCodeProviderConfig
import org.starfall.generative.integrations.opencode.toMcp
import org.starfall.generative.integrations.opencode.toProvider
import org.starfall.generative.integrations.opencode.withMcp
import org.starfall.generative.integrations.opencode.withProvider
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.provider.ModelProvider

internal class ServerCatalogOperations(
    private val service: ServerConnections,
    private val connectionId: String,
    private val openCode: Boolean,
    private val directory: String?,
) {
    internal class ProviderEdit(
        val provider: ModelProvider,
        val isNew: Boolean,
        val authorization: OpenCodeProviderAuthorization? = null,
        internal var configuration: OpenCodeCatalogEntry? = null,
    )

    internal class McpEdit(
        val server: McpInfo,
        val isNew: Boolean,
        internal val configuration: OpenCodeCatalogEntry? = null,
        internal val runtimeDraft: Boolean = false,
    )

    internal data class ProviderSave(
        val closeEditor: Boolean,
        val reloaded: Boolean = true,
        val updateDraft: Boolean = false,
    )

    internal data class McpSave(val draft: McpInfo?, val reloaded: Boolean = true)

    suspend fun openProvider(provider: ModelProvider, integrationId: String?): ProviderEdit {
        if (integrationId != null) {
            val authorization = OpenCodeProviderAuthorization.load(service, connectionId, integrationId)
            return ProviderEdit(authorization.initialProvider(provider), isNew = false, authorization = authorization)
        }
        val entry = service.providerConfigEntry(connectionId, provider.id)
        return ProviderEdit(entry.toProvider(provider), isNew = false, configuration = entry)
    }

    suspend fun saveProvider(edit: ProviderEdit, draft: ModelProvider, keyAnswers: JsonObject?): ProviderSave {
        edit.authorization?.let { authorization ->
            if (draft.auth.method != AuthMethod.OAUTH) authorization.connectKey(draft, keyAnswers)
            return ProviderSave(closeEditor = false)
        }

        if (!openCode) {
            service.saveImportedProvider(connectionId, draft)
            return ProviderSave(closeEditor = true)
        }

        if (!edit.isNew) {
            val entry = requireNotNull(edit.configuration)
            val updated = entry.withProvider(entry.toProvider(edit.provider), draft)
            val reloaded = service.changeCatalogConfigEntry(connectionId, entry, updated)
            edit.configuration = entry.copy(value = updated)
            return ProviderSave(closeEditor = false, reloaded = reloaded, updateDraft = true)
        }

        require(draft.auth.method in setOf(AuthMethod.PLATFORM_DEFAULT, AuthMethod.NONE))
        val endpoint = draft.baseUrl.ifBlank {
            if (draft.type == ModelApiType.OPENAI) ModelApiType.OPENAI_RESPONSES.defaultBaseUrl
            else draft.type.defaultBaseUrl
        }
        val config = requireNotNull(openCodeProviderConfig(
            draft.name, draft.type, endpoint, draft.auth.token,
            draft.config.modelIds.orEmpty(), draft.config.modelConfigs.mapValues { it.value.displayName },
        ))
        val target = service.providerConfigFile(connectionId, directory = null)
        val reloaded = service.addConfiguredProvider(connectionId, target, draft.id, config, directory = null)
        return ProviderSave(closeEditor = true, reloaded = reloaded)
    }

    suspend fun deleteProvider(id: String, edit: ProviderEdit?): Boolean? {
        if (!openCode) return null
        val entry = edit?.takeIf { it.provider.id == id }?.configuration?.takeIf { it.path.last() == id }
        return service.deleteCatalogProvider(connectionId, id, entry)
    }

    suspend fun openMcp(server: McpInfo): McpEdit {
        val entry = service.mcpConfigEntry(connectionId, server.id, directory)
        return McpEdit(entry.toMcp(), isNew = false, configuration = entry)
    }

    suspend fun saveMcp(edit: McpEdit, draft: McpInfo): McpSave {
        if (!openCode) {
            service.saveImportedMcp(connectionId, draft)
            return McpSave(draft)
        }

        val entry = edit.configuration
        if (entry != null && !edit.isNew) {
            val newKey = draft.name.takeUnless { it == edit.server.id }
            if (newKey != null) requireAvailableMcpName(newKey)
            val reloaded = service.changeCatalogConfigEntry(
                connectionId, entry, entry.withMcp(edit.server, draft), newKey, directory,
            )
            return McpSave(draft = null, reloaded = reloaded)
        }

        if (edit.isNew) requireAvailableMcpName(draft.name)
        else require(draft.name == edit.server.id && edit.runtimeDraft)
        service.addMcp(connectionId, draft.name, openCodeMcpImportConfig(draft), directory)
        return McpSave(draft.copy(id = draft.name))
    }

    private suspend fun requireAvailableMcpName(name: String) {
        require(service.mcpServers(connectionId, directory).orEmpty().none { it.id == name })
    }
}
