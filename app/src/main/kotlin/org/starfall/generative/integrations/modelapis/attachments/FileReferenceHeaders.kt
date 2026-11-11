package org.starfall.generative.integrations.modelapis.attachments

import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider

/** Older Anthropic-compatible gateways still require the Files API beta on Messages requests. */
internal fun ModelProvider.withFileReferenceHeaders(): ModelProvider {
    if (type != ModelApiType.ANTHROPIC) return this
    val key = config.headers.keys.firstOrNull { it.equals("anthropic-beta", true) } ?: "anthropic-beta"
    val values = config.headers[key].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    return copy(config = config.copy(headers = config.headers + (key to (values + "files-api-2025-04-14").distinct().joinToString(","))))
}
