package org.starfall.generative.features.providers.shared.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import org.starfall.generative.shared.model.provider.ProviderApiKey

/** Provider-editor draft; credentials are persisted only when the provider is saved. */
internal class ProviderApiKeysState(current: String, enabled: Boolean, saved: List<ProviderApiKey>) {
    var currentKey by mutableStateOf(current)
    var enabled by mutableStateOf(enabled)
    var entries by mutableStateOf(saved)
        private set

    fun selectedId(identity: (String) -> String): String? =
        entries.firstOrNull { currentKey.isNotBlank() && identity(it.value) == identity(currentKey) }?.id

    fun ensureCurrent(identity: (String) -> String) {
        if (currentKey.isNotBlank() && selectedId(identity) == null) {
            entries = entries + ProviderApiKey(UUID.randomUUID().toString(), value = currentKey.trim())
        }
    }

    fun save(
        id: String?,
        label: String,
        value: String,
        identity: (String) -> String,
        blankError: String = "Enter an API key.",
        duplicateError: String = "This API key already exists."
    ): ProviderApiKey {
        require(value.isNotBlank()) { blankError }
        require(entries.none { it.id != id && identity(it.value) == identity(value) }) { duplicateError }
        val selected = selectedId(identity) == id && id != null
        val entry = ProviderApiKey(id ?: UUID.randomUUID().toString(), label.trim(), value.trim())
        entries = if (id == null) entries + entry else entries.map { if (it.id == id) entry else it }
        if (selected || currentKey.isBlank()) currentKey = entry.value
        return entry
    }

    fun delete(id: String, identity: (String) -> String) {
        val selected = selectedId(identity) == id
        entries = entries.filterNot { it.id == id }
        if (selected) currentKey = entries.firstOrNull()?.value.orEmpty()
    }
}

internal fun maskedProviderApiKey(value: String): String {
    val key = value.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "")
    return when {
        key.length > 8 -> "${key.take(4)}••••${key.takeLast(4)}"
        key.length > 2 -> "${key.first()}••••${key.last()}"
        else -> "••••"
    }
}
