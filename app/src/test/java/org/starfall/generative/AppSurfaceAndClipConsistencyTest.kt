package org.starfall.generative

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.chatoptions.ui.InstructionsTab
import org.starfall.generative.features.providers.list.logic.ProviderRootItem
import org.starfall.generative.features.providers.list.ui.ProviderRootCard
import org.starfall.generative.features.home.drawer.ui.ConversationItem
import org.starfall.generative.features.home.shared.ui.QuickActionsSheetContent
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SavedInstruction
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.theme.GenerativeTheme
import org.starfall.generative.shared.ui.theme.ThemePresets
import org.starfall.generative.shared.ui.layout.LocalItemTransparency
import org.starfall.generative.shared.ui.layout.appearanceContainerColor
import org.starfall.generative.shared.ui.layout.appearanceContainerBorder
import org.starfall.generative.shared.ui.layout.modalContainerColor
import org.starfall.generative.shared.ui.layout.groupedItemShape
import org.starfall.generative.shared.ui.layout.appTabRowColor
import org.starfall.generative.shared.ui.layout.appBarSurfaceColor
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w480dp-h960dp-xhdpi")
class AppSurfaceAndClipConsistencyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appBarsFollowComponentTransparencyAndKeepAmoledTransparent() {
        val surface = Color(0xFF273244)
        for (transparency in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val actual = appBarSurfaceColor(surface, transparency, amoled = false)
            assertEquals(1f - transparency, actual.alpha, 1f / 255f)
            assertEquals(surface.copy(alpha = 1f - transparency), actual)
            assertEquals(Color.Transparent, appBarSurfaceColor(surface, transparency, amoled = true))
        }
        assertEquals(surface, appBarSurfaceColor(surface, -0.5f, amoled = false))
        assertEquals(0f, appBarSurfaceColor(surface, 1.5f, amoled = false).alpha, 0.001f)
    }

    @Test
    fun cardAppearance_keepsScreensOpaqueAndItemsTranslucentOrOutlined() {
        var darkTheme by mutableStateOf(false)
        var amoledMode by mutableStateOf(false)
        var colorSchemeName by mutableStateOf(ThemePresets.first().name)
        var background = Color.Unspecified
        var prominentColor = Color.Unspecified
        var nestedColor = Color.Unspecified
        var tabColor = Color.Unspecified
        var prominentBorder: BorderStroke? = null
        var nestedBorder: BorderStroke? = null
        var outlineVariant = Color.Unspecified

        compose.setContent {
            GenerativeTheme(
                darkTheme = darkTheme,
                amoledMode = amoledMode,
                colorSchemeName = colorSchemeName
            ) {
                background = MaterialTheme.colorScheme.background
                outlineVariant = MaterialTheme.colorScheme.outlineVariant
                prominentColor = prominentContainerColor()
                nestedColor = nestedContainerColor()
                tabColor = appTabRowColor()
                prominentBorder = prominentContainerBorder()
                nestedBorder = nestedContainerBorder()
            }
        }

        for (preset in ThemePresets) {
            colorSchemeName = preset.name

            // Light theme: prominent fill, no border.
            darkTheme = false
            amoledMode = false
            compose.waitForIdle()
            assertNotEquals("Light ${preset.name}: prominent card must contrast with background", background, prominentColor)
            assertNotEquals("Light ${preset.name}: nested container must contrast with parent card", prominentColor, nestedColor)
            assertEquals(1f, background.alpha, 0.001f)
            assertEquals(0.5f, prominentColor.alpha, 1f / 255f)
            assertEquals("TabRow uses the same translucent fill as other items", prominentColor, tabColor)
            assertEquals(0.5f, nestedColor.alpha, 1f / 255f)
            assertNull("Light ${preset.name}: prominent card must not draw an AMOLED border", prominentBorder)
            assertNull("Light ${preset.name}: nested container must not draw an AMOLED border", nestedBorder)

            // Standard Dark theme: prominent fill, no border.
            darkTheme = true
            amoledMode = false
            compose.waitForIdle()
            assertNotEquals("Dark ${preset.name}: prominent card must contrast with background", background, prominentColor)
            assertNotEquals("Dark ${preset.name}: nested container must contrast with parent card", prominentColor, nestedColor)
            assertEquals(1f, background.alpha, 0.001f)
            assertEquals(0.5f, prominentColor.alpha, 1f / 255f)
            assertEquals("TabRow uses the same translucent fill as other items", prominentColor, tabColor)
            assertEquals(0.5f, nestedColor.alpha, 1f / 255f)
            assertNull("Dark ${preset.name}: prominent card must not draw an AMOLED border", prominentBorder)
            assertNull("Dark ${preset.name}: nested container must not draw an AMOLED border", nestedBorder)

            // AMOLED Dark theme: pure black screen, fully transparent item fills and outlined items.
            darkTheme = true
            amoledMode = true
            compose.waitForIdle()
            assertEquals("AMOLED ${preset.name}: background must be black", Color.Black, background)
            assertEquals("AMOLED ${preset.name}: prominent item fill must be transparent", Color.Transparent, prominentColor)
            assertEquals("AMOLED ${preset.name}: nested item fill must be transparent", Color.Transparent, nestedColor)
            assertEquals("AMOLED TabRow must be transparent", Color.Transparent, tabColor)
            assertNotNull("AMOLED ${preset.name}: prominent card must draw a border", prominentBorder)
            assertNotNull("AMOLED ${preset.name}: nested container must draw a border", nestedBorder)
            assertEquals(BorderStroke(1.dp, outlineVariant), prominentBorder)
            assertEquals(BorderStroke(1.dp, outlineVariant), nestedBorder)
        }
    }

    @Test
    fun sharedAppearancePoliciesFollowTransparencyAndKeepProfileSurfaceOpaque() {
        var transparency by mutableStateOf(0.5f)
        var dark by mutableStateOf(false)
        var amoled by mutableStateOf(false)
        val accentBase = Color(0xFF6482A6)
        val modalBase = Color(0xAA345678)
        var accent = Color.Unspecified
        var boosted = Color.Unspecified
        var modal = Color.Unspecified
        var border: BorderStroke? = null
        compose.setContent {
            GenerativeTheme(darkTheme = dark, amoledMode = amoled, dynamicColor = false) {
                CompositionLocalProvider(LocalItemTransparency provides transparency) {
                    accent = appearanceContainerColor(accentBase)
                    boosted = prominentContainerColor(opacityBoost = 0.42f)
                    modal = modalContainerColor(modalBase)
                    border = appearanceContainerBorder(1.5.dp)
                }
            }
        }
        for (t in listOf(0f, 0.5f, 1f)) {
            transparency = t
            compose.waitForIdle()
            assertEquals(1f - t, accent.alpha, 1f / 255f)
            assertEquals((1f - t + 0.42f).coerceAtMost(1f), boosted.alpha, 1f / 255f)
            assertEquals(modalBase.copy(alpha = 1f), modal)
            assertNull(border)
        }
        dark = true
        amoled = true
        compose.waitForIdle()
        assertEquals(Color.Transparent, accent)
        assertEquals(Color.Transparent, boosted)
        assertEquals(Color.Black, modal)
        assertNotNull(border)
        assertEquals(1.5.dp, border!!.width)
    }

    @Test
    fun appearanceItemSliderDoesNotMakeScreenTransparent() {
        var transparency by mutableStateOf(0.5f)
        var screen = Color.Unspecified
        var card = Color.Unspecified
        var nested = Color.Unspecified
        compose.setContent {
            GenerativeTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalItemTransparency provides transparency) {
                    screen = MaterialTheme.colorScheme.background
                    card = prominentContainerColor()
                    nested = nestedContainerColor()
                }
            }
        }
        for (value in listOf(0f, 0.25f, 0.5f, 1f)) {
            transparency = value
            compose.waitForIdle()
            assertEquals(1f, screen.alpha, 0.001f)
            assertEquals(1f - value, card.alpha, 1f / 255f)
            assertEquals(1f - value, nested.alpha, 1f / 255f)
        }
    }

    @Test
    fun groupedItemShape_assignsOuterAndInnerCornerRadiiByPosition() {
        val density = Density(1f)
        val size = Size(200f, 80f)

        val single = groupedItemShape(index = 0, count = 1)
        assertEquals(24f, single.topStart.toPx(size, density), 0.01f)
        assertEquals(24f, single.bottomEnd.toPx(size, density), 0.01f)

        val top = groupedItemShape(index = 0, count = 3)
        val middle = groupedItemShape(index = 1, count = 3)
        val bottom = groupedItemShape(index = 2, count = 3)

        assertEquals(24f, top.topStart.toPx(size, density), 0.01f)
        assertEquals(6f, top.bottomStart.toPx(size, density), 0.01f)
        assertEquals(6f, middle.topStart.toPx(size, density), 0.01f)
        assertEquals(6f, middle.bottomEnd.toPx(size, density), 0.01f)
        assertEquals(6f, bottom.topEnd.toPx(size, density), 0.01f)
        assertEquals(24f, bottom.bottomStart.toPx(size, density), 0.01f)
    }

    @Test
    fun roundedInteractiveComponents_ignoreClicksOutsideCornerArcs() {
        val provider = ModelProvider(
            id = "p1",
            name = "Test Provider",
            type = ModelApiType.OPENAI,
            baseUrl = "https://api.openai.com/v1"
        )
        val instructions = listOf(
            SavedInstruction(id = "s1", name = "First Instruction", content = "Be concise."),
            SavedInstruction(id = "s2", name = "Second Instruction", content = "Show code.")
        )
        val chatItem = ConversationListItem(
            id = "c1",
            title = "Sample Chat",
            createdAt = 1L,
            updatedAt = 2L,
            tokenCount = null,
            providerId = "p1",
            modelId = "gpt-4o",
            profileId = null,
            reasoningEffort = null,
            hasMessages = true,
            hasSummary = false
        )

        var providerClicks = 0
        var previewedInstructionId: String? = null
        var quickPhotoClicks = 0
        var chatClicks = 0
        var mode by mutableStateOf(0)

        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                when (mode) {
                    0 -> Box(Modifier.size(200.dp)) {
                        ProviderRootCard(
                            item = ProviderRootItem.ProviderItem(provider),
                            members = emptyList(),
                            isGrid = true,
                            isDropTarget = false,
                            onEditProvider = { providerClicks++ },
                            onMoveProvider = {},
                            onDeleteProvider = {},
                            onOpenGroup = {}
                        )
                    }
                    1 -> InstructionsTab(
                        library = InstructionLibrary(instructions = instructions),
                        onEditInstruction = {},
                        onPreviewInstruction = { previewedInstructionId = it.id },
                        onDeleteInstruction = {},
                        onReorderInstructions = {}
                    )
                    2 -> QuickActionsSheetContent(
                        onPickImage = { quickPhotoClicks++ },
                        onPickDocument = {},
                        onTakePhoto = {},
                        onCreateImage = {},
                        onCreateVideo = {},
                        onOpenFeatureTools = {},
                        onOpenMcpConnectors = {},
                        onDismiss = {}
                    )
                    else -> ConversationItem(
                        chat = chatItem,
                        currentId = "c1",
                        generatingIds = emptySet(),
                        selecting = false,
                        checked = false,
                        pinned = false,
                        onClick = { chatClicks++ },
                        onLongClick = {},
                        onSelect = {},
                        onPin = {},
                        onRename = {},
                        onDelete = {},
                        onMove = {},
                        showContextMenu = false,
                        onContextMenuDismiss = {},
                        modifier = Modifier.testTag("conv_item")
                    )
                }
            }
        }

        // 1. ProviderRootCard (20.dp rounded corners in grid mode).
        compose.onNodeWithTag("provider_p1").performTouchInput {
            click(Offset(1.5.dp.toPx(), 1.5.dp.toPx()))
        }
        compose.waitForIdle()
        assertEquals("Click outside 20.dp provider card corner should not fire", 0, providerClicks)
        compose.onNodeWithTag("provider_p1").performTouchInput {
            click(center)
        }
        compose.waitForIdle()
        assertEquals(1, providerClicks)

        // 2. Instructions use the same 16.dp list-card corners as providers and MCP servers.
        mode = 1
        compose.waitForIdle()
        compose.onNodeWithText("First Instruction").performTouchInput {
            // Note: onNodeWithText resolves to the merged clickable Surface semantics node.
            click(Offset(2.dp.toPx(), 2.dp.toPx()))
        }
        compose.waitForIdle()
        assertNull("Click outside the instruction card corner should not fire", previewedInstructionId)
        compose.onNodeWithText("First Instruction").performTouchInput {
            click(center)
        }
        compose.waitForIdle()
        assertEquals("Click inside the instruction card should fire", "s1", previewedInstructionId)

        // 3. QuickActionsSheet tile (24.dp rounded corners).
        mode = 2
        compose.waitForIdle()
        compose.onNodeWithText("Photos").performTouchInput {
            click(Offset(2.dp.toPx(), 2.dp.toPx()))
        }
        compose.waitForIdle()
        assertEquals("Click outside 24.dp quick action tile corner should not fire", 0, quickPhotoClicks)
        compose.onNodeWithText("Photos").performTouchInput {
            click(center)
        }
        compose.waitForIdle()
        assertEquals(1, quickPhotoClicks)

        // 4. ConversationItem (12.dp rounded corners with combinedClickable).
        mode = 3
        compose.waitForIdle()
        compose.onNodeWithTag("conv_item").performTouchInput {
            click(Offset(1.dp.toPx(), 1.dp.toPx()))
        }
        compose.waitForIdle()
        assertEquals("Click outside 12.dp conversation item corner should not fire", 0, chatClicks)
        compose.onNodeWithTag("conv_item").performTouchInput {
            click(center)
        }
        compose.waitForIdle()
        assertEquals(1, chatClicks)
    }
}
