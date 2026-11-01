package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.database.AppDatabase
import org.starfall.generative.database.ModelRepository
import org.starfall.generative.features.providers.shared.ui.ProviderModelsPane
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration

/** Real Compose model-list drop wired to the actual asynchronous persistence repository. */
@RunWith(AndroidJUnit4::class)
class OptimisticModelPaneDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun acceptedModelDragRendersImmediatelyAndSurvivesReopeningRoom() {
        val context = compose.activity.applicationContext
        val name = "model-pane-drop-device-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val original = listOf("First", "Second", "Third")
        val configs = original.associateWith { ModelConfiguration() }
        val repository = ModelRepository(db, AiService(context))
        val visibleOrder = mutableStateOf(original)
        val acceptedWrite = AtomicReference<Deferred<Unit>?>(null)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        try {
            runBlocking {
                repository.saveProvider(ModelProvider(
                    "device-model-editor", "Device Model Editor",
                    ModelApiType.OPENAI, baseUrl = "https://example.invalid/v1",
                    config = ProviderConfiguration(modelIds = original, modelConfigs = configs)
                ))
                repository.allProviders.first()
            }
            compose.setContent {
                MaterialTheme {
                    ProviderModelsPane(
                        visibleOrder.value, configs, rememberLazyListState(),
                        onOrderChange = { visibleOrder.value = it },
                        onDrop = { _, updated ->
                            acceptedWrite.set(repository.queueReorderProviderModels("device-model-editor", updated))
                        },
                        onEdit = {}, onDelete = {}, onOpenCatalog = {}
                    )
                }
            }
            compose.waitForIdle()

            val blockingRoom = scope.async {
                db.withTransaction {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                runBlocking { withTimeout(5_000) { entered.await() } }
                val root = compose.onRoot()
                val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
                val from = compose.onNodeWithText("First").fetchSemanticsNode().boundsInRoot.center - origin
                val to = compose.onNodeWithText("Third").fetchSemanticsNode().boundsInRoot.center - origin

                root.performTouchInput {
                    down(from)
                    advanceEventTime(750)
                    moveBy(Offset(0f, to.y - from.y), delayMillis = 200)
                }
                compose.waitForIdle()
                assertNotEquals("Hover must already reorder the visible models", original, visibleOrder.value)
                assertEquals("Only release may persist the order", null, acceptedWrite.get())
                root.performTouchInput { up() }
                compose.waitUntil(5_000) { acceptedWrite.get() != null }

                val queued = acceptedWrite.get()
                assertNotNull(queued)
                assertFalse("Room transaction is still blocked", queued!!.isCompleted)
                runBlocking {
                    val projected = withTimeout(2_000) {
                        repository.allProviders.first().single().config.modelIds
                    }
                    assertEquals(visibleOrder.value, projected)
                }

                release.complete(Unit)
                runBlocking { withTimeout(15_000) { queued.await(); blockingRoom.await() } }
                val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
                try {
                    runBlocking {
                        assertEquals(visibleOrder.value,
                            ModelRepository(reopened, AiService(context))
                                .getProviderById("device-model-editor")!!.config.modelIds)
                    }
                } finally {
                    reopened.close()
                }
            } finally {
                release.complete(Unit)
                runBlocking { withTimeout(15_000) { blockingRoom.await() } }
            }
        } finally {
            scope.cancel()
            db.close()
            context.deleteDatabase(name)
        }
    }
}
