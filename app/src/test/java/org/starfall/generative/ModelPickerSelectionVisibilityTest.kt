package org.starfall.generative

import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.providers.shared.ui.*
import org.starfall.generative.shared.subfeatures.modelpicker.logic.*
import org.starfall.generative.shared.subfeatures.modelpicker.ui.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.subfeatures.modelpicker.logic.ModelPickerItem
import org.starfall.generative.shared.subfeatures.modelpicker.logic.computeModelPickerItems
import org.starfall.generative.shared.subfeatures.modelpicker.logic.computeModelPickerTabs
import org.starfall.generative.shared.subfeatures.modelpicker.logic.initialModelPickerItemIndex
import org.starfall.generative.shared.model.language.withConversationReasoning

class ModelPickerSelectionVisibilityTest {
    private val group = ProviderGroup("folder", "Work")
    private val selected = ModelProvider("selected", "Selected provider", ModelApiType.OPENAI,
        baseUrl = "", groupId = group.id,
        config = ProviderConfiguration(modelIds = listOf("alpha", "beta")))
    private val other = selected.copy(id = "other")

    @Test fun selectedModelRetainsItsProviderInFolderTabAtEveryCollapseCombination() {
        for (folderCollapsed in listOf(false, true)) {
            for (providerCollapsed in listOf(false, true)) {
                val items = computeModelPickerItems(listOf(selected, other, other.copy(id = "root", groupId = null)),
                    listOf(group), emptyMap(),
                    selected.id, "alpha",
                    collapsedGroupIds = if (folderCollapsed) setOf(group.id) else emptySet(),
                    collapsedProviderIds = if (providerCollapsed) setOf(selected.id) else emptySet(),
                    selectedFolderId = group.id)
                assertTrue(items.any { it is ModelPickerItem.Group && it.group.id == group.id })
                val providerIndex = items.indexOfFirst {
                    it is ModelPickerItem.Provider && it.provider.id == selected.id
                }
                assertTrue(providerIndex >= 0)
                val provider = items[providerIndex] as ModelPickerItem.Provider
                assertEquals(selected.id, provider.provider.id)
                assertEquals(group.id, provider.provider.groupId)
                assertEquals(0, provider.depth)
                assertTrue(provider.hasSelectedModel)
                val visibleSelectedModels = items.filterIsInstance<ModelPickerItem.Model>()
                    .filter { it.provider.id == selected.id }
                if (providerCollapsed) {
                    assertTrue(visibleSelectedModels.isEmpty())
                } else {
                    assertEquals(listOf("alpha", "beta"), visibleSelectedModels.map { it.modelId })
                    assertTrue(visibleSelectedModels.first().isSelected)
                    assertEquals(1, visibleSelectedModels.first().depth)
                }
                assertEquals(if (providerCollapsed) 5 else 7, items.size)
            }
        }
    }

    @Test fun hiddenProvidersAreAbsentFromPickerEvenWhenSelectedOrSearched() {
        val hidden = selected.copy(isHidden = true)
        val visible = other.copy(id = "visible", groupId = null)
        val providers = listOf(hidden, visible)

        fun entries(query: String = "", folderId: String? = null) = computeModelPickerItems(
            providers, listOf(group), emptyMap(), hidden.id, "alpha",
            query = query, selectedFolderId = folderId
        )

        assertEquals(listOf(visible.id),
            entries().filterIsInstance<ModelPickerItem.Provider>().map { it.provider.id })
        assertTrue(entries().filterIsInstance<ModelPickerItem.Model>().none { it.provider.id == hidden.id })
        assertFalse(entries().any { it is ModelPickerItem.Group && it.group.id == group.id })
        assertTrue(entries("Selected provider").isEmpty())
        assertTrue(entries(folderId = group.id).isEmpty())
        assertEquals(0, initialModelPickerItemIndex(entries(), hidden.id, "alpha"))

        val unhidden = computeModelPickerItems(
            listOf(hidden.copy(isHidden = false), visible), listOf(group), emptyMap(),
            hidden.id, "alpha"
        )
        assertTrue(unhidden.any { it is ModelPickerItem.Model && it.provider.id == hidden.id })
    }

    @Test fun selectingAModelDoesNotReorderProvidersInAnyTab() {
        val first = selected.copy(id = "first", sortOrder = 0)
        val last = selected.copy(id = "last", sortOrder = 1)
        val rootFirst = first.copy(id = "root-first", groupId = null)
        val rootLast = last.copy(id = "root-last", groupId = null)
        val providers = listOf(last, rootLast, first, rootFirst)

        fun providerIds(selectedId: String, folderId: String? = null, ungrouped: Boolean = false) =
            computeModelPickerItems(
                providers, listOf(group), emptyMap(), selectedId, "alpha",
                selectedFolderId = folderId, ungroupedOnly = ungrouped
            ).filterIsInstance<ModelPickerItem.Provider>().map { it.provider.id }

        assertEquals(listOf("first", "last", "root-first", "root-last"), providerIds(last.id))
        assertEquals(providerIds(first.id), providerIds(last.id))
        assertEquals(listOf("first", "last"), providerIds(last.id, folderId = group.id))
        assertEquals(listOf("root-first", "root-last"), providerIds(rootLast.id, ungrouped = true))
    }

    @Test fun sectionHeadersMatchTheVisibleProvidersInOneList() {
        val second = ProviderGroup("second", "Second", sortOrder = 2)
        val first = group.copy(sortOrder = 1)
        val firstProvider = selected.copy(id = "first", groupId = first.id)
        val secondProvider = selected.copy(id = "second", groupId = second.id)
        val ungrouped = selected.copy(id = "root", groupId = "missing")
        val providers = listOf(ungrouped, secondProvider, firstProvider)

        fun items(query: String = "") = computeModelPickerItems(
            providers, listOf(second, first), emptyMap(), "second", "alpha", query = query
        )

        val rows = items()
        assertEquals(listOf("first", "second", "root"),
            rows.filterIsInstance<ModelPickerItem.Provider>().map { it.provider.id })
        assertEquals(listOf("folder_folder", "folder_second", "ungrouped"),
            computeModelPickerTabs(rows, "Ungrouped").map { it.key })
        assertEquals(listOf("folder", "second"), rows.filterIsInstance<ModelPickerItem.Group>().map { it.group.id })
        assertTrue(rows.any { it is ModelPickerItem.Ungrouped })
        assertEquals(listOf("second"), items("Second").filterIsInstance<ModelPickerItem.Group>().map { it.group.id })
        assertTrue(items("no match").isEmpty())
    }

    @Test fun collapsedRootProviderHidesAllModelsAndExpansionRestoresThem() {
        val root = selected.copy(groupId = null)
        val items = computeModelPickerItems(listOf(root), emptyList(), emptyMap(), root.id, "beta",
            collapsedProviderIds = setOf(root.id))
        assertTrue(items[0] is ModelPickerItem.Ungrouped)
        assertEquals(0, (items[1] as ModelPickerItem.Provider).depth)
        assertTrue((items[1] as ModelPickerItem.Provider).hasSelectedModel)
        assertTrue(items.none { it is ModelPickerItem.Model })
        assertEquals(2, items.size)
        val expanded = computeModelPickerItems(listOf(root), emptyList(), emptyMap(), root.id, "beta")
        assertEquals(listOf("alpha", "beta"), expanded.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
    }

    @Test fun pickerStartsAtSelectedModelOrItsCollapsedProviderNotLastFolder() {
        val first = selected.copy(id = "early", groupId = group.id, sortOrder = 0)
        val laterGroup = ProviderGroup("later", "Later", sortOrder = 1)
        val current = selected.copy(id = "chosen", groupId = laterGroup.id)
        val providers = listOf(first, current)
        val groups = listOf(group, laterGroup)
        val expanded = computeModelPickerItems(providers, groups, emptyMap(), current.id, "beta")
        val modelIndex = expanded.indexOfFirst {
            it is ModelPickerItem.Model && it.provider.id == current.id && it.modelId == "beta"
        }
        assertTrue(modelIndex > 0)
        assertEquals(modelIndex, initialModelPickerItemIndex(expanded, current.id, "beta"))

        val collapsed = computeModelPickerItems(providers, groups, emptyMap(), current.id, "beta",
            collapsedProviderIds = setOf(current.id))
        val providerIndex = collapsed.indexOfFirst {
            it is ModelPickerItem.Provider && it.provider.id == current.id
        }
        assertTrue(providerIndex > 0)
        assertEquals(providerIndex, initialModelPickerItemIndex(collapsed, current.id, "beta"))
        assertEquals(providerIndex, initialModelPickerItemIndex(collapsed, current.id, "missing"))
        assertEquals(0, initialModelPickerItemIndex(expanded, "", ""))
        assertEquals(0, initialModelPickerItemIndex(expanded, "unknown-provider", "beta"))
    }

    @Test fun offIsSeparateFromDefaultAndDoesNotRemoveThinkingCapability() {
        assertFalse(reasoningEffortEnabled("none"))
        assertFalse(reasoningEffortEnabled(" OFF "))
        assertTrue(reasoningEffortEnabled(null))
        val options = reasoningEffortOptions()
        assertEquals("none", options.first().effort)
        assertEquals("None", options.first().label)
        assertTrue(options.any { it.effort == null })
        assertEquals(1, reasoningEffortIndex(null))
        val model = ModelConfiguration(supportsThinking = true, reasoningEffort = "high")
        assertTrue(model.withConversationReasoning("none").supportsThinking)
        assertEquals("high", model.reasoningEffort)
        assertEquals(model, model.withConversationReasoning(null))
    }

    @Test fun sameNamedModelUnderAnotherProviderIsNotMarkedSelected() {
        val items = computeModelPickerItems(listOf(selected, other), listOf(group), emptyMap(), selected.id, "alpha")
        val marked = items.filterIsInstance<ModelPickerItem.Model>().filter { it.isSelected }
        assertEquals(listOf(selected.id to "alpha"), marked.map { it.provider.id to it.modelId })
    }

    @Test fun sameDisplayNameWithinProviderDoesNotMarkSiblingModelSelected() {
        val provider = selected.copy(
            config = ProviderConfiguration(
                modelIds = listOf("alpha", "beta"),
                modelConfigs = mapOf(
                    "alpha" to ModelConfiguration(displayName = "alpha"),
                    "beta" to ModelConfiguration(displayName = "alpha")
                )
            )
        )
        val items = computeModelPickerItems(listOf(provider), listOf(group), emptyMap(), provider.id, "alpha")
        val marked = items.filterIsInstance<ModelPickerItem.Model>().filter { it.isSelected }

        assertEquals(listOf(provider.id to "alpha"), marked.map { it.provider.id to it.modelId })
    }

    @Test fun selectionDoesNotLeakToSameNamedModelsWhenSelectedProviderIsNotVisible() {
        val elsewhere = other.copy(groupId = null)
        val items = computeModelPickerItems(listOf(selected, elsewhere), listOf(group), emptyMap(), selected.id, "alpha",
            ungroupedOnly = true)
        assertTrue(items.filterIsInstance<ModelPickerItem.Model>().isNotEmpty())
        assertTrue(items.filterIsInstance<ModelPickerItem.Model>().none { it.isSelected })
    }
}
