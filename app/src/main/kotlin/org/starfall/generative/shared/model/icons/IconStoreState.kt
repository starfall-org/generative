package org.starfall.generative.shared.model.icons

import kotlinx.serialization.Serializable

/** Serializable metadata for every app-owned icon and its matching rules. */
@Serializable
data class IconStoreState(
    val rules: List<IconRule> = emptyList(),
    val assetLabels: Map<String, String> = emptyMap(),
    val automatic: Map<String, String> = emptyMap(),
    val variants: Map<String, String> = emptyMap(),
    val fileNames: Map<String, String> = emptyMap(),
    val files: List<String> = emptyList()
)
