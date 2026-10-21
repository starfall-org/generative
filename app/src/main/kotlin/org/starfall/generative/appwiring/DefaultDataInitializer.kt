package org.starfall.generative.appwiring

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.starfall.generative.shared.icons.IconStore
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.McpProtocol
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.isContentApiName
import org.starfall.generative.shared.storage.McpRepository
import org.starfall.generative.shared.storage.ModelRepository
import org.starfall.generative.shared.storage.SpeechRepository
import org.starfall.generative.shared.storage.preferences.AppPreferencesRepository

/** Runs once per process using provider/MCP metadata only; auth repairs belong in migrations. */
class DefaultDataInitializer(
    private val modelRepo: ModelRepository,
    private val mcpRepo: McpRepository,
    private val speechRepo: SpeechRepository,
    private val prefsRepo: AppPreferencesRepository,
    private val icons: IconStore,
) {
    suspend fun initialize() = withContext(Dispatchers.IO) {
        modelRepo.allProviders.first().let { currentProviders ->
            modelRepo.rememberExistingModelConfigurations(currentProviders)
            if (currentProviders.isEmpty()) {
                initDefaultProviders()
            } else {
                val existingOllama = currentProviders.find { it.type == ModelApiType.OLLAMA }
                if (existingOllama != null && (
                        existingOllama.baseUrl.contains("108.181.196.208") ||
                        existingOllama.baseUrl.contains("10.0.2.2") ||
                        existingOllama.baseUrl.contains("localhost")
                    )) {
                    modelRepo.saveProvider(
                        existingOllama.copy(
                            name = "Ollama",
                            baseUrl = "https://ollama.com/api"
                        )
                    )
                }
            }
        }

        val prefs = prefsRepo.appPreferencesFlow.first()
        if (!prefs.mcpPresetsInitialized) {
            initDefaultMcpServers()
            prefsRepo.setMcpPresetsInitialized(true)
        }
        // This has its own migration marker so existing installations receive it exactly once.
        // Deleting the preset is respected because the marker remains set.
        if (!prefs.contentApiPresetInitialized) {
            val exists = mcpRepo.allServers.first().any { it.name.isContentApiName() }
            if (!exists) {
                mcpRepo.saveServer(McpInfo(
                    id = "content-api",
                    name = "Content API",
                    protocol = McpProtocol.STREAMABLE_HTTP,
                    url = null
                ))
            }
            prefsRepo.setContentApiPresetInitialized(true)
        }

        speechRepo.allServices.first().let { currentServices ->
            if (currentServices.isEmpty()) {
                initDefaultSpeechServices()
            }
        }

        pruneUnusedIcons(prefs.featuresLogoImage)
    }

    private suspend fun pruneUnusedIcons(featuresLogoImage: String?) {
        val entityImages = buildList {
            // The Features logo is stored in app preferences rather than a provider/model entity.
            // Keep its app-owned file during startup pruning so it survives process restarts.
            add(featuresLogoImage)
            modelRepo.allProviders.first().forEach { provider ->
                add(provider.icon)
                provider.config.modelConfigs.values.forEach { add(it.icon) }
            }
            modelRepo.allGroups.first().forEach { add(it.icon) }
            mcpRepo.allServers.first().forEach { add(it.icon) }
        }
        icons.prune(entityImages)
    }

    private suspend fun initDefaultProviders() {
        // No default provider presets
    }

    private suspend fun initDefaultMcpServers() {
        // No default MCP server presets
    }

    private suspend fun initDefaultSpeechServices() {
        val systemTts = SpeechService(
            id = "system_tts",
            name = "Android System TTS",
            provider = "system",
            voice = "Default",
            speed = 1.0f,
            pitch = 1.0f
        )
        speechRepo.saveService(systemTts)
    }

}
