package org.starfall.generative.shared.model.generation

/** A transport can close cleanly even if the model never sent its protocol completion marker. */
internal const val UNEXPECTED_STREAM_END = "Stream ended unexpectedly."
internal const val OUTPUT_TOKEN_LIMIT_REACHED = "Output token limit reached."

internal fun requireStreamCompleted(completed: Boolean) {
    check(completed) { UNEXPECTED_STREAM_END }
}
