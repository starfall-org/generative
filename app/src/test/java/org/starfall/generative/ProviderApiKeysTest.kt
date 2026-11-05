package org.starfall.generative

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.providers.shared.model.ProviderApiKeysState
import org.starfall.generative.features.providers.shared.model.maskedProviderApiKey
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderApiKey
import org.starfall.generative.shared.model.provider.ProviderAuxiliaryChange
import org.starfall.generative.shared.model.provider.withAuxiliaryChange
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.auth.requestHeaders
import org.starfall.generative.shared.model.auth.bearerHeaderValue

class ProviderApiKeysTest {
    private val identity: (String) -> String = { bearerHeaderValue("Authorization", it) }

    @Test fun existingExternalKeyIsImportedOnceAndMatchesBearerFormatting() {
        val state = ProviderApiKeysState("first-secret-key", true, emptyList())
        state.ensureCurrent(identity)
        val selected = state.selectedId(identity)
        state.currentKey = "Bearer first-secret-key"
        state.ensureCurrent(identity)
        assertEquals(1, state.entries.size)
        assertEquals(selected, state.selectedId(identity))
        state.currentKey = "another-secret-key"
        state.ensureCurrent(identity)
        assertEquals(2, state.entries.size)
        assertNotEquals(selected, state.selectedId(identity))
        assertEquals("first-secret-key", state.entries.first().value)
    }

    @Test fun editingAndDeletingActiveKeyKeepsExternalFieldInSync() {
        val state = ProviderApiKeysState("active-key", true, listOf(
            ProviderApiKey("a", "Primary", "active-key"), ProviderApiKey("b", "Backup", "backup-key")))
        state.save("a", " Renamed ", " updated-key ", identity)
        assertEquals("updated-key", state.currentKey)
        assertEquals("a", state.selectedId(identity))
        assertEquals("Renamed", state.entries.first().label)
        state.save("b", "Backup", "new-backup-key", identity)
        assertEquals("updated-key", state.currentKey)
        state.delete("a", identity)
        assertEquals("new-backup-key", state.currentKey)
        assertEquals("b", state.selectedId(identity))
        state.delete("b", identity)
        assertEquals("", state.currentKey)
        assertNull(state.selectedId(identity))
    }

    @Test fun addingKeyPreservesSelectionAndRejectsBlankAndDuplicateKeys() {
        val state = ProviderApiKeysState("selected-key", true, emptyList())
        state.ensureCurrent(identity)
        val selected = state.selectedId(identity)
        state.save(null, "Backup", "backup-key", identity)
        assertEquals(selected, state.selectedId(identity))
        assertTrue(runCatching { state.save(null, "", "  ", identity) }.isFailure)
        assertTrue(runCatching { state.save(null, "Duplicate", "Bearer selected-key", identity) }.isFailure)
        assertEquals(2, state.entries.size)
        state.currentKey = ""
        state.save(null, "First", "first-key", identity)
        assertEquals("first-key", state.currentKey)
    }

    @Test fun savedKeysAndPreferenceRoundTripWithoutChangingActiveAuthorization() {
        val legacy = Json.decodeFromString<ProviderConfiguration>("""{"headers":{"X-Client":"app"}}""")
        assertFalse(legacy.multipleApiKeys)
        assertTrue(legacy.apiKeys.isEmpty())
        val provider = ModelProvider("p", "Provider", ModelApiType.OPENAI,
            baseUrl = "https://example.com/v1", auth = Authorization(value = "active-key"),
            config = legacy.copy(multipleApiKeys = true, apiKeys = listOf(
                ProviderApiKey("a", "", "active-key"), ProviderApiKey("b", "Backup", "backup-key"))))
        val restored = Json.decodeFromString<ModelProvider>(Json.encodeToString(ModelProvider.serializer(), provider))
        assertEquals(provider, restored)
        assertEquals(mapOf("Authorization" to "Bearer active-key"), restored.auth.requestHeaders(restored.type))
        val state = ProviderApiKeysState(restored.auth.token, restored.config.multipleApiKeys, restored.config.apiKeys)
        assertEquals("a", state.selectedId(identity))
    }

    @Test fun auxiliaryChangesAreAdditiveAndPreserveUnrelatedProviderFields() {
        val original = ModelProvider("id", "Original", ModelApiType.OPENAI, baseUrl = "https://example.com",
            auth = Authorization(AuthMethod.BEARER_TOKEN, "Authorization", "Bearer first"),
            config = ProviderConfiguration(headers = mapOf("X-Existing" to "old"),
                modelConfigs = mapOf("model" to org.starfall.generative.shared.model.language.ModelConfiguration()),
                apiKeys = listOf(ProviderApiKey("one", "Main", "first"))))
        val enabled = original.withAuxiliaryChange(ProviderAuxiliaryChange.SetMultipleApiKeys(true,
            listOf(ProviderApiKey("one", "Main", "first"))))
        assertTrue(enabled.config.multipleApiKeys)
        assertEquals(1, enabled.config.apiKeys.size)
        val added = enabled.withAuxiliaryChange(ProviderAuxiliaryChange.SaveApiKey(
            ProviderApiKey("two", "Backup", "second")))
        val withHeader = added.withAuxiliaryChange(ProviderAuxiliaryChange.AddHeader("X-New", "added"))
        val refusedOverwrite = withHeader.withAuxiliaryChange(ProviderAuxiliaryChange.AddHeader("x-existing", "wrong"))
        assertEquals(withHeader, refusedOverwrite)
        assertEquals(mapOf("X-Existing" to "old", "X-New" to "added"), refusedOverwrite.config.headers)
        assertEquals("Original", refusedOverwrite.name)
        assertEquals(original.baseUrl, refusedOverwrite.baseUrl)
        assertEquals(original.config.modelConfigs, refusedOverwrite.config.modelConfigs)
        assertEquals("Bearer first", refusedOverwrite.auth.value)
        val selected = refusedOverwrite.withAuxiliaryChange(ProviderAuxiliaryChange.SelectApiKey("second"))
        assertEquals("Bearer second", selected.auth.value)
        val renamed = selected.withAuxiliaryChange(ProviderAuxiliaryChange.SaveApiKey(
            ProviderApiKey("two", "", "third"), selectedValue = "third"))
        assertEquals("", renamed.config.apiKeys.last().label)
        assertEquals("Bearer third", renamed.auth.value)
        val removed = renamed.withAuxiliaryChange(ProviderAuxiliaryChange.DeleteApiKey("two", "first"))
        assertEquals(listOf("one"), removed.config.apiKeys.map { it.id })
        assertEquals("Bearer first", removed.auth.value)
        val withoutHeader = removed.withAuxiliaryChange(ProviderAuxiliaryChange.DeleteHeader("x-existing"))
        assertEquals(mapOf("X-New" to "added"), withoutHeader.config.headers)
    }

    @Test fun unlabeledKeysShowOnlySmallFragmentsIncludingForShortKeys() {
        assertEquals("sk-a••••wxyz", maskedProviderApiKey("Bearer sk-abcdefgh-wxyz"))
        for (key in listOf("", "a", "ab", "abc", "abcdefgh", "long-secret-key")) {
            val masked = maskedProviderApiKey(key)
            assertTrue(masked.contains("••••"))
            if (key.isNotBlank()) assertFalse(masked.contains(key))
        }
    }
}
