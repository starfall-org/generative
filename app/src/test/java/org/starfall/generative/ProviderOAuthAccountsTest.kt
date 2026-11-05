package org.starfall.generative

import org.starfall.generative.integrations.modelapis.account.*

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.auth.logic.*
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.shared.model.*
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.model.provider.recordOAuthAccount
import org.starfall.generative.shared.model.provider.removeOAuthAccount

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderOAuthAccountsTest {
    private val marker = "account:antigravity:v1"
    private val legacy = ModelProvider("provider", "Antigravity", ModelApiType.ANTIGRAVITY,
        baseUrl = ModelApiType.ANTIGRAVITY.defaultBaseUrl,
        auth = Authorization(AuthMethod.OAUTH, "first@example.test", marker))

    @Test fun legacyAccountIsImportedAndAccountsRoundTripWithoutTokens() {
        assertEquals("provider", legacy.oauthCredentialId)
        val imported = legacy.recordOAuthAccount("Primary")
        assertEquals("provider", imported.config.oauthAccounts.single().id)
        val second = imported.copy(auth = Authorization(AuthMethod.OAUTH, "second@example.test", marker, "provider:second"))
            .recordOAuthAccount("Backup")
        assertEquals(listOf("Primary", "Backup"), second.config.oauthAccounts.map { it.label })
        assertEquals("provider:second", second.oauthCredentialId)
        val json = Json.encodeToString(second)
        assertFalse(json.contains("accessToken")); assertFalse(json.contains("refreshToken"))
        assertEquals(second, Json.decodeFromString<ModelProvider>(json))
        assertEquals(second, second.recordOAuthAccount())
    }

    @Test fun removingSelectedAccountSelectsRemainingAccountAndRemovingOtherKeepsSelection() {
        val first = legacy.recordOAuthAccount()
        val second = first.copy(auth = Authorization(AuthMethod.OAUTH, "second@example.test", marker, "provider:second"))
            .recordOAuthAccount()
        val configured = second.copy(config = second.config.copy(antigravityProjectIds = mapOf(
            "provider" to "project-primary", "provider:second" to "project-backup")))
        val withoutPrimary = configured.removeOAuthAccount("provider")
        assertEquals(configured.auth, withoutPrimary.auth)
        assertEquals(mapOf("provider:second" to "project-backup"), withoutPrimary.config.antigravityProjectIds)
        val remaining = configured.removeOAuthAccount("provider:second")
        assertEquals("first@example.test", remaining.auth.key)
        assertEquals("provider", remaining.oauthCredentialId)
        assertEquals(mapOf("provider" to "project-primary"), remaining.config.antigravityProjectIds)
        val empty = remaining.removeOAuthAccount("provider")
        assertEquals(AuthMethod.OAUTH, empty.auth.method)
        assertNull(empty.auth.value)
        assertTrue(empty.config.oauthAccounts.isEmpty())
        assertTrue(empty.config.antigravityProjectIds.isEmpty())
    }

    @Test fun repositoryDeletesOnlyRequestedAccountThenProviderDeletionClearsAllSlots() = runBlocking {
        installTestAndroidKeyStore()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = ModelRepository(db, AiService(context))
            val store = AccountTokenStore(context, "antigravity")
            val first = legacy.recordOAuthAccount()
            val second = first.copy(auth = Authorization(AuthMethod.OAUTH, "second@example.test", marker, "provider:second"))
                .recordOAuthAccount()
            store.save("provider", AccountTokenState("first-secret", "first-refresh"))
            store.save("provider:second", AccountTokenState("second-secret", "second-refresh"))
            val configured = second.copy(config = second.config.copy(antigravityProjectIds = mapOf(
                "provider" to "project-primary", "provider:second" to "project-backup")))
            repository.saveProvider(configured)
            // Removing an unselected account must not sign out the selected account in storage.
            repository.clearOAuthCredentials(configured.copy(auth = first.auth)).getOrThrow()
            assertNull(store.load("provider"))
            assertEquals("second-secret", store.load("provider:second")!!.accessToken)
            val persisted = repository.getProviderById("provider")!!
            assertEquals("provider:second", persisted.oauthCredentialId)
            assertEquals(listOf("provider:second"), persisted.config.oauthAccounts.map { it.id })
            assertEquals(mapOf("provider:second" to "project-backup"), persisted.config.antigravityProjectIds)
            repository.deleteProvider("provider")
            assertNull(store.load("provider:second"))
        } finally { db.close() }
    }
}
