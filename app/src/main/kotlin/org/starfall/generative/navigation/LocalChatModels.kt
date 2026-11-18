package org.starfall.generative.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.starfall.generative.integrations.modelapis.ollama.OllamaModelCache
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider

/** Resolves the local backend's discovered model catalogs before handing them to chat. */
@Composable
internal fun rememberLocalChatModels(
    providers: List<ModelProvider>,
    foreground: Boolean,
    fetch: suspend (String) -> List<String>,
): Map<String, List<String>> {
    val context = LocalContext.current
    val cache = remember(context) { OllamaModelCache(context) }
    val models = remember { mutableStateMapOf<String, List<String>>() }
    val endpoints = remember { mutableMapOf<String, String>() }
    val dynamicProviders = providers.filter { it.type == ModelApiType.OLLAMA && it.config.modelIds == null }
        .map { it.id to it.baseUrl }.sortedBy { it.first }
    val latestFetch by rememberUpdatedState(fetch)
    LaunchedEffect(dynamicProviders, foreground) {
        if (!foreground) return@LaunchedEffect
        val ids = dynamicProviders.map { it.first }.toSet()
        models.keys.retainAll(ids)
        endpoints.keys.retainAll(ids)
        dynamicProviders.forEach { (id, url) ->
            if (endpoints[id] == url && models.containsKey(id)) return@forEach
            endpoints[id] = url
            models.remove(id)
            cache.read(url)?.let { models[id] = it.models }
        }
        coroutineScope {
            dynamicProviders.forEach { (id, url) ->
                launch { models[id] = latestFetch(url) }
            }
        }
    }
    return models.toMap()
}

/** Providers currently supported by the app's direct media implementation. */
internal fun supportedDirectMediaProviders(providers: List<ModelProvider>): List<ModelProvider> =
    providers.filter { it.type in setOf(ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES, ModelApiType.GOOGLE) }
