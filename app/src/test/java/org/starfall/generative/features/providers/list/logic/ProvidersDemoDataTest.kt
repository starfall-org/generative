package org.starfall.generative.features.providers.list.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem

class ProvidersDemoDataTest {
    @Test fun sampleProvidesLargeFoldersAndRootItemsWithoutUserRecords() {
        val data = sampleProvidersDemoData()
        assertEquals(4, data.groups.size)
        assertEquals(116, data.providers.size)
        assertEquals(60, data.providers.count { it.groupId == "demo-long" })
        assertEquals(30, data.providers.count { it.groupId == "demo-second" })
        assertEquals(20, data.providers.count { it.groupId == null })
        assertTrue(data.providers.all { it.id.startsWith("demo-") })
    }

    @Test fun dropAcrossFoldersUpdatesMembershipAndOrderInMemory() {
        val before = sampleProvidersDemoData()
        val root = providerRootItems(before.providers, before.groups).map { it.toOrderItem() }
        val longIds = before.providers.filter { it.groupId == "demo-long" }.map { it.id }
        val secondIds = before.providers.filter { it.groupId == "demo-second" }.map { it.id }
        val placement = ProviderPlacement(
            providerId = "demo-long-59", groupId = "demo-second", rootOrder = root,
            groupOrders = mapOf(
                "demo-long" to longIds.dropLast(1),
                "demo-second" to listOf("demo-long-59") + secondIds,
                "demo-small" to before.providers.filter { it.groupId == "demo-small" }.map { it.id },
                "demo-empty" to emptyList()
            )
        )
        val after = before.place(placement)
        assertEquals("demo-second", after.providers.first { it.id == "demo-long-59" }.groupId)
        assertEquals("demo-long-59", after.providers.first { it.groupId == "demo-second" }.id)
        assertEquals(59, after.providers.count { it.groupId == "demo-long" })
        assertEquals(31, after.providers.count { it.groupId == "demo-second" })
        assertEquals(60, before.providers.count { it.groupId == "demo-long" })
        assertFalse(after.providers === before.providers)
    }

    @Test fun rootReorderAndFolderReorderRemainIndependent() {
        val original = sampleProvidersDemoData()
        val order = providerRootItems(original.providers, original.groups).map { it.toOrderItem() }.reversed()
        val reordered = original.reorderRoot(order)
        assertEquals(order.indexOf(ProviderRootOrderItem("demo-empty", true)),
            reordered.groups.first { it.id == "demo-empty" }.sortOrder)
        val members = original.providers.filter { it.groupId == "demo-long" }.map { it.id }.reversed()
        val changed = reordered.reorderMembers(members)
        assertEquals(members, changed.providers.filter { it.groupId == "demo-long" }.map { it.id })
        assertEquals(20, changed.providers.count { it.groupId == null })
    }
}
