package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.modelapis.attachments.*
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.subfeatures.media.logic.ResolvedAttachment
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class AttachmentPreflightTest {
    @Suppress("DEPRECATION")
    @Before fun mimeTypes() {
        val map = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowMimeTypeMap>(android.webkit.MimeTypeMap.getSingleton())
        map.addExtensionMimeTypeMapping("pdf", "application/pdf")
        map.addExtensionMimeTypeMapping("png", "image/png")
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val source get() = AttachmentResolver(context)
    private val model = ModelConfiguration(supportsVision = true, supportsVideoInput = true)
    private val provider = ModelProvider("p", "Provider", ModelApiType.OPENAI_RESPONSES,
        auth = Authorization(value = "secret-a"), baseUrl = "https://example.test/v1")
    private fun file(name: String, content: String) = File(context.cacheDir, name).apply { writeText(content) }.absolutePath

    private class Uploader : AttachmentUploader {
        val uploads = AtomicInteger()
        var uploadDelayMs = 20L
        var present = true
        var expiry: Long? = null
        var failure: Exception? = null
        var remote = true
        var limit = 1000L
        var unsupported = false
        override fun capability(provider: ModelProvider, file: ResolvedAttachment) = UploadCapability(remote, true, limit, "test")
        override fun scope(provider: ModelProvider) = provider.type.name + provider.baseUrl + provider.auth.token
        override suspend fun upload(provider: ModelProvider, file: ResolvedAttachment, key: String): RemoteAttachmentRecord {
            val number = uploads.incrementAndGet()
            delay(uploadDelayMs)
            failure?.let { throw it }
            return RemoteAttachmentRecord(key, file.reference, "file-$number", expiresAt = expiry)
        }
        override suspend fun exists(provider: ModelProvider, remote: RemoteAttachmentRecord) = present
        override fun isUnsupportedEndpoint(error: Exception) = unsupported
    }

    @Test fun concurrentRequestsAndDuplicateContentShareUploadAndSurviveCoordinatorRestart() = runBlocking {
        val uploader = Uploader()
        val store = MemoryRemoteAttachmentStore()
        val preflight = AttachmentPreflight(source, uploader, store)
        val first = file("first.pdf", "same contents")
        val second = file("renamed.pdf", "same contents")
        val results = (1..8).map { async { preflight.prepare(provider, model, listOf(first, second)) } }.awaitAll()
        assertEquals(1, uploader.uploads.get())
        assertEquals(1, results.flatMap { it.values }.map { it.remote!!.remoteId }.distinct().size)
        AttachmentPreflight(source, uploader, store).prepare(provider, model, listOf(second))
        assertEquals(1, uploader.uploads.get())
        File(second).writeText("different contents")
        preflight.prepare(provider, model, listOf(second))
        assertEquals(2, uploader.uploads.get())
    }

    @Test fun credentialsEndpointAndProtocolHaveSeparateScope() = runBlocking {
        val uploader = Uploader()
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
        val file = file("scope.pdf", "content")
        listOf(provider, provider.copy(auth = Authorization(value = "secret-b")),
            provider.copy(baseUrl = "https://other.test/v1"), provider.copy(type = ModelApiType.GOOGLE))
            .forEach { preflight.prepare(it, model, listOf(file)) }
        assertEquals(4, uploader.uploads.get())
        preflight.prepare(provider.copy(name = "Renamed"), model, listOf(file))
        assertEquals(4, uploader.uploads.get())
    }

    @Test fun expiryDeletionAndExplicitInvalidationUploadAgain() = runBlocking {
        val uploader = Uploader().apply { expiry = 200_000 }
        var now = 0L
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore(), { now })
        val file = file("expire.pdf", "content")
        preflight.prepare(provider, model, listOf(file))
        now = 200_001
        uploader.expiry = 500_000
        preflight.prepare(provider, model, listOf(file))
        uploader.present = false
        val prepared = preflight.prepare(provider, model, listOf(file))
        uploader.present = true
        preflight.invalidate(prepared)
        preflight.prepare(provider, model, listOf(file))
        assertEquals(4, uploader.uploads.get())
    }

    @Test fun cancellationNeverPersistsAnIncompleteUpload() = runBlocking {
        val uploader = Uploader().apply { failure = CancellationException("stop") }
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
        val file = file("cancel.pdf", "content")
        assertTrue(runCatching { preflight.prepare(provider, model, listOf(file)) }.exceptionOrNull() is CancellationException)
        uploader.failure = null
        preflight.prepare(provider, model, listOf(file))
        assertEquals(2, uploader.uploads.get())
    }

    @Test fun onlyVerifiedUnsupportedEndpointFallsBackAndLearningExpires() = runBlocking {
        val uploader = Uploader().apply { failure = IllegalStateException("unsupported"); unsupported = true }
        var now = 0L
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore(), { now })
        val file = file("fallback.pdf", "content")
        assertNull(preflight.prepare(provider, model, listOf(file)).getValue(file).remote)
        preflight.prepare(provider, model, listOf(file))
        assertEquals(1, uploader.uploads.get())
        now = 16 * 60_000L
        uploader.failure = null
        assertNotNull(preflight.prepare(provider, model, listOf(file)).getValue(file).remote)
        uploader.failure = IllegalStateException("unauthorized")
        uploader.unsupported = false
        assertTrue(runCatching { preflight.prepare(provider.copy(auth = Authorization(value = "wrong")), model, listOf(file)) }.isFailure)
    }

    @Test fun unsupportedModelMissingAndLargeFilesFailBeforeUpload() = runBlocking {
        val uploader = Uploader().apply { limit = 3 }
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
        val file = file("large.pdf", "too large")
        assertTrue(runCatching { preflight.prepare(provider, model, listOf(file)) }.isFailure)
        assertTrue(runCatching { preflight.prepare(provider, model, listOf("/missing-attachment.pdf")) }.isFailure)
        val image = file("picture.png", "x")
        assertTrue(runCatching { preflight.prepare(provider, model.copy(supportsVision = false), listOf(image)) }.isFailure)
        assertEquals(0, uploader.uploads.get())
    }

    @Test fun realScopesIncludeProjectCredentialAndCustomRoutesButNotModelSettings() {
        val uploader = SdkAttachmentUploader(OfficialProviderSdk(source), source)
        val scope = uploader.scope(provider)
        assertNotEquals(scope, uploader.scope(provider.copy(config = provider.config.copy(headers = mapOf("OpenAI-Project" to "project")))))
        assertNotEquals(scope, uploader.scope(provider.copy(config = provider.config.copy(customEndpoints = mapOf("responses" to "https://gateway.test/generate")))))
        assertNotEquals(scope, uploader.scope(provider.copy(auth = Authorization(value = "other"))))
        assertEquals(scope, uploader.scope(provider.copy(config = provider.config.copy(modelConfigs = mapOf("other" to model)))))
        assertFalse(scope.contains("secret"))
    }
    @Test fun externalDirectoryNamedUploadCachesIsNeverTreatedAsAnImmutableSnapshot() = runBlocking {
        val root = File(context.cacheDir, "preflight-snapshot-scope").apply { mkdirs() }
        val managed = File(root, "managed").apply { mkdirs() }
        val outside = File(root, "external/UploadCaches").apply { mkdirs() }
        val file = File(outside, "same.pdf").apply { writeText("first") }
        val timestamp = file.lastModified()
        val uploader = Uploader()
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore(), immutableRoot = managed)
        preflight.prepare(provider, model, listOf(file.absolutePath))
        file.writeText("other")
        assertTrue(file.setLastModified(timestamp))
        preflight.prepare(provider, model, listOf(file.absolutePath))
        assertEquals(2, uploader.uploads.get())
        root.deleteRecursively()
        Unit
    }

    @Test fun rejectingOneRemoteIdPreservesOtherFilesInTheSameRequest() = runBlocking {
        val uploader = Uploader()
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
        val first = file("repair-first.pdf", "first")
        val second = file("repair-second.pdf", "second")
        val prepared = preflight.prepare(provider, model, listOf(first, second))
        val rejected = rejectedRemoteAttachments(IllegalStateException("HTTP 400: File file-1 not found"), prepared)
        assertEquals(setOf(first), rejected.keys)
        preflight.invalidate(rejected)
        val repaired = preflight.prepare(provider, model, listOf(first, second))
        assertEquals(3, uploader.uploads.get())
        assertEquals(prepared.getValue(second).remote, repaired.getValue(second).remote)
    }

    @Test fun staleInvalidationDoesNotDiscardAReplacementUploadedByAnotherRequest() = runBlocking {
        val uploader = Uploader()
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
        val file = file("repair-race.pdf", "content")
        val original = preflight.prepare(provider, model, listOf(file))
        preflight.invalidate(original)
        val replacement = preflight.prepare(provider, model, listOf(file))
        preflight.invalidate(original)
        val reused = preflight.prepare(provider, model, listOf(file))
        assertEquals(2, uploader.uploads.get())
        assertEquals(replacement.getValue(file).remote, reused.getValue(file).remote)
    }



    @Test fun sameBasenameWithDifferentContentsUsesDistinctRemoteIds() = runBlocking {
        val uploader = Uploader()
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
        val one = File(context.cacheDir, "first-parent").apply { mkdirs() }
        val two = File(context.cacheDir, "second-parent").apply { mkdirs() }
        val first = File(one, "same.pdf").apply { writeText("one") }.absolutePath
        val second = File(two, "same.pdf").apply { writeText("two") }.absolutePath
        val prepared = preflight.prepare(provider, model, listOf(first, second))
        assertNotEquals(prepared.getValue(first).remote?.remoteId, prepared.getValue(second).remote?.remoteId)
        assertEquals(2, uploader.uploads.get())
        assertTrue(File(first).isFile)
        assertTrue(File(second).isFile)
    }

    @Test fun missingHistoricalFileAndUnsupportedMimeRejectBeforeAnyNetworkCall() = runBlocking {
        val remoteUploader = Uploader()
        val sourceFile = file("historical.pdf", "historical")
        File(sourceFile).delete()
        assertTrue(runCatching {
            AttachmentPreflight(source, remoteUploader, MemoryRemoteAttachmentStore())
                .prepare(provider, model, listOf(sourceFile))
        }.isFailure)
        assertEquals(0, remoteUploader.uploads.get())

        val realUploader = SdkAttachmentUploader(OfficialProviderSdk(source), source)
        val textFile = file("unsupported.txt", "text")
        val openAi = provider.copy(type = ModelApiType.OPENAI)
        assertTrue(runCatching {
            AttachmentPreflight(source, realUploader, MemoryRemoteAttachmentStore())
                .prepare(openAi, model, listOf(textFile))
        }.isFailure)
    }

    @Test fun sdkLimitsRejectOversizedInlineAndUploadFilesBeforeNetwork() = runBlocking {
        val realUploader = SdkAttachmentUploader(OfficialProviderSdk(source), source)
        val preflight = AttachmentPreflight(source, realUploader, MemoryRemoteAttachmentStore())
        val local = File(context.cacheDir, "attachment-size-guard.png")
        val largePdf = File(context.cacheDir, "attachment-size-guard.pdf")
        try {
            java.io.RandomAccessFile(local, "rw").use { it.setLength(AttachmentPreflight.INLINE_LIMIT + 1) }
            java.io.RandomAccessFile(largePdf, "rw").use { it.setLength(50L * 1024 * 1024 + 1) }
            val chat = provider.copy(type = ModelApiType.OPENAI)
            assertTrue(runCatching { preflight.prepare(chat, model, listOf(local.absolutePath)) }.isFailure)
            assertTrue(runCatching { preflight.prepare(chat, model, listOf(largePdf.absolutePath)) }.isFailure)
        } finally {
            local.delete()
            largePdf.delete()
        }
    }

    @Test fun timeoutAndCancellationAreNotTreatedAsUnsupportedUpload() = runBlocking {
        val store = MemoryRemoteAttachmentStore()
        val uploader = Uploader().apply { unsupported = true; uploadDelayMs = 500L }
        val preflight = AttachmentPreflight(source, uploader, store)
        val reference = file("timeout.pdf", "content")
        val timedOut = withTimeoutOrNull(100L) {
            preflight.prepare(provider, model, listOf(reference))
            true
        }
        assertNull(timedOut)
        // Cancellation must not mark the endpoint unsupported or save a partial ID.
        uploader.unsupported = false
        uploader.uploadDelayMs = 20L
        val result = preflight.prepare(provider, model, listOf(reference))
        assertNotNull(result.getValue(reference).remote)
        assertEquals(2, uploader.uploads.get())
    }

    @Test fun deletingOneOfTwoIdenticalFilesDoesNotLoseTheirSharedRemoteReference() = runBlocking {
        val uploader = Uploader()
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
        val first = file("retained.pdf", "same bytes")
        val second = file("deleted.pdf", "same bytes")
        preflight.prepare(provider, model, listOf(first, second))
        assertEquals(1, uploader.uploads.get())
        File(second).delete()
        preflight.prepare(provider, model, listOf(first))
        assertEquals(1, uploader.uploads.get())
    }

}
