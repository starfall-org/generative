package org.starfall.generative.shared.subfeatures.modelpicker.logic

import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup

private const val MODEL_LABEL_PREFIX_LIMIT = 20

/** Keep the model's final path segment visible when a namespace or prefix is unusually long. */
internal fun compactModelLabel(value: String): String {
    val slash = value.indexOf('/')
    if (slash <= MODEL_LABEL_PREFIX_LIMIT) return value
    val prefix = value.substring(0, slash)
    return "${prefix.first()}...${prefix.takeLast(5)}/${value.substring(slash + 1)}"
}

sealed interface ModelPickerItem {
    data class Group(
        val group: ProviderGroup,
        val providerCount: Int
    ) : ModelPickerItem

    data object Ungrouped : ModelPickerItem

    data class Provider(
        val provider: ModelProvider,
        val modelCount: Int,
        val depth: Int,
        val hasSelectedModel: Boolean = false
    ) : ModelPickerItem

    data class Model(
        val provider: ModelProvider,
        val modelId: String,
        val config: ModelConfiguration,
        val isSelected: Boolean,
        val depth: Int
    ) : ModelPickerItem
}

internal val ModelPickerItem.sectionKey: String?
    get() = when (this) {
        is ModelPickerItem.Group -> "folder_${group.id}"
        ModelPickerItem.Ungrouped -> "ungrouped"
        else -> null
    }

internal data class ModelPickerTab(val key: String, val label: String)

/** Initial position is based on the current selection, never a previously saved folder. */
internal fun initialModelPickerItemIndex(
    items: List<ModelPickerItem>,
    selectedProviderId: String,
    selectedModelId: String
): Int {
    if (selectedProviderId.isBlank()) return 0
    if (selectedModelId.isNotBlank()) {
        val modelIndex = items.indexOfFirst {
            it is ModelPickerItem.Model && it.provider.id == selectedProviderId && it.modelId == selectedModelId
        }
        if (modelIndex >= 0) return modelIndex
    }
    // A collapsed provider hides its models but remains the correct navigation target.
    return items.indexOfFirst {
        it is ModelPickerItem.Provider && it.provider.id == selectedProviderId
    }.coerceAtLeast(0)
}

internal val chatModelPickerFilter: (ModelProvider, String, ModelConfiguration) -> Boolean = { _, _, config ->
    config.modelType == org.starfall.generative.shared.model.language.ModelType.TEXT_GENERATION
}

fun computeModelPickerItems(
    providers: List<ModelProvider>,
    providerGroups: List<ProviderGroup>,
    dynamicModelsMap: Map<String, List<String>>,
    selectedProviderId: String,
    selectedModelId: String,
    query: String = "",
    providerFilterId: String? = null,
    collapsedGroupIds: Set<String> = emptySet(),
    collapsedProviderIds: Set<String> = emptySet(),
    expandSearchResults: Boolean = true,
    selectedFolderId: String? = null,
    ungroupedOnly: Boolean = false,
    modelFilter: (ModelProvider, String, ModelConfiguration) -> Boolean = { _, _, _ -> true }
): List<ModelPickerItem> {
    data class ProviderNode(val provider: ModelProvider, val models: List<String>)

    val defaultConfig = ModelConfiguration()
    val normalizedQuery = query.trim()
    val groupById = providerGroups.associateBy { it.id }
    val providerOrder = compareBy<ModelProvider> { it.sortOrder }
        .thenBy { it.id }
    val orderedProviders = when {
        selectedFolderId != null -> providers
            .filter { it.groupId == selectedFolderId }
            .sortedWith(providerOrder)
        ungroupedOnly -> providers
            .filter { it.groupId == null || it.groupId !in groupById }
            .sortedWith(providerOrder)
        else -> buildList {
            providerGroups
                .sortedWith(compareBy<ProviderGroup> { it.sortOrder }.thenBy { it.name.lowercase() })
                .forEach { group ->
                    addAll(providers.filter { it.groupId == group.id }.sortedWith(providerOrder))
                }
            addAll(
                providers
                    .filter { it.groupId == null || it.groupId !in groupById }
                    .sortedWith(providerOrder)
            )
        }
    }
    val visibleProviders = orderedProviders.mapNotNull { provider ->
        if (provider.isHidden || (providerFilterId != null && provider.id != providerFilterId)) return@mapNotNull null

        val groupMatches = provider.groupId
            ?.let(groupById::get)
            ?.name
            ?.contains(normalizedQuery, ignoreCase = true) == true
        val providerMatches = provider.name.contains(normalizedQuery, ignoreCase = true)
        val models = providerModels(provider, dynamicModelsMap, selectedProviderId, selectedModelId)
            .filter { modelId ->
                modelFilter(provider, modelId, provider.config.modelConfigs[modelId] ?: defaultConfig)
            }
            .filter { modelId ->
                normalizedQuery.isBlank() ||
                    groupMatches ||
                    providerMatches ||
                    modelId.contains(normalizedQuery, ignoreCase = true) ||
                    provider.config.modelConfigs[modelId]?.displayName.orEmpty()
                        .contains(normalizedQuery, ignoreCase = true)
            }

        if (models.isEmpty()) null else ProviderNode(provider, models)
    }

    val forceExpanded = (normalizedQuery.isNotBlank() && expandSearchResults) || providerFilterId != null
    fun modelItems(node: ProviderNode, depth: Int): List<ModelPickerItem.Model> =
        node.models.map { modelId ->
            val config = node.provider.config.modelConfigs[modelId] ?: defaultConfig
            // Model IDs are scoped to a provider. Display names are presentation-only and may
            // legitimately be shared by multiple models, so selection must use both IDs exactly.
            val isSelected = selectedProviderId.isNotBlank() &&
                selectedModelId.isNotBlank() &&
                node.provider.id == selectedProviderId &&
                modelId == selectedModelId
            ModelPickerItem.Model(node.provider, modelId, config, isSelected, depth)
        }

    val groupCounts = visibleProviders.groupingBy { it.provider.groupId }.eachCount()
    return buildList {
        var previousSection: String? = null
        visibleProviders.forEach { node ->
            val group = node.provider.groupId?.let(groupById::get)
            val section = group?.let { "folder_${it.id}" } ?: "ungrouped"
            if (section != previousSection) {
                if (group != null) {
                    add(ModelPickerItem.Group(group, groupCounts[group.id] ?: 0))
                } else {
                    add(ModelPickerItem.Ungrouped)
                }
                previousSection = section
            }
            val models = modelItems(node, depth = 1)
            add(ModelPickerItem.Provider(
                node.provider,
                node.models.size,
                depth = 0,
                hasSelectedModel = models.any { it.isSelected }
            ))
            // The provider row already indicates selection; a collapsed provider hides every card.
            if (forceExpanded || node.provider.id !in collapsedProviderIds) addAll(models)
        }
    }
}

private fun providerModels(
    provider: ModelProvider,
    dynamicModelsMap: Map<String, List<String>>,
    selectedProviderId: String,
    selectedModelId: String
): List<String> = (
        provider.config.modelIds
            ?: ((dynamicModelsMap[provider.id]
                ?: defaultProviderModels(provider.type)) + provider.config.modelConfigs.keys)
        )
    .plus(if (provider.id == selectedProviderId && selectedModelId.isNotBlank()) listOf(selectedModelId) else emptyList())
    .distinct()


internal fun defaultProviderModels(type: ModelApiType): List<String> = emptyList()

/** Tabs navigate to section headers in the currently filtered model list. */
internal fun computeModelPickerTabs(items: List<ModelPickerItem>, ungroupedLabel: String): List<ModelPickerTab> =
    items.mapNotNull { item ->
        when (item) {
            is ModelPickerItem.Group -> ModelPickerTab("folder_${item.group.id}", item.group.name)
            ModelPickerItem.Ungrouped -> ModelPickerTab("ungrouped", ungroupedLabel)
            else -> null
        }
    }
