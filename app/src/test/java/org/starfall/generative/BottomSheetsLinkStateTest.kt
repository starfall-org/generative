package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.features.home.BottomSheetsLinkState
import org.starfall.generative.features.home.ModelSheetPage
import org.starfall.generative.features.home.QuickActionsPage

class BottomSheetsLinkStateTest {
    @Test fun oneNullablePageRepresentsTheEntireQuickActionsSheet() {
        val state = BottomSheetsLinkState()
        assertFalse(state.showAddMenu)
        state.openAddMenu()
        assertEquals(QuickActionsPage.ROOT, state.quickActionsPage)
        assertTrue(state.showAddMenu)
        state.openInstructions()
        assertEquals(QuickActionsPage.INSTRUCTIONS, state.quickActionsPage)
        state.backToQuickActions()
        assertEquals(QuickActionsPage.ROOT, state.quickActionsPage)
        state.dismissAddMenu()
        assertNull(state.quickActionsPage)
        assertFalse(state.showAddMenu)
    }

    @Test fun pickerBackDependsOnlyOnItsPageVariant() {
        val state = BottomSheetsLinkState()
        state.openModelPicker()
        assertEquals(ModelSheetPage.MODEL_PICKER, state.modelSheetPage)
        assertEquals(ModelSheetPage.MODEL_PICKER, state.renderedModelSheetPage)
        state.backFromModelPicker()
        assertNull(state.modelSheetPage)

        state.openSelectedModelOverview()
        state.openModelOptions()
        assertEquals(ModelSheetPage.MODEL_OPTIONS, state.modelSheetPage)
        assertEquals(ModelSheetPage.MODEL_OPTIONS, state.renderedModelSheetPage)
        state.openSelectedModelOverview()
        assertEquals(ModelSheetPage.SELECTED_MODEL, state.modelSheetPage)
        state.switchToModelPicker()
        assertEquals(ModelSheetPage.MODEL_PICKER_FROM_OVERVIEW, state.modelSheetPage)
        assertEquals(ModelSheetPage.MODEL_PICKER, state.renderedModelSheetPage)
        state.backFromModelPicker()
        assertEquals(ModelSheetPage.SELECTED_MODEL, state.modelSheetPage)
        assertEquals(ModelSheetPage.SELECTED_MODEL, state.renderedModelSheetPage)
        state.dismissModelSheet()
        assertNull(state.renderedModelSheetPage)
    }
}
