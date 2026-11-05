package org.starfall.generative.database.repositories

import org.starfall.generative.shared.state.ImmediateState
import org.starfall.generative.shared.state.upsert

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.subfeatures.icons.logic.IconStore
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.language.LanguageModel
import org.starfall.generative.shared.model.language.Capabilities
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderAuxiliaryChange
import org.starfall.generative.shared.model.provider.withAuxiliaryChange
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.model.provider.ProviderModels
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem
import org.starfall.generative.shared.model.provider.oauthAccountsWithCurrent
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.model.provider.recordOAuthAccount
import org.starfall.generative.shared.model.provider.removeOAuthAccount
import org.starfall.generative.shared.model.provider.reorderedModels
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ProviderGroupEntity
import org.starfall.generative.database.room.entities.ProviderCatalogModelEntity
import org.starfall.generative.database.room.entities.ProviderCatalogToolEntity
import kotlinx.serialization.json.JsonObject

/** Repair reports individual failures instead of letting one poisoned row stop other repairs. */
data class ProviderRepairResult(val repaired: Int, val failedIds: List<String>)

class ModelRepository(private val db: AppDatabase, private val service: AiService, private val icons: IconStore? = null) {
    suspend fun testConnection(provider: ModelProvider) = service.testConnection(resolveProvider(provider))
    suspend fun testModel(provider: ModelProvider, modelId: String) = service.testModel(resolveProvider(provider), modelId)
    suspend fun fetchProviderModelCatalog(provider: ModelProvider) = service.fetchProviderModelCatalog(resolveProvider(provider))
    suspend fun fetchProviderModels(provider: ModelProvider) = service.fetchProviderModels(resolveProvider(provider))
    suspend fun fetchOllamaModels(baseUrl: String) = service.fetchOllamaModels(baseUrl)
    internal fun rememberExistingModelConfigurations(providers: List<ModelProvider>) {
        providers.flatMap { it.config.modelConfigs.entries }
            .sortedBy { if (it.value.modelType == ModelType.TEXT_GENERATION) 1 else 0 }
            .forEach { (id, config) -> service.modelConfigurationMemory.remember(id, config, onlyIfMissing = true) }
    }

    private val providerDao = db.modelProviderDao()
    private val groupDao = db.providerGroupDao()

    private val structuredJsonDao = db.structuredJsonDao()
    private val mapper = ProviderStorageMapper(db)
    // Migration-aware token cleanup must never occupy the single Room mutation writer.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun credentialSlots(provider: ModelProvider) =
        (provider.oauthAccountsWithCurrent().map { it.id } + provider.id + provider.oauthCredentialId).distinct()

    private fun queueCredentialCleanup(provider: ModelProvider) {
        cleanupScope.launch {
            credentialSlots(provider).forEach { id ->
                runCatching { service.clearAccountCredentials(provider.type, id) }
                    .onFailure { Log.w("ModelRepository", "Could not clear retired OAuth slot for ${provider.id}", it) }
            }
        }
    }

    private data class Catalog(val providers: List<ModelProvider>, val groups: List<ProviderGroup>)
    private val providerOrder = compareBy<ModelProvider> { it.sortOrder }.thenBy { it.id }

    /** Compact the destination order before appending, including legacy Int.MAX_VALUE ranks. */
    private fun Catalog.appendProvider(provider: ModelProvider): Catalog {
        val destination = buildList {
            providers.filter { it.id != provider.id && it.groupId == provider.groupId }
                .forEach { add(Triple(it.id, false, it.sortOrder)) }
            if (provider.groupId == null) groups.forEach { add(Triple(it.id, true, it.sortOrder)) }
        }.sortedWith(compareBy<Triple<String, Boolean, Int>> { it.third }.thenBy { if (it.second) 0 else 1 }.thenBy { it.first })
        val providerRanks = destination.mapIndexedNotNull { index, item -> if (item.second) null else item.first to index }.toMap()
        val groupRanks = destination.mapIndexedNotNull { index, item -> if (item.second) item.first to index else null }.toMap()
        val placed = provider.copy(sortOrder = destination.size)
        return copy(
            providers = providers.filterNot { it.id == provider.id }.map { p ->
                providerRanks[p.id]?.let { p.copy(sortOrder = it) } ?: p
            }.plus(placed).sortedWith(providerOrder),
            groups = groups.map { g -> groupRanks[g.id]?.let { g.copy(sortOrder = it) } ?: g }.sortedBy { it.sortOrder }
        )
    }

    private fun Catalog.saveProviderDraft(provider: ModelProvider): Catalog {
        val previous = providers.firstOrNull { it.id == provider.id }
        return if ((previous == null && provider.sortOrder == Int.MAX_VALUE) ||
            (previous != null && previous.groupId != provider.groupId)) appendProvider(provider)
        else copy(providers = providers.upsert(provider) { it.id }.sortedWith(providerOrder))
    }

    private suspend fun readCatalog() = db.withTransaction { Catalog(
        providerDao.getAllProviders().map { entity ->
            try { mapper.metadata(entity) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("ModelRepository", "Using unreadable provider placeholder ${entity.id}", e)
                mapper.unreadableMetadata(entity)
            }
        },
        groupDao.getAllGroupsOnce().map { ProviderGroup(it.id, it.name, it.sortOrder, it.icon) }
    ) }

    private suspend fun persistDestinationOrder(catalog: Catalog, groupId: String?) {
        catalog.providers.filter { it.groupId == groupId }.forEach {
            providerDao.updateGroupAndSortOrder(it.id, groupId, it.sortOrder)
        }
        if (groupId == null) catalog.groups.forEach { groupDao.updateSortOrder(it.id, it.sortOrder) }
    }

    private val state = ImmediateState(load = { readCatalog() })
    val allProviders: Flow<List<ModelProvider>> = state.flow.map { it.providers }.distinctUntilChanged()
    val allGroups: Flow<List<ProviderGroup>> = state.flow.map { it.groups }.distinctUntilChanged()

    suspend fun getProviderById(id: String): ModelProvider? = withContext(Dispatchers.IO) {
        db.withTransaction { providerDao.getProviderById(id)?.let { mapper.selected(it) } }
    }

    /** Resolve only this record's secrets, retaining the caller's public configuration edits. */
    suspend fun resolveProvider(provider: ModelProvider): ModelProvider = when (provider.credentialState) {
        CredentialLoadState.LOADED, CredentialLoadState.NOT_CONFIGURED -> provider
        CredentialLoadState.NOT_REQUESTED -> withContext(Dispatchers.IO) {
            db.withTransaction {
                val entity = providerDao.getProviderById(provider.id)
                    ?: error("Provider ${provider.id} no longer exists. Reload the provider list.")
                mapper.resolve(provider, entity)
            }
        }
        CredentialLoadState.ERROR, CredentialLoadState.LOADING -> error("Provider ${provider.id} credentials are ${provider.credentialState}. Reload before use.")
    }

    suspend fun loadProvidersForExport(): List<ModelProvider> = withContext(Dispatchers.IO) {
        db.withTransaction { providerDao.getAllProviders().map { mapper.selected(it) } }
    }

    internal fun exportAccountCredentials(): Map<String, Map<String, JsonObject>> = service.exportAccountCredentials()

    internal fun importAccountCredentials(credentials: Map<String, Map<String, JsonObject>>) {
        service.importAccountCredentials(credentials)
    }

    internal suspend fun awaitModelConfigurationMemoryWrites() = service.awaitModelConfigurationMemoryWrites()

    internal fun exportModelConfigurationMemory(): Map<String, JsonObject> = service.exportModelConfigurationMemory()

    internal fun importModelConfigurationMemory(values: Map<String, JsonObject>) {
        service.importModelConfigurationMemory(values)
    }

    suspend fun saveGroup(group: ProviderGroup) {
        state.mutate({ it.copy(groups = it.groups.upsert(group.copy(name = group.name.trim())) { g -> g.id }.sortedBy { g -> g.sortOrder }) }) {
            db.withTransaction { persistGroup(group.copy(name = group.name.trim())) }
        }
    }

    private suspend fun persistGroup(group: ProviderGroup) {
        if (!db.updateStorageRow("provider_groups", group.id, "name" to group.name, "sortOrder" to group.sortOrder, "icon" to group.icon)) {
            groupDao.insertOrUpdate(ProviderGroupEntity(group.id, group.name, group.sortOrder, group.icon))
        }
    }

    suspend fun deleteGroup(id: String) {
        state.mutate({ catalog ->
            val rootMax = maxOf(catalog.groups.filterNot { it.id == id }.maxOfOrNull { it.sortOrder } ?: -1,
                catalog.providers.filter { it.groupId == null }.maxOfOrNull { it.sortOrder } ?: -1)
            val children = catalog.providers.filter { it.groupId == id }.sortedBy { it.sortOrder }.map { it.id }
            catalog.copy(groups = catalog.groups.filterNot { it.id == id },
                providers = catalog.providers.map { p -> if (p.groupId == id) p.copy(groupId = null, sortOrder = rootMax + children.indexOf(p.id) + 1) else p })
        }) {
            db.withTransaction {
                val children = providerDao.getProvidersByGroup(id)
                val rootMax = maxOf(
                    groupDao.getAllGroupsOnce().filterNot { it.id == id }.maxOfOrNull { it.sortOrder } ?: -1,
                    providerDao.getUngroupedProviders().maxOfOrNull { it.sortOrder } ?: -1
                )
                children.forEachIndexed { index, provider ->
                    providerDao.updateGroupAndSortOrder(provider.id, null, rootMax + index + 1)
                }
                groupDao.deleteById(id)
            }
        }
    }

    suspend fun moveProviderToGroup(providerId: String, groupId: String?) {
        state.mutate({ catalog ->
            val provider = catalog.providers.firstOrNull { it.id == providerId }
            if (provider == null || provider.groupId == groupId) catalog
            else catalog.appendProvider(provider.copy(groupId = groupId))
        }) {
            db.withTransaction {
                val catalog = readCatalog()
                val provider = catalog.providers.firstOrNull { it.id == providerId }
                if (provider != null && provider.groupId != groupId) {
                    persistDestinationOrder(catalog.appendProvider(provider.copy(groupId = groupId)), groupId)
                }
            }
        }
    }

    private fun Catalog.placeProvider(placement: ProviderPlacement): Catalog {
        if (providers.none { it.id == placement.providerId } ||
            (placement.groupId != null && groups.none { it.id == placement.groupId })) return this
        var members = providers.map { if (it.id == placement.providerId) it.copy(groupId = placement.groupId) else it }
        val actualRoot = providerRootOrder(members, groups)
        val root = placement.rootOrder.filter { it in actualRoot }.distinct() + actualRoot.filter { it !in placement.rootOrder }
        val rootRanks = root.withIndex().associate { it.value to it.index }
        members = members.map { p ->
            rootRanks[ProviderRootOrderItem(p.id, false)]?.let { p.copy(sortOrder = it) } ?: p
        }
        placement.groupOrders.forEach { (groupId, requested) ->
            val actual = members.filter { it.groupId == groupId }.sortedWith(providerOrder).map { it.id }
            val order = requested.filter { it in actual }.distinct() + actual.filter { it !in requested }
            val ranks = order.withIndex().associate { it.value to it.index }
            members = members.map { p -> if (p.groupId == groupId) p.copy(sortOrder = ranks.getValue(p.id)) else p }
        }
        return copy(providers = members.sortedWith(providerOrder), groups = groups.map { g ->
            g.copy(sortOrder = rootRanks.getValue(ProviderRootOrderItem(g.id, true)))
        }.sortedBy { it.sortOrder })
    }

    private fun providerRootOrder(providers: List<ModelProvider>, groups: List<ProviderGroup>): List<ProviderRootOrderItem> =
        (groups.map { Triple(ProviderRootOrderItem(it.id, true), it.sortOrder, 0) } +
            providers.filter { it.groupId == null }.map { Triple(ProviderRootOrderItem(it.id, false), it.sortOrder, 1) })
            .sortedWith(compareBy<Triple<ProviderRootOrderItem, Int, Int>> { it.second }.thenBy { it.third }.thenBy { it.first.id })
            .map { it.first }

    /** The user-facing drop is accepted in RAM now; the repository writer persists later. */
    fun queueProviderPlacement(placement: ProviderPlacement) =
        state.enqueue({ it.placeProvider(placement) }) {
            db.withTransaction {
                val updated = readCatalog().placeProvider(placement)
                updated.providers.forEach { providerDao.updateGroupAndSortOrder(it.id, it.groupId, it.sortOrder) }
                updated.groups.forEach { groupDao.updateSortOrder(it.id, it.sortOrder) }
            }
        }

    suspend fun placeProvider(placement: ProviderPlacement) {
        state.mutate({ it.placeProvider(placement) }) {
            db.withTransaction {
                val updated = readCatalog().placeProvider(placement)
                updated.providers.forEach { providerDao.updateGroupAndSortOrder(it.id, it.groupId, it.sortOrder) }
                updated.groups.forEach { groupDao.updateSortOrder(it.id, it.sortOrder) }
            }
        }
    }

    fun queueReorderRootItems(items: List<ProviderRootOrderItem>): kotlinx.coroutines.Deferred<Unit> {
        // The drop callback runs on the UI thread. Index each destination once rather
        // than repeatedly scanning the whole root order for every provider and folder.
        val order = items.toList()
        val providerRanks = order.withIndex().filterNot { it.value.isGroup }
            .associate { it.value.id to it.index }
        val groupRanks = order.withIndex().filter { it.value.isGroup }
            .associate { it.value.id to it.index }
        return state.enqueue({ catalog -> catalog.copy(
            providers = catalog.providers.map { p ->
                providerRanks[p.id]?.let { p.copy(sortOrder = it) } ?: p
            }.sortedWith(providerOrder),
            groups = catalog.groups.map { g ->
                groupRanks[g.id]?.let { g.copy(sortOrder = it) } ?: g
            }.sortedBy { it.sortOrder })
        }) {
            db.withTransaction {
                order.forEachIndexed { index, item ->
                    if (item.isGroup) groupDao.updateSortOrder(item.id, index)
                    else providerDao.updateSortOrder(item.id, index)
                }
            }
        }
    }

    suspend fun reorderRootItems(items: List<ProviderRootOrderItem>) {
        state.mutate({ catalog -> catalog.copy(
            providers = catalog.providers.map { p -> items.indexOfFirst { !it.isGroup && it.id == p.id }.takeIf { it >= 0 }?.let { p.copy(sortOrder = it) } ?: p },
            groups = catalog.groups.map { g -> items.indexOfFirst { it.isGroup && it.id == g.id }.takeIf { it >= 0 }?.let { g.copy(sortOrder = it) } ?: g })
        }) {
            db.withTransaction {
                items.forEachIndexed { index, item ->
                    if (item.isGroup) groupDao.updateSortOrder(item.id, index)
                    else providerDao.updateSortOrder(item.id, index)
                }
            }
        }
    }

    suspend fun reorderGroups(ids: List<String>) {
        state.mutate({ catalog -> catalog.copy(groups = catalog.groups.map { g -> ids.indexOf(g.id).takeIf { it >= 0 }?.let { g.copy(sortOrder = it) } ?: g }.sortedBy { it.sortOrder }) }) {
            db.withTransaction {
                ids.forEachIndexed { index, id -> groupDao.updateSortOrder(id, index) }
            }
        }
    }

    private fun mergeOAuthAuthorization(persisted: ModelProvider?, authorized: ModelProvider): ModelProvider {
        val accounts = (persisted?.takeIf { it.type == authorized.type }?.oauthAccountsWithCurrent().orEmpty() +
            authorized.config.oauthAccounts).associateBy { it.id }.values.toList()
        return (persisted ?: authorized).copy(type = authorized.type, baseUrl = authorized.baseUrl, auth = authorized.auth,
            config = (persisted?.config ?: authorized.config).copy(oauthAccounts = accounts,
                multipleApiKeys = authorized.config.multipleApiKeys,
                codexClientVersion = authorized.config.codexClientVersion,
                antigravityVersion = authorized.config.antigravityVersion,
                antigravityChangelist = authorized.config.antigravityChangelist,
                antigravityProjectIds = authorized.config.antigravityProjectIds),
            credentialState = CredentialLoadState.LOADED)
    }

    suspend fun authorizeProvider(provider: ModelProvider): Result<ModelProvider> = runCatching {
        service.withOAuthSession {
            val authorized = service.authorizeProvider(resolveProvider(provider)).getOrThrow().recordOAuthAccount()
                .copy(credentialState = CredentialLoadState.LOADED)
            var saved = authorized
            var obsolete: ModelProvider? = null
            state.mutate({ catalog ->
                val updated = authorized.metadata()
                catalog.copy(providers = catalog.providers.upsert(updated) { it.id }.sortedWith(providerOrder))
            }) {
                db.withTransaction {
                    val persisted = providerForCredentialCleanup(provider.id)
                    if (persisted != null && persisted.type != authorized.type) obsolete = persisted
                    // Token storage now uses this same Room database. Never call its synchronous
                    // runBlocking bridge while holding an outer Room transaction.
                    saved = mergeOAuthAuthorization(persisted, authorized)
                    mapper.persist(saved)
                }
            }
            // Old provider OAuth slots are cleaned up after the database transaction commits.
            // This must not run under db.withTransaction (nested Room bridge deadlock).
            obsolete?.let(::queueCredentialCleanup)
            authorized.copy(config = authorized.config.copy(oauthAccounts = saved.config.oauthAccounts))
        }
    }

    suspend fun clearOAuthCredentials(provider: ModelProvider): Result<ModelProvider> = runCatching {
        val resolved = resolveProvider(provider)
        val accountId = resolved.oauthCredentialId
        // Blocking migration-backed token removal must not run on the repository's writer.
        withContext(Dispatchers.IO) { service.clearAccountCredentials(resolved.type, accountId) }
        val cleared = resolved.removeOAuthAccount(accountId)
        val visibleMetadata = cleared.metadata()
        state.mutate({ catalog ->
            catalog.copy(providers = catalog.providers.map { current ->
                if (current.id == provider.id && current.type == cleared.type) current.copy(
                    auth = visibleMetadata.auth,
                    config = current.config.copy(oauthAccounts = visibleMetadata.config.oauthAccounts)
                ) else current
            })
        }) {
            db.withTransaction {
                providerDao.getProviderById(provider.id)?.let { mapper.selected(it) }?.let { persisted ->
                    if (persisted.type == resolved.type) mapper.persist(persisted.removeOAuthAccount(accountId))
                }
            }
        }
        cleared
    }

    /** Credential replacement/deletion remains possible when the old encrypted record is damaged. */
    private suspend fun providerForCredentialCleanup(id: String): ModelProvider? = providerDao.getProviderById(id)?.let { entity ->
        try { mapper.selected(entity) } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            try { mapper.metadata(entity) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mapper.unreadableMetadata(entity) }
        }
    }

    /** Repair only unreadable records; healthy provider rows, models and accounts are untouched. */
    suspend fun repairCorruptProviders(): ProviderRepairResult = withContext(Dispatchers.IO) {
        var repaired = 0
        val failed = mutableListOf<String>()
        val rows = providerDao.getAllProviders()
        for (entity in rows) {
            val broken = try { mapper.selected(entity); false }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { true }
            if (!broken) continue
            try {
                var resetCredentials = false
                var repairedMetadata: ModelProvider? = null
                state.mutate({ catalog ->
                    repairedMetadata?.let { updated ->
                        catalog.copy(providers = catalog.providers.upsert(updated) { it.id }.sortedWith(providerOrder))
                    } ?: catalog
                }) {
                    db.withTransaction {
                        val row = providerDao.getProviderById(entity.id) ?: return@withTransaction
                        // Remove only malformed document graphs; never overwrite healthy model rows.
                        // All healthy model configuration rows and catalog entries survive.
                        mapper.deleteMalformedDocuments(row.id)
                        val metadata = mapper.metadata(row)
                        if (row.authId != null) {
                            val authBroken = try { mapper.resolve(metadata, row); false }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { true }
                            if (authBroken) {
                                db.updateStorageRow("model_providers", row.id, "authId" to null)
                                val type = metadata.type
                                if (type.isAccountProvider) {
                                    structuredJsonDao.replaceDocument(StructuredJsonDocument(
                                        "provider", row.id, "auth_method", JsonPrimitive(AuthMethod.OAUTH.name)))
                                }
                                db.deleteUnreferencedEndpointAuth("provider_auth", "model_providers", row.authId)
                                resetCredentials = true
                            }
                        }
                        val repaired = providerDao.getProviderById(entity.id)
                        check(repaired?.let { mapper.selected(it) } != null) {
                            "Unable to repair provider ${entity.id}"
                        }
                        repairedMetadata = mapper.metadata(requireNotNull(repaired))
                    }
                }
                if (resetCredentials) {
                    // Never access synchronous token storage from inside a Room transaction.
                    queueCredentialCleanup(mapper.unreadableMetadata(entity))
                }
                repaired++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failed += entity.id
                Log.e("ModelRepository", "Repair failed for provider ${entity.id}; scanning continues", e)
            }
        }
        ProviderRepairResult(repaired, failed)
    }

    suspend fun providerExists(id: String): Boolean = providerDao.getProviderById(id) != null

    suspend fun saveProvider(draft: ModelProvider) {
        val provider = draft
        requireSavableCredentials(provider.credentialState)
        state.mutate({ it.saveProviderDraft(provider.metadata()) }) {
            var obsolete: ModelProvider? = null
            val previous = db.withTransaction {
                val catalog = readCatalog()
                val previous = catalog.providers.firstOrNull { it.id == provider.id }
                if (provider.credentialState != CredentialLoadState.NOT_REQUESTED && previous != null) {
                    // Only a credential edit needs the old OAuth account references.
                    providerForCredentialCleanup(provider.id)?.let { old ->
                        if (old.type != provider.type || (old.auth.method == AuthMethod.OAUTH && provider.auth.method != AuthMethod.OAUTH)) {
                            obsolete = old
                        }
                    }
                }
                val updated = catalog.saveProviderDraft(provider)
                persistDestinationOrder(updated, provider.groupId)
                mapper.persist(updated.providers.first { it.id == provider.id })
                previous
            }
            obsolete?.let(::queueCredentialCleanup)
            cleanupScope.launch {
                provider.config.modelConfigs.forEach { (id, config) ->
                    if (previous?.config?.modelConfigs?.get(id) != config) {
                        runCatching { service.modelConfigurationMemory.remember(id, config) }
                            .onFailure { Log.w("ModelRepository", "Could not remember model template $id", it) }
                    }
                }
            }
        }
    }

    suspend fun setProviderHidden(providerId: String, hidden: Boolean) {
        state.mutate({ catalog -> catalog.copy(providers = catalog.providers.map { provider ->
            if (provider.id == providerId) provider.copy(isHidden = hidden) else provider
        }) }) {
            db.withTransaction {
                if (providerDao.getProviderById(providerId) != null) mapper.persistHidden(providerId, hidden)
            }
        }
    }

    /** Persist one key/header action on the latest stored provider, not the editor's draft. */
    fun queueProviderAuxiliaryChange(providerId: String, change: ProviderAuxiliaryChange) = state.enqueue(
        { catalog -> catalog.copy(providers = catalog.providers.map { provider ->
            if (provider.id == providerId) provider.withAuxiliaryChange(change).metadata() else provider
        }) },
        {
            db.withTransaction {
                val current = providerDao.getProviderById(providerId)?.let { mapper.selected(it) }
                    ?: return@withTransaction
                val updated = current.withAuxiliaryChange(change)
                if (updated != current) mapper.persist(updated)
            }
        }
    )

    /** Restore the exported layout directly; editor saves append providers when their group changes. */
    suspend fun importProviders(providers: List<ModelProvider>, groups: List<ProviderGroup>?) {
        val importedProviders = providers
        val importedGroups = groups?.map { it.copy(name = it.name.trim()) }
        val importedIds = importedProviders.mapTo(hashSetOf()) { it.id }
        importedProviders.forEach { requireSavableCredentials(it.credentialState) }
        state.mutate({ it.mergeImport(importedProviders.map { p -> p.metadata() }, importedGroups) }) {
            val changes = db.withTransaction {
                val previous = readCatalog()
                val updated = previous.mergeImport(importedProviders, importedGroups)
                importedGroups.orEmpty().forEach {
                    persistGroup(it)
                }
                val previousById = previous.providers.associateBy { it.id }
                val importedChanges = mutableListOf<Pair<ModelProvider?, ModelProvider>>()
                updated.providers.filter { it.id in importedIds }.forEach { provider ->
                    val old = previousById[provider.id]?.let {
                        if (provider.credentialState == CredentialLoadState.NOT_REQUESTED) it else providerForCredentialCleanup(provider.id)
                    }
                    mapper.persist(provider)
                    importedChanges += old to provider
                }
                importedChanges
            }
            changes.forEach { (previous, provider) ->
                if (previous != null && (previous.type != provider.type ||
                        (previous.auth.method == AuthMethod.OAUTH && provider.auth.method != AuthMethod.OAUTH))) {
                    queueCredentialCleanup(previous)
                }
                cleanupScope.launch {
                    provider.config.modelConfigs.forEach { (id, config) ->
                        if (previous?.config?.modelConfigs?.get(id) != config) {
                            runCatching { service.modelConfigurationMemory.remember(id, config) }
                                .onFailure { Log.w("ModelRepository", "Could not remember imported model $id", it) }
                        }
                    }
                }
            }
        }
    }

    /** Replaces the complete provider catalog during a full application restore. */
    suspend fun replaceAll(providers: List<ModelProvider>, groups: List<ProviderGroup>) {
        providerDao.getAllProviders().map { it.id }.forEach { deleteProvider(it) }
        groupDao.getAllGroupsOnce().map { it.id }.forEach { deleteGroup(it) }
        importProviders(providers, groups)
    }

    private fun Catalog.mergeImport(importedProviders: List<ModelProvider>, importedGroups: List<ProviderGroup>?): Catalog {
        val mergedGroups = (groups + importedGroups.orEmpty()).associateBy { it.id }.values.toList()
        val groupIds = mergedGroups.mapTo(hashSetOf()) { it.id }
        val previousById = providers.associateBy { it.id }
        val restored = importedProviders.map { provider ->
            val previous = previousById[provider.id]
            when {
                // Old backups cannot restore a complete layout. Preserve the local organization
                // for existing providers rather than overwriting it with missing/stale group IDs.
                importedGroups == null && previous != null -> provider.copy(groupId = previous.groupId, sortOrder = previous.sortOrder)
                provider.groupId != null && provider.groupId !in groupIds -> provider.copy(groupId = null)
                else -> provider
            }
        }
        return Catalog(
            providers = (providers + restored).associateBy { it.id }.values.sortedWith(providerOrder),
            groups = mergedGroups.sortedWith(compareBy<ProviderGroup> { it.sortOrder }.thenBy { it.id })
        )
    }

    private fun Catalog.withSavedModels(providerId: String, modelIds: List<String>, modelConfigs: Map<String, org.starfall.generative.shared.model.language.ModelConfiguration>) =
        copy(providers = providers.map { provider ->
            if (provider.id == providerId) provider.copy(config = provider.config.copy(
                modelIds = modelIds, modelConfigs = modelConfigs)) else provider
        })

    private suspend fun persistSavedModels(providerId: String, modelIds: List<String>, modelConfigs: Map<String, org.starfall.generative.shared.model.language.ModelConfiguration>) {
        db.withTransaction {
            check(providerDao.getProviderById(providerId) != null) { "Provider $providerId no longer exists" }
            mapper.persistModels(providerId, modelIds, modelConfigs)
        }
    }

    /** Used by the editor's background queue; even the RAM catalog projection runs off Main. */
    fun queueSaveProviderModels(providerId: String, modelConfigs: Map<String, org.starfall.generative.shared.model.language.ModelConfiguration>): kotlinx.coroutines.Deferred<Unit> {
        val modelIds = modelConfigs.keys.toList()
        return state.enqueue({ it.withSavedModels(providerId, modelIds, modelConfigs) }) {
            persistSavedModels(providerId, modelIds, modelConfigs)
        }
    }

    /** Awaitable counterpart for callers that need to confirm the database commit. */
    suspend fun saveProviderModels(providerId: String, modelConfigs: Map<String, org.starfall.generative.shared.model.language.ModelConfiguration>) {
        val modelIds = modelConfigs.keys.toList()
        state.mutate({ it.withSavedModels(providerId, modelIds, modelConfigs) }) {
            persistSavedModels(providerId, modelIds, modelConfigs)
        }
    }

    fun queueReorderProviderModels(providerId: String, modelIds: List<String>) =
        state.enqueue({ catalog -> catalog.copy(providers = catalog.providers.map {
            if (it.id == providerId) it.copy(config = it.config.reorderedModels(modelIds)) else it
        }) }) {
            db.withTransaction {
                val entity = providerDao.getProviderById(providerId) ?: return@withTransaction
                val provider = mapper.metadata(entity)
                val updated = provider.config.reorderedModels(modelIds)
                mapper.persistModels(providerId, updated.modelIds, updated.modelConfigs)
            }
        }

    suspend fun reorderProviderModels(providerId: String, modelIds: List<String>) {
        state.mutate({ catalog -> catalog.copy(providers = catalog.providers.map { if (it.id == providerId) it.copy(config = it.config.reorderedModels(modelIds)) else it }) }) {
            db.withTransaction {
                val entity = providerDao.getProviderById(providerId) ?: return@withTransaction
                val provider = mapper.metadata(entity)
                val updated = provider.config.reorderedModels(modelIds)
                mapper.persistModels(providerId, updated.modelIds, updated.modelConfigs)
            }
        }
    }

    /**
     * Delete by primary key without deserializing or decrypting the record being deleted.
     * Auth/JSON payloads may be corrupt; none may hold a Room transaction hostage.
     * The RAM catalog drops the row immediately while the background writer persists it.
     */
    suspend fun deleteProvider(id: String) {
        state.mutate({ it.copy(providers = it.providers.filterNot { p -> p.id == id }) }) {
            var old: ModelProvider? = null
            db.withTransaction {
                providerDao.getProviderById(id)?.let { entity ->
                    old = try { mapper.selected(entity) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { mapper.unreadableMetadata(entity) }
                    mapper.deleteDocumentsByOwner(id)
                    providerDao.deleteById(id)
                    entity.authId?.let { db.deleteUnreferencedEndpointAuth("provider_auth", "model_providers", it) }
                    // Delete current OAuth token rows using the *same transaction*, not via
                    // synchronous AccountTokenStore (which would deadlock on this database).
                    val provider = requireNotNull(old)
                    val namespace = "generative.${provider.type.name.lowercase()}.oauth"
                    credentialSlots(provider).forEach { accountId ->
                        db.oauthTokenDao().remove(namespace, accountId)
                    }
                }
            }
            // Legacy DataStore slots are cleaned asynchronously after the DB commit.
            // One failed migration can neither resurrect the provider nor block other writes.
            old?.let(::queueCredentialCleanup)
        }
    }

    fun queueReorderProviders(ids: List<String>) {
        val positions = ids.withIndex().associate { it.value to it.index }
        state.enqueue({ catalog -> catalog.copy(providers = catalog.providers.map { p ->
            positions[p.id]?.let { p.copy(sortOrder = it) } ?: p
        }.sortedBy { it.sortOrder }) }) {
            db.withTransaction { ids.forEachIndexed { index, id -> providerDao.updateSortOrder(id, index) } }
        }
    }

    suspend fun reorderProviders(ids: List<String>) {
        state.mutate({ catalog -> catalog.copy(providers = catalog.providers.map { p -> ids.indexOf(p.id).takeIf { it >= 0 }?.let { p.copy(sortOrder = it) } ?: p }.sortedBy { it.sortOrder }) }) {
            db.withTransaction {
                ids.forEachIndexed { index, id -> providerDao.updateSortOrder(id, index) }
            }
        }
    }

    suspend fun getModelsForProvider(providerId: String): ProviderModels? = withContext(Dispatchers.IO) {
        val entities = providerDao.getCatalogModels(providerId)
        if (entities.isEmpty() && structuredJsonDao.getDocument("provider_catalog", providerId, "cache_state") == null) return@withContext null
        val tools = providerDao.getCatalogTools(providerId).groupBy { it.modelOrdinal }
        ProviderModels(providerId, entities.sortedBy { it.ordinal }.map { model ->
            LanguageModel(
                id = model.modelId,
                displayName = model.displayName,
                icon = model.icon,
                providerId = model.catalogProviderId,
                inputCapabilities = Capabilities(model.inputText, model.inputImage, model.inputVideo, model.inputEmbed, model.inputAudio, model.inputOthers),
                outputCapabilities = Capabilities(model.outputText, model.outputImage, model.outputVideo, model.outputEmbed, model.outputAudio, model.outputOthers),
                modelTools = tools[model.ordinal].orEmpty().sortedBy { it.ordinal }.map { it.toolName }
            )
        })
    }

    suspend fun saveModelsForProvider(providerModels: ProviderModels) = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (providerDao.getProviderById(providerModels.id) == null) return@withTransaction
            providerDao.deleteCatalogTools(providerModels.id)
            providerDao.deleteCatalogModels(providerModels.id)
            providerModels.models.forEachIndexed { ordinal, model ->
                providerDao.insertCatalogModels(listOf(ProviderCatalogModelEntity(
                    providerId = providerModels.id,
                    ordinal = ordinal,
                    modelId = model.id,
                    displayName = model.displayName,
                    icon = model.icon,
                    catalogProviderId = model.providerId,
                    inputText = model.inputCapabilities.text,
                    inputImage = model.inputCapabilities.image,
                    inputVideo = model.inputCapabilities.video,
                    inputEmbed = model.inputCapabilities.embed,
                    inputAudio = model.inputCapabilities.audio,
                    inputOthers = model.inputCapabilities.others,
                    outputText = model.outputCapabilities.text,
                    outputImage = model.outputCapabilities.image,
                    outputVideo = model.outputCapabilities.video,
                    outputEmbed = model.outputCapabilities.embed,
                    outputAudio = model.outputCapabilities.audio,
                    outputOthers = model.outputCapabilities.others
                )))
                providerDao.insertCatalogTools(model.modelTools.mapIndexed { toolOrdinal, tool ->
                    ProviderCatalogToolEntity(providerModels.id, ordinal, toolOrdinal, tool)
                })
            }
            structuredJsonDao.replaceDocument(StructuredJsonDocument("provider_catalog", providerModels.id, "cache_state", JsonPrimitive("READY")))
        }
    }
}
