package org.starfall.generative.features.home.shared.ui

import androidx.compose.runtime.staticCompositionLocalOf
import org.starfall.generative.shared.model.InstructionLibrary

data class InstructionControls(
    /** The instruction library with the selection of the chat that is open. */
    val library: InstructionLibrary = InstructionLibrary(),
    val setInstructionEnabled: (instructionId: String, enabled: Boolean) -> Unit = { _, _ -> },
    val openInstructions: () -> Unit = {},
    val editInstruction: (instructionId: String) -> Unit = {}
)

val LocalInstructionControls = staticCompositionLocalOf { InstructionControls() }
