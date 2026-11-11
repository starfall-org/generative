package org.starfall.generative.shared.model.generation

import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity

/** Events emitted across the model/tool orchestration boundary during a generation turn. */
sealed interface GenerationEvent {
    data class PreparingAttachments(val active: Boolean) : GenerationEvent
    data class Text(val text: String) : GenerationEvent
    data class Reasoning(val text: String, val signature: String? = null) : GenerationEvent
    data class Tool(val activity: ToolActivity) : GenerationEvent
    data class Usage(val usage: TokenUsage) : GenerationEvent
    /** A user message inserted between a completed tool turn and the next model turn. */
    data class UserMessage(val message: StoredMessage) : GenerationEvent
}
