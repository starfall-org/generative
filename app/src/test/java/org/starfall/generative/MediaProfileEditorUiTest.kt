package org.starfall.generative

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.tools.ui.MediaRequestChainSettings
import org.starfall.generative.features.tools.ui.MediaRequestProfileEditor
import org.starfall.generative.features.tools.ui.ToolsEditor
import org.starfall.generative.navigation.AppDestination
import org.starfall.generative.navigation.MediaProfileRoutes
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.media.defaultMediaProfile
import org.starfall.generative.shared.model.media.withDefaultMediaProfile
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class MediaProfileEditorUiTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun ProfileTestNavigation(
        initialSettings: ToolSettings,
        onSaved: (SystemToolConfig) -> Unit = {},
        onRouteChange: (String?) -> Unit = {}
    ) {
        var settings by remember { mutableStateOf(initialSettings) }
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        SideEffect { onRouteChange(entry?.destination?.route) }
        GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
            NavHost(
                navController = nav,
                startDestination = AppDestination.TOOLS_EDITOR.route,
                enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) },
                popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
            ) {
                composable(AppDestination.TOOLS_EDITOR.route) {
                    ToolsEditor(
                        providers = emptyList(), settings = settings,
                        onSave = { name, config ->
                            settings = settings.copy(system = settings.system + (name to config))
                            onSaved(config)
                        },
                        onOpenMediaProfiles = { video -> nav.navigate(MediaProfileRoutes.library(video)) },
                        onBack = {}
                    )
                }
                composable(MediaProfileRoutes.LIBRARY, arguments = listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
                    val video = entry.arguments?.getString("kind") == "video"
                    val name = if (video) "generate_video" else "generate_image"
                    val config = settings.system[name] ?: SystemToolConfig()
                    MediaRequestChainSettings(
                        provider = null, config = config, video = video,
                        onAutoSave = { profiles, selectedId ->
                            val normalized = profiles.withDefaultMediaProfile(video)
                            val updated = config.withMediaProfiles(video, normalized)
                            val next = if (video) updated.copy(activeVideoRequestProfileId = selectedId)
                                else updated.copy(activeImageRequestProfileId = selectedId)
                            settings = settings.copy(system = settings.system + (name to next))
                            onSaved(next)
                        },
                        onOpenProfile = { id -> nav.navigate(MediaProfileRoutes.editor(video, id)) },
                        onAddProfile = { nav.navigate(MediaProfileRoutes.editor(video, "new")) },
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(MediaProfileRoutes.EDITOR, arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("profileId") { type = NavType.StringType }
                )) { entry ->
                    val video = entry.arguments?.getString("kind") == "video"
                    val name = if (video) "generate_video" else "generate_image"
                    val id = entry.arguments?.getString("profileId").orEmpty()
                    val config = settings.system[name] ?: SystemToolConfig()
                    val profile = if (id == "new") {
                        val newId = rememberSaveable { UUID.randomUUID().toString() }
                        remember(newId) { MediaRequestProfile(newId, "", mode = MediaGenerationMode.DEFAULT) }
                    } else remember(id) { config.mediaProfiles(video)[id] }
                    if (profile != null) {
                        MediaRequestProfileEditor(
                            provider = null, profile = profile, config = config, video = video,
                            onAutoSave = { updated ->
                                val latest = settings.system[name] ?: SystemToolConfig()
                                val next = latest.withMediaProfiles(video, latest.mediaProfiles(video) + (updated.id to updated))
                                settings = settings.copy(system = settings.system + (name to next))
                                onSaved(next)
                            },
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    @Test fun imageProfileEditorOpensAndReturnsAsADistinctRouteWhileSaving() {
        var savedConfig: SystemToolConfig? = null
        var route: String? = null
        compose.setContent {
            ProfileTestNavigation(ToolSettings(), { savedConfig = it }, { route = it })
        }
        compose.onNodeWithTag("media_profile_library_generate_image").performClick()
        compose.runOnIdle { assertEquals(MediaProfileRoutes.LIBRARY, route) }
        compose.onNodeWithTag("media-profiles-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add").performClick()
        compose.runOnIdle { assertEquals(MediaProfileRoutes.EDITOR, route) }
        compose.onNodeWithTag("media-profile-editor-screen").assertIsDisplayed()
        compose.onNodeWithTag("media-profile-name-field").performTextInput("Portrait preset")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(MediaProfileRoutes.LIBRARY, route) }
        compose.onNodeWithTag("media-profiles-screen").assertIsDisplayed()
        compose.onNodeWithText("Portrait preset").assertIsDisplayed()
        compose.runOnIdle {
            assertNotNull(savedConfig)
            assertEquals("Portrait preset", savedConfig?.imageRequestProfiles?.values?.firstOrNull {
                it.name == "Portrait preset"
            }?.name)
        }
        val id = savedConfig!!.imageRequestProfiles.values.first { it.name == "Portrait preset" }.id
        compose.onNodeWithTag("media-profile-item_$id").performClick()
        compose.runOnIdle { assertEquals(MediaProfileRoutes.EDITOR, route) }
        compose.onNodeWithTag("media-profile-editor-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(MediaProfileRoutes.LIBRARY, route) }
    }

    @Test fun videoProfileEditorOpensAndReturnsToItsOwnLibraryRoute() {
        var route: String? = null
        val profile = MediaRequestProfile("video-preset", "Clip preset")
        compose.setContent {
            ProfileTestNavigation(
                ToolSettings(system = mapOf("generate_video" to SystemToolConfig(videoRequestProfiles = mapOf(profile.id to profile)))),
                onRouteChange = { route = it }
            )
        }
        compose.onNodeWithTag("media_profile_library_generate_video").performClick()
        compose.runOnIdle { assertEquals(MediaProfileRoutes.LIBRARY, route) }
        compose.onNodeWithText("Video Profiles").assertIsDisplayed()
        compose.onNodeWithTag("media-profile-item_video-preset").performClick()
        compose.runOnIdle { assertEquals(MediaProfileRoutes.EDITOR, route) }
        compose.onNodeWithTag("media-profile-editor-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(MediaProfileRoutes.LIBRARY, route) }
        compose.onNodeWithText("Video Profiles").assertIsDisplayed()
    }

    @Test fun disabledToolWithoutModelStillOpensProfileLibrary() {
        compose.setContent {
            ProfileTestNavigation(ToolSettings(system = mapOf("generate_image" to SystemToolConfig(enabled = false))))
        }
        compose.onNodeWithTag("media_profile_picker_generate_image").assertIsNotEnabled()
        compose.onNodeWithTag("media_profile_library_generate_image").assertIsEnabled().performClick()
        compose.onNodeWithText("Image Profiles").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add").assertIsEnabled()
    }
    @Test fun builtInMediaTabAllowsIndependentCustomWithoutModelAndKeepsOtherToolsSeparate() {
        var saved: SystemToolConfig? = null
        val standalone = MediaRequestProfile("standalone", "Independent", requiresModel = false)
        val required = MediaRequestProfile("required", "Bound", requiresModel = true)
        val config = SystemToolConfig().withMediaProfiles(false,
            mapOf(standalone.id to standalone, required.id to required))
        compose.setContent {
            var settings by remember {
                mutableStateOf(ToolSettings(system = mapOf("generate_image" to config)))
            }
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ToolsEditor(providers = emptyList(), settings = settings, onSave = { key, update ->
                    settings = settings.copy(system = settings.system + (key to update))
                    if (key == "generate_image") saved = update
                }, onBack = {})
            }
        }
        compose.onNodeWithTag("built-in-tools-media-tab").assertIsDisplayed()
        compose.onNodeWithTag("built-in-tools-others-tab").assertIsDisplayed().performClick()
        compose.onNodeWithText("Tools Search").assertIsDisplayed()
        compose.onNodeWithText("Image Generation").assertDoesNotExist()
        compose.onNodeWithTag("built-in-tools-media-tab").performClick()
        compose.onNodeWithTag("media_profile_picker_generate_image").assertIsEnabled().performClick()
        compose.onNodeWithText("Independent · Custom").performClick()
        compose.runOnIdle {
            assertEquals("", saved?.modelId)
            assertEquals("", saved?.providerId)
            assertEquals(standalone.id, saved?.activeImageRequestProfileId)
        }
        compose.onNodeWithTag("media_profile_picker_generate_image").performClick()
        compose.onNodeWithText("Bound · Custom").assertDoesNotExist()
    }

    @Test fun profileEditorExposesRequireModelSwitchOnlyForCustom() {
        val custom = MediaRequestProfile("custom", "Custom preset", mode = MediaGenerationMode.CUSTOM)
        var savedConfig: SystemToolConfig? = null
        compose.setContent {
            ProfileTestNavigation(
                ToolSettings(system = mapOf("generate_image" to SystemToolConfig(
                    imageRequestProfiles = mapOf(custom.id to custom)
                ))),
                onSaved = { savedConfig = it }
            )
        }
        compose.onNodeWithTag("media_profile_library_generate_image").performClick()
        compose.onNodeWithTag("media-profile-item_custom").performClick()
        compose.onNodeWithTag("media-profile-require-model").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            assertEquals(false, savedConfig?.imageRequestProfiles?.get("custom")?.requiresModel)
        }
    }

    @Test fun choosingIndependentProfileDeselectsExistingModelWithoutWritingMetadata() {
        val independent = MediaRequestProfile("standalone", "Independent", requiresModel = false)
        val provider = ModelProvider("p", "Provider", ModelApiType.OPENAI,
            baseUrl = "https://example.test/v1", config = ProviderConfiguration(
                modelConfigs = mapOf("image-model" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION))
            ))
        var metadataWrites = 0
        var saved: SystemToolConfig? = null
        val starting = SystemToolConfig(providerId = provider.id, modelId = "image-model")
            .withMediaProfiles(false, mapOf(independent.id to independent))
        compose.setContent {
            var settings by remember {
                mutableStateOf(ToolSettings(system = mapOf("generate_image" to starting)))
            }
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ToolsEditor(providers = listOf(provider), settings = settings,
                    onSave = { key, update ->
                        settings = settings.copy(system = settings.system + (key to update))
                        if (key == "generate_image") saved = update
                    },
                    onSaveModelConfiguration = { _, _, _ -> metadataWrites++ }, onBack = {})
            }
        }
        compose.onNodeWithTag("media_profile_picker_generate_image").performClick()
        compose.onNodeWithText("Independent · Custom").performClick()
        compose.runOnIdle {
            assertEquals("", saved?.providerId)
            assertEquals("", saved?.modelId)
            assertEquals(independent.id, saved?.activeImageRequestProfileId)
            assertEquals(0, metadataWrites)
        }
    }

}
