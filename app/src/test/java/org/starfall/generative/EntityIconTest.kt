package org.starfall.generative

import org.starfall.generative.database.preferences.MigratedStringPreferences

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.McpServerEntity
import org.starfall.generative.shared.model.*
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.features.mcp.shared.ui.*
import org.starfall.generative.integrations.mcp.*
import org.starfall.generative.features.machines.shared.ui.*
import org.starfall.generative.integrations.remote.*
import org.starfall.generative.features.home.chat.*
import org.starfall.generative.integrations.modelapis.*
import org.starfall.generative.shared.subfeatures.icons.logic.*
import java.io.File
import org.starfall.generative.shared.model.icons.IconRule
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.provider.ProviderGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EntityIconTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun logoVariantsPersistStayGroupedAndFallBackToNormal() {
        val source = File.createTempFile("variant-icon", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val store = IconStore(context)
            val base = store.importImage(Uri.fromFile(source))
            val light = store.importImage(Uri.fromFile(source), shared = false)
            val dark = store.importImage(Uri.fromFile(source), shared = false)
            store.setVariant(base, light, false)
            store.setVariant(base, dark, true)
            val reloaded = IconStore(context)
            assertEquals(light, reloaded.themedImage(base, false))
            assertEquals(dark, reloaded.themedImage(base, true))
            assertEquals(dark, reloaded.entries().first { it.image == base }.darkImage)
            assertFalse(reloaded.entries().any { it.image == light || it.image == dark })
            reloaded.prune(emptySet())
            assertNotNull(reloaded.load(light))
            assertNotNull(reloaded.load(dark))
            reloaded.setVariant(base, null, true)
            assertEquals(light, reloaded.themedImage(base, true))
        } finally { source.delete() }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun importedImageRemainsAvailableWithoutOriginalAndIsBounded() {
        val source = File.createTempFile("source-icon", ".png", context.cacheDir)
        val original = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        original.recycle()
        val id = IconStore(context).importImage(Uri.fromFile(source))
        source.delete()
        val loaded = IconStore(context).load(id)!!
        assertTrue(loaded.width <= 256)
        assertTrue(loaded.height <= 256)
        assertEquals(2f, loaded.width.toFloat() / loaded.height, 0.05f)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun invalidImagesAreRejectedAndTemporaryCopiesRemoved() {
        val source = File.createTempFile("invalid-icon", ".txt", context.cacheDir).apply { writeText("not an image") }
        try {
            assertThrows(Exception::class.java) { IconStore(context).importImage(Uri.fromFile(source)) }
            assertFalse(File(context.filesDir, "entity-icons").listFiles().orEmpty().any { it.extension == "tmp" })
            assertNull(IconStore(context).load("../source.png"))
        } finally { source.delete() }
    }

    @Test fun iconsSurviveRepositoryReloadAndRemovalPreservesSettings() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val provider = ModelProvider("p", "Provider", ModelApiType.OPENAI,
                auth = Authorization(AuthMethod.BEARER_TOKEN, value = "fixture-icon-test-token"),
                baseUrl = "https://example.test/v1", icon = "icon-a.png", sortOrder = 0, config = ProviderConfiguration(
                    modelConfigs = mapOf("model" to ModelConfiguration(icon = "icon-b.png", temperature = 0.4))))
            ModelRepository(db, AiService(context)).saveProvider(provider)
            val ai = ModelRepository(db, AiService(context))
            assertEquals(provider, ai.getProviderById("p"))
            val removed = provider.copy(icon = null, config = provider.config.copy(
                modelConfigs = mapOf("model" to provider.config.modelConfigs.getValue("model").copy(icon = null))))
            ai.saveProvider(removed)
            assertEquals(removed, ai.getProviderById("p"))

            val server = McpInfo("s", "Server", url = "https://example.test/mcp",
                headers = mapOf("X-Test" to "yes"), auth = McpAuthorization(McpAuthMethod.BEARER_TOKEN, value = "secret"),
                cachedTools = emptyList(), sortOrder = 3, icon = "icon-c.png")
            McpRepository(db, McpService()).saveServer(server)
            val mcp = McpRepository(db, McpService())
            assertEquals(server, mcp.getById("s"))
            mcp.saveServer(server.copy(icon = null))
            assertEquals(server.copy(icon = null), mcp.getById("s"))
        } finally { db.close() }
    }

    @Test fun entityIconsDoNotCreateSharedMatchingRules() = runBlocking {
        installTestAndroidKeyStore()
        MigratedStringPreferences.get(context, "named-entity-icons").replaceAll(emptyMap())
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val source = ModelProvider("first", " My Provider ", ModelApiType.OPENAI,
                baseUrl = "https://example.test", icon = "icon-a.png",
                config = ProviderConfiguration(modelConfigs = mapOf("m" to ModelConfiguration(displayName = "My Model", icon = "icon-b.png"))))
            ModelRepository(db, AiService(context), IconStore(context)).saveProvider(source)
            ModelRepository(db, AiService(context), IconStore(context)).deleteProvider(source.id)
            val repository = ModelRepository(db, AiService(context), IconStore(context))
            val future = source.copy(id = "later", name = "my provider", icon = null,
                config = ProviderConfiguration(modelConfigs = mapOf("another-id" to ModelConfiguration(displayName = " my model "))))
            repository.saveProvider(future)
            val restored = repository.getProviderById("later")!!
            assertNull(restored.icon)
            assertNull(IconStore(context).find(restored.name))
            assertNull(restored.config.modelConfigs.getValue("another-id").icon)
            assertNull(IconStore(context).find("my model", model = true))
            assertNull(IconStore(context).find("my provider"))
            val mcp = McpRepository(db, McpService(), IconStore(context))
            mcp.saveServer(McpInfo("s", "Tool Server", icon = "icon-c.png"))
            mcp.deleteServer("s")
            McpRepository(db, McpService(), IconStore(context)).saveServer(McpInfo("s2", " tool server "))
            // Read a server saved by another repository through a fresh repository,
            // rather than the older instance's immediate local state.
            val reloadedServer = McpRepository(db, McpService(), IconStore(context)).getById("s2")!!
            assertNull(reloadedServer.icon)
            assertNull(IconStore(context).find(reloadedServer.name))
            mcp.saveServer(reloadedServer.copy(icon = null))
            mcp.saveServer(McpInfo("s3", "tool server"))
            assertNull(mcp.getById("s3")!!.icon)
            assertNull(IconStore(context).find("tool server"))
            repository.saveGroup(ProviderGroup("g", "Folder", icon = "icon-d.png"))
            assertNull(IconStore(context).find("Folder"))
            assertTrue(IconStore(context).rules().isEmpty())
        } finally { db.close() }
    }

    @Test fun sharedRegexMatchingPrefersModelSuffixThenPrefixAndIgnoresVariant() {
        val store = IconStore(context)
        store.saveRules(listOf(
            IconRule(pattern = "vendor", image = "icon-a.png"),
            IconRule(pattern = "claude", image = "icon-b.png"),
            IconRule(pattern = "gemini|google", image = "icon-c.png")))
        assertEquals("icon-b.png", store.find("vendor/claude-sonnet-4", model = true))
        assertNull(store.find("vendor/unknown-v2", model = true))
        assertEquals("icon-c.png", store.find("GEMINI-3-flash", model = true))
        assertEquals("icon-c.png", store.find(" Google "))
        assertEquals(listOf("claude", "claude-sonnet"), iconMatchNames("vendor/claude-sonnet", true))
        assertNull(store.find("somethingelse", model = true))
        store.saveRules(store.rules() + IconRule(pattern = "ollama", image = "ollama.png"))
        assertEquals("ollama.png", store.find("Ollama"))
        assertEquals("ollama.png", store.find(" Ollama Cloud "))
        assertEquals("ollama.png", store.find("ollama cloud"))
        assertEquals("ollama.png", store.find("Ollama Cloud Other"))
        store.saveRules(store.rules() + IconRule(pattern = "Ollama Cloud", image = "custom-cloud.png"))
        assertEquals("custom-cloud.png", store.find("Ollama Cloud"))
        assertEquals("ollama.png", store.find("Ollama"))
        assertThrows(IllegalArgumentException::class.java) { store.saveRules(listOf(IconRule(pattern = "[", image = "icon-d.png"))) }
        store.saveRules(store.rules().map { if (it.pattern == "claude") it.copy(image = "icon-d.png") else it })
        assertEquals("icon-d.png", IconStore(context).find("claude-haiku", true))
        store.saveRules(emptyList())
        assertNull(store.find("claude-haiku", true))
    }

    @Test fun modelMatchingRestoresHyphenSuffixesWithoutUsingVendorPrefix() {
        val store = IconStore(context)
        store.saveRules(listOf(
            IconRule(pattern = "vendor", image = "vendor.png"),
            IconRule(pattern = "claude-sonnet-4", image = "specific.png"),
            IconRule(pattern = "claude-sonnet", image = "family.png")))
        assertEquals("family.png", store.find("vendor/CLAUDE-sonnet-4", true))
        store.saveRules(store.rules().filterNot { it.pattern == "claude-sonnet" })
        assertEquals("specific.png", store.find("vendor/claude-sonnet-4", true))
        assertNull(store.find("vendor/unknown-v2", true))
        assertNull(store.find("claude-sonnet", false))
        assertEquals("vendor/claude-sonnet", iconMatchNames("vendor/claude-sonnet", false).first())
    }

    @Test fun catalogMatchingUsesRealFilesAndPrefersColorWithoutPartialBrandMatches() {
        val files = setOf("openai.png", "claude.png", "claude-color.png", "claudecode.png")
        assertEquals("claude-color.png", LobeIconSource.match("Claude", files))
        assertEquals("qwen-color.png", LobeIconSource.match("Qwen", setOf("qwen-color.png")))
        assertEquals("qwen-dark.png", LobeIconSource.match("Qwen", setOf("qwen-dark.png")))
        assertEquals("qwen-light.png", LobeIconSource.match("Qwen", setOf("qwen-light.png")))
        assertEquals("openai.png", LobeIconSource.match("Open AI", files))
        assertEquals("openai.png", LobeIconSource.match("My OpenAI Provider", files))
        assertEquals("claudecode.png", LobeIconSource.match("Claude Code Cloud", files))
        assertEquals("claude-color.png", LobeIconSource.match("My Claude API", files))
        assertEquals("openai.png", LobeIconSource.match("gpt", files))
        val ollamaFiles = setOf("ollama.png")
        assertEquals("ollama.png", LobeIconSource.match("Ollama Cloud", ollamaFiles))
        assertNull(LobeIconSource.match("Ollamaish Cloud", ollamaFiles))
        assertEquals("ollama.png", iconMatchNames("Ollama Cloud", false).firstNotNullOfOrNull { LobeIconSource.match(it, ollamaFiles) })
        assertNull(LobeIconSource.match("claud", files))
    }

    @Test fun modelCatalogMatchingDoesNotSplitHyphenatedFamiliesIntoSuffixBrands() {
        val files = setOf("spark-color.png", "muse-spark-color.png")

        assertEquals(listOf("muse", "muse-spark", "muse-spark-1.3"),
            iconMatchNames("abc/muse-spark-1.3", model = true))
        assertEquals(listOf("abc", "abc123"), iconMatchNames("abc123", model = true))
        assertEquals("muse-spark-color.png", LobeIconSource.match("muse-spark", files, model = true))
        assertNull(LobeIconSource.match("muse-spark", setOf("spark-color.png"), model = true))
    }

    @Test fun allProviderBrandsMatchCompleteWordsWithDescriptionsAndPreferSpecificNames() {
        val store = IconStore(context)
        val brands = listOf("Ollama", "OpenAI", "Google", "Anthropic", "DeepSeek", "GitHub Copilot", "Open AI")
        store.saveRules(brands.mapIndexed { index, brand -> IconRule(pattern = Regex.escape(brand), image = "brand-$index.png") })
        brands.forEachIndexed { index, brand ->
            val expected = "brand-$index.png"
            for (name in listOf("$brand Cloud", "My $brand", "My $brand API Server", "  ${brand.lowercase()}  Cloud  ", "$brand / Production")) {
                assertEquals(name, expected, store.find(name))
            }
            assertNull(store.find("${brand.replace(" ", "")}Other"))
        }
        store.saveRules(store.rules() + listOf(
            IconRule(pattern = "GitHub", image = "github.png"),
            IconRule(pattern = "My GitHub Copilot Cloud", image = "exact.png")))
        assertEquals("brand-5.png", store.find("GitHub Copilot Cloud"))
        assertEquals("exact.png", store.find("My GitHub Copilot Cloud"))
        assertEquals("brand-5.png", store.find("GitHub-Copilot Cloud"))

        val files = setOf("ollama.png", "openai.png", "github.png", "githubcopilot.png", "claude.png", "claudecode.png")
        for ((name, expected) in listOf("Ollama Cloud" to "ollama.png", "Open AI Responses" to "openai.png",
            "My GitHub Copilot" to "githubcopilot.png", "Claude Code API" to "claudecode.png")) {
            assertEquals(name, expected, iconMatchNames(name, false).firstNotNullOfOrNull { LobeIconSource.match(it, files) })
        }
        assertNull(iconMatchNames("OpenAIish Cloud", false).firstNotNullOfOrNull { LobeIconSource.match(it, files) })
    }

    @Test fun officialLobeModelMappingsResolveBrandBeforeAssetLookup() {
        assertEquals("deepseek", LobeModelIconResolver.resolve("DeepSeek-v4-Flash"))
        assertEquals("perplexity", LobeModelIconResolver.resolve("sonar-pro"))
        assertEquals("qwen", LobeModelIconResolver.resolve("vendor/Qwen3.8-Max"))
        assertEquals("openai", LobeModelIconResolver.resolve("gpt-5.4"))

        val files = setOf("deepseek.png", "deepseek-color.png", "perplexity-color.png")
        val deepSeekBrand = requireNotNull(LobeModelIconResolver.resolve("DeepSeek-v4-Flash"))
        assertEquals("deepseek-color.png", LobeIconSource.match(deepSeekBrand, files))
        assertEquals("meta", LobeModelIconResolver.resolve("abc/muse-spark-1.3"))
        assertNull(LobeModelIconResolver.resolve("spark"))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun privateUploadsStayOutOfSharedCacheAndSurvivePruningWhileAssigned() {
        val store = IconStore(context)
        val source = File.createTempFile("private-icon", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val privateIcon = store.importImage(Uri.fromFile(source), shared = false)
            val sharedIcon = store.importImage(Uri.fromFile(source), shared = true)
            assertFalse(store.entries().any { it.image == privateIcon })
            assertTrue(store.entries().any { it.image == sharedIcon })
            store.cache("Private model", privateIcon)
            assertNull(store.find("Private model"))
            store.prune(listOf(privateIcon))
            assertNotNull(store.load(privateIcon))
            store.prune(emptyList())
            assertNull(store.load(privateIcon))
            assertNotNull(store.load(sharedIcon))
        } finally { source.delete() }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun remoteIconsDownloadLazilyReuseFilesAndRespectCustomRules() = runBlocking {
        listOf("named-entity-icons", "icon-assets", "automatic-entity-icons").forEach {
            MigratedStringPreferences.get(context, it).replaceAll(emptyMap())
        }
        File(context.filesDir, "entity-icons").listFiles().orEmpty().forEach { it.delete() }
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val source = LobeIconSource(server.url("/index").toString(), server.url("/images/").toString())
            val store = IconStore(context)
            assertTrue(store.entries().isEmpty())
            assertEquals(0, server.requestCount)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                """{"files":[{"name":"/light/claude-color.png"},{"name":"/light/openai.png"}]}"""))
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes)))
            val lookupRevision = IconStore.lookupRevision.value
            val id = store.resolve("vendor/claude-sonnet-4", true, source)!!
            assertEquals(lookupRevision, IconStore.lookupRevision.value)
            assertEquals("lobe-claude-color.png", id)
            assertEquals(2, server.requestCount)
            assertEquals("/index", server.takeRequest().path)
            assertEquals("/images/claude-color.png", server.takeRequest().path)
            assertEquals(listOf("claude-color.png"), store.entries().map { it.filename })
            assertNotNull(store.load(id))
            assertSame(store.load(id), store.load(id))
            assertEquals(id, IconStore(context).resolve("claude-haiku", true, source))
            assertEquals(2, server.requestCount)
            store.editMatches(id, listOf("my-model", "special"))
            assertEquals(id, store.find("my-model-v2", true))
            assertEquals(id, store.find("SPECIAL"))
            store.saveRules(listOf(IconRule(pattern = "claude", image = "custom.png")) + store.rules())
            assertEquals("custom.png", store.resolve("claude-sonnet", true, source))
            assertEquals(2, server.requestCount)
            store.prune(emptyList())
            assertNotNull(store.load(id))
            store.delete(id)
            assertNull(store.load(id))
            assertNull(store.find("special"))
            assertTrue(store.entries().isEmpty())
            assertEquals("custom.png", store.find("claude"))
        } finally { server.shutdown() }
    }

    @Test fun iconCatalogIsDownloadedOnceAndReusedFromDiskByLaterSources() {
        val cacheDir = File(context.cacheDir, "catalog-test-${System.nanoTime()}")
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            val index = server.url("/index").toString()
            val images = server.url("/images/").toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                """{"files":[{"name":"/light/claude-color.png"},{"name":"/dark/claude.png"}]}"""))
            val first = LobeIconSource(index, images, catalogCacheDir = cacheDir)
            assertEquals("claude-color.png", first.find("Claude"))
            assertEquals(1, server.requestCount)
            assertTrue(cacheDir.listFiles().orEmpty().any { it.name.startsWith("lobe-icon-catalog-") })
            // A new process is a new source: it must answer from disk without another request.
            val second = LobeIconSource(index, images, catalogCacheDir = cacheDir)
            assertEquals("claude-color.png", second.find("Claude"))
            assertEquals("claude.png", second.find("Claude", dark = true))
            assertEquals(1, server.requestCount)
            // A corrupt cache file is discarded and the index fetched again.
            cacheDir.listFiles().orEmpty().forEach { it.writeText("not json") }
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                """{"files":[{"name":"/light/openai.png"}]}"""))
            assertEquals("openai.png", LobeIconSource(index, images, catalogCacheDir = cacheDir).find("OpenAI"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown(); cacheDir.deleteRecursively() }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun providerNameWithExtraWordsDownloadsTheBrandLogoFile() = runBlocking {
        listOf("named-entity-icons", "automatic-entity-icons").forEach {
            MigratedStringPreferences.get(context, it).replaceAll(emptyMap())
        }
        File(context.filesDir, "entity-icons").listFiles().orEmpty().forEach { it.delete() }
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                """{"files":[{"name":"/light/ollama.png"}]}"""))
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes)))
            val source = LobeIconSource(server.url("/index").toString(), server.url("/images/").toString())
            val store = IconStore(context)
            val image = store.resolve("Ollama Cloud", iconSource = source)
            assertEquals("lobe-ollama.png", image)
            assertNotNull(store.loadIcon(image))
            assertEquals("/index", server.takeRequest().path)
            assertEquals("/images/ollama.png", server.takeRequest().path)
            assertEquals(image, store.resolve("Ollama", iconSource = source))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun legacyRecordsWithoutIconsStillLoad() = runBlocking {
        installTestAndroidKeyStore()
        assertNull(kotlinx.serialization.json.Json.decodeFromString<ModelConfiguration>("{}").icon)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            McpRepository(db, McpService()).saveServer(McpInfo(
                id = "old", name = "Old", protocol = McpProtocol.SSE, url = "https://example.test/sse",
                headers = mapOf("X-Test" to "yes"), sortOrder = 0))
            val restored = McpRepository(db, McpService()).getById("old")!!
            assertNull(restored.icon)
            assertEquals(mapOf("X-Test" to "yes"), restored.headers)
        } finally { db.close() }
    }

    @Test fun pruneRemovesOnlyUnreferencedImportedFiles() {
        MigratedStringPreferences.get(context, "named-entity-icons").replaceAll(emptyMap())
        val directory = File(context.filesDir, "entity-icons").apply {
            mkdirs()
            listFiles().orEmpty().forEach { it.delete() }
        }
        val ruleIcon = "icon-11111111-1111-1111-1111-111111111111.png"
        val entityIcon = "icon-22222222-2222-2222-2222-222222222222.png"
        val orphanIcon = "icon-33333333-3333-3333-3333-333333333333.png"
        File(directory, ruleIcon).writeBytes(byteArrayOf(1))
        File(directory, entityIcon).writeBytes(byteArrayOf(2))
        File(directory, orphanIcon).writeBytes(byteArrayOf(3))
        File(directory, "import-stale.tmp").writeBytes(byteArrayOf(4))

        val store = IconStore(context)
        store.saveRules(listOf(IconRule(pattern = "provider", image = ruleIcon)))
        store.prune(listOf(entityIcon))

        assertTrue(File(directory, ruleIcon).exists())
        assertTrue(File(directory, entityIcon).exists())
        assertFalse(File(directory, orphanIcon).exists())
        assertFalse(File(directory, "import-stale.tmp").exists())
    }

}
