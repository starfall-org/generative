package org.starfall.generative.database.room.dao

import androidx.room.*
import org.starfall.generative.database.room.entities.CustomHttpToolEntity

@Dao
interface CustomHttpToolDao {
    @Query("SELECT * FROM custom_http_tools ORDER BY rowid")
    suspend fun all(): List<CustomHttpToolEntity>

    @Upsert
    suspend fun put(tool: CustomHttpToolEntity)

    @Query("DELETE FROM custom_http_tools WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM custom_http_tools")
    suspend fun clear()
}
