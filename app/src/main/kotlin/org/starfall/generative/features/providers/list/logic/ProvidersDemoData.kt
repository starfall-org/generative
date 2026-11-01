package org.starfall.generative.features.providers.list.logic

import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem

/** Disposable, entirely in-memory data used by the actual ProvidersScreen in Developer Options. */
internal data class ProvidersDemoData(
    val providers: List<ModelProvider>,
    val groups: List<ProviderGroup>
)

internal fun sampleProvidersDemoData(): ProvidersDemoData {
    val groups = listOf(
        ProviderGroup("demo-long", "Long folder (60)", sortOrder = 0),
        ProviderGroup("demo-second", "Second folder (30)", sortOrder = 1),
        ProviderGroup("demo-small", "Small folder (6)", sortOrder = 2),
        ProviderGroup("demo-empty", "Empty folder", sortOrder = 3)
    )
    fun provider(id: String, name: String, folder: String?, order: Int) = ModelProvider(
        id = id,
        name = name,
        type = ModelApiType.OPENAI,
        baseUrl = "",
        groupId = folder,
        sortOrder = order
    )
    val root = (0 until 20).map { i ->
        provider("demo-root-$i", "Root provider ${i + 1}", null, i + groups.size)
    }
    val long = (0 until 60).map { i ->
        provider("demo-long-$i", "Long folder item ${i + 1}", groups[0].id, i)
    }
    val second = (0 until 30).map { i ->
        provider("demo-second-$i", "Second folder item ${i + 1}", groups[1].id, i)
    }
    val small = (0 until 6).map { i ->
        provider("demo-small-$i", "Small folder item ${i + 1}", groups[2].id, i)
    }
    return ProvidersDemoData(root + long + second + small, groups)
}

/** Apply the identical placement payload emitted by ProvidersScreen; never touch storage. */
internal fun ProvidersDemoData.place(placement: ProviderPlacement): ProvidersDemoData {
    val byId = providers.associateBy { it.id }.toMutableMap()
    val moving = byId[placement.providerId] ?: return this
    byId[placement.providerId] = moving.copy(groupId = placement.groupId)
    val ordered = buildList {
        placement.rootOrder.forEach { entry ->
            if (entry.isGroup) {
                placement.groupOrders[entry.id].orEmpty().forEach { id ->
                    byId[id]?.takeIf { it.groupId == entry.id }?.let(::add)
                }
            } else {
                byId[entry.id]?.takeIf { it.groupId == null }?.let(::add)
            }
        }
    }
    val seen = ordered.mapTo(hashSetOf()) { it.id }
    val all = ordered + providers.mapNotNull { byId[it.id]?.takeIf { candidate -> candidate.id !in seen } }
    val rootOrder = placement.rootOrder.withIndex().associate { it.value to it.index }
    val memberOrder = placement.groupOrders.mapValues { (_, ids) -> ids.withIndex().associate { it.value to it.index } }
    return copy(
        providers = all.map { provider ->
            provider.copy(sortOrder = provider.groupId?.let { memberOrder[it]?.get(provider.id) }
                ?: rootOrder[ProviderRootOrderItem(provider.id, false)] ?: provider.sortOrder)
        },
        groups = groups.map { group ->
            group.copy(sortOrder = rootOrder[ProviderRootOrderItem(group.id, true)] ?: group.sortOrder)
        }
    )
}

internal fun ProvidersDemoData.reorderRoot(order: List<ProviderRootOrderItem>): ProvidersDemoData {
    val ranks = order.withIndex().associate { it.value to it.index }
    return copy(
        providers = providers.map { p ->
            if (p.groupId == null) p.copy(sortOrder = ranks[ProviderRootOrderItem(p.id, false)] ?: p.sortOrder)
            else p
        },
        groups = groups.map { g -> g.copy(sortOrder = ranks[ProviderRootOrderItem(g.id, true)] ?: g.sortOrder) }
    )
}

internal fun ProvidersDemoData.reorderMembers(ids: List<String>): ProvidersDemoData {
    val ranked = ids.withIndex().associate { it.value to it.index }
    val selected = providers.filter { it.id in ranked }.sortedBy { ranked[it.id] }
    if (selected.isEmpty()) return this
    val membership = selected.first().groupId
    val unchanged = providers.filterNot { it.id in ranked }
    return copy(providers = unchanged + selected.mapIndexed { index, p ->
        p.copy(groupId = membership, sortOrder = index)
    })
}
