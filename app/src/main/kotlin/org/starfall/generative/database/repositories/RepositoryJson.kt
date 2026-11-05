package org.starfall.generative.database.repositories

import kotlinx.serialization.json.Json

internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
