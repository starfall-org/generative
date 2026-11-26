package org.starfall.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.generative.integrations.modelapis.geminicli.geminiCliQuotaModels

class GeminiCliQuotaModelsTest {
    @Test fun listsDistinctModelIdsReportedForTheProject() {
        val quota = Json.parseToJsonElement("""{
            "buckets": [
                {"modelId": "gemini-project-pro", "remainingFraction": 0},
                {"modelId": "models/gemini-project-pro", "tokenType": "REQUESTS"},
                {"modelId": "gemini-project-flash"},
                {"tokenType": "REQUESTS"},
                {"modelId": " "}
            ]
        }""").jsonObject
        assertEquals(listOf("gemini-project-pro", "gemini-project-flash"), geminiCliQuotaModels(quota).map { it.id })
    }

    @Test fun quotaWithoutModelIdsProducesAnEmptyCatalog() {
        val quota = Json.parseToJsonElement("""{"buckets":[{"remainingFraction": 1}]}""").jsonObject
        assertEquals(emptyList<String>(), geminiCliQuotaModels(quota).map { it.id })
    }
}
