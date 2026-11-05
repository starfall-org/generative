package org.starfall.generative.shared.model.provider

import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.bearerHeaderValue

/** Small, independently persisted edits; never save the editor's unrelated unsaved fields. */
sealed interface ProviderAuxiliaryChange {
    data class SetMultipleApiKeys(val enabled: Boolean, val newlyImported: List<ProviderApiKey>) : ProviderAuxiliaryChange
    data class SaveApiKey(val entry: ProviderApiKey, val selectedValue: String? = null) : ProviderAuxiliaryChange
    data class DeleteApiKey(val id: String, val selectedValue: String? = null) : ProviderAuxiliaryChange
    data class SelectApiKey(val value: String) : ProviderAuxiliaryChange
    data class AddHeader(val name: String, val value: String) : ProviderAuxiliaryChange
    data class DeleteHeader(val name: String) : ProviderAuxiliaryChange
}

fun ModelProvider.withAuxiliaryChange(change: ProviderAuxiliaryChange): ModelProvider = when (change) {
    is ProviderAuxiliaryChange.SetMultipleApiKeys -> {
        val existingIds = config.apiKeys.mapTo(hashSetOf()) { it.id }
        copy(config = config.copy(
            multipleApiKeys = change.enabled,
            apiKeys = config.apiKeys + change.newlyImported.filter { it.id !in existingIds }
        ))
    }
    is ProviderAuxiliaryChange.SaveApiKey -> {
        val keys = config.apiKeys
        val updated = if (keys.any { it.id == change.entry.id }) {
            keys.map { if (it.id == change.entry.id) change.entry else it }
        } else keys + change.entry
        copy(config = config.copy(apiKeys = updated)).withSelectedKey(change.selectedValue)
    }
    is ProviderAuxiliaryChange.DeleteApiKey ->
        copy(config = config.copy(apiKeys = config.apiKeys.filterNot { it.id == change.id }))
            .withSelectedKey(change.selectedValue)
    is ProviderAuxiliaryChange.SelectApiKey -> withSelectedKey(change.value)
    is ProviderAuxiliaryChange.AddHeader -> {
        val name = change.name.trim()
        // An additive edit must never replace a previously stored header, including case variants.
        if (name.isBlank() || config.headers.keys.any { it.equals(name, ignoreCase = true) }) this
        else copy(config = config.copy(headers = config.headers + (name to change.value)))
    }
    is ProviderAuxiliaryChange.DeleteHeader -> copy(config = config.copy(
        headers = config.headers.filterKeys { !it.equals(change.name, ignoreCase = true) }
    ))
}

/** Null means the editor did not change the selection; empty means explicitly clear it. */
private fun ModelProvider.withSelectedKey(value: String?): ModelProvider {
    if (value == null) return this
    val normalized = if (auth.method == AuthMethod.BEARER_TOKEN && value.isNotBlank()) {
        bearerHeaderValue(auth.key.orEmpty().ifBlank { "Authorization" }, value)
    } else value.trim()
    return copy(auth = auth.copy(value = normalized))
}
