package org.starfall.generative.database.room.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonPrimitive
import org.starfall.generative.shared.model.language.LanguageModel

internal object ConfigurationMigration10Catalogs {
    fun copy(db: SupportSQLiteDatabase) {
        db.query("SELECT id, modelsJson FROM provider_models_v10 ORDER BY rowid").use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val source = migrationValue(db, "provider_catalog", id, "models", rows.getString(1))
                val hasProvider = db.query("SELECT 1 FROM model_providers WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }
                if (!hasProvider) {
                    source.retain("orphan_catalog")
                    continue
                }
                val models = source.cache<List<LanguageModel>>() ?: continue
                // A present empty legacy cache is different from no cache row at all.
                migrationStructured(db, "provider_catalog", id, "cache_state", JsonPrimitive("READY"))
                models.forEachIndexed { ordinal, model ->
                    db.execSQL("""
                        INSERT INTO provider_catalog_models(providerId, ordinal, modelId, displayName, icon, catalogProviderId,
                            inputText, inputImage, inputVideo, inputEmbed, inputAudio, inputOthers,
                            outputText, outputImage, outputVideo, outputEmbed, outputAudio, outputOthers)
                        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(), migrationArgs(id, ordinal, model.id, model.displayName, model.icon, model.providerId,
                        migrationBool(model.inputCapabilities.text), migrationBool(model.inputCapabilities.image),
                        migrationBool(model.inputCapabilities.video), migrationBool(model.inputCapabilities.embed),
                        migrationBool(model.inputCapabilities.audio), model.inputCapabilities.others,
                        migrationBool(model.outputCapabilities.text), migrationBool(model.outputCapabilities.image),
                        migrationBool(model.outputCapabilities.video), migrationBool(model.outputCapabilities.embed),
                        migrationBool(model.outputCapabilities.audio), model.outputCapabilities.others))
                    model.modelTools.forEachIndexed { toolOrdinal, tool ->
                        db.execSQL("INSERT INTO provider_catalog_tools(providerId, modelOrdinal, ordinal, toolName) VALUES(?, ?, ?, ?)",
                            migrationArgs(id, ordinal, toolOrdinal, tool))
                    }
                }
                migrationCheckCount(db, "provider_catalog_models", models.size, "providerId", id)
                migrationCheckCount(db, "provider_catalog_tools", models.sumOf { it.modelTools.size }, "providerId", id)
            }
        }
    }
}
