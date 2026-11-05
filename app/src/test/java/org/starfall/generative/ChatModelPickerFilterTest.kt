package org.starfall.generative

import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.providers.shared.ui.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.subfeatures.modelpicker.logic.ModelPickerItem
import org.starfall.generative.shared.subfeatures.modelpicker.logic.chatModelPickerFilter
import org.starfall.generative.shared.subfeatures.modelpicker.logic.computeModelPickerItems

class ChatModelPickerFilterTest {
    private val configs = ModelType.entries.associate { it.name to ModelConfiguration(modelType = it, displayName = "Shared Name") }
    private val mixed = ModelProvider("mixed", "Mixed", ModelApiType.OPENAI, baseUrl = "", groupId = "g",
        config = ProviderConfiguration(modelIds = configs.keys.toList(), modelConfigs = configs))
    private val mediaOnly = mixed.copy(id = "media", name = "Media", groupId = null,
        config = ProviderConfiguration(modelIds = listOf("image"), modelConfigs = mapOf("image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION))))

    @Test fun chatFilterKeepsOnlyChatModelsAndRemovesEmptyProvidersEvenWhenSearching() {
        for (query in listOf("", "Shared", "Mixed", "Work")) {
            val rows = computeModelPickerItems(listOf(mixed, mediaOnly), listOf(ProviderGroup("g", "Work")), emptyMap(),
                selectedProviderId = "mixed", selectedModelId = ModelType.IMAGE_GENERATION.name,
                query = query, modelFilter = chatModelPickerFilter)
            assertEquals(listOf(ModelType.TEXT_GENERATION.name), rows.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
            assertFalse(rows.filterIsInstance<ModelPickerItem.Model>().any { it.isSelected })
            assertEquals(listOf("mixed"), rows.filterIsInstance<ModelPickerItem.Provider>().map { it.provider.id })
            assertEquals(1, rows.filterIsInstance<ModelPickerItem.Provider>().single().modelCount)
        }
    }

    @Test fun legacyUnconfiguredModelsAreChatAndSelectedMediaModelsRemainFiltered() {
        val legacy = mixed.copy(groupId = null, config = ProviderConfiguration(modelConfigs = mapOf(
            "image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION))))
        val rows = computeModelPickerItems(listOf(legacy), emptyList(), mapOf("mixed" to listOf("chat", "image")),
            "mixed", "image", modelFilter = chatModelPickerFilter)
        assertEquals(listOf("chat"), rows.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
    }
}
